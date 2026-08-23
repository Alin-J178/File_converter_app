package com.example.fileconverter

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Main dashboard screen: pie chart, favourite format quick-picks, and compression settings.
 * All conversion is now handled via the Convert screen (burger menu).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(
    previews: List<Bitmap>,
    selectedCount: Int,
    originalSize: Long,
    outputFormat: OutputFormat,
    quality: Float,
    scalePercent: Int,
    originalDims: Pair<Int, Int>?,
    pieChartSlices: List<PieSlice>,
    favouriteFormats: Set<OutputFormat>,
    selectedFavouriteFormat: OutputFormat?,
    busy: Boolean,
    onMenu: () -> Unit,
    onOpenLibrary: () -> Unit,
    onFormatTap: (String) -> Unit,
    onFavouriteFormatSelected: (OutputFormat) -> Unit,
    onPickFileForFavourite: () -> Unit,
    onFavouriteConvert: () -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val context = LocalContext.current
    val pullToRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullToRefreshState,
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top bar: hamburger (left) + folder (right)
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoIconButton(
                icon = Icons.Filled.Menu,
                contentDescription = "Menu",
                onClick = onMenu,
            )
            Spacer(modifier = Modifier.weight(1f))
            NeoIconButton(
                icon = Icons.Filled.Folder,
                contentDescription = "Your saved files",
                onClick = onOpenLibrary,
            )
        }

        // Pie chart — file format distribution
        NeoPieChart(slices = pieChartSlices, modifier = Modifier.fillMaxWidth(), onSliceClick = onFormatTap)

        // ── Favourite section ──
        SectionTitle("Favourite")

        if (favouriteFormats.isEmpty()) {
            // Empty state
            NeoCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = BrutMuted.copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "No favourites yet",
                        color = BrutMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Long-press a format in Convert to add it here",
                        color = BrutMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            // File selection row + convert button (like the old layout)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NeoField(
                    onClick = onPickFileForFavourite,
                    modifier = Modifier.weight(1f),
                ) {
                    if (selectedCount > 0 && selectedFavouriteFormat != null) {
                        previews.take(3).forEach { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(2.dp, BrutBlack, RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (selectedCount == 1) "Ready to convert" else "$selectedCount files ready",
                                color = BrutBlack,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (originalSize > 0) {
                                Text(
                                    text = "${formatBytes(originalSize)} \u00b7 ${selectedFavouriteFormat.label} \u00b7 tap to change",
                                    color = BrutMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        val icon = when (selectedFavouriteFormat) {
                            OutputFormat.PDF -> Icons.Filled.PictureAsPdf
                            else -> Icons.Filled.Image
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = BrutBlack,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pick a file to convert",
                                color = BrutBlack,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = favouriteFormats.joinToString(" \u00b7 ") { it.label },
                                color = BrutMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Yellow + convert button
                val canConvert = selectedCount > 0 && selectedFavouriteFormat != null && !busy
                val plusShape = RoundedCornerShape(14.dp)
                Box(modifier = Modifier.size(56.dp)) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .offset(x = 5.dp, y = 6.dp)
                            .clip(plusShape)
                            .background(if (canConvert) BrutBlack else Color(0xFF9E9E9E))
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(plusShape)
                            .background(if (canConvert) BrutYellow else Color(0xFFE3E3E3))
                            .border(3.dp, BrutBlack, plusShape)
                            .clickable(enabled = canConvert, onClick = onFavouriteConvert),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                color = BrutBlack,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Convert",
                                tint = if (canConvert) BrutBlack else Color(0xFF9E9E9E),
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }
            }

            // Favourite format chips (selectable, compact, wrapping)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                favouriteFormats.forEach { format ->
                    val isSelected = selectedFavouriteFormat == format
                    val chipShape = RoundedCornerShape(12.dp)
                    val accent = when (format) {
                        OutputFormat.PNG -> BrutGreen
                        OutputFormat.JPEG -> BrutYellow
                        OutputFormat.WEBP -> BrutPink
                        OutputFormat.GIF -> BrutPurple
                        OutputFormat.BMP -> BrutOrange
                        OutputFormat.TIFF -> Color(0xFF8D6E63)
                        OutputFormat.HEIF -> Color(0xFF7E57C2)
                        OutputFormat.PDF -> BrutBlue
                    }
                    val bgColor = if (isSelected) accent else Color.White
                    Box(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(bgColor)
                            .border(2.dp, BrutBlack, chipShape)
                            .clickable { onFavouriteFormatSelected(format) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = format.label,
                                color = BrutBlack,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = BrutBlack,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Hint text
            if (selectedFavouriteFormat != null && selectedCount == 0) {
                Text(
                    text = "Tap \u201aPick a file\u201c to select ${selectedFavouriteFormat.label} files, then tap + to convert.",
                    color = BrutMuted,
                    fontSize = 13.sp,
                )
            }
        }

        // Compression
        SectionTitle("Compression")
        Text(text = "Adjust quality and resize settings for conversions", color = BrutMuted, fontSize = 13.sp)

        // Quality + Resize settings card
        NeoCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Quality
                val qualityLocked = outputFormat == OutputFormat.PNG
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (outputFormat == OutputFormat.PNG) "PNG is lossless" else "Quality: ${quality.toInt()}%",
                        color = BrutBlack, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!qualityLocked) {
                    NeoSlider(value = quality, onValueChange = {}, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Lower quality = smaller file. Typical: a 3 MB photo becomes ~1.5 MB at 85%, ~0.7 MB at 50%. You're at ${quality.toInt()}% \u2014 85% is a good default.",
                        color = BrutMuted, fontSize = 12.sp,
                    )
                }
                // Resize
                Text(text = "Resize", color = BrutBlack, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(100, 75, 50, 25).forEach { pct ->
                        val selected = scalePercent == pct
                        NeoButton(
                            text = "$pct%",
                            onClick = {},
                            height = 40.dp,
                            modifier = Modifier.weight(1f),
                            backgroundColor = if (selected) BrutYellow else Color.White,
                        )
                    }
                }
                val (w, h) = originalDims ?: (2000 to 1500)
                val newW = w * scalePercent / 100
                val newH = h * scalePercent / 100
                Text(
                    text = "Example: $w \u00d7 $h px \u2192 $newW \u00d7 $newH px at $scalePercent%. Fewer pixels = much smaller file.",
                    color = BrutMuted, fontSize = 12.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
    } // PullToRefreshBox
}
