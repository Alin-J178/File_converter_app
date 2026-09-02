package com.example.fileconverter

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Parses .odt (OpenDocument Text) files into [WordDocument].
 *
 * Handles:
 *  - Paragraphs (<text:p>) and headings (<text:h>) with run level formatting
 *    (bold / italic / underline / font size) resolved from style declarations.
 *  - Lists (<text:list>) rendered as bulleted [WordBlock.ListItem] items.
 *  - Tables (<table:table> -> <table:table-row> -> <table:table-cell>).
 */
object OdtParser {

    fun parse(ctx: Context, uri: Uri): WordDocument {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return WordDocument(emptyList())
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) { entries[entry.name] = zip.readBytes(); entry = zip.nextEntry }
        }
        val contentXml = entries["content.xml"] ?: return WordDocument(emptyList())
        val xml = String(contentXml, Charsets.UTF_8)

        val styles = parseStyles(xml)
        val body = Regex("""<office:body[^>]*>(.*?)</office:body>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: xml

        val blocks = mutableListOf<WordBlock>()
        parseContent(body, styles, blocks)
        if (blocks.isEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun("Empty document")))
        return WordDocument(blocks)
    }

    // ------------------------------------------------------------------
    // Styles
    // ------------------------------------------------------------------

    private class OdtStyle(
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val fontSizePt: Float?,
    )

    private fun parseStyles(xml: String): Map<String, OdtStyle> {
        val styles = mutableMapOf<String, OdtStyle>()
        val styleRe = Regex("""<style:style\b([^>]*)>(.*?)</style:style>""", RegexOption.DOT_MATCHES_ALL)
        for (m in styleRe.findAll(xml)) {
            val name = Regex("""style:name="([^"]+)"""").find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val props = m.groupValues[2]
            var bold = false; var italic = false; var underline = false; var size: Float? = null
            val propRe = Regex("""<style:text-properties\b([^>]*)/?>""")
            for (p in propRe.findAll(props)) {
                val a = p.groupValues[1]
                val fw = Regex("""fo:font-weight="([^"]+)"""").find(a)?.groupValues?.get(1)
                if (fw == "bold" || fw == "bolder") bold = true
                val fs = Regex("""fo:font-style="([^"]+)"""").find(a)?.groupValues?.get(1)
                if (fs == "italic") italic = true
                val tu = Regex("""style:text-underline-style="([^"]+)"""").find(a)?.groupValues?.get(1)
                if (tu != null && tu != "none") underline = true
                Regex("""fo:font-size="([0-9.]+)pt"""").find(a)?.groupValues?.get(1)?.toFloatOrNull()?.let { size = it }
            }
            styles[name] = OdtStyle(bold, italic, underline, size)
        }
        return styles
    }

    // ------------------------------------------------------------------
    // Top level traversal
    // ------------------------------------------------------------------

    private fun parseContent(content: String, styles: Map<String, OdtStyle>, blocks: MutableList<WordBlock>) {
        var pos = 0
        while (pos < content.length) {
            // We only care about block-level start tags outside of tables/paragraphs already consumed.
            val lt = content.indexOf('<', pos)
            if (lt < 0) break
            val tagInfo = Regex("""<(/)?([a-zA-Z0-9]+:[a-zA-Z0-9]+)(?:\s[^>]*)?>""").find(content, lt)
            if (tagInfo == null || tagInfo.range.first != lt) { pos = lt + 1; continue }
            val closing = tagInfo.groupValues[1].isNotEmpty()
            val tag = tagInfo.groupValues[2]
            if (closing) { pos = content.indexOf('>', lt) + 1; continue }

            when (tag) {
                "text:p" -> { val end = findEnd(content, lt, "text:p"); blocks += parseParagraph(content.substring(lt, end), styles, isHeading = false, level = 1); pos = end }
                "text:h" -> { val end = findEnd(content, lt, "text:h"); val level = attrMap(tagInfo.value)["text:outline-level"]?.toIntOrNull() ?: 1; blocks += parseParagraph(content.substring(lt, end), styles, isHeading = true, level = level); pos = end }
                "text:list" -> { val end = findEnd(content, lt, "text:list"); blocks += parseList(content.substring(lt, end), styles, 0); pos = end }
                "table:table" -> { val end = findNestedEnd(content, lt, "table:table"); parseTable(content.substring(lt, end), styles, blocks); pos = end }
                else -> { val gt = content.indexOf('>', lt); pos = if (gt < 0) content.length else gt + 1 }
            }
        }
    }

    // ------------------------------------------------------------------
    // Paragraph / heading
    // ------------------------------------------------------------------

    private fun parseParagraph(xml: String, styles: Map<String, OdtStyle>, isHeading: Boolean, level: Int): WordBlock {
        val runs = parseRuns(xml, styles, isHeading, if (isHeading) headingFontSize(level) else 12f)
        return if (isHeading) WordBlock.Heading(if (runs.isEmpty()) listOf(WordRun("")) else runs, level.coerceIn(1, 6))
        else WordBlock.Paragraph(runs)
    }

    /**
     * Extract runs from the inner XML of a <text:p>/<text:h>. Handles spans,
     * tabs, line breaks, spaces and plain text.
     */
    private fun parseRuns(xml: String, styles: Map<String, OdtStyle>, isHeading: Boolean, defaultSize: Float): List<WordRun> {
        val inner = innerXml(xml)
        val runs = mutableListOf<WordRun>()
        val sb = StringBuilder()
        var style: OdtStyle? = null
        var pos = 0
        while (pos < inner.length) {
            val lt = inner.indexOf('<', pos)
            if (lt < 0) { sb.append(inner.substring(pos)); break }
            sb.append(inner.substring(pos, lt))
            pos = lt
            val gt = inner.indexOf('>', pos)
            if (gt < 0) { sb.append(inner.substring(pos)); break }
            val tagText = inner.substring(pos, gt + 1)
            val tag = Regex("""</?([a-zA-Z0-9]+):([a-zA-Z0-9]+)""").find(tagText)
            pos = gt + 1
            if (tag == null) continue
            val name = tag.groupValues[2]
            val closing = tagText.startsWith("</")
            when (name) {
                "span" -> {
                    if (!closing) {
                        if (sb.isNotEmpty()) { runs += buildRun(sb.toString(), style, isHeading, defaultSize); sb.setLength(0) }
                        val sn = attrMap(tagText)["text:style-name"]
                        style = sn?.let { styles[it] }
                    } else {
                        if (sb.isNotEmpty()) { runs += buildRun(sb.toString(), style, isHeading, defaultSize); sb.setLength(0) }
                        style = null
                    }
                }
                "tab" -> if (!closing) sb.append('\t')
                "line-break" -> if (!closing) sb.append('\n')
                "s" -> if (!closing) { val c = attrMap(tagText)["text:c"]?.toIntOrNull() ?: 1; repeat(c) { sb.append(' ') } }
                else -> { /* ignore other tags (notes, annotations, bookmarks...) */ }
            }
        }
        if (sb.isNotBlank()) runs += buildRun(sb.toString().trim(), style, isHeading, defaultSize)
        return runs
    }

    private fun buildRun(text: String, style: OdtStyle?, isHeading: Boolean, defaultSize: Float): WordRun =
        WordRun(
            text = text,
            bold = style?.bold == true || isHeading,
            italic = style?.italic == true,
            underline = style?.underline == true,
            fontSize = style?.fontSizePt ?: defaultSize,
        )

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    private fun parseList(xml: String, styles: Map<String, OdtStyle>, level: Int): WordBlock {
        val items = Regex("""<text:list-item[^>]*>(.*?)</text:list-item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
        val texts = items.mapNotNull { m ->
            val inner = m.groupValues[1]
            if (Regex("""<text:list[^>]*>""").containsMatchIn(inner)) null // nested list
            else stripTags(inner).trim().ifEmpty { null }
        }.toList()
        if (texts.isEmpty()) return WordBlock.Paragraph(emptyList())
        return WordBlock.ListItem(listOf(WordRun(texts.first())), level = level, bulleted = true)
    }

    // ------------------------------------------------------------------
    // Table
    // ------------------------------------------------------------------

    private fun parseTable(xml: String, styles: Map<String, OdtStyle>, blocks: MutableList<WordBlock>) {
        val rows = mutableListOf<WordTableRow>()
        val rowRe = Regex("""<table:table-row\b[^>]*>(.*?)</table:table-row>""", RegexOption.DOT_MATCHES_ALL)
        for (rm in rowRe.findAll(xml)) {
            val rowXml = rm.groupValues[1]
            val cells = mutableListOf<WordTableCell>()
            val cellRe = Regex("""<table:table-cell\b[^>]*>(.*?)</table:table-cell>""", RegexOption.DOT_MATCHES_ALL)
            for (cm in cellRe.findAll(rowXml)) {
                val cellXml = cm.groupValues[1]
                val paras = mutableListOf<WordBlock>()
                val pRe = Regex("""<text:p\b[^>]*>(.*?)</text:p>""", RegexOption.DOT_MATCHES_ALL)
                for (pm in pRe.findAll(cellXml)) {
                    paras += WordBlock.Paragraph(parseRuns(pm.groupValues[1], styles, false, 12f))
                }
                if (paras.isEmpty()) paras += WordBlock.Paragraph(emptyList())
                cells += WordTableCell(paras)
            }
            if (cells.isNotEmpty()) rows += WordTableRow(cells, isHeader = rows.isEmpty() && xml.contains("table:table-header-rows"))
        }
        if (rows.size >= 2 || (rows.size == 1 && rows[0].cells.size >= 2)) {
            blocks += WordBlock.Table(rows)
        } else {
            // Single row / narrow table: fall back to paragraph text so nothing is lost.
            for (row in rows) {
                val line = row.cells.joinToString("    ") { c -> c.blocks.joinToString(" ") { b -> (b as? WordBlock.Paragraph)?.runs?.joinToString("") { it.text } ?: "" } }
                blocks += WordBlock.Paragraph(listOf(WordRun(line)))
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun innerXml(xml: String): String {
        val open = Regex("""<[a-zA-Z0-9]+:[ph]\b[^>]*>""").find(xml) ?: return xml
        val close = xml.lastIndexOf("</")
        return if (close > open.range.last) xml.substring(open.range.last + 1, close) else xml.substring(open.range.last + 1)
    }

    private fun stripTags(xml: String): String = Regex("""<[^>]+>""").replace(xml, "").trim()

    private fun attrMap(s: String): Map<String, String> {
        val m = mutableMapOf<String, String>()
        for (mm in Regex("""([a-zA-Z0-9:_-]+)="([^"]*)"""").findAll(s)) {
            m[mm.groupValues[1].substringAfterLast(':')] = mm.groupValues[2]
        }
        return m
    }

    private fun findEnd(xml: String, start: Int, tag: String): Int {
        val open = Regex("""<$tag\b[^>]*>""").find(xml, start) ?: return xml.length
        val close = xml.indexOf("</$tag>", open.range.last)
        return if (close < 0) xml.length else close + tag.length + 3
    }

    private fun findNestedEnd(xml: String, start: Int, tag: String): Int {
        val open = Regex("""<$tag\b[^>]*>"""); val close = "</$tag>"
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
