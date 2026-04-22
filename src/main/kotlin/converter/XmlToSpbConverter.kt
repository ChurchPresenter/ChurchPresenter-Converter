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

            for (book in bible.books) {
                for (chapter in book.chapters) {
                    for (verse in chapter.verses) {
                        val verseId = "B%03dC%03dV%03d".format(book.number, chapter.number, verse.number)
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
