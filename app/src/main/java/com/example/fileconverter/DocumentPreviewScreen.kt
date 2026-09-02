package com.example.fileconverter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* ===== Simple parsers ===== */

object PlainTextParser {
    fun parse(ctx: android.content.Context, uri: Uri): TextDocument {
        val i = ctx.contentResolver.openInputStream(uri) ?: return TextDocument(emptyList())
        val t = i.use { it.bufferedReader().readText() }
        return if (t.isBlank()) TextDocument(listOf(TextBlock("Empty file")))
        else TextDocument(t.lines().chunked(50).map { TextBlock(it.joinToString("\n")) })
    }
}

object MarkdownParser {
    fun parse(ctx: android.content.Context, uri: Uri): TextDocument {
        val i = ctx.contentResolver.openInputStream(uri) ?: return TextDocument(emptyList())
        val t = i.use { it.bufferedReader().readText() }
        return if (t.isBlank()) TextDocument(listOf(TextBlock("Empty file")))
        else TextDocument(t.lines().map { l ->
            when {
                l.startsWith("# ") -> TextBlock(l.removePrefix("# "), isHeading = true, headingLevel = 1)
                l.startsWith("## ") -> TextBlock(l.removePrefix("## "), isHeading = true, headingLevel = 2)
                l.startsWith("### ") -> TextBlock(l.removePrefix("### "), isHeading = true, headingLevel = 3)
                else -> TextBlock(l)
            }
        })
    }
}

object RtfParser {
    fun parse(ctx: android.content.Context, uri: Uri): WordDocument {
        val input = ctx.contentResolver.openInputStream(uri) ?: return WordDocument(emptyList())
        val raw = input.use { it.bufferedReader().readText() }
        if (raw.isBlank()) return WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Empty RTF")))))
        val blocks = mutableListOf<WordBlock>()
        var bold = false; var italic = false; var underline = false
        var inTable = false
        val tableRows = mutableListOf<WordTableRow>()
        var currentRow = mutableListOf<WordTableCell>()
        var currentCell = mutableListOf<WordBlock>()
        var cellSb = StringBuilder()
        val sb = StringBuilder()
        var idx = 0
        while (idx < raw.length) {
            val c = raw[idx]
            when {
                c == '\\' -> {
                    val cmdStart = idx + 1
                    if (cmdStart >= raw.length) break
                    val cmdChar = raw[cmdStart]
                    when {
                        cmdChar == 'b' && (cmdStart + 1 >= raw.length || !raw[cmdStart + 1].isLetter()) -> {
                            if (cmdStart + 1 < raw.length && raw[cmdStart + 1] == '0') { bold = false; idx = cmdStart + 2 } else { bold = true; idx = cmdStart + 1 }
                        }
                        cmdChar == 'i' && (cmdStart + 1 >= raw.length || !raw[cmdStart + 1].isLetter()) -> {
                            if (cmdStart + 1 < raw.length && raw[cmdStart + 1] == '0') { italic = false; idx = cmdStart + 2 } else { italic = true; idx = cmdStart + 1 }
                        }
                        cmdChar == 'u' && (cmdStart + 1 < raw.length && raw[cmdStart + 1].isDigit()) -> {
                            val numEnd = (cmdStart + 1 until raw.length).firstOrNull { !raw[it].isDigit() } ?: raw.length
                            val code = raw.substring(cmdStart + 1, numEnd).toIntOrNull() ?: 0
                            sb.append(if (code in 0x20..0x10FFFF) code.toChar() else '?')
                            idx = if (numEnd < raw.length && raw[numEnd] == ';') numEnd + 1 else numEnd
                        }
                        raw.startsWith("\\tab", cmdStart - 1) -> { sb.append('\t'); idx = cmdStart + 3 }
                        raw.startsWith("\\par", cmdStart - 1) || raw.startsWith("\\pard", cmdStart - 1) -> {
                            val txt = sb.toString().trim()
                            if (inTable) {
                                if (txt.isNotEmpty()) cellSb.appendLine(txt)
                                if (cellSb.isNotEmpty()) currentCell += WordBlock.Paragraph(listOf(WordRun(cellSb.toString().trim())))
                                cellSb = StringBuilder(); flushRtfCell(currentRow, currentCell); currentCell = mutableListOf()
                            } else { if (txt.isNotEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun(txt))); sb.setLength(0) }
                            idx = cmdStart + if (raw.startsWith("\\pard", cmdStart - 1)) 4 else 3
                        }
                        raw.startsWith("\\cell", cmdStart - 1) && inTable -> {
                            val txt = sb.toString().trim(); sb.setLength(0)
                            if (txt.isNotEmpty()) cellSb.append(txt)
                            if (cellSb.isNotEmpty()) currentCell += WordBlock.Paragraph(listOf(WordRun(cellSb.toString().trim())))
                            cellSb = StringBuilder(); flushRtfCell(currentRow, currentCell); currentCell = mutableListOf()
                            idx = cmdStart + 4
                        }
                        raw.startsWith("\\row", cmdStart - 1) && inTable -> {
                            val txt = sb.toString().trim(); sb.setLength(0)
                            if (txt.isNotEmpty()) cellSb.append(txt)
                            if (cellSb.isNotEmpty()) currentCell += WordBlock.Paragraph(listOf(WordRun(cellSb.toString().trim())))
                            cellSb = StringBuilder(); flushRtfCell(currentRow, currentCell); currentCell = mutableListOf()
                            if (currentRow.isNotEmpty()) { tableRows += WordTableRow(currentRow.toList()); currentRow = mutableListOf() }
                            idx = cmdStart + 3
                        }
                        raw.startsWith("\\trowd", cmdStart - 1) -> { inTable = true; idx = cmdStart + 5 }
                        else -> {
                            val end = raw.indexOfAny(charArrayOf(' ', '\\', '{', '}', '\r', '\n'), cmdStart)
                            val cmd = if (end < 0) raw.substring(cmdStart) else raw.substring(cmdStart, end)
                            val numParam = cmd.all { it.isDigit() || it == '-' }
                            idx = if (end < 0) raw.length else end
                            if (cmd == "plain") { bold = false; italic = false; underline = false }
                            else if (numParam && cmd.isNotEmpty() && cmd[0].isDigit()) { /* number parameter consumed */ }
                        }
                    }
                }
                c == '{' -> { idx++; }
                c == '}' -> { idx++; }
                c == '\r' || c == '\n' -> { idx++; }
                else -> { sb.append(c); idx++; }
            }
        }
        if (inTable) { if (currentRow.isNotEmpty()) tableRows += WordTableRow(currentRow.toList()) }
        if (tableRows.size >= 2) {
            val maxCols = tableRows.maxOf { it.cells.size }
            if (maxCols >= 2) blocks += WordBlock.Table(tableRows)
        }
        val txt = sb.toString().trim()
        if (txt.isNotEmpty() && !inTable) blocks += WordBlock.Paragraph(listOf(WordRun(txt)))
        if (blocks.isEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun("Empty RTF")))
        return WordDocument(blocks)
    }

    private fun flushRtfCell(row: MutableList<WordTableCell>, cell: MutableList<WordBlock>) {
        if (cell.isEmpty()) cell += WordBlock.Paragraph(emptyList())
        row += WordTableCell(cell.toList())
    }
}

object HtmlParser {
    fun parse(ctx: android.content.Context, uri: Uri): WordDocument {
        val input = ctx.contentResolver.openInputStream(uri) ?: return WordDocument(emptyList())
        val html = input.use { it.bufferedReader().readText() }
        if (html.isBlank()) return WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Empty HTML")))))
        val blocks = mutableListOf<WordBlock>()
        var pos = 0
        while (pos < html.length) {
            val lt = html.indexOf('<', pos)
            if (lt < 0) { val rest = decodeEntities(html.substring(pos).trim()); if (rest.isNotEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun(rest))); break }
            if (lt > pos) { val txt = decodeEntities(html.substring(pos, lt).trim()); if (txt.isNotEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun(txt))) }
            val gt = html.indexOf('>', lt)
            if (gt < 0) break
            val tagText = html.substring(lt, gt + 1)
            val tagInfo = Regex("""</?([a-zA-Z]+)""").find(tagText) ?: run { pos = gt + 1; continue }
            val tagName = tagInfo.groupValues[1].lowercase()
            val closing = tagText.startsWith("</")
            pos = gt + 1
            when (tagName) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    if (closing) continue
                    val endTag = "</$tagName>"; val end = html.indexOf(endTag, pos, true)
                    val content = if (end < 0) html.substring(pos) else html.substring(pos, end)
                    val runs = extractInlineHtml(decodeEntities(content.trim()))
                    val level = tagName.last().digitToInt()
                    blocks += WordBlock.Heading(runs, level)
                    if (end >= 0) pos = end + endTag.length
                }
                "p" -> {
                    if (closing) continue
                    val end = findHtmlEnd(html, pos, "p")
                    val runs = extractInlineHtml(decodeEntities(html.substring(pos, end).trim()))
                    if (runs.isNotEmpty()) blocks += WordBlock.Paragraph(runs)
                    pos = end
                }
                "table" -> {
                    if (closing) continue
                    val end = findHtmlEnd(html, pos, "table")
                    parseHtmlTable(html.substring(pos, end), blocks)
                    pos = end
                }
                "br" -> { /* handled inside runs */ }
                "img" -> { /* skip images in HTML preview */ }
                else -> { /* skip unknown tags */ }
            }
        }
        if (blocks.isEmpty()) blocks += WordBlock.Paragraph(listOf(WordRun("Empty HTML")))
        return WordDocument(blocks)
    }

    private fun parseHtmlTable(tableHtml: String, blocks: MutableList<WordBlock>) {
        val rows = mutableListOf<WordTableRow>()
        val rowRe = Regex("""<tr\b[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
        for (rm in rowRe.findAll(tableHtml)) {
            val rowHtml = rm.groupValues[1]
            val cells = mutableListOf<WordTableCell>()
            val cellRe = Regex("""<t[hd]\b[^>]*>(.*?)</t[hd]>""", RegexOption.DOT_MATCHES_ALL)
            for (cm in cellRe.findAll(rowHtml)) {
                val cellContent = cm.value
                val isHeader = cellContent.startsWith("<th", ignoreCase = true)
                val runs = extractInlineHtml(decodeEntities(stripOuterTags(cm.groupValues[1]).trim()))
                cells += WordTableCell(listOf(WordBlock.Paragraph(runs)))
            }
            if (cells.isNotEmpty()) rows += WordTableRow(cells, isHeader = rows.isEmpty())
        }
        if (rows.size >= 2 || (rows.size == 1 && rows[0].cells.size >= 2)) {
            blocks += WordBlock.Table(rows)
        }
    }

    private fun extractInlineHtml(text: String): List<WordRun> {
        if (text.isBlank()) return emptyList()
        val runs = mutableListOf<WordRun>()
        var bold = false; var italic = false; var underline = false
        val sb = StringBuilder()
        var pos = 0
        while (pos < text.length) {
            val lt = text.indexOf('<', pos)
            if (lt < 0) { sb.append(text.substring(pos)); break }
            if (lt > pos) sb.append(text.substring(pos, lt))
            val gt = text.indexOf('>', lt)
            if (gt < 0) { sb.append(text.substring(lt)); break }
            val tagText = text.substring(lt, gt + 1)
            pos = gt + 1
            val tag = Regex("""</?([a-zA-Z]+)""").find(tagText) ?: continue
            val name = tag.groupValues[1].lowercase()
            val closing = tagText.startsWith("</")
            when (name) {
                "b", "strong" -> { if (closing) bold = false else { if (sb.isNotEmpty()) { runs += WordRun(sb.toString(), bold, italic, underline); sb.setLength(0) }; bold = true } }
                "i", "em" -> { if (closing) italic = false else { if (sb.isNotEmpty()) { runs += WordRun(sb.toString(), bold, italic, underline); sb.setLength(0) }; italic = true } }
                "u" -> { if (closing) underline = false else { if (sb.isNotEmpty()) { runs += WordRun(sb.toString(), bold, italic, underline); sb.setLength(0) }; underline = true } }
                "br" -> sb.append('\n')
                "img" -> {
                    val alt = Regex("""alt="([^"]*)"""", RegexOption.IGNORE_CASE).find(tagText)?.groupValues?.get(1) ?: ""
                    if (alt.isNotEmpty()) sb.append("[Image: $alt]")
                }
                else -> { /* ignore other inline tags */ }
            }
        }
        if (sb.isNotEmpty()) runs += WordRun(sb.toString(), bold, italic, underline)
        return runs
    }

    private fun decodeEntities(text: String): String = text
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: "" }

    private fun stripOuterTags(text: String): String = Regex("""<[^>]+>""").replace(text, "")

    private fun findHtmlEnd(html: String, start: Int, tag: String): Int {
        val close = "</$tag>"
        var depth = 1; var i = start
        while (i < html.length) {
            val open = Regex("""<$tag\b[^>]*>""", RegexOption.IGNORE_CASE).find(html, i)
            val cl = html.indexOf(close, i, ignoreCase = true)
            val oi = open?.range?.first ?: Int.MAX_VALUE
            if (open != null && oi < cl) { depth++; i = open.range.last + 1 }
            else if (cl >= 0) { depth--; if (depth == 0) return cl + close.length; i = cl + close.length }
            else return html.length
        }
        return html.length
    }
}

object CsvParser {
    fun parse(ctx: android.content.Context, uri: Uri): SpreadsheetDocument {
        val i = ctx.contentResolver.openInputStream(uri) ?: return SpreadsheetDocument(emptyList())
        val r = java.io.BufferedReader(java.io.InputStreamReader(i))
        val ls = r.use { it.readLines() }
        if (ls.isEmpty()) return SpreadsheetDocument(listOf(SpreadsheetSheet("CSV", emptyList())))
        val d = if (ls.first().contains('\t')) '\t' else ','
        val ar = ls.map { pcl(it, d) }.filter { it.any { c -> c.isNotEmpty() } }
        return if (ar.isEmpty()) SpreadsheetDocument(listOf(SpreadsheetSheet("CSV", emptyList())))
        else SpreadsheetDocument(listOf(SpreadsheetSheet("CSV", ar.mapIndexed { ri, row ->
            SpreadsheetRow(cells = row.map { SpreadsheetCell(value = it, bold = ri == 0) })
        })))
    }

    private fun pcl(line: String, d: Char): List<String> {
        val r2 = mutableListOf<String>(); val sb = StringBuilder(); var q = false
        for (c in line) { when { c == '"' -> q = !q; c == d && !q -> { r2.add(sb.toString().trim()); sb.clear() }; else -> sb.append(c) } }
        r2.add(sb.toString().trim()); return r2
    }
}

/* ===== PDF ===== */
private fun renderPdfPages(ctx: android.content.Context, uri: Uri): List<Bitmap> {
    val p = mutableListOf<Bitmap>()
    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        PdfRenderer(pfd).use { r ->
            for (i in 0 until r.pageCount) {
                val pg = r.openPage(i)
                val w = (pg.width * 2f).toInt()
                val h = (pg.height * 2f).toInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pg.close()
                p.add(bmp)
            }
        }
    }
    return p
}

@Composable
private fun renderWordTable(table: WordBlock.Table, colors: AppColors, ac: Color) {
    if (table.rows.isEmpty()) return
    val borderColor = colors.onBackground.copy(alpha = 0.15f)
    val hb = ac.copy(alpha = 0.10f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        for ((ri, row) in table.rows.withIndex()) {
            val bg = when {
                ri == 0 -> hb  // first row as header
                ri % 2 == 1 -> colors.onBackground.copy(alpha = 0.04f)
                else -> colors.background
            }
            Row(modifier = Modifier.background(bg).height(IntrinsicSize.Min)) {
                for ((ci, cell) in row.cells.withIndex()) {
                    val cellBg = if (cell.background != 0) Color(cell.background).copy(alpha = 0.15f) else Color.Transparent
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .defaultMinSize(minWidth = 60.dp)
                            .background(cellBg)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        for (b in cell.blocks) {
                            if (b is WordBlock.Paragraph) {
                                val tx = buildAnnotatedString {
                                    for (r in b.runs) withStyle(
                                        SpanStyle(
                                            fontWeight = if (r.bold || ri == 0) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 11.sp,
                                            color = colors.onBackground
                                        )
                                    ) { append(r.text) }
                                }
                                if (tx.isNotBlank()) Text(
                                    tx, fontSize = 11.sp, lineHeight = 16.sp,
                                    maxLines = 15, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // Cell right border
                    if (ci < row.cells.size - 1) {
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .fillMaxSize()
                                .background(borderColor)
                        )
                    }
                }
            }
            // Row bottom border
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(borderColor)
            )
        }
    }
}

/* ===== Word renderer ===== */
@Composable
fun WordBlockRenderer(block: WordBlock, colors: AppColors, mono: FontFamily, ac: Color, ext: String) {
    when (block) {
        is WordBlock.Paragraph -> {
            if (block.runs.isEmpty() || block.runs.all { it.text.isBlank() }) {
                Spacer(modifier = Modifier.height(8.dp))
                return
            }
            val t = buildAnnotatedString {
                for (r in block.runs) withStyle(
                    SpanStyle(
                        fontWeight = if (r.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (r.italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = when {
                            r.underline -> TextDecoration.Underline
                            r.strikethrough -> TextDecoration.LineThrough
                            else -> null
                        },
                        fontSize = if (r.fontSize > 0) r.fontSize.sp else 13.sp,
                        color = if (r.color != 0) Color(r.color) else colors.onBackground
                    )
                ) { append(r.text) }
            }
            val a = when (block.alignment) {
                DocAlignment.CENTER -> TextAlign.Center
                DocAlignment.RIGHT -> TextAlign.End
                DocAlignment.JUSTIFY -> TextAlign.Justify
                else -> TextAlign.Start
            }
            Text(t, color = colors.onBackground, fontSize = 13.sp, lineHeight = 20.sp, textAlign = a, modifier = Modifier.fillMaxWidth(), fontFamily = if (ext in listOf("csv", "txt", "md", "html", "htm", "rtf")) mono else FontFamily.Default)
        }
        is WordBlock.Heading -> {
            val fs = headingFontSize(block.level).sp
            val fw = when (block.level) { 1 -> FontWeight.Black; 2 -> FontWeight.Bold; else -> FontWeight.SemiBold }
            val t = buildAnnotatedString { for (r in block.runs) withStyle(SpanStyle(fontWeight = fw, fontSize = fs, color = colors.onBackground)) { append(r.text) } }
            Text(t, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), lineHeight = (fs.value + 6).sp)
        }
        is WordBlock.Table -> renderWordTable(block, colors, ac)
        is WordBlock.PageBreak -> Spacer(modifier = Modifier.height(24.dp))
        is WordBlock.EmbeddedImage -> {
            val bmp = remember(block.data) {
                try { BitmapFactory.decodeByteArray(block.data, 0, block.data.size) } catch (_: Exception) { null }
            }
            if (bmp != null) Image(bitmap = bmp.asImageBitmap(), contentDescription = block.alt, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        is WordBlock.ChartBlock -> {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                if (block.title.isNotEmpty()) Text(block.title, color = colors.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val mx = block.series.flatMap { it.values }.maxOrNull() ?: 1.0
                for (s in block.series) {
                    Text(s.name, color = colors.muted, fontSize = 11.sp)
                    Row(modifier = Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.Bottom) {
                        for (v in s.values) {
                            val f = (v / mx).toFloat().coerceIn(0f, 1f)
                            Box(modifier = Modifier.weight(1f).height((f * 18).dp).background(Color(s.color).copy(alpha = 0.7f)))
                        }
                    }
                }
            }
        }
        is WordBlock.ListItem -> {
            Row(modifier = Modifier.padding(start = (block.level * 16).dp)) {
                Text(if (block.bulleted) "\u2022 " else "${block.level + 1}. ", color = colors.muted, fontSize = 13.sp)
                val tx = buildAnnotatedString {
                    for (r in block.runs) withStyle(SpanStyle(fontWeight = if (r.bold) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp, color = colors.onBackground)) { append(r.text) }
                }
                Text(tx, color = colors.onBackground, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        is WordBlock.Unsupported -> {
            Text("[${block.description}]", color = colors.muted, fontSize = 11.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

/* ===== Spreadsheet renderer ===== */
@Composable
fun SpreadsheetRowRenderer(row: SpreadsheetRow, colors: AppColors, ac: Color) {
    val ih = row.cells.firstOrNull()?.bold == true
    val bg = if (ih) ac.copy(alpha = 0.12f) else Color.Transparent
    Row(modifier = Modifier.background(bg).height(IntrinsicSize.Min).horizontalScroll(rememberScrollState())) {
        for (cell in row.cells) {
            Box(modifier = Modifier.defaultMinSize(minWidth = 80.dp).padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(cell.value, fontSize = 11.sp, fontWeight = if (ih) FontWeight.Bold else FontWeight.Normal, color = colors.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Box(modifier = Modifier.width(0.5.dp).fillMaxSize().background(Color(0xFFBDBDBD)))
        }
    }
}

/* ===== Presentation renderer ===== */
@Composable
fun SlideRenderer(slide: PresentationSlide, colors: AppColors) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Slide ${slide.index + 1}", color = colors.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp)).padding(12.dp)) {
            Column {
                for (el in slide.elements) {
                    when (el) {
                        is SlideElement.TextBox -> {
                            val t = buildAnnotatedString {
                                for (r in el.runs) withStyle(SpanStyle(fontWeight = if (r.bold) FontWeight.Bold else FontWeight.Normal, fontSize = el.fontSize.sp, color = colors.onBackground)) { append(r.text) }
                            }
                            Text(t, modifier = Modifier.padding(vertical = 2.dp))
                        }
                        is SlideElement.ImageElement -> {
                            val bmp = remember(el.data) {
                                try { BitmapFactory.decodeByteArray(el.data, 0, el.data.size) } catch (_: Exception) { null }
                            }
                            if (bmp != null) Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        }
                        is SlideElement.Shape -> Text(el.description, color = colors.muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/* ===== Text renderer ===== */
@Composable
fun TextBlockRenderer(block: TextBlock, colors: AppColors, mono: FontFamily) {
    val fw = if (block.isBold) FontWeight.Bold else if (block.isHeading) FontWeight.Bold else FontWeight.Normal
    val fs = if (block.isHeading && block.headingLevel > 0) headingFontSize(block.headingLevel).sp else 13.sp
    val bg = if (block.isCode) Color(0xFFF5F5F5) else Color.Transparent
    val f = if (block.isCode) mono else FontFamily.Default
    Text(
        text = block.text, color = colors.onBackground, fontSize = fs,
        lineHeight = (fs.value + 7).sp, fontWeight = fw, fontFamily = f,
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = if (block.isCode) 8.dp else 0.dp, vertical = if (block.isCode) 4.dp else 0.dp)
    )
}

/* ===== Main composable ===== */
@Composable
fun DocumentPreviewScreen(uri: Uri, fileName: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalAppColors.current
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val mono = FontFamily.Monospace
    val mt = remember(uri) { ctx.contentResolver.getType(uri) ?: "" }

    var doc by remember { mutableStateOf<ParsedDocument?>(null) }
    var bmps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var pg by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var sc by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    val zoomed = sc > 1.05f

    LaunchedEffect(uri, ext) {
        loading = true; err = null; doc = null; bmps = emptyList()
        withContext(Dispatchers.IO) {
            try {
                android.util.Log.e("DOC_DEBUG", "Opening file: ext=$ext, mime=$mt")
                when {
                    ext == "pdf" || mt.contains("pdf") -> bmps = renderPdfPages(ctx, uri)
                    ext == "docx" || mt.contains("wordprocessingml") -> {
                        val wordDoc = DocxParser.parse(ctx, uri)
                        bmps = DocPageRenderer.render(wordDoc)
                    }
                    ext == "doc" || mt.contains("msword") -> {
                        val wordDoc = DocParser.parse(ctx, uri)
                        bmps = DocPageRenderer.render(wordDoc)
                    }
                    ext == "xlsx" || mt.contains("spreadsheetml") -> doc = ParsedDocument.Spreadsheet(XlsxParser.parse(ctx, uri))
                    ext == "xls" -> doc = ParsedDocument.Text(TextDocument(listOf(TextBlock("[XLS not supported]"))))
                    ext == "pptx" || mt.contains("presentationml") -> doc = ParsedDocument.Presentation(PptxParser.parse(ctx, uri))
                    ext == "ppt" || mt.contains("powerpoint") -> doc = ParsedDocument.Word(DocParser.parseLegacyPpt(ctx, uri))
                    ext == "csv" || mt.contains("csv") -> doc = ParsedDocument.Spreadsheet(CsvParser.parse(ctx, uri))
                    ext == "odt" || mt.contains("opendocument.text") -> {
                        val wordDoc = OdtParser.parse(ctx, uri)
                        bmps = DocPageRenderer.render(wordDoc)
                    }
                    ext == "rtf" -> {
                        val wordDoc = RtfParser.parse(ctx, uri)
                        bmps = DocPageRenderer.render(wordDoc)
                    }
                    ext == "txt" || ext == "text" || mt.contains("text/plain") -> doc = ParsedDocument.Text(PlainTextParser.parse(ctx, uri))
                    ext == "md" || ext == "markdown" -> doc = ParsedDocument.Text(MarkdownParser.parse(ctx, uri))
                    ext == "html" || ext == "htm" || mt.contains("html") -> {
                        val wordDoc = HtmlParser.parse(ctx, uri)
                        bmps = DocPageRenderer.render(wordDoc)
                    }
                    else -> doc = ParsedDocument.Text(TextDocument(listOf(TextBlock("Preview not available for .$ext"))))
                }
                if (doc == null && bmps.isEmpty()) err = "Could not read file content"
            } catch (e: Exception) {
                Log.e("DocPreview", "Failed to parse $ext", e)
                err = "Error: ${e.message ?: "Unknown"}"
            }
            loading = false
        }
    }

    val tp = if (bmps.isNotEmpty()) bmps.size else 1
    val ac = Color(docFormatColor(ext))

    Box(modifier = Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(c.background).padding(horizontal = 12.dp, vertical = 8.dp)) {
                NeoIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(fileName, color = c.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Page ${pg + 1} of $tp" + if (zoomed) " \u2022 ${String.format("%.1f", sc)}\u00d7" else "", color = c.muted, fontSize = 12.sp)
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = ac, strokeWidth = 2.dp)
            }
            // Accent line
            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(ac))
            // Content
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer(scaleX = sc, scaleY = sc, translationX = ox, translationY = oy)
                        .pointerInput(zoomed) {
                            detectTransformGestures { cen, pan, zm, _ ->
                                val ns = (sc * zm).coerceIn(1f, 5f)
                                val s2 = ns / sc
                                ox = (ox + pan.x) * s2 + cen.x * (1f - s2)
                                oy = (oy + pan.y) * s2 + cen.y * (1f - s2)
                                sc = ns
                                if (sc <= 1.05f) { sc = 1f; ox = 0f; oy = 0f }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { o ->
                                if (sc > 1.05f) {
                                    sc = 1f; ox = 0f; oy = 0f
                                } else {
                                    sc = 2.5f
                                    ox = (size.width / 2f - o.x) * 1.5f
                                    oy = (size.height / 2f - o.y) * 1.5f
                                }
                            })
                        }
                ) {
                    when {
                        loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(modifier = Modifier.size(48.dp), color = ac, strokeWidth = 4.dp)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Loading preview\u2026", color = c.muted, fontSize = 14.sp)
                                }
                            }
                        }
                        err != null -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(err!!, color = c.pink, fontSize = 14.sp)
                            }
                        }
                        bmps.isNotEmpty() -> {
                            val ls = rememberLazyListState()
                            LazyColumn(
                                state = ls, modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                userScrollEnabled = !zoomed
                            ) {
                                itemsIndexed(bmps) { i, b ->
                                    Image(bitmap = b.asImageBitmap(), contentDescription = "Page ${i + 1}", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White))
                                }
                            }
                            LaunchedEffect(ls.firstVisibleItemIndex) { pg = ls.firstVisibleItemIndex }
                        }
                        doc != null -> {
                            val ls = rememberLazyListState()
                            LazyColumn(
                                state = ls, modifier = Modifier.fillMaxSize().background(Color.White),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                userScrollEnabled = !zoomed
                            ) {
                                when (val d = doc!!) {
                                    is ParsedDocument.Word -> itemsIndexed(d.doc.blocks) { _, b -> WordBlockRenderer(b, c, mono, ac, ext) }
                                    is ParsedDocument.Spreadsheet -> {
                                        item {
                                            Text(d.doc.sheets.firstOrNull()?.name ?: "Sheet", color = ac, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                                        }
                                        itemsIndexed(d.doc.sheets.flatMap { it.rows }) { _, r -> SpreadsheetRowRenderer(r, c, ac) }
                                    }
                                    is ParsedDocument.Presentation -> itemsIndexed(d.doc.slides) { _, s -> SlideRenderer(s, c) }
                                    is ParsedDocument.Text -> itemsIndexed(d.doc.blocks) { _, b -> TextBlockRenderer(b, c, mono) }
                                }
                            }
                            LaunchedEffect(ls.firstVisibleItemIndex) { pg = ls.firstVisibleItemIndex }
                        }
                    }
                }
            }
        }
    }
}
