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
                        is WordBlock.Table -> appendLine("[$idx] TABLE ${b.rows.size}r x ${b.rows.maxOfOrNull { it.cells.size } ?: 0}c")
                        is WordBlock.Paragraph -> {
                            val txt = b.runs.joinToString("") { it.text }
                            appendLine("[$idx] PARA (${txt.length}chars) \"${txt.take(120)}\"")
                        }
                        else -> appendLine("[$idx] ${b::class.simpleName}")
                    }
                }
            })
        } catch (_: Exception) {}
        return if (blocks.isEmpty()) WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Empty document"))))) else WordDocument(blocks)
    }

    private fun parseWithTableMarkers(raw: String, blocks: MutableList<WordBlock>) {
        var sb = StringBuilder()
        var inFieldCode = false
        val preTableParagraphs = mutableListOf<String>()
        val tableCells = mutableListOf<String>()
        var foundFirstCellMarker = false
        var inTable = false

        fun flushCellContent() {
            val t = sb.toString().trim()
            if (inTable) tableCells.add(t) else if (t.isNotEmpty()) preTableParagraphs.add(t)
            sb.setLength(0)
        }

        for (c in raw) {
            when {
                c == '\u0013' -> inFieldCode = true
                c == '\u0014' -> inFieldCode = false
                c == '\u0015' -> Unit
                inFieldCode -> Unit
                c == DocTextExtractor.CELL_MARKER -> {
                    if (!foundFirstCellMarker) { foundFirstCellMarker = true; inTable = true; val t = sb.toString().trim(); if (t.isNotEmpty()) tableCells.add(t); sb.setLength(0) }
                    else flushCellContent()
                }
                c == DocTextExtractor.ROW_MARKER -> { if (!foundFirstCellMarker) { foundFirstCellMarker = true; inTable = true }; flushCellContent() }
                c == '\r' || c == '\n' -> { if (inTable) sb.append(' ') else flushCellContent() }
                c == '\u000C' -> flushCellContent()
                c == '\t' -> sb.append("    ")
                c.code < 0x20 || c == '\u007F' || c == '\uFFFF' -> Unit
                else -> sb.append(c)
            }
        }
        if (inTable) { val t = sb.toString().trim(); if (t.isNotEmpty()) tableCells.add(t) }
        else { val t = sb.toString().trim(); if (t.isNotEmpty()) preTableParagraphs.add(t) }

        // Emit pre-table paragraphs
        for (p in preTableParagraphs) {
            emitFormattedParagraph(p, blocks)
        }

        if (tableCells.size < 4) {
            for (c in tableCells) { if (c.isNotBlank()) emitFormattedParagraph(c, blocks) }
            return
        }

        val cleanedCells = tableCells.toMutableList()

        // Detect column count
        var bestCols = 4
        if (cleanedCells.size >= 4) {
            val first = cleanedCells[0].trim()
            if (first.length < 10) bestCols = 4
        }

        // Detect separator pattern
        val stride = bestCols + 1
        var separatorCount = 0; var checkGroups = 0; var ci = bestCols
        while (ci + 1 < cleanedCells.size && checkGroups < 5) {
            if (cleanedCells[ci].isBlank() && ci + 1 < cleanedCells.size && cleanedCells[ci + 1].isNotBlank()) separatorCount++
            ci += stride; checkGroups++
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

        // Split off trailing non-data rows
        val dataRows = mutableListOf<List<String>>()
        // Collect extra content as a combined string per section
        val extraSections = mutableListOf<String>()
        var currentExtraSection = StringBuilder()
        var inExtraSection = false

        for (row in rows) {
            val firstCell = row.firstOrNull() ?: ""
            val allText = row.joinToString(" ")
            val isNonData = firstCell.length > 40 ||
                firstCell.contains("BORANG", ignoreCase = true) ||
                firstCell.contains("INVENTORI PERSONALITI", ignoreCase = true) ||
                firstCell.contains("Helaian", ignoreCase = true) ||
                firstCell.contains("Sidek's Personality", ignoreCase = true) ||
                firstCell.contains("Nama", ignoreCase = true) ||
                firstCell.contains("Tarikh", ignoreCase = true) ||
                firstCell.contains("Jantina", ignoreCase = true) ||
                allText.length > 200 ||
                // Detect answer form cells: short cells with numbers, Y, T
                (firstCell.length < 10 && (allText.contains("Y") && allText.contains("T")))
            if (isNonData) {
                if (!inExtraSection) {
                    currentExtraSection = StringBuilder()
                    inExtraSection = true
                }
                for (cell in row) {
                    if (cell.isNotBlank()) {
                        if (currentExtraSection.isNotEmpty()) currentExtraSection.append(" ")
                        currentExtraSection.append(cell)
                    }
                }
            } else {
                if (inExtraSection) {
                    extraSections.add(currentExtraSection.toString())
                    currentExtraSection = StringBuilder()
                    inExtraSection = false
                }
                dataRows.add(row)
            }
        }
        if (inExtraSection && currentExtraSection.isNotEmpty()) {
            extraSections.add(currentExtraSection.toString())
        }

        if (dataRows.size >= 2) {
            blocks += WordBlock.Table(rows = dataRows.map { r ->
                WordTableRow(
                    isHeader = r == dataRows.first(),
                    cells = r.map { c -> WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c))))) }
                )
            })
        }

        // Process extra content sections
        for (section in extraSections) {
            processExtraContent(section, blocks)
        }
    }

    /**
     * Process extra content that was after the main table.
     * Detect sub-tables (answer forms, scoring forms) and long paragraphs.
     */
    private fun processExtraContent(text: String, blocks: MutableList<WordBlock>) {
        if (text.isBlank()) return
        val trimmed = text.trim()

        // Detect answer form pattern: "1 Y T 17 Y T..." or "1 Y  T 18 Y  T..."
        // Pattern: number + Y + T repeated in a grid
        val answerFormPattern = Regex("(?:^|\\s)(\\d+)\\s+Y\\s+T")
        if (answerFormPattern.containsMatchIn(trimmed) && trimmed.length > 200) {
            // This is an answer form — extract the header and body
            val lines = trimmed.split(Regex("\\s{2,}")).filter { it.isNotBlank() }
            // Try to detect the form structure
            // Header part: title + name/date fields
            val headerParts = mutableListOf<String>()
            val tableParts = mutableListOf<String>()
            var inTable = false
            for (part in lines) {
                if (answerFormPattern.containsMatchIn(part)) inTable = true
                if (inTable) tableParts.add(part) else headerParts.add(part)
            }
            // Emit header as paragraphs
            for (h in headerParts) {
                if (h.isNotBlank() && h.length > 1) {
                    emitFormattedParagraph(h, blocks)
                }
            }
            // Emit table parts as paragraphs (they're too complex to render as a table)
            if (tableParts.isNotEmpty()) {
                val tableText = tableParts.joinToString(" ")
                // Split the answer form into rows: each row has "N Y T" pattern
                val rowPattern = Regex("(\\d+)\\s+Y\\s+T\\s*")
                val matches = rowPattern.findAll(tableText).toList()
                if (matches.size > 5) {
                    // Build a table: columns = Number, Y, T (repeated)
                    // Group into rows of 10 (the form has 10 columns per row)
                    val allNumbers = mutableListOf<String>()
                    for (m in matches) {
                        allNumbers.add(m.groupValues[1])
                    }
                    // The form has 10 items per row, each with Y T
                    // Row 1: 1 Y T 17 Y T 33 Y T ... 145 Y T  AGR
                    // Row 2: 2 Y T 18 Y T 34 Y T ... 146 Y T  ANA
                    // Each row has 10 columns: No, Y, T
                    val tableRows = mutableListOf<MutableList<String>>()
                    val rowSize = 10
                    for (i in allNumbers.indices step rowSize) {
                        val row = mutableListOf<String>()
                        for (j in 0 until rowSize) {
                            if (i + j < allNumbers.size) {
                                row.add(allNumbers[i + j])
                                row.add("Y")
                                row.add("T")
                            }
                        }
                        if (row.isNotEmpty()) tableRows.add(row)
                    }
                    if (tableRows.size >= 2) {
                        blocks += WordBlock.Table(rows = tableRows.map { r ->
                            WordTableRow(cells = r.map { c ->
                                WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c)))))
                            })
                        })
                    }
                } else {
                    // Not enough rows for a table, emit as paragraphs
                    emitFormattedParagraph(tableText, blocks)
                }
            }
            return
        }

        // Detect scoring form pattern: "TRET PERSONALITI KADAR SKOR PERATUS"
        if (trimmed.contains("TRET", ignoreCase = true) && trimmed.contains("SKOR", ignoreCase = true) && trimmed.contains("PERATUS", ignoreCase = true) && trimmed.length > 200) {
            // This is the scoring form
            // Split at trait names
            val traitNames = listOf("Agresif", "Analitik", "Autonomi", "Bersandar", "Ekstrovert", "Intelektual", "Introvert",
                "KepeIbagaian", "Kepelbagaian", "Ketahanan", "Kritik Diri", "Mengawal", "Menolong", "Sokongan", "Struktur",
                "Pencapaian", "Kejujuran")
            val sections = mutableListOf<String>()
            var current = StringBuilder()

            // Split at double spaces or trait names
            val parts = trimmed.split(Regex("\\s{3,}"))
            for (part in parts) {
                val isTraitStart = traitNames.any { part.startsWith(it, ignoreCase = true) }
                if (isTraitStart && current.isNotEmpty()) {
                    sections.add(current.toString().trim())
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(" ")
                current.append(part)
            }
            if (current.isNotEmpty()) sections.add(current.toString().trim())

            // Emit each section
            for (s in sections) {
                if (s.isNotBlank()) emitFormattedParagraph(s, blocks)
            }
            return
        }

        // Detect trait descriptions: "Agresif Skor rendah" or "Analitikal Skor rendah"
        val traitPattern = Regex("(?=\\b(?:Agresif|Analitik|Autonomi|Bersandar|Ekstrovert|Intelektual|Introvert|Kepe[il]bagai(?:an)?|Ketahanan|Kritik\\s+Diri|Mengawal|Menolong|Sokongan|Struktur|Pencapaian|Kejujuran)\\b)")
        val traitParts = trimmed.split(traitPattern).filter { it.isNotBlank() }
        if (traitParts.size > 3) {
            for (tp in traitParts) {
                if (tp.isNotBlank()) emitFormattedParagraph(tp.trim(), blocks)
            }
            return
        }

        // Detect numbered items: "1. Agresif\nTrait personality..."
        val numberedPattern = Regex("(?=\\b\\d+\\.\\s)")
        val numParts = trimmed.split(numberedPattern).filter { it.isNotBlank() }
        if (numParts.size > 3) {
            for (np in numParts) {
                if (np.isNotBlank()) emitFormattedParagraph(np.trim(), blocks)
            }
            return
        }

        // Detect "Pentafsiran IPS" section
        if (trimmed.contains("Pentafsiran IPS", ignoreCase = true) && trimmed.length > 500) {
            val pentafsiranIdx = trimmed.indexOf("Pentafsiran IPS", ignoreCase = true)
            val before = trimmed.substring(0, pentafsiranIdx).trim()
            val after = trimmed.substring(pentafsiranIdx).trim()
            if (before.isNotBlank()) emitFormattedParagraph(before, blocks)
            if (after.isNotBlank()) {
                // Split after at trait names
                val afterParts = after.split(traitPattern).filter { it.isNotBlank() }
                if (afterParts.size > 1) {
                    for (ap in afterParts) {
                        if (ap.isNotBlank()) emitFormattedParagraph(ap.trim(), blocks)
                    }
                } else {
                    emitFormattedParagraph(after, blocks)
                }
            }
            return
        }

        // Default: emit as paragraphs
        emitFormattedParagraph(trimmed, blocks)
    }

    private fun emitFormattedParagraph(text: String, blocks: MutableList<WordBlock>) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        val sentences = splitIntoLogicalParagraphs(trimmed)
        for (sentence in sentences) {
            if (sentence.isBlank()) continue
            blocks += WordBlock.Paragraph(listOf(WordRun(sentence)))
        }
    }

    private fun splitIntoLogicalParagraphs(text: String): List<String> {
        // Check for numbered items
        val numberedPattern = Regex("(?=\\b\\d+\\.\\s)")
        val parts = text.split(numberedPattern).filter { it.isNotBlank() }
        if (parts.size > 1) return parts.map { it.trim() }

        // Check for title + body pattern
        val titlePattern = Regex("^([A-Z][A-Z\\s()]+)\\s{2,}(.+)")
        val titleMatch = titlePattern.find(text)
        if (titleMatch != null) {
            val title = titleMatch.groupValues[1].trim()
            val body = titleMatch.groupValues[2].trim()
            val result = mutableListOf<String>()
            if (title.length > 3) result.add(title)
            result.addAll(splitBodyIntoParagraphs(body))
            return result
        }

        // Check for "Trait personality" pattern
        val traitPattern = Regex("(?=Trait personality yang menunjukkan)")
        val traitParts = text.split(traitPattern).filter { it.isNotBlank() }
        if (traitParts.size > 1) return traitParts.map { it.trim() }

        return listOf(text)
    }

    private fun splitBodyIntoParagraphs(text: String): List<String> {
        val result = mutableListOf<String>()
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
            c == '\r' || c == '\n' || c == '\u000C' -> { flushParagraph(); blankRun++ }
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
