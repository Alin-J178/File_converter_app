package com.example.fileconverter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts text-based documents (DOC, ODT, RTF, TXT, MD, HTML) into DOCX format.
 * Produces a valid .docx (Office Open XML) file by writing the raw XML components
 * inside a ZIP archive.
 */
object DocToDocx {

    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val text = extractText(context, uri)
        val docxBytes = buildDocx(text)
        return saveToDownloads(context, docxBytes, displayName, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    }

    private fun extractText(context: Context, uri: Uri): String {
        // Try to detect format from MIME type or extension
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val name = ImageConverter.queryDisplayName(context, uri)
        val ext = name.substringAfterLast('.', "").lowercase()

        return when {
            // DOC (legacy OLE2) — extract text from WordDocument stream
            mimeType == "application/msword" || ext == "doc" -> extractDocText(context, uri)
            // ODT — extract from content.xml
            mimeType == "application/vnd.oasis.opendocument.text" || ext == "odt" -> extractOdtText(context, uri)
            // RTF — strip control words
            mimeType == "application/rtf" || ext == "rtf" -> extractRtfText(context, uri)
            // Markdown — strip syntax
            mimeType == "text/markdown" || ext == "md" || ext == "markdown" -> extractMdText(context, uri)
            // HTML — strip tags
            mimeType == "text/html" || ext == "html" || ext == "htm" -> extractHtmlText(context, uri)
            // Plain text (TXT and everything else)
            else -> context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: error("Could not read the file")
        }
    }

    /** Extract text from a legacy .doc (OLE2) file using DocToPdf's text extraction. */
    private fun extractDocText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not read the DOC file")
        // Reuse the OLE2 reader from DocToPdf via reflection-free approach:
        // We parse the OLE2 ourselves since DocToPdf's internals are private.
        return try {
            val ole = Ole2Reader(bytes)
            val wordDoc = ole.stream("WordDocument") ?: error("Missing WordDocument stream")
            val table = ole.stream("0Table") ?: ole.stream("1Table") ?: error("Missing Table stream")
            if (wordDoc.size < 32 || le16(wordDoc, 0) != 0xA5EC) error("Not a valid .doc file")

            val clx = listOf(406, 418).firstNotNullOfOrNull { off ->
                if (off + 8 > wordDoc.size) return@firstNotNullOfOrNull null
                val fc = le32(wordDoc, off)
                val lcb = le32(wordDoc, off + 4)
                if (fc < 0 || lcb <= 0 || fc + lcb > table.size) null else fc to lcb
            } ?: error("No piece table found")

            val sb = StringBuilder()
            parsePieces(wordDoc, table, clx.first, clx.second, sb)
            sb.toString()
        } catch (_: Exception) {
            // Fallback: extract readable ASCII runs
            extractReadableRuns(bytes)
        }
    }

    /** Extract text from ODT (ZIP containing content.xml). */
    private fun extractOdtText(context: Context, uri: Uri): String {
        val sb = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { input ->
            java.util.zip.ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "content.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        val regex = Regex("<text:p[^>]*>(.*?)</text:p>", RegexOption.DOT_MATCHES_ALL)
                        for (match in regex.findAll(xml)) {
                            val para = match.groupValues[1]
                                .replace(Regex("<[^>]+>"), "")
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .trim()
                            sb.appendLine(para)
                        }
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        } ?: error("Could not read the ODT file")
        return sb.toString().ifEmpty { error("ODT file contains no text") }
    }

    /** Strip RTF control words to get plain text. */
    private fun extractRtfText(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not read the RTF file")
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            when {
                raw[i] == '\\' && i + 1 < raw.length -> {
                    val next = raw[i + 1]
                    when (next) {
                        '{', '}', '\\' -> { sb.append(next); i += 2 }
                        '\n', '\r' -> i += 1
                        in 'a'..'z', in 'A'..'Z' -> {
                            i += 2
                            while (i < raw.length && raw[i].isLetter()) i++
                            if (i < raw.length && raw[i] == ' ') i++
                        }
                        else -> i += 1
                    }
                }
                raw[i] == '{' -> i++
                raw[i] == '}' -> i++
                raw[i] == '\'' && i + 2 < raw.length -> {
                    val hex = raw.substring(i + 1, i + 3)
                    try { sb.append(hex.toInt(16).toChar()) } catch (_: Exception) { sb.append('?') }
                    i += 3
                }
                else -> { sb.append(raw[i]); i++ }
            }
        }
        return sb.toString().replace(Regex("\r\n?"), "\n")
    }

    /** Strip Markdown syntax. */
    private fun extractMdText(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not read the Markdown file")
        return raw
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("`(.+?)`"), "$1")
            .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1")
            .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "• ")
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
    }

    /** Strip HTML tags. */
    private fun extractHtmlText(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not read the HTML file")
        return raw
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    /** Fallback: extract readable ASCII runs from binary data. */
    private fun extractReadableRuns(data: ByteArray): String {
        val sb = StringBuilder()
        var run = StringBuilder()
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.code in 32..126 || c == '\n' || c == '\t') {
                run.append(c)
            } else {
                if (run.length > 3) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(run)
                    if (sb.length > 500) break
                }
                run = StringBuilder()
            }
        }
        if (run.length > 3 && sb.length <= 500) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(run)
        }
        return sb.toString().ifEmpty { "Document" }
    }

    // ---- OLE2 reader for .doc files ----

    private class Ole2Reader(private val data: ByteArray) {
        private val sectorSize = 1 shl le16(data, 30)
        private val miniSectorSize = 1 shl le16(data, 32)
        private val miniStreamCutoff = le32(data, 56)
        private val fat: IntArray
        private val miniFat: IntArray
        private val rootStart: Int
        private val entries = mutableMapOf<String, Pair<Int, Long>>() // name -> (startSector, size)

        init {
            val numFatSectors = le32(data, 44)
            val firstDirSector = le32(data, 48)
            val firstMiniFatSector = le32(data, 60)
            val numMiniFatSectors = le32(data, 64)
            var firstDifatSector = le32(data, 68)
            var numDifatSectors = le32(data, 72)

            val fatSectors = mutableListOf<Int>()
            for (i in 0 until 109) {
                val s = le32(data, 76 + i * 4)
                if (s != -2 && s != -1) fatSectors += s
            }
            var guard = 0
            while (firstDifatSector != -2 && firstDifatSector != -1 && guard++ < 10000) {
                val base = (firstDifatSector + 1) * sectorSize
                val perSector = sectorSize / 4 - 1
                for (i in 0 until perSector) {
                    val s = le32(data, base + i * 4)
                    if (s != -1) fatSectors += s
                }
                firstDifatSector = le32(data, base + sectorSize - 4)
                numDifatSectors--
                if (numDifatSectors <= 0) break
            }

            val entriesPerFat = sectorSize / 4
            fat = IntArray(fatSectors.size * entriesPerFat)
            for ((i, sec) in fatSectors.withIndex()) {
                val base = (sec + 1) * sectorSize
                for (j in 0 until entriesPerFat) fat[i * entriesPerFat + j] = le32(data, base + j * 4)
            }

            miniFat = if (numMiniFatSectors > 0) {
                val chain = readSectorChain(firstMiniFatSector)
                IntArray(chain.size / 4) { i -> le32(chain, i * 4) }
            } else IntArray(0)

            val dirBytes = readSectorChain(firstDirSector)
            var rootStartSector = -2
            val entryCount = dirBytes.size / 128
            for (i in 0 until entryCount) {
                val base = i * 128
                val nameLen = le16(dirBytes, base + 64)
                val type = dirBytes[base + 66].toInt() and 0xFF
                val start = le32(dirBytes, base + 116)
                val size = le64(dirBytes, base + 120)
                if (type == 5) {
                    rootStartSector = start
                } else if (type == 2 && nameLen >= 2) {
                    val name = StringBuilder()
                    for (j in 0 until (nameLen - 2) / 2) {
                        val c = (dirBytes[base + 2 * j].toInt() and 0xFF) or
                            ((dirBytes[base + 2 * j + 1].toInt() and 0xFF) shl 8)
                        if (c != 0) name.append(c.toChar())
                    }
                    entries[name.toString()] = start to size
                }
            }
            rootStart = rootStartSector
        }

        fun stream(name: String): ByteArray? {
            val (start, size) = entries[name] ?: return null
            if (size >= miniStreamCutoff || miniFat.isEmpty()) {
                val chain = readSectorChain(start)
                return if (chain.size > size) chain.copyOf(size.toInt()) else chain
            }
            val mini = readSectorChain(rootStart)
            val out = ByteArrayOutputStream()
            var s = start
            var remaining = size
            var guard = 0
            while (s != -2 && s != -1 && s >= 0 && remaining > 0 && guard++ < 1000000) {
                val off = s * miniSectorSize
                val n = minOf(remaining, miniSectorSize.toLong()).toInt()
                if (off + n > mini.size) break
                out.write(mini, off, n)
                remaining -= n
                s = miniFat[s]
            }
            return out.toByteArray()
        }

        private fun readSectorChain(start: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s != -2 && s != -1 && s >= 0 && s < fat.size && guard++ < 1000000) {
                val base = (s + 1) * sectorSize
                if (base + sectorSize > data.size) break
                out.write(data, base, sectorSize)
                s = fat[s]
            }
            return out.toByteArray()
        }
    }

    private fun parsePieces(wordDoc: ByteArray, table: ByteArray, fcClx: Int, lcbClx: Int, out: StringBuilder) {
        val end = fcClx + lcbClx
        if (fcClx < 0 || lcbClx <= 0 || end > table.size) return

        fun tryPcdt(at: Int): Pair<Int, Int>? {
            if (at + 4 > end) return null
            val lcb = le32(table, at)
            val n = (lcb - 4) / 12
            if (lcb < 4 || n < 0 || lcb != 4 + 12 * n || at + 4 + lcb > end) return null
            return at to n
        }

        val (pcdtOff, n) = (0..2).mapNotNull { d ->
            val base = fcClx + d
            tryPcdt(base) ?: run {
                if (base + 2 <= end) tryPcdt(base + 2 + le16(table, base)) else null
            }
        }.firstOrNull() ?: return

        val aCp = pcdtOff + 4
        val aPcd = aCp + 4 * (n + 1)
        for (i in 0 until n) {
            val cpStart = le32(table, aCp + 4 * i)
            val cpEnd = le32(table, aCp + 4 * (i + 1))
            val count = cpEnd - cpStart
            if (count < 0) continue
            val fc = le32(table, aPcd + 8 * i + 2)
            val offset = fc and 0x3FFFFFFF
            if (fc and 0x40000000 != 0) {
                val start = offset / 2
                if (start + count > wordDoc.size) continue
                for (j in 0 until count) out.append(cp1252(wordDoc[start + j].toInt() and 0xFF))
            } else {
                if (offset + 2 * count > wordDoc.size) continue
                for (j in 0 until count) {
                    out.append(((wordDoc[offset + 2 * j].toInt() and 0xFF) or
                        ((wordDoc[offset + 2 * j + 1].toInt() and 0xFF) shl 8)).toChar())
                }
            }
        }
    }

    private fun cp1252(b: Int): Char = when {
        b < 0x80 -> b.toChar()
        b in 0x80..0x9F -> {
            val specials = "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\uFFFD\u017D\uFFFD" +
                "\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\uFFFD\u017E\u0178"
            specials[b - 0x80]
        }
        else -> b.toChar()
    }

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun le64(b: ByteArray, o: Int): Long = (le32(b, o).toLong() and 0xFFFFFFFFL) or ((le32(b, o + 4).toLong() and 0xFFFFFFFFL) shl 32)

    // ---- OOXML DOCX builder ----

    private fun buildDocx(text: String): ByteArray {
        val paragraphs = text.lines().map { it.trim() }
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            // [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES.toByteArray())
            zip.closeEntry()

            // _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(RELS.toByteArray())
            zip.closeEntry()

            // word/document.xml
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(buildDocumentXml(paragraphs).toByteArray())
            zip.closeEntry()

            // word/_rels/document.xml.rels
            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(DOC_RELS.toByteArray())
            zip.closeEntry()

            // word/styles.xml
            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write(STYLES.toByteArray())
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun buildDocumentXml(paragraphs: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.appendLine("<w:document xmlns:wpc=\"http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas\" xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\" xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:w10=\"urn:schemas-microsoft-com:office:word\" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\" xmlns:wpg=\"http://schemas.microsoft.com/office/word/2010/wordprocessingGroup\" xmlns:wpi=\"http://schemas.microsoft.com/office/word/2010/wordprocessingInk\" xmlns:wne=\"http://schemas.microsoft.com/office/word/2006/wordml\" xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\" mc:Ignorable=\"w14 wp14\">")
        sb.appendLine("<w:body>")
        for (para in paragraphs) {
            sb.appendLine("<w:p>")
            sb.appendLine("<w:pPr><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:pPr>")
            if (para.isNotEmpty()) {
                sb.appendLine("<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">${xmlEscape(para)}</w:t></w:r>")
            }
            sb.appendLine("</w:p>")
        }
        sb.appendLine("<w:sectPr>")
        sb.appendLine("<w:pgSz w:w=\"12240\" w:h=\"15840\"/>")
        sb.appendLine("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>")
        sb.appendLine("</w:sectPr>")
        sb.appendLine("</w:body>")
        sb.appendLine("</w:document>")
        return sb.toString()
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun saveToDownloads(context: Context, bytes: ByteArray, displayName: String, mime: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileConverter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
                ?: error("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            val file = File(dir, displayName)
            FileOutputStream(file).use { it.write(bytes) }
            Uri.fromFile(file)
        }
    }

    // ---- Static OOXML skeleton files ----

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/>
        <w:sz w:val="22"/>
        <w:szCs w:val="22"/>
      </w:rPr>
    </w:rPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/>
  </w:style>
</w:styles>"""
}
