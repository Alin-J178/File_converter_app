package com.example.fileconverter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.min

/**
 * Renders a [WordDocument] into a list of page-sized Bitmaps,
 * giving a PDF-like page-by-page view.
 */
object DocPageRenderer {

    // A4-like proportions at 150 DPI
    private const val PAGE_WIDTH = 1080
    private const val PAGE_HEIGHT = 1527  // 1527 ~ A4 ratio
    private const val MARGIN_LEFT = 56
    private const val MARGIN_RIGHT = 56
    private const val MARGIN_TOP = 56
    private const val MARGIN_BOTTOM = 56
    private const val USABLE_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT
    private const val LINE_SPACING = 1.35f

    fun render(doc: WordDocument): List<Bitmap> {
        if (doc.blocks.isEmpty()) {
            val bmp = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            val p = Paint().apply { color = android.graphics.Color.GRAY; textSize = 40f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
            canvas.drawText("Empty document", PAGE_WIDTH / 2f, PAGE_HEIGHT / 2f, p)
            return listOf(bmp)
        }

        // Lay out all blocks into lines
        val lines = mutableListOf<LayoutLine>()
        for (block in doc.blocks) {
            when (block) {
                is WordBlock.Table -> layoutTable(block, lines)
                is WordBlock.Heading -> layoutHeading(block, lines)
                is WordBlock.Paragraph -> layoutParagraph(block, lines)
                is WordBlock.ListItem -> layoutListItem(block, lines)
                is WordBlock.PageBreak -> lines.add(LayoutLine.PageBreak)
                is WordBlock.EmbeddedImage -> layoutImage(block, lines)
                is WordBlock.ChartBlock -> layoutChart(block, lines)
                is WordBlock.Unsupported -> layoutUnsupported(block, lines)
            }
        }

        // Split lines into pages
        return paginateToBitmaps(lines)
    }

    // ── Layout helpers ──────────────────────────────────────────

    private sealed class LayoutLine {
        data class Text(val text: String, val fontSize: Float, val isBold: Boolean, val isItalic: Boolean, val color: Int, val indent: Int = 0) : LayoutLine()
        data class Spacing(val height: Float) : LayoutLine()
        data class TableRow(val cells: List<String>, val isHeader: Boolean, val colCount: Int) : LayoutLine()
        data class ImageLine(val width: Int, val height: Int) : LayoutLine()
        data class Separator(val height: Float) : LayoutLine()
        object PageBreak : LayoutLine()
    }

    private fun layoutParagraph(block: WordBlock.Paragraph, lines: MutableList<LayoutLine>) {
        if (block.runs.isEmpty() || block.runs.all { it.text.isBlank() }) {
            lines.add(LayoutLine.Spacing(16f))
            return
        }
        val combined = block.runs.joinToString("") { it.text }
        val isBold = block.runs.any { it.bold }
        val isItalic = block.runs.any { it.italic }
        val fontSize = block.runs.firstOrNull { it.fontSize > 0f }?.fontSize ?: 13f
        val color = block.runs.firstOrNull { it.color != 0 }?.color ?: android.graphics.Color.BLACK
        val indent = if (block.indent.firstLine > 0f || block.indent.left > 0f) 1 else 0
        wrapText(combined, fontSize, isBold, color, indent, lines)
        lines.add(LayoutLine.Spacing(8f))
    }

    private fun layoutHeading(block: WordBlock.Heading, lines: MutableList<LayoutLine>) {
        lines.add(LayoutLine.Spacing(16f))
        val combined = block.runs.joinToString("") { it.text }
        val fontSize = headingFontSize(block.level)
        wrapText(combined, fontSize, true, android.graphics.Color.BLACK, 0, lines)
        lines.add(LayoutLine.Spacing(12f))
    }

    private fun layoutListItem(block: WordBlock.ListItem, lines: MutableList<LayoutLine>) {
        val prefix = if (block.bulleted) "\u2022 " else "${block.level + 1}. "
        val combined = prefix + block.runs.joinToString("") { it.text }
        val isBold = block.runs.any { it.bold }
        val fontSize = block.runs.firstOrNull { it.fontSize > 0f }?.fontSize ?: 13f
        val color = block.runs.firstOrNull { it.color != 0 }?.color ?: android.graphics.Color.BLACK
        wrapText(combined, fontSize, isBold, color, block.level, lines)
        lines.add(LayoutLine.Spacing(4f))
    }

    private fun layoutTable(block: WordBlock.Table, lines: MutableList<LayoutLine>) {
        lines.add(LayoutLine.Spacing(12f))
        for ((ri, row) in block.rows.withIndex()) {
            val cellTexts = row.cells.map { cell ->
                cell.blocks.filterIsInstance<WordBlock.Paragraph>()
                    .joinToString(" ") { it.runs.joinToString("") { r -> r.text } }
            }
            lines.add(LayoutLine.TableRow(cellTexts, ri == 0, row.cells.size))
        }
        lines.add(LayoutLine.Spacing(12f))
    }

    private fun layoutImage(block: WordBlock.EmbeddedImage, lines: MutableList<LayoutLine>) {
        val scale = USABLE_WIDTH.toFloat() / block.width.coerceAtLeast(1)
        val h = (block.height * scale).toInt().coerceIn(20, 600)
        lines.add(LayoutLine.Spacing(8f))
        lines.add(LayoutLine.ImageLine(USABLE_WIDTH, h))
        lines.add(LayoutLine.Spacing(8f))
    }

    private fun layoutChart(block: WordBlock.ChartBlock, lines: MutableList<LayoutLine>) {
        if (block.title.isNotEmpty()) {
            lines.add(LayoutLine.Text(block.title, 14f, true, false, android.graphics.Color.BLACK, 0))
        }
        for (s in block.series) {
            val label = "${s.name}: ${s.values.joinToString(", ") { String.format("%.0f", it) }}"
            lines.add(LayoutLine.Text(label, 11f, false, false, android.graphics.Color.DKGRAY, 0))
        }
        lines.add(LayoutLine.Spacing(12f))
    }

    private fun layoutUnsupported(block: WordBlock.Unsupported, lines: MutableList<LayoutLine>) {
        lines.add(LayoutLine.Text("[${block.description}]", 11f, false, false, android.graphics.Color.GRAY, 0))
        lines.add(LayoutLine.Spacing(4f))
    }

    private fun wrapText(text: String, fontSize: Float, isBold: Boolean, color: Int, indent: Int, lines: MutableList<LayoutLine>) {
        val paint = Paint().apply {
            this.textSize = fontSize * 2  // Canvas uses px, we scale later
            this.isFakeBoldText = isBold
            this.isAntiAlias = true
            typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val maxWidth = (USABLE_WIDTH - indent * 60) * 2  // px at 2x scale
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var currentLine = StringBuilder()
        for (word in words) {
            val test = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(test) > maxWidth && currentLine.isNotEmpty()) {
                lines.add(LayoutLine.Text(currentLine.toString(), fontSize, isBold, false, color, indent))
                currentLine = StringBuilder(word)
            } else {
                if (currentLine.isEmpty()) currentLine = StringBuilder(word)
                else currentLine.append(" ").append(word)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(LayoutLine.Text(currentLine.toString(), fontSize, isBold, false, color, indent))
        }
        if (text.isBlank()) {
            lines.add(LayoutLine.Spacing(fontSize * LINE_SPACING))
        }
    }

    // ── Pagination ──────────────────────────────────────────────

    private fun paginateToBitmaps(lines: MutableList<LayoutLine>): List<Bitmap> {
        val pages = mutableListOf<Bitmap>()
        var currentY = 0f
        var page = createBlankPage()
        var canvas = Canvas(page)

        fun startNewPage(): Canvas {
            pages.add(page)
            page = createBlankPage()
            currentY = MARGIN_TOP.toFloat()
            return Canvas(page)
        }

        currentY = MARGIN_TOP.toFloat()

        for (line in lines) {
            when (line) {
                is LayoutLine.PageBreak -> {
                    canvas = startNewPage()
                    continue
                }
                is LayoutLine.Spacing -> {
                    currentY += line.height
                }
                is LayoutLine.Separator -> {
                    currentY += line.height
                }
                is LayoutLine.Text -> {
                    val lineHeight = line.fontSize * LINE_SPACING
                    if (currentY + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        canvas = startNewPage()
                    }
                    val paint = Paint().apply {
                        textSize = line.fontSize * 2
                        isFakeBoldText = line.isBold
                        color = line.color
                        isAntiAlias = true
                        typeface = if (line.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    val x = (MARGIN_LEFT + line.indent * 60) * 2f
                    canvas.drawText(line.text, x, currentY + line.fontSize * 2, paint)
                    currentY += lineHeight * 2
                }
                is LayoutLine.TableRow -> {
                    val cellHeight = 36f * 2
                    if (currentY + cellHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        canvas = startNewPage()
                    }
                    val colWidth = USABLE_WIDTH * 2f / line.colCount.coerceAtLeast(1)
                    val bgPaint = Paint().apply {
                        color = if (line.isHeader) android.graphics.Color.rgb(240, 240, 240) else android.graphics.Color.TRANSPARENT
                        style = Paint.Style.FILL
                    }
                    val borderPaint = Paint().apply {
                        color = android.graphics.Color.rgb(200, 200, 200)
                        style = Paint.Style.STROKE
                        strokeWidth = 1f
                    }
                    val textPaint = Paint().apply {
                        textSize = 22f
                        isFakeBoldText = line.isHeader
                        color = android.graphics.Color.BLACK
                        isAntiAlias = true
                        typeface = if (line.isHeader) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }

                    for ((ci, cellText) in line.cells.withIndex()) {
                        val cellLeft = MARGIN_LEFT * 2f + ci * colWidth
                        val cellTop = currentY
                        // Background
                        canvas.drawRect(cellLeft, cellTop, cellLeft + colWidth, cellTop + cellHeight, bgPaint)
                        // Border
                        canvas.drawRect(cellLeft, cellTop, cellLeft + colWidth, cellTop + cellHeight, borderPaint)
                        // Text (truncated to fit)
                        val maxChars = (colWidth / textPaint.measureText("m")).toInt().coerceAtLeast(1)
                        val displayText = if (cellText.length > maxChars) cellText.take(maxChars - 1) + "\u2026" else cellText
                        canvas.drawText(displayText, cellLeft + 8f, cellTop + cellHeight - 10f, textPaint)
                    }
                    currentY += cellHeight
                }
                is LayoutLine.ImageLine -> {
                    val h = line.height * 2f
                    if (currentY + h > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        canvas = startNewPage()
                    }
                    val placeholderPaint = Paint().apply {
                        color = android.graphics.Color.rgb(230, 230, 230)
                        style = Paint.Style.FILL
                    }
                    val x = MARGIN_LEFT * 2f
                    canvas.drawRect(x, currentY, x + line.width * 2f, currentY + h, placeholderPaint)
                    val labelPaint = Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
                    canvas.drawText("[Image]", x + line.width.toFloat(), currentY + h / 2f + 8f, labelPaint)
                    currentY += h
                }
            }
        }

        // Add the last page
        pages.add(page)

        return pages.ifEmpty {
            listOf(createBlankPage())
        }
    }

    private fun createBlankPage(): Bitmap {
        val bmp = Bitmap.createBitmap(PAGE_WIDTH * 1, PAGE_HEIGHT * 1, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        return bmp
    }
}
