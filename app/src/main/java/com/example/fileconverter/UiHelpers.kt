package com.example.fileconverter

import android.content.Context
import android.provider.MediaStore
import java.text.DecimalFormat

/** Formats a byte count as a human-friendly string, e.g. "1.5 MB". */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${DecimalFormat("#.#").format(kb)} KB"
    val mb = kb / 1024.0
    return "${DecimalFormat("#.##").format(mb)} MB"
}

/** Formats a Unix-millis timestamp as e.g. "Aug 22, 2026 · 3:45 PM". */
internal fun formatTimestamp(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, yyyy \u00b7 h:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}

/**
 * Counts files on the phone by their extension-derived format label.
 * Returns a map like {"PNG" -> 3, "JPEG" -> 2, "PDF" -> 1}.
 */
internal fun countFormatsByExtension(files: List<RecentFile>): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    for (file in files) {
        val ext = file.name.substringAfterLast('.', "").uppercase()
        val label = when (ext) {
            "PNG" -> "PNG"
            "JPEG", "JPG" -> "JPEG"
            "WEBP" -> "WebP"
            "GIF" -> "GIF"
            "BMP" -> "BMP"
            "PDF" -> "PDF"
            else -> ext.ifEmpty { "Other" }
        }
        counts[label] = (counts[label] ?: 0) + 1
    }
    return counts
}

/**
 * Queries the device MediaStore for all images and documents.
 * Returns a map like {"PNG" -> 120, "JPEG" -> 340, "PDF" -> 15}.
 * Requires READ_MEDIA_IMAGES (Android 13+) or READ_EXTERNAL_STORAGE.
 */
internal fun queryDeviceFileCounts(context: Context): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()

    // --- Images ---
    val imageProjection = arrayOf(MediaStore.Images.Media.MIME_TYPE)
    val imageMimeCol = MediaStore.Images.Media.MIME_TYPE

    // Count images grouped by MIME type
    val imageMimes = listOf(
        "image/png" to "PNG",
        "image/jpeg" to "JPEG",
        "image/webp" to "WebP",
        "image/gif" to "GIF",
        "image/bmp" to "BMP",
    )
    for ((mime, label) in imageMimes) {
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            "$imageMimeCol = ?",
            arrayOf(mime),
            null,
        )
        cursor?.use {
            counts[label] = (counts[label] ?: 0) + it.count
        }
    }

    // --- PDFs (via Files collection) ---
    val filesProjection = arrayOf(MediaStore.Files.FileColumns.MIME_TYPE)
    val filesMimeCol = MediaStore.Files.FileColumns.MIME_TYPE
    val pdfCursor = context.contentResolver.query(
        MediaStore.Files.getContentUri("external"),
        filesProjection,
        "$filesMimeCol = ?",
        arrayOf("application/pdf"),
        null,
    )
    pdfCursor?.use {
        counts["PDF"] = (counts["PDF"] ?: 0) + it.count
    }

    return counts
}
