package com.example.fileconverter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a [WordDocument] into page-sized Bitmaps.
 *
 * Layout decisions come from the parsed model only:
 *  - Paragraph/Heading/ListItem blocks are drawn from their runs (bold, italic,
 *    underline, font size, color) — no text-pattern guessing.
 *  - Paragraph.indent (first line / hanging / left / right) is honoured.
 *  - ListItem renders real per-level numbering ("1.", "a.", "i.") resolved from
 *    numbering.xml, resetting per numbering instance.
 *  - Tables use parser-provided column widths / gridSpan and skip vMerge-continue cells.
 */
object DocPageRenderer {

    private const val PAGE_WIDTH = 1080
    private const val PAGE_HEIGHT = 1527
    private const val MARGIN_LEFT = 72
    private const val MARGIN_RIGHT = 72
    private const val MARGIN_TOP = 72
    private const val MARGIN_BOTTOM = 72
    private const val USABLE_WIDTH = (PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT).toFloat()
    private const val LINE_SPACING = 1.4f
    private const val CELL_PADDING = 8f

    /** One styled word used by the wrapped layout. */
    private class StyledWord(
        val text: String,
        val paint: Paint,
        val sizePt: Float,
    )

    private class WrappedLine(
        val words: List<StyledWord>,
        val width: Float,
        val height: Float,
        val xOffset: Float,
    )

    fun render(doc: WordDocument): List<Bitmap> {
        if (doc.blocks.isEmpty()) {
            val bmp = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            val p = Paint().apply { color = android.graphics.Color.GRAY; textSize = 40f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
            canvas.drawText("Empty document", PAGE_WIDTH / 2f, PAGE_HEIGHT / 2f, p)
            return listOf(bmp)
        }

        val pages = mutableListOf<Bitmap>()
        var page = createBlankPage()
        var canvas = Canvas(page)
        var currentY = MARGIN_TOP.toFloat()

        // Numbering counters, keyed by (numId, level). Reset when the numbering
        // instance changes or a non-list block interrupts.
        val counters = mutableMapOf<Pair<Int, Int>, Int>()
        var activeNumId = -2
        var inList = false

        fun newPage(): Canvas {
            pages.add(page)
            page = createBlankPage()
            currentY = MARGIN_TOP.toFloat()
            return Canvas(page)
        }

        fun ensureFits(lineHeight: Float) {
            if (currentY + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM) canvas = newPage()
        }

        for (block in doc.blocks) {
            when (block) {
                is WordBlock.Table -> {
                    val result = drawTable(canvas, block, currentY, ::newPage)
                    canvas = result.first
                    currentY = result.second
                    inList = false
                }
                is WordBlock.Heading -> {
                    val fontSize = headingFontSize(block.level)
                    val runs = block.runs.ifEmpty { listOf(WordRun("")) }
                    val words = runsToWords(runs, forceBold = true, forceSize = fontSize)
                    val lines = wrapWords(words, USABLE_WIDTH, 0f, 0f)
                    for (line in lines) {
                        ensureFits(line.height)
                        currentY = drawLine(canvas, line, block.alignment, MARGIN_LEFT.toFloat(), currentY)
                    }
                    currentY += 8f
                    inList = false
                }
                is WordBlock.Paragraph -> {
                    if (block.pageBreakBefore) canvas = newPage()
                    val runs = block.runs
                    val words = runsToWords(runs, forceBold = false, forceSize = 0f)
                    if (words.isEmpty()) {
                        // Empty paragraph still takes a line of the default size.
                        currentY += block.spacing.before + block.spacing.after + 13f * LINE_SPACING * 0.5f
                        inList = false
                        continue
                    }
                    // OOXML indents: every line starts at (left + xOffset), and the first
                    // line is shifted by (firstLine − hanging) — hanging pulls it back left.
                    val leftIndent = block.indent.left.coerceAtLeast(0f)
                    val firstLineShift = (block.indent.firstLine - block.indent.hanging).coerceAtLeast(-leftIndent)
                    val lines = wrapWords(words, USABLE_WIDTH, leftIndent, firstLineShift)
                    if (block.spacing.before > 0) currentY += block.spacing.before
                    for (line in lines) {
                        ensureFits(line.height)
                        currentY = drawLine(canvas, line, block.alignment, MARGIN_LEFT + leftIndent, currentY)
                    }
                    currentY += block.spacing.after + 4f
                    inList = false
                }
                is WordBlock.ListItem -> {
                    val runs = block.runs
                    val isNumbered = !block.bulleted && block.numFmt.isNotEmpty() && block.numId >= 0
                    if (isNumbered) {
                        if (!inList || block.numId != activeNumId) {
                            counters.clear()
                            activeNumId = block.numId
                        }
                        val key = block.numId to block.level
                        val next = (counters[key] ?: (block.start - 1)) + 1
                        counters[key] = next
                        // A shallower item restarts its deeper sub-lists.
                        counters.keys.filter { it.first == block.numId && it.second > block.level }.forEach { counters.remove(it) }
                        val prefix = numberPrefix(next, block.numFmt) + ". "
                        val words = listOf(StyledWord(prefix, runPaint(WordRun(prefix, bold = true, fontSize = 13f), false, 13f), 13f)) +
                            runsToWords(runs, forceBold = false, forceSize = 0f)
                        val indentShift = (block.level * 22f)
                        val lines = wrapWords(words, USABLE_WIDTH, indentShift, 0f)
                        for (line in lines) {
                            ensureFits(line.height)
                            currentY = drawLine(canvas, line, DocAlignment.LEFT, MARGIN_LEFT + indentShift, currentY)
                        }
                        currentY += 4f
                        inList = true
                    } else {
                        val bullet = "•  "
                        val words = listOf(StyledWord(bullet, runPaint(WordRun(bullet, fontSize = 13f), false, 13f), 13f)) +
                            runsToWords(runs, forceBold = false, forceSize = 0f)
                        val indentShift = (block.level * 22f)
                        val lines = wrapWords(words, USABLE_WIDTH, indentShift, 0f)
                        for (line in lines) {
                            ensureFits(line.height)
                            currentY = drawLine(canvas, line, DocAlignment.LEFT, MARGIN_LEFT + indentShift, currentY)
                        }
                        currentY += 4f
                        inList = true
                    }
                }
                is WordBlock.PageBreak -> { canvas = newPage(); inList = false }
                is WordBlock.EmbeddedImage -> {
                    val scale = USABLE_WIDTH.toFloat() / block.width.coerceAtLeast(1)
                    val h = (block.height * scale).toInt().coerceIn(20, 600).toFloat()
                    if (currentY + h > PAGE_HEIGHT - MARGIN_BOTTOM) canvas = newPage()
                    val bgPaint = Paint().apply { color = android.graphics.Color.rgb(230, 230, 230); style = Paint.Style.FILL }
                    canvas.drawRect(MARGIN_LEFT.toFloat(), currentY, MARGIN_LEFT + USABLE_WIDTH.toFloat(), currentY + h, bgPaint)
                    val labelPaint = Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
                    canvas.drawText("[Image]", MARGIN_LEFT + USABLE_WIDTH / 2f, currentY + h / 2f + 8f, labelPaint)
                    currentY += h + 8f
                    inList = false
                }
                is WordBlock.ChartBlock -> {
                    if (block.title.isNotEmpty()) {
                        val words = runsToWords(listOf(WordRun(block.title, bold = true, fontSize = 14f)))
                        val lines = wrapWords(words, USABLE_WIDTH, 0f, 0f)
                        for (line in lines) { ensureFits(line.height); currentY = drawLine(canvas, line, DocAlignment.LEFT, MARGIN_LEFT.toFloat(), currentY) }
                    }
                    for (s in block.series) {
                        val label = "${s.name}: ${s.values.joinToString(", ") { String.format("%.0f", it) }}"
                        val words = runsToWords(listOf(WordRun(label, fontSize = 11f)))
                        val lines = wrapWords(words, USABLE_WIDTH, 0f, 0f)
                        for (line in lines) { ensureFits(line.height); currentY = drawLine(canvas, line, DocAlignment.LEFT, MARGIN_LEFT.toFloat(), currentY) }
                    }
                    currentY += 12f
                    inList = false
                }
                is WordBlock.Unsupported -> {
                    val words = runsToWords(listOf(WordRun("[${block.description}]", fontSize = 11f)))
                    val lines = wrapWords(words, USABLE_WIDTH, 0f, 0f)
                    for (line in lines) { ensureFits(line.height); currentY = drawLine(canvas, line, DocAlignment.LEFT, MARGIN_LEFT.toFloat(), currentY) }
                    currentY += 4f
                    inList = false
                }
            }
        }

        pages.add(page)
        return pages.ifEmpty { listOf(createBlankPage()) }
    }

    // ── Rich text layout ─────────────────────────────────────────

    private fun runPaint(run: WordRun, forceBold: Boolean, forceSize: Float): Paint {
        val bold = run.bold || forceBold
        val typeface = when {
            bold && run.italic -> Typeface.create(Typeface.DEFAULT_BOLD, Typeface.ITALIC)
            bold -> Typeface.DEFAULT_BOLD
            run.italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
        return Paint().apply {
            this.typeface = typeface
            textSize = if (forceSize > 0f) forceSize else run.fontSize.coerceAtLeast(4f)
            isAntiAlias = true
            color = if (run.color != 0) run.color else android.graphics.Color.BLACK
            isUnderlineText = run.underline
            isStrikeThruText = run.strikethrough
        }
    }

    private fun runsToWords(runs: List<WordRun>, forceBold: Boolean = false, forceSize: Float = 0f): List<StyledWord> {
        val out = mutableListOf<StyledWord>()
        for (run in runs) {
            val paint = runPaint(run, forceBold, forceSize)
            for (piece in run.text.split(Regex("(?<=\\s)|(?=\\s)"))) {
                if (piece.isNotEmpty()) out += StyledWord(piece, paint, paint.textSize)
            }
        }
        return out
    }

    private fun wrapWords(words: List<StyledWord>, maxWidth: Float, blockIndent: Float, firstLineShift: Float): List<WrappedLine> {
        val lines = mutableListOf<WrappedLine>()
        if (words.isEmpty()) return lines
        var lineWords = mutableListOf<StyledWord>()
        var lineWidth = 0f
        var lineHeight = 0f

        for (word in words) {
            val w = word.paint.measureText(word.text)
            val isSpace = word.text.isBlank()
            if (lineWords.isNotEmpty() && !isSpace) {
                // Indent applies to every line via blockIndent; the first line additionally
                // shifts by firstLineShift (positive → first-line indent, negative → hanging).
                val shift = if (lines.isEmpty()) firstLineShift else 0f
                val budget = maxWidth - blockIndent - shift
                val extraSpace = lineWords.lastOrNull()?.let { it.paint.measureText(" ") } ?: 0f
                if (lineWidth + extraSpace + w > budget) {
                    lines += WrappedLine(lineWords, lineWidth, lineHeight, if (lines.isEmpty()) firstLineShift else 0f)
                    lineWords = mutableListOf()
                    lineWidth = 0f
                    lineHeight = 0f
                }
            }
            if (isSpace && lineWords.isEmpty()) continue // drop leading spaces on a line
            lineWords += word
            lineWidth += w
            lineHeight = max(lineHeight, word.sizePt)
        }
        if (lineWords.isNotEmpty()) {
            lines += WrappedLine(lineWords, lineWidth, lineHeight, if (lines.isEmpty()) firstLineShift else 0f)
        }
        return lines
    }

    private fun drawLine(canvas: Canvas, line: WrappedLine, alignment: DocAlignment, baseX: Float, baselineY: Float): Float {
        val lineX = baseX + line.xOffset
        val startX = when (alignment) {
            DocAlignment.CENTER -> MARGIN_LEFT + (USABLE_WIDTH - line.width - line.xOffset) / 2f + line.xOffset
            DocAlignment.RIGHT -> MARGIN_LEFT + USABLE_WIDTH - line.width
            else -> lineX
        }
        val baseline = baselineY + line.height // shared baseline for the whole line
        var x = startX
        for (word in line.words) {
            canvas.drawText(word.text, x, baseline, word.paint)
            x += word.paint.measureText(word.text)
        }
        return baselineY + line.height * LINE_SPACING
    }

    /** Formats a counter value according to a Word numFmt value. */
    private fun numberPrefix(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> toLetters(n).lowercase()
        "upperLetter" -> toLetters(n).uppercase()
        "lowerRoman" -> toRoman(n).lowercase()
        "upperRoman" -> toRoman(n).uppercase()
        else -> n.toString()
    }

    private fun toLetters(n: Int): String {
        var v = n; val sb = StringBuilder()
        while (v > 0) { v--; sb.insert(0, ('A' + (v % 26))); v /= 26 }
        return sb.toString()
    }

    private fun toRoman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val romans = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var v = n; val sb = StringBuilder()
        for (i in vals.indices) { while (v >= vals[i]) { sb.append(romans[i]); v -= vals[i] } }
        return sb.toString()
    }

    // ── Table drawing ────────────────────────────────────────────

    private fun drawTable(
        startCanvas: Canvas,
        table: WordBlock.Table,
        startY: Float,
        newPage: () -> Canvas,
    ): Pair<Canvas, Float> {
        var canvas = startCanvas
        var currentY = startY + 12f // spacing before table

        val colWidths = resolveColumnWidths(table)

        // Pre-calculate row heights
        data class RowMetrics(val row: WordTableRow, val height: Float, val isHeader: Boolean)
        val rowMetrics = mutableListOf<RowMetrics>()
        for ((ri, row) in table.rows.withIndex()) {
            val isHeader = row.isHeader || ri == 0
            val paint = if (isHeader) HEADER_PAINT else CELL_PAINT
            var maxLines = 1
            var colIdx = 0
            for (cell in row.cells) {
                val span = cell.gridSpan.coerceAtLeast(1)
                if (cell.vMergeCont) { colIdx += span; continue }
                val spanWidth = (colIdx until min(colIdx + span, colWidths.size)).sumOf { colWidths[it].toDouble() }.toFloat()
                val maxTextWidth = spanWidth - CELL_PADDING * 2
                if (maxTextWidth > 0) {
                    val words = cellWords(cell)
                    val lines = wrapWords(words, maxTextWidth, 0f, 0f)
                    maxLines = max(maxLines, lines.size)
                }
                colIdx += span
            }
            val cellHeight = max(maxLines, 1) * 12f * LINE_SPACING + CELL_PADDING * 2
            rowMetrics.add(RowMetrics(row, cellHeight, isHeader))
        }

        val headerMetrics = rowMetrics.firstOrNull { it.isHeader }

        fun drawRow(rCanvas: Canvas, y: Float, metrics: RowMetrics) {
            val row = metrics.row
            val isHeader = metrics.isHeader
            var cellX = MARGIN_LEFT.toFloat()
            var colIdx = 0
            for (cell in row.cells) {
                val span = cell.gridSpan.coerceAtLeast(1)
                val spanWidth = (colIdx until min(colIdx + span, colWidths.size)).sumOf { colWidths[it].toDouble() }.toFloat()
                if (cell.vMergeCont) { cellX += spanWidth; colIdx += span; continue }
                val cellTop = y
                val bgColor = when {
                    cell.background != 0 -> cell.background
                    isHeader -> android.graphics.Color.rgb(235, 235, 235)
                    else -> android.graphics.Color.TRANSPARENT
                }
                if (bgColor != android.graphics.Color.TRANSPARENT) {
                    val bgPaint = Paint().apply { color = bgColor; style = Paint.Style.FILL }
                    rCanvas.drawRect(cellX, cellTop, cellX + spanWidth, cellTop + metrics.height, bgPaint)
                }
                rCanvas.drawRect(cellX, cellTop, cellX + spanWidth, cellTop + metrics.height, BORDER_PAINT)

                val maxTextWidth = spanWidth - CELL_PADDING * 2
                if (maxTextWidth > 0) {
                    val words = cellWords(cell)
                    if (words.isNotEmpty()) {
                        val lines = wrapWords(words, maxTextWidth, 0f, 0f)
                        var textY = cellTop + CELL_PADDING
                        for (line in lines) {
                            var x = cellX + CELL_PADDING
                            for (word in line.words) {
                                rCanvas.drawText(word.text, x, textY + word.sizePt, word.paint)
                                x += word.paint.measureText(word.text)
                            }
                            textY += line.height * LINE_SPACING
                        }
                    }
                }
                cellX += spanWidth
                colIdx += span
            }
        }

        for ((ri, metrics) in rowMetrics.withIndex()) {
            if (currentY + metrics.height > PAGE_HEIGHT - MARGIN_BOTTOM) {
                canvas = newPage()
                currentY = MARGIN_TOP.toFloat()
                if (ri > 0 && headerMetrics != null && headerMetrics.height <= PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM) {
                    drawRow(canvas, currentY, headerMetrics)
                    currentY += headerMetrics.height
                }
            }
            drawRow(canvas, currentY, metrics)
            currentY += metrics.height
        }

        return canvas to currentY + 12f // + spacing after table
    }

    /** Builds styled words from all paragraphs in a cell (keeps run styles). */
    private fun cellWords(cell: WordTableCell): List<StyledWord> {
        val out = mutableListOf<StyledWord>()
        for (b in cell.blocks) {
            when (b) {
                is WordBlock.Paragraph -> out += runsToWords(b.runs)
                is WordBlock.Heading -> out += runsToWords(b.runs, forceBold = true, forceSize = headingFontSize(b.level))
                is WordBlock.ListItem -> {
                    val runs = if (b.bulleted) listOf(WordRun("• ")) + b.runs else b.runs
                    out += runsToWords(runs)
                }
                else -> Unit
            }
        }
        return out
    }

    private fun resolveColumnWidths(table: WordBlock.Table): List<Float> {
        if (table.columnWidths.isNotEmpty() && table.columnWidths.sum() > 0) {
            val totalW = table.columnWidths.sum()
            return table.columnWidths.map { (it / totalW) * USABLE_WIDTH }
        }
        var maxCols = 0
        for (row in table.rows.take(5)) {
            var colIdx = 0
            for (cell in row.cells) colIdx += cell.gridSpan.coerceAtLeast(1)
            maxCols = maxOf(maxCols, colIdx)
        }
        if (maxCols < 1) maxCols = table.rows.maxOfOrNull { it.cells.size } ?: 4
        return List(maxCols) { USABLE_WIDTH / maxCols.toFloat() }
    }

    // ── Shared Paint objects (avoid GC thrash) ─────────────────

    private val CELL_PAINT = Paint().apply {
        textSize = 12f; color = android.graphics.Color.BLACK
        isAntiAlias = true; typeface = Typeface.DEFAULT
    }
    private val HEADER_PAINT = Paint().apply {
        textSize = 13f; color = android.graphics.Color.BLACK
        isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; isFakeBoldText = true
    }
    private val BORDER_PAINT = Paint().apply {
        color = android.graphics.Color.rgb(180, 180, 180)
        style = Paint.Style.STROKE; strokeWidth = 1f
    }

    private fun createBlankPage(): Bitmap {
        val bmp = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        return bmp
    }
}
