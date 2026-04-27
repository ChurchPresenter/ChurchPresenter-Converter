package converter

import java.io.File
import java.nio.charset.Charset

data class SongInfo(
    val file: File,
    val title: String,
    val lyricsText: String
)

data class DuplicateGroup(
    val songs: List<SongInfo>,
    val reason: String,
    /** Pairwise similarity of each song to the first song in the group (1.0 for the first). */
    val similarities: List<Double> = emptyList()
)

object DuplicateFinder {

    fun findDuplicates(directory: File, threshold: Double = 0.9, matchByNumber: Boolean = false, matchByTitle: Boolean = true): List<DuplicateGroup> {
        val songs = scanSongs(directory)
        if (songs.size < 2) return emptyList()

        val groups = mutableListOf<DuplicateGroup>()
        val assigned = mutableSetOf<Int>()

        // Pre-compute normalized lyrics and bigrams for all songs (used by multiple passes)
        val allNormalized = songs.map { normalize(it.lyricsText) }
        val allBigrams = allNormalized.map { bigrams(it) }

        // Pass 1 (optional): same song number across different parent folders
        // Only groups songs if their lyrics similarity also meets the threshold
        if (matchByNumber) {
            val byNumberOnly = songs.withIndex()
                .filter { extractSongNumber(it.value.file.name) != null }
                .groupBy { extractSongNumber(it.value.file.name)!! }
            for ((_, entries) in byNumberOnly) {
                val distinctFolders = entries.map { it.value.file.parentFile.canonicalPath }.distinct()
                if (entries.size > 1 && distinctFolders.size > 1) {
                    val firstIdx = entries.first().index
                    // Filter to only entries whose lyrics are similar enough to the first
                    val similar = entries.filter { e ->
                        e.index == firstIdx || similarityFromBigrams(
                            allNormalized[firstIdx], allBigrams[firstIdx],
                            allNormalized[e.index], allBigrams[e.index]
                        ) >= threshold
                    }
                    if (similar.size > 1) {
                        val sims = similar.map { e ->
                            similarityFromBigrams(
                                allNormalized[firstIdx], allBigrams[firstIdx],
                                allNormalized[e.index], allBigrams[e.index]
                            )
                        }
                        groups.add(DuplicateGroup(similar.map { it.value }, "Same song number", sims))
                        similar.forEach { assigned.add(it.index) }
                    }
                }
            }
        }

        // Pass 2 (optional): exact title matches (normalized) for remaining songs
        val remainingAfterNumbers = songs.withIndex().filter { it.index !in assigned }
        val byTitle = if (matchByTitle) remainingAfterNumbers.groupBy { normalize(it.value.title) } else emptyMap()
        for ((_, entries) in byTitle) {
            if (entries.size > 1) {
                val firstIdx = entries.first().index
                val sims = entries.map { e ->
                    similarityFromBigrams(
                        allNormalized[firstIdx], allBigrams[firstIdx],
                        allNormalized[e.index], allBigrams[e.index]
                    )
                }
                groups.add(DuplicateGroup(entries.map { it.value }, "Same title", sims))
                entries.forEach { assigned.add(it.index) }
            }
        }

        // Pass 3: content similarity for remaining songs
        val remaining = songs.withIndex().filter { it.index !in assigned }
        // Map from remaining list index to original song index
        val remNorm = remaining.map { allNormalized[it.index] }
        val remBigrams = remaining.map { allBigrams[it.index] }

        for (i in remaining.indices) {
            if (remaining[i].index in assigned) continue
            val groupIndices = mutableListOf(i)
            val groupSims = mutableListOf(1.0)
            for (j in i + 1 until remaining.size) {
                if (remaining[j].index in assigned) continue
                // Require similarity to ALL existing members of the group
                val sims = groupIndices.map { gi ->
                    similarityFromBigrams(remNorm[gi], remBigrams[gi], remNorm[j], remBigrams[j])
                }
                val minSim = sims.min()
                if (minSim >= threshold) {
                    groupIndices.add(j)
                    // Store similarity to the first song (for display)
                    groupSims.add(sims.first())
                    assigned.add(remaining[j].index)
                }
            }
            if (groupIndices.size > 1) {
                assigned.add(remaining[i].index)
                val groupSongs = groupIndices.map { remaining[it] }
                groups.add(DuplicateGroup(groupSongs.map { it.value }, "Similar lyrics", groupSims))
            }
        }

        return groups.sortedByDescending { it.songs.size }
    }

    fun scanSongs(directory: File): List<SongInfo> {
        return directory.walkTopDown()
            .filter { it.isFile && it.extension.equals("song", ignoreCase = true) }
            .mapNotNull { file ->
                try {
                    parseSong(file)
                } catch (_: Exception) {
                    null
                }
            }
            .toList()
    }

    /**
     * Given duplicate groups and a "keep" folder, returns the list of files
     * that should be deleted (duplicates NOT inside the keep folder).
     * If a group has no song in the keep folder, nothing from that group is deleted.
     */
    fun resolveDeletes(groups: List<DuplicateGroup>, keepFolder: File): List<File> {
        val keepPath = keepFolder.canonicalPath
        return groups.flatMap { group ->
            val kept = group.songs.filter { it.file.canonicalPath.startsWith(keepPath) }
            if (kept.isEmpty()) {
                emptyList()
            } else {
                // Delete songs outside the keep folder, plus all but the first inside the keep folder
                val outsiders = group.songs.filter { !it.file.canonicalPath.startsWith(keepPath) }.map { it.file }
                val extraInsiders = kept.drop(1).map { it.file }
                outsiders + extraInsiders
            }
        }
    }

    /** Extract leading number from filenames like "0407 - Title.song" */
    private fun extractSongNumber(filename: String): String? {
        val match = Regex("""^(\d+)\s*-\s""").find(filename)
        return match?.groupValues?.get(1)
    }

    private fun parseSong(file: File): SongInfo {
        val content = readFileWithFallback(file)
        val lines = content.lines()

        var title = ""
        val lyricsLines = mutableListOf<String>()
        var inFrontmatter = false
        var frontmatterDone = false
        var foundPrimary = false

        for (line in lines) {
            val trimmed = line.trim()
            if (!frontmatterDone) {
                if (trimmed == "---") {
                    inFrontmatter = !inFrontmatter
                    if (!inFrontmatter) frontmatterDone = true
                }
                continue
            }
            if (trimmed.equals("[Primary]", ignoreCase = true)) {
                foundPrimary = true
                continue
            }
            if (trimmed.startsWith("title:", ignoreCase = true) && title.isEmpty()) {
                title = trimmed.substringAfter(":").trim()
                continue
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (trimmed.equals("[Secondary]", ignoreCase = true)) break
                continue
            }
            if (foundPrimary && trimmed.isNotEmpty() && !isStructuralMarker(trimmed)) {
                lyricsLines.add(trimmed)
            }
        }

        if (title.isEmpty()) title = file.nameWithoutExtension
        return SongInfo(file, title, deduplicateBlocks(lyricsLines).joinToString("\n"))
    }

    private val structuralMarkerRegex = Regex(
        """^\{.*\}[.:]?$""" // curly-brace markers like {Припев:}, {Chorus}
    )
    private val bareLabelRegex = Regex(
        """^(припев|куплет|хор|вступление|окончание|бридж|кода|chorus|verse|bridge|intro|outro|refrain|coda)\s*\d*\s*[.:]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private fun isStructuralMarker(line: String): Boolean {
        return structuralMarkerRegex.matches(line) || bareLabelRegex.matches(line)
    }

    /** Remove repeated multi-line blocks (e.g. chorus lyrics repeated after each verse). */
    private fun deduplicateBlocks(lines: List<String>): List<String> {
        if (lines.size < 4) return lines
        // Try block sizes from large to small to catch full chorus blocks first
        val normalized = lines.map { it.lowercase().trim() }
        val removed = BooleanArray(lines.size)

        for (blockSize in (lines.size / 2) downTo 2) {
            for (i in 0..lines.size - blockSize) {
                if (removed[i]) continue
                val block = normalized.subList(i, i + blockSize)
                // Look for identical blocks later in the text
                var j = i + blockSize
                while (j + blockSize <= lines.size) {
                    if (!removed[j] && normalized.subList(j, j + blockSize) == block) {
                        for (k in j until j + blockSize) removed[k] = true
                        j += blockSize
                    } else {
                        j++
                    }
                }
            }
        }
        return lines.filterIndexed { idx, _ -> !removed[idx] }
    }

    private val structuralWordsRegex = Regex(
        """\b(припев|куплет|хор|вступление|окончание|бридж|кода|chorus|verse|bridge|intro|outro|refrain|coda)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(structuralWordsRegex, "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Similarity using pre-computed bigrams (avoids redundant normalize + bigram calls) */
    private fun similarityFromBigrams(
        na: String, bigramsA: Map<String, Int>,
        nb: String, bigramsB: Map<String, Int>
    ): Double {
        if (na.isEmpty() && nb.isEmpty()) return 1.0
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        if (bigramsA.isEmpty() && bigramsB.isEmpty()) return 1.0
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0

        val intersection = bigramsA.keys.sumOf { minOf(bigramsA[it] ?: 0, bigramsB[it] ?: 0) }
        return (2.0 * intersection) / (bigramsA.values.sum() + bigramsB.values.sum())
    }

    /** Bigram-based similarity (Dice coefficient) — standalone version */
    internal fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        return similarityFromBigrams(na, bigrams(na), nb, bigrams(nb))
    }

    private fun bigrams(text: String): Map<String, Int> {
        if (text.length < 2) return emptyMap()
        val map = mutableMapOf<String, Int>()
        for (i in 0 until text.length - 1) {
            val bg = text.substring(i, i + 2)
            map[bg] = (map[bg] ?: 0) + 1
        }
        return map
    }

    private fun readFileWithFallback(file: File): String {
        return try {
            val bytes = file.readBytes()
            val content = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            } else {
                String(bytes, Charsets.UTF_8)
            }
            if (content.contains('\uFFFD')) String(bytes, Charset.forName("windows-1251")) else content
        } catch (_: Exception) {
            file.readText(Charset.forName("windows-1251"))
        }
    }
}
