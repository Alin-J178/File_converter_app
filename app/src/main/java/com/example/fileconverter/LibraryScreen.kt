package com.example.fileconverter

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
        ) {
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
            // File list with NeoCard
            NeoCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                ) {
                    if (recentFiles.isEmpty()) {
                        Text(
                            "No converted files yet \u2014 convert something first!",
                            color = BrutMuted,
                            fontSize = 14.sp,
                        )
                    } else {
                        val cols = 3
                        val chunks = recentFiles.chunked(cols)
                        LazyGrid(
                            chunks, thumbs, selectMode, selected,
                            onToggleSelect = onToggleSelect,
                            onClick = { file ->
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
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
            // Clear All button
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NeoButton(
                    text = "Clear All",
                    onClick = onClearAll,
                    modifier = Modifier.width(180.dp),
                    height = 44.dp,
                    backgroundColor = BrutGrey,
                )
            }
        }
    }
}

/** 3-column grid of file tiles with thumbnails, type badges, and selection highlights. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyGrid(
    chunks: List<List<RecentFile>>,
    thumbs: Map<String, Bitmap>,
    selectMode: Boolean,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onClick: (RecentFile) -> Unit,
    onLongPress: (RecentFile, Pair<Int, Int>?, String?) -> Unit,
) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        chunks.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { file ->
                    val isSelected = selected.contains(file.uri.toString())
                    val ext = file.name.substringAfterLast('.', "").uppercase()
                    val badgeColor = when (ext) {
                        "JPEG", "JPG" -> BrutYellow
                        "PNG" -> BrutBlue
                        "WEBP" -> BrutGreen
                        "PDF" -> BrutPink
                        else -> BrutMuted
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(3.dp, if (isSelected) BrutPink else BrutBlack, RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = {
                                    if (selectMode) onToggleSelect(file.uri.toString()) else onClick(file)
                                },
                                onLongClick = {
                                    if (!selectMode) {
                                        val dims = if (!file.name.endsWith(".pdf", true)) ImageConverter.queryDimensions(ctx, file.uri) else null
                                        val path = ImageConverter.displayPath(ctx, file.uri)
                                        onLongPress(file, dims, path)
                                    }
                                },
                            ),
                    ) {
                        val bmp = thumbs[file.uri.toString()]
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = file.name,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
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
                // Fill empty cells
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }
}
