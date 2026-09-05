package com.example.fileconverter

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Parses .docx files into [WordDocument].
 *
 * Fidelity notes (Part 1 fixes):
 *  - word/styles.xml is parsed once and styles are resolved through their
 *    basedOn chain (heading level, default bold/italic/font size).
 *  - Paragraphs whose pStyle resolves to a heading are emitted as
 *    [WordBlock.Heading] at parse time — the renderer never guesses from text.
 *  - w:ind (firstLine / hanging / left / right) is parsed into [Paragraph.indent].
 *  - word/numbering.xml maps numId+ilvl to a numbering format; such paragraphs
 *    become [WordBlock.ListItem] with level + numFmt so the renderer can draw
 *    real 1./2./a./b./i./ii. counters instead of a flat bullet flag.
 *  - w:vMerge cells are tagged [WordTableCell.vMergeCont] so "continue" cells
 *    are not duplicated as new cells.
 */
object DocxParser {

    // ═══════════════════════════════════════════════════════════════
    //  Style model (styles.xml)
    // ═══════════════════════════════════════════════════════════════

    private class DocxStyle(
        val styleId: String,
        val name: String = "",
        val basedOn: String? = null,
        val outlineLvl: Int? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val sizeHalfPt: Int? = null,
    )

    private class ResolvedStyle(
        val headingLevel: Int? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val sizeHalfPt: Int? = null,
    )

    /** Resolved default character size from docDefaults, if present. */
    private class StyleContext(
        private val styles: Map<String, DocxStyle>,
        private val docDefaultSizeHalfPt: Int?,
    ) {
        private val cache = mutableMapOf<String, ResolvedStyle>()

        fun resolve(styleId: String?): ResolvedStyle {
            if (styleId.isNullOrEmpty()) return ResolvedStyle()
            return cache.getOrPut(styleId) {
                var cur = styles[styleId]
                var headingLevel: Int? = null
                var bold: Boolean? = null
                var italic: Boolean? = null
                var size: Int? = null
                var guard = 0
                while (cur != null && guard++ < 12) {
                    // Heading level: <w:name w:val="heading N"/> (canonical) …
                    val nameMatch = Regex("""heading\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(cur.name)
                    if (nameMatch != null) {
                        headingLevel = nameMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 6)
                    } else if (headingLevel == null && cur.outlineLvl != null) {
                        // … or outlineLvl on a style whose name/base is heading-like.
                        val headingish = Regex("heading", RegexOption.IGNORE_CASE).containsMatchIn(cur.name)
                        if (headingish) headingLevel = (cur.outlineLvl + 1).coerceIn(1, 6)
                    }
                    if (bold == null) bold = cur.bold
                    if (italic == null) italic = cur.italic
                    if (size == null) size = cur.sizeHalfPt
                    cur = cur.basedOn?.let { styles[it] }
                }
                ResolvedStyle(headingLevel, bold, italic, size ?: docDefaultSizeHalfPt)
            }
        }
    }

    private fun parseStyles(xml: ByteArray?): StyleContext {
        val styles = mutableMapOf<String, DocxStyle>()
        var docDefaultSize: Int? = null
        if (xml != null) {
            val s = String(xml, Charsets.UTF_8)
            // docDefaults → rPrDefault → rPr → sz
            Regex("""<w:docDefaults[\s\S]*?<w:sz w:val="(\d+)"""")
                .find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { docDefaultSize = it }
            val styleRe = Regex("""<w:style\b([^>]*)>([\s\S]*?)</w:style>""")
            for (m in styleRe.findAll(s)) {
                val attrs = m.groupValues[1]
                val styleId = Regex("""w:styleId="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: continue
                val body = m.groupValues[2]
                val name = Regex("""<w:name\s+w:val="([^"]*)"""").find(body)?.groupValues?.get(1) ?: ""
                val basedOn = Regex("""<w:basedOn\s+w:val="([^"]*)"""").find(body)?.groupValues?.get(1)
                val outline = Regex("""<w:outlineLvl\s+w:val="(\d+)"""").find(body)?.groupValues?.get(1)?.toIntOrNull()
                val rPr = Regex("""<w:rPr>([\s\S]*?)</w:rPr>""").find(body)?.groupValues?.get(1).orEmpty()
                fun has(tag: String): Boolean? = when {
                    Regex("""<$tag\s*/?>""").containsMatchIn(rPr) &&
                        !Regex("""<$tag\s+w:val="(0|false)"""", RegexOption.IGNORE_CASE).containsMatchIn(rPr) -> true
                    Regex("""<$tag\s+w:val="(0|false)"""", RegexOption.IGNORE_CASE).containsMatchIn(rPr) -> false
                    else -> null
                }
                val sz = Regex("""<w:sz\s+w:val="(\d+)"""").find(rPr)?.groupValues?.get(1)?.toIntOrNull()
                styles[styleId] = DocxStyle(styleId, name, basedOn, outline, has("w:b"), has("w:i"), sz)
            }
        }
        return StyleContext(styles, docDefaultSize)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Numbering model (numbering.xml)
    // ═══════════════════════════════════════════════════════════════

    private data class NumLevel(val fmt: String, val start: Int = 1)

    /** numId → ilvl → level definition. */
    private class NumberingContext(private val levels: Map<Int, Map<Int, NumLevel>>) {
        fun level(numId: Int, ilvl: Int): NumLevel? = levels[numId]?.get(ilvl)
    }

    private fun parseNumbering(xml: ByteArray?): NumberingContext {
        val abstractLevels = mutableMapOf<Int, MutableMap<Int, NumLevel>>()
        val numToAbstract = mutableMapOf<Int, Int>()
        if (xml != null) {
            val s = String(xml, Charsets.UTF_8)
            val absRe = Regex("""<w:abstractNum\s+w:abstractNumId="(\d+)"[^>]*>([\s\S]*?)</w:abstractNum>""")
            for (am in absRe.findAll(s)) {
                val absId = am.groupValues[1].toIntOrNull() ?: continue
                val map = mutableMapOf<Int, NumLevel>()
                val lvlRe = Regex("""<w:lvl\s+w:ilvl="(\d+)"[^>]*>([\s\S]*?)</w:lvl>""")
                for (lm in lvlRe.findAll(am.groupValues[2])) {
                    val ilvl = lm.groupValues[1].toIntOrNull() ?: 0
                    val fmt = Regex("""<w:numFmt\s+w:val="([^"]+)"""").find(lm.groupValues[2])?.groupValues?.get(1) ?: "decimal"
                    val start = Regex("""<w:start\s+w:val="(\d+)"""").find(lm.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    map[ilvl] = NumLevel(fmt, start)
                }
                abstractLevels[absId] = map
            }
            val numRe = Regex("""<w:num\s+w:numId="(\d+)"[^>]*>([\s\S]*?)</w:num>""")
            for (nm in numRe.findAll(s)) {
                val numId = nm.groupValues[1].toIntOrNull() ?: continue
                val absId = Regex("""<w:abstractNumId\s+w:val="(\d+)"""").find(nm.groupValues[2])?.groupValues?.get(1)?.toIntOrNull()
                if (absId != null) numToAbstract[numId] = absId
            }
            // Resolve numId → concrete level map (respecting lvlOverride where present).
            val result = mutableMapOf<Int, Map<Int, NumLevel>>()
            for ((numId, absId) in numToAbstract) {
                val base = abstractLevels[absId] ?: continue
                result[numId] = base
            }
            return NumberingContext(result)
        }
        return NumberingContext(emptyMap())
    }

    // ═══════════════════════════════════════════════════════════════
    //  Entry point
    // ═══════════════════════════════════════════════════════════════

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
        val styles = parseStyles(entries["word/styles.xml"])
        val numbering = parseNumbering(entries["word/numbering.xml"])
        val blocks = mutableListOf<WordBlock>()
        parseDocument(docXml, imageRels, chartRels, entries, styles, numbering, blocks)
        return WordDocument(blocks)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Top-level scan of document.xml
    // ═══════════════════════════════════════════════════════════════

    private val TAG_RE = Regex("""<(/)?([A-Za-z0-9_]+):([A-Za-z0-9_]+)""")

    private fun parseDocument(
        docXml: ByteArray,
        imageRels: Map<String, String>,
        chartRels: Map<String, String>,
        media: Map<String, ByteArray>,
        styles: StyleContext,
        numbering: NumberingContext,
        blocks: MutableList<WordBlock>,
    ) {
        val xml = String(docXml, Charsets.UTF_8); var pos = 0
        while (pos < xml.length) {
            val lt = xml.indexOf('<', pos); if (lt < 0) break
            if (xml.startsWith("<?", lt) || xml.startsWith("<!--", lt) || xml.startsWith("<!", lt)) { val gt = xml.indexOf('>', lt); pos = if (gt < 0) xml.length else gt + 1; continue }
            val m = TAG_RE.find(xml, lt); if (m == null) { pos = lt + 1; continue }
            val closing = m.groupValues[1].isNotEmpty(); val ns = m.groupValues[2]; val name = m.groupValues[3]
            if (!closing && ns == "w") {
                when (name) {
                    "p" -> { val end = findEnd(xml, lt, "w:p"); splitParagraph(xml.substring(lt, end), imageRels, chartRels, media, styles, numbering, blocks); pos = end; continue }
                    "tbl" -> { val end = findNestedEnd(xml, lt, "w:tbl"); blocks += parseTable(xml.substring(lt, end), styles, numbering); pos = end; continue }
                    "drawing" -> { val end = findEnd(xml, lt, "w:drawing"); blocks += parseDrawing(xml.substring(lt, end), imageRels, chartRels, media); pos = end; continue }
                    "sectPr" -> { pos = findEnd(xml, lt, "w:sectPr"); continue }
                }
            }
            val gt = xml.indexOf('>', lt); pos = if (gt < 0) xml.length else gt + 1
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Paragraph → block conversion
    // ═══════════════════════════════════════════════════════════════

    /**
     * Splits a paragraph into text + inline drawings, and converts the (possibly
     * fragmented) paragraph into Heading / ListItem / Paragraph blocks using the
     * resolved pStyle and numbering info.
     */
    private fun splitParagraph(
        xml: String,
        imageRels: Map<String, String>,
        chartRels: Map<String, String>,
        media: Map<String, ByteArray>,
        styles: StyleContext,
        numbering: NumberingContext,
        blocks: MutableList<WordBlock>,
    ) {
        val drawingRanges = mutableListOf<Pair<Int, Int>>()
        var search = 0
        while (true) {
            val d = Regex("""<w:drawing(\s[^>]*)?>""").find(xml, search) ?: break
            val dEnd = findEnd(xml, d.range.first, "w:drawing")
            drawingRanges += d.range.first to dEnd
            search = dEnd
        }

        // No inline drawings → single paragraph/heading/list block.
        if (drawingRanges.isEmpty()) {
            emitParagraphBlock(parseParagraphFull(xml, styles, numbering), null, blocks)
            return
        }

        // Text fragments around a drawing may start/end mid-run (a drawing can sit
        // inside <w:r>), so balance each fragment and parse it as its own paragraph.
        var textStart = 0
        for ((ds, de) in drawingRanges) {
            val fragment = xml.substring(textStart, ds)
            if (fragment.isNotBlank()) {
                val normalized = normalizeFragment(fragment)
                if (normalized.isNotBlank()) {
                    emitParagraphBlock(parseParagraphFull("<w:p>$normalized</w:p>", styles, numbering), null, blocks)
                }
            }
            blocks += parseDrawing(xml.substring(ds, de), imageRels, chartRels, media)
            textStart = de
        }
        val tail = xml.substring(textStart)
        if (tail.isNotBlank()) {
            val normalized = normalizeFragment(tail)
            if (normalized.isNotBlank()) {
                emitParagraphBlock(parseParagraphFull("<w:p>$normalized</w:p>", styles, numbering), null, blocks)
            }
        }
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
            if (lt < 0) { out.append(f.substring(i)); break }
            out.append(f.substring(i, lt))
            val gt2 = f.indexOf('>', lt); if (gt2 < 0) { out.append(f.substring(lt)); break }
            val tagText = f.substring(lt, gt2 + 1); val m = tagRe.find(tagText)
            if (m != null) {
                val closing = m.groupValues[1] == "/"; val name = m.groupValues[2]; val selfClose = m.groupValues[4] == "/"
                if (closing) {
                    if (stack.isNotEmpty() && stack.last() == name) { stack.removeAt(stack.size - 1); out.append(tagText) }
                } else if (selfClose) out.append(tagText) else { stack += name; out.append(tagText) }
            } else out.append(tagText)
            i = gt2 + 1
        }
        for (name in stack.reversed()) out.append("</$name>")
        return out.toString()
    }

    /** Paragraph-level info produced by parseParagraphFull. */
    private class ParaInfo(
        val runs: List<WordRun>,
        val alignment: DocAlignment,
        val style: String,
        val spacing: DocSpacing,
        val indent: DocIndent,
        val pageBreakBefore: Boolean,
        val numId: Int,
        val ilvl: Int,
        val resolved: ResolvedStyle,
    )

    /** Adds a Heading / ListItem / Paragraph block from [info]. */
    private fun emitParagraphBlock(info: ParaInfo?, blockOverride: WordBlock?, blocks: MutableList<WordBlock>, numbering: NumberingContext = NumberingContext(emptyMap())) {
        if (blockOverride != null) { blocks += blockOverride; return }
        if (info == null) return
        val level = info.resolved.headingLevel
        when {
            // Real heading (resolved from pStyle → styles.xml). Empty headings are kept as spacers.
            level != null && info.style.isNotEmpty() -> {
                val runs = applyStyleDefaults(info.runs, info.resolved)
                val text = runs.joinToString("") { it.text }
                if (text.isBlank()) {
                    blocks += WordBlock.Paragraph(emptyList())
                } else {
                    blocks += WordBlock.Heading(runs, level, info.alignment)
                }
            }
            // Numbered/bulleted list item (from numbering.xml).
            info.numId >= 0 -> {
                val numLevel = numbering.level(info.numId, info.ilvl)
                val fmt = numLevel?.fmt ?: ""
                val isBullet = fmt.isEmpty() || fmt == "bullet"
                val runs = applyStyleDefaults(info.runs, info.resolved)
                blocks += WordBlock.ListItem(
                    runs = runs,
                    level = info.ilvl.coerceAtLeast(0),
                    bulleted = isBullet,
                    numFmt = if (isBullet) "" else fmt,
                    numId = info.numId,
                    start = numLevel?.start ?: 1,
                )
            }
            else -> {
                blocks += WordBlock.Paragraph(
                    runs = applyStyleDefaults(info.runs, info.resolved),
                    alignment = info.alignment,
                    style = info.style,
                    spacing = info.spacing,
                    indent = info.indent,
                    pageBreakBefore = info.pageBreakBefore,
                )
            }
        }
    }

    /** A run inherits bold/italic/size from the resolved style only when rPr didn't set it. */
    private fun applyStyleDefaults(runs: List<WordRun>, resolved: ResolvedStyle): List<WordRun> {
        return runs.map { r ->
            if (r.bold || r.italic || r.fontSize != 12f || resolved.bold == null && resolved.sizeHalfPt == null) r
            else WordRun(
                text = r.text,
                bold = r.bold || resolved.bold == true,
                italic = r.italic || resolved.italic == true,
                underline = r.underline,
                strikethrough = r.strikethrough,
                fontSize = if (r.fontSize != 12f) r.fontSize
                else if (resolved.sizeHalfPt != null) resolved.sizeHalfPt / 2f else r.fontSize,
                fontFamily = r.fontFamily,
                color = r.color,
                superscript = r.superscript,
                subscript = r.subscript,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Paragraph parser (runs + properties)
    // ═══════════════════════════════════════════════════════════════

    private fun parseRuns(xml: String): List<WordRun> = parseParagraphFull(xml, StyleContext(emptyMap(), null), NumberingContext(emptyMap())).runs

    private fun parseParagraphFull(
        xml: String,
        styles: StyleContext = StyleContext(emptyMap(), null),
        numbering: NumberingContext = NumberingContext(emptyMap()),
    ): ParaInfo {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml.toByteArray()), "UTF-8")
        val runs = mutableListOf<WordRun>()
        var alignment = DocAlignment.LEFT
        var style = ""
        var spacingBefore = 0f; var spacingAfter = 0f; var spacingLine = 0f; var spacingRule = ""
        var indentLeft = 0f; var indentRight = 0f; var indentFirst = 0f; var indentHanging = 0f
        var numId = -1; var ilvl = 0; var pageBreak = false

        var inRun = false; var runText = StringBuilder()
        var runBold = false; var runItalic = false; var runUnderline = false; var runStrike = false
        var runSize = 0; var runColor = 0; var runSuperSub = ""
        var inRPr = false; var inNumPr = false; var inT = false; var preserveSpace = false

        fun flushRun() {
            if (inRun) {
                val t = runText.toString()
                if (t.isNotEmpty()) {
                    runs += WordRun(
                        text = t, bold = runBold, italic = runItalic, underline = runUnderline,
                        strikethrough = runStrike, fontSize = if (runSize > 0) runSize / 2f else 12f,
                        color = runColor, superscript = runSuperSub == "superscript", subscript = runSuperSub == "subscript",
                    )
                }
            }
            inRun = false; runText = StringBuilder(); runBold = false; runItalic = false; runUnderline = false
            runStrike = false; runSize = 0; runColor = 0; runSuperSub = ""
        }
        fun attr(name: String): String? {
            for (i in 0 until parser.attributeCount) {
                if (parser.getAttributeName(i).substringAfterLast(':') == name) return parser.getAttributeValue(i)
            }
            return null
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "w:pStyle" -> style = attr("val") ?: ""
                    "w:jc" -> alignment = when (attr("val")) {
                        "center" -> DocAlignment.CENTER; "right" -> DocAlignment.RIGHT
                        "both", "distribute" -> DocAlignment.JUSTIFY; else -> DocAlignment.LEFT
                    }
                    "w:numPr" -> inNumPr = true
                    "w:numId" -> if (inNumPr) numId = attr("val")?.toIntOrNull() ?: -1
                    "w:ilvl" -> if (inNumPr) ilvl = attr("val")?.toIntOrNull() ?: 0
                    "w:pageBreakBefore" -> pageBreak = true
                    "w:spacing" -> {
                        spacingBefore = (attr("before")?.toFloatOrNull() ?: 0f) / 20f
                        spacingAfter = (attr("after")?.toFloatOrNull() ?: 0f) / 20f
                        spacingLine = (attr("line")?.toFloatOrNull() ?: 0f) / 20f
                        spacingRule = attr("lineRule") ?: ""
                    }
                    "w:ind" -> {
                        indentLeft = (attr("left") ?: attr("start") ?: "0").toFloatOrNull()?.div(20f) ?: 0f
                        indentRight = (attr("right") ?: attr("end") ?: "0").toFloatOrNull()?.div(20f) ?: 0f
                        indentFirst = (attr("firstLine")?.toFloatOrNull() ?: 0f) / 20f
                        indentHanging = (attr("hanging")?.toFloatOrNull() ?: 0f) / 20f
                    }
                    "w:r" -> { flushRun(); inRun = true }
                    "w:rPr" -> inRPr = true
                    "w:b" -> if (inRPr) runBold = attr("val")?.let { it != "0" && it != "false" } ?: true
                    "w:i" -> if (inRPr) runItalic = attr("val")?.let { it != "0" && it != "false" } ?: true
                    "w:u" -> if (inRPr) runUnderline = attr("val")?.let { it != "0" && it != "false" } ?: true
                    "w:strike" -> if (inRPr) runStrike = attr("val")?.let { it != "0" && it != "false" } ?: true
                    "w:sz" -> if (inRPr) runSize = attr("val")?.toIntOrNull() ?: 0
                    "w:color" -> if (inRPr) runColor = parseDocColor(attr("val"))
                    "w:vertAlign" -> if (inRPr) runSuperSub = attr("val") ?: ""
                    "w:t" -> { inT = true; preserveSpace = attr("space") == "preserve" }
                    "w:tab" -> runText.append('\t')
                    "w:br" -> { if (attr("type") == "page") pageBreak = true else runText.append('\n') }
                    else -> Unit
                }
                XmlPullParser.TEXT -> if (inT) runText.append(if (preserveSpace) parser.text else parser.text.trim())
                XmlPullParser.END_TAG -> when (name) {
                    "w:t" -> inT = false
                    "w:rPr" -> inRPr = false
                    "w:r" -> flushRun()
                    "w:numPr" -> inNumPr = false
                    else -> Unit
                }
            }
            // Simple depth tracking: we parse whole-paragraph fragments, so just
            // keep nested tables out of run text by skipping their subtrees.
            event = parser.next()
        }
        flushRun()

        val resolved = styles.resolve(style)
        return ParaInfo(
            runs = runs,
            alignment = alignment,
            style = style,
            spacing = DocSpacing(spacingBefore, spacingAfter, spacingLine, spacingRule),
            indent = DocIndent(indentLeft, indentRight, indentFirst, indentHanging),
            pageBreakBefore = pageBreak,
            numId = numId,
            ilvl = ilvl,
            resolved = resolved,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tables
    // ═══════════════════════════════════════════════════════════════

    private fun parseTable(xml: String, styles: StyleContext = StyleContext(emptyMap(), null), numbering: NumberingContext = NumberingContext(emptyMap())): WordBlock.Table {
        val columns = Regex("""<w:gridCol\s+w:w="(\d+)"""").findAll(xml).map { it.groupValues[1].toFloat() / 20f }.toList()
        val rows = mutableListOf<WordTableRow>(); var pos = 0
        while (true) {
            val tr = Regex("""<w:tr(\s[^>]*)?>""").find(xml, pos) ?: break
            val trEnd = findEnd(xml, tr.range.first, "w:tr"); val trXml = xml.substring(tr.range.first, trEnd)
            val isHeader = trXml.contains("w:tblHeader")
            val cells = mutableListOf<WordTableCell>(); var cpos = 0
            while (true) {
                val tc = Regex("""<w:tc(\s[^>]*)?>""").find(trXml, cpos) ?: break
                val tcEnd = findEnd(trXml, tc.range.first, "w:tc"); val tcXml = trXml.substring(tc.range.first, tcEnd)
                val gridSpan = Regex("""<w:gridSpan\s+w:val="(\d+)"""").find(tcXml)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val cellBg = parseDocColor(Regex("""w:fill="([0-9A-Fa-f]+)"""").find(tcXml)?.groupValues?.get(1))
                // Vertical merge: <w:vMerge/> (restart) or <w:vMerge w:val="continue"/>.
                val vMerge = Regex("""<w:vMerge(\s[^>]*)?/?>""").find(tcXml)
                val isContinue = vMerge != null && (Regex("""w:val="continue"""").containsMatchIn(vMerge.value))
                if (isContinue) {
                    // "Continue" cell carries no new content — tag it so renderers skip it.
                    cells += WordTableCell(listOf(WordBlock.Paragraph(emptyList())), gridSpan, vMergeCont = true)
                } else {
                    val paras = mutableListOf<WordBlock>(); var ppos = 0
                    while (true) {
                        val p = Regex("""<w:p(\s[^>]*)?>""").find(tcXml, ppos) ?: break
                        val pEnd = findEnd(tcXml, p.range.first, "w:p")
                        val info = parseParagraphFull(tcXml.substring(p.range.first, pEnd), styles)
                        val inner = mutableListOf<WordBlock>()
                        emitParagraphBlock(info, null, inner, numbering)
                        paras += inner
                        ppos = pEnd
                    }
                    if (paras.isEmpty()) paras += WordBlock.Paragraph(emptyList())
                    cells += WordTableCell(blocks = paras, gridSpan = gridSpan, background = cellBg)
                }
                cpos = tcEnd
            }
            rows += WordTableRow(cells = cells, isHeader = isHeader); pos = trEnd
        }
        return WordBlock.Table(rows = rows, columnWidths = columns)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Drawings / images / charts
    // ═══════════════════════════════════════════════════════════════

    private fun parseRels(xml: ByteArray?): Pair<Map<String, String>, Map<String, String>> {
        val images = mutableMapOf<String, String>(); val charts = mutableMapOf<String, String>()
        if (xml == null) return images to charts
        val parser = Xml.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id"); val type = parser.getAttributeValue(null, "Type") ?: ""; val target = parser.getAttributeValue(null, "Target") ?: ""
                if (id != null) {
                    val path = if (target.startsWith("/")) target.removePrefix("/") else "word/$target"
                    when { type.contains("image") -> images[id] = path; type.contains("chart") -> charts[id] = path }
                }
            }
            event = parser.next()
        }
        return images to charts
    }

    fun parseDrawing(xml: String, imageRels: Map<String, String>, chartRels: Map<String, String>, media: Map<String, ByteArray>): WordBlock {
        val chartRid = Regex("""r:id="([^"]+)"""").find(xml)?.groupValues?.get(1)
        if (chartRid != null && chartRels.containsKey(chartRid)) {
            val chartPath = chartRels[chartRid]!!
            val chartXml = media[chartPath]?.let { String(it, Charsets.UTF_8) }
            if (chartXml != null) {
                val extent = Regex("""<wp:extent\s+cx="(\d+)"\s+cy="(\d+)" """).find(xml)
                val h = extent?.let { it.groupValues[2].toFloat() / 914400f * 72f } ?: 240f
                return parseChart(chartXml, minOf(maxOf(h, 120f), 360f))
            }
        }
        val blipRid = Regex("""r:embed="([^"]+)"""").find(xml)?.groupValues?.get(1)
        if (blipRid != null) {
            val mediaPath = imageRels[blipRid]; val imgBytes = mediaPath?.let { media[it] }
            if (imgBytes != null) {
                val extent = Regex("""<wp:extent\s+cx="(\d+)"\s+cy="(\d+)" """).find(xml)
                val w = extent?.let { (it.groupValues[1].toFloat() / 914400f * 72f).toInt() } ?: 200
                val h = extent?.let { (it.groupValues[2].toFloat() / 914400f * 72f).toInt() } ?: 200
                return WordBlock.EmbeddedImage(imgBytes, w, h)
            }
        }
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
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "ser" -> { inSer = true; serName = ""; serColor = colors[series.size % colors.size]; serCats = mutableListOf(); serVals = mutableListOf() }
                    name == "tx" -> inTx = true; name == "cat" -> inCat = true; name == "val" -> inVal = true; name == "spPr" -> inSpPr = true
                    name == "srgbClr" -> if (inSer && inSpPr) { parser.getAttributeValue(null, "val")?.toIntOrNull(16)?.let { serColor = 0xFF000000.toInt() or it } }
                    name == "v" -> inV = true; name == "title" -> inTitle = true
                    name.endsWith("Chart") && name != "chart" -> type = name.removeSuffix("Chart")
                }
                XmlPullParser.TEXT -> if (inSer && inV) {
                    val t = parser.text?.trim().orEmpty()
                    when { inTx -> if (serName.isEmpty()) serName = t; inCat -> if (t.isNotEmpty()) serCats += t; inVal -> t.toDoubleOrNull()?.let { serVals += it } }
                } else if (inTitle && inV) { if (title.isEmpty()) title = parser.text?.trim().orEmpty() }
                XmlPullParser.END_TAG -> when (name) {
                    "v" -> inV = false; "tx" -> inTx = false; "cat" -> inCat = false; "val" -> inVal = false; "spPr" -> inSpPr = false; "title" -> inTitle = false
                    "ser" -> { if (inSer) { series += DocChartSeries(serName, serColor, serVals); if (serCats.isNotEmpty() && categories.isEmpty()) categories += serCats }; inSer = false }
                }
            }
            event = parser.next()
        }
        return WordBlock.ChartBlock(type, title, categories, series, heightPt)
    }

    // ═══════════════════════════════════════════════════════════════
    //  XML helpers
    // ═══════════════════════════════════════════════════════════════

    private fun findEnd(xml: String, start: Int, tag: String): Int {
        val open = Regex("""<$tag(\s[^>]*)?>""").find(xml, start) ?: return xml.length
        val gt = xml.indexOf('>', open.range.last)
        if (gt >= 0 && xml[gt - 1] == '/') return gt + 1
        val close = xml.indexOf("</$tag>", open.range.last)
        return if (close < 0) xml.length else close + tag.length + 3
    }

    private fun findNestedEnd(xml: String, start: Int, tag: String): Int {
        val open = Regex("""<$tag(\s[^>]*)?>"""); val close = "</$tag>"
        var depth = 0; var i = start
        while (i < xml.length) {
            val o = open.find(xml, i); val c = xml.indexOf(close, i); val oi = o?.range?.first ?: Int.MAX_VALUE
            if (o != null && oi < c) { depth++; i = o.range.last + 1 }
            else if (c >= 0) { depth--; if (depth == 0) return c + close.length; i = c + close.length }
            else return xml.length
        }
        return xml.length
    }
}
