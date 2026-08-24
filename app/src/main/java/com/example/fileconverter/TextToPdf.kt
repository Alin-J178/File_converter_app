package com.example.fileconverter

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Shared PDF rendering for text-based document formats.
 * Reads plain text and renders onto A4 pages with word wrapping.
 */
internal object TextToPdf {

    private const val PAGE_W = 595  // A4 at 72 dpi
    private const val PAGE_H = 842
    private const val MARGIN = 56f
    private const val FONT_SIZE = 12f

    /** Converts a text-based document (any supported input) to PDF. */
    fun convert(context: Context, uri: Uri, displayName: String, text: String): Uri {
        val document = PdfDocument()
        try {
            var pageIndex = 0
            var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex).create())
            var y = MARGIN

            val paint = Paint().apply {
                textSize = FONT_SIZE * 2 // StaticLayout uses px, PDF uses pt
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
                color = Color.BLACK
            }
            val pageContentWidth = ((PAGE_W - 2 * MARGIN) * 2).toInt() // px

            val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(paint), pageContentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.2f)
                .setIncludePad(true)
                .build()

            var lastBreak = 0
            for (i in 0 until staticLayout.lineCount) {
                val lineBottom = staticLayout.getLineBottom(i).toFloat() / 2f // back to pt
                if (lineBottom + y > PAGE_H - MARGIN && y > MARGIN + 10) {
                    // Draw what we have so far
                    val cropped = StaticLayout.Builder.obtain(
                        text, lastBreak, staticLayout.getLineEnd(i - 1),
                        TextPaint(paint), pageContentWidth
                    ).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.2f).setIncludePad(true).build()
                    page.canvas.save()
                    page.canvas.translate(MARGIN, y)
                    cropped.draw(page.canvas)
                    page.canvas.restore()

                    y = MARGIN
                    lastBreak = staticLayout.getLineStart(i)
                    pageIndex++
                    document.finishPage(page)
                    page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex).create())
                }
            }
            // Draw remaining text
            if (lastBreak < text.length) {
                val remaining = StaticLayout.Builder.obtain(
                    text, lastBreak, text.length,
                    TextPaint(paint), pageContentWidth
                ).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.2f).setIncludePad(true).build()
                page.canvas.save()
                page.canvas.translate(MARGIN, y)
                remaining.draw(page.canvas)
                page.canvas.restore()
            }
            document.finishPage(page)
            return savePdf(context, document, displayName)
        } finally {
            document.close()
        }
    }

    private fun savePdf(context: Context, document: PdfDocument, displayName: String): Uri {
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
            resolver.openOutputStream(uri)?.use { out -> document.writeTo(out) }
                ?: error("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            val file = File(dir, displayName)
            FileOutputStream(file).use { out -> document.writeTo(out) }
            Uri.fromFile(file)
        }
    }
}

/** TXT → PDF */
object TxtToPdf {
    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not read the text file")
        return TextToPdf.convert(context, uri, displayName, text)
    }
}

/** MD (Markdown) → PDF – renders as plain text for now */
object MdToPdf {
    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not read the Markdown file")
        // Strip markdown syntax for a clean text PDF
        val text = raw
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")  // remove headings markers
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")                   // bold
            .replace(Regex("\\*(.+?)\\*"), "$1")                         // italic
            .replace(Regex("`(.+?)`"), "$1")                             // inline code
            .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1")               // links
            .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "• ")  // bullet lists
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")  // numbered lists
        return TextToPdf.convert(context, uri, displayName, text)
    }
}

/** HTML → PDF – strips tags and renders as plain text */
object HtmlToPdf {
    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not read the HTML file")
        // Simple HTML-to-text: strip tags, decode entities
        val text = raw
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
        return TextToPdf.convert(context, uri, displayName, text)
    }
}

/** RTF → PDF – strips RTF control words and renders as plain text */
object RtfToPdf {
    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not read the RTF file")
        val text = stripRtf(raw)
        return TextToPdf.convert(context, uri, displayName, text)
    }

    private fun stripRtf(rtf: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < rtf.length) {
            when {
                rtf[i] == '\\' && i + 1 < rtf.length -> {
                    val next = rtf[i + 1]
                    when (next) {
                        '{', '}', '\\' -> { sb.append(next); i += 2 }
                        '\n', '\r' -> i += 1
                        in 'a'..'z', in 'A'..'Z' -> {
                            // Skip control word + optional digits
                            i += 2
                            while (i < rtf.length && rtf[i].isLetter()) i++
                            // Skip optional space after control word
                            if (i < rtf.length && rtf[i] == ' ') i++
                        }
                        else -> i += 1
                    }
                }
                rtf[i] == '{' -> i++ // group start
                rtf[i] == '}' -> i++ // group end
                rtf[i] == '\u0002' -> i++ // skip picture data marker
                else -> {
                    // Skip ANSI escape sequences like \'XX
                    if (rtf[i] == '\'' && i + 2 < rtf.length) {
                        val hex = rtf.substring(i + 1, i + 3)
                        try {
                            sb.append(hex.toInt(16).toChar())
                        } catch (_: NumberFormatException) {
                            sb.append('?')
                        }
                        i += 3
                    } else {
                        sb.append(rtf[i])
                        i++
                    }
                }
            }
        }
        return sb.toString().replace(Regex("\\r\\n?"), "\n")
    }
}

/** ODT → PDF – extracts text from the content.xml inside the ODT ZIP */
object OdtToPdf {
    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val text = extractTextFromOdt(context, uri)
        return TextToPdf.convert(context, uri, displayName, text)
    }

    private fun extractTextFromOdt(context: Context, uri: Uri): String {
        val sb = StringBuilder()
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open ODT file")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "content.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        // Extract text between <text:p> tags
                        val regex = Regex("<text:p[^>]*>(.*?)</text:p>", RegexOption.DOT_MATCHES_ALL)
                        for (match in regex.findAll(xml)) {
                            val para = match.groupValues[1]
                                .replace(Regex("<[^>]+>"), "") // strip nested tags
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .trim()
                            sb.appendLine(para)
                        }
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return sb.toString().ifEmpty { error("ODT file contains no text content") }
    }
}
