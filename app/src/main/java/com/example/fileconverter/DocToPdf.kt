package com.example.fileconverter

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Converts a legacy Word 97-2003 .doc file into a PDF.
 * Uses [DocTextExtractor] for shared OLE2 parsing and table detection.
 */
object DocToPdf {

    private const val PAGE_W = 595 // A4 at 72 dpi, in points
    private const val PAGE_H = 842
    private const val MARGIN = 56f
    private const val FONT_SIZE = 12f

    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open the Word file")
        val docText = DocTextExtractor.extract(bytes)
        val blocks = DocTextExtractor.buildBlocks(docText)
        val document = PdfDocument()
        try {
            var pageIndex = 0
            var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex).create())
            var y = MARGIN

            fun newPage() {
                document.finishPage(page)
                pageIndex++
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex).create())
                y = MARGIN
            }

            for (block in blocks) {
                when (block) {
                    is DocTextExtractor.DocBlock.TextBlock -> {
                        if (block.pageBreakBefore) newPage()
                        val tokens = buildTokens(block.text)
                        if (tokens.isEmpty()) {
                            y += FONT_SIZE * 1.4f
                            if (y > PAGE_H - MARGIN) newPage()
                            continue
                        }
                        for (line in wrapTokens(tokens)) {
                            if (y + line.height > PAGE_H - MARGIN) newPage()
                            drawLine(page.canvas, line, y)
                            y += line.height * 1.3f
                        }
                        y += 6f
                    }
                    is DocTextExtractor.DocBlock.TableBlock -> {
                        val estimatedH = estimateTableHeight(block.rows)
                        if (y + estimatedH > PAGE_H - MARGIN) newPage()
                        y = renderTable(page.canvas, block.rows, y)
                        y += 8f
                    }
                }
            }
            document.finishPage(page)
            return save(context, document, displayName)
        } finally {
            document.close()
        }
    }

    // ---- Table rendering ----------------------------------------------------

    private const val CELL_PAD = 4f

    private fun estimateTableHeight(rows: List<List<String>>): Float {
        val colCount = rows.maxOfOrNull { it.size } ?: 0
        if (colCount == 0) return 0f
        val totalW = PAGE_W - 2 * MARGIN
        val colW = totalW / colCount
        var totalH = 0f
        for (row in rows) {
            var rowH = FONT_SIZE + 2 * CELL_PAD
            for (cell in row) {
                val tokens = buildTokens(cell)
                val lines = wrapTokens(tokens, colW - 2 * CELL_PAD)
                val cellH = maxOf(lines.size * (FONT_SIZE * 1.3f) + 2 * CELL_PAD, FONT_SIZE + 2 * CELL_PAD)
                rowH = maxOf(rowH, cellH)
            }
            totalH += rowH
        }
        return totalH + 2f
    }

    private fun renderTable(canvas: Canvas, rows: List<List<String>>, startY: Float): Float {
        if (rows.isEmpty()) return startY
        val colCount = rows.maxOf { it.size }
        if (colCount == 0) return startY

        val totalW = PAGE_W - 2 * MARGIN
        val colW = totalW / colCount

        val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.75f
            color = 0xFF333333.toInt()
        }
        val headerBgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = 0xFFF0F0F0.toInt()
        }

        val rowHeights = mutableListOf<Float>()
        for (row in rows) {
            var maxRowH = FONT_SIZE + 2 * CELL_PAD
            for (cell in row) {
                val tokens = buildTokens(cell)
                val lines = wrapTokens(tokens, colW - 2 * CELL_PAD)
                val cellH = maxOf(lines.size * (FONT_SIZE * 1.3f) + 2 * CELL_PAD, FONT_SIZE + 2 * CELL_PAD)
                maxRowH = maxOf(maxRowH, cellH)
            }
            rowHeights.add(maxRowH)
        }

        var y = startY
        for ((ri, row) in rows.withIndex()) {
            val h = rowHeights[ri]
            if (ri == 0) canvas.drawRect(MARGIN, y, MARGIN + totalW, y + h, headerBgPaint)

            for (ci in 0 until colCount) {
                val x0 = MARGIN + ci * colW
                val x1 = x0 + colW
                val cellText = if (ci < row.size) row[ci] else ""
                canvas.drawRect(x0, y, x1, y + h, borderPaint)

                val fontPaint = Paint().apply {
                    typeface = if (ri == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    textSize = FONT_SIZE
                    color = 0xFF111111.toInt()
                }
                val tokens = buildTokens(cellText).map { Token(it.text, fontPaint) }
                val textLines = wrapTokens(tokens, colW - 2 * CELL_PAD)
                var ty = y + CELL_PAD + FONT_SIZE
                for (line in textLines) {
                    if (ty - FONT_SIZE * 0.3f > y + h - CELL_PAD) break
                    var tx = x0 + CELL_PAD
                    for (token in line.tokens) {
                        canvas.drawText(token.text, tx, ty, token.paint)
                        tx += token.paint.measureText(token.text)
                    }
                    ty += FONT_SIZE * 1.3f
                }
            }
            y += h
        }
        return y
    }

    // ---- Token/Line rendering -----------------------------------------------

    private data class Token(val text: String, val paint: Paint)
    private data class Line(val tokens: List<Token>, val width: Float, val height: Float)

    private fun buildTokens(text: String): List<Token> {
        val paint = Paint().apply {
            typeface = Typeface.DEFAULT
            textSize = FONT_SIZE
        }
        return text.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }.map { Token(it, paint) }
    }

    private fun wrapTokens(tokens: List<Token>, maxWidth: Float = PAGE_W - MARGIN * 2): List<Line> {
        val lines = mutableListOf<Line>()
        var lineTokens = mutableListOf<Token>()
        var lineWidth = 0f
        var lineHeight = 0f
        for (token in tokens) {
            val w = token.paint.measureText(token.text)
            val isSpace = token.text.isBlank()
            if (!isSpace && lineTokens.isNotEmpty() && lineWidth + w > maxWidth) {
                lines += Line(lineTokens, lineWidth, lineHeight)
                lineTokens = mutableListOf()
                lineWidth = 0f
                lineHeight = 0f
            }
            if (isSpace && lineTokens.isEmpty()) continue
            lineTokens += token
            lineWidth += w
            lineHeight = maxOf(lineHeight, FONT_SIZE)
        }
        if (lineTokens.isNotEmpty()) lines += Line(lineTokens, lineWidth, lineHeight)
        return lines
    }

    private fun drawLine(canvas: Canvas, line: Line, baselineY: Float) {
        var x = MARGIN
        for (token in line.tokens) {
            canvas.drawText(token.text, x, baselineY, token.paint)
            x += token.paint.measureText(token.text)
        }
    }

    // ---- Saving ------------------------------------------------------------

    private fun save(context: Context, document: PdfDocument, displayName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileConverter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
                ?: error("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { document.writeTo(it) }
                ?: error("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            val file = File(dir, displayName)
            FileOutputStream(file).use { document.writeTo(it) }
            Uri.fromFile(file)
        }
    }
}
