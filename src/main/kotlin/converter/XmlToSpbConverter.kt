package converter

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class BibleBook(
    val number: Int,
    val name: String,
    val chapters: List<BibleChapter>
)

data class BibleChapter(
    val number: Int,
    val verses: List<BibleVerse>
)

data class BibleVerse(
    val number: Int,
    val text: String
)

data class ParsedBible(
    val name: String,
    val description: String,
    val language: String?,
    val books: List<BibleBook>
)

object XmlToSpbConverter {

    // Languages that use LXX/Septuagint Psalm numbering (Orthodox traditions)
    private val LXX_PSALM_LANGUAGES = setOf(
        "RUS", "UKR", "BEL",           // East Slavic
        "SRP", "BUL", "MKD",           // South Slavic
        "RON", "RUM", "MOL",           // Romanian
        "KAT", "GEO",                  // Georgian
        "GRE", "GRC", "ELL",           // Greek
        "AMH", "ETH",                  // Ethiopian
        "COP",                         // Coptic
        "SYR", "ARC"                   // Syriac/Aramaic
    )

    /**
     * Maps LXX Psalm chapter number to Hebrew Psalm chapter number.
     * Used for the BXXXCXXXVXXX code so cross-referencing with Hebrew-numbered Bibles works.
     */
    private fun lxxToHebrewPsalm(lxxChapter: Int): Int = when {
        lxxChapter <= 8 -> lxxChapter                   // Psalms 1-8: same
        lxxChapter == 9 -> 9                             // LXX 9 = Hebrew 9+10 (merged)
        lxxChapter in 10..112 -> lxxChapter + 1          // LXX 10-112 = Hebrew 11-113
        lxxChapter == 113 -> 114                         // LXX 113 = Hebrew 114+115 (merged)
        lxxChapter == 114 -> 116                         // LXX 114 = Hebrew 116:1-9
        lxxChapter == 115 -> 116                         // LXX 115 = Hebrew 116:10-19
        lxxChapter in 116..145 -> lxxChapter + 1         // LXX 116-145 = Hebrew 117-146
        lxxChapter == 146 -> 147                         // LXX 146 = Hebrew 147:1-11
        lxxChapter == 147 -> 147                         // LXX 147 = Hebrew 147:12-20
        lxxChapter >= 148 -> lxxChapter                  // Psalms 148-150(151): same
        else -> lxxChapter
    }

    /**
     * Detects if a psalm verse text is a standalone superscription (title only, no content).
     * Examples: "Начальнику хора. На струнных. Псалом Давида."
     *           "Псалом Давида, когда он бежал от Авессалома"
     * Counter-example: "«Псалом Давида.» Блажен муж, который не ходит..." (embedded title + content)
     */
    private fun isPsalmSuperscription(text: String): Boolean {
        val trimmed = text.trim()
        // Too long to be just a title
        if (trimmed.length > 200) return false
        // Remove text inside «» brackets (title markers)
        val withoutBrackets = trimmed.replace(Regex("«[^»]*»\\.?"), "").trim()
        // If after removing bracketed title there's substantial content, it's embedded
        if (withoutBrackets.length > 40) return false
        // Check for known superscription patterns
        val titlePatterns = listOf(
            "Псалом", "Молитва", "Начальнику", "Песнь", "Аллилуия",
            "Давида", "Асафа", "Кореевых", "Соломона", "Моисея", "Ефама", "Емана",
            "Psalm", "Prayer", "Song", "Maskil", "Miktam", "Shiggaion",
            "For the director", "Of David", "Of Asaph", "Of Solomon",
            "Псалом", "Пісня", "Молитва" // Ukrainian
        )
        val hasTitle = titlePatterns.any { trimmed.contains(it, ignoreCase = true) }
        // If it contains a title pattern and has no substantial content after removal, it's a superscription
        return hasTitle || withoutBrackets.isEmpty()
    }

    fun parse(xmlFile: File): ParsedBible {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xmlFile)
        val root = doc.documentElement

        val bibleName = root.getAttribute("biblename").ifBlank { "Unknown" }

        var description = ""
        var language: String? = null

        val infoNodes = root.getElementsByTagName("INFORMATION")
        if (infoNodes.length > 0) {
            val info = infoNodes.item(0)
            for (i in 0 until info.childNodes.length) {
                val child = info.childNodes.item(i)
                when (child.nodeName) {
                    "description" -> description = child.textContent ?: ""
                    "language" -> {
                        val xmlLang = (child.textContent ?: "").trim().uppercase()
                        val pathLower = xmlFile.absolutePath.lowercase()
                        language = if (xmlLang == "RUS" && ("ukrainian" in pathLower || "українська" in pathLower)) {
                            "UKR"
                        } else {
                            xmlLang
                        }
                    }
                }
            }
        }

        if (language.isNullOrBlank()) {
            val parts = xmlFile.absolutePath.replace("\\", "/").split("/")
            val parentFolder = parts.getOrNull(parts.size - 4)?.uppercase()
            language = when {
                parentFolder == "RUS" -> {
                    val pathLower = xmlFile.absolutePath.lowercase()
                    if ("ukrainian" in pathLower || "українська" in pathLower) "UKR" else "RUS"
                }
                parentFolder != null && (parentFolder in BookNames.LANGUAGE_LOOKUPS || parentFolder == "ENG") -> parentFolder
                else -> null
            }
        }

        val books = mutableListOf<BibleBook>()
        val bookNodes = root.getElementsByTagName("BIBLEBOOK")

        for (b in 0 until bookNodes.length) {
            val bookElem = bookNodes.item(b)
            val bookNum = bookElem.attributes.getNamedItem("bnumber")?.nodeValue?.toIntOrNull() ?: 0
            val bookName = getBookName(bookElem, bookNum, language)

            val chapters = mutableListOf<BibleChapter>()
            val chapterNodes = bookElem.childNodes

            for (c in 0 until chapterNodes.length) {
                val chapElem = chapterNodes.item(c)
                if (chapElem.nodeName != "CHAPTER") continue

                val chapNum = chapElem.attributes.getNamedItem("cnumber")?.nodeValue?.toIntOrNull() ?: 0
                val verses = mutableListOf<BibleVerse>()
                val verseNodes = chapElem.childNodes

                for (v in 0 until verseNodes.length) {
                    val versElem = verseNodes.item(v)
                    if (versElem.nodeName != "VERS") continue

                    val versNum = versElem.attributes.getNamedItem("vnumber")?.nodeValue?.toIntOrNull() ?: 0
                    val versText = versElem.textContent ?: ""
                    verses.add(BibleVerse(versNum, versText))
                }

                chapters.add(BibleChapter(chapNum, verses))
            }

            books.add(BibleBook(bookNum, bookName, chapters))
        }

        return ParsedBible(bibleName, description, language, books)
    }

    fun convert(xmlFile: File, outputFile: File) {
        val bible = parse(xmlFile)

        val abbreviation = bible.name.split(" ")
            .filter { it.isNotBlank() }
            .joinToString("") { it.first().toString() }

        val rtl = if (bible.language in setOf("ARA", "HEB", "SYR", "CKB", "SHU")) "1" else ""

        outputFile.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("##spDataVersion:\t1\n")
            w.write("##Title:\t${bible.name}\n")
            w.write("##Abbreviation:\t$abbreviation\n")
            w.write("##Information:\t${bible.description}\n")
            w.write("##RightToLeft:\t$rtl\n")

            for (book in bible.books) {
                w.write("${book.number}\t${book.name}\t${book.chapters.size}\n")
            }

            w.write("-----\n")

            val useLxxMapping = bible.language?.uppercase() in LXX_PSALM_LANGUAGES
            val psalmsBookNum = 19

            for (book in bible.books) {
                for (chapter in book.chapters) {
                    // For LXX Psalms, detect if verse 1 is a standalone superscription.
                    // If so, code it as V000 and offset subsequent verse numbers by -1.
                    val isLxxPsalm = useLxxMapping && book.number == psalmsBookNum
                    val hasStandaloneTitle = isLxxPsalm && chapter.verses.isNotEmpty()
                            && isPsalmSuperscription(chapter.verses.first().text)
                    val codeChapter = if (isLxxPsalm)
                        lxxToHebrewPsalm(chapter.number) else chapter.number

                    for (verse in chapter.verses) {
                        val codeVerse = if (hasStandaloneTitle) verse.number - 1 else verse.number
                        val verseId = "B%03dC%03dV%03d".format(book.number, codeChapter, codeVerse)
                        w.write("$verseId\t${book.number}\t${chapter.number}\t${verse.number}\t${verse.text}\n")
                    }
                }
            }
        }
    }

    fun convertBatch(xmlFiles: List<File>, outputDir: File): List<Pair<File, File>> {
        outputDir.mkdirs()
        return xmlFiles.map { xmlFile ->
            val outputFile = File(outputDir, xmlFile.nameWithoutExtension + ".spb")
            convert(xmlFile, outputFile)
            xmlFile to outputFile
        }
    }

    private fun getBookName(bookElem: org.w3c.dom.Node, bookNum: Int, language: String?): String {
        if (language == "ENG") {
            val bname = bookElem.attributes.getNamedItem("bname")?.nodeValue
            if (!bname.isNullOrBlank()) return bname
            val bsname = bookElem.attributes.getNamedItem("bsname")?.nodeValue
            if (!bsname.isNullOrBlank()) return bsname
        }

        if (language != null && language in BookNames.LANGUAGE_LOOKUPS) {
            return BookNames.LANGUAGE_LOOKUPS[language]?.get(bookNum)
                ?: BookNames.ENGLISH[bookNum]
                ?: "Book $bookNum"
        }

        // Fallback: try caption from first chapter
        val children = bookElem.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeName == "CHAPTER") {
                val chapChildren = child.childNodes
                for (j in 0 until chapChildren.length) {
                    val cc = chapChildren.item(j)
                    if (cc.nodeName == "CAPTION") {
                        val captionText = cc.textContent?.trim() ?: ""
                        if ("." in captionText) {
                            val shortName = captionText.substringAfterLast(".").trim()
                            if (shortName.isNotBlank() && shortName.length < 30) {
                                return shortName
                            }
                        }
                    }
                }
                break
            }
        }

        return BookNames.ENGLISH[bookNum] ?: "Book $bookNum"
    }
}
