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
        val blocks = mutableListOf<WordBlock>()
        if (docText.hasTableMarkers) parseWithTableMarkers(docText.text, blocks) else parseAsParagraphs(docText.text, blocks)
        return if (blocks.isEmpty()) WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Empty document"))))) else WordDocument(blocks)
    }

    private fun parseWithTableMarkers(raw: String, blocks: MutableList<WordBlock>) {
        var sb = StringBuilder(); var inFieldCode = false
        val tableRows = mutableListOf<List<String>>(); var currentRow = mutableListOf<String>(); var currentCell = StringBuilder(); var inTable = false
        fun flushCell() { currentRow.add(currentCell.toString().trim()); currentCell = StringBuilder() }
        fun flushRow() { flushCell(); if (currentRow.isNotEmpty()) { tableRows.add(currentRow.toList()); currentRow = mutableListOf() } }
        fun flushParagraph() { val t = sb.toString().trim(); if (t.isNotEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun(t))); sb.setLength(0) }
        fun finalizeTable() { flushRow()
            if (tableRows.size >= 2 && tableRows.maxOf { it.size } >= 2) blocks += WordBlock.Table(rows = tableRows.map { r -> WordTableRow(cells = r.map { c -> WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c))))) }) })
            else for (r in tableRows) blocks += WordBlock.Paragraph(listOf(WordRun(r.joinToString("    ")))); tableRows.clear(); inTable = false }
        var i = 0; while (i < raw.length) { val c = raw[i]; when {
            c == '\u0013' -> inFieldCode = true; c == '\u0014' -> inFieldCode = false; c == '\u0015' -> Unit; inFieldCode -> Unit
            c == DocTextExtractor.CELL_MARKER -> { inTable = true; flushCell() }; c == DocTextExtractor.ROW_MARKER -> { inTable = true; flushRow() }
            c == '\r' || c == '\n' -> { if (inTable) { flushCell(); var peek = i + 1; while (peek < raw.length && (raw[peek] == '\r' || raw[peek] == '\n')) peek++; if (peek >= raw.length || (raw[peek] != DocTextExtractor.CELL_MARKER && raw[peek] != DocTextExtractor.ROW_MARKER)) { if (tableRows.isNotEmpty()) finalizeTable() } } else flushParagraph() }
            c == '\u000C' -> flushParagraph(); c == '\t' -> { if (inTable) currentCell.append("    ") else sb.append("    ") }
            c.code < 0x20 || c == '\u007F' || c == '\uFFFF' -> Unit; else -> { if (inTable) currentCell.append(c) else sb.append(c) } }; i++ }
        if (inTable) finalizeTable() else flushParagraph()
    }

    private fun parseAsParagraphs(raw: String, blocks: MutableList<WordBlock>) {
        val paragraphs = mutableListOf<String>(); val current = StringBuilder(); var inFieldCode = false
        for (c in raw) { when { c == '\u0013' -> inFieldCode = true; c == '\u0014' -> inFieldCode = false; c == '\u0015' -> Unit; inFieldCode -> Unit
            c == '\r' || c == '\n' || c == '\u000C' -> { val t = current.toString().trim(); if (t.isNotEmpty()) paragraphs.add(t); current.setLength(0) }
            c == '\t' -> current.append("    "); c.code < 0x20 || c == '\u007F' || c == '\uFFFF' -> Unit; else -> current.append(c) } }
        val last = current.toString().trim(); if (last.isNotEmpty()) paragraphs.add(last)

        // Two-pass approach: first collect all consecutive "tabular" lines,
        // then group them by column count to find the dominant table pattern.
        var i = 0
        while (i < paragraphs.size) {
            val line = paragraphs[i]

            // Skip separator lines (___________, ------------, etc.)
            val isSeparator = line.all { it == '_' || it == '-' || it == '=' || it == ' ' || it == '.' }

            // Detect tab/space columns
            val columns = line.split(Regex("\\t|\\s{2,}")).filter { it.isNotBlank() }
            if (columns.size >= 2 && !isSeparator) {
                // Collect all consecutive non-empty lines as potential table rows
                val candidateRows = mutableListOf<String>()
                var j = i
                while (j < paragraphs.size) {
                    val candidate = paragraphs[j]
                    val candidateSep = candidate.all { it == '_' || it == '-' || it == '=' || it == ' ' || it == '.' }
                    if (candidate.isBlank()) break
                    candidateRows.add(candidate)
                    j++
                }

                if (candidateRows.size >= 2) {
                    // Parse all rows into columns
                    val parsedRows = candidateRows.map { row ->
                        row.split(Regex("\\t|\\s{2,}")).filter { it.isNotBlank() }
                    }.filter { it.isNotEmpty() }

                    if (parsedRows.size >= 2) {
                        // Find the MOST COMMON column count (mode) >= 2
                        val colCounts = parsedRows.map { it.size }
                        val colCountGroups = colCounts.groupingBy { it }.eachCount()
                        val targetCols = colCountGroups.filter { it.key >= 2 }.maxByOrNull { it.value }?.key
                            ?: parsedRows.maxOf { it.size }

                        // Filter rows to those within ±40% of target or at least 2 columns
                        val tableRows = parsedRows.filter { row ->
                            row.size >= 2 && (row.size <= targetCols + 2)
                        }

                        if (tableRows.size >= 2 && targetCols >= 2) {
                            // Pad all rows to targetCols
                            val paddedRows = tableRows.map { row ->
                                if (row.size < targetCols) row + List(targetCols - row.size) { "" }
                                else row.take(targetCols)
                            }
                            blocks += WordBlock.Table(rows = paddedRows.map { r ->
                                WordTableRow(cells = r.map { c ->
                                    WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c)))))
                                })
                            })
                            i = j
                            continue
                        }
                    }
                }
            }

            blocks += WordBlock.Paragraph(listOf(WordRun(line)))
            i++
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
