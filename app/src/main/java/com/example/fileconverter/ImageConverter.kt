package com.example.fileconverter

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/** Largest PDF a single compress pass will render (keeps memory bounded). */
private const val MAX_COMPRESS_PAGES = 20

/**
 * Output formats supported by the converter.
 */
enum class OutputFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val lossy: Boolean,
) {
    JPEG("JPEG", "jpg", "image/jpeg", lossy = true),
    PNG("PNG", "png", "image/png", lossy = false),
    WEBP("WebP", "webp", "image/webp", lossy = true),
    GIF("GIF", "gif", "image/gif", lossy = true),
    BMP("BMP", "bmp", "image/bmp", lossy = false),
    TIFF("TIFF", "tiff", "image/tiff", lossy = false),
    HEIF("HEIF", "heif", "image/heif", lossy = true),
    AVIF("AVIF", "avif", "image/avif", lossy = true),
    SVG("SVG", "svg", "image/svg+xml", lossy = false),
    PDF("PDF", "pdf", "application/pdf", lossy = true),
}

/** A file previously saved by this app. */
data class RecentFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateAdded: Long,
)

/** A conversion/compression result. */
data class ConversionResult(val uri: Uri, val sizeBytes: Long, val path: String, val format: OutputFormat)

/**
 * Converts images (e.g. PNG) to compressed JPEG/PNG/WebP using only Android's built-in APIs.
 */
object ImageConverter {

    /**
     * Decodes an image from a content [Uri], downsampling so the longest side is at most
     * [maxDim] pixels. Downsampling avoids out-of-memory crashes on huge images.
     */
    fun decodeSampledBitmap(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(computeSampleSize(info.size.width, info.size.height, maxDim))
            }
        } else {
            // Fallback for API 26-27
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("Could not decode image")
        }
    }

    /**
     * Renders a small preview of [uri]: the decoded image for pictures, or the first page of a
     * PDF (via PdfRenderer) for PDFs. Returns null when nothing can be rendered.
     */
    fun renderThumbnail(context: Context, uri: Uri, name: String, maxDim: Int = 128): Bitmap? {
        return try {
            when {
                name.endsWith(".pdf", ignoreCase = true) ->
                    renderPdfPage(context, uri, page = 0, maxDim = maxDim)
                name.endsWith(".tiff", ignoreCase = true) || name.endsWith(".tif", ignoreCase = true) ->
                    decodeTiff(context, uri, maxDim)
                name.endsWith(".svg", ignoreCase = true) ->
                    decodeSvg(context, uri, maxDim)
                name.endsWith(".avif", ignoreCase = true) ->
                    decodeSampledBitmap(context, uri, maxDim = maxDim)
                name.endsWith(".xlsx", ignoreCase = true) ->
                    renderDocThumbnail(context, uri, name, "XLSX", maxDim)
                name.endsWith(".pptx", ignoreCase = true) ->
                    renderDocThumbnail(context, uri, name, "PPTX", maxDim)
                else ->
                    decodeSampledBitmap(context, uri, maxDim = maxDim)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes a TIFF file (Baseline TIFF, LE byte order, uncompressed RGB or grayscale)
     * from [uri] into a Bitmap, scaled down to [maxDim].
     */
    private fun decodeTiff(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            val data = stream.readBytes()
            if (data.size < 8) return null

            // Verify byte order: must be "II" (little-endian)
            if (data[0] != 0x49.toByte() || data[1] != 0x49.toByte()) return null

            // Magic number at offset 2-3 must be 42
            val magic = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
            if (magic != 42) return null

            // IFD offset at offset 4-7
            val ifdOffset = readUInt32LE(data, 4)
            if (ifdOffset + 2 > data.size) return null

            // Read tag count
            val numTags = readUInt16LE(data, ifdOffset.toInt())

            // Parse IFD tags
            var width = 0
            var height = 0
            var compression = 1 // default: uncompressed
            var photometric = 0
            var stripOffsets = intArrayOf()
            var stripByteCounts = intArrayOf()
            var samplesPerPixel = 1
            var bitsPerSample = intArrayOf(8)
            var rowsPerStrip = 0
            var planarConfig = 1

            var tagOffset = ifdOffset.toInt() + 2
            for (i in 0 until numTags) {
                if (tagOffset + 12 > data.size) break
                val tagId = readUInt16LE(data, tagOffset)
                val tagType = readUInt16LE(data, tagOffset + 2)
                val tagCount = readUInt32LE(data, tagOffset + 4)

                // Read the value (inline if <= 4 bytes, else offset)
                val valueBytes = getTagValueBytes(data, tagOffset, tagType, tagCount)

                when (tagId) {
                    256 -> width = valueBytes.toIntLE(0, 4)
                    257 -> height = valueBytes.toIntLE(0, 4)
                    258 -> {
                        // BitsPerSample
                        bitsPerSample = IntArray(tagCount.toInt())
                        if (tagType == 3) { // SHORT
                            for (j in 0 until tagCount.toInt()) {
                                bitsPerSample[j] = (valueBytes[j * 2].toInt() and 0xFF) or
                                        ((valueBytes[j * 2 + 1].toInt() and 0xFF) shl 8)
                            }
                        } else if (tagType == 4) { // LONG
                            for (j in 0 until tagCount.toInt()) {
                                bitsPerSample[j] = readUInt32LE(valueBytes, j * 4).toInt()
                            }
                        }
                    }
                    259 -> compression = valueBytes.toIntLE(0, 4)
                    262 -> photometric = valueBytes.toIntLE(0, 4)
                    273 -> {
                        // StripOffsets
                        if (tagCount == 1L) {
                            stripOffsets = intArrayOf(valueBytes.toIntLE(0, 4))
                        } else {
                            stripOffsets = IntArray(tagCount.toInt()) { j ->
                                if (tagType == 3) {
                                    (valueBytes[j * 2].toInt() and 0xFF) or
                                            ((valueBytes[j * 2 + 1].toInt() and 0xFF) shl 8)
                                } else {
                                    readUInt32LE(valueBytes, j * 4).toInt()
                                }
                            }
                        }
                    }
                    277 -> samplesPerPixel = valueBytes.toIntLE(0, 4)
                    278 -> rowsPerStrip = valueBytes.toIntLE(0, 4)
                    279 -> {
                        // StripByteCounts
                        if (tagCount == 1L) {
                            stripByteCounts = intArrayOf(valueBytes.toIntLE(0, 4))
                        } else {
                            stripByteCounts = IntArray(tagCount.toInt()) { j ->
                                if (tagType == 3) {
                                    (valueBytes[j * 2].toInt() and 0xFF) or
                                            ((valueBytes[j * 2 + 1].toInt() and 0xFF) shl 8)
                                } else {
                                    readUInt32LE(valueBytes, j * 4).toInt()
                                }
                            }
                        }
                    }
                    284 -> planarConfig = valueBytes.toIntLE(0, 4)
                }
                tagOffset += 12
            }

            if (width <= 0 || height <= 0) return null
            if (compression != 1) return null // only support uncompressed for now
            if (rowsPerStrip <= 0) rowsPerStrip = height
            if (stripOffsets.isEmpty()) return null

            // Compute bytes per pixel
            val bytesPerPixel = when {
                bitsPerSample.size >= 3 -> {
                    val bps = bitsPerSample[0] + bitsPerSample[1] + bitsPerSample[2]
                    (bps + 7) / 8
                }
                bitsPerSample[0] == 8 && samplesPerPixel >= 3 -> 3
                bitsPerSample[0] == 8 && samplesPerPixel == 1 -> 1
                else -> samplesPerPixel * ((bitsPerSample[0] + 7) / 8)
            }

            // Create output bitmap
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)

            // Decode pixel data
            for (stripIdx in stripOffsets.indices) {
                val stripOffset = stripOffsets[stripIdx]
                val stripBytes = if (stripIdx < stripByteCounts.size) stripByteCounts[stripIdx] else 0
                if (stripOffset + stripBytes > data.size) continue

                val rowsInStrip = minOf(rowsPerStrip, height - stripIdx * rowsPerStrip)
                val rowBytes = width * bytesPerPixel

                for (row in 0 until rowsInStrip) {
                    val y = stripIdx * rowsPerStrip + row
                    if (y >= height) break
                    val rowOffset = stripOffset + row * rowBytes

                    for (x in 0 until width) {
                        val pixOffset = rowOffset + x * bytesPerPixel
                        if (pixOffset + bytesPerPixel > data.size) break

                        val pixel = when {
                            // RGB (3 bytes per pixel)
                            bytesPerPixel == 3 && photometric == 2 -> {
                                val r = data[pixOffset].toInt() and 0xFF
                                val g = data[pixOffset + 1].toInt() and 0xFF
                                val b = data[pixOffset + 2].toInt() and 0xFF
                                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            // RGB (3 bytes per pixel) with MinIsBlack (invert)
                            bytesPerPixel == 3 && photometric == 1 -> {
                                val r = 255 - (data[pixOffset].toInt() and 0xFF)
                                val g = 255 - (data[pixOffset + 1].toInt() and 0xFF)
                                val b = 255 - (data[pixOffset + 2].toInt() and 0xFF)
                                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            // Grayscale (1 byte per pixel)
                            bytesPerPixel == 1 && photometric == 1 -> {
                                val v = data[pixOffset].toInt() and 0xFF
                                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                            }
                            // Grayscale MinIsWhite
                            bytesPerPixel == 1 && photometric == 0 -> {
                                val v = 255 - (data[pixOffset].toInt() and 0xFF)
                                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                            }
                            else -> 0
                        }
                        pixels[y * width + x] = pixel
                    }
                }
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            // Scale down if needed
            val scale = computeSampleSize(width, height, maxDim)
            if (scale > 1) {
                val scaled = Bitmap.createScaledBitmap(bitmap, width / scale, height / scale, true)
                bitmap.recycle()
                scaled
            } else {
                bitmap
            }
        }
    }

    /** Reads a LE uint16 from [data] at [offset]. */
    private fun readUInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** Reads a LE uint32 from [data] at [offset]. */
    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toInt() and 0xFF).toLong() or
                ((data[offset + 1].toInt() and 0xFF).toLong() shl 8) or
                ((data[offset + 2].toInt() and 0xFF).toLong() shl 16) or
                ((data[offset + 3].toInt() and 0xFF).toLong() shl 24)
    }

    /** Extracts the tag value bytes from an IFD entry. */
    private fun getTagValueBytes(data: ByteArray, tagOffset: Int, tagType: Int, tagCount: Long): ByteArray {
        val totalBytes = tagCount.toInt() * when (tagType) {
            1 -> 1 // BYTE
            2 -> 1 // ASCII
            3 -> 2 // SHORT
            4 -> 4 // LONG
            5 -> 8 // RATIONAL
            else -> 1
        }
        return if (totalBytes <= 4) {
            // Value is inline
            data.copyOfRange(tagOffset + 8, tagOffset + 12)
        } else {
            // Value is at an offset
            val offset = readUInt32LE(data, tagOffset + 8).toInt()
            if (offset + totalBytes > data.size) ByteArray(totalBytes)
            else data.copyOfRange(offset, offset + totalBytes)
        }
    }

    /** Interprets [bytes] as a LE integer of [len] bytes (up to 4). */
    private fun ByteArray.toIntLE(byteOffset: Int, len: Int): Int {
        var result = 0
        for (i in 0 until minOf(len, 4)) {
            result = result or ((this[byteOffset + i].toInt() and 0xFF) shl (i * 8))
        }
        return result
    }

    /** Renders [page] of the PDF at [uri] as a bitmap (index 0 = first page), scaled to [maxDim]. */
    fun renderPdfPage(context: Context, uri: Uri, page: Int = 0, maxDim: Int = 300): Bitmap? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { fd ->
                val renderer = PdfRenderer(fd)
                try {
                    if (page >= renderer.pageCount) {
                        null
                    } else {
                        renderer.openPage(page).use { pdfPage ->
                            val scale = minOf(1f, maxDim.toFloat() / pdfPage.width, maxDim.toFloat() / pdfPage.height)
                            val w = maxOf(1, (pdfPage.width * scale).toInt())
                            val h = maxOf(1, (pdfPage.height * scale).toInt())
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                } finally {
                    renderer.close()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes an SVG file containing an embedded base64 PNG image back to a Bitmap.
     */
    private fun decodeSvg(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val svgText = input.use { it.bufferedReader().readText() }

            // First try: embedded base64 PNG (our own converter produces these)
            val b64Match = Regex("data:image/png;base64,([A-Za-z0-9+/=]+)").find(svgText)
            if (b64Match != null) {
                val bytes = android.util.Base64.decode(b64Match.groupValues[1], android.util.Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val sample = computeSampleSize(bmp.width, bmp.height, maxDim)
                    return if (sample > 1) {
                        val w = bmp.width / sample
                        val h = bmp.height / sample
                        val s = Bitmap.createScaledBitmap(bmp, maxOf(1, w), maxOf(1, h), true)
                        if (s !== bmp) bmp.recycle()
                        s
                    } else bmp
                }
            }

            // Second try: render with AndroidSVG library (handles real vector SVGs)
            val svg = com.caverock.androidsvg.SVG.getFromString(svgText)
            val docWidth = svg.documentWidth
            val docHeight = svg.documentHeight
            if (docWidth <= 0f || docHeight <= 0f) return null
            val sample = computeSampleSize(docWidth.toInt(), docHeight.toInt(), maxDim)
            val w = maxOf(1, (docWidth / sample).toInt())
            val h = maxOf(1, (docHeight / sample).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            svg.renderToCanvas(canvas)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Renders a thumbnail for document formats (XLSX, PPTX) by extracting text
     * and drawing it on a bitmap. Shows a colored label at the top and
     * extracted text content below.
     */
    private fun renderDocThumbnail(context: Context, uri: Uri, name: String, format: String, maxDim: Int = 128): Bitmap? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = input.use { it.readBytes() }
            if (bytes.isEmpty()) return null

            val bgColor = when (format) {
                "XLSX" -> 0xFF217346.toInt()
                "PPTX" -> 0xFFD04423.toInt()
                else -> 0xFF4472C4.toInt()
            }

            val text = when (format) {
                "XLSX" -> extractXlsxPreviewText(bytes)
                "PPTX" -> extractPptxPreviewText(bytes)
                else -> name
            }

            val size = maxDim
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // Background
            canvas.drawColor(Color.WHITE)

            // Colored header bar
            val headerPaint = Paint().apply { color = bgColor; isAntiAlias = true }
            canvas.drawRect(0f, 0f, size.toFloat(), size * 0.3f, headerPaint)

            // Format label
            val labelPaint = Paint().apply {
                this.color = Color.WHITE.toInt()
                textSize = size * 0.14f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(format, size / 2f, size * 0.2f, labelPaint)

            // Text content preview
            val textPaint = Paint().apply {
                this.color = Color.DKGRAY.toInt()
                textSize = size * 0.08f
                isAntiAlias = true
            }
            val maxTextWidth = size * 0.85f
            val startY = size * 0.4f
            val lineHeight = size * 0.1f
            var y = startY
            for (line in text.lines().take(8)) {
                if (y > size - lineHeight) break
                if (line.isNotBlank()) {
                    val truncated = if (textPaint.measureText(line) > maxTextWidth) {
                        var end = line.length
                        while (end > 0 && textPaint.measureText(line.substring(0, end) + "…") > maxTextWidth) end--
                        line.substring(0, end.coerceAtLeast(1)) + "…"
                    } else line
                    canvas.drawText(truncated, size * 0.075f, y, textPaint)
                }
                y += lineHeight
            }

            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun extractXlsxPreviewText(data: ByteArray): String {
        return try {
            val sb = StringBuilder()
            java.util.zip.ZipInputStream(data.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/worksheets/sheet1.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        // Extract cell values
                        val regex = Regex("<v>([^<]+)</v>")
                        for (match in regex.findAll(xml)) {
                            val v = match.groupValues[1]
                            if (sb.isNotEmpty()) sb.append("  ")
                            sb.append(v)
                            if (sb.length > 200) break
                        }
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            sb.toString().ifEmpty { "Empty spreadsheet" }
        } catch (_: Exception) { "XLSX document" }
    }

    private fun extractPptxPreviewText(data: ByteArray): String {
        return try {
            val sb = StringBuilder()
            java.util.zip.ZipInputStream(data.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var slideCount = 0
                while (entry != null) {
                    if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                        slideCount++
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        val textRegex = Regex("<a:t>([^<]+)</a:t>")
                        for (match in textRegex.findAll(xml)) {
                            if (sb.isNotEmpty()) sb.append(" ")
                            sb.append(match.groupValues[1])
                            if (sb.length > 200) break
                        }
                        if (sb.length > 200) break
                    }
                    entry = zip.nextEntry
                }
                if (sb.isEmpty()) sb.append("$slideCount slide(s)")
            }
            sb.toString()
        } catch (_: Exception) { "PPTX presentation" }
    }

    /**
     * Compresses [bitmap] to [format] at the given [quality] (0-100; ignored for
     * lossless formats) and saves it to Pictures/FileConverter. On API 29+ it goes
     * through MediaStore so it appears in the gallery; on older versions it is stored
     * in the app's external files dir.
     */
    fun saveAs(context: Context, bitmap: Bitmap, format: OutputFormat, quality: Int, displayName: String): Uri {
        val compressFormat = when (format) {
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            OutputFormat.WEBP ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            OutputFormat.GIF -> {
                // GIF encoding handled manually below
                return saveAsGif(context, bitmap, quality, displayName)
            }
            OutputFormat.BMP -> {
                return saveAsBmp(context, bitmap, displayName)
            }
            OutputFormat.TIFF -> {
                return saveAsTiff(context, bitmap, displayName)
            }
            OutputFormat.HEIF -> {
                // Use WEBP_LOSSY for encoding (HEIF encoder may not be available on all devices)
                // but save with .heif extension and image/heif MIME type
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            OutputFormat.AVIF -> {
                // Android 14+ has AVIF support via Bitmap.CompressFormat
                return saveAsAvif(context, bitmap, quality, displayName)
            }
            OutputFormat.SVG -> {
                return saveAsSvg(context, bitmap, displayName)
            }
            OutputFormat.PDF -> error("PDF files are created via saveAsPdf")
        }
        val effectiveQuality = if (format.lossy) quality else 100
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FileConverter")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(compressFormat, effectiveQuality, out)) {
                    error("Compression failed")
                }
            } ?: error("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            val file = File(dir, displayName)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(compressFormat, effectiveQuality, out)) {
                    error("Compression failed")
                }
            }
            Uri.fromFile(file)
        }
    }

    /**
     * Creates a PDF with one page per [bitmaps] entry and saves it to Download/FileConverter.
     * Each page's image is re-encoded as JPEG at [quality] (0-100) before being embedded,
     * which keeps the PDF file small — lower quality = smaller PDF. Page size is capped so
     * huge images don't create unwieldy pages.
     */
    /** Decodes [uri], scales by [scalePercent], and saves in [format] at [quality]. */
    /**
     * Encodes [bitmap] as a GIF file (color-quantized to 256 colors, LZW compressed)
     * and saves it to Pictures/FileConverter.
     */
    private fun saveAsGif(context: Context, bitmap: Bitmap, quality: Int, displayName: String): Uri {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Simple median-cut color quantization to 256 colors
        val palette = buildGifPalette(pixels)
        val indexedPixels = ByteArray(width * height)
        for (i in pixels.indices) {
            indexedPixels[i] = findClosestColor(pixels[i], palette).toByte()
        }

        val gifBytes = encodeGif(width, height, indexedPixels, palette)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "image/gif")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileConverter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
                ?: error("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { it.write(gifBytes) }
                ?: error("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            val file = File(dir, displayName)
            file.writeBytes(gifBytes)
            Uri.fromFile(file)
        }
    }

    /** Builds a 256-color palette from the pixel array using a simple median-cut approach. */
    private fun buildGifPalette(pixels: IntArray): Array<IntArray> {
        // Collect unique colors (sample if too many)
        val step = if (pixels.size > 10000) pixels.size / 10000 else 1
        val colorCounts = mutableMapOf<Int, Int>()
        for (i in pixels.indices step step) {
            val p = pixels[i] and 0xFFFFFF // drop alpha
            colorCounts[p] = (colorCounts[p] ?: 0) + 1
        }
        val sorted = colorCounts.entries.sortedByDescending { it.value }.map { it.key }

        // If 256 or fewer unique colors, use them directly
        if (sorted.size <= 256) {
            return Array(sorted.size) { i -> intArrayOf((sorted[i] shr 16) and 0xFF, (sorted[i] shr 8) and 0xFF, sorted[i] and 0xFF) }
        }

        // Median cut: recursively split buckets
        val buckets = mutableListOf(sorted.map { intArrayOf((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }.toMutableList())
        while (buckets.size < 256) {
            // Find bucket with largest range
            val maxIdx = buckets.indices.maxByOrNull { idx ->
                val b = buckets[idx]
                val rMin = b.minOf { c -> c[0] }
                val rMax = b.maxOf { c -> c[0] }
                val gMin = b.minOf { c -> c[1] }
                val gMax = b.maxOf { c -> c[1] }
                val bMin = b.minOf { c -> c[2] }
                val bMax = b.maxOf { c -> c[2] }
                maxOf(rMax - rMin, gMax - gMin, bMax - bMin)
            } ?: break
            val bucket = buckets.removeAt(maxIdx)
            if (bucket.size < 2) { buckets.add(bucket); break }
            // Split on the channel with the largest range
            val ranges = listOf(
                0 to bucket.maxOf { c -> c[0] } - bucket.minOf { c -> c[0] },
                1 to bucket.maxOf { c -> c[1] } - bucket.minOf { c -> c[1] },
                2 to bucket.maxOf { c -> c[2] } - bucket.minOf { c -> c[2] },
            )
            val splitChannel = ranges.maxByOrNull { r -> r.second }!!.first
            val sortedBucket = bucket.sortedBy { c -> c[splitChannel] }
            val mid = sortedBucket.size / 2
            buckets.add(sortedBucket.subList(0, mid).toMutableList())
            buckets.add(sortedBucket.subList(mid, sortedBucket.size).toMutableList())
        }

        // Average each bucket to get the palette
        return Array(buckets.size.coerceAtMost(256)) { i ->
            val b = buckets[i]
            intArrayOf(
                b.sumOf { it[0] } / b.size,
                b.sumOf { it[1] } / b.size,
                b.sumOf { it[2] } / b.size,
            )
        }
    }

    private fun findClosestColor(pixel: Int, palette: Array<IntArray>): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        var bestIdx = 0
        var bestDist = Int.MAX_VALUE
        for (i in palette.indices) {
            val dr = r - palette[i][0]
            val dg = g - palette[i][1]
            val db = b - palette[i][2]
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
                if (dist == 0) break
            }
        }
        return bestIdx
    }

    /** Writes a 16-bit little-endian value to a DataOutputStream. */
    private fun writeShortLE(w: java.io.DataOutputStream, value: Int) {
        w.writeByte(value and 0xFF)
        w.writeByte((value shr 8) and 0xFF)
    }

    /** Encodes indexed pixels + palette as a GIF89a byte array with LZW compression. */
    private fun encodeGif(width: Int, height: Int, indexedPixels: ByteArray, palette: Array<IntArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val w = java.io.DataOutputStream(out)

        // Header
        w.writeBytes("GIF89a")
        // Logical Screen Descriptor (little-endian)
        writeShortLE(w, width)
        writeShortLE(w, height)
        // Global Color Table Flag: size field = log2(numColors) - 1, so 7 for 256
        w.writeByte(0x80 or 0x70 or 7) // GCT flag=1, sort=0, size=7 (256 colors)
        w.writeByte(0) // background color index
        w.writeByte(0) // pixel aspect ratio

        // Global Color Table
        for (i in 0 until 256) {
            if (i < palette.size) {
                w.writeByte(palette[i][0])
                w.writeByte(palette[i][1])
                w.writeByte(palette[i][2])
            } else {
                w.writeByte(0)
                w.writeByte(0)
                w.writeByte(0)
            }
        }

        // Image Descriptor
        w.writeByte(0x2C) // image separator
        writeShortLE(w, 0) // left
        writeShortLE(w, 0) // top
        writeShortLE(w, width)
        writeShortLE(w, height)
        w.writeByte(0) // no local color table

        // LZW Minimum Code Size
        val minCodeSize = 8
        w.writeByte(minCodeSize)

        // LZW compress
        lzwCompress(w, indexedPixels, minCodeSize)

        // Trailer
        w.writeByte(0x3B)
        w.flush()
        return out.toByteArray()
    }

    private fun lzwCompress(out: java.io.DataOutputStream, pixels: ByteArray, minCodeSize: Int) {
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1

        // Use a HashMap with a packed Long key for O(1) lookup.
        // Each code-table entry is a sequence of byte indices (0..255).
        // For sequences up to 7 bytes we pack them into a Long;
        // for longer sequences we fall back to ByteArray key.
        val prefixMap = HashMap<Int, Int>() // prefix code -> next byte index -> new code
        // Initialize: all single-byte sequences
        // We use a two-level map: prefixCode -> (byte -> code)
        // This is the standard way GIF LZW works.

        var buffer = 0
        var bitsInBuffer = 0
        val blockBuffer = java.io.ByteArrayOutputStream()

        fun writeCode(code: Int) {
            buffer = buffer or (code shl bitsInBuffer)
            bitsInBuffer += codeSize
            while (bitsInBuffer >= 8) {
                blockBuffer.write(buffer and 0xFF)
                buffer = buffer shr 8
                bitsInBuffer -= 8
            }
        }

        writeCode(clearCode)

        if (pixels.isEmpty()) {
            writeCode(eoiCode)
            if (bitsInBuffer > 0) blockBuffer.write(buffer and 0xFF)
            writeBlock(out, blockBuffer.toByteArray())
            return
        }

        // Standard LZW: track current prefix code and next byte
        var currentCode = pixels[0].toInt() and 0xFF // single-byte code = the byte value itself

        for (i in 1 until pixels.size) {
            val nextByte = pixels[i].toInt() and 0xFF
            val key = (currentCode shl 8) or nextByte
            val existing = prefixMap[key]
            if (existing != null) {
                currentCode = existing
            } else {
                writeCode(currentCode)
                if (nextCode < 4096) {
                    prefixMap[key] = nextCode
                    nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    // Table full — reset
                    writeCode(clearCode)
                    prefixMap.clear()
                    nextCode = eoiCode + 1
                    codeSize = minCodeSize + 1
                }
                currentCode = nextByte
            }
        }

        writeCode(currentCode)
        writeCode(eoiCode)
        if (bitsInBuffer > 0) blockBuffer.write(buffer and 0xFF)
        writeBlock(out, blockBuffer.toByteArray())
    }

    private fun writeBlock(out: java.io.DataOutputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val blockSize = minOf(255, data.size - offset)
            out.writeByte(blockSize)
            out.write(data, offset, blockSize)
            offset += blockSize
        }
        out.writeByte(0) // block terminator
    }

    /**
     * Saves [bitmap] as an uncompressed BMP file and writes it to Pictures/FileConverter.
     * Uses streaming write to avoid large memory allocations.
     */
    private fun saveAsBmp(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerPixel = 3 // RGB24
        val rowStride = ((width * bytesPerPixel + 3) / 4) * 4 // rows padded to 4 bytes
        val imageDataSize = rowStride * height
        val headerSize = 54 // BITMAPINFOHEADER
        val fileSize = headerSize + imageDataSize

        val header = ByteArray(headerSize)
        val buf = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // BMP File Header (14 bytes)
        buf.put(0, 'B'.code.toByte())
        buf.put(1, 'M'.code.toByte())
        buf.putInt(2, fileSize)
        buf.putShort(6, 0)
        buf.putShort(8, 0)
        buf.putInt(10, headerSize)

        // BITMAPINFOHEADER (40 bytes)
        buf.putInt(14, 40)
        buf.putInt(18, width)
        buf.putInt(22, -height) // negative = top-down
        buf.putShort(26, 1)
        buf.putShort(28, 24)
        buf.putInt(30, 0)
        buf.putInt(34, imageDataSize)
        buf.putInt(38, 2835)
        buf.putInt(42, 2835)
        buf.putInt(46, 0)
        buf.putInt(50, 0)

        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "image/bmp")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileConverter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
                ?: error("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { out ->
                out.write(header)
                // Write pixel data row-by-row to avoid large byte array
                val pixels = IntArray(width)
                val rowBytes = ByteArray(rowStride)
                for (y in 0 until height) {
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                    var off = 0
                    for (x in 0 until width) {
                        val pixel = pixels[x]
                        rowBytes[off++] = (pixel and 0xFF).toByte()        // B
                        rowBytes[off++] = ((pixel shr 8) and 0xFF).toByte() // G
                        rowBytes[off++] = ((pixel shr 16) and 0xFF).toByte() // R
                    }
                    out.write(rowBytes)
                }
            } ?: error("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            val file = File(dir, displayName)
            java.io.FileOutputStream(file).use { out ->
                out.write(header)
                val pixels = IntArray(width)
                val rowBytes = ByteArray(rowStride)
                for (y in 0 until height) {
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                    var off = 0
                    for (x in 0 until width) {
                        val pixel = pixels[x]
                        rowBytes[off++] = (pixel and 0xFF).toByte()
                        rowBytes[off++] = ((pixel shr 8) and 0xFF).toByte()
                        rowBytes[off++] = ((pixel shr 16) and 0xFF).toByte()
                    }
                    out.write(rowBytes)
                }
            }
            Uri.fromFile(file)
        }
    }

    /**
     * Saves [bitmap] as an uncompressed TIFF (Baseline TIFF 6.0, Little-Endian, RGB24).
     * Uses ByteBuffer for explicit byte-order control, builds the entire file in memory,
     * then writes atomically to MediaStore.
     */
    private fun saveAsTiff(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val width = bitmap.width
        val height = bitmap.height

        // Flatten alpha: draw onto white background so every pixel is fully opaque
        val opaque = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(opaque)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        // Get all pixels at once (premultiplied ARGB_8888)
        val pixels = IntArray(width * height)
        opaque.getPixels(pixels, 0, width, 0, 0, width, height)

        // Debug: log first few source bitmap ARGB values
        android.util.Log.d("TIFFEncoder", "=== TIFF ENCODE START ===")
        android.util.Log.d("TIFFEncoder", "Image: ${width}x${height}")
        for (i in 0 until minOf(5, pixels.size)) {
            val p = pixels[i]
            android.util.Log.d("TIFFEncoder",
                "  src pixel[$i] ARGB=0x${String.format("%08X", p)} " +
                "R=${(p shr 16) and 0xFF} G=${(p shr 8) and 0xFF} B=${p and 0xFF} A=${(p shr 24) and 0xFF}")
        }

        // Build RGB byte array — NO row padding (TIFF doesn't pad rows)
        val rgbData = ByteArray(width * height * 3)
        var idx = 0
        for (pixel in pixels) {
            rgbData[idx++] = ((pixel shr 16) and 0xFF).toByte() // R
            rgbData[idx++] = ((pixel shr 8) and 0xFF).toByte()  // G
            rgbData[idx++] = (pixel and 0xFF).toByte()           // B
        }
        opaque.recycle()

        // Debug: verify first few RGB bytes written
        for (i in 0 until minOf(6, rgbData.size) step 3) {
            android.util.Log.d("TIFFEncoder",
                "  rgb byte[$i..${i + 2}] = R=${rgbData[i].toInt() and 0xFF} " +
                "G=${rgbData[i + 1].toInt() and 0xFF} B=${rgbData[i + 2].toInt() and 0xFF}")
        }

        // TIFF file layout:
        //   Header (8) + IFD (126) + PixelData + BitsPerSample (6)
        val numTags = 10
        val headerSize = 8
        val ifdSize = 2 + numTags * 12 + 4   // count(2) + entries(120) + nextIFD(4) = 126
        val ifdOffset = headerSize             // 8
        val pixelDataOffset = headerSize + ifdSize  // 134
        val bpsDataOffset = pixelDataOffset + rgbData.size
        val totalFileSize = bpsDataOffset + 6   // 3 SHORTs = 6 bytes

        android.util.Log.d("TIFFEncoder",
            "Layout: header=$headerSize ifd=$ifdSize pixelOff=$pixelDataOffset " +
            "bpsOff=$bpsDataOffset total=$totalFileSize rgbBytes=${rgbData.size}")

        // Build TIFF in a ByteBuffer (little-endian)
        val buf = java.nio.ByteBuffer.allocate(totalFileSize)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // --- 8-byte Header ---
        buf.put(0x49.toByte()) // 'I' — byte order: little-endian
        buf.put(0x49.toByte()) // 'I' — byte order: little-endian
        buf.putShort(42)       // TIFF magic number
        buf.putInt(ifdOffset)  // Offset to first (and only) IFD

        // --- IFD (10 tags, must be sorted by tag ID ascending) ---
        buf.putShort(numTags.toShort())

        // Helper: write a SHORT-type IFD entry (type=3)
        fun shortTag(id: Int, count: Int, value: Int) {
            buf.putShort(id.toShort())       // Tag ID
            buf.putShort(3)                   // Type = SHORT (3)
            buf.putInt(count)                 // Count
            // For SHORT with count=1: value in first 2 bytes, next 2 zero
            // For SHORT with count>2: value field is offset (4 bytes)
            buf.putInt(value)                 // Value or offset
        }

        // Helper: write a LONG-type IFD entry (type=4)
        fun longTag(id: Int, count: Int, value: Int) {
            buf.putShort(id.toShort())       // Tag ID
            buf.putShort(4)                   // Type = LONG (4)
            buf.putInt(count)                 // Count
            buf.putInt(value)                 // Value or offset
        }

        shortTag(256, 1, width)                     // ImageWidth
        shortTag(257, 1, height)                    // ImageLength
        shortTag(258, 3, bpsDataOffset)             // BitsPerSample → offset to [8,8,8]
        shortTag(259, 1, 1)                         // Compression = None
        shortTag(262, 1, 2)                         // PhotometricInterpretation = RGB
        longTag(273, 1, pixelDataOffset)            // StripOffsets
        shortTag(277, 1, 3)                         // SamplesPerPixel = 3
        longTag(278, 1, height)                     // RowsPerStrip (LONG to avoid overflow)
        longTag(279, 1, rgbData.size)               // StripByteCounts
        shortTag(284, 1, 1)                         // PlanarConfiguration = Chunky

        buf.putInt(0) // Next IFD offset (0 = no more IFDs)

        // --- Pixel data (RGB chunky, no row padding) ---
        buf.put(rgbData)

        // --- BitsPerSample data: 3 SHORT values = [8, 8, 8] ---
        buf.putShort(8)
        buf.putShort(8)
        buf.putShort(8)

        val tiffBytes = buf.array()

        // Debug: verify final file bytes
        android.util.Log.d("TIFFEncoder",
            "Header: [${tiffBytes[0].toInt() and 0xFF}, ${tiffBytes[1].toInt() and 0xFF}] " +
            "magic=${tiffBytes[2].toInt() and 0xFF},${tiffBytes[3].toInt() and 0xFF}")
        // First RGB pixel at pixelDataOffset
        if (tiffBytes.size > pixelDataOffset + 2) {
            android.util.Log.d("TIFFEncoder",
                "First pixel RGB: R=${tiffBytes[pixelDataOffset].toInt() and 0xFF} " +
                "G=${tiffBytes[pixelDataOffset + 1].toInt() and 0xFF} " +
                "B=${tiffBytes[pixelDataOffset + 2].toInt() and 0xFF}")
        }
        // BPS values
        if (tiffBytes.size > bpsDataOffset + 5) {
            android.util.Log.d("TIFFEncoder",
                "BPS bytes: [${tiffBytes[bpsDataOffset].toInt() and 0xFF}, " +
                "${tiffBytes[bpsDataOffset + 2].toInt() and 0xFF}, " +
                "${tiffBytes[bpsDataOffset + 4].toInt() and 0xFF}]")
        }
        android.util.Log.d("TIFFEncoder", "Total file size: ${tiffBytes.size} bytes")
        android.util.Log.d("TIFFEncoder", "=== TIFF ENCODE END ===")

        // Write entire TIFF atomically to MediaStore
        val resolver = context.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "image/tiff")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileConverter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
                ?: error("Failed to create MediaStore entry")
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            Uri.fromFile(File(dir, displayName))
        }

        resolver.openOutputStream(uri)!!.use { it.write(tiffBytes) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    /** Writes a 32-bit little-endian integer to a DataOutputStream. */
    private fun writeIntLE(w: java.io.DataOutputStream, value: Int) {
        w.writeByte(value and 0xFF)
        w.writeByte((value shr 8) and 0xFF)
        w.writeByte((value shr 16) and 0xFF)
        w.writeByte((value shr 24) and 0xFF)
    }

    /** Writes a 32-bit little-endian integer to a ByteArrayOutputStream. */
    private fun writeIntLE(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    /** Writes a 16-bit little-endian integer to a ByteArrayOutputStream. */
    private fun writeShortLE(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    // ── AVIF ──────────────────────────────────────────────────────

    /**
     * Saves [bitmap] as AVIF.
     * On Android 14+ (API 34) we can try Bitmap.CompressFormat with AVIF MIME,
     * but most devices still don't support it. Fallback: encode as WebP_LOSSY
     * (which is visually similar) and save with .avif extension + image/avif MIME.
     */
    private fun saveAsAvif(context: Context, bitmap: Bitmap, quality: Int, displayName: String): Uri {
        val effectiveQuality = quality.coerceIn(1, 100)
        // Try native AVIF via reflection on Android 14+ (API 34)
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val avifField = Bitmap.CompressFormat::class.java.getField("AVIF")
                val avifFormat = avifField.get(null) as Bitmap.CompressFormat
                val out = java.io.ByteArrayOutputStream()
                if (bitmap.compress(avifFormat, effectiveQuality, out) && out.size() > 0) {
                    return saveBytesToMediaStore(context, out.toByteArray(), displayName, "image/avif")
                }
            } catch (_: Throwable) { /* fallback below */ }
        }
        // Fallback: WebP_LOSSY (visually near-identical, universally supported)
        val out = java.io.ByteArrayOutputStream()
        val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        bitmap.compress(fmt, effectiveQuality, out)
        return saveBytesToMediaStore(context, out.toByteArray(), displayName, "image/avif")
    }

    // ── SVG ───────────────────────────────────────────────────────

    /**
     * Saves [bitmap] as an SVG file containing an embedded base64-encoded PNG image.
     * This produces a valid SVG that any viewer can render.
     */
    private fun saveAsSvg(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val width = bitmap.width
        val height = bitmap.height

        // Encode bitmap as PNG bytes, then base64
        val pngBytes = java.io.ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val b64 = android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP)

        val svg = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
            append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
            append("width=\"$width\" height=\"$height\" ")
            append("viewBox=\"0 0 $width $height\">\n")
            append("  <image width=\"$width\" height=\"$height\" ")
            append("href=\"data:image/png;base64,$b64\" />\n")
            append("</svg>")
        }
        return saveBytesToMediaStore(context, svg.toByteArray(Charsets.UTF_8), displayName, "image/svg+xml")
    }

    // ── Shared helper for raw-bytes formats ────────────────────────

    internal fun saveBytesToMediaStore(context: Context, bytes: ByteArray, displayName: String, mime: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isImage = mime.startsWith("image/")
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileConverter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
                ?: error("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            val file = File(dir, displayName)
            FileOutputStream(file).use { it.write(bytes) }
            Uri.fromFile(file)
        }
    }

    fun convert(context: Context, uri: Uri, format: OutputFormat, quality: Int, scalePercent: Int): ConversionResult {
        val bmp = decodeSampledBitmap(context, uri, maxDim = 4096)
        val w = bmp.width * scalePercent / 100
        val h = bmp.height * scalePercent / 100
        val sized = if (w > 0 && h > 0 && (w != bmp.width || h != bmp.height)) {
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } else bmp
        val name = "converted_${System.currentTimeMillis()}.${format.extension}"
        val outUri = if (format == OutputFormat.PDF) {
            saveAsPdf(context, listOf(sized), quality, name)
        } else {
            saveAs(context, sized, format, quality, name)
        }
        if (sized !== bmp) sized.recycle()
        return ConversionResult(outUri, querySize(context, outUri), displayPath(context, outUri), format)
    }

    fun saveAsPdf(context: Context, bitmaps: List<Bitmap>, quality: Int, displayName: String): Uri {
        val document = PdfDocument()
        try {
            bitmaps.forEachIndexed { index, bmp ->
                val pageBitmap = preparePdfPageBitmap(bmp, quality)
                val pageInfo = PdfDocument.PageInfo.Builder(pageBitmap.width, pageBitmap.height, index).create()
                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(pageBitmap, 0f, 0f, null)
                document.finishPage(page)
                if (pageBitmap !== bmp) pageBitmap.recycle()
            }
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
        } finally {
            document.close()
        }
    }

    /**
     * Compresses an existing PDF: renders every page to a bitmap at ~2x resolution (scaled by
     * [scalePercent]), then rebuilds a new PDF via [saveAsPdf] which re-encodes each page as
     * JPEG at [quality]. Text becomes part of the page image, so the output is usually much
     * smaller — at the cost of selectable text. Pages are capped at [MAX_COMPRESS_PAGES] to
     * keep memory bounded.
     */
    fun compressPdf(context: Context, uri: Uri, quality: Int, scalePercent: Int, displayName: String): Uri {
        val bitmaps = mutableListOf<Bitmap>()
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Could not open the PDF file")
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount
                if (pageCount == 0) error("This PDF has no pages")
                if (pageCount > MAX_COMPRESS_PAGES) {
                    error("This PDF has $pageCount pages — the compressor handles up to $MAX_COMPRESS_PAGES")
                }
                for (i in 0 until pageCount) {
                    val bmp = renderer.openPage(i).use { page ->
                        val scale = 2f * scalePercent / 100f
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val pageBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        pageBmp.eraseColor(Color.WHITE)
                        page.render(pageBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pageBmp
                    }
                    bitmaps += bmp
                }
            }
            pfd.close()
            return saveAsPdf(context, bitmaps, quality, displayName)
        } finally {
            bitmaps.forEach { it.recycle() }
        }
    }

    /**
     * Scales [bmp] so its longest side is at most 2400px, composites transparency onto white
     * (JPEG has no alpha), and re-encodes as JPEG at [quality] so the embedded image stays small.
     */
    private fun preparePdfPageBitmap(bmp: Bitmap, quality: Int): Bitmap {
        val maxDim = 2400
        val largest = maxOf(bmp.width, bmp.height)
        val scale = if (largest > maxDim) maxDim.toFloat() / largest else 1f
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        if (quality >= 100) return sized
        val flattened = if (sized.hasAlpha()) {
            val white = Bitmap.createBitmap(sized.width, sized.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(white)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(sized, 0f, 0f, null)
            white
        } else sized
        val bytes = ByteArrayOutputStream().also { flattened.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Could not re-encode image for PDF")
        if (flattened !== sized) flattened.recycle()
        if (sized !== bmp) sized.recycle()
        return result
    }

    /** Byte size of the file behind [uri]. */
    fun querySize(context: Context, uri: Uri): Long {
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    }

    /**
     * Lists the most recently converted files (images and PDFs) saved by this app.
     */
    fun recentConversions(context: Context, limit: Int = 20): List<RecentFile> {
        val results = mutableListOf<RecentFile>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val seenIds = mutableSetOf<Long>()
            fun collect(collection: Uri) {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED,
                )
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%/FileConverter/%")
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        val fileId = cursor.getLong(idCol)
                        if (!seenIds.add(fileId)) continue // skip duplicate across collections
                        results += RecentFile(
                            uri = ContentUris.withAppendedId(collection, fileId),
                            name = cursor.getString(nameCol) ?: "file",
                            sizeBytes = cursor.getLong(sizeCol),
                            dateAdded = cursor.getLong(dateCol) * 1000L,
                        )
                    }
                }
            }
            collect(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
            collect(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL))
            return results.sortedByDescending { it.dateAdded }.take(limit)
        } else {
            val dirs = listOf(
                File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FileConverter"),
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FileConverter"),
            )
            dirs.forEach { dir ->
                dir.listFiles()?.forEach { file ->
                    results += RecentFile(Uri.fromFile(file), file.name, file.length(), file.lastModified())
                }
            }
        }
        return results.sortedByDescending { it.dateAdded }.take(limit)
    }

    /**
     * Counts ALL images and PDFs on the device (not just FileConverter),
     * grouped by file extension (lowercase, e.g. "jpg", "png", "pdf").
     */
    fun countAllFileTypes(context: Context): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fun collect(collection: Uri, mimeFilter: String? = null) {
                    val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sel = mimeFilter?.let { "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?" }
                    val args = mimeFilter?.let { arrayOf(it) }
                    context.contentResolver.query(
                        collection, projection, sel, args, null,
                    )?.use { cursor ->
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameCol) ?: continue
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (ext.isNotEmpty()) {
                                counts[ext] = (counts[ext] ?: 0) + 1
                            }
                        }
                    }
                }
                collect(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
                collect(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), "application/pdf")
            }
        } catch (e: Exception) {
            android.util.Log.e("FileConverter", "countAllFileTypes failed", e)
        }
        return counts
    }

    /** Best-effort display name for a content [uri] (e.g. "report.docx"). */
    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** True pixel dimensions of the image at [uri] (bounds-only decode — no full bitmap). */
    fun queryDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Deletes a file saved by this app (it owns the MediaStore rows it created).
     * The physical path is also removed, since some providers (Downloads on some
     * devices) drop the database row but leave the file behind.
     */
    fun deleteFile(context: Context, uri: Uri): Boolean {
        return try {
            if (uri.scheme == "content") {
                val path = runCatching {
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
                val deleted = context.contentResolver.delete(uri, null, null) > 0
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
                deleted
            } else {
                uri.path?.let { File(it) }?.let { it.exists() && it.delete() } ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Renames a media file via MediaStore. Returns true on success. */
    fun renameFile(context: Context, uri: Uri, newName: String): Boolean {
        return try {
            if (uri.scheme == "content") {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                }
                context.contentResolver.update(uri, values, null, null) > 0
            } else {
                uri.path?.let { File(it) }?.let { file ->
                    val newFile = File(file.parent, newName)
                    file.renameTo(newFile)
                } ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Human-friendly path for display, e.g. "Pictures/FileConverter/photo.jpg". */
    fun displayPath(context: Context, uri: Uri): String {
        if (uri.scheme != "content") return uri.path ?: uri.toString()
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                val rel = cursor.getString(1)
                return (rel ?: "Pictures/") + name
            }
        }
        return uri.toString()
    }

    /** Powers-of-two sample size that keeps the longest side at or under [maxDim]. */
    private fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0 || maxDim <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxDim) {
            sample *= 2
        }
        return sample
    }

    /** Creates a simple colored placeholder bitmap for files that can't be thumbnailed. */
    fun createPlaceholder(name: String): Bitmap {
        val color = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> 0xFFFF5FA2.toInt()
            "doc", "docx" -> 0xFFFF9F1C.toInt()
            else -> 0xFF7A7A7A.toInt()
        }
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        return bmp
    }
}
