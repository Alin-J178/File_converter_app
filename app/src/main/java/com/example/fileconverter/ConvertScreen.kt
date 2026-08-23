package com.example.fileconverter

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrutCream),
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
                    color = BrutBlack,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(BrutBlack))
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Select a category",
                color = BrutMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Image conversion card
            ConvertCategoryCard(
                icon = Icons.Filled.Image,
                iconBg = BrutGreen,
                title = "Image",
                subtitle = "PNG, JPEG, WebP, GIF, BMP \u00b7 PDF",
                onClick = onSelectImage,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Document conversion card
            ConvertCategoryCard(
                icon = Icons.Filled.Description,
                iconBg = BrutBlue,
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
) {
    val cardShape = RoundedCornerShape(20.dp)
    val buttonShape = RoundedCornerShape(12.dp)
    val blueBg = Color(0xFF5B9BD5)

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
                .border(3.dp, BrutBlack, cardShape)
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
                        .background(Color.White)
                        .border(2.dp, BrutBlack, buttonShape)
                        .clickable { onPickImages() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            tint = BrutBlack,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Select an image",
                            color = BrutBlack,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Image preview strip
                if (pickedBitmaps.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(buttonShape)
                            .background(Color.White.copy(alpha = 0.5f))
                            .border(2.dp, BrutBlack, buttonShape)
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
                                            .border(2.dp, BrutBlack, RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    // X button to remove
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(BrutBlack.copy(alpha = 0.7f))
                                            .clickable { onRemoveImage(index) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove",
                                            tint = Color.White,
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
                    OutputFormat.PNG to BrutGreen,
                    OutputFormat.JPEG to BrutYellow,
                    OutputFormat.WEBP to BrutPink,
                    OutputFormat.GIF to BrutPurple,
                    OutputFormat.BMP to BrutOrange,
                )

                formats.forEach { (format, color) ->
                    val isSelected = selectedFormat == format
                    val bgColor = if (isSelected) color else Color.White
                    val textColor = if (isSelected) BrutBlack else BrutBlack

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(buttonShape)
                            .background(bgColor)
                            .border(2.dp, BrutBlack, buttonShape)
                            .clickable { onFormatSelected(format) }
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
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = BrutBlack,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Run button (fixed at bottom)
                val runEnabled = selectedFormat != null && pickedBitmaps.isNotEmpty() && !busy
                val runBg = if (runEnabled) BrutGreen else BrutGrey.copy(alpha = 0.4f)
                val runBorder = if (runEnabled) BrutBlack else BrutGrey

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
                        color = BrutBlack,
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
) {
    val cardShape = RoundedCornerShape(20.dp)
    val buttonShape = RoundedCornerShape(12.dp)
    val blueBg = Color(0xFF5B9BD5)

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
                .border(3.dp, BrutBlack, cardShape)
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
                        .background(Color.White)
                        .border(2.dp, BrutBlack, buttonShape)
                        .clickable { onPickDocs() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = BrutBlack,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Select Document/Image",
                            color = BrutBlack,
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
                            .background(Color.White.copy(alpha = 0.5f))
                            .border(2.dp, BrutBlack, buttonShape)
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
                                            .border(2.dp, BrutBlack, RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    // X button to remove
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(BrutBlack.copy(alpha = 0.7f))
                                            .clickable { onRemoveItem(index) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                    // File name label at bottom
                                    if (index < pickedNames.size) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .background(BrutBlack.copy(alpha = 0.6f))
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = pickedNames[index].take(12) + if (pickedNames[index].length > 12) "..." else "",
                                                color = Color.White,
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

                // PDF format button (only option)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(buttonShape)
                        .background(BrutBlue)
                        .border(2.dp, BrutBlack, buttonShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "PDF",
                            color = BrutBlack,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = BrutBlack,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Run button
                val runEnabled = pickedBitmaps.isNotEmpty() && !busy
                val runBg = if (runEnabled) BrutGreen else BrutGrey.copy(alpha = 0.4f)
                val runBorder = if (runEnabled) BrutBlack else BrutGrey

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
                        color = BrutBlack,
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
    val cardBg = if (enabled) Color.White else Color.White.copy(alpha = 0.5f)
    val borderColor = if (enabled) BrutBlack else BrutGrey
    val titleColor = if (enabled) BrutBlack else BrutMuted

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
                .border(2.dp, BrutBlack, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BrutBlack, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = BrutMuted, fontSize = 12.sp)
        }
        if (enabled) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = BrutBlack, modifier = Modifier.size(22.dp))
        }
    }
}
