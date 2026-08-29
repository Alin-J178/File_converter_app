package com.example.fileconverter

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConvertScreen(
    onBack: () -> Unit,
    onSelectImage: () -> Unit,
    onSelectDocument: () -> Unit,
    showImagePicker: Boolean = false,
    showDocPicker: Boolean = false,
    onImagePickerDismiss: () -> Unit = {},
    onDocPickerDismiss: () -> Unit = {},
    pickedImageBitmaps: List<android.graphics.Bitmap> = emptyList(),
    pickedDocBitmaps: List<android.graphics.Bitmap> = emptyList(),
    pickedDocNames: List<String> = emptyList(),
    onRemoveImage: (Int) -> Unit = {},
    onRemoveDoc: (Int) -> Unit = {},
    onPickImages: () -> Unit = {},
    onPickDocs: () -> Unit = {},
    selectedFormat: OutputFormat? = null,
    onFormatSelected: (OutputFormat) -> Unit = {},
    onRun: () -> Unit = {},
    onDocRun: () -> Unit = {},
    busy: Boolean = false,
    docBusy: Boolean = false,
    selectedDocOutput: String? = null,
    onDocOutputSelected: (String?) -> Unit = {},
    favouriteFormats: Set<OutputFormat> = emptySet(),
    onToggleFavourite: (OutputFormat) -> Unit = {},
) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                NeoIconButton(
                    icon = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Convert",
                    color = colors.onBackground,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(colors.border))
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Select a category",
                color = colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Image conversion card
            ConvertCategoryCard(
                icon = Icons.Filled.Image,
                iconBg = colors.green,
                title = "Image",
                subtitle = "PNG, JPEG, WebP, GIF, BMP \u00b7 PDF",
                onClick = onSelectImage,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Document conversion card
            ConvertCategoryCard(
                icon = Icons.Filled.Description,
                iconBg = colors.blue,
                title = "Document",
                subtitle = "Word, Images \u00b7 PDF",
                onClick = onSelectDocument,
            )
        }

        // Image conversion overlay
        if (showImagePicker) {
            ImageConvertOverlay(
                onDismiss = onImagePickerDismiss,
                pickedBitmaps = pickedImageBitmaps,
                onRemoveImage = onRemoveImage,
                onPickImages = onPickImages,
                selectedFormat = selectedFormat,
                onFormatSelected = onFormatSelected,
                onRun = onRun,
                busy = busy,
                favouriteFormats = favouriteFormats,
                onToggleFavourite = onToggleFavourite,
            )
        }

        // Document conversion overlay
        if (showDocPicker) {
            DocConvertOverlay(
                onDismiss = onDocPickerDismiss,
                pickedBitmaps = pickedDocBitmaps,
                pickedNames = pickedDocNames,
                onRemoveItem = onRemoveDoc,
                onPickDocs = onPickDocs,
                onRun = onDocRun,
                busy = docBusy,
                selectedDocOutput = selectedDocOutput,
                onDocOutputSelected = onDocOutputSelected,
                favouriteFormats = favouriteFormats,
                onToggleFavourite = onToggleFavourite,
            )
        }
    }
}

@Composable
private fun ImageConvertOverlay(
    onDismiss: () -> Unit,
    pickedBitmaps: List<android.graphics.Bitmap>,
    onRemoveImage: (Int) -> Unit,
    onPickImages: () -> Unit,
    selectedFormat: OutputFormat?,
    onFormatSelected: (OutputFormat) -> Unit,
    onRun: () -> Unit,
    busy: Boolean,
    favouriteFormats: Set<OutputFormat> = emptySet(),
    onToggleFavourite: (OutputFormat) -> Unit = {},
) {
    val cardShape = RoundedCornerShape(20.dp)
    val buttonShape = RoundedCornerShape(12.dp)
    val blueBg = Color(0xFF5B9BD5)
    val colors = LocalAppColors.current

    Box(modifier = Modifier.fillMaxSize()) {
        // Dim scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable { onDismiss() },
        )

        // Overlay card
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
                .clip(cardShape)
                .background(blueBg)
                .border(3.dp, colors.border, cardShape)
                .clickable(enabled = false) { /* consume clicks */ }
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Select image button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(buttonShape)
                        .background(colors.surface)
                        .border(2.dp, colors.border, buttonShape)
                        .clickable { onPickImages() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            tint = colors.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Select an image",
                            color = colors.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Supported input formats (depends on selected output)
                val imageSupportedInput = when (selectedFormat) {
                    OutputFormat.JPEG -> "PNG • WebP • GIF • BMP • TIFF • HEIF • AVIF • SVG"
                    OutputFormat.PNG -> "JPEG • WebP • GIF • BMP • TIFF • HEIF • AVIF • SVG"
                    OutputFormat.WEBP -> "JPEG • PNG • GIF • BMP • TIFF • HEIF • AVIF • SVG"
                    OutputFormat.GIF -> "JPEG • PNG • WebP • BMP • TIFF • HEIF • AVIF • SVG"
                    OutputFormat.BMP -> "JPEG • PNG • WebP • GIF • TIFF • HEIF • AVIF • SVG"
                    OutputFormat.TIFF -> "JPEG • PNG • WebP • GIF • BMP • HEIF • AVIF • SVG"
                    OutputFormat.HEIF -> "JPEG • PNG • WebP • GIF • BMP • TIFF • AVIF • SVG"
                    OutputFormat.AVIF -> "JPEG • PNG • WebP • GIF • BMP • TIFF • HEIF • SVG"
                    OutputFormat.SVG -> "JPEG • PNG • WebP • GIF • BMP • TIFF • HEIF • AVIF"
                    OutputFormat.PDF, null -> "JPEG • PNG • WebP • GIF • BMP • TIFF • HEIF • AVIF • SVG"
                    else -> ""
                }
                Text(
                    text = "Converts to ${selectedFormat?.label ?: "?"} from: $imageSupportedInput",
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )

                // Image preview strip
                if (pickedBitmaps.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(buttonShape)
                            .background(colors.surface.copy(alpha = 0.5f))
                            .border(2.dp, colors.border, buttonShape)
                            .padding(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pickedBitmaps.forEachIndexed { index, bitmap ->
                                Box {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Preview",
                                        modifier = Modifier
                                            .size(104.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(2.dp, colors.border, RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    // X button to remove
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(colors.border.copy(alpha = 0.7f))
                                            .clickable { onRemoveImage(index) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove",
                                            tint = colors.surface,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Format buttons
                val formats = listOf(
                    OutputFormat.PNG to colors.green,
                    OutputFormat.JPEG to colors.accent,
                    OutputFormat.WEBP to colors.pink,
                    OutputFormat.GIF to colors.purple,
                    OutputFormat.BMP to BrutOrange,
                    OutputFormat.TIFF to Color(0xFF8D6E63), // brown
                    OutputFormat.HEIF to Color(0xFF7E57C2), // deep purple
                    OutputFormat.AVIF to Color(0xFF00897B), // teal
                    OutputFormat.SVG to Color(0xFFEF6C00), // orange
                )

                @OptIn(ExperimentalFoundationApi::class)
                formats.forEach { (format, color) ->
                    val isSelected = selectedFormat == format
                    val isFavourite = format in favouriteFormats
                    val bgColor = if (isSelected) color else colors.surface
                    val textColor = colors.onSurface

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(buttonShape)
                            .background(bgColor)
                            .border(2.dp, colors.border, buttonShape)
                            .combinedClickable(
                                onClick = { onFormatSelected(format) },
                                onLongClick = { onToggleFavourite(format) },
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = format.label,
                                color = textColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isFavourite) {
                                    Icon(
                                        Icons.Filled.Favorite,
                                        contentDescription = "Favourite",
                                        tint = Color.Red,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = colors.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Run button (fixed at bottom)
                val runEnabled = selectedFormat != null && pickedBitmaps.isNotEmpty() && !busy
                val runBg = if (runEnabled) colors.green else colors.muted.copy(alpha = 0.4f)
                val runBorder = if (runEnabled) colors.border else colors.muted

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(buttonShape)
                        .background(runBg)
                        .border(2.dp, runBorder, buttonShape)
                        .clickable(enabled = runEnabled) { onRun() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (busy) "Converting..." else "Run",
                        color = colors.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun DocConvertOverlay(
    onDismiss: () -> Unit,
    pickedBitmaps: List<android.graphics.Bitmap>,
    pickedNames: List<String>,
    onRemoveItem: (Int) -> Unit,
    onPickDocs: () -> Unit,
    onRun: () -> Unit,
    busy: Boolean,
    selectedDocOutput: String? = null,
    onDocOutputSelected: (String?) -> Unit = {},
    favouriteFormats: Set<OutputFormat> = emptySet(),
    onToggleFavourite: (OutputFormat) -> Unit = {},
) {
    val cardShape = RoundedCornerShape(20.dp)
    val buttonShape = RoundedCornerShape(12.dp)
    val blueBg = Color(0xFF5B9BD5)
    val colors = LocalAppColors.current

    Box(modifier = Modifier.fillMaxSize()) {
        // Dim scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable { onDismiss() },
        )

        // Overlay card
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
                .clip(cardShape)
                .background(blueBg)
                .border(3.dp, colors.border, cardShape)
                .clickable(enabled = false) { /* consume clicks */ }
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Select document/image button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(buttonShape)
                        .background(colors.surface)
                        .border(2.dp, colors.border, buttonShape)
                        .clickable { onPickDocs() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = colors.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Select Document/Image",
                            color = colors.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Preview strip
                if (pickedBitmaps.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(buttonShape)
                            .background(colors.surface.copy(alpha = 0.5f))
                            .border(2.dp, colors.border, buttonShape)
                            .padding(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pickedBitmaps.forEachIndexed { index, bitmap ->
                                Box {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Preview",
                                        modifier = Modifier
                                            .size(104.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(2.dp, colors.border, RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    // X button to remove
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(colors.border.copy(alpha = 0.7f))
                                            .clickable { onRemoveItem(index) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove",
                                            tint = colors.surface,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                    // File name label at bottom
                                    if (index < pickedNames.size) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .background(colors.border.copy(alpha = 0.6f))
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = pickedNames[index].take(12) + if (pickedNames[index].length > 12) "..." else "",
                                                color = colors.surface,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Supported input formats (depends on selected output)
                val docSupportedInput = when (selectedDocOutput) {
                    "PDF" -> "DOC/DOCX • TXT • RTF • MD • HTML • ODT • CSV • PPT • Images"
                    "DOCX" -> "DOC • ODT • RTF • TXT • MD • HTML"
                    "XLSX" -> "CSV"
                    "PPTX" -> "PPT"
                    else -> "DOC/DOCX • TXT • RTF • MD • HTML • ODT • CSV • PPT • Images"
                }
                Text(
                    text = "Converts to $selectedDocOutput from:",
                    color = colors.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = docSupportedInput,
                    color = colors.onSurface,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )

                // Output format options
                Text(
                    text = "Output:",
                    color = colors.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                
                val docFormats = listOf(
                    Pair("PDF", colors.blue),
                    Pair("DOCX", Color(0xFF2B579A)),
                    Pair("XLSX", Color(0xFF217346)),
                    Pair("PPTX", Color(0xFFD04423)),
                )
                val docFormatMap = mapOf(
                    "PDF" to OutputFormat.PDF,
                    "DOCX" to OutputFormat.DOCX,
                    "XLSX" to OutputFormat.XLSX,
                    "PPTX" to OutputFormat.PPTX,
                )
                docFormats.forEach { (label, color) ->
                    val isSelected = selectedDocOutput == label
                    val isFavourite = docFormatMap[label] in favouriteFormats
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(buttonShape)
                            .background(if (isSelected) color else colors.surface)
                            .border(2.dp, colors.border, buttonShape)
                            .combinedClickable(
                                onClick = { onDocOutputSelected(label) },
                                onLongClick = { docFormatMap[label]?.let { onToggleFavourite(it) } },
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                color = colors.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isFavourite) {
                                    Icon(
                                        Icons.Filled.Favorite,
                                        contentDescription = "Favourite",
                                        tint = Color(0xFFE91E63),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = colors.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Format note
                Text(
                    text = "Text docs → PDF • DOC/ODT/RTF/TXT → DOCX • CSV → XLSX • PPT → PPTX",
                    color = colors.onSurface.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Run button
                val runEnabled = pickedBitmaps.isNotEmpty() && !busy
                val runBg = if (runEnabled) colors.green else colors.muted.copy(alpha = 0.4f)
                val runBorder = if (runEnabled) colors.border else colors.muted

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(buttonShape)
                        .background(runBg)
                        .border(2.dp, runBorder, buttonShape)
                        .clickable(enabled = runEnabled) { onRun() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (busy) "Converting..." else "Run",
                        color = colors.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConvertCategoryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val cardBg = if (enabled) colors.surface else colors.surface.copy(alpha = 0.5f)
    val borderColor = if (enabled) colors.border else colors.muted
    val titleColor = if (enabled) colors.onSurface else colors.muted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(3.dp, borderColor, RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg)
                .border(2.dp, colors.border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.muted, fontSize = 12.sp)
        }
        if (enabled) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}
