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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
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
 * The main conversion screen: top bar, file picker field, convert button, target format rows,
 * and the quality/resize settings card. State is hoisted to the caller.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(
    previews: List<Bitmap>,
    selectedCount: Int,
    originalSize: Long,
    pendingWordUris: List<Uri>,
    pendingPdfUri: Uri?,
    outputFormat: OutputFormat,
    quality: Float,
    scalePercent: Int,
    originalDims: Pair<Int, Int>?,
    busy: Boolean,
    docBusy: Boolean,
    pdfBusy: Boolean,
    convertedCount: Int,
    pieChartSlices: List<PieSlice>,
    onMenu: () -> Unit,
    onOpenLibrary: () -> Unit,
    onFieldClick: () -> Unit,
    onConvert: () -> Unit,
    onPickWord: () -> Unit,
    onPickPdf: () -> Unit,
    onFormatSelected: (OutputFormat) -> Unit,
    onQualityChanged: (Float) -> Unit,
    onResetQuality: () -> Unit,
    onScaleChanged: (Int) -> Unit,
    onFormatTap: (String) -> Unit,
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

        // File selection row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NeoField(
                onClick = onFieldClick,
                modifier = Modifier.weight(1f),
            ) {
                if (pendingWordUris.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        tint = BrutBlack,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (pendingWordUris.size == 1) "Word file ready" else "${pendingWordUris.size} Word files ready",
                            color = BrutBlack,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (pendingWordUris.size == 1) "${ImageConverter.queryDisplayName(context, pendingWordUris.first())} · tap to change" else "Tap to change selection",
                            color = BrutMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (pendingPdfUri != null) {
                    Icon(
                        imageVector = Icons.Filled.PictureAsPdf,
                        contentDescription = null,
                        tint = BrutBlack,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PDF ready to compress",
                            color = BrutBlack,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${ImageConverter.queryDisplayName(context, pendingPdfUri)} · tap to change",
                            color = BrutMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (previews.isNotEmpty()) {
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
                            text = if (selectedCount == 1) "Ready to convert" else "$selectedCount images ready",
                            color = BrutBlack,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (originalSize > 0) {
                            Text(
                                text = "${formatBytes(originalSize)} · tap to change",
                                color = BrutMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = BrutBlack,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tap to select an image",
                            color = BrutBlack,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "PNG · JPEG · WebP · PDF",
                            color = BrutMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // Yellow + convert button
            val canConvert = (selectedCount > 0 || pendingWordUris.isNotEmpty() || pendingPdfUri != null) && !busy && !docBusy && !pdfBusy
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
                        .clickable(enabled = canConvert, onClick = onConvert),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy || docBusy || pdfBusy) {
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

        if (busy && selectedCount > 1) {
            Text(text = "Converting $convertedCount/$selectedCount…", color = BrutMuted, fontSize = 13.sp)
        } else if (docBusy && pendingWordUris.isNotEmpty()) {
            Text(text = "Converting Word files $convertedCount/${pendingWordUris.size}…", color = BrutMuted, fontSize = 13.sp)
        } else if (pendingWordUris.isNotEmpty() && !docBusy) {
            Text(
                text = if (pendingWordUris.size == 1) "Word file selected — tap + to convert it to PDF." else "${pendingWordUris.size} Word files — tap + to convert all to PDF.",
                color = BrutMuted, fontSize = 13.sp,
            )
        } else if (pendingPdfUri != null && !pdfBusy) {
            Text(text = "PDF selected — adjust quality/resize below, then tap + to compress.", color = BrutMuted, fontSize = 13.sp)
        } else if (selectedCount == 0) {
            Text(text = "Pick one or more images, choose a format, tap + to convert.", color = BrutMuted, fontSize = 13.sp)
        }

        // Convert to
        SectionTitle("Convert to")
        ActionRow(text = "PNG", icon = Icons.Filled.PhotoLibrary, accent = BrutGreen, selected = outputFormat == OutputFormat.PNG, busy = busy || docBusy, onClick = { onFormatSelected(OutputFormat.PNG) })
        ActionRow(text = "JPEG", icon = Icons.Filled.Image, accent = BrutYellow, selected = outputFormat == OutputFormat.JPEG, busy = busy || docBusy, onClick = { onFormatSelected(OutputFormat.JPEG) })
        ActionRow(text = "WebP", icon = Icons.Filled.Photo, accent = BrutPink, selected = outputFormat == OutputFormat.WEBP, busy = busy || docBusy, onClick = { onFormatSelected(OutputFormat.WEBP) })
        ActionRow(text = "PDF", icon = Icons.Filled.PictureAsPdf, accent = BrutBlue, selected = outputFormat == OutputFormat.PDF, busy = busy || docBusy, onClick = { onFormatSelected(OutputFormat.PDF) })

        Spacer(modifier = Modifier.height(4.dp))
        ActionRow(text = "Word - PDF", icon = Icons.Filled.Description, accent = BrutOrange, busy = busy || docBusy, onClick = onPickWord)

        Spacer(modifier = Modifier.height(4.dp))

        // Compression
        SectionTitle("Compression")
        Text(text = "Pick a PDF file to compress, or use quality/resize for image conversion", color = BrutMuted, fontSize = 13.sp)

        NeoButton(
            text = "Pick PDF to compress",
            onClick = onPickPdf,
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
        )

        // Quality + Resize settings card
        NeoCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Quality — PNG is lossless so there is nothing to adjust; PDF compression always uses it
                val qualityLocked = pendingPdfUri == null && outputFormat == OutputFormat.PNG
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = when {
                            pendingPdfUri != null -> "PDF quality: ${quality.toInt()}%"
                            outputFormat == OutputFormat.PNG -> "PNG is lossless"
                            else -> "${outputFormat.label} quality: ${quality.toInt()}%"
                        },
                        color = BrutBlack, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (!qualityLocked) {
                        // Explicit width is required: NeoButton sizes its layers via matchParentSize,
                        // so without a width modifier it collapses to zero and renders nothing.
                        NeoButton(
                            text = "Reset",
                            onClick = onResetQuality,
                            modifier = Modifier.width(72.dp),
                            height = 36.dp,
                            backgroundColor = BrutGrey,
                        )
                    }
                }
                if (!qualityLocked) {
                    NeoSlider(value = quality, onValueChange = onQualityChanged, modifier = Modifier.fillMaxWidth())
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
                            onClick = { onScaleChanged(pct) },
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

/** Neo-brutalist selectable row used for the target format options. */
@Composable
private fun ActionRow(text: String, icon: ImageVector, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, busy: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier = modifier.fillMaxWidth().height(64.dp)) {
        Box(modifier = Modifier.matchParentSize().offset(x = 5.dp, y = 6.dp).clip(shape).background(BrutBlack))
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Color.White)
                .border(3.dp, BrutBlack, shape)
                .clickable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(modifier = Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(accent).border(3.dp, BrutBlack, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = BrutBlack, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(text, color = BrutBlack, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = BrutBlack, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
