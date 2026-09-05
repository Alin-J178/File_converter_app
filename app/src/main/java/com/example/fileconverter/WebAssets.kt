package com.example.fileconverter

import android.content.Context
import android.webkit.WebResourceResponse

/**
 * Shared WebView asset serving for the offline document viewers (docx/xlsx/pptx).
 *
 * Each format viewer runs inside a WebView pointed at a virtual HTTPS host
 * (e.g. https://docx.local/docx_viewer.html). [intercept] maps request paths to
 * files bundled in assets/ plus the uploaded document (served from cacheDir),
 * so fetch() works fully offline. Returns null for unknown paths so the
 * WebView falls through to its default handling.
 */
object WebAssets {

    private val MIME_BY_EXT = mapOf(
        "html" to "text/html",
        "js" to "application/javascript",
        "css" to "text/css",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "svg" to "image/svg+xml",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "json" to "application/json",
    )

    /**
     * Serves a request path from assets/ (with the right MIME type) or, when the
     * path equals [servePath], from the cache file named [cacheFile] with [cacheMime].
     */
    fun intercept(
        context: Context,
        path: String,
        servePath: String? = null,
        cacheFile: String? = null,
        cacheMime: String? = null,
        allowedAssetFiles: Set<String> = emptySet(),
    ): WebResourceResponse? {
        val clean = path.trimStart('/').substringBefore('?')
        if (servePath != null && cacheFile != null && cacheMime != null && clean == servePath) {
            val f = java.io.File(context.cacheDir, cacheFile)
            if (f.exists()) return WebResourceResponse(cacheMime, null, f.inputStream())
        }
        if (clean in allowedAssetFiles) {
            val ext = clean.substringAfterLast('.', "")
            val mime = MIME_BY_EXT[ext] ?: "application/octet-stream"
            return try {
                WebResourceResponse(mime, if (mime == "text/html") "utf-8" else null, context.assets.open(clean))
            } catch (_: Exception) { null }
        }
        return null
    }
}
