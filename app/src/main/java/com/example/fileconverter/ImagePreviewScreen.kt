package com.example.fileconverter

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen image preview with pinch-to-zoom, pan, and double-tap toggle zoom.
 * Supports all image formats: JPEG, PNG, WebP, GIF, BMP, TIFF, HEIF, AVIF, SVG.
 */
@Composable
fun ImagePreviewScreen(
    uri: Uri,
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val ext = fileName.substringAfterLast('.', "").lowercase()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Zoom state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val isZoomed = scale > 1.05f

    // Load bitmap
    LaunchedEffect(uri) {
        isLoading = true
        loadError = null
        bitmap = null
        withContext(Dispatchers.IO) {
            try {
                bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    // Decode with downsampling for very large images
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    // Need to read twice — first for bounds
                    val bytes = stream.readBytes()
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                    val sampleSize = calculateInSampleSize(options, 4096, 4096)
                    val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                }
                if (bitmap == null) {
                    loadError = "Could not load image"
                }
            } catch (e: Exception) {
                Log.e("ImagePreview", "Failed to load image", e)
                loadError = "Error: ${e.message ?: "Unknown error"}"
            }
            isLoading = false
        }
    }

    val accentColor = when (ext.uppercase()) {
        "JPEG", "JPG" -> colors.accent
        "PNG" -> colors.blue
        "WEBP" -> colors.green
        "GIF" -> colors.purple
        "BMP" -> colors.orange
        "TIFF", "TIF" -> colors.brown
        "HEIF", "HEIC" -> Color(0xFF7E57C2)
        "AVIF" -> Color(0xFF00897B)
        "SVG" -> Color(0xFFEF6C00)
        else -> colors.muted
    }

    val dims = bitmap?.let { "${it.width}×${it.height}" } ?: ""

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                NeoIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    backgroundColor = Color.White.copy(alpha = 0.15f),
                    iconTint = Color.White,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(ext.uppercase())
                            if (dims.isNotEmpty()) append(" • $dims")
                            if (isZoomed) append(" • ${String.format("%.1f", scale)}×")
                        },
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
            }

            // Content — zoomable image
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            ) {
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
                                Text("Loading image…", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                            }
                        }
                    }
                    loadError != null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(loadError!!, color = colors.pink, fontSize = 14.sp)
                        }
                    }
                    bitmap != null -> {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY,
                                )
                                .pointerInput(isZoomed) {
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, 8f)
                                        val scaleChange = newScale / scale
                                        offsetX = (offsetX + pan.x) * scaleChange +
                                                centroid.x * (1f - scaleChange)
                                        offsetY = (offsetY + pan.y) * scaleChange +
                                                centroid.y * (1f - scaleChange)
                                        scale = newScale
                                        if (scale <= 1.05f) {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = { offset ->
                                            if (scale > 1.05f) {
                                                scale = 1f
                                                offsetX = 0f
                                                offsetY = 0f
                                            } else {
                                                scale = 2.5f
                                                offsetX = (size.width / 2f - offset.x) * 1.5f
                                                offsetY = (size.height / 2f - offset.y) * 1.5f
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Calculate optimal inSampleSize for BitmapFactory */
private fun calculateInSampleSize(
    options: android.graphics.BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int,
): Int {
    val (height, width) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
