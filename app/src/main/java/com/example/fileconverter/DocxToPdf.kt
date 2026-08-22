package com.example.fileconverter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/** One styled piece of text inside a paragraph (a Word "run"). */
private data class Run(
    val text: String,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val sizeHalfPoints: Int,
)

/** One paragraph of the Word document. */
private data class Paragraph(
    val runs: List<Run>,
    val alignment: String,       // "left", "center", "right"
    val style: String,           // pStyle value, e.g. "Title", "Heading1"
    val pageBreakBefore: Boolean,
    val bulleted: Boolean,       // part of a (numbered/bulleted) list
)

/** A laid-out line of tokens with its total width, line height and horizontal offset. */
private data class Line(
    val tokens: List<Token>,
    val width: Float,
    val height: Float,
    val alignment: String,
    val xOffset: Float,
)

/** A word (or whitespace) plus the paint used to draw it. */
private data class Token(val text: String, val paint: Paint, val sizePt: Float)

private data class TableCell(val paragraphs: List<Paragraph>, val gridSpan: Int)
private data class DocTable(val columns: List<Float>, val rows: List<List<TableCell>>)

private data class ChartSeries(val name: String, val color: Int, val values: List<Double>)
private data class DocChart(
    val type: String,
    val title: String,
    val categories: List<String>,
    val series: List<ChartSeries>,
    val heightPt: Float,
)

/** A block of document content, in document order. */
private sealed class DocBlock
private class ParagraphBlock(val paragraph: Paragraph) : DocBlock()
private class TableBlock(val table: DocTable) : DocBlock()
private class ImageBlock(val imageRId: String) : DocBlock()
private class ChartBlock(val chart: DocChart) : DocBlock()

/** Everything parsed out of the .docx that we need to render. */
private data class ParsedDoc(
    val blocks: List<DocBlock>,
    val imageRels: Map<String, String>,  // relationship id -> media file path
    val chartRels: Map<String, String>,  // relationship id -> chart xml path
    val media: Map<String, ByteArray>,   // zip entry name -> file bytes
)

/**
 * Converts a .docx (Word 2007+) file into a PDF. The document XML is parsed into blocks —
 * paragraphs (with bold/italic/underline, font size, alignment, heading styles, page breaks
 * and list items), tables (rendered as a grid), embedded images and native OOXML charts
 * (rendered as bar charts) — which are then drawn onto A4 pages with word wrapping. Complex
 * layout (columns, text boxes) is flattened into flowing text.
 */
object DocxToPdf {

    private const val PAGE_W = 595 // A4 at 72 dpi, in points
    private const val PAGE_H = 842
    private const val MARGIN = 56f

    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        if (isLegacyDoc(context, uri)) return DocToPdf.convert(context, uri, displayName)
        val doc = parse(context, uri)
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

            for (block in doc.blocks) {
                when (block) {
                    is ParagraphBlock -> {
                        val para = block.paragraph
                        if (para.pageBreakBefore) newPage()
                        val tokens = buildTokens(para)
                        if (tokens.isEmpty()) {
                            // Empty paragraph renders as a blank line.
                            y += paraFontSizePt(para) * 1.4f
                            if (y > PAGE_H - MARGIN) newPage()
                            continue
                        }
                        val indent = if (para.bulleted) bulletIndent(para) else 0f
                        for (line in wrapTokens(tokens, para.alignment, indent, PAGE_W - MARGIN * 2)) {
                            if (y + line.height > PAGE_H - MARGIN) newPage()
                            val xStart = when (line.alignment) {
                                "center" -> (PAGE_W - line.width) / 2f
                                "right" -> PAGE_W - MARGIN - line.width
                                else -> MARGIN + line.xOffset
                            }
                            drawLine(page.canvas, line, y, xStart)
                            y += line.height * 1.3f
                        }
                        y += 6f // paragraph spacing
                    }

                    is ImageBlock -> {
                        val img = loadImage(block.imageRId, doc) ?: continue
                        if (y + img.h > PAGE_H - MARGIN) newPage()
                        page.canvas.drawBitmap(img.bmp, null, RectF(MARGIN, y, MARGIN + img.w, y + img.h), null)
                        img.bmp.recycle()
                        y += img.h + 12f
                    }

                    is TableBlock -> {
                        if (y + 40f > PAGE_H - MARGIN) newPage()
                        y = renderTable(page, block.table, y)
                        if (y > PAGE_H - MARGIN) newPage()
                    }

                    is ChartBlock -> {
                        if (y + 60f > PAGE_H - MARGIN) newPage()
                        y = renderChart(page, block.chart, y)
                        if (y > PAGE_H - MARGIN) newPage()
                    }
                }
            }
            document.finishPage(page)
            return save(context, document, displayName)
        } finally {
            document.close()
        }
    }

    // ---- Rendering ---------------------------------------------------------

    /** Default font size (half-points) for a paragraph with the given style. */
    private fun paraFontSizePt(para: Paragraph): Float {
        val runSize = para.runs.firstOrNull()?.sizeHalfPoints ?: 0
        return (if (runSize > 0) runSize else defaultSizeHalfPoints(para.style)) / 2f
    }

    private fun bulletIndent(para: Paragraph): Float {
        val paint = Paint().apply { typeface = Typeface.DEFAULT; textSize = paraFontSizePt(para) }
        return paint.measureText(BULLET)
    }

    private fun buildTokens(para: Paragraph): List<Token> {
        val tokens = mutableListOf<Token>()
        if (para.bulleted) {
            val sizePt = paraFontSizePt(para)
            val paint = Paint().apply { typeface = Typeface.DEFAULT; textSize = sizePt }
            tokens += Token(BULLET, paint, sizePt)
        }
        for (run in para.runs) {
            val sizePt = (if (run.sizeHalfPoints > 0) run.sizeHalfPoints else defaultSizeHalfPoints(para.style)) / 2f
            val style = when {
                run.bold && run.italic -> Typeface.BOLD_ITALIC
                run.bold -> Typeface.BOLD
                run.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            val paint = Paint().apply {
                typeface = Typeface.create(Typeface.DEFAULT, style)
                textSize = sizePt
                isUnderlineText = run.underline
            }
            // Split into words, keeping the whitespace between them so wrapping can drop it.
            run.text.split(Regex("(?<=\\s)|(?=\\s)")).forEach { part ->
                if (part.isNotEmpty()) tokens += Token(part, paint, sizePt)
            }
        }
        return tokens
    }

    private fun wrapTokens(tokens: List<Token>, alignment: String, hangingIndent: Float, width: Float): List<Line> {
        val lines = mutableListOf<Line>()
        var lineTokens = mutableListOf<Token>()
        var lineWidth = 0f
        var lineHeight = 0f
        for (token in tokens) {
            val w = token.paint.measureText(token.text)
            val isSpace = token.text.isBlank()
            val isFirstLine = lines.isEmpty() && lineTokens.isEmpty()
            val budget = if (isFirstLine) width else width - hangingIndent
            if (!isSpace && lineTokens.isNotEmpty() && lineWidth + w > budget) {
                lines += Line(lineTokens, lineWidth, lineHeight, alignment, if (lines.isEmpty()) 0f else hangingIndent)
                lineTokens = mutableListOf()
                lineWidth = 0f
                lineHeight = 0f
            }
            if (isSpace && lineTokens.isEmpty()) continue // drop leading space on a new line
            lineTokens += token
            lineWidth += w
            lineHeight = maxOf(lineHeight, token.sizePt)
        }
        if (lineTokens.isNotEmpty()) {
            lines += Line(lineTokens, lineWidth, lineHeight, alignment, if (lines.isEmpty()) 0f else hangingIndent)
        }
        return lines
    }

    private fun drawLine(canvas: Canvas, line: Line, baselineY: Float, xStart: Float) {
        var x = xStart
        for (token in line.tokens) {
            canvas.drawText(token.text, x, baselineY, token.paint)
            x += token.paint.measureText(token.text)
        }
    }

    private fun defaultSizeHalfPoints(style: String): Int = when (style) {
        "Title" -> 56
        "Heading1" -> 40
        "Heading2" -> 32
        "Heading3" -> 28
        else -> 24
    }

    private const val BULLET = "\u2022  "

    /** Decodes the paragraph's embedded image (if any), scaled to fit the page. */
    private class SizedBitmap(val bmp: Bitmap, val w: Int, val h: Int)

    private fun loadImage(imageRId: String, doc: ParsedDoc): SizedBitmap? {
        val mediaPath = doc.imageRels[imageRId] ?: return null
        val bytes = doc.media[mediaPath] ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val maxW = PAGE_W - 2 * MARGIN
        val maxH = 480f
        val scale = min(1f, min(maxW / bmp.width, maxH / bmp.height))
        return SizedBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt())
    }

    // ---- Table rendering ---------------------------------------------------

    private fun renderTable(page: PdfDocument.Page, table: DocTable, startY: Float): Float {
        val canvas = page.canvas
        val fullW = PAGE_W - 2 * MARGIN
        val total = table.columns.sum()
        val scale = if (total > fullW && total > 0) fullW / total else 1f
        val colW = table.columns.map { it * scale }.ifEmpty { listOf(fullW) }
        val xPos = ArrayList<Float>(colW.size + 1)
        var acc = MARGIN
        xPos += acc
        for (w in colW) {
            acc += w
            xPos += acc
        }
        val pad = 6f
        val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0xFF222222.toInt()
        }

        // Compute row heights by wrapping each cell's text into its column width.
        val rowH = mutableListOf<Float>()
        for (row in table.rows) {
            var h = 16f
            for ((ci, cell) in row.withIndex()) {
                if (ci + cell.gridSpan > colW.size) continue
                var w = 0f
                for (k in 0 until cell.gridSpan) w += colW[ci + k]
                w -= 2 * pad
                var ch = 10f
                for (para in cell.paragraphs) {
                    val tokens = buildTokens(para)
                    for (line in wrapTokens(tokens, "left", 0f, maxOf(w, 20f))) ch += line.height * 1.3f
                    ch += 4f
                }
                h = maxOf(h, ch)
            }
            rowH += h
        }

        var y = startY
        for ((ri, row) in table.rows.withIndex()) {
            val h = rowH[ri]
            for ((ci, cell) in row.withIndex()) {
                if (ci + cell.gridSpan > colW.size) continue
                val x0 = xPos[ci]
                val x1 = xPos[ci + cell.gridSpan]
                canvas.drawRect(x0, y, x1, y + h, border)
                var ty = y + pad + 9f
                val cellW = x1 - x0 - 2 * pad
                for (para in cell.paragraphs) {
                    val tokens = buildTokens(para)
                    for (line in wrapTokens(tokens, "left", 0f, maxOf(cellW, 20f))) {
                        drawLine(canvas, line, ty, x0 + pad)
                        ty += line.height * 1.3f
                    }
                    ty += 4f
                }
            }
            y += h
        }
        return y + 10f
    }

    // ---- Chart rendering ---------------------------------------------------

    private fun renderChart(page: PdfDocument.Page, chart: DocChart, startY: Float): Float {
        val canvas = page.canvas
        val x0 = MARGIN
        val x1 = PAGE_W - MARGIN
        var y = startY

        val titlePaint = Paint().apply { typeface = Typeface.DEFAULT_BOLD; textSize = 12f; color = 0xFF000000.toInt() }
        val legendPaint = Paint().apply { typeface = Typeface.DEFAULT; textSize = 9f; color = 0xFF000000.toInt() }
        val tickPaint = Paint().apply { typeface = Typeface.DEFAULT; textSize = 8f; color = 0xFF444444.toInt() }
        val gridPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = 0xFFCCCCCC.toInt() }
        val axisPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = 0xFF000000.toInt() }

        // Title — centered above the plot area.
        if (chart.title.isNotEmpty()) {
            canvas.drawText(chart.title, (x0 + x1) / 2f - titlePaint.measureText(chart.title) / 2f, y + 12f, titlePaint)
            y += 18f
        }

        // Legend — color swatch + series name, wrapping to a second row if it overflows.
        if (chart.series.isNotEmpty()) {
            var lx = x0
            var ly = y + 10f
            for (s in chart.series) {
                val label = s.name.ifEmpty { "Series" }
                val itemW = 12f + 5f + legendPaint.measureText(label) + 18f
                if (lx > x0 && lx + itemW > x1) {
                    lx = x0
                    ly += 15f
                }
                val swatch = Paint().apply { style = Paint.Style.FILL; color = s.color }
                canvas.drawRect(lx, ly - 8f, lx + 12f, ly + 2f, swatch)
                canvas.drawText(label, lx + 17f, ly, legendPaint)
                lx += itemW
            }
            y = ly + 10f
        }

        // Value axis — rounded "nice" maximum, gridlines and tick labels.
        val rawMax = chart.series.flatMap { it.values }.maxOrNull() ?: 0.0
        val axisMax = niceAxisMax(rawMax)
        val nCat = maxOf(chart.categories.size, 1)
        val tickW = 28f // space for y-axis labels on the left
        val plotX0 = x0 + tickW
        val plotW = x1 - plotX0
        val slot = plotW / nCat
        val barW = min(40f, slot / (chart.series.size + 1) * 0.7f)
        val plotH = maxOf(chart.heightPt - (y - startY) - 44f, 110f)
        val axisY = y + plotH

        val steps = 4
        for (i in 0..steps) {
            val v = axisMax * i / steps
            val gy = axisY - plotH * i / steps
            if (i > 0) canvas.drawLine(plotX0, gy, x1, gy, gridPaint)
            val label = formatNumber(v)
            canvas.drawText(label, plotX0 - 4f - tickPaint.measureText(label), gy + 3f, tickPaint)
        }

        // Bars (grouped by category), value marker centered above each bar.
        for ((si, s) in chart.series.withIndex()) {
            val bar = Paint().apply { style = Paint.Style.FILL; color = s.color }
            for ((ci, v) in s.values.withIndex()) {
                if (ci >= nCat) break
                val bh = (v / axisMax * plotH).toFloat()
                val bx = plotX0 + ci * slot + slot / 2f - barW * chart.series.size / 2f + si * barW
                canvas.drawRect(bx, axisY - bh, bx + barW, axisY, bar)
                val label = formatNumber(v)
                canvas.drawText(label, bx + barW / 2f - tickPaint.measureText(label) / 2f, axisY - bh - 3f, tickPaint)
            }
        }

        // Axes.
        canvas.drawLine(plotX0, axisY, x1, axisY, axisPaint)
        canvas.drawLine(plotX0, y, plotX0, axisY, axisPaint)

        // Category labels under the baseline.
        for ((ci, cat) in chart.categories.withIndex()) {
            val cx = plotX0 + ci * slot + slot / 2f
            canvas.drawText(cat, cx - tickPaint.measureText(cat) / 2f, axisY + 13f, tickPaint)
        }
        return axisY + 28f
    }

    /** Rounds an axis maximum up to a "nice" number (1 / 2 / 2.5 / 5 × 10^n). */
    private fun niceAxisMax(raw: Double): Double {
        if (raw <= 0) return 1.0
        val exp = floor(log10(raw))
        val base = raw / 10.0.pow(exp)
        val nice = when {
            base <= 1 -> 1.0
            base <= 2 -> 2.0
            base <= 2.5 -> 2.5
            base <= 5 -> 5.0
            else -> 10.0
        }
        return nice * 10.0.pow(exp)
    }

    private fun formatNumber(v: Double): String {
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        var s = String.format("%.2f", v)
        while (s.endsWith("0")) s = s.dropLast(1)
        if (s.endsWith(".")) s = s.dropLast(1)
        return s
    }

    // ---- Parsing -----------------------------------------------------------

    private fun parse(context: Context, uri: Uri): ParsedDoc {
        val entries = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries[entry.name] = zip.readBytes()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Could not open the Word file")
        val docXml = entries["word/document.xml"]
            ?: error("Not a valid Word file (missing word/document.xml)")
        val (imageRels, chartRels) = parseRels(entries["word/_rels/document.xml.rels"])
        val blocks = mutableListOf<DocBlock>()
        parseDocument(docXml, imageRels, chartRels, entries, blocks)
        return ParsedDoc(blocks, imageRels, chartRels, entries)
    }

    /** Maps relationship ids to media/chart file paths. */
    private fun parseRels(xml: ByteArray?): Pair<Map<String, String>, Map<String, String>> {
        val images = mutableMapOf<String, String>()
        val charts = mutableMapOf<String, String>()
        if (xml == null) return images to charts
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val type = parser.getAttributeValue(null, "Type") ?: ""
                val target = parser.getAttributeValue(null, "Target") ?: ""
                if (id != null) {
                    val path = if (target.startsWith("/")) target.removePrefix("/") else "word/$target"
                    when {
                        type.contains("image") -> images[id] = path
                        type.contains("chart") -> charts[id] = path
                    }
                }
            }
            event = parser.next()
        }
        return images to charts
    }

    /** Legacy .doc files are OLE compound documents (magic D0 CF 11 E0), not ZIPs. */
    private fun isLegacyDoc(context: Context, uri: Uri): Boolean {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val magic = ByteArray(4)
            var off = 0
            while (off < 4) {
                val n = input.read(magic, off, 4 - off)
                if (n < 0) break
                off += n
            }
            off == 4 && magic[0] == 0xD0.toByte() && magic[1] == 0xCF.toByte() &&
                magic[2] == 0x11.toByte() && magic[3] == 0xE0.toByte()
        } ?: false
    }

    // ---- Block walker ------------------------------------------------------

    private val TAG_RE = Regex("""<(/)?([A-Za-z0-9_]+):([A-Za-z0-9_]+)""")

    /** Finds the end of a non-nesting element (e.g. <w:p>...</w:p>). */
    private fun findEnd(xml: String, start: Int, tag: String): Int {
        val open = Regex("""<$tag(\s[^>]*)?>""").find(xml, start) ?: return xml.length
        val gt = xml.indexOf('>', open.range.last)
        if (gt >= 0 && xml[gt - 1] == '/') return gt + 1 // self-closing
        val close = xml.indexOf("</$tag>", open.range.last)
        return if (close < 0) xml.length else close + tag.length + 3
    }

    /** Finds the end of a nesting element (tables can contain tables). */
    private fun findNestedEnd(xml: String, start: Int, tag: String): Int {
        val open = Regex("""<$tag(\s[^>]*)?>""")
        val close = "</$tag>"
        var depth = 0
        var i = start
        while (i < xml.length) {
            val o = open.find(xml, i)
            val c = xml.indexOf(close, i)
            val oi = o?.range?.first ?: Int.MAX_VALUE
            if (o != null && oi < c) {
                depth++
                i = o.range.last + 1
            } else if (c >= 0) {
                depth--
                if (depth == 0) return c + close.length
                i = c + close.length
            } else {
                return xml.length
            }
        }
        return xml.length
    }

    /** Splits document.xml into blocks in document order and parses each. */
    private fun parseDocument(docXml: ByteArray, imageRels: Map<String, String>, chartRels: Map<String, String>, media: Map<String, ByteArray>, blocks: MutableList<DocBlock>) {
        val xml = String(docXml, Charsets.UTF_8)
        var pos = 0
        while (pos < xml.length) {
            val lt = xml.indexOf('<', pos)
            if (lt < 0) break
            if (xml.startsWith("<?", lt) || xml.startsWith("<!--", lt) || xml.startsWith("<!", lt)) {
                val gt = xml.indexOf('>', lt)
                pos = if (gt < 0) xml.length else gt + 1
                continue
            }
            val m = TAG_RE.find(xml, lt)
            if (m == null) {
                pos = lt + 1
                continue
            }
            val closing = m.groupValues[1].isNotEmpty()
            val ns = m.groupValues[2]
            val name = m.groupValues[3]
            if (!closing && ns == "w") {
                when (name) {
                    "p" -> {
                        val end = findEnd(xml, lt, "w:p")
                        splitParagraphWithDrawings(xml.substring(lt, end), chartRels, media, blocks)
                        pos = end
                        continue
                    }
                    "tbl" -> {
                        val end = findNestedEnd(xml, lt, "w:tbl")
                        blocks += parseTable(xml.substring(lt, end))
                        pos = end
                        continue
                    }
                    "drawing" -> {
                        val end = findEnd(xml, lt, "w:drawing")
                        blocks += parseDrawing(xml.substring(lt, end), chartRels, media)
                        pos = end
                        continue
                    }
                    "sectPr" -> {
                        val end = findEnd(xml, lt, "w:sectPr")
                        pos = end
                        continue
                    }
                }
            }
            val gt = xml.indexOf('>', lt)
            pos = if (gt < 0) xml.length else gt + 1
        }
    }

    /** A paragraph may contain inline drawings — split them out as their own blocks. */
    private fun splitParagraphWithDrawings(xml: String, chartRels: Map<String, String>, media: Map<String, ByteArray>, blocks: MutableList<DocBlock>) {
        val drawings = mutableListOf<Pair<Int, Int>>()
        var search = 0
        while (true) {
            val d = Regex("""<w:drawing(\s[^>]*)?>""").find(xml, search) ?: break
            val dEnd = findEnd(xml, d.range.first, "w:drawing")
            drawings += d.range.first to dEnd
            search = dEnd
        }
        if (drawings.isEmpty()) {
            blocks += ParagraphBlock(parseParagraph(xml))
            return
        }
        var textStart = 0
        for ((ds, de) in drawings) {
            addParagraphFragment(xml.substring(textStart, ds), blocks)
            blocks += parseDrawing(xml.substring(ds, de), chartRels, media)
            textStart = de
        }
        addParagraphFragment(xml.substring(textStart), blocks)
    }

    /** Makes a paragraph fragment well-formed XML so it can be re-wrapped in <w:p>. */
    private fun normalizeFragment(fragment: String): String {
        var f = fragment
        val gt = f.indexOf('>')
        if (f.startsWith("<w:p") && gt >= 0) f = f.substring(gt + 1)
        if (f.endsWith("</w:p>")) f = f.removeSuffix("</w:p>")
        val out = StringBuilder()
        val stack = mutableListOf<String>()
        val tagRe = Regex("""<(/?)([A-Za-z0-9_]+:[A-Za-z0-9_]+)(\s[^>]*?)?(/?)\s*>""")
        var i = 0
        while (i < f.length) {
            val lt = f.indexOf('<', i)
            if (lt < 0) {
                out.append(f.substring(i))
                break
            }
            out.append(f.substring(i, lt))
            val gt2 = f.indexOf('>', lt)
            if (gt2 < 0) {
                out.append(f.substring(lt))
                break
            }
            val tagText = f.substring(lt, gt2 + 1)
            val m = tagRe.find(tagText)
            if (m != null) {
                val closing = m.groupValues[1] == "/"
                val name = m.groupValues[2]
                val selfClose = m.groupValues[4] == "/"
                if (closing) {
                    if (stack.isNotEmpty() && stack.last() == name) {
                        stack.removeAt(stack.size - 1)
                        out.append(tagText)
                    }
                    // Stray close (a drawing cut off the element before it) — drop it.
                } else if (selfClose) {
                    out.append(tagText)
                } else {
                    stack += name
                    out.append(tagText)
                }
            } else {
                out.append(tagText)
            }
            i = gt2 + 1
        }
        for (name in stack.reversed()) out.append("</$name>")
        return out.toString()
    }

    private fun addParagraphFragment(fragment: String, blocks: MutableList<DocBlock>) {
        if (fragment.isBlank()) return
        val normalized = normalizeFragment(fragment)
        if (normalized.isBlank()) return
        blocks += ParagraphBlock(parseParagraph("<w:p>$normalized</w:p>"))
    }

    // ---- Paragraph parsing -------------------------------------------------

    private fun parseParagraph(xml: String): Paragraph {
        val parser = Xml.newPullParser()
        // Fragments of document.xml don't carry the xmlns declarations from the root element,
        // so KXmlParser would reject the "w:" prefix with namespace processing enabled
        // (Xml.newPullParser() enables it by default). Parse without namespaces and match
        // the fully-prefixed tag/attribute names instead.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml.toByteArray()), "UTF-8")

        val runs = mutableListOf<Run>()
        var alignment = "left"
        var style = ""
        var pageBreak = false
        var bulleted = false

        var inRun = false
        var runText = StringBuilder()
        var runBold = false
        var runItalic = false
        var runUnderline = false
        var runSize = 0

        var inRPr = false
        var inNumPr = false
        var inT = false
        var preserveSpace = false

        fun flushRun() {
            if (inRun) {
                runs += Run(runText.toString(), runBold, runItalic, runUnderline, runSize)
            }
            inRun = false
            runText = StringBuilder()
            runBold = false
            runItalic = false
            runUnderline = false
            runSize = 0
        }

        fun attr(parser: XmlPullParser, name: String): String? {
            for (i in 0 until parser.attributeCount) {
                // Without namespace processing, prefixed attributes come back as "w:val" etc.
                if (parser.getAttributeName(i).substringAfterLast(':') == name) return parser.getAttributeValue(i)
            }
            return null
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "w:pPr" -> Unit
                    "w:pStyle" -> style = attr(parser, "val") ?: ""
                    "w:jc" -> alignment = attr(parser, "val") ?: "left"
                    "w:numPr" -> inNumPr = true
                    "w:numId" -> if (inNumPr) bulleted = true
                    "w:r" -> {
                        flushRun()
                        inRun = true
                    }
                    "w:rPr" -> inRPr = true
                    "w:b" -> if (inRPr) runBold = attr(parser, "val")?.let { it != "0" && it != "false" } ?: true
                    "w:i" -> if (inRPr) runItalic = attr(parser, "val")?.let { it != "0" && it != "false" } ?: true
                    "w:u" -> if (inRPr) runUnderline = attr(parser, "val")?.let { it != "0" && it != "false" } ?: true
                    "w:sz" -> if (inRPr) runSize = attr(parser, "val")?.toIntOrNull() ?: 0
                    "w:t" -> {
                        inT = true
                        preserveSpace = attr(parser, "space") == "preserve"
                    }
                    "w:tab" -> runText.append('\t')
                    "w:br" -> {
                        if (attr(parser, "type") == "page") pageBreak = true
                        else runText.append('\n')
                    }
                    "w:lastRenderedPageBreak" -> Unit
                }
                XmlPullParser.TEXT -> if (inT) runText.append(if (preserveSpace) parser.text else parser.text.trim())
                XmlPullParser.END_TAG -> when (name) {
                    "w:t" -> inT = false
                    "w:rPr" -> inRPr = false
                    "w:numPr" -> inNumPr = false
                    "w:r" -> flushRun()
                }
            }
            event = parser.next()
        }
        flushRun()
        return Paragraph(runs, alignment, style, pageBreak, bulleted)
    }

    // ---- Table parsing -----------------------------------------------------

    private fun parseTable(xml: String): TableBlock {
        val columns = Regex("""<w:gridCol\s+w:w="(\d+)"""").findAll(xml)
            .map { it.groupValues[1].toFloat() / 20f } // twips -> points
            .toList()
        val rows = mutableListOf<List<TableCell>>()
        var pos = 0
        while (true) {
            val tr = Regex("""<w:tr(\s[^>]*)?>""").find(xml, pos) ?: break
            val trEnd = findEnd(xml, tr.range.first, "w:tr")
            val trXml = xml.substring(tr.range.first, trEnd)
            val cells = mutableListOf<TableCell>()
            var cpos = 0
            while (true) {
                val tc = Regex("""<w:tc(\s[^>]*)?>""").find(trXml, cpos) ?: break
                val tcEnd = findEnd(trXml, tc.range.first, "w:tc")
                val tcXml = trXml.substring(tc.range.first, tcEnd)
                val gridSpan = Regex("""<w:gridSpan\s+w:val="(\d+)"""").find(tcXml)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val paras = mutableListOf<Paragraph>()
                var ppos = 0
                while (true) {
                    val p = Regex("""<w:p(\s[^>]*)?>""").find(tcXml, ppos) ?: break
                    val pEnd = findEnd(tcXml, p.range.first, "w:p")
                    paras += parseParagraph(tcXml.substring(p.range.first, pEnd))
                    ppos = pEnd
                }
                cells += TableCell(paras, gridSpan)
                cpos = tcEnd
            }
            rows += cells
            pos = trEnd
        }
        return TableBlock(DocTable(columns, rows))
    }

    // ---- Drawing parsing ---------------------------------------------------

    private fun parseDrawing(xml: String, chartRels: Map<String, String>, media: Map<String, ByteArray>): DocBlock {
        // Native chart? <c:chart r:id="rIdN"/>
        val chartRid = Regex("""r:id="([^"]+)"""").find(xml)?.groupValues?.get(1)
        if (chartRid != null && chartRels.containsKey(chartRid)) {
            val chartPath = chartRels[chartRid]!!
            val chartXml = media[chartPath]?.let { String(it, Charsets.UTF_8) }
            if (chartXml != null) {
                val extent = Regex("""<wp:extent\s+cx="(\d+)"\s+cy="(\d+)"""").find(xml)
                val heightPt = extent?.let { it.groupValues[2].toFloat() / 914400f * 72f } ?: 240f
                return ChartBlock(parseChart(chartXml, min(maxOf(heightPt, 120f), 360f)))
            }
        }
        // Embedded image? <a:blip r:embed="rIdN"/>
        val blipRid = Regex("""r:embed="([^"]+)"""").find(xml)?.groupValues?.get(1)
        if (blipRid != null) return ImageBlock(blipRid)
        return ParagraphBlock(Paragraph(emptyList(), "left", "", false, false))
    }

    // ---- Chart parsing -----------------------------------------------------

    private fun parseChart(xml: String, heightPt: Float): DocChart {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(xml.toByteArray()), "UTF-8")

        var type = "bar"
        var title = ""
        val categories = mutableListOf<String>()
        val series = mutableListOf<ChartSeries>()
        var inSer = false
        var serName = ""
        var serColor = DEFAULT_CHART_COLORS[0]
        var serCats = mutableListOf<String>()
        var serVals = mutableListOf<Double>()
        var inTx = false
        var inCat = false
        var inVal = false
        var inSpPr = false
        var inTitle = false
        var inV = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "ser" -> {
                        inSer = true
                        serName = ""
                        serColor = DEFAULT_CHART_COLORS[series.size % DEFAULT_CHART_COLORS.size]
                        serCats = mutableListOf()
                        serVals = mutableListOf()
                    }
                    name == "tx" -> inTx = true
                    name == "cat" -> inCat = true
                    name == "val" -> inVal = true
                    name == "spPr" -> inSpPr = true
                    name == "srgbClr" -> if (inSer && inSpPr) {
                        parser.getAttributeValue(null, "val")?.toIntOrNull(16)?.let { serColor = 0xFF000000.toInt() or it }
                    }
                    name == "v" -> inV = true
                    name == "title" -> inTitle = true
                    name == "t" -> Unit
                    name.endsWith("Chart") && name != "chart" -> type = name.removeSuffix("Chart")
                }
                XmlPullParser.TEXT -> if (inSer && inV) {
                    val text = parser.text?.trim().orEmpty()
                    when {
                        inTx -> if (serName.isEmpty()) serName = text
                        inCat -> if (text.isNotEmpty()) serCats += text
                        inVal -> text.toDoubleOrNull()?.let { serVals += it }
                    }
                } else if (inTitle && inV) {
                    if (title.isEmpty()) title = parser.text?.trim().orEmpty()
                }
                XmlPullParser.END_TAG -> when (name) {
                    "v" -> inV = false
                    "tx" -> inTx = false
                    "cat" -> inCat = false
                    "val" -> inVal = false
                    "spPr" -> inSpPr = false
                    "title" -> inTitle = false
                    "ser" -> {
                        if (inSer) {
                            series += ChartSeries(serName, serColor, serVals)
                            if (serCats.isNotEmpty() && categories.isEmpty()) categories += serCats
                        }
                        inSer = false
                    }
                }
            }
            event = parser.next()
        }
        return DocChart(type, title, categories, series, heightPt)
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

    private val DEFAULT_CHART_COLORS = listOf(
        0xFF004586.toInt(), 0xFFFF420E.toInt(), 0xFFFFD320.toInt(),
        0xFF007777.toInt(), 0xFF7B0080.toInt(), 0xFF00A1F1.toInt(),
    )
}
