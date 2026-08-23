package com.example.fileconverter

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Saved in Storage" screen: a grid of previously converted files with thumbnails,
 * multi-select, delete, share, open, and long-press for details. State is hoisted to the caller.
 */
@Composable
internal fun LibraryScreen(
    recentFiles: List<RecentFile>,
    thumbs: Map<String, Bitmap>,
    selectMode: Boolean,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onBack: () -> Unit,
    onToggleSelectMode: () -> Unit,
    onDeleteSelected: () -> Unit,
    onLongPress: (RecentFile, Pair<Int, Int>?, String?) -> Unit,
    onClearAll: () -> Unit,
    showClearConfirm: Boolean = false,
    onShowClearConfirm: (Boolean) -> Unit = {},
    onThumbLoaded: ((String, Bitmap) -> Unit)? = null,
    title: String? = null,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrutCream)
            .zIndex(10f)
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header: back + title + action buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                NeoIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title ?: "Saved in Storage",
                    color = BrutBlack,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                NeoIconButton(
                    icon = Icons.Filled.Check,
                    contentDescription = if (selectMode) "Done" else "Select",
                    onClick = onToggleSelectMode,
                    backgroundColor = if (selectMode) BrutBlack else Color.White,
                    iconTint = if (selectMode) Color.White else BrutBlack,
                )
                Spacer(modifier = Modifier.width(8.dp))
                NeoIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Delete selected",
                    onClick = onDeleteSelected,
                    backgroundColor = BrutPink,
                )
                Spacer(modifier = Modifier.width(8.dp))
                NeoIconButton(
                    icon = Icons.Filled.Share,
                    contentDescription = "Share",
                    onClick = {
                        if (selected.isNotEmpty()) {
                            val uris = selected.map { Uri.parse(it) }
                            val intent = if (uris.size == 1) {
                                Intent(Intent.ACTION_SEND).apply {
                                    type = context.contentResolver.getType(uris.first()) ?: "*/*"
                                    putExtra(Intent.EXTRA_STREAM, uris.first())
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            } else {
                                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "*/*"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            }
                            context.startActivity(Intent.createChooser(intent, "Share files"))
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Lazy grid — only renders visible items, so thousands of files are fine
            if (recentFiles.isEmpty()) {
                NeoCard(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No converted files yet \u2014 convert something first!",
                            color = BrutMuted,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                Text(
                    text = "${recentFiles.size} file${if (recentFiles.size != 1) "s" else ""}",
                    color = BrutMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = recentFiles,
                        key = { it.uri.toString() },
                        contentType = { "file" },
                    ) { file ->
                        FileTile(
                            file = file,
                            thumbs = thumbs,
                            selectMode = selectMode,
                            selected = selected,
                            onToggleSelect = onToggleSelect,
                            onClick = {
                                if (!selectMode) {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(file.uri, context.contentResolver.getType(file.uri) ?: "*/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try { context.startActivity(intent) } catch (_: Exception) {
                                        Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onLongPress = {
                                if (!selectMode) {
                                    val dims = if (!file.name.endsWith(".pdf", true)) ImageConverter.queryDimensions(context, file.uri) else null
                                    val path = ImageConverter.displayPath(context, file.uri)
                                    onLongPress(file, dims, path)
                                }
                            },
                            onThumbLoaded = onThumbLoaded,
                        )
                    }
                }
            }

            // Clear All button
            if (recentFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {                        NeoButton(
                        text = "Clear All",
                        onClick = { onShowClearConfirm(true) },
                        modifier = Modifier.width(180.dp),
                        height = 44.dp,
                        backgroundColor = BrutGrey,
                    )
                }
            }
        }

        // Clear All confirmation dialog
        if (showClearConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { onShowClearConfirm(false) },
                containerColor = BrutCream,
                shape = RoundedCornerShape(18.dp),
                title = {
                    Text(
                        text = "Clear All Files?",
                        color = BrutBlack,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                    )
                },
                text = {
                    Text(
                        text = "This will remove all ${recentFiles.size} converted files from your storage. This action cannot be undone.",
                        color = BrutMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                },
                confirmButton = {
                    NeoButton(
                        text = "Clear All",
                        onClick = { onShowClearConfirm(false); onClearAll() },
                        modifier = Modifier.width(120.dp),
                        height = 40.dp,
                        backgroundColor = BrutPink,
                    )
                },
                dismissButton = {
                    NeoButton(
                        text = "Cancel",
                        onClick = { onShowClearConfirm(false) },
                        modifier = Modifier.width(120.dp),
                        height = 40.dp,
                        backgroundColor = Color.White,
                    )
                },
            )
        }
    }
}

/** A single file tile in the grid with on-demand thumbnail loading. */
@Composable
private fun FileTile(
    file: RecentFile,
    thumbs: Map<String, Bitmap>,
    selectMode: Boolean,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onThumbLoaded: ((String, Bitmap) -> Unit)?,
) {
    val ctx = LocalContext.current
    val isSelected = selected.contains(file.uri.toString())
    val ext = file.name.substringAfterLast('.', "").uppercase()
    val badgeColor = when (ext) {
        "JPEG", "JPG" -> BrutYellow
        "PNG" -> BrutBlue
        "WEBP" -> BrutGreen
        "GIF" -> BrutPurple
        "BMP" -> BrutOrange
        "PDF" -> BrutPink
        else -> BrutMuted
    }

    // Load thumbnail on-demand when this item becomes visible
    val cachedThumb = thumbs[file.uri.toString()]
    LaunchedEffect(file.uri) {
        if (cachedThumb == null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = ImageConverter.renderThumbnail(ctx, file.uri, file.name)
                        ?: ImageConverter.createPlaceholder(file.name)
                    onThumbLoaded?.invoke(file.uri.toString(), bmp)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(3.dp, if (isSelected) BrutPink else BrutBlack, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    if (selectMode) onToggleSelect(file.uri.toString()) else onClick()
                },
                onLongClick = { onLongPress() },
            ),
    ) {
        val bmp = cachedThumb ?: thumbs[file.uri.toString()]
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            // Placeholder while loading
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = BrutMuted, modifier = Modifier.size(36.dp))
            }
        }
        // Badge
        Box(
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(BrutBlack).padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(ext, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        if (selectMode && isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x55FF5FA2)).clip(RoundedCornerShape(12.dp)))
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
        }
    }
}
