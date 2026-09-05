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
        if (docText.hasTableMarkers) parseWithTableMarkers(docText.text, blocks)
        else parseAsParagraphs(docText.text, blocks)

        // Debug: write to file
        try {
            val f = java.io.File("/sdcard/Documents/doc_debug.txt")
            f.parentFile?.mkdirs()
            f.writeText(buildString {
                appendLine("Text length: ${docText.text.length}")
                appendLine("hasTableMarkers: ${docText.hasTableMarkers}")
                appendLine("cellCount: ${docText.text.count { it == DocTextExtractor.CELL_MARKER }}")
                appendLine("rowCount: ${docText.text.count { it == DocTextExtractor.ROW_MARKER }}")
                appendLine("crCount: ${docText.text.count { it == '\r' }}")
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
        val allCells = mutableListOf<String>()
        var foundFirstCellMarker = false

        fun flushCell() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) allCells.add(t)
            sb.setLength(0)
        }

        for (c in raw) {
            when {
                c == '\u0013' -> inFieldCode = true
                c == '\u0014' -> inFieldCode = false
                c == '\u0015' -> Unit
                inFieldCode -> Unit
                c == DocTextExtractor.CELL_MARKER -> {
                    if (!foundFirstCellMarker) { foundFirstCellMarker = true }
                    flushCell()
                }
                c == DocTextExtractor.ROW_MARKER -> { flushCell() }
                c == '\r' || c == '\n' -> { if (foundFirstCellMarker) sb.append(' ') else flushCell() }
                c == '\u000C' -> flushCell()
                c == '\t' -> sb.append("    ")
                c.code < 0x20 || c == '\u007F' || c == '\uFFFF' -> Unit
                else -> sb.append(c)
            }
        }
        flushCell()

        // DIAG: dump cells to file so we can see real structure
        try {
            val df = java.io.File("/sdcard/Documents/doc_cells.txt")
            df.parentFile?.mkdirs()
            df.writeText(buildString {
                appendLine("allCells.size=${allCells.size}")
                for (i in allCells.indices) {
                    appendLine("[$i] \"${allCells[i].take(90)}\"")
                }
            })
        } catch (_: Exception) {}

        if (allCells.size < 4) {
            for (c in allCells) { if (c.isNotBlank()) emitFormattedParagraph(c, blocks) }
            return
        }

        // ── Phase 1: Detect & locate the main inventory table ─────
        // Real document layout (flat cell stream):
        //   ... paragraphs ...
        //   header: Bil. | Item | Ya | Tidak       (4 cells)
        //   rows:   1 | item1 | 2 | item2 | 3 | item3 | ...   (2 cells per row;
        //           the "Ya"/"Tidak" text is empty form objects and is absent)
        //   then post-table sections (BORANG JAWAPAN, Helaian Profil, ...)
        //
        // We locate the header by its literal column labels, then reconstruct
        // each row from the sequential number/item pairs.

        val postSectionMarkers = listOf(
            "BORANG JAWAPAN", "HELAIAN PROFIL", "BORANG TRANSFORMASI",
            "PENTAFSIRAN", "PENGIRAAN SKOR", "PENTADBIRAN INVENTORI",
        )

        var foundHeaderIdx = -1
        var mainTableCols = 4

        // Find a header row whose cells look like column labels (Bil/No, Item, Ya, Tidak).
        for (i in 0 until minOf(allCells.size - 3, 20)) {
            val c = allCells[i].trim()
            val isBil = c.equals("Bil.", ignoreCase = true) || c == "Bil" || c == "No" ||
                c.startsWith("Bil", ignoreCase = true)
            if (!isBil) continue
            val next = allCells.drop(i + 1).take(3).map { it.trim().lowercase() }
            val hasItem = next.any { it == "item" || it.contains("item") }
            val hasYaTidak = next.any { it == "ya" } && next.any { it == "tidak" }
            if (hasItem || hasYaTidak) { foundHeaderIdx = i; break }
        }

        if (foundHeaderIdx < 0) {
            // No recognizable header — try the numeric-run fallback
            var startIdx = -1
            var consecutiveNums = 0
            var lastNum = 0
            for (i in allCells.indices) {
                val num = allCells[i].trim().toIntOrNull()
                if (num != null && num == lastNum + 1) {
                    if (consecutiveNums == 0) startIdx = i
                    consecutiveNums++
                    lastNum = num
                } else {
                    if (consecutiveNums >= 50) break
                    consecutiveNums = 0; lastNum = 0
                }
            }
            if (consecutiveNums >= 50) {
                // Inventory rows follow the run of numbers, one row per number.
                foundHeaderIdx = startIdx - 4 // guess header just before numbers
            } else {
                parseAllAsSections(allCells, blocks)
                return
            }
        }

        // ── Phase 2: Extract the main table ───────────────────────
        var idx = foundHeaderIdx
        val headerCells = allCells.subList(foundHeaderIdx, foundHeaderIdx + mainTableCols)
            .map { it.trim() }

        val mainTableRows = mutableListOf<WordTableRow>()
        mainTableRows += WordTableRow(
            isHeader = true,
            cells = headerCells.map { c -> WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c))))) }
        )

        // Data rows: sequential number/item pairs. Each visual row maps to 4
        // columns (Bil, Item, Ya, Tidak); Ya/Tidak are empty if missing.
        idx = foundHeaderIdx + mainTableCols
        while (idx < allCells.size) {
            val noCell = allCells[idx].trim()
            val num = noCell.toIntOrNull()
            if (num == null || num < 1 || num > 200) {
                val upper = noCell.uppercase()
                if (postSectionMarkers.any { upper.startsWith(it) }) break
                if (noCell.isBlank()) { idx++; continue }
                break
            }
            val itemText = if (idx + 1 < allCells.size) allCells[idx + 1].trim() else ""
            mainTableRows += WordTableRow(
                cells = listOf(
                    WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(noCell))))),
                    WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(itemText))))),
                    WordTableCell(listOf(WordBlock.Paragraph(emptyList()))),
                    WordTableCell(listOf(WordBlock.Paragraph(emptyList()))),
                )
            )
            idx += 2
        }

        if (mainTableRows.size >= 3) {
            // Narrow number column, wide item column, two checkbox columns (10/64/13/13).
            val widths = if (mainTableCols == 4) listOf(0.10f, 0.64f, 0.13f, 0.13f) else emptyList()
            blocks += WordBlock.Table(rows = mainTableRows, columnWidths = widths)
        }

        // ── Phase 3: Process everything before the main table ──────
        for (i in 0 until foundHeaderIdx) {
            val cell = allCells[i].trim()
            if (cell.isNotBlank()) emitFormattedParagraph(cell, blocks)
        }

        // ── Phase 4: Process everything after the main table ───────
        // Resume from the last index actually consumed by the table (loop may break early).
        val postCells = allCells.subList(idx, allCells.size).toMutableList()
        processPostTableContent(postCells, blocks)
    }

    /**
     * Process all cells after the main table.
     * Detects: answer forms, scoring forms, form headers, trait descriptions.
     */
    private fun processPostTableContent(cells: MutableList<String>, blocks: MutableList<WordBlock>) {
        if (cells.isEmpty()) return

        // Combine all remaining cells into one text stream with markers
        // We'll use the structure of the content to detect sections
        val combinedText = cells.joinToString(" ")

        // ── Detect section boundaries ─────────────────────────────
        // Key section markers:
        // "BORANG JAWAPAN" — answer form
        // "Helaian Profil" — scoring form
        // "Borang Transformasi" — transform scoring form
        // "Pentafsiran IPS" — interpretation section
        // Trait names as headers: Agresif, Analitik, etc.

        val sectionMarkers = listOf(
            "BORANG JAWAPAN",
            "Helaian Profil",
            "Borang Transformasi",
            "Pentafsiran IPS",
            "Pengiraan Skor",
            "Pentadbiran Inventori",
        )

        // Split cells into sections based on markers
        data class CellSection(val cells: List<String>, val marker: String = "")

        val sections = mutableListOf<CellSection>()
        var currentSectionCells = mutableListOf<String>()
        var currentMarker = ""

        for (cell in cells) {
            val trimmed = cell.trim()
            val matchedMarker = sectionMarkers.find { trimmed.contains(it, ignoreCase = true) }
            if (matchedMarker != null && currentSectionCells.isNotEmpty()) {
                sections.add(CellSection(currentSectionCells, currentMarker))
                currentSectionCells = mutableListOf()
                currentMarker = matchedMarker
            }
            if (matchedMarker != null && currentSectionCells.isEmpty()) {
                currentMarker = matchedMarker
            }
            currentSectionCells.add(trimmed)
        }
        if (currentSectionCells.isNotEmpty()) {
            sections.add(CellSection(currentSectionCells, currentMarker))
        }

        // ── Process each section ──────────────────────────────────
        for (section in sections) {
            when {
                section.marker.contains("BORANG JAWAPAN", ignoreCase = true) -> {
                    processAnswerForm(section.cells, blocks)
                }
                section.marker.contains("Helaian Profil", ignoreCase = true) -> {
                    processScoringForm(section.cells, blocks)
                }
                section.marker.contains("Borang Transformasi", ignoreCase = true) -> {
                    processScoringForm(section.cells, blocks)
                }
                section.marker.contains("Pentafsiran IPS", ignoreCase = true) -> {
                    processInterpretationSection(section.cells, blocks)
                }
                section.marker.contains("Pengiraan Skor", ignoreCase = true) -> {
                    processScoringInstructions(section.cells, blocks)
                }
                section.marker.contains("Pentadbiran Inventori", ignoreCase = true) -> {
                    processScoringInstructions(section.cells, blocks)
                }
                else -> {
                    processGenericSection(section.cells, blocks)
                }
            }
        }
    }

    /**
     * Process the answer form (BORANG JAWAPAN).
     * Pattern: title + form fields + checkbox grid (N Y T repeated).
     * The grid has 10 columns per row: [No. Y T] repeated 10 times + trait code.
     */
    private fun processAnswerForm(cells: List<String>, blocks: MutableList<WordBlock>) {
        // Find where the checkbox grid starts (first cell that's a number)
        val formFields = mutableListOf<String>()
        val checkboxCells = mutableListOf<String>()
        var gridStarted = false

        for (cell in cells) {
            val trimmed = cell.trim()
            if (!gridStarted) {
                // Check if this is a checkbox cell (number followed by Y/T pattern)
                val isGridCell = (trimmed.toIntOrNull() != null && trimmed.toInt() in 1..160) ||
                    trimmed.matches(Regex("^T\\s*\\d+$")) ||
                    trimmed == "Y" || trimmed == "T" ||
                    trimmed.matches(Regex("^\\d+\\s+Y$")) ||
                    trimmed.matches(Regex("^T\\s+\\d+\\s+Y$"))
                if (isGridCell) {
                    gridStarted = true
                    checkboxCells.add(trimmed)
                } else {
                    formFields.add(trimmed)
                }
            } else {
                checkboxCells.add(trimmed)
            }
        }

        // Emit form fields as paragraphs
        for (field in formFields) {
            if (field.isNotBlank()) emitFormattedParagraph(field, blocks)
        }

        // Build answer form table from checkbox cells
        if (checkboxCells.size >= 10) {
            // The form has 10 columns: each column is a pair of (item#, Y, T)
            // Row structure: N Y T N Y T N Y T ... TRAIT_CODE
            // Where N is the item number (1-160) and TRAIT_CODE is AGR, ANA, etc.

            // First, combine the cells into one text to parse the structure
            val gridText = checkboxCells.joinToString(" ")

            // Extract all number-Y-T triplets
            val tripletPattern = Regex("(\\d+)\\s+Y\\s+T")
            val triplets = tripletPattern.findAll(gridText).toList()

            // Extract trait codes (3-letter codes like AGR, ANA, AUT, BSD, EKS, ITL, INT, KPL, THN, KRD, MGL, TLG, SOK, STR, PCP)
            val traitCodes = checkboxCells.filter { it.matches(Regex("^[A-Z]{2,4}$")) }

            if (triplets.size >= 10) {
                // Build table: 10 columns, each with item number
                val itemsPerRow = 10
                val tableRows = mutableListOf<WordTableRow>()

                // Header row
                val headerRow = WordTableRow(
                    isHeader = true,
                    cells = (1..itemsPerRow).map { n ->
                        WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun("No.$n")))))
                    }
                )
                tableRows.add(headerRow)

                // Group triplets into rows of 10
                for (i in triplets.indices step itemsPerRow) {
                    val rowCells = mutableListOf<String>()
                    for (j in 0 until itemsPerRow) {
                        if (i + j < triplets.size) {
                            val itemNum = triplets[i + j].groupValues[1]
                            rowCells.add(itemNum)
                        } else {
                            rowCells.add("")
                        }
                    }
                    // Add trait code if available
                    // val traitIdx = i / itemsPerRow
                    // if (traitIdx < traitCodes.size) rowCells.add(traitCodes[traitIdx])

                    tableRows += WordTableRow(
                        cells = rowCells.map { c ->
                            WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c)))))
                        }
                    )
                }

                if (tableRows.size >= 2) {
                    blocks += WordBlock.Table(rows = tableRows)
                }
            } else {
                // Not enough triplets — emit as paragraphs
                for (cell in checkboxCells) {
                    if (cell.isNotBlank()) emitFormattedParagraph(cell, blocks)
                }
            }
        } else {
            for (cell in checkboxCells) {
                if (cell.isNotBlank()) emitFormattedParagraph(cell, blocks)
            }
        }
    }

    /**
     * Process the scoring form (Helaian Profil I / Borang Transformasi Skor).
     * Pattern: form header + form fields + trait scoring grid (trait name, score, percentage).
     */
    private fun processScoringForm(cells: List<String>, blocks: MutableList<WordBlock>) {
        // Find where the scoring grid starts
        val formFields = mutableListOf<String>()
        val gridCells = mutableListOf<String>()
        var gridStarted = false

        // Known trait names
        val traitNames = setOf(
            "Agresif", "Analitik", "Autonomi", "Bersandar", "Ekstrovert",
            "Intelektual", "Introvert", "Kepelbagaian", "Ketahanan",
            "Kritik Diri", "Mengawal", "Menolong", "Sokongan", "Struktur",
            "Pencapaian", "Kejujuran", "Analitis"
        )

        for (cell in cells) {
            val trimmed = cell.trim()
            if (!gridStarted) {
                // Grid starts at TRET/KADAR/PERATUS header or first trait name
                if (trimmed.equals("TRET", ignoreCase = true) || traitNames.any { trimmed.equals(it, ignoreCase = true) }) {
                    gridStarted = true
                    gridCells.add(trimmed)
                } else {
                    formFields.add(trimmed)
                }
            } else {
                gridCells.add(trimmed)
            }
        }

        // Emit form fields as paragraphs
        for (field in formFields) {
            if (field.isNotBlank()) emitFormattedParagraph(field, blocks)
        }

        // Build scoring table from grid cells
        if (gridCells.size >= 6) {
            // Structure: TRET KADAR PERATUS [scale] PERSONALITI SKOR (%) then alternating: trait score percentage
            val tableRows = mutableListOf<WordTableRow>()

            // Header row
            tableRows += WordTableRow(
                isHeader = true,
                cells = listOf(
                    WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun("Tret"))))),
                    WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun("Skor"))))),
                    WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun("Peratus (%)"))))),
                )
            )

            // Find trait names and pair with their score/percentage fields
            var i = 0
            while (i < gridCells.size) {
                val cell = gridCells[i].trim()
                val isTrait = traitNames.any { cell.equals(it, ignoreCase = true) }

                if (isTrait) {
                    val traitName = cell
                    val score = if (i + 1 < gridCells.size) gridCells[i + 1].trim() else ""
                    val pct = if (i + 2 < gridCells.size) gridCells[i + 2].trim() else ""
                    tableRows += WordTableRow(
                        cells = listOf(
                            WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(traitName))))),
                            WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(score))))),
                            WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(pct))))),
                        )
                    )
                    i += 3
                } else if (cell.contains("----")) {
                    // Scale line, skip
                    i++
                } else if (cell.equals("TRET", ignoreCase = true) || cell.equals("KADAR", ignoreCase = true) ||
                    cell.equals("PERATUS", ignoreCase = true) || cell.equals("PERSONALITI", ignoreCase = true) ||
                    cell.equals("SKOR", ignoreCase = true) || cell == "(%)" || cell.matches(Regex("^\\d+$"))) {
                    // Header/metadata cells, skip
                    i++
                } else {
                    // Unknown cell, emit as paragraph
                    emitFormattedParagraph(cell, blocks)
                    i++
                }
            }

            if (tableRows.size >= 3) {
                blocks += WordBlock.Table(rows = tableRows)
            }
        } else {
            for (cell in gridCells) {
                if (cell.isNotBlank()) emitFormattedParagraph(cell, blocks)
            }
        }
    }

    /**
     * Process the interpretation section (Pentafsiran IPS).
     * Each trait has a heading + description paragraph.
     */
    private fun processInterpretationSection(cells: List<String>, blocks: MutableList<WordBlock>) {
        val traitNames = listOf(
            "Agresif", "Analitikal", "Autonomi", "Bersandar", "Ekstrovert",
            "Intelektual", "Introvert", "Kepelbagaian", "Ketahanan",
            "Kritik-Diri", "Mengawal", "Menolong", "Sokongan", "Struktur",
            "Pencapaian"
        )

        // Combine cells into text, split at trait names
        val combinedText = cells.joinToString(" ")

        // Split at trait name boundaries
        val pattern = Regex("(?=\\b(?:Agresif|Analitikal|Autonomi|Bersandar|Ekstrovert|Intelektual|Introvert|Kepelbagaian|Ketahanan|Kritik-?Diri|Mengawal|Menolong|Sokongan|Struktur|Pencapaian)\\b)")
        val parts = combinedText.split(pattern).filter { it.isNotBlank() }

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotBlank()) {
                // Check if this starts with a trait name (make it a heading)
                val isHeading = traitNames.any { trimmed.startsWith(it, ignoreCase = true) }
                if (isHeading) {
                    // Split into heading + body
                    val firstSpace = trimmed.indexOf(' ')
                    if (firstSpace > 0 && firstSpace < 30) {
                        val heading = trimmed.substring(0, firstSpace)
                        val body = trimmed.substring(firstSpace + 1).trim()
                        blocks += WordBlock.Paragraph(listOf(WordRun(heading, bold = true, fontSize = 14f)))
                        if (body.isNotBlank()) {
                            emitFormattedParagraph(body, blocks)
                        }
                    } else {
                        emitFormattedParagraph(trimmed, blocks)
                    }
                } else {
                    emitFormattedParagraph(trimmed, blocks)
                }
            }
        }
    }

    /**
     * Process scoring instructions (numbered steps).
     */
    private fun processScoringInstructions(cells: List<String>, blocks: MutableList<WordBlock>) {
        val combinedText = cells.joinToString(" ")

        // Split at numbered items
        val numberedPattern = Regex("(?=\\b\\d+\\.\\s)")
        val parts = combinedText.split(numberedPattern).filter { it.isNotBlank() }

        if (parts.size > 2) {
            for (part in parts) {
                emitFormattedParagraph(part.trim(), blocks)
            }
        } else {
            emitFormattedParagraph(combinedText, blocks)
        }
    }

    /**
     * Process a generic section — try to detect tables or emit as paragraphs.
     */
    private fun processGenericSection(cells: List<String>, blocks: MutableList<WordBlock>) {
        // Try to detect tabular data
        if (cells.size >= 6) {
            // Check if cells form a table pattern (equal column counts)
            val colCounts = cells.map { cell ->
                cell.split(Regex("\\t|\\s{2,}")).filter { it.isNotBlank() }.size
            }
            val mode = colCounts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 1

            if (mode >= 2) {
                val tableRows = mutableListOf<WordTableRow>()
                for (cell in cells) {
                    val cols = cell.split(Regex("\\t|\\s{2,}")).filter { it.isNotBlank() }
                    if (cols.size >= 2) {
                        tableRows += WordTableRow(
                            cells = cols.map { c -> WordTableCell(listOf(WordBlock.Paragraph(listOf(WordRun(c))))) }
                        )
                    }
                }
                if (tableRows.size >= 2) {
                    blocks += WordBlock.Table(rows = tableRows)
                    return
                }
            }
        }

        // Fall back to paragraphs
        for (cell in cells) {
            if (cell.isNotBlank()) emitFormattedParagraph(cell, blocks)
        }
    }

    private fun parseAllAsSections(allCells: List<String>, blocks: MutableList<WordBlock>) {
        // Fallback: treat everything as sections
        processPostTableContent(allCells.toMutableList(), blocks)
    }

    private fun emitFormattedParagraph(text: String, blocks: MutableList<WordBlock>) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        val sentences = splitIntoLogicalParagraphs(trimmed)
        for (sentence in sentences) {
            if (sentence.isBlank()) continue
            val t = sentence.trim()
            val isTitle = isTitleLine(t)
            val allCaps = t.uppercase() == t
            val bold = isTitle || (allCaps && t.length in 3..60)
            val fontSize = when {
                isTitle -> 18f
                allCaps && t.length in 3..60 -> 14f
                else -> 13f
            }
            val alignment = if (isTitle) DocAlignment.CENTER else DocAlignment.LEFT
            blocks += WordBlock.Paragraph(
                listOf(WordRun(t, bold = bold, fontSize = fontSize)),
                alignment = alignment,
            )
        }
    }

    /** Detects title-page / section-header lines that should be centered and emphasized. */
    private fun isTitleLine(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val allCapsShort = t.length in 3..45 &&
            t == t.uppercase() &&
            t.any { it.isLetter() } &&
            !t.endsWith(":")
        if (allCapsShort) return true
        if (t.equals("Oleh:", ignoreCase = true)) return true
        if (Regex("^(DR\\.|DR |Prof\\.|PROF |Dr\\.)", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        return t.contains("Personality Inventory", ignoreCase = true)
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
