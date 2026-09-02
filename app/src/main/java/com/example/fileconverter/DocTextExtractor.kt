package com.example.fileconverter

import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Shared utility for extracting text from legacy .doc (OLE2) files.
 * Preserves table markers (\u0007 cell, \u000B row) so callers can detect
 * and render table structures.
 */
object DocTextExtractor {

    // cp1252 (ANSI) special characters for bytes 0x80-0x9F
    private const val CP1252_SPECIALS =
        "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\uFFFD\u017D\uFFFD" +
            "\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\uFFFD\u017E\u0178"

    // Table cell end marker
    const val CELL_MARKER = '\u0007'
    // Table row end marker
    const val ROW_MARKER = '\u000B'

    /** Result of extracting text from a .doc file */
    data class DocText(
        val text: String,
        val hasTableMarkers: Boolean,
    )

    /**
     * Extract text from a raw .doc byte array.
     * Returns [DocText] with the decoded text and whether table markers were found.
     */
    fun extract(bytes: ByteArray): DocText {
        if (bytes.size < 512) return DocText("", false)
        if (bytes[0] != 0xD0.toByte() || bytes[1] != 0xCF.toByte() ||
            bytes[2] != 0x11.toByte() || bytes[3] != 0xE0.toByte()
        ) return DocText("", false)

        val ole = Ole2(bytes)
        val wordDoc = ole.stream("WordDocument") ?: return DocText("", false)
        val table = ole.stream("0Table") ?: ole.stream("1Table") ?: return DocText("", false)
        if (wordDoc.size < 32 || le16(wordDoc, 0) != 0xA5EC) return DocText("", false)

        val clx = listOf(406, 418).firstNotNullOfOrNull { off ->
            if (off + 8 > wordDoc.size) return@firstNotNullOfOrNull null
            val fc = le32(wordDoc, off)
            val lcb = le32(wordDoc, off + 4)
            if (fc < 0 || lcb <= 0 || fc + lcb > table.size) null else fc to lcb
        } ?: return DocText("", false)

        val sb = StringBuilder()
        parsePieces(wordDoc, table, clx.first, clx.second, sb)
        val text = sb.toString()
        val hasTableMarkers = text.contains(CELL_MARKER) || text.contains(ROW_MARKER)
        return DocText(text, hasTableMarkers)
    }

    // ---- Structured content extraction ------------------------------------

    /** A block of document content */
    sealed class DocBlock {
        class TextBlock(val text: String, val pageBreakBefore: Boolean) : DocBlock()
        class TableBlock(val rows: List<List<String>>) : DocBlock()
    }

    /**
     * Parse extracted text into structured blocks, detecting tables.
     * Call this on the text from [extract] to get renderable content.
     */
    fun buildBlocks(docText: DocText): List<DocBlock> {
        val raw = docText.text
        if (raw.isBlank()) return listOf(DocBlock.TextBlock("Could not extract text from DOC file", false))

        val blocks = mutableListOf<DocBlock>()
        val sb = StringBuilder()
        var pageBreak = false
        var inFieldCode = false
        var i = 0

        // Buffer for table cells/rows
        val tableRows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        var currentCell = StringBuilder()
        var inTable = false

        fun flushCell() {
            val cellText = currentCell.toString().trim()
            currentRow.add(cellText)
            currentCell = StringBuilder()
        }

        fun flushRow() {
            flushCell()
            if (currentRow.isNotEmpty()) {
                tableRows.add(currentRow.toList())
                currentRow = mutableListOf()
            }
        }

        fun flushParagraph() {
            val text = sb.toString().trim()
            if (text.isNotEmpty()) {
                blocks.add(DocBlock.TextBlock(text, pageBreak))
            }
            sb.setLength(0)
            pageBreak = false
        }

        fun finalizeTable() {
            flushRow()
            if (tableRows.isNotEmpty()) {
                val maxCols = tableRows.maxOf { it.size }
                if (tableRows.size >= 2 && maxCols >= 2) {
                    blocks.add(DocBlock.TableBlock(tableRows.toList()))
                } else {
                    for (row in tableRows) {
                        blocks.add(DocBlock.TextBlock(row.joinToString("    "), false))
                    }
                }
                tableRows.clear()
            }
            inTable = false
        }

        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\u0013' -> inFieldCode = true
                c == '\u0014' -> inFieldCode = false
                c == '\u0015' -> Unit
                inFieldCode -> Unit
                c == CELL_MARKER -> {
                    inTable = true
                    flushCell()
                }
                c == ROW_MARKER -> {
                    inTable = true
                    flushRow()
                }
                c == '\r' || c == '\n' -> {
                    if (inTable) {
                        flushCell()
                        // Check if we're leaving the table
                        // Peek ahead: if next non-whitespace char is not a table marker, end table
                        var peek = i + 1
                        while (peek < raw.length && (raw[peek] == '\r' || raw[peek] == '\n')) peek++
                        val nextIsTableChar = peek < raw.length && (raw[peek] == CELL_MARKER || raw[peek] == ROW_MARKER)
                        if (!nextIsTableChar && tableRows.isNotEmpty()) {
                            finalizeTable()
                        }
                    } else {
                        flushParagraph()
                    }
                }
                c == '\u000C' -> {
                    flushParagraph()
                    pageBreak = true
                }
                c == '\t' -> {
                    if (inTable) currentCell.append("    ") else sb.append("    ")
                }
                c.code < 0x20 || c == '\u007F' || c == '\uFFFF' -> Unit
                else -> {
                    if (inTable) currentCell.append(c) else sb.append(c)
                }
            }
            i++
        }

        if (inTable) finalizeTable() else flushParagraph()

        if (blocks.isEmpty()) {
            blocks.add(DocBlock.TextBlock(raw.trim(), false))
        }

        // Post-process: detect tab/space-delimited tables in TextBlocks
        return detectTabDelimitedTables(blocks)
    }

    // ---- Tab/space-delimited table detection -------------------------------

    /**
     * Scans TextBlocks for consecutive lines with consistent tab/space column patterns.
     * Groups them into TableBlocks for proper grid rendering.
     * Handles documents where tables are formatted with tabs instead of Word table objects.
     */
    private fun detectTabDelimitedTables(blocks: List<DocBlock>): List<DocBlock> {
        val result = mutableListOf<DocBlock>()
        var i = 0

        // Collect consecutive TextBlocks into groups separated by non-TextBlocks or page breaks
        while (i < blocks.size) {
            val block = blocks[i]
            if (block is DocBlock.TextBlock && !block.pageBreakBefore) {
                val candidateLines = mutableListOf<String>()
                var j = i
                while (j < blocks.size && blocks[j] is DocBlock.TextBlock && !(blocks[j] as DocBlock.TextBlock).pageBreakBefore) {
                    val text = (blocks[j] as DocBlock.TextBlock).text
                    if (text.isBlank()) break
                    candidateLines.add(text)
                    j++
                }

                if (candidateLines.size >= 2) {
                    // Only use tab / multi-space detection (Pass 1)
                    // Do NOT use word-count detection — it creates false positives
                    val pass1Rows = candidateLines.map { it.split(Regex("\\t|\\s{2,}")).filter { c -> c.isNotBlank() } }
                    val pass1ColCounts = pass1Rows.map { it.size }
                    val pass1Mode = pass1ColCounts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 1

                    if (pass1Mode >= 2) {
                        val tableRows = pass1Rows.filter { it.size in 2..(pass1Mode + 2) }
                        if (tableRows.size >= 2) {
                            val padded = tableRows.map { r ->
                                if (r.size < pass1Mode) r + List(pass1Mode - r.size) { "" }
                                else r.take(pass1Mode)
                            }
                            result.add(DocBlock.TableBlock(padded))
                            i = j
                            continue
                        }
                    }
                }
            }
            result.add(block)
            i++
        }

        return result
    }

    // ---- OLE2 piece table parser ------------------------------------------

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
        b in 0x80..0x9F -> CP1252_SPECIALS[b - 0x80]
        else -> b.toChar()
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

        fun stream(name: String): ByteArray? {
            val e = entries[name] ?: return null
            if (e.size >= miniStreamCutoff || miniFat.isEmpty()) {
                val chain = readSectorChain(e.startSector)
                return if (chain.size > e.size) chain.copyOf(e.size.toInt()) else chain
            }
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
