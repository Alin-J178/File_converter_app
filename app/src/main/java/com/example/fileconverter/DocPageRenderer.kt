package com.example.fileconverter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.min

/**
 * Renders a [WordDocument] into page-sized Bitmaps (A4-like).
 * Infers formatting (bold, headings) from text patterns when the parser
 * doesn't carry formatting info (legacy .doc OLE2 extraction).
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

        return paginateToBitmaps(lines)
    }

    // ── Layout lines ──────────────────────────────────────────

    private sealed class LayoutLine {
        data class Text(
            val text: String, val fontSize: Float,
            val isBold: Boolean, val isItalic: Boolean,
            val color: Int, val indent: Int = 0,
            val alignment: Int = 0, // 0=left, 1=center, 2=right
        ) : LayoutLine()
        data class Spacing(val height: Float) : LayoutLine()
        data class TableRow(
            val cells: List<String>, val isHeader: Boolean,
            val colCount: Int,
        ) : LayoutLine()
        data class ImageLine(val width: Int, val height: Int) : LayoutLine()
        object PageBreak : LayoutLine()
    }

    // ── Block → Lines ─────────────────────────────────────────

    private fun layoutParagraph(block: WordBlock.Paragraph, lines: MutableList<LayoutLine>) {
        if (block.runs.isEmpty() || block.runs.all { it.text.isBlank() }) {
            lines.add(LayoutLine.Spacing(10f))
            return
        }
        val combined = block.runs.joinToString("") { it.text }
        val isBold = block.runs.any { it.bold }
        val isItalic = block.runs.any { it.italic }
        val fontSize = block.runs.firstOrNull { it.fontSize > 0f }?.fontSize ?: 13f
        val color = block.runs.firstOrNull { it.color != 0 }?.color ?: android.graphics.Color.BLACK

        // Infer formatting from text patterns when parser doesn't carry formatting
        val inferred = inferFormatting(combined)
        val actualBold = isBold || inferred.bold
        val actualFontSize = if (inferred.isHeading) inferred.headingSize else fontSize
        val indent = if (block.indent.firstLine > 0f || block.indent.left > 0f) 1 else 0

        wrapText(combined, actualFontSize, actualBold, isItalic, color, indent, lines)
        lines.add(LayoutLine.Spacing(8f))
    }

    private fun layoutHeading(block: WordBlock.Heading, lines: MutableList<LayoutLine>) {
        lines.add(LayoutLine.Spacing(16f))
        val combined = block.runs.joinToString("") { it.text }
        val fontSize = headingFontSize(block.level)
        wrapText(combined, fontSize, true, false, android.graphics.Color.BLACK, 0, lines)
        lines.add(LayoutLine.Spacing(12f))
    }

    private fun layoutListItem(block: WordBlock.ListItem, lines: MutableList<LayoutLine>) {
        val prefix = if (block.bulleted) "\u2022 " else "${block.level + 1}. "
        val combined = prefix + block.runs.joinToString("") { it.text }
        val isBold = block.runs.any { it.bold }
        val fontSize = block.runs.firstOrNull { it.fontSize > 0f }?.fontSize ?: 13f
        val color = block.runs.firstOrNull { it.color != 0 }?.color ?: android.graphics.Color.BLACK
        wrapText(combined, fontSize, isBold, false, color, block.level, lines)
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

    // ── Formatting inference ───────────────────────────────────

    private data class InferredFormat(
        val bold: Boolean, val isHeading: Boolean, val headingSize: Float,
    )

    private fun inferFormatting(text: String): InferredFormat {
        val trimmed = text.trim()
        // Title patterns (ALL CAPS or known title words)
        val isAllCaps = trimmed.length in 3..80 && trimmed == trimmed.uppercase() && trimmed.any { it.isLetter() }
        val titleKeywords = listOf("INVENTORI", "PERSONALITI", "SIDEK", "IPS", "BORANG", "ARAHAN", "SKOR", "PROFIL")
        val hasTitleKeyword = titleKeywords.any { trimmed.contains(it, ignoreCase = true) }
        val isShort = trimmed.length < 40
        val isTitle = isAllCaps || (hasTitleKeyword && isShort && trimmed.count { it.isLetter() } > 5)

        // Numbered item (e.g., "1. Agresif")
        val numberedItem = Regex("^\\d+\\.\\s+.+").matches(trimmed)
        // Numbered list item with text (e.g., "1. Agresif ...Trait personality...")
        val isNumberedBlock = numberedItem && !trimmed.contains(".")

        return when {
            isTitle && trimmed.length < 30 -> InferredFormat(true, true, 24f)
            isTitle -> InferredFormat(true, true, 20f)
            numberedItem -> InferredFormat(true, false, 14f)
            else -> InferredFormat(false, false, 13f)
        }
    }

    // ── Text wrapping ──────────────────────────────────────────

    private fun wrapText(
        text: String, fontSize: Float, isBold: Boolean, isItalic: Boolean,
        color: Int, indent: Int, lines: MutableList<LayoutLine>,
    ) {
        val paint = Paint().apply {
            textSize = fontSize
            isFakeBoldText = isBold
            isAntiAlias = true
            typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val maxWidth = USABLE_WIDTH - indent * 50
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var currentLine = StringBuilder()
        for (word in words) {
            val test = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(test) > maxWidth && currentLine.isNotEmpty()) {
                lines.add(LayoutLine.Text(currentLine.toString(), fontSize, isBold, isItalic, color, indent))
                currentLine = StringBuilder(word)
            } else {
                if (currentLine.isEmpty()) currentLine = StringBuilder(word)
                else currentLine.append(" ").append(word)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(LayoutLine.Text(currentLine.toString(), fontSize, isBold, isItalic, color, indent))
        }
        if (text.isBlank()) {
            lines.add(LayoutLine.Spacing(fontSize * LINE_SPACING))
        }
    }

    // ── Pagination + Bitmap rendering ──────────────────────────

    private fun paginateToBitmaps(lines: List<LayoutLine>): List<Bitmap> {
        val pages = mutableListOf<Bitmap>()
        var currentY = 0f
        var page = createBlankPage()
        var canvas = Canvas(page)

        fun newPage(): Canvas {
            pages.add(page)
            page = createBlankPage()
            currentY = MARGIN_TOP.toFloat()
            return Canvas(page)
        }
        currentY = MARGIN_TOP.toFloat()

        for (line in lines) {
            when (line) {
                is LayoutLine.PageBreak -> { canvas = newPage(); continue }
                is LayoutLine.Spacing -> { currentY += line.height }
                is LayoutLine.Text -> {
                    val lineHeight = line.fontSize * LINE_SPACING
                    if (currentY + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        canvas = newPage()
                    }
                    val paint = Paint().apply {
                        textSize = line.fontSize
                        isFakeBoldText = line.isBold
                        color = line.color
                        isAntiAlias = true
                        typeface = if (line.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    val x = MARGIN_LEFT + line.indent * 50f
                    // Handle alignment
                    val drawX = when (line.alignment) {
                        1 -> MARGIN_LEFT + (USABLE_WIDTH - paint.measureText(line.text)) / 2f
                        2 -> MARGIN_LEFT + USABLE_WIDTH - paint.measureText(line.text)
                        else -> x
                    }
                    canvas.drawText(line.text, drawX, currentY + line.fontSize, paint)
                    currentY += lineHeight
                }
                is LayoutLine.TableRow -> {
                    val fontSize = if (line.isHeader) 13f else 12f
                    val textPaint = Paint().apply {
                        textSize = fontSize
                        isFakeBoldText = line.isHeader
                        color = android.graphics.Color.BLACK
                        isAntiAlias = true
                        typeface = if (line.isHeader) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    val lineHeight = fontSize * 1.5f
                    val padding = 8f

                    // Column widths: weighted (Bil. narrow, Item wide, Ya/Tidak medium)
                    val colWidths = when (line.colCount) {
                        4 -> floatArrayOf(56f, USABLE_WIDTH - 56f - 120f - 120f, 120f, 120f)
                        else -> FloatArray(line.colCount) { USABLE_WIDTH / line.colCount.toFloat() }
                    }

                    // Calculate row height
                    var maxLines = 1
                    for ((ci2, cellText) in line.cells.withIndex()) {
                        val maxTextWidth = colWidths[ci2] - padding * 2
                        val words = cellText.split(" ")
                        var lineCount = 1
                        var curWidth = 0f
                        for (word in words) {
                            val ww = textPaint.measureText(word)
                            if (curWidth + ww > maxTextWidth && curWidth > 0) {
                                lineCount++
                                curWidth = ww
                            } else {
                                curWidth += ww + textPaint.measureText(" ")
                            }
                        }
                        maxLines = maxOf(maxLines, lineCount)
                    }
                    val cellHeight = (lineHeight * maxLines + padding * 2).coerceAtLeast(32f)

                    if (currentY + cellHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        canvas = newPage()
                    }

                    // Draw cells
                    var cellX = MARGIN_LEFT.toFloat()
                    for ((ci, cellText) in line.cells.withIndex()) {
                        val cw = colWidths[ci]
                        // Background
                        val bgPaint = Paint().apply {
                            color = if (line.isHeader) android.graphics.Color.rgb(235, 235, 235)
                            else if (ci % 2 == 1) android.graphics.Color.rgb(248, 248, 248)
                            else android.graphics.Color.TRANSPARENT
                            style = Paint.Style.FILL
                        }
                        canvas.drawRect(cellX, currentY, cellX + cw, currentY + cellHeight, bgPaint)
                        // Border
                        val borderPaint = Paint().apply {
                            color = android.graphics.Color.rgb(180, 180, 180)
                            style = Paint.Style.STROKE
                            strokeWidth = 1f
                        }
                        canvas.drawRect(cellX, currentY, cellX + cw, currentY + cellHeight, borderPaint)
                        // Text
                        val maxTextWidth = cw - padding * 2
                        val words = cellText.split(" ")
                        var textY = currentY + padding + fontSize
                        var lineStart = StringBuilder()
                        var lineWidth = 0f
                        for (word in words) {
                            val ww = textPaint.measureText(word)
                            val testW = if (lineStart.isEmpty()) ww else lineWidth + textPaint.measureText(" ") + ww
                            if (testW > maxTextWidth && lineStart.isNotEmpty()) {
                                canvas.drawText(lineStart.toString(), cellX + padding, textY, textPaint)
                                textY += lineHeight
                                lineStart = StringBuilder(word)
                                lineWidth = ww
                            } else {
                                if (lineStart.isEmpty()) lineStart.append(word) else lineStart.append(" ").append(word)
                                lineWidth = testW
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
                    if (currentY + h > PAGE_HEIGHT - MARGIN_BOTTOM) canvas = newPage()
                    val placeholderPaint = Paint().apply {
                        color = android.graphics.Color.rgb(230, 230, 230)
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(MARGIN_LEFT.toFloat(), currentY, MARGIN_LEFT + line.width.toFloat(), currentY + h, placeholderPaint)
                    val labelPaint = Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
                    canvas.drawText("[Image]", MARGIN_LEFT + line.width / 2f, currentY + h / 2f + 8f, labelPaint)
                    currentY += h
                }
            }
        }

        pages.add(page)
        return pages.ifEmpty { listOf(createBlankPage()) }
    }

    private fun createBlankPage(): Bitmap {
        val bmp = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        return bmp
    }
}
