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
                        android.graphics.Color.BLACK, 0, currentY, 16f, 12f, ::newPage)
                    canvas = r.first; currentY = r.second
                }
                is WordBlock.Paragraph -> {
                    val combined = block.runs.joinToString("") { it.text }
                    val isBold = block.runs.any { it.bold } || inferFormatting(combined).bold
                    val fontSize = if (inferFormatting(combined).isHeading) inferFormatting(combined).headingSize
                        else block.runs.firstOrNull { it.fontSize > 0f }?.fontSize ?: 13f
                    val isItalic = block.runs.any { it.italic }
                    val color = block.runs.firstOrNull { it.color != 0 }?.color ?: android.graphics.Color.BLACK
                    val indent = if (block.indent.firstLine > 0f || block.indent.left > 0f) 1 else 0
                    val r = drawWrappedText(canvas, combined, fontSize, isBold, isItalic,
                        color, indent, currentY, 0f, 8f, ::newPage)
                    canvas = r.first; currentY = r.second
                }
                is WordBlock.ListItem -> {
                    val prefix = if (block.bulleted) "\u2022 " else "${block.level + 1}. "
                    val combined = prefix + block.runs.joinToString("") { it.text }
                    val isBold = block.runs.any { it.bold }
                    val fontSize = block.runs.firstOrNull { it.fontSize > 0f }?.fontSize ?: 13f
                    val color = block.runs.firstOrNull { it.color != 0 }?.color ?: android.graphics.Color.BLACK
                    val r = drawWrappedText(canvas, combined, fontSize, isBold, false,
                        color, block.level, currentY, 0f, 4f, ::newPage)
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
                            android.graphics.Color.BLACK, 0, currentY, 0f, 0f, ::newPage)
                        canvas = r.first; currentY = r.second
                    }
                    for (s in block.series) {
                        val label = "${s.name}: ${s.values.joinToString(", ") { String.format("%.0f", it) }}"
                        val r = drawWrappedText(canvas, label, 11f, false, false,
                            android.graphics.Color.DKGRAY, 0, currentY, 0f, 0f, ::newPage)
                        canvas = r.first; currentY = r.second
                    }
                    currentY += 12f
                }
                is WordBlock.Unsupported -> {
                    val r = drawWrappedText(canvas, "[${block.description}]", 11f, false, false,
                        android.graphics.Color.GRAY, 0, currentY, 0f, 4f, ::newPage)
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
        color: Int, indent: Int, startY: Float,
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
            typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val maxWidth = USABLE_WIDTH - indent * 50
        val x = MARGIN_LEFT + indent * 50f
        val lineHeight = fontSize * LINE_SPACING

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
                canvasRef.drawText(currentLine.toString(), x, currentY + fontSize, paint)
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
            canvasRef.drawText(currentLine.toString(), x, currentY + fontSize, paint)
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

        // Resolve column widths
        val colWidths = resolveColumnWidths(table)
        val totalCols = colWidths.size

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
                    val cellText = cell.blocks.filterIsInstance<WordBlock.Paragraph>()
                        .joinToString(" ") { it.runs.joinToString("") { r -> r.text } }
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

        // Draw rows with page break handling
        for ((ri, metrics) in rowMetrics.withIndex()) {
            val rowHeight = metrics.height

            // Check if row fits on current page
            if (currentY + rowHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                canvas = newPage()
                currentY = MARGIN_TOP.toFloat()
            }

            val row = metrics.row
            val isHeader = metrics.isHeader
            val cellPaint = if (isHeader) HEADER_PAINT else CELL_PAINT
            var cellX = MARGIN_LEFT.toFloat()
            var colIdx = 0

            for (cell in row.cells) {
                val span = cell.gridSpan.coerceAtLeast(1)
                val spanWidth = (colIdx until min(colIdx + span, colWidths.size)).sumOf { colWidths[it].toDouble() }.toFloat()
                val cellTop = currentY
                val cellBg = cell.background

                // Background
                val bgColor = when {
                    cellBg != 0 -> cellBg
                    isHeader -> android.graphics.Color.rgb(235, 235, 235)
                    ri % 2 == 1 -> android.graphics.Color.rgb(248, 248, 248)
                    else -> android.graphics.Color.TRANSPARENT
                }
                if (bgColor != android.graphics.Color.TRANSPARENT) {
                    val bgPaint = Paint().apply { color = bgColor; style = Paint.Style.FILL }
                    canvas.drawRect(cellX, cellTop, cellX + spanWidth, cellTop + rowHeight, bgPaint)
                }

                // Border
                canvas.drawRect(cellX, cellTop, cellX + spanWidth, cellTop + rowHeight, BORDER_PAINT)

                // Cell text
                val maxTextWidth = spanWidth - CELL_PADDING * 2
                if (maxTextWidth > 0) {
                    val cellText = cell.blocks.filterIsInstance<WordBlock.Paragraph>()
                        .joinToString(" ") { it.runs.joinToString("") { r -> r.text } }
                    if (cellText.isNotBlank()) {
                        val isCellBold = isHeader || cell.blocks.filterIsInstance<WordBlock.Paragraph>()
                            .any { p -> p.runs.any { it.bold } }
                        val paint = if (isCellBold) HEADER_PAINT else CELL_PAINT
                        val words = cellText.split(" ")
                        var textY = cellTop + CELL_PADDING + 12f
                        var lineStart = StringBuilder()
                        var lineWidth = 0f
                        for (word in words) {
                            val ww = paint.measureText(word)
                            val testW = if (lineStart.isEmpty()) ww else lineWidth + paint.measureText(" ") + ww
                            if (testW > maxTextWidth && lineStart.isNotEmpty()) {
                                canvas.drawText(lineStart.toString(), cellX + CELL_PADDING, textY, paint)
                                textY += 12f * LINE_SPACING
                                lineStart = StringBuilder(word)
                                lineWidth = ww
                            } else {
                                if (lineStart.isEmpty()) lineStart.append(word) else lineStart.append(" ").append(word)
                                lineWidth = testW
                            }
                        }
                        if (lineStart.isNotEmpty()) {
                            canvas.drawText(lineStart.toString(), cellX + CELL_PADDING, textY, paint)
                        }
                    }
                }

                cellX += spanWidth
                colIdx += span
            }
            currentY += rowHeight
        }

        return canvas to currentY + 12f // + spacing after table
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

    private fun createBlankPage(): Bitmap {
        val bmp = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        return bmp
    }
}
