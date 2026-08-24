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
            "TIFF", "TIF" -> "TIFF"
            "HEIC", "HEIF" -> "HEIF"
            "AVIF" -> "AVIF"
            "SVG" -> "SVG"
            "XLSX" -> "XLSX"
            "PPTX" -> "PPTX"
            "CSV" -> "CSV"
            "PPT" -> "PPT"
            else -> ext.ifEmpty { "Other" }
        }
        counts[label] = (counts[label] ?: 0) + 1
    }
    return counts
}

/**
 * Queries the device MediaStore for all images and documents.
 * Returns a map like {"PNG" -> 120, "JPEG" -> 340, "PDF" -> 15}.
 * Uses both MIME type and file extension to catch all file types.
 */
internal fun queryDeviceFileCounts(context: Context): Map<String, Pair<Int, Long>> {
    // label -> (count, totalSizeBytes)
    val counts = mutableMapOf<String, Pair<Int, Long>>()
    fun addCount(label: String, size: Long = 0L) {
        val prev = counts[label] ?: (0 to 0L)
        counts[label] = (prev.first + 1) to (prev.second + size)
    }

    // --- Images via MediaStore.Images (PNG, JPEG, WebP) ---
    val imageProjection = arrayOf(MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE)
    val imageMimeCol = MediaStore.Images.Media.MIME_TYPE
    val imageSizeCol = MediaStore.Images.Media.SIZE
    val imageMimes = listOf(
        "image/png" to "PNG",
        "image/jpeg" to "JPEG",
        "image/webp" to "WebP",
    )
    for ((mime, label) in imageMimes) {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            "$imageMimeCol = ?",
            arrayOf(mime),
            null,
        )?.use { cursor ->
            val sizeIdx = cursor.getColumnIndex(imageSizeCol)
            while (cursor.moveToNext()) {
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                addCount(label, size)
            }
        }
    }

    // --- All files via MediaStore.Files, matched by extension ---
    val extMap = listOf(
        ".gif" to "GIF",
        ".bmp" to "BMP",
        ".tiff" to "TIFF",
        ".tif" to "TIFF",
        ".heic" to "HEIF",
        ".heif" to "HEIF",
        ".avif" to "AVIF",
        ".svg" to "SVG",
        ".pdf" to "PDF",
        ".xlsx" to "XLSX",
        ".pptx" to "PPTX",
        ".csv" to "CSV",
        ".ppt" to "PPT",
    )
    val filesProjection = arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE)
    val nameCol = MediaStore.Files.FileColumns.DISPLAY_NAME
    val filesSizeCol = MediaStore.Files.FileColumns.SIZE
    for ((ext, label) in extMap) {
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            filesProjection,
            "$nameCol LIKE ?",
            arrayOf("%$ext"),
            null,
        )?.use { cursor ->
            val sizeIdx = cursor.getColumnIndex(filesSizeCol)
            while (cursor.moveToNext()) {
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                addCount(label, size)
            }
        }
    }

    return counts
}

/**
 * Queries the device MediaStore for all files matching [formatLabel] (e.g. "PNG", "JPEG", "PDF").
 * Uses both MIME type and file extension to catch all file types.
 * Returns a list of [RecentFile] suitable for displaying in [LibraryScreen].
 */
internal fun queryDeviceFilesByFormat(context: Context, formatLabel: String): List<RecentFile> {
    val results = mutableListOf<RecentFile>()
    val label = formatLabel.uppercase()

    // Extensions that may not be indexed by MIME type in MediaStore
    val extMap = mapOf(
        "GIF" to listOf(".gif"),
        "BMP" to listOf(".bmp"),
        "TIFF" to listOf(".tiff", ".tif"),
        "HEIF" to listOf(".heic", ".heif"),
        "AVIF" to listOf(".avif"),
        "SVG" to listOf(".svg"),
        "PDF" to listOf(".pdf"),
        "XLSX" to listOf(".xlsx"),
        "PPTX" to listOf(".pptx"),
        "CSV" to listOf(".csv"),
        "PPT" to listOf(".ppt"),
    )

    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED,
    )
    val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

    if (label in extMap) {
        val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
        val extensions = extMap[label]!!
        for (ext in extensions) {
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "$nameCol LIKE ?",
                arrayOf("%$ext"),
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val displayNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                if (idCol < 0 || displayNameCol < 0) return@use

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    results.add(
                        RecentFile(
                            uri = uri,
                            name = cursor.getString(displayNameCol) ?: "unknown",
                            sizeBytes = cursor.getLong(sizeCol),
                            dateAdded = cursor.getLong(dateCol) * 1000,
                        )
                    )
                }
            }
        }.onFailure { android.util.Log.w("FileConverter", "queryDeviceFilesByFormat ext failed", it) }
        } // end for (extensions)
    } else {
        // Match by MIME type for standard formats
        val mimeMap = mapOf(
            "PNG" to listOf("image/png"),
            "JPEG" to listOf("image/jpeg", "image/jpg"),
            "WEBP" to listOf("image/webp"),
        )
        val mimes = mimeMap[label] ?: return emptyList()
        val mimeCol = MediaStore.MediaColumns.MIME_TYPE
        val selection = "$mimeCol IN (${mimes.joinToString { "?" }})"
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                mimes.toTypedArray(),
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val displayNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                if (idCol < 0 || displayNameCol < 0) return@use

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    results.add(
                        RecentFile(
                            uri = uri,
                            name = cursor.getString(displayNameCol) ?: "unknown",
                            sizeBytes = cursor.getLong(sizeCol),
                            dateAdded = cursor.getLong(dateCol) * 1000,
                        )
                    )
                }
            }
        }.onFailure { android.util.Log.w("FileConverter", "queryDeviceFilesByFormat mime failed", it) }
    }

    return results
}
