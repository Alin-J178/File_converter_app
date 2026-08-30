package com.example.fileconverter

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Parses .docx files into [WordDocument].
 * Extracted from DocxToPdf — same parsing logic, shared output.
 */
object DocxParser {

    private val TAG_RE = Regex("""<(/)?([A-Za-z0-9_]+):([A-Za-z0-9_]+)""")

    fun parse(context: Context, uri: Uri): WordDocument {
        val entries = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) { entries[entry.name] = zip.readBytes(); entry = zip.nextEntry }
            }
        } ?: return WordDocument(emptyList())
        val docXml = entries["word/document.xml"] ?: return WordDocument(emptyList())
        val (imageRels, chartRels) = parseRels(entries["word/_rels/document.xml.rels"])
        val blocks = mutableListOf<WordBlock>()
        parseDocument(docXml, imageRels, chartRels, entries, blocks)
        return WordDocument(blocks)
    }

    private fun parseRels(xml: ByteArray?): Pair<Map<String, String>, Map<String, String>> {
        val images = mutableMapOf<String, String>(); val charts = mutableMapOf<String, String>()
        if (xml == null) return images to charts
        val parser = Xml.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id"); val type = parser.getAttributeValue(null, "Type") ?: ""; val target = parser.getAttributeValue(null, "Target") ?: ""
                if (id != null) { val path = if (target.startsWith("/")) target.removePrefix("/") else "word/$target"
                    when { type.contains("image") -> images[id] = path; type.contains("chart") -> charts[id] = path } }
            }
            event = parser.next()
        }
        return images to charts
    }

    private fun parseDocument(docXml: ByteArray, imageRels: Map<String, String>, chartRels: Map<String, String>, media: Map<String, ByteArray>, blocks: MutableList<WordBlock>) {
        val xml = String(docXml, Charsets.UTF_8); var pos = 0
        while (pos < xml.length) {
            val lt = xml.indexOf('<', pos); if (lt < 0) break
            if (xml.startsWith("<?", lt) || xml.startsWith("<!--", lt) || xml.startsWith("<!", lt)) { val gt = xml.indexOf('>', lt); pos = if (gt < 0) xml.length else gt + 1; continue }
            val m = TAG_RE.find(xml, lt); if (m == null) { pos = lt + 1; continue }
            val closing = m.groupValues[1].isNotEmpty(); val ns = m.groupValues[2]; val name = m.groupValues[3]
            if (!closing && ns == "w") { when (name) {
                "p" -> { val end = findEnd(xml, lt, "w:p"); splitParagraphWithDrawings(xml.substring(lt, end), chartRels, media, blocks); pos = end; continue }
                "tbl" -> { val end = findNestedEnd(xml, lt, "w:tbl"); blocks += parseTable(xml.substring(lt, end)); pos = end; continue }
                "drawing" -> { val end = findEnd(xml, lt, "w:drawing"); blocks += parseDrawing(xml.substring(lt, end), chartRels, media); pos = end; continue }
                "sectPr" -> { pos = findEnd(xml, lt, "w:sectPr"); continue }
            } }
            val gt = xml.indexOf('>', lt); pos = if (gt < 0) xml.length else gt + 1
        }
    }

    private fun splitParagraphWithDrawings(xml: String, chartRels: Map<String, String>, media: Map<String, ByteArray>, blocks: MutableList<WordBlock>) {
        val drawings = mutableListOf<Pair<Int, Int>>(); var search = 0
        while (true) { val d = Regex("""<w:drawing(\s[^>]*)?>""").find(xml, search) ?: break; val dEnd = findEnd(xml, d.range.first, "w:drawing"); drawings += d.range.first to dEnd; search = dEnd }
        if (drawings.isEmpty()) { blocks += WordBlock.Paragraph(parseRuns(xml)); return }
        var textStart = 0
        for ((ds, de) in drawings) { addParagraphFragment(xml.substring(textStart, ds), blocks); blocks += parseDrawing(xml.substring(ds, de), chartRels, media); textStart = de }
        addParagraphFragment(xml.substring(textStart), blocks)
    }

    private fun normalizeFragment(fragment: String): String {
        var f = fragment; val gt = f.indexOf('>')
        if (f.startsWith("<w:p") && gt >= 0) f = f.substring(gt + 1); if (f.endsWith("</w:p>")) f = f.removeSuffix("</w:p>")
        val out = StringBuilder(); val stack = mutableListOf<String>()
        val tagRe = Regex("""<(/?)([A-Za-z0-9_]+:[A-Za-z0-9_]+)(\s[^>]*?)?(/?)\s*>"""); var i = 0
        while (i < f.length) { val lt = f.indexOf('<', i); if (lt < 0) { out.append(f.substring(i)); break }; out.append(f.substring(i, lt))
            val gt2 = f.indexOf('>', lt); if (gt2 < 0) { out.append(f.substring(lt)); break }; val tagText = f.substring(lt, gt2 + 1); val m = tagRe.find(tagText)
            if (m != null) { val closing = m.groupValues[1] == "/"; val name = m.groupValues[2]; val selfClose = m.groupValues[4] == "/"
                if (closing) { if (stack.isNotEmpty() && stack.last() == name) { stack.removeAt(stack.size - 1); out.append(tagText) } }
                else if (selfClose) out.append(tagText) else { stack += name; out.append(tagText) } } else out.append(tagText); i = gt2 + 1 }
        for (name in stack.reversed()) out.append("</$name>"); return out.toString()
    }

    private fun addParagraphFragment(fragment: String, blocks: MutableList<WordBlock>) {
        if (fragment.isBlank()) return; val n = normalizeFragment(fragment); if (n.isBlank()) return
        blocks += WordBlock.Paragraph(parseRuns("<w:p>$n</w:p>"))
    }

    // ---- Paragraph/Run parsing ----

    fun parseRuns(xml: String): List<WordRun> = parseParagraphFull(xml).first

    fun parseParagraphFull(xml: String): Triple<List<WordRun>, WordBlock.Paragraph, String> {
        val parser = Xml.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml.toByteArray()), "UTF-8")
        val runs = mutableListOf<WordRun>(); var alignment = DocAlignment.LEFT; var style = ""; var pageBreak = false; var bulleted = false
        var spacingBefore = 0f; var spacingAfter = 0f; var spacingLine = 0f; var spacingLineRule = ""
        var inRun = false; var runText = StringBuilder(); var runBold = false; var runItalic = false; var runUnderline = false; var runStrike = false; var runSize = 0; var runColor = 0; var runSuperSub = ""
        var inRPr = false; var inNumPr = false; var inT = false; var inSpacing = false; var inInd = false; var preserveSpace = false

        fun flushRun() {
            if (inRun && runText.isNotEmpty()) runs += WordRun(text = runText.toString(), bold = runBold, italic = runItalic, underline = runUnderline, strikethrough = runStrike, fontSize = if (runSize > 0) runSize / 2f else 12f, color = runColor, superscript = runSuperSub == "superscript", subscript = runSuperSub == "subscript")
            inRun = false; runText = StringBuilder(); runBold = false; runItalic = false; runUnderline = false; runStrike = false; runSize = 0; runColor = 0; runSuperSub = ""
        }
        fun attr(name: String): String? { for (i in 0 until parser.attributeCount) if (parser.getAttributeName(i).substringAfterLast(':') == name) return parser.getAttributeValue(i); return null }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) { val name = parser.name; when (event) {
            XmlPullParser.START_TAG -> when (name) {
                "w:pStyle" -> style = attr("val") ?: ""
                "w:jc" -> alignment = when (attr("val")) { "center" -> DocAlignment.CENTER; "right" -> DocAlignment.RIGHT; "both", "distribute" -> DocAlignment.JUSTIFY; else -> DocAlignment.LEFT }
                "w:numPr" -> inNumPr = true; "w:numId" -> if (inNumPr) bulleted = true
                "w:r" -> { flushRun(); inRun = true }; "w:rPr" -> inRPr = true
                "w:b" -> if (inRPr) runBold = attr("val")?.let { it != "0" && it != "false" } ?: true
                "w:i" -> if (inRPr) runItalic = attr("val")?.let { it != "0" && it != "false" } ?: true
                "w:u" -> if (inRPr) runUnderline = attr("val")?.let { it != "0" && it != "false" } ?: true
                "w:strike" -> if (inRPr) runStrike = attr("val")?.let { it != "0" && it != "false" } ?: true
                "w:sz" -> if (inRPr) runSize = attr("val")?.toIntOrNull() ?: 0
                "w:color" -> if (inRPr) runColor = parseDocColor(attr("val"))
                "w:vertAlign" -> if (inRPr) runSuperSub = attr("val") ?: ""
                "w:spacing" -> inSpacing = true; "w:ind" -> inInd = true
                "w:t" -> { inT = true; preserveSpace = attr("space") == "preserve" }
                "w:tab" -> runText.append('\t'); "w:br" -> { if (attr("type") == "page") pageBreak = true else runText.append('\n') }
            }
            XmlPullParser.TEXT -> { if (inT) runText.append(if (preserveSpace) parser.text else parser.text.trim())
                if (inSpacing) { attr("before")?.toFloatOrNull()?.let { spacingBefore = it / 20f }; attr("after")?.toFloatOrNull()?.let { spacingAfter = it / 20f }; attr("line")?.toFloatOrNull()?.let { spacingLine = it / 20f }; attr("lineRule")?.let { spacingLineRule = it } }
                if (inInd) { attr("firstLine")?.toFloatOrNull()?.let { } }
            }
            XmlPullParser.END_TAG -> when (name) { "w:t" -> inT = false; "w:rPr" -> inRPr = false; "w:numPr" -> inNumPr = false; "w:spacing" -> inSpacing = false; "w:ind" -> inInd = false; "w:r" -> flushRun() }
        }; event = parser.next() }
        flushRun()
        return Triple(runs, WordBlock.Paragraph(runs = runs, alignment = alignment, style = style, spacing = DocSpacing(spacingBefore, spacingAfter, spacingLine, spacingLineRule), bulleted = bulleted, pageBreakBefore = pageBreak), style)
    }

    // ---- Table parsing ----

    fun parseTable(xml: String): WordBlock.Table {
        val columns = Regex("""<w:gridCol\s+w:w="(\d+)"""").findAll(xml).map { it.groupValues[1].toFloat() / 20f }.toList()
        val rows = mutableListOf<WordTableRow>(); var pos = 0
        while (true) { val tr = Regex("""<w:tr(\s[^>]*)?>""").find(xml, pos) ?: break; val trEnd = findEnd(xml, tr.range.first, "w:tr"); val trXml = xml.substring(tr.range.first, trEnd)
            val isHeader = trXml.contains("w:tblHeader"); val cells = mutableListOf<WordTableCell>(); var cpos = 0
            while (true) { val tc = Regex("""<w:tc(\s[^>]*)?>""").find(trXml, cpos) ?: break; val tcEnd = findEnd(trXml, tc.range.first, "w:tc"); val tcXml = trXml.substring(tc.range.first, tcEnd)
                val gridSpan = Regex("""<w:gridSpan\s+w:val="(\d+)"""").find(tcXml)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val cellBg = parseDocColor(Regex("""w:fill="([0-9A-Fa-f]+)"""").find(tcXml)?.groupValues?.get(1))
                val paras = mutableListOf<WordBlock>(); var ppos = 0
                while (true) { val p = Regex("""<w:p(\s[^>]*)?>""").find(tcXml, ppos) ?: break; val pEnd = findEnd(tcXml, p.range.first, "w:p"); paras += WordBlock.Paragraph(parseRuns(tcXml.substring(p.range.first, pEnd))); ppos = pEnd }
                if (paras.isEmpty()) paras += WordBlock.Paragraph(emptyList())
                cells += WordTableCell(blocks = paras, gridSpan = gridSpan, background = cellBg); cpos = tcEnd }
            rows += WordTableRow(cells = cells, isHeader = isHeader); pos = trEnd }
        return WordBlock.Table(rows = rows, columnWidths = columns)
    }

    // ---- Drawing / Image / Chart ----

    fun parseDrawing(xml: String, chartRels: Map<String, String>, media: Map<String, ByteArray>): WordBlock {
        val chartRid = Regex("""r:id="([^"]+)"""").find(xml)?.groupValues?.get(1)
        if (chartRid != null && chartRels.containsKey(chartRid)) { val chartPath = chartRels[chartRid]!!; val chartXml = media[chartPath]?.let { String(it, Charsets.UTF_8) }
            if (chartXml != null) { val extent = Regex("""<wp:extent\s+cx="(\d+)"\s+cy="(\d+)" """).find(xml); val h = extent?.let { it.groupValues[2].toFloat() / 914400f * 72f } ?: 240f
                return parseChart(chartXml, minOf(maxOf(h, 120f), 360f)) } }
        val blipRid = Regex("""r:embed="([^"]+)"""").find(xml)?.groupValues?.get(1)
        if (blipRid != null) { val mediaPath = media.keys.find { it.contains("media") }; val imgBytes = mediaPath?.let { media[it] }
            if (imgBytes != null) { val extent = Regex("""<wp:extent\s+cx="(\d+)"\s+cy="(\d+)" """).find(xml)
                val w = extent?.let { (it.groupValues[1].toFloat() / 914400f * 72f).toInt() } ?: 200
                val h = extent?.let { (it.groupValues[2].toFloat() / 914400f * 72f).toInt() } ?: 200
                return WordBlock.EmbeddedImage(imgBytes, w, h) } }
        return WordBlock.Unsupported("Drawing/shape")
    }

    private fun parseChart(xml: String, heightPt: Float): WordBlock.ChartBlock {
        val parser = Xml.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(xml.toByteArray()), "UTF-8")
        var type = "bar"; var title = ""; val categories = mutableListOf<String>(); val series = mutableListOf<DocChartSeries>()
        var inSer = false; var serName = ""; var serColor = 0xFF004586.toInt(); var serVals = mutableListOf<Double>(); var serCats = mutableListOf<String>()
        var inTx = false; var inCat = false; var inVal = false; var inSpPr = false; var inTitle = false; var inV = false
        val colors = listOf(0xFF004586.toInt(), 0xFFFF420E.toInt(), 0xFFFFD320.toInt(), 0xFF007777.toInt(), 0xFF7B0080.toInt(), 0xFF00A1F1.toInt())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) { val name = parser.name; when (event) {
            XmlPullParser.START_TAG -> when { name == "ser" -> { inSer = true; serName = ""; serColor = colors[series.size % colors.size]; serCats = mutableListOf(); serVals = mutableListOf() }
                name == "tx" -> inTx = true; name == "cat" -> inCat = true; name == "val" -> inVal = true; name == "spPr" -> inSpPr = true
                name == "srgbClr" -> if (inSer && inSpPr) { parser.getAttributeValue(null, "val")?.toIntOrNull(16)?.let { serColor = 0xFF000000.toInt() or it } }
                name == "v" -> inV = true; name == "title" -> inTitle = true; name.endsWith("Chart") && name != "chart" -> type = name.removeSuffix("Chart") }
            XmlPullParser.TEXT -> if (inSer && inV) { val t = parser.text?.trim().orEmpty(); when { inTx -> if (serName.isEmpty()) serName = t; inCat -> if (t.isNotEmpty()) serCats += t; inVal -> t.toDoubleOrNull()?.let { serVals += it } }
                } else if (inTitle && inV) { if (title.isEmpty()) title = parser.text?.trim().orEmpty() }
            XmlPullParser.END_TAG -> when (name) { "v" -> inV = false; "tx" -> inTx = false; "cat" -> inCat = false; "val" -> inVal = false; "spPr" -> inSpPr = false; "title" -> inTitle = false
                "ser" -> { if (inSer) { series += DocChartSeries(serName, serColor, serVals); if (serCats.isNotEmpty() && categories.isEmpty()) categories += serCats }; inSer = false } }
        }; event = parser.next() }
        return WordBlock.ChartBlock(type, title, categories, series, heightPt)
    }

    // ---- XML helpers ----
    private fun findEnd(xml: String, start: Int, tag: String): Int {
        val open = Regex("""<$tag(\s[^>]*)?>""").find(xml, start) ?: return xml.length; val gt = xml.indexOf('>', open.range.last)
        if (gt >= 0 && xml[gt - 1] == '/') return gt + 1; val close = xml.indexOf("</$tag>", open.range.last); return if (close < 0) xml.length else close + tag.length + 3 }
    private fun findNestedEnd(xml: String, start: Int, tag: String): Int {
        val open = Regex("""<$tag(\s[^>]*)?>"""); val close = "</$tag>"; var depth = 0; var i = start
        while (i < xml.length) { val o = open.find(xml, i); val c = xml.indexOf(close, i); val oi = o?.range?.first ?: Int.MAX_VALUE
            if (o != null && oi < c) { depth++; i = o.range.last + 1 } else if (c >= 0) { depth--; if (depth == 0) return c + close.length; i = c + close.length } else return xml.length }; return xml.length }
}
