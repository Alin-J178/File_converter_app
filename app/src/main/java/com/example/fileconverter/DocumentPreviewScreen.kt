package com.example.fileconverter

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Full-screen document preview with page-by-page scrolling.
 * Supports: PDF, DOCX, DOC, XLSX, XLS, PPTX, PPT, CSV, ODT, RTF, TXT, MD, HTML.
 * All parsing is done manually — no Apache POI required.
 */
@Composable
fun DocumentPreviewScreen(
    uri: Uri,
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val monospace = FontFamily.Monospace

    val mimeType = remember(uri) {
        context.contentResolver.getType(uri) ?: ""
    }

    var textPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var bitmapPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var currentPage by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri, ext) {
        isLoading = true
        loadError = null
        textPages = emptyList()
        bitmapPages = emptyList()

        withContext(Dispatchers.IO) {
            try {
                when {
                    ext == "pdf" || mimeType.contains("pdf") ->
                        bitmapPages = renderPdfPages(context, uri)
                    ext == "docx" || mimeType.contains("wordprocessingml") ->
                        textPages = parseDocx(context, uri)
                    ext == "doc" || mimeType.contains("msword") ->
                        textPages = parseDocRaw(context, uri)
                    ext == "xlsx" || mimeType.contains("spreadsheetml") ->
                        textPages = parseXlsx(context, uri)
                    ext == "xls" ->
                        textPages = listOf("[XLS preview not supported — open with external app]")
                    ext == "pptx" || mimeType.contains("presentationml") ->
                        textPages = parsePptx(context, uri)
                    ext == "ppt" || mimeType.contains("powerpoint") ->
                        textPages = parsePptRaw(context, uri)
                    ext == "csv" || mimeType.contains("csv") ->
                        textPages = parseCsvPreview(context, uri)
                    ext == "odt" || mimeType.contains("opendocument.text") ->
                        textPages = parseOdt(context, uri)
                    ext == "rtf" ->
                        textPages = parseRtfPreview(context, uri)
                    ext == "txt" || ext == "text" || mimeType.contains("text/plain") ->
                        textPages = parseTxtPreview(context, uri)
                    ext == "md" || ext == "markdown" ->
                        textPages = parseTxtPreview(context, uri)
                    ext == "html" || ext == "htm" || mimeType.contains("html") ->
                        textPages = parseHtmlPreview(context, uri)
                    else ->
                        textPages = listOf("Preview not available for .$ext")
                }
                if (textPages.isEmpty() && bitmapPages.isEmpty()) {
                    loadError = "Could not read file content"
                }
            } catch (e: Exception) {
                Log.e("DocPreview", "Failed to parse $ext", e)
                loadError = "Error: ${e.message ?: "Unknown error"}"
            }
            isLoading = false
        }
    }

    val totalPages = if (bitmapPages.isNotEmpty()) bitmapPages.size else textPages.size
    val accentColor = when (ext.uppercase()) {
        "PDF" -> colors.pink
        "DOCX", "DOC" -> Color(0xFF2B579A)
        "XLSX", "XLS" -> Color(0xFF217346)
        "PPTX", "PPT" -> Color(0xFFD04423)
        "CSV" -> Color(0xFF00897B)
        "ODT" -> Color(0xFF0066CC)
        "RTF" -> Color(0xFF8B4513)
        "TXT" -> Color(0xFF616161)
        "MD" -> Color(0xFF455A64)
        "HTML", "HTM" -> Color(0xFFE65100)
        else -> colors.muted
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                NeoIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        color = colors.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (totalPages > 0) {
                        Text(
                            text = "Page ${currentPage + 1} of $totalPages",
                            color = colors.muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = accentColor,
                        strokeWidth = 2.dp,
                    )
                }
            }

            // Accent line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accentColor),
            )

            // Content
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = accentColor,
                                strokeWidth = 4.dp,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Loading preview…", color = colors.muted, fontSize = 14.sp)
                        }
                    }
                }
                loadError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(loadError!!, color = colors.pink, fontSize = 14.sp)
                    }
                }
                bitmapPages.isNotEmpty() -> {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(bitmapPages) { index, pageBmp ->
                            ZoomablePage {
                                Image(
                                    bitmap = pageBmp.asImageBitmap(),
                                    contentDescription = "Page ${index + 1}",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                    LaunchedEffect(listState.firstVisibleItemIndex) {
                        currentPage = listState.firstVisibleItemIndex
                    }
                }
                textPages.isNotEmpty() -> {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        itemsIndexed(textPages) { index, pageText ->
                            ZoomablePage {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(colors.surface, RoundedCornerShape(12.dp))
                                        .padding(16.dp),
                                ) {
                                    if (totalPages > 1) {
                                        Text(
                                            text = "— Page ${index + 1} —",
                                            color = accentColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                    }
                                    val isMonospace = ext in listOf("csv", "txt", "md", "html", "htm", "rtf", "xls", "xlsx")
                                    val text = pageText.trimEnd()
                                    if (text.isNotEmpty()) {
                                        Text(
                                            text = text,
                                            color = colors.onBackground,
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                            fontFamily = if (isMonospace) monospace else FontFamily.Default,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(
                                                    if (isMonospace) Modifier.horizontalScroll(rememberScrollState())
                                                    else Modifier
                                                ),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    LaunchedEffect(listState.firstVisibleItemIndex) {
                        currentPage = listState.firstVisibleItemIndex
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  ZoomablePage — pinch-to-zoom + pan + double-tap to reset
// ═══════════════════════════════════════════════════════════════

/**
 * Wraps [content] with pinch-to-zoom, pan, and double-tap-to-reset.
 * Each page gets its own independent zoom state so scrolling the
 * LazyColumn resets zoom for the previous page.
 */
@Composable
private fun ZoomablePage(
    maxZoom: Float = 4f,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Double-tap resets zoom
    val doubleTapReset = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onDoubleTap = {
                // Animate back to 1× (we just snap for simplicity)
                scale = 1f
                offsetX = 0f
                offsetY = 0f
            },
        )
    }

    // Pinch-to-zoom + pan
    val transformModifier = Modifier.pointerInput(Unit) {
        detectTransformGestures { centroid, pan, zoom, _ ->
            val newScale = (scale * zoom).coerceIn(1f, maxZoom)
            val ratio = newScale / scale

            // Zoom toward the centroid
            offsetX = (offsetX + pan.x) * ratio + centroid.x * (1 - ratio)
            offsetY = (offsetY + pan.y) * ratio + centroid.y * (1 - ratio)

            // Clamp pan when zoomed out
            if (newScale <= 1f) {
                offsetX = 0f
                offsetY = 0f
            }

            scale = newScale
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY,
            )
            .then(doubleTapReset)
            .then(transformModifier),
    ) {
        content()
    }
}

// ═══════════════════════════════════════════════════════════════
//  Parsers
// ═══════════════════════════════════════════════════════════════

/** PDF → Bitmap pages via PdfRenderer */
private fun renderPdfPages(context: android.content.Context, uri: Uri): List<Bitmap> {
    val pages = mutableListOf<Bitmap>()
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            val scale = 2f
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val w = (page.width * scale).toInt()
                val h = (page.height * scale).toInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                pages.add(bmp)
            }
        }
    }
    return pages
}

/** DOCX → text pages parsed from word/document.xml in the ZIP */
private fun parseDocx(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val bytes = input.use { it.readBytes() }

    val zipIn = ZipInputStream(ByteArrayInputStream(bytes))
    var documentXml = ""
    var entry = zipIn.nextEntry
    while (entry != null) {
        if (entry.name == "word/document.xml") {
            documentXml = zipIn.bufferedReader().readText()
            break
        }
        entry = zipIn.nextEntry
    }
    zipIn.close()

    if (documentXml.isEmpty()) return listOf("Empty document")

    // Split by paragraph tags and extract text from each
    val tagRegex = Regex("""<[^>]+>""")
    val sections = documentXml.split(Regex("""<w:p[\s>]"""))
    val paragraphs = sections.mapNotNull { section ->
        val text = tagRegex.replace(section, "").trim()
        if (text.isNotEmpty()) text else null
    }

    return if (paragraphs.isEmpty()) listOf("Empty document")
    else paragraphs.chunked(40).map { chunk -> chunk.joinToString("\n\n") }
}

/** DOC (legacy OLE2) → extract readable text from raw bytes */
private fun parseDocRaw(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val bytes = input.use { it.readBytes() }

    // Try to find the WordDocument stream and extract Unicode text
    // Simple heuristic: scan for runs of printable chars / UTF-16LE text
    val text = extractReadableText(bytes)
    return if (text.isBlank()) listOf("Could not extract text from DOC file")
    else text.chunked(2000).map { it }
}

/** XLSX → text pages parsed from the ZIP */
private fun parseXlsx(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val bytes = input.use { it.readBytes() }

    val zipIn = ZipInputStream(ByteArrayInputStream(bytes))
    val zipEntries = mutableMapOf<String, String>()
    var entry = zipIn.nextEntry
    while (entry != null) {
        if (entry.name == "xl/sharedStrings.xml" ||
            entry.name.startsWith("xl/worksheets/sheet") ||
            entry.name == "xl/workbook.xml" ||
            entry.name == "xl/styles.xml"
        ) {
            zipEntries[entry.name] = zipIn.bufferedReader().readText()
        }
        entry = zipIn.nextEntry
    }
    zipIn.close()

    // Parse shared strings
    val sharedStrings = parseSharedStrings(zipEntries["xl/sharedStrings.xml"] ?: "")

    // Parse each worksheet
    val pages = mutableListOf<String>()
    val sheetEntries = zipEntries.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted()
    for (sheetName in sheetEntries) {
        val sheetXml = zipEntries[sheetName] ?: continue
        val sb = StringBuilder()
        val sheetNum = sheetName.replace("xl/worksheets/sheet", "").replace(".xml", "")
        sb.appendLine("═══ Sheet $sheetNum ═══")
        sb.appendLine()

        // Extract rows
        val rowRegex = Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
        val cellRegex = Regex("""<c[^>]*r="([^"]*)"[^>]*>(?:<v>([^<]*)</v>)?</c>""", RegexOption.DOT_MATCHES_ALL)

        for (rowMatch in rowRegex.findAll(sheetXml)) {
            val cells = mutableListOf<String>()
            for (cellMatch in cellRegex.findAll(rowMatch.groupValues[1])) {
                val ref = cellMatch.groupValues[1]
                val value = cellMatch.groupValues[2]
                val cellType = if (cellMatch.value.contains("""t="s"""")) "s" else "n"
                val display = if (cellType == "s" && value.isNotEmpty()) {
                    val idx = value.toIntOrNull() ?: -1
                    if (idx in sharedStrings.indices) sharedStrings[idx] else value
                } else if (value.isNotEmpty()) {
                    // Format number
                    val d = value.toDoubleOrNull()
                    if (d != null && d == d.toLong().toDouble()) d.toLong().toString() else value
                } else {
                    ""
                }
                cells.add(display)
            }
            if (cells.any { it.isNotEmpty() }) {
                sb.appendLine(cells.joinToString(" | ") { if (it.isEmpty()) "—" else it })
            }
        }
        pages.add(sb.toString())
    }

    return if (pages.isEmpty()) listOf("Empty spreadsheet") else pages
}

/** Parse sharedStrings.xml for XLSX */
private fun parseSharedStrings(xml: String): List<String> {
    if (xml.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val tagRegex = Regex("""<[^>]+>""")
    val siRegex = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
    for (match in siRegex.findAll(xml)) {
        val text = tagRegex.replace(match.groupValues[1], "").trim()
        result.add(text)
    }
    return result
}

/** PPTX → text pages parsed from slide XMLs */
private fun parsePptx(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val bytes = input.use { it.readBytes() }

    val zipIn = ZipInputStream(ByteArrayInputStream(bytes))
    val slideXmls = mutableListOf<String>()
    var entry = zipIn.nextEntry
    while (entry != null) {
        if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
            slideXmls.add(zipIn.bufferedReader().readText())
        }
        entry = zipIn.nextEntry
    }
    zipIn.close()

    if (slideXmls.isEmpty()) return listOf("Empty presentation")

    val pages = mutableListOf<String>()
    for ((idx, slideXml) in slideXmls.withIndex()) {
        val sb = StringBuilder()
        sb.appendLine("═══ Slide ${idx + 1} ═══")
        sb.appendLine()

        // Extract text between <a:t> tags (DrawingML text runs)
        val textRegex = Regex("""<a:t>([^<]+)</a:t>""")
        val texts = textRegex.findAll(slideXml).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        if (texts.isNotEmpty()) {
            for (t in texts) {
                sb.appendLine(t)
            }
        } else {
            sb.appendLine("[No text content]")
        }
        pages.add(sb.toString())
    }

    return pages
}

/** PPT (legacy) → extract readable text from raw bytes */
private fun parsePptRaw(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val bytes = input.use { it.readBytes() }

    val text = extractReadableText(bytes)
    return if (text.isBlank()) listOf("Could not extract text from PPT file")
    else text.chunked(2000).map { it }
}

/** CSV → formatted table pages */
private fun parseCsvPreview(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val reader = BufferedReader(InputStreamReader(input))
    val lines = reader.use { it.readLines() }
    if (lines.isEmpty()) return listOf("Empty CSV file")

    val delimiter = if (lines.first().contains('\t')) '\t' else ','
    val rowsPerPage = 30
    val pages = mutableListOf<String>()

    for (chunk in lines.chunked(rowsPerPage)) {
        val sb = StringBuilder()
        for (line in chunk) {
            val cells = parseCsvLine(line, delimiter)
            sb.appendLine(cells.joinToString(" | ") { if (it.isEmpty()) "—" else it })
        }
        pages.add(sb.toString())
    }
    return pages
}

/** Parse a CSV line handling quoted fields */
private fun parseCsvLine(line: String, delimiter: Char): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    for (c in line) {
        when {
            c == '"' -> inQuotes = !inQuotes
            c == delimiter && !inQuotes -> {
                result.add(current.toString().trim())
                current.clear()
            }
            else -> current.append(c)
        }
    }
    result.add(current.toString().trim())
    return result
}

/** ODT → extract text from content.xml */
private fun parseOdt(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val bytes = input.use { it.readBytes() }

    val zipIn = ZipInputStream(ByteArrayInputStream(bytes))
    var contentXml = ""
    var entry = zipIn.nextEntry
    while (entry != null) {
        if (entry.name == "content.xml") {
            contentXml = zipIn.bufferedReader().readText()
            break
        }
        entry = zipIn.nextEntry
    }
    zipIn.close()

    if (contentXml.isEmpty()) return listOf("Empty document")

    // Extract text between <text:p> tags
    val paraRegex = Regex("""<text:p[^>]*>(.*?)</text:p>""", RegexOption.DOT_MATCHES_ALL)
    val tagRegex = Regex("""<[^>]+>""")
    val paragraphs = paraRegex.findAll(contentXml).map { match ->
        tagRegex.replace(match.groupValues[1], "").trim()
    }.filter { it.isNotEmpty() }.toList()

    if (paragraphs.isEmpty()) return listOf("Empty document")
    return paragraphs.chunked(40).map { chunk -> chunk.joinToString("\n\n") }
}

/** RTF → strip control words and show plain text */
private fun parseRtfPreview(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val text = input.use { it.bufferedReader().readText() }

    // Remove RTF header and control sequences
    val cleaned = text
        .replace(Regex("""\{\\rtf1[^}]*\}"""), "")
        .replace(Regex("""\\'[0-9a-fA-F]{2}"""), "")
        .replace(Regex("""\\u\d+;?"""), "")
        .replace(Regex("""\\\w+\s?"""), "")
        .replace(Regex("""\{[^}]*\}"""), "")
        .replace("\\", "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .trim()

    if (cleaned.isEmpty()) return listOf("Empty RTF document")
    return cleaned.chunked(2000).map { it }
}

/** TXT / MD → split into page-sized chunks */
private fun parseTxtPreview(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val text = input.use { it.bufferedReader().readText() }
    if (text.isBlank()) return listOf("Empty file")

    val linesPerPage = 50
    return text.lines().chunked(linesPerPage).map { chunk -> chunk.joinToString("\n") }
}

/** HTML → strip tags and show text */
private fun parseHtmlPreview(context: android.content.Context, uri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val html = input.use { it.bufferedReader().readText() }

    val plain = html
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("""</div>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</h[1-6]>""", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("""</li>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    if (plain.isEmpty()) return listOf("Empty HTML file")

    val linesPerPage = 50
    return plain.lines().chunked(linesPerPage).map { chunk -> chunk.joinToString("\n") }
}

/**
 * Generic extractor for binary files (DOC, PPT): scans the byte array for
 * runs of printable characters and UTF-16LE text to extract readable content.
 */
private fun extractReadableText(bytes: ByteArray): String {
    val result = StringBuilder()

    // Method 1: Look for UTF-16LE text runs (common in Office binary formats)
    val utf16Runs = mutableListOf<String>()
    var currentRun = StringBuilder()
    var i = 0
    while (i < bytes.size - 1) {
        val lo = bytes[i].toInt() and 0xFF
        val hi = bytes[i + 1].toInt() and 0xFF
        // Printable ASCII as UTF-16LE: lo is printable, hi is 0
        if (lo in 0x20..0x7E && hi == 0) {
            currentRun.append(lo.toChar())
        } else if (currentRun.length >= 4) {
            utf16Runs.add(currentRun.toString())
            currentRun = StringBuilder()
        } else {
            currentRun = StringBuilder()
        }
        i += 2
    }
    if (currentRun.length >= 4) utf16Runs.add(currentRun.toString())

    // Deduplicate and sort by length (longest first)
    val uniqueRuns = utf16Runs.distinct().sortedByDescending { it.length }

    // Filter out garbage — keep runs that look like real text
    val realText = uniqueRuns.filter { run ->
        val alphaCount = run.count { it.isLetter() }
        alphaCount > run.length * 0.5 // at least 50% letters
    }

    for (run in realText) {
        result.appendLine(run)
        result.appendLine()
    }

    // Method 2: If we didn't get much, try ASCII scan
    if (result.length < 50) {
        result.clear()
        currentRun = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c == '\n'.code || c == '\r'.code || c == '\t'.code) {
                currentRun.append(c.toChar())
            } else {
                if (currentRun.length >= 8) {
                    result.appendLine(currentRun.toString())
                }
                currentRun = StringBuilder()
            }
        }
        if (currentRun.length >= 8) {
            result.appendLine(currentRun.toString())
        }
    }

    return result.toString().trim()
}
