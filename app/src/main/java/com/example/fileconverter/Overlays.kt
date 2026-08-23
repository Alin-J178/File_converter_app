package com.example.fileconverter

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/** Hamburger settings drawer with the app info. */
@Composable
internal fun SettingsDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConvert: () -> Unit = {},
    onSavedFiles: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(280)),
        exit = slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(240)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0x66000000)).clickable { onDismiss() },
            )
            val drawerShape = RoundedCornerShape(0.dp)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .shadow(12.dp)
                    .background(BrutCream)
                    .border(3.dp, BrutBlack, drawerShape)
                    .zIndex(1f)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(BrutYellow).border(2.dp, BrutBlack, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("FC", color = BrutBlack, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("File Converter", color = BrutBlack, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text("v3.1", color = BrutMuted, fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(BrutBlack))
                    Spacer(modifier = Modifier.height(20.dp))
                    DrawerItem(icon = Icons.Filled.SwapHoriz, label = "Convert", description = "Image, Word, PDF conversions", onClick = { onDismiss(); onConvert() })
                    Spacer(modifier = Modifier.height(4.dp))
                    DrawerItem(icon = Icons.Filled.Folder, label = "Saved Files", description = "Browse converted files", onClick = { onDismiss(); onSavedFiles() })
                    DrawerItem(icon = Icons.Filled.Settings, label = "Settings", description = "App preferences", onClick = { onDismiss(); onSettings() })
                    Spacer(modifier = Modifier.height(20.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(BrutBlack))
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("About", color = BrutBlack, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Convert & compress images to JPEG, PNG, WebP, GIF, BMP, or PDF. Images save to Pictures/FileConverter; PDFs to Download/FileConverter.",
                        color = BrutMuted, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    NeoButton(text = "Close", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), height = 44.dp)
                }
            }
        }
    }
}

/** Info + rename dialog shown when long-pressing a file in the library. */
@Composable
internal fun FileInfoOverlay(
    file: RecentFile,
    dims: Pair<Int, Int>?,
    path: String?,
    editing: Boolean,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onEditClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Full-screen wrapper (zIndex above the library screen) hosting the dim scrim and the card.
    Box(modifier = Modifier.fillMaxSize().zIndex(11f)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0x99000000)).clickable { onDismiss() },
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(3.dp, BrutBlack, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ext = file.name.substringAfterLast('.', "").uppercase()
                val badgeColor = when (ext) {
                    "JPEG", "JPG" -> BrutYellow
                    "PNG" -> BrutBlue
                    "WEBP" -> BrutGreen
                    "PDF" -> BrutPink
                    else -> BrutMuted
                }
                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(badgeColor).padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(ext, color = BrutBlack, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (editing) {
                        BasicTextField(
                            value = renameText,
                            onValueChange = onRenameTextChange,
                            modifier = Modifier
                                .weight(1f)
                                .border(2.dp, BrutBlack, RoundedCornerShape(4.dp))
                                .padding(4.dp),
                            textStyle = TextStyle(color = BrutBlack, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            singleLine = true,
                        )
                    } else {
                        Text(
                            text = file.name,
                            color = BrutBlack, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (editing) BrutYellow else Color.White)
                        .border(2.dp, BrutBlack, RoundedCornerShape(6.dp))
                        .clickable(onClick = onEditClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                        contentDescription = if (editing) "Save" else "Rename",
                        tint = BrutBlack,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (dims != null) {
                InfoRow("Dimensions", "${dims.first} \u00d7 ${dims.second} px")
            }
            InfoRow("Size", formatBytes(file.sizeBytes))
            if (path != null) {
                InfoRow("Location", path)
            }
            if (file.dateAdded > 0) {
                InfoRow("Saved", formatTimestamp(file.dateAdded))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NeoButton(text = "Open", onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(file.uri, context.contentResolver.getType(file.uri) ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }, modifier = Modifier.weight(1f), height = 40.dp)
                NeoButton(text = "Close", onClick = onDismiss, modifier = Modifier.weight(1f), height = 40.dp, backgroundColor = Color.White)
            }
            }
        }
    }
}

/** "Conversion Complete!" dialog listing the converted files. */
@Composable
internal fun SuccessOverlay(results: List<ConversionResult>, onAwesome: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0x99000000)).clickable { },
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF7EC8FF))
                .border(3.dp, BrutBlack, RoundedCornerShape(24.dp))
                .padding(28.dp),
        ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF34D399)).border(3.dp, BrutBlack, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Text("Conversion Complete!", color = BrutBlack, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("Your file${if (results.size > 1) "s are" else " is"} ready.", color = BrutMuted, fontSize = 14.sp)
            results.forEach { r ->
                Text(
                    text = r.path.substringAfterLast('/'),
                    color = BrutBlack, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            NeoButton(
                text = "Awesome",
                onClick = onAwesome,
                modifier = Modifier.fillMaxWidth(),
                height = 48.dp,
            )
        }
        }
    }
}

/** First-run onboarding pager shown until the user dismisses it. */
@Composable
internal fun TutorialOverlay(onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x99000000)).clickable { /* consume taps */ },
        contentAlignment = Alignment.Center,
    ) {
        NeoCard(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            onClick = {},
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Dots at top
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dotColors = listOf(BrutYellow, BrutGreen, BrutBlue, BrutPurple)
                    repeat(4) { i ->
                        Box(
                            modifier = Modifier
                                .size(if (pagerState.currentPage == i) 12.dp else 8.dp)
                                .clip(CircleShape)
                                .background(if (pagerState.currentPage == i) dotColors[i] else BrutGrey)
                                .border(2.dp, BrutBlack, CircleShape),
                        )
                    }
                }
                // Pager content
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(300.dp)) { page ->
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val (icon, title, body, color) = when (page) {
                            0 -> Quadruple(Icons.Filled.Image, "Welcome to File Converter", "Convert & compress images to JPEG, PNG, WebP, GIF, BMP or PDF. Everything stays on your device \u2014 nothing is uploaded.", BrutYellow)
                            1 -> Quadruple(Icons.Filled.PhotoLibrary, "Convert to any format", "Pick an image, choose your target format under Convert to, then tap +. You can also convert Word documents to PDF the same way.", BrutGreen)
                            2 -> Quadruple(Icons.Filled.PictureAsPdf, "Compress anything", "The quality slider and resize buttons apply to your selected format. Pick PNG/JPEG/WebP/GIF/BMP above, then adjust the sliders. For PDFs, tap Pick PDF to compress, adjust sliders, then tap +.", BrutBlue)
                            else -> Quadruple(Icons.Filled.Folder, "Your converted files", "The folder icon shows your saved files. Long-press any file for details, rename, or open.", BrutPurple)
                        }
                        Box(
                            modifier = Modifier.size(80.dp).clip(CircleShape).background(color).border(3.dp, BrutBlack, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = null, tint = BrutBlack, modifier = Modifier.size(40.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(title, color = BrutBlack, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(body, color = BrutMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                // Buttons
                NeoButton(
                    text = if (pagerState.currentPage == 3) "Let\u2019s go!" else "Next",
                    onClick = {
                        scope.launch {
                            if (pagerState.currentPage < 3) {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            } else {
                                onFinish()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    height = 48.dp,
                )
                if (pagerState.currentPage < 3) {
                    Text(
                        text = "Skip",
                        color = BrutMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onFinish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, color = BrutMuted, fontSize = 11.sp)
        Text(value, color = BrutBlack, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(BrutYellow).border(2.dp, BrutBlack, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BrutBlack, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = BrutBlack, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(description, color = BrutMuted, fontSize = 11.sp)
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
