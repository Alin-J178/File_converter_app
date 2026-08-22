package com.example.fileconverter

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Converts a legacy Word 97-2003 .doc file (an OLE2 compound document) into a PDF.
 *
 * The document text lives in the WordDocument stream and is located through the piece
 * table (CLX) described by the FIB in the 0Table/1Table stream. This parser reads the
 * OLE2 container, walks the FIB's piece table and decodes the text pieces — either
 * Unicode (UTF-16LE) or the older ANSI-compressed form — then renders the text onto A4
 * pages with word wrapping. Character formatting (bold, sizes, fonts) and embedded
 * images are not recovered from the binary format in this version: output is plain text.
 */
object DocToPdf {

    private const val PAGE_W = 595 // A4 at 72 dpi, in points
    private const val PAGE_H = 842
    private const val MARGIN = 56f
    private const val FONT_SIZE = 12f

    // cp1252 (ANSI) special characters for bytes 0x80-0x9F, used by compressed text pieces.
    private const val CP1252_SPECIALS =
        "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\uFFFD\u017D\uFFFD" +
            "\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\uFFFD\u017E\u0178"

    private data class DocParagraph(val text: String, val pageBreakBefore: Boolean)

    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open the Word file")
        val text = extractText(bytes)
        val document = PdfDocument()
        try {
            var pageIndex = 0
            var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex).create())
            var y = MARGIN

            fun newPage() {
                document.finishPage(page)
                pageIndex++
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex).create())
                y = MARGIN
            }

            for (para in splitParagraphs(text)) {
                if (para.pageBreakBefore) newPage()
                val tokens = buildTokens(para.text)
                if (tokens.isEmpty()) {
                    y += FONT_SIZE * 1.4f
                    if (y > PAGE_H - MARGIN) newPage()
                    continue
                }
                for (line in wrapTokens(tokens)) {
                    if (y + line.height > PAGE_H - MARGIN) newPage()
                    drawLine(page.canvas, line, y)
                    y += line.height * 1.3f
                }
                y += 6f // paragraph spacing
            }
            document.finishPage(page)
            return save(context, document, displayName)
        } finally {
            document.close()
        }
    }

    // ---- Text extraction ---------------------------------------------------

    private fun extractText(bytes: ByteArray): String {
        if (bytes.size < 512) error("Not a valid .doc file")
        if (bytes[0] != 0xD0.toByte() || bytes[1] != 0xCF.toByte() ||
            bytes[2] != 0x11.toByte() || bytes[3] != 0xE0.toByte()
        ) {
            error("Not a valid .doc file")
        }
        val ole = Ole2(bytes)
        val wordDoc = ole.stream("WordDocument") ?: error("Not a valid .doc file (missing WordDocument stream)")
        val table = ole.stream("0Table") ?: ole.stream("1Table")
            ?: error("Not a valid .doc file (missing Table stream)")
        if (wordDoc.size < 32 || le16(wordDoc, 0) != 0xA5EC) error("Not a valid .doc file")

        // The FIB's FibRgFcLcb table starts at offset 142; fcClx/lcbClx are entry 33.
        // (Some writers pad the base FIB to 154 — try both, pick the one that parses.)
        val clx = listOf(406, 418).firstNotNullOfOrNull { off ->
            if (off + 8 > wordDoc.size) return@firstNotNullOfOrNull null
            val fc = le32(wordDoc, off)
            val lcb = le32(wordDoc, off + 4)
            if (fc < 0 || lcb <= 0 || fc + lcb > table.size) null else fc to lcb
        } ?: error("Unsupported .doc file (no piece table found)")

        val sb = StringBuilder()
        parsePieces(wordDoc, table, clx.first, clx.second, sb)
        return sb.toString()
    }

    /** Reads the CLX (piece table) from the Table stream and appends the decoded text. */
    private fun parsePieces(wordDoc: ByteArray, table: ByteArray, fcClx: Int, lcbClx: Int, out: StringBuilder) {
        val end = fcClx + lcbClx
        if (fcClx < 0 || lcbClx <= 0 || end > table.size) error("Corrupt .doc file (piece table out of range)")

        // The CLX starts with an optional Prc (formatting runs); the Pcdt follows. A Pcdt's
        // first field is its own size, which must satisfy lcb = 4 + 12*n. Some writers leave
        // a stray byte before the Pcdt, so probe a few offsets (with and without a Prc).
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
                // Skip a Prc: cbGrpprl (2 bytes) + the formatting data.
                if (base + 2 <= end) tryPcdt(base + 2 + le16(table, base)) else null
            }
        }.firstOrNull() ?: error("Corrupt .doc file (bad piece table)")

        val aCp = pcdtOff + 4
        val aPcd = aCp + 4 * (n + 1)
        for (i in 0 until n) {
            val cpStart = le32(table, aCp + 4 * i)
            val cpEnd = le32(table, aCp + 4 * (i + 1))
            val count = cpEnd - cpStart
            if (count < 0) continue
            // Pcd layout: flags (2 bytes) + FcCompressed.fc (4 bytes) + prm (2 bytes).
            val fc = le32(table, aPcd + 8 * i + 2)
            val offset = fc and 0x3FFFFFFF
            if (fc and 0x40000000 != 0) {
                // Compressed piece: 1-byte ANSI characters; the fc stores twice the offset.
                val start = offset / 2
                if (start + count > wordDoc.size) continue
                for (j in 0 until count) out.append(cp1252(wordDoc[start + j].toInt() and 0xFF))
            } else {
                // Unicode piece: 2-byte characters at the given byte offset.
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
        b in 0x80..0x9F -> CP1252_SPECIALS[b - 0x80]
        else -> b.toChar()
    }

    /** Splits the raw document text into paragraphs, dropping Word control characters. */
    private fun splitParagraphs(raw: String): List<DocParagraph> {
        val result = mutableListOf<DocParagraph>()
        val sb = StringBuilder()
        var pageBreak = false
        var inFieldCode = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\u0013' -> inFieldCode = true      // field begin
                c == '\u0014' -> inFieldCode = false     // field separator
                c == '\u0015' -> Unit                    // field end
                inFieldCode -> Unit                      // skip the field code, keep the result
                c == '\r' || c == '\n' || c == '\u0007' || c == '\u000B' -> { // paragraph / table cell / line break
                    if (sb.isNotBlank()) result += DocParagraph(sb.toString().trim(), pageBreak)
                    sb.setLength(0)
                    pageBreak = false
                }
                c == '\u000C' -> {                       // form feed = manual page break
                    if (sb.isNotBlank()) result += DocParagraph(sb.toString().trim(), pageBreak)
                    sb.setLength(0)
                    pageBreak = true
                }
                c == '\t' -> sb.append("    ")
                c.code < 0x20 || c == '\u007F' || c == '\uFFFF' -> Unit // other control / invalid chars
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank() || result.isEmpty()) result += DocParagraph(sb.toString().trim(), pageBreak)
        return result
    }

    // ---- Rendering ---------------------------------------------------------

    private data class Token(val text: String, val paint: Paint)
    private data class Line(val tokens: List<Token>, val width: Float, val height: Float)

    private fun buildTokens(text: String): List<Token> {
        val paint = Paint().apply {
            typeface = Typeface.DEFAULT
            textSize = FONT_SIZE
        }
        return text.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }.map { Token(it, paint) }
    }

    private fun wrapTokens(tokens: List<Token>): List<Line> {
        val lines = mutableListOf<Line>()
        var lineTokens = mutableListOf<Token>()
        var lineWidth = 0f
        var lineHeight = 0f
        val fullWidth = PAGE_W - MARGIN * 2
        for (token in tokens) {
            val w = token.paint.measureText(token.text)
            val isSpace = token.text.isBlank()
            if (!isSpace && lineTokens.isNotEmpty() && lineWidth + w > fullWidth) {
                lines += Line(lineTokens, lineWidth, lineHeight)
                lineTokens = mutableListOf()
                lineWidth = 0f
                lineHeight = 0f
            }
            if (isSpace && lineTokens.isEmpty()) continue // drop leading space on a new line
            lineTokens += token
            lineWidth += w
            lineHeight = maxOf(lineHeight, FONT_SIZE)
        }
        if (lineTokens.isNotEmpty()) lines += Line(lineTokens, lineWidth, lineHeight)
        return lines
    }

    private fun drawLine(canvas: Canvas, line: Line, baselineY: Float) {
        var x = MARGIN
        for (token in line.tokens) {
            canvas.drawText(token.text, x, baselineY, token.paint)
            x += token.paint.measureText(token.text)
        }
    }

    // ---- Saving ------------------------------------------------------------

    private fun save(context: Context, document: PdfDocument, displayName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileConverter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
                ?: error("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { document.writeTo(it) }
                ?: error("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            val file = File(dir, displayName)
            FileOutputStream(file).use { document.writeTo(it) }
            Uri.fromFile(file)
        }
    }

    // ---- OLE2 (Compound File Binary) reader --------------------------------

    private class Ole2(private val data: ByteArray) {

        private class DirEntry(val startSector: Int, val size: Long)

        private val sectorSize: Int
        private val miniSectorSize: Int
        private val miniStreamCutoff: Int
        private val fat: IntArray
        private val miniFat: IntArray
        private val rootStart: Int
        private val entries = mutableMapOf<String, DirEntry>()

        init {
            sectorSize = 1 shl le16(data, 30)
            miniSectorSize = 1 shl le16(data, 32)
            miniStreamCutoff = le32(data, 56)
            val numFatSectors = le32(data, 44)
            val firstDirSector = le32(data, 48)
            val firstMiniFatSector = le32(data, 60)
            var numMiniFatSectors = le32(data, 64)
            var firstDifatSector = le32(data, 68)
            var numDifatSectors = le32(data, 72)

            // Collect every FAT sector, following the DIFAT chain. Note: sector 0 is a
            // valid FAT sector (only 0xFFFFFFFF marks an unused DIFAT slot), so don't
            // filter out zero here.
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

            // Mini FAT (for streams smaller than the mini stream cutoff).
            miniFat = if (numMiniFatSectors > 0) {
                val chain = readSectorChain(firstMiniFatSector)
                IntArray(chain.size / 4) { i -> le32(chain, i * 4) }
            } else {
                IntArray(0)
            }

            // Directory: 128-byte entries chained from the first directory sector.
            val dirBytes = readSectorChain(firstDirSector)
            var root: DirEntry? = null
            val entryCount = dirBytes.size / 128
            for (i in 0 until entryCount) {
                val base = i * 128
                val nameLen = le16(dirBytes, base + 64)
                val type = dirBytes[base + 66].toInt() and 0xFF
                val start = le32(dirBytes, base + 116)
                val size = le64(dirBytes, base + 120)
                if (type == 5) {
                    root = DirEntry(start, size)
                } else if (type == 2 && nameLen >= 2) {
                    val name = StringBuilder()
                    for (j in 0 until (nameLen - 2) / 2) {
                        val c = (dirBytes[base + 2 * j].toInt() and 0xFF) or
                            ((dirBytes[base + 2 * j + 1].toInt() and 0xFF) shl 8)
                        if (c != 0) name.append(c.toChar())
                    }
                    entries[name.toString()] = DirEntry(start, size)
                }
            }
            rootStart = root?.startSector ?: -2
        }

        /** Reads a named stream, handling both regular and mini (small) streams. */
        fun stream(name: String): ByteArray? {
            val e = entries[name] ?: return null
            if (e.size >= miniStreamCutoff || miniFat.isEmpty()) {
                val chain = readSectorChain(e.startSector)
                return if (chain.size > e.size) chain.copyOf(e.size.toInt()) else chain
            }
            // Small stream: lives inside the root entry's mini stream, chained by the mini FAT.
            val mini = readSectorChain(rootStart)
            val out = ByteArrayOutputStream()
            var s = e.startSector
            var remaining = e.size
            var guard = 0
            while (s != -2 && s != -1 && s >= 0 && remaining > 0 && guard++ < 1000000) {
                val off = s * miniSectorSize
                val n = min(remaining, miniSectorSize.toLong()).toInt()
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

    // ---- Little-endian helpers --------------------------------------------

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long =
        (le32(b, o).toLong() and 0xFFFFFFFFL) or ((le32(b, o + 4).toLong() and 0xFFFFFFFFL) shl 32)
}
