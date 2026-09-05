package com.example.fileconverter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a [WordDocument] into page-sized Bitmaps.
 * Properly handles column widths, merged cells (gridSpan), cell backgrounds,
 * multi-page tables, and cell paragraph structure.
 */
object DocPageRenderer {

    private const val PAGE_WIDTH = 1080
    private const val PAGE_HEIGHT = 1527
    private const val MARGIN_LEFT = 72
    private const val MARGIN_RIGHT = 72
    private const val MARGIN_TOP = 72
    private const val MARGIN_BOTTOM = 72
    private const val USABLE_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT
    private const val LINE_SPACING = 1.4f
    private const val CELL_PADDING = 8f

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

        fun newPage(): Canvas {
            pages.add(page)
            page = createBlankPage()
            currentY = MARGIN_TOP.toFloat()
            return Canvas(page)
        }

        for (block in doc.blocks) {
            when (block) {
                is WordBlock.Table -> {
                    val result = drawTable(canvas, block, currentY, ::newPage)
                    canvas = result.first
                    currentY = result.second
                }
                is WordBlock.Heading -> {
                    val combined = block.runs.joinToString("") { it.text }
                    val fontSize = headingFontSize(block.level)
                    val r = drawWrappedText(canvas, combined, fontSize, true, false,
                        android.graphics.Color.BLACK, 0, block.alignment, currentY, 16f, 12f, ::newPage)
                    canvas = r.first; currentY = r.second
                }
                is WordBlock.Paragraph -> {
                    val combined = block.runs.joinToString("") { it.text }
                    val inferred = inferFormatting(combined)
                    // Parser-provided formatting wins; inference is only a fallback
                    // for parsers that don't emit explicit run styling.
                    val hasExplicit = block.runs.any { it.bold || it.italic } ||
                        block.runs.any { it.fontSize != 0f && it.fontSize != 12f }
                    val isBold = block.runs.any { it.bold } || (!hasExplicit && inferred.bold)
                    val fontSize = if (hasExplicit)
                        (block.runs.firstOrNull { it.fontSize > 0f }?.fontSize ?: 13f)
                    else if (inferred.isHeading) inferred.headingSize else 13f
                    val isItalic = block.runs.any { it.italic }
                    val color = block.runs.firstOrNull { it.color != 0 }?.color ?: android.graphics.Color.BLACK
                    val indent = if (block.indent.firstLine > 0f || block.indent.left > 0f) 1 else 0
                    val r = drawWrappedText(canvas, combined, fontSize, isBold, isItalic,
                        color, indent, block.alignment, currentY, 0f, 8f, ::newPage)
                    canvas = r.first; currentY = r.second
                }
                is WordBlock.ListItem -> {
                    val prefix = if (block.bulleted) "\u2022 " else "${block.level + 1}. "
                    val combined = prefix + block.runs.joinToString("") { it.text }
                    val isBold = block.runs.any { it.bold }
                    val fontSize = block.runs.firstOrNull { it.fontSize > 0f }?.fontSize ?: 13f
                    val color = block.runs.firstOrNull { it.color != 0 }?.color ?: android.graphics.Color.BLACK
                    val r = drawWrappedText(canvas, combined, fontSize, isBold, false,
                        color, block.level, DocAlignment.LEFT, currentY, 0f, 4f, ::newPage)
                    canvas = r.first; currentY = r.second
                }
                is WordBlock.PageBreak -> { canvas = newPage() }
                is WordBlock.EmbeddedImage -> {
                    val scale = USABLE_WIDTH.toFloat() / block.width.coerceAtLeast(1)
                    val h = (block.height * scale).toInt().coerceIn(20, 600).toFloat()
                    if (currentY + h > PAGE_HEIGHT - MARGIN_BOTTOM) canvas = newPage()
                    val bgPaint = Paint().apply { color = android.graphics.Color.rgb(230, 230, 230); style = Paint.Style.FILL }
                    canvas.drawRect(MARGIN_LEFT.toFloat(), currentY, MARGIN_LEFT + USABLE_WIDTH.toFloat(), currentY + h, bgPaint)
                    val labelPaint = Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
                    canvas.drawText("[Image]", MARGIN_LEFT + USABLE_WIDTH / 2f, currentY + h / 2f + 8f, labelPaint)
                    currentY += h + 8f
                }
                is WordBlock.ChartBlock -> {
                    if (block.title.isNotEmpty()) {
                        val r = drawWrappedText(canvas, block.title, 14f, true, false,
                            android.graphics.Color.BLACK, 0, DocAlignment.LEFT, currentY, 0f, 0f, ::newPage)
                        canvas = r.first; currentY = r.second
                    }
                    for (s in block.series) {
                        val label = "${s.name}: ${s.values.joinToString(", ") { String.format("%.0f", it) }}"
                        val r = drawWrappedText(canvas, label, 11f, false, false,
                            android.graphics.Color.DKGRAY, 0, DocAlignment.LEFT, currentY, 0f, 0f, ::newPage)
                        canvas = r.first; currentY = r.second
                    }
                    currentY += 12f
                }
                is WordBlock.Unsupported -> {
                    val r = drawWrappedText(canvas, "[${block.description}]", 11f, false, false,
                        android.graphics.Color.GRAY, 0, DocAlignment.LEFT, currentY, 0f, 4f, ::newPage)
                    canvas = r.first; currentY = r.second
                }
            }
        }

        pages.add(page)
        return pages.ifEmpty { listOf(createBlankPage()) }
    }

    // ── Text drawing ───────────────────────────────────────────

    private fun drawWrappedText(
        canvas: Canvas, text: String, fontSize: Float, isBold: Boolean, isItalic: Boolean,
        color: Int, indent: Int, alignment: DocAlignment, startY: Float,
        spaceBefore: Float, spaceAfter: Float,
        newPage: () -> Canvas,
    ): Pair<Canvas, Float> {
        if (text.isBlank()) return canvas to (startY + spaceBefore + spaceAfter + fontSize * 0.5f)

        var currentY = startY + spaceBefore
        val paint = Paint().apply {
            textSize = fontSize
            isFakeBoldText = isBold
            this.color = color
            isAntiAlias = true
            typeface = when {
                isBold && isItalic -> Typeface.create(Typeface.DEFAULT_BOLD, Typeface.ITALIC)
                isBold -> Typeface.DEFAULT_BOLD
                isItalic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else -> Typeface.DEFAULT
            }
        }
        val maxWidth = USABLE_WIDTH - indent * 50
        val lineHeight = fontSize * LINE_SPACING

        fun lineX(w: Float): Float = when (alignment) {
            DocAlignment.CENTER -> MARGIN_LEFT + (USABLE_WIDTH - w) / 2f
            DocAlignment.RIGHT -> MARGIN_LEFT + USABLE_WIDTH - w
            else -> MARGIN_LEFT + indent * 50f
        }

        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var currentLine = StringBuilder()

        var canvasRef = canvas

        for (word in words) {
            val test = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(test) > maxWidth && currentLine.isNotEmpty()) {
                if (currentY + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                    canvasRef = newPage()
                    currentY = MARGIN_TOP.toFloat()
                }
                canvasRef.drawText(currentLine.toString(), lineX(paint.measureText(currentLine.toString())), currentY + fontSize, paint)
                currentY += lineHeight
                currentLine = StringBuilder(word)
            } else {
                if (currentLine.isEmpty()) currentLine = StringBuilder(word)
                else currentLine.append(" ").append(word)
            }
        }
        if (currentLine.isNotEmpty()) {
            if (currentY + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                canvasRef = newPage()
                currentY = MARGIN_TOP.toFloat()
            }
            canvasRef.drawText(currentLine.toString(), lineX(paint.measureText(currentLine.toString())), currentY + fontSize, paint)
            currentY += lineHeight
        }

        return canvasRef to (currentY + spaceAfter)
    }

    // ── Table drawing ──────────────────────────────────────────

    /**
     * Draw a table with proper column widths, gridSpan, cell backgrounds,
     * and page break handling. Returns (finalCanvas, finalY, intermediatePages).
     */
    private fun drawTable(
        startCanvas: Canvas,
        table: WordBlock.Table,
        startY: Float,
        newPage: () -> Canvas,
    ): Pair<Canvas, Float> {
        var canvas = startCanvas
        var currentY = startY + 12f // spacing before table

        val colWidths = resolveColumnWidths(table)
        val isQuestionnaire = colWidths.size == 4 && isQuestionnaireTable(table)

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
                val spanWidth = (colIdx until min(colIdx + span, colWidths.size)).sumOf { colWidths[it].toDouble() }.toFloat()
                val maxTextWidth = spanWidth - CELL_PADDING * 2
                if (maxTextWidth > 0) {
                    val cellText = cellText(cell)
                    val words = cellText.split(" ")
                    var lineCount = 1
                    var curWidth = 0f
                    for (word in words) {
                        val ww = paint.measureText(word)
                        if (curWidth + ww > maxTextWidth && curWidth > 0) {
                            lineCount++; curWidth = ww
                        } else {
                            curWidth += ww + paint.measureText(" ")
                        }
                    }
                    maxLines = maxOf(maxLines, lineCount)
                }
                colIdx += span
            }
            val cellHeight = (12f * LINE_SPACING * maxLines + CELL_PADDING * 2).coerceAtLeast(30f)
            rowMetrics.add(RowMetrics(row, cellHeight, isHeader))
        }

        val headerMetrics = rowMetrics.firstOrNull { it.isHeader }

        // Draw a single row at (y). Returns nothing; caller advances y.
        fun drawRow(rCanvas: Canvas, y: Float, metrics: RowMetrics) {
            val row = metrics.row
            val isHeader = metrics.isHeader
            val cellPaint = if (isHeader) HEADER_PAINT else CELL_PAINT
            var cellX = MARGIN_LEFT.toFloat()
            var colIdx = 0
            for ((ci, cell) in row.cells.withIndex()) {
                val span = cell.gridSpan.coerceAtLeast(1)
                val spanWidth = (colIdx until min(colIdx + span, colWidths.size)).sumOf { colWidths[it].toDouble() }.toFloat()
                val cellTop = y
                val cellBg = cell.background

                // Background
                val bgColor = when {
                    cellBg != 0 -> cellBg
                    isHeader -> android.graphics.Color.rgb(235, 235, 235)
                    else -> android.graphics.Color.TRANSPARENT
                }
                if (bgColor != android.graphics.Color.TRANSPARENT) {
                    val bgPaint = Paint().apply { color = bgColor; style = Paint.Style.FILL }
                    rCanvas.drawRect(cellX, cellTop, cellX + spanWidth, cellTop + metrics.height, bgPaint)
                }

                // Border
                rCanvas.drawRect(cellX, cellTop, cellX + spanWidth, cellTop + metrics.height, BORDER_PAINT)

                // Cell text
                val maxTextWidth = spanWidth - CELL_PADDING * 2
                val text = cellText(cell)
                if (maxTextWidth > 0 && text.isNotBlank()) {
                    val isCellBold = isHeader || cell.blocks.filterIsInstance<WordBlock.Paragraph>()
                        .any { p -> p.runs.any { it.bold } }
                    val paint = if (isCellBold) HEADER_PAINT else CELL_PAINT
                    val words = text.split(" ")
                    var textY = cellTop + CELL_PADDING + 12f
                    var lineStart = StringBuilder()
                    var lineWidth = 0f
                    for (word in words) {
                        val ww = paint.measureText(word)
                        val testW = if (lineStart.isEmpty()) ww else lineWidth + paint.measureText(" ") + ww
                        if (testW > maxTextWidth && lineStart.isNotEmpty()) {
                            rCanvas.drawText(lineStart.toString(), cellX + CELL_PADDING, textY, paint)
                            textY += 12f * LINE_SPACING
                            lineStart = StringBuilder(word)
                            lineWidth = ww
                        } else {
                            if (lineStart.isEmpty()) lineStart.append(word) else lineStart.append(" ").append(word)
                            lineWidth = testW
                        }
                    }
                    if (lineStart.isNotEmpty()) {
                        rCanvas.drawText(lineStart.toString(), cellX + CELL_PADDING, textY, paint)
                    }
                } else if (isQuestionnaire && ci >= 2 && text.isBlank()) {
                    // Empty "Ya"/"Tidak" cells in a questionnaire get a checkbox box
                    drawCheckBox(rCanvas, cellX, cellTop, spanWidth, metrics.height)
                }

                cellX += spanWidth
                colIdx += span
            }
        }

        // Draw rows with page break handling + repeated header on each page
        for ((ri, metrics) in rowMetrics.withIndex()) {
            if (currentY + metrics.height > PAGE_HEIGHT - MARGIN_BOTTOM) {
                canvas = newPage()
                currentY = MARGIN_TOP.toFloat()
                // Repeat the header row at the top of the new page, like most viewers.
                if (ri > 0 && headerMetrics != null &&
                    headerMetrics.height <= PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM
                ) {
                    drawRow(canvas, currentY, headerMetrics)
                    currentY += headerMetrics.height
                }
            }
            drawRow(canvas, currentY, metrics)
            currentY += metrics.height
        }

        return canvas to currentY + 12f // + spacing after table
    }

    private fun cellText(cell: WordTableCell): String =
        cell.blocks.filterIsInstance<WordBlock.Paragraph>()
            .joinToString(" ") { it.runs.joinToString("") { r -> r.text } }

    /** Questionnaire-style table: 4 columns where most data rows start with a small number. */
    private fun isQuestionnaireTable(table: WordBlock.Table): Boolean {
        val dataRows = table.rows.drop(1)
        if (dataRows.isEmpty()) return false
        val numeric = dataRows.count { row ->
            row.cells.firstOrNull()?.let { c -> cellText(c).trim().toIntOrNull() != null } ?: false
        }
        return numeric.toFloat() / dataRows.size > 0.5f
    }

    private fun drawCheckBox(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val side = min(w, 14f) - 2f
        if (side < 6f) return
        val left = x + (w - side) / 2f
        val top = y + (h - side) / 2f
        c.drawRect(left, top, left + side, top + side, CHECK_PAINT)
    }

    private fun resolveColumnWidths(table: WordBlock.Table): List<Float> {
        // Use parser-provided column widths if available
        if (table.columnWidths.isNotEmpty() && table.columnWidths.sum() > 0) {
            val totalW = table.columnWidths.sum()
            return table.columnWidths.map { (it / totalW) * USABLE_WIDTH }
        }

        // Fallback: detect from gridSpan usage in first few rows
        var maxCols = 0
        for (row in table.rows.take(5)) {
            var colIdx = 0
            for (cell in row.cells) {
                colIdx += cell.gridSpan.coerceAtLeast(1)
            }
            maxCols = maxOf(maxCols, colIdx)
        }
        if (maxCols < 1) maxCols = table.rows.maxOfOrNull { it.cells.size } ?: 4

        // Questionnaire tables get narrow number/checkbox columns and a wide item column.
        if (maxCols == 4 && isQuestionnaireTable(table)) {
            return listOf(0.10f, 0.64f, 0.13f, 0.13f).map { it * USABLE_WIDTH }
        }

        return List(maxCols) { USABLE_WIDTH / maxCols.toFloat() }
    }

    // ── Formatting inference ───────────────────────────────────

    private data class InferredFormat(val bold: Boolean, val isHeading: Boolean, val headingSize: Float)

    private fun inferFormatting(text: String): InferredFormat {
        val trimmed = text.trim()
        val isAllCaps = trimmed.length in 3..80 && trimmed == trimmed.uppercase() && trimmed.any { it.isLetter() }
        val titleKeywords = listOf("INVENTORI", "PERSONALITI", "SIDEK", "IPS", "BORANG", "ARAHAN", "SKOR", "PROFIL")
        val hasTitleKeyword = titleKeywords.any { trimmed.contains(it, ignoreCase = true) }
        val isShort = trimmed.length < 40
        val isTitle = isAllCaps || (hasTitleKeyword && isShort && trimmed.count { it.isLetter() } > 5)
        val numberedItem = Regex("^\\d+\\.\\s+.+").matches(trimmed)
        return when {
            isTitle && trimmed.length < 30 -> InferredFormat(true, true, 24f)
            isTitle -> InferredFormat(true, true, 20f)
            numberedItem -> InferredFormat(true, false, 14f)
            else -> InferredFormat(false, false, 13f)
        }
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
    private val CHECK_PAINT = Paint().apply {
        color = android.graphics.Color.rgb(120, 120, 120)
        style = Paint.Style.STROKE; strokeWidth = 1.5f
    }

    private fun createBlankPage(): Bitmap {
        val bmp = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        return bmp
    }
}
