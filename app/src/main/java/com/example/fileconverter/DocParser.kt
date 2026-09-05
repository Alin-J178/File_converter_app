package com.example.fileconverter

import android.content.Context
import android.net.Uri
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.CharacterRun
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.hwpf.usermodel.Range
import org.apache.poi.hwpf.usermodel.Table
import org.apache.poi.hwpf.usermodel.TableCell
import org.apache.poi.hwpf.usermodel.TableIterator
import org.apache.poi.hwpf.usermodel.TableRow
import java.io.ByteArrayInputStream

/**
 * Parses legacy .doc (Word 97-2003 / OLE2) files into [WordDocument].
 *
 * Uses Apache POI's HWPF reader (org.apache.poi:poi-scratchpad) instead of
 * character-marker guessing. HWPF understands the real binary structure, so we get:
 *  - real row / cell boundaries from [Table]/[TableRow]/[TableCell];
 *  - real bold / italic / underline / font size per [CharacterRun];
 *  - real paragraph style names (headings) from the document style sheet;
 *  - real alignment, indents, spacing and list membership from [Paragraph].
 *
 * Everything is mapped into the existing [WordDocument]/[WordBlock] model so the
 * renderer does not need to guess formatting from text. If HWPF cannot open the
 * file (e.g. it is actually RTF/HTML saved as .doc), a plain-text fallback emits
 * paragraphs only — no table heuristics.
 */
object DocParser {

    fun parse(context: Context, uri: Uri): WordDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return WordDocument(emptyList())
        return parseBytes(bytes)
    }

    fun parseBytes(bytes: ByteArray): WordDocument {
        return try {
            parseWithHwpf(bytes)
        } catch (e: Exception) {
            android.util.Log.w("DocParser", "HWPF parse failed, using plain-text fallback", e)
            parsePlainFallback(bytes)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HWPF parsing
    // ═══════════════════════════════════════════════════════════════

    private fun parseWithHwpf(bytes: ByteArray): WordDocument {
        val doc = HWPFDocument(ByteArrayInputStream(bytes))
        try {
            val range = doc.range
            val styleSheet = doc.styleSheet

            // Collect all tables up-front (they are ordered by start offset).
            val tables = mutableListOf<Table>()
            val tableIter = TableIterator(range)
            while (tableIter.hasNext()) tables += tableIter.next()

            val blocks = mutableListOf<WordBlock>()
            var ti = 0
            var i = 0
            val n = range.numParagraphs()
            while (i < n) {
                val p = range.getParagraph(i)
                val inTable = p.isInTable && ti < tables.size &&
                    p.startOffset >= tables[ti].startOffset && p.startOffset < tables[ti].endOffset
                if (inTable) {
                    // Emit the table exactly once, when we reach its first paragraph.
                    if (p.startOffset == tables[ti].startOffset) {
                        blocks += tableToBlock(tables[ti], styleSheet)
                        ti++
                    }
                    i++
                } else {
                    blocks += paragraphToBlock(p, styleSheet)
                    i++
                }
            }

            if (blocks.isEmpty()) return WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Empty document")))))

            writeDebug(blocks)
            return WordDocument(blocks)
        } finally {
            try { doc.close() } catch (_: Exception) {}
        }
    }

    // ── Paragraph → WordBlock ─────────────────────────────────────

    private fun paragraphToBlock(p: Paragraph, styleSheet: org.apache.poi.hwpf.model.StyleSheet?): WordBlock {
        val runs = paragraphRuns(p)
        if (runs.isEmpty()) return WordBlock.Paragraph(emptyList())

        val styleName = try {
            styleSheet?.getStyleDescription(p.styleIndex.toInt())?.name
        } catch (_: Exception) { null } ?: ""

        val headingLevel = headingLevelFromStyle(styleName)
        if (headingLevel != null && runs.isNotEmpty()) {
            return WordBlock.Heading(runs, headingLevel)
        }

        val alignment = when (p.justification) {
            1 -> DocAlignment.CENTER
            2 -> DocAlignment.RIGHT
            3 -> DocAlignment.JUSTIFY
            else -> DocAlignment.LEFT
        }
        val indentLeft = twipsToPts(p.indentFromLeft)
        val indentRight = twipsToPts(p.indentFromRight)
        var first = twipsToPts(p.firstLineIndent)
        var hanging = 0f
        if (first < 0) { hanging = -first; first = 0f }
        val indent = DocIndent(indentLeft, indentRight, first, hanging)
        val spacing = DocSpacing(
            before = twipsToPts(p.spacingBefore),
            after = twipsToPts(p.spacingAfter),
        )
        return WordBlock.Paragraph(
            runs = runs,
            alignment = alignment,
            style = styleName,
            spacing = spacing,
            indent = indent,
        )
    }

    private fun paragraphRuns(p: Paragraph): List<WordRun> {
        val runs = mutableListOf<WordRun>()
        val n = p.numCharacterRuns()
        for (i in 0 until n) {
            val r = p.getCharacterRun(i)
            runs += runToWordRun(r)
        }
        return runs.filter { it.text.isNotEmpty() }
    }

    private fun runToWordRun(r: CharacterRun): WordRun {
        val raw = try { r.text() } catch (_: Exception) { "" }
        // Strip control / cell / row / field characters HWPF exposes.
        val text = raw.map { c -> if (c.code >= 0x20 && c != '\u007f') c else ' ' }
            .joinToString("").replace(Regex("\\s+"), " ")
        val halfPts = try { r.fontSize } catch (_: Exception) { 0 }
        return WordRun(
            text = text.trim(),
            bold = try { r.isBold } catch (_: Exception) { false },
            italic = try { r.isItalic } catch (_: Exception) { false },
            underline = try { r.underlineCode != 0 } catch (_: Exception) { false },
            strikethrough = try { r.isStrikeThrough } catch (_: Exception) { false },
            fontSize = if (halfPts > 0) halfPts / 2f else 12f,
            fontFamily = try { r.fontName } catch (_: Exception) { "" },
        )
    }

    // ── Table → WordBlock.Table ───────────────────────────────────

    private fun tableToBlock(table: Table, styleSheet: org.apache.poi.hwpf.model.StyleSheet?): WordBlock.Table {
        val rows = mutableListOf<WordTableRow>()
        val numRows = table.numRows()
        var maxCells = 0
        for (r in 0 until numRows) {
            try { maxCells = maxOf(maxCells, table.getRow(r).numCells()) } catch (_: Exception) {}
        }
        if (maxCells == 0) maxCells = 1

        for (r in 0 until numRows) {
            val row = try { table.getRow(r) } catch (_: Exception) { continue }
            val isHeader = try { row.isTableHeader } catch (_: Exception) { r == 0 }
            val cells = mutableListOf<WordTableCell>()
            var c = 0
            val numCells = try { row.numCells() } catch (_: Exception) { 0 }
            while (c < numCells) {
                val cell = try { row.getCell(c) } catch (_: Exception) { break }
                // Horizontal merge: the first cell in a merged run carries the content.
                val firstMerged = try { cell.isFirstMerged } catch (_: Exception) { false }
                val merged = try { cell.isMerged } catch (_: Exception) { false }
                val vMerged = try { cell.isVerticallyMerged } catch (_: Exception) { false }
                val firstVMerged = try { cell.isFirstVerticallyMerged } catch (_: Exception) { false }

                if (merged && !firstMerged) {
                    // Continuation of a horizontal merge already counted on the first cell.
                    c++
                    continue
                }
                var gridSpan = 1
                if (firstMerged) {
                    var k = c + 1
                    while (k < numCells) {
                        val nxt = try { row.getCell(k) } catch (_: Exception) { null }
                        val nxtMerged = try { nxt?.isMerged == true && nxt?.isFirstMerged == false } catch (_: Exception) { false }
                        if (!nxtMerged) break
                        k++
                    }
                    gridSpan = (k - c).coerceAtLeast(1)
                }
                val isVCont = vMerged && !firstVMerged
                if (isVCont) {
                    // Vertical-merge continuation: no new content, just occupies the column.
                    cells += WordTableCell(listOf(WordBlock.Paragraph(emptyList())), gridSpan, vMergeCont = true)
                } else {
                    val cellBlocks = mutableListOf<WordBlock>()
                    val np = try { cell.numParagraphs() } catch (_: Exception) { 0 }
                    for (pi in 0 until np) {
                        val cp = try { cell.getParagraph(pi) } catch (_: Exception) { continue }
                        val runs = paragraphRuns(cp)
                        if (runs.isNotEmpty()) {
                            val styleName = try { styleSheet?.getStyleDescription(cp.styleIndex.toInt())?.name } catch (_: Exception) { null } ?: ""
                            val lvl = headingLevelFromStyle(styleName)
                            if (lvl != null) cellBlocks += WordBlock.Heading(runs, lvl)
                            else cellBlocks += WordBlock.Paragraph(runs)
                        }
                    }
                    if (cellBlocks.isEmpty()) cellBlocks += WordBlock.Paragraph(emptyList())
                    cells += WordTableCell(cellBlocks, gridSpan)
                }
                c++
            }
            if (cells.isNotEmpty()) rows += WordTableRow(cells, isHeader = isHeader)
        }
        return WordBlock.Table(rows = rows)
    }

    // ── Style-name heading detection (real metadata, not text guessing) ──

    /** Maps a HWPF style-sheet name (e.g. "heading 1" / "Heading 2") to a level. */
    private fun headingLevelFromStyle(name: String): Int? {
        if (name.isBlank()) return null
        val m = Regex("""heading\s*(\d+)""", RegexOption.IGNORE_CASE).find(name) ?: return null
        return m.groupValues[1].toIntOrNull()?.coerceIn(1, 6)
    }

    private fun twipsToPts(twips: Int): Float = twips / 20f

    // ═══════════════════════════════════════════════════════════════
    //  Plain fallback (no table heuristics)
    // ═══════════════════════════════════════════════════════════════

    private fun parsePlainFallback(bytes: ByteArray): WordDocument {
        val text = try {
            val extracted = DocTextExtractor.extract(bytes)
            extracted.text
        } catch (_: Exception) { "" }
        if (text.isBlank()) return WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Could not read this DOC file")))))
        val blocks = mutableListOf<WordBlock>()
        for (raw in text.split('\u000C', '\r', '\n')) {
            val line = raw.replace('\u0007', ' ').replace('\u000b', ' ').trim()
            if (line.isNotEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun(line)))
        }
        if (blocks.isEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun("Could not read this DOC file")))
        writeDebug(blocks)
        return WordDocument(blocks)
    }

    private fun writeDebug(blocks: List<WordBlock>) {
        try {
            val f = java.io.File("/sdcard/Documents/doc_debug.txt")
            f.parentFile?.mkdirs()
            f.writeText(buildString {
                appendLine("Blocks: ${blocks.size}")
                appendLine("Tables: ${blocks.count { it is WordBlock.Table }}")
                appendLine("Paragraphs: ${blocks.count { it is WordBlock.Paragraph }}")
                appendLine("Headings: ${blocks.count { it is WordBlock.Heading }}")
                appendLine("ListItems: ${blocks.count { it is WordBlock.ListItem }}")
                blocks.forEachIndexed { idx, b ->
                    when (b) {
                        is WordBlock.Table -> {
                            appendLine("[$idx] TABLE ${b.rows.size}r")
                            b.rows.take(3).forEachIndexed { ri, row ->
                                appendLine("  row $ri: ${row.cells.size} cells: ${row.cells.joinToString(" | ") { c -> c.blocks.joinToString("") { bl -> (bl as? WordBlock.Paragraph)?.runs?.joinToString("") { it.text } ?: "" }.take(40) }}")
                            }
                        }
                        is WordBlock.Paragraph -> {
                            val txt = b.runs.joinToString("") { it.text }
                            appendLine("[$idx] PARA style=${b.style} align=${b.alignment} (${txt.length}c) \"${txt.take(100)}\"")
                        }
                        is WordBlock.Heading -> {
                            val txt = b.runs.joinToString("") { it.text }
                            appendLine("[$idx] HEADING lvl=${b.level} \"${txt.take(100)}\"")
                        }
                        is WordBlock.ListItem -> appendLine("[$idx] LIST lvl=${b.level} fmt=${b.numFmt} numId=${b.numId}")
                        else -> appendLine("[$idx] ${b::class.simpleName}")
                    }
                }
            })
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    //  Legacy PPT preview (kept — independent of .doc parsing)
    // ═══════════════════════════════════════════════════════════════

    fun parseLegacyPpt(context: Context, uri: Uri): WordDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return WordDocument(emptyList())
        val text = extractReadableText(bytes)
        if (text.isBlank()) return WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Could not extract text from PPT file")))))
        return WordDocument(text.chunked(2000).map { WordBlock.Paragraph(listOf(WordRun(it))) })
    }

    private fun extractReadableText(bytes: ByteArray): String {
        val result = StringBuilder()
        val utf16Runs = mutableListOf<String>()
        var currentRun = StringBuilder()
        var i = 0
        while (i < bytes.size - 1) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            if (lo in 0x20..0x7E && hi == 0) currentRun.append(lo.toChar())
            else {
                if (currentRun.length >= 4) utf16Runs.add(currentRun.toString())
                currentRun = StringBuilder()
            }
            i += 2
        }
        if (currentRun.length >= 4) utf16Runs.add(currentRun.toString())
        val realText = utf16Runs.distinct().sortedByDescending { it.length }
            .filter { run -> run.count { it.isLetter() } > run.length * 0.5 }
        for (run in realText) { result.appendLine(run); result.appendLine() }
        if (result.length < 50) {
            result.clear()
            currentRun = StringBuilder()
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E || c == 10 || c == 13 || c == 9) currentRun.append(c.toChar())
                else {
                    if (currentRun.length >= 8) result.appendLine(currentRun.toString())
                    currentRun = StringBuilder()
                }
            }
            if (currentRun.length >= 8) result.appendLine(currentRun.toString())
        }
        return result.toString().trim()
    }
}
