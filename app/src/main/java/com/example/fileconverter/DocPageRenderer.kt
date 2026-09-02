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
        val pages = paginateToBitmaps(lines)

        // Debug: write page info to file
        try {
            val f = java.io.File("/sdcard/Documents/doc_debug.txt")
            f.appendText(buildString {
                appendLine("\n=== PAGE RENDERER ===")
                appendLine("Layout lines: ${lines.size}")
                appendLine("Pages rendered: ${pages.size}")
                appendLine("Page size: ${PAGE_WIDTH}x${PAGE_HEIGHT}")
                appendLine("Block types: ${doc.blocks.groupBy { it::class.simpleName }.mapValues { it.value.size }}")
                appendLine("--- First 15 lines ---")
                lines.take(15).forEachIndexed { idx, l ->
                    when (l) {
                        is LayoutLine.Text -> appendLine("[$idx] TEXT(\"${l.text.take(50)}\" fs=${l.fontSize} bold=${l.isBold})")
                        is LayoutLine.TableRow -> appendLine("[$idx] TABLEROW ${l.colCount}c hdr=${l.isHeader} ${l.cells.joinToString(" | ") { it.take(20) }}")
                        is LayoutLine.Spacing -> appendLine("[$idx] SPACING ${l.height}")
                        is LayoutLine.PageBreak -> appendLine("[$idx] PAGEBREAK")
                        else -> appendLine("[$idx] ${l::class.simpleName}")
                    }
                }
                appendLine("--- Last 15 lines ---")
                lines.takeLast(15).forEachIndexed { idx, l ->
                    val realIdx = lines.size - 15 + idx
                    when (l) {
                        is LayoutLine.Text -> appendLine("[$realIdx] TEXT(\"${l.text.take(50)}\" fs=${l.fontSize} bold=${l.isBold})")
                        is LayoutLine.TableRow -> appendLine("[$realIdx] TABLEROW ${l.colCount}c hdr=${l.isHeader} ${l.cells.joinToString(" | ") { it.take(20) }}")
                        is LayoutLine.Spacing -> appendLine("[$realIdx] SPACING ${l.height}")
                        is LayoutLine.PageBreak -> appendLine("[$realIdx] PAGEBREAK")
                        else -> appendLine("[$realIdx] ${l::class.simpleName}")
                    }
                }
            })
        } catch (_: Exception) {}

        return pages
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
            this.textSize = fontSize
            this.isFakeBoldText = isBold
            this.isAntiAlias = true
            typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val maxWidth = USABLE_WIDTH - indent * 40
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
                        textSize = line.fontSize
                        isFakeBoldText = line.isBold
                        color = line.color
                        isAntiAlias = true
                        typeface = if (line.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    val x = MARGIN_LEFT + line.indent * 40.toFloat()
                    canvas.drawText(line.text, x, currentY + line.fontSize, paint)
                    currentY += lineHeight
                }
                is LayoutLine.TableRow -> {
                    val fontSize = if (line.isHeader) 12f else 11f
                    val textPaint = Paint().apply {
                        textSize = fontSize
                        isFakeBoldText = line.isHeader
                        color = android.graphics.Color.BLACK
                        isAntiAlias = true
                        typeface = if (line.isHeader) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    val lineHeight = fontSize * 1.4f
                    val padding = 6f

                    // Calculate column widths: first col (Bil.) narrow, second (Item) wide, rest medium
                    val colWidths = FloatArray(line.colCount)
                    val totalWeight = when (line.colCount) {
                        4 -> floatArrayOf(1f, 5f, 1.5f, 1.5f)  // Bil, Item, Ya, Tidak
                        else -> FloatArray(line.colCount) { 1f }
                    }
                    var totalW = 0f
                    for (w in totalWeight) totalW += w
                    for (ci in colWidths.indices) {
                        colWidths[ci] = USABLE_WIDTH * totalWeight[ci] / totalW
                    }

                    // Calculate row height based on longest cell text
                    var maxLines = 1
                    for ((ci, cellText) in line.cells.withIndex()) {
                        val cw = colWidths[ci]
                        val maxTextWidth = cw - padding * 2
                        val words = cellText.split(" ")
                        var lineCount = 1
                        var currentLineWidth = 0f
                        for (word in words) {
                            val wordWidth = textPaint.measureText(word)
                            if (currentLineWidth + wordWidth > maxTextWidth && currentLineWidth > 0) {
                                lineCount++
                                currentLineWidth = wordWidth
                            } else {
                                currentLineWidth += wordWidth + textPaint.measureText(" ")
                            }
                        }
                        maxLines = maxOf(maxLines, lineCount)
                    }
                    val cellHeight = (lineHeight * maxLines + padding * 2).coerceAtLeast(28f)

                    if (currentY + cellHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        canvas = startNewPage()
                    }

                    val bgPaint = Paint().apply {
                        color = if (line.isHeader) android.graphics.Color.rgb(240, 240, 240) else android.graphics.Color.TRANSPARENT
                        style = Paint.Style.FILL
                    }
                    val borderPaint = Paint().apply {
                        color = android.graphics.Color.rgb(180, 180, 180)
                        style = Paint.Style.STROKE
                        strokeWidth = 0.5f
                    }

                    var cellX = MARGIN_LEFT.toFloat()
                    for ((ci, cellText) in line.cells.withIndex()) {
                        val cw = colWidths[ci]
                        val cellTop = currentY
                        // Background
                        canvas.drawRect(cellX, cellTop, cellX + cw, cellTop + cellHeight, bgPaint)
                        // Border
                        canvas.drawRect(cellX, cellTop, cellX + cw, cellTop + cellHeight, borderPaint)
                        // Draw wrapped text
                        val maxTextWidth = cw - padding * 2
                        val words = cellText.split(" ")
                        var textY = cellTop + padding + fontSize
                        var lineStart = StringBuilder()
                        var lineWidth = 0f
                        for (word in words) {
                            val wordWidth = textPaint.measureText(word)
                            val testWidth = if (lineStart.isEmpty()) wordWidth else lineWidth + textPaint.measureText(" ") + wordWidth
                            if (testWidth > maxTextWidth && lineStart.isNotEmpty()) {
                                canvas.drawText(lineStart.toString(), cellX + padding, textY, textPaint)
                                textY += lineHeight
                                lineStart = StringBuilder(word)
                                lineWidth = wordWidth
                            } else {
                                if (lineStart.isEmpty()) lineStart.append(word) else lineStart.append(" ").append(word)
                                lineWidth = testWidth
                            }
                        }
                        if (lineStart.isNotEmpty()) {
                            canvas.drawText(lineStart.toString(), cellX + padding, textY, textPaint)
                        }
                        cellX += cw
                    }
                    currentY += cellHeight
                }
                is LayoutLine.ImageLine -> {
                    val h = line.height.toFloat()
                    if (currentY + h > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        canvas = startNewPage()
                    }
                    val placeholderPaint = Paint().apply {
                        color = android.graphics.Color.rgb(230, 230, 230)
                        style = Paint.Style.FILL
                    }
                    val x = MARGIN_LEFT.toFloat()
                    canvas.drawRect(x, currentY, x + line.width.toFloat(), currentY + h, placeholderPaint)
                    val labelPaint = Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
                    canvas.drawText("[Image]", x + line.width / 2f, currentY + h / 2f + 8f, labelPaint)
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
