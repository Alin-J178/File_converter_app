package com.example.fileconverter

import android.content.Context
import android.net.Uri

/** Parses legacy .doc (OLE2) files into [WordDocument]. */
object DocParser {

    fun parse(context: Context, uri: Uri): WordDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return WordDocument(emptyList())
        return parseBytes(bytes)
    }

    fun parseBytes(bytes: ByteArray): WordDocument {
        val docText = DocTextExtractor.extract(bytes)
        if (docText.text.isBlank()) return WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Could not extract text from DOC file")))))
        // Debug: write to file (Vivo suppresses all logcat)
        try {
            val f = java.io.File("/sdcard/Documents/doc_debug.txt")
            f.parentFile?.mkdirs()
            f.writeText(buildString {
                appendLine("Text length: ${docText.text.length}")
                appendLine("hasTableMarkers: ${docText.hasTableMarkers}")
                appendLine("First 800 chars:")
                appendLine(docText.text.take(800).replace("\n", "[LN]").replace("\r", "[CR]"))
            })
        } catch (_: Exception) {}
        val blocks = mutableListOf<WordBlock>()
        if (docText.hasTableMarkers) parseWithTableMarkers(docText.text, blocks) else parseAsParagraphs(docText.text, blocks)
        try {
            val f = java.io.File("/sdcard/Documents/doc_debug.txt")
            f.appendText(buildString {
                appendLine("Blocks: ${blocks.size}")
                appendLine("Tables: ${blocks.count { it is WordBlock.Table }}")
                appendLine("Paragraphs: ${blocks.count { it is WordBlock.Paragraph }}")
                blocks.take(15).forEachIndexed { idx, b ->
                    when (b) {
                        is WordBlock.Table -> appendLine("[$idx] TABLE ${b.rows.size}r x ${b.rows.maxOfOrNull { it.cells.size } ?: 0}c")
                        is WordBlock.Paragraph -> appendLine("[$idx] PARA \"${b.runs.joinToString("") { it.text }.take(60)}\"")
                        else -> appendLine("[$idx] ${b::class.simpleName}")
                    }
                }
            })
        } catch (_: Exception) {}
        return if (blocks.isEmpty()) WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Empty document"))))) else WordDocument(blocks)
    }

    private fun parseWithTableMarkers(raw: String, blocks: MutableList<WordBlock>) {
        var sb = StringBuilder(); var inFieldCode = false
        val tableRows = mutableListOf<List<String>>(); var currentRow = mutableListOf<String>(); var currentCell = StringBuilder(); var inTable = false
        fun flushCell() { currentRow.add(currentCell.toString().trim()); currentCell = StringBuilder() }
        fun flushRow() { flushCell(); if (currentRow.isNotEmpty()) { tableRows.add(currentRow.toList()); currentRow = mutableListOf() } }
        fun flushParagraph() { val t = sb.toString().trim(); if (t.isNotEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun(t))); sb.setLength(0) }
        fun emitTable() {
            if (tableRows.size >= 2 && tableRows.maxOf { it.size } >= 2) {
                blocks += WordBlock.Table(rows = tableRows.map { r ->
                    WordTableRow(cells = r.map { c ->
                        WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c)))))
                    })
                })
            } else {
                // Too small for a real table — emit as paragraphs
                for (r in tableRows) blocks += WordBlock.Paragraph(listOf(WordRun(r.joinToString("    "))))
            }
            tableRows.clear(); currentRow.clear(); inTable = false
        }
        var i = 0; while (i < raw.length) { val c = raw[i]; when {
            c == '\u0013' -> inFieldCode = true
            c == '\u0014' -> inFieldCode = false
            c == '\u0015' -> Unit
            inFieldCode -> Unit
            c == DocTextExtractor.CELL_MARKER -> {
                // Transition from non-table to table: flush sb as first cell
                if (!inTable) {
                    inTable = true
                    val t = sb.toString().trim()
                    if (t.isNotEmpty()) currentCell.append(t)
                    sb.setLength(0)
                }
                flushCell()
            }
            c == DocTextExtractor.ROW_MARKER -> {
                if (!inTable) inTable = true
                flushRow()
            }
            c == '\r' || c == '\n' -> {
                if (inTable) {
                    flushCell()
                    // \r marks end of a row in DOC tables — flush the row
                    if (currentRow.isNotEmpty()) {
                        tableRows.add(currentRow.toList()); currentRow = mutableListOf()
                    }
                    // Peek: if no more table markers follow, finalize
                    var peek = i + 1
                    while (peek < raw.length && (raw[peek] == '\r' || raw[peek] == '\n')) peek++
                    if (peek >= raw.length || (raw[peek] != DocTextExtractor.CELL_MARKER && raw[peek] != DocTextExtractor.ROW_MARKER)) {
                        emitTable()
                    }
                } else {
                    flushParagraph()
                }
            }
            c == '\u000C' -> flushParagraph()
            c == '\t' -> { if (inTable) currentCell.append("    ") else sb.append("    ") }
            c.code < 0x20 || c == '\u007F' || c == '\uFFFF' -> Unit
            else -> { if (inTable) currentCell.append(c) else sb.append(c) }
        }; i++ }
        if (inTable) emitTable() else flushParagraph()
    }

    private fun parseAsParagraphs(raw: String, blocks: MutableList<WordBlock>) {
        // Split into groups separated by blank lines (consecutive CR/LF)
        // This preserves paragraph structure for proper table detection
        data class TextGroup(val lines: MutableList<String> = mutableListOf())
        val groups = mutableListOf<TextGroup>()
        val current = StringBuilder()
        var inFieldCode = false
        var blankRun = 0 // count consecutive blank lines

        fun flushParagraph() {
            val t = current.toString().trim()
            if (t.isNotEmpty()) {
                if (blankRun > 0 && groups.isNotEmpty()) groups.add(TextGroup()) // new group after blanks
                if (groups.isEmpty()) groups.add(TextGroup())
                groups.last().lines.add(t)
            } else if (blankRun == 0 && groups.isNotEmpty()) {
                // Empty content but no blank run yet — still same group
            }
            current.setLength(0)
        }

        for (c in raw) { when {
            c == '\u0013' -> inFieldCode = true
            c == '\u0014' -> inFieldCode = false
            c == '\u0015' -> Unit
            inFieldCode -> Unit
            c == '\r' || c == '\n' || c == '\u000C' -> {
                flushParagraph()
                blankRun++
            }
            c == '\t' -> { current.append("    "); blankRun = 0 }
            c.code < 0x20 || c == '\u007F' || c == '\uFFFF' -> Unit
            else -> { current.append(c); blankRun = 0 }
        } }
        flushParagraph()

        // Now process each group independently
        for (group in groups) {
            val lines = group.lines
            if (lines.isEmpty()) continue

            if (lines.size == 1) {
                blocks += WordBlock.Paragraph(listOf(WordRun(lines[0])))
                continue
            }

            // Try table detection WITHIN this group only
            // Pass 1: Split by tabs / 2+ spaces
            val pass1Rows = lines.map { it.split(Regex("\\t|\\s{2,}")).filter { c -> c.isNotBlank() } }
            val pass1ColCounts = pass1Rows.map { it.size }
            val pass1Mode = pass1ColCounts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 1

            if (pass1Mode >= 2 && lines.size >= 2) {
                val tableRows = pass1Rows.filter { it.size in 2..(pass1Mode + 2) }
                if (tableRows.size >= 2) {
                    val padded = tableRows.map { r ->
                        if (r.size < pass1Mode) r + List(pass1Mode - r.size) { "" }
                        else r.take(pass1Mode)
                    }
                    blocks += WordBlock.Table(rows = padded.map { r ->
                        WordTableRow(cells = r.map { c -> WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c))))) })
                    })
                    continue
                }
            }

            // No table detected — render as paragraphs
            for (line in lines) {
                blocks += WordBlock.Paragraph(listOf(WordRun(line)))
            }
        }
    }

    fun parseLegacyPpt(context: Context, uri: Uri): WordDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return WordDocument(emptyList())
        val text = extractReadableText(bytes); if (text.isBlank()) return WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Could not extract text from PPT file")))))
        return WordDocument(text.chunked(2000).map { WordBlock.Paragraph(listOf(WordRun(it))) })
    }

    private fun extractReadableText(bytes: ByteArray): String {
        val result = StringBuilder(); val utf16Runs = mutableListOf<String>(); var currentRun = StringBuilder(); var i = 0
        while (i < bytes.size - 1) { val lo = bytes[i].toInt() and 0xFF; val hi = bytes[i + 1].toInt() and 0xFF
            if (lo in 0x20..0x7E && hi == 0) currentRun.append(lo.toChar()) else { if (currentRun.length >= 4) utf16Runs.add(currentRun.toString()); currentRun = StringBuilder() }; i += 2 }
        if (currentRun.length >= 4) utf16Runs.add(currentRun.toString())
        val realText = utf16Runs.distinct().sortedByDescending { it.length }.filter { run -> run.count { it.isLetter() } > run.length * 0.5 }
        for (run in realText) { result.appendLine(run); result.appendLine() }
        if (result.length < 50) { result.clear(); currentRun = StringBuilder(); for (b in bytes) { val c = b.toInt() and 0xFF; if (c in 0x20..0x7E || c == 10 || c == 13 || c == 9) currentRun.append(c.toChar()) else { if (currentRun.length >= 8) result.appendLine(currentRun.toString()); currentRun = StringBuilder() } }; if (currentRun.length >= 8) result.appendLine(currentRun.toString()) }
        return result.toString().trim()
    }
}
