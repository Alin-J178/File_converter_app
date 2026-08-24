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
import androidx.compose.material.icons.filled.Close
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
    onRemoveFavouriteFile: (Int) -> Unit = {},
    onQualityChange: (Float) -> Unit = {},
    onScaleChange: (Int) -> Unit = {},
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
                        tint = C.muted.copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "No favourites yet",
                        color = C.muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Long-press a format in Convert to add it here",
                        color = C.muted,
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
                        previews.take(3).forEachIndexed { index, bmp ->
                            Box(modifier = Modifier.size(38.dp)) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(2.dp, C.border, RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                // X button to remove
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(0.dp)
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(C.border.copy(alpha = 0.75f))
                                        .clickable { onRemoveFavouriteFile(index) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        tint = C.surface,
                                        modifier = Modifier.size(10.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (selectedCount == 1) "Ready to convert" else "$selectedCount files ready",
                                color = C.onBackground,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (originalSize > 0) {
                                Text(
                                    text = "${formatBytes(originalSize)} \u00b7 ${selectedFavouriteFormat.label} \u00b7 tap to change",
                                    color = C.muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // Estimated output size
                                val fmt = selectedFavouriteFormat
                                val qualityFactor = if (fmt.lossy) (quality / 100f) else 1f
                                val scaleFactor = (scalePercent / 100f) * (scalePercent / 100f)
                                val estimatedSize = (originalSize * qualityFactor * scaleFactor).toLong()
                                val savings = if (originalSize > 0) {
                                    val saved = originalSize - estimatedSize
                                    val pct = (saved * 100 / originalSize).toInt()
                                    if (pct > 0) "saves ~$pct%" else "full size"
                                } else ""
                                Text(
                                    text = "Est. output: ~${formatBytes(estimatedSize)} ($savings)",
                                    color = if (savings.startsWith("saves")) C.green else C.muted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
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
                            tint = C.onSurface,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pick a file to convert",
                                color = C.onBackground,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = favouriteFormats.joinToString(" \u00b7 ") { it.label },
                                color = C.muted,
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
                            .background(if (canConvert) C.border else Color(0xFF9E9E9E))
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(plusShape)
                            .background(if (canConvert) C.accent else Color(0xFFE3E3E3))
                            .border(3.dp, C.border, plusShape)
                            .clickable(enabled = canConvert, onClick = onFavouriteConvert),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                color = C.onSurface,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Convert",
                                tint = if (canConvert) C.onSurface else Color(0xFF9E9E9E),
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
                        OutputFormat.PNG -> C.green
                        OutputFormat.JPEG -> C.accent
                        OutputFormat.WEBP -> C.pink
                        OutputFormat.GIF -> C.purple
                        OutputFormat.BMP -> BrutOrange
                        OutputFormat.TIFF -> Color(0xFF8D6E63)
                        OutputFormat.HEIF -> Color(0xFF7E57C2)
                        OutputFormat.PDF -> C.blue
                    }
                    val bgColor = if (isSelected) accent else C.surface
                    Box(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(bgColor)
                            .border(2.dp, C.border, chipShape)
                            .clickable { onFavouriteFormatSelected(format) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = format.label,color = C.onBackground,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = C.onSurface,
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
                    color = C.muted,
                    fontSize = 13.sp,
                )
            }
        }

        // Compression
        SectionTitle("Compression")
        Text(text = "Adjust quality and resize settings for conversions", color = C.muted, fontSize = 13.sp)

        // Quality + Resize settings card — adapts to selected favourite format
        NeoCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val fmt = selectedFavouriteFormat

                if (fmt == null) {
                    // No format selected yet
                    Text(
                        text = "Select a favourite format above to see compression options.",
                        color = C.muted, fontSize = 13.sp,
                    )
                } else {
                    // ── Quality section ──
                    if (fmt.lossy) {
                        // Lossy format — show quality slider
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Quality: ${quality.toInt()}%",
                                color = C.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            // Reset button
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(C.accent)
                                    .border(2.dp, C.border, RoundedCornerShape(8.dp))
                                    .clickable { onQualityChange(85f) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = "Reset",color = C.onSurface,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        NeoSlider(value = quality, onValueChange = { onQualityChange(it) }, modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Lower quality = smaller file. Typical: a 3 MB photo becomes ~1.5 MB at 85%, ~0.7 MB at 50%. You're at ${quality.toInt()}% \u2014 85% is a good default.",
                            color = C.muted, fontSize = 12.sp,
                        )
                    } else {
                        // Lossless format — no quality adjustment
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${fmt.label} is lossless",
                                color = C.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = "Quality adjustment is not supported for ${fmt.label} files. The output will always be full quality.",
                            color = C.muted, fontSize = 12.sp,
                        )
                    }

                    // ── Resize section ──
                    Text(text = "Resize", color = C.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(100, 75, 50, 25).forEach { pct ->
                            val selected = scalePercent == pct
                            NeoButton(
                                text = "$pct%",
                                onClick = { onScaleChange(pct) },
                                height = 40.dp,
                                modifier = Modifier.weight(1f),
                                backgroundColor = if (selected) C.accent else C.surface,
                            )
                        }
                    }
                    val (w, h) = originalDims ?: (2000 to 1500)
                    val newW = w * scalePercent / 100
                    val newH = h * scalePercent / 100
                    Text(
                        text = "Example: $w \u00d7 $h px \u2192 $newW \u00d7 $newH px at $scalePercent%. Fewer pixels = much smaller file.",
                        color = C.muted, fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
    } // PullToRefreshBox
}
