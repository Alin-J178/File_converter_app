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
                appendLine("cellCount: ${docText.text.count { it == DocTextExtractor.CELL_MARKER }}")
                appendLine("rowCount: ${docText.text.count { it == DocTextExtractor.ROW_MARKER }}")
                appendLine("crCount: ${docText.text.count { it == '\r' }}")
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
                blocks.take(20).forEachIndexed { idx, b ->
                    when (b) {
                        is WordBlock.Table -> {
                            appendLine("[$idx] TABLE ${b.rows.size}r x ${b.rows.maxOfOrNull { it.cells.size } ?: 0}c")
                            // Show last 5 rows
                            b.rows.takeLast(5).forEachIndexed { ri, row ->
                                appendLine("  last-r${b.rows.size - 5 + ri}: ${row.cells.joinToString(" | ") { it.blocks.filterIsInstance<WordBlock.Paragraph>().joinToString("") { p -> p.runs.joinToString("") { r -> r.text } }.take(30) }}")
                            }
                        }
                        is WordBlock.Paragraph -> appendLine("[$idx] PARA \"${b.runs.joinToString("") { it.text }.take(80)}\"")
                        else -> appendLine("[$idx] ${b::class.simpleName}")
                    }
                }
            })
        } catch (_: Exception) {}
        return if (blocks.isEmpty()) WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Empty document"))))) else WordDocument(blocks)
    }

    /**
     * Parse text that contains \u0007 (cell) markers from Word table format.
     * Strategy: split text into pre-table paragraphs and table cells,
     * then group cells into rows based on detected column count.
     */
    private fun parseWithTableMarkers(raw: String, blocks: MutableList<WordBlock>) {
        var sb = StringBuilder()
        var inFieldCode = false

        // Phase 1: Collect pre-table paragraphs and table cells separately
        val preTableParagraphs = mutableListOf<String>()
        val tableCells = mutableListOf<String>()
        var foundFirstCellMarker = false
        var inTable = false

        fun flushCellContent() {
            val t = sb.toString().trim()
            if (inTable) {
                tableCells.add(t)
            } else if (t.isNotEmpty()) {
                preTableParagraphs.add(t)
            }
            sb.setLength(0)
        }

        for (c in raw) {
            when {
                c == '\u0013' -> inFieldCode = true
                c == '\u0014' -> inFieldCode = false
                c == '\u0015' -> Unit
                inFieldCode -> Unit
                c == DocTextExtractor.CELL_MARKER -> {
                    if (!foundFirstCellMarker) {
                        foundFirstCellMarker = true
                        inTable = true
                        // Transfer any accumulated sb content
                        val t = sb.toString().trim()
                        if (t.isNotEmpty()) tableCells.add(t)
                        sb.setLength(0)
                    } else {
                        flushCellContent()
                    }
                }
                c == DocTextExtractor.ROW_MARKER -> {
                    if (!foundFirstCellMarker) {
                        foundFirstCellMarker = true
                        inTable = true
                    }
                    flushCellContent()
                }
                c == '\r' || c == '\n' -> {
                    if (inTable) {
                        // \r in table context: ignore (cells are separated by \u0007, not \r)
                        // Just append a space so wrapped text doesn't merge words
                        sb.append(' ')
                    } else {
                        flushCellContent()
                    }
                }
                c == '\u000C' -> flushCellContent()
                c == '\t' -> sb.append("    ")
                c.code < 0x20 || c == '\u007F' || c == '\uFFFF' -> Unit
                else -> sb.append(c)
            }
        }
        // Flush remaining content
        if (inTable) {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) tableCells.add(t)
        } else {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) preTableParagraphs.add(t)
        }

        // Emit pre-table paragraphs
        for (p in preTableParagraphs) {
            blocks += WordBlock.Paragraph(listOf(WordRun(p)))
        }

        // Phase 2: Group table cells into rows
        if (tableCells.size < 4) {
            // Not enough cells for a table — emit as paragraphs
            for (c in tableCells) {
                if (c.isNotBlank()) blocks += WordBlock.Paragraph(listOf(WordRun(c)))
            }
            return
        }

        // Filter out truly empty cells that are likely row separators
        // Keep single-space cells (they're checkbox values)
        // But remove cells that are completely empty AND surrounded by other empties
        val cleanedCells = mutableListOf<String>()
        for (cell in tableCells) {
            cleanedCells.add(cell)
        }

        // Detect column count from the header row
        var bestCols = 0
        if (cleanedCells.isNotEmpty()) {
            var headerLen = 0
            for (ci in cleanedCells.indices) {
                val cell = cleanedCells[ci]
                if (ci > 0 && cell.isBlank() && ci + 1 < cleanedCells.size && cleanedCells[ci + 1].toIntOrNull() != null) {
                    headerLen = ci; break
                }
                if (ci > 0 && cell.toIntOrNull() != null && !cleanedCells[ci - 1].isBlank() && cleanedCells[ci - 1].toIntOrNull() == null) {
                    headerLen = ci; break
                }
                headerLen = ci + 1
            }
            bestCols = headerLen.coerceIn(2, 10)
        }
        if (bestCols < 2) bestCols = 4

        // Detect if there are empty separator cells between rows
        // Pattern: every (bestCols + 1) cells, the last one is empty (separator from \u0007\u0007)
        // Check: count empty cells in the first few groups
        val stride = bestCols + 1
        var separatorCount = 0
        var checkGroups = 0
        var ci = bestCols // start after header
        while (ci + 1 < cleanedCells.size && checkGroups < 5) {
            if (cleanedCells[ci].isBlank() && ci + 1 < cleanedCells.size && cleanedCells[ci + 1].isNotBlank()) {
                separatorCount++
            }
            ci += stride
            checkGroups++
        }
        val hasSeparators = checkGroups > 0 && separatorCount >= checkGroups / 2
        val effectiveStride = if (hasSeparators) stride else bestCols

        // Group cells into rows
        val rows = mutableListOf<MutableList<String>>()
        var idx = 0
        while (idx < cleanedCells.size) {
            val end = (idx + bestCols).coerceAtMost(cleanedCells.size)
            val row = cleanedCells.subList(idx, end).toMutableList()
            while (row.size < bestCols) row.add("")
            rows.add(row)
            idx += effectiveStride
        }

        // Split off trailing non-data rows and emit as paragraphs
        val dataRows = mutableListOf<List<String>>()
        val extraParagraphs = mutableListOf<String>()
        for (row in rows) {
            val firstCell = row.firstOrNull() ?: ""
            // If first cell is very long (>40 chars) or looks like section text, split it out
            if (firstCell.length > 40 || firstCell.contains("BORANG") || firstCell.contains("INVENTORI PERSONALITI")) {
                // This row is not table data — emit all non-empty cells as paragraphs
                for (cell in row) {
                    if (cell.isNotBlank() && cell.length > 3) {
                        extraParagraphs.add(cell)
                    }
                }
            } else {
                dataRows.add(row)
            }
        }

        if (dataRows.size >= 2) {
            blocks += WordBlock.Table(rows = dataRows.map { r ->
                WordTableRow(
                    isHeader = r == dataRows.first(),
                    cells = r.map { c ->
                        WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c)))))
                    }
                )
            })
            // Emit extra content that was after the table
            for (p in extraParagraphs) {
                blocks += WordBlock.Paragraph(listOf(WordRun(p)))
            }
        } else {
            // Fallback: emit as paragraphs
            for (c in cleanedCells) {
                if (c.isNotBlank()) blocks += WordBlock.Paragraph(listOf(WordRun(c)))
            }
        }
    }

    private fun parseAsParagraphs(raw: String, blocks: MutableList<WordBlock>) {
        // Split into groups separated by blank lines (consecutive CR/LF)
        data class TextGroup(val lines: MutableList<String> = mutableListOf())
        val groups = mutableListOf<TextGroup>()
        val current = StringBuilder()
        var inFieldCode = false
        var blankRun = 0

        fun flushParagraph() {
            val t = current.toString().trim()
            if (t.isNotEmpty()) {
                if (blankRun > 0 && groups.isNotEmpty()) groups.add(TextGroup())
                if (groups.isEmpty()) groups.add(TextGroup())
                groups.last().lines.add(t)
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

        for (group in groups) {
            val lines = group.lines
            if (lines.isEmpty()) continue
            if (lines.size == 1) {
                blocks += WordBlock.Paragraph(listOf(WordRun(lines[0])))
                continue
            }
            val pass1Rows = lines.map { it.split(Regex("\t|\u0007|\\s{2,}")).filter { c -> c.isNotBlank() } }
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
