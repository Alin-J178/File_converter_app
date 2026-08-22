package com.example.fileconverter

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
