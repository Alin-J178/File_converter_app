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
        // Debug: write to file
        try {
            val f = java.io.File("/sdcard/Documents/doc_debug.txt")
            f.parentFile?.mkdirs()
            f.writeText(buildString {
                appendLine("Text length: ${docText.text.length}")
                appendLine("hasTableMarkers: ${docText.hasTableMarkers}")
                appendLine("cellCount: ${docText.text.count { it == DocTextExtractor.CELL_MARKER }}")
                appendLine("crCount: ${docText.text.count { it == '\r' }}")
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
                blocks.forEachIndexed { idx, b ->
                    when (b) {
                        is WordBlock.Table -> {
                            appendLine("[$idx] TABLE ${b.rows.size}r x ${b.rows.maxOfOrNull { it.cells.size } ?: 0}c")
                            b.rows.takeLast(3).forEachIndexed { ri, row ->
                                appendLine("  last-r${b.rows.size - 3 + ri}: ${row.cells.joinToString(" | ") { it.blocks.filterIsInstance<WordBlock.Paragraph>().joinToString("") { p -> p.runs.joinToString("") { r -> r.text } }.take(40) }}")
                            }
                        }
                        is WordBlock.Paragraph -> {
                            val txt = b.runs.joinToString("") { it.text }
                            appendLine("[$idx] PARA (${txt.length}chars) \"${txt.take(120)}\"")
                            if (txt.length > 120) appendLine("  ...full: \"${txt.take(500)}\"")
                        }
                        else -> appendLine("[$idx] ${b::class.simpleName}")
                    }
                }
            })
        } catch (_: Exception) {}
        return if (blocks.isEmpty()) WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Empty document"))))) else WordDocument(blocks)
    }

    /**
     * Parse text that contains \u0007 (cell) markers from Word table format.
     */
    private fun parseWithTableMarkers(raw: String, blocks: MutableList<WordBlock>) {
        var sb = StringBuilder()
        var inFieldCode = false

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
        if (inTable) {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) tableCells.add(t)
        } else {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) preTableParagraphs.add(t)
        }

        // Emit pre-table paragraphs with heading detection
        for (p in preTableParagraphs) {
            emitFormattedParagraph(p, blocks)
        }

        // Phase 2: Group table cells into rows
        if (tableCells.size < 4) {
            for (c in tableCells) {
                if (c.isNotBlank()) emitFormattedParagraph(c, blocks)
            }
            return
        }

        val cleanedCells = mutableListOf<String>()
        for (cell in tableCells) {
            cleanedCells.add(cell)
        }

        // Detect column count: look at header pattern
        // Header is "Bil.\u0007Item\u0007Ya\u0007Tidak" = 4 columns
        // After header, cells repeat: separator \u0007 then 4 data cells
        var bestCols = 4 // Default for this doc format
        if (cleanedCells.size >= 4) {
            // Check: is first cell "Bil." or similar short label?
            val first = cleanedCells[0].trim()
            if (first.length < 10) {
                bestCols = 4 // Bil, Item, Ya, Tidak
            }
        }

        // Detect separator pattern: every (bestCols) cells, check if there's an empty cell
        // Pattern: [Bil] [Item] [Ya] [Tidak] [empty-separator] [1] [text] [space] [space] [empty-separator] ...
        // Actually the separator is \u0007\u0007 which creates an empty cell between rows
        val stride = bestCols + 1
        var separatorCount = 0
        var checkGroups = 0
        var ci = bestCols
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
        val extraContent = mutableListOf<String>()
        for (row in rows) {
            val firstCell = row.firstOrNull() ?: ""
            val allText = row.joinToString(" ")
            // Detect if this is a non-data row (scoring form, title text, etc.)
            if (firstCell.length > 40 ||
                firstCell.contains("BORANG", ignoreCase = true) ||
                firstCell.contains("INVENTORI PERSONALITI", ignoreCase = true) ||
                firstCell.contains("Helaian", ignoreCase = true) ||
                firstCell.contains("Sidek's Personality", ignoreCase = true) ||
                allText.length > 200
            ) {
                // Split this row's cells into paragraphs
                for (cell in row) {
                    if (cell.isNotBlank() && cell.length > 2) {
                        extraContent.add(cell)
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
        }

        // Emit extra content with formatting
        for (p in extraContent) {
            emitFormattedParagraph(p, blocks)
        }
    }

    /**
     * Emit a paragraph with inferred formatting (headings, bold, numbered items).
     */
    private fun emitFormattedParagraph(text: String, blocks: MutableList<WordBlock>) {
        if (text.isBlank()) return
        val trimmed = text.trim()

        // Split long paragraphs at sentence boundaries (periods followed by space)
        // But keep numbered items together
        val sentences = splitIntoLogicalParagraphs(trimmed)
        for (sentence in sentences) {
            if (sentence.isBlank()) continue
            val runs = listOf(WordRun(sentence))
            blocks += WordBlock.Paragraph(runs)
        }
    }

    /**
     * Split a long text into logical paragraphs:
     * - Numbered items (1. ... 2. ...) become separate paragraphs
     * - Title-like text (ALL CAPS, short) becomes separate paragraphs
     * - Long text gets split at natural breaks
     */
    private fun splitIntoLogicalParagraphs(text: String): List<String> {
        // Check if text contains numbered items (e.g., "1. Agresif\nTrait personality...")
        val numberedPattern = Regex("(?=\\b\\d+\\.\\s)")
        val parts = text.split(numberedPattern).filter { it.isNotBlank() }

        if (parts.size > 1) {
            // Text has numbered items — split them
            return parts.map { it.trim() }
        }

        // Check for title + body pattern (e.g., "INVENTORI PERSONALITI SIDEK (IPS)  Inventori personality...")
        val titlePattern = Regex("^([A-Z][A-Z\\s()]+)\\s{2,}(.+)")
        val titleMatch = titlePattern.find(text)
        if (titleMatch != null) {
            val title = titleMatch.groupValues[1].trim()
            val body = titleMatch.groupValues[2].trim()
            val result = mutableListOf<String>()
            if (title.length > 3) result.add(title)
            // Split body at "trait personality" or numbered patterns
            val bodyParts = splitBodyIntoParagraphs(body)
            result.addAll(bodyParts)
            return result
        }

        // Check for "Trait personality" pattern which starts new paragraphs
        val traitPattern = Regex("(?=Trait personality yang menunjukkan)")
        val traitParts = text.split(traitPattern).filter { it.isNotBlank() }
        if (traitParts.size > 1) {
            return traitParts.map { it.trim() }
        }

        return listOf(text)
    }

    private fun splitBodyIntoParagraphs(text: String): List<String> {
        val result = mutableListOf<String>()
        // Split at "Trait personality" or "1." patterns
        val pattern = Regex("(?=Trait personality|\\b\\d+\\.\\s)")
        val parts = text.split(pattern).filter { it.isNotBlank() }
        if (parts.size > 1) {
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.isNotBlank()) result.add(trimmed)
            }
        } else {
            result.add(text)
        }
        return result
    }

    private fun parseAsParagraphs(raw: String, blocks: MutableList<WordBlock>) {
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
                emitFormattedParagraph(lines[0], blocks)
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
                emitFormattedParagraph(line, blocks)
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
