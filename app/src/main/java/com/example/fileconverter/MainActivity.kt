package com.example.fileconverter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class MainActivity : ComponentActivity() {

    private var sharedUris by mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUris = extractSharedUris(intent)
        setContent {
            MaterialTheme {
                FileConverterScreen(
                    sharedUris = sharedUris,
                    onSharedUrisHandled = { sharedUris = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUris = extractSharedUris(intent)
        if (!newUris.isNullOrEmpty()) sharedUris = newUris
    }

    private fun extractSharedUris(intent: Intent?): List<Uri>? {
        if (intent == null) return null
        val action = intent.action
        if (action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { return listOf(it) }
        }
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                if (it.isNotEmpty()) return it.toList()
            }
        }
        return null
    }
}

private const val MAX_IMAGES = 50

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileConverterScreen(
    sharedUris: List<Uri>?,
    onSharedUrisHandled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var previews by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var originalSize by remember { mutableStateOf(0L) }
    var originalDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var quality by remember { mutableFloatStateOf(85f) }
    var scalePercent by remember { mutableIntStateOf(100) }
    var docBusy by remember { mutableStateOf(false) }
    var pdfBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var convertedCount by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<ConversionResult>>(emptyList()) }
    var outputFormat by remember { mutableStateOf(OutputFormat.JPEG) }
    var showSettings by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }
    var recentFiles by remember { mutableStateOf<List<RecentFile>>(emptyList()) }
    var pendingWordUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingPdfUri by remember { mutableStateOf<Uri?>(null) }
    var libraryThumbs by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var librarySelectMode by remember { mutableStateOf(false) }
    var librarySelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showSuccess by remember { mutableStateOf(false) }
    var infoFile by remember { mutableStateOf<RecentFile?>(null) }
    var infoDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var infoPath by remember { mutableStateOf<String?>(null) }
    var infoEditing by remember { mutableStateOf(false) }
    var infoRenameText by remember { mutableStateOf("") }
    LaunchedEffect(infoFile) { infoEditing = false }


    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    var showTutorial by remember { mutableStateOf(!prefs.getBoolean("tutorial_done", false)) }

    fun loadSelection(uris: List<Uri>) {
        if (uris.isEmpty()) return
        selectedUris = uris
        pendingWordUris = emptyList()
        pendingPdfUri = null
        results = emptyList()
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val maxDim = if (uris.size == 1) 1200 else 200
                    val thumbs = uris.map { ImageConverter.decodeSampledBitmap(context, it, maxDim = maxDim) }
                    val total = uris.sumOf { ImageConverter.querySize(context, it) }
                    val dims = uris.firstOrNull()?.let { ImageConverter.queryDimensions(context, it) }
                    Triple(thumbs, total, dims)
                }
            }.onSuccess { (thumbs, total, dims) ->
                previews = thumbs
                originalSize = total
                originalDims = dims
            }.onFailure { e ->
                previews = emptyList()
                originalSize = 0
                originalDims = null
                Toast.makeText(context, "Could not load images: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris -> loadSelection(uris) }

    LaunchedEffect(sharedUris) {
        val uris = sharedUris
        if (!uris.isNullOrEmpty()) {
            loadSelection(uris)
            onSharedUrisHandled()
        }
    }

    fun convertDocx(uris: List<Uri>) {
        if (uris.isEmpty() || docBusy) return
        docBusy = true
        results = emptyList()
        convertedCount = 0
        scope.launch {
            val converted = mutableListOf<ConversionResult>()
            for (uri in uris) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val name = "converted_${System.currentTimeMillis()}.pdf"
                        val outUri = DocxToPdf.convert(context, uri, name)
                        ConversionResult(
                            outUri,
                            ImageConverter.querySize(context, outUri),
                            ImageConverter.displayPath(context, outUri),
                            OutputFormat.PDF,
                        )
                    }
                }.onSuccess {
                    converted.add(it)
                    convertedCount++
                }.onFailure { e ->
                    android.util.Log.e("FileConverter", "Word conversion failed", e)
                    Toast.makeText(context, "Could not convert: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            results = converted
            pendingWordUris = emptyList()
            if (converted.isNotEmpty()) showSuccess = true
            docBusy = false
        }
    }

    fun pick() {
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val pickDocx = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            pendingWordUris = uris
            pendingPdfUri = null
            selectedUris = emptyList()
            previews = emptyList()
            originalSize = 0
            originalDims = null
            results = emptyList()
        }
    }

    fun compressPdf(uri: Uri) {
        if (pdfBusy) return
        pdfBusy = true
        results = emptyList()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = "compressed_${System.currentTimeMillis()}.pdf"
                    val outUri = ImageConverter.compressPdf(context, uri, quality = quality.toInt(), scalePercent = scalePercent, displayName = name)
                    ConversionResult(
                        outUri,
                        ImageConverter.querySize(context, outUri),
                        ImageConverter.displayPath(context, outUri),
                        OutputFormat.PDF,
                    )
                }
            }.onSuccess {
                results = listOf(it)
                pendingPdfUri = null
                showSuccess = true
            }.onFailure { e ->
                Toast.makeText(context, "Could not compress PDF: ${e.message}", Toast.LENGTH_LONG).show()
            }
            pdfBusy = false
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingPdfUri = uri
            pendingWordUris = emptyList()
            selectedUris = emptyList()
            previews = emptyList()
            originalSize = ImageConverter.querySize(context, uri)
            originalDims = null
            results = emptyList()
        }
    }

    fun convert() {
        if (busy || selectedUris.isEmpty()) return
        busy = true
        results = emptyList()
        convertedCount = 0
        scope.launch {
            val converted = mutableListOf<ConversionResult>()
            for (uri in selectedUris) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        ImageConverter.convert(context, uri, outputFormat, quality = quality.toInt(), scalePercent = scalePercent)
                    }
                }.onSuccess {
                    converted.add(it)
                    convertedCount++
                }.onFailure { e ->
                    Toast.makeText(context, "Could not convert: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            results = converted
            if (converted.isNotEmpty()) {
                showSuccess = true
                // Reset the picker field back to its empty "Tap to select an image" state.
                selectedUris = emptyList()
                previews = emptyList()
                originalSize = 0
                originalDims = null
            }
            busy = false
        }
    }

    fun refreshLibrary() {
        scope.launch {
            recentFiles = withContext(Dispatchers.IO) {
                ImageConverter.recentConversions(context)
            }
            libraryThumbs = withContext(Dispatchers.IO) {
                recentFiles.associate { file ->
                    file.uri.toString() to (ImageConverter.renderThumbnail(context, file.uri, file.name) ?: ImageConverter.createPlaceholder(file.name))
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshLibrary() }

    // ──────────────────────────── UI ────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(BrutCream)) {
        // Background blobs — use BoxWithConstraints so they scale across screen sizes
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sw = maxWidth   // screen width in dp
            val sh = maxHeight  // screen height in dp
            val blobPink   = (sw * 0.38f).coerceAtLeast(140.dp)
            val blobPurple = (sw * 0.50f).coerceAtLeast(180.dp)
            val blobYellow = (sw * 0.34f).coerceAtLeast(120.dp)
            // Pink: top-left, mostly off-screen
            Box(
                modifier = Modifier.offset(x = -(blobPink * 0.45f), y = -(blobPink * 0.30f)).size(blobPink).background(BrutPink, CircleShape),
            )
            // Purple: bottom-right, mostly off right edge
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = blobPurple * 0.35f, y = blobPurple * 0.15f).size(blobPurple).background(BrutPurple, CircleShape),
            )
            // Yellow: bottom-left, mostly off left edge
            Box(
                modifier = Modifier.align(Alignment.BottomStart).offset(x = -(blobYellow * 0.55f), y = blobYellow * 0.10f).size(blobYellow).background(BrutYellow, CircleShape),
            )
        }

        if (!showRecent) Column(
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
                    onClick = { showSettings = true },
                )
                Spacer(modifier = Modifier.weight(1f))
                NeoIconButton(
                    icon = Icons.Filled.Folder,
                    contentDescription = "Your saved files",
                    onClick = { showRecent = true },
                )
            }

            // File selection row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NeoField(
                    onClick = {
                        when {
                            pendingWordUris.isNotEmpty() -> pickDocx.launch(
                                arrayOf(
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/msword",
                                )
                            )
                            pendingPdfUri != null -> pickPdf.launch(arrayOf("application/pdf"))
                            else -> pick()
                        }
                    },
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
                                text = "${ImageConverter.queryDisplayName(context, pendingPdfUri!!)} · tap to change",
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
                                text = if (selectedUris.size == 1) "Ready to convert" else "${selectedUris.size} images ready",
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
                val canConvert = (selectedUris.isNotEmpty() || pendingWordUris.isNotEmpty() || pendingPdfUri != null) && !busy && !docBusy && !pdfBusy
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
                            .clickable(enabled = canConvert) {
                                when {
                                    pendingWordUris.isNotEmpty() -> convertDocx(pendingWordUris)
                                    pendingPdfUri != null -> compressPdf(pendingPdfUri!!)
                                    else -> convert()
                                }
                            },
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

            if (busy && selectedUris.size > 1) {
                Text(text = "Converting $convertedCount/${selectedUris.size}…", color = BrutMuted, fontSize = 13.sp)
            } else if (docBusy && pendingWordUris.isNotEmpty()) {
                Text(text = "Converting Word files $convertedCount/${pendingWordUris.size}…", color = BrutMuted, fontSize = 13.sp)
            } else if (pendingWordUris.isNotEmpty() && !docBusy) {
                Text(
                    text = if (pendingWordUris.size == 1) "Word file selected — tap + to convert it to PDF." else "${pendingWordUris.size} Word files — tap + to convert all to PDF.",
                    color = BrutMuted, fontSize = 13.sp,
                )
            } else if (pendingPdfUri != null && !pdfBusy) {
                Text(text = "PDF selected — adjust quality/resize below, then tap + to compress.", color = BrutMuted, fontSize = 13.sp)
            } else if (selectedUris.isEmpty()) {
                Text(text = "Pick one or more images, choose a format, tap + to convert.", color = BrutMuted, fontSize = 13.sp)
            }

            // Convert to
            SectionTitle("Convert to")
            ActionRow(text = "PNG", icon = Icons.Filled.PhotoLibrary, accent = BrutGreen, selected = outputFormat == OutputFormat.PNG, busy = busy || docBusy, onClick = { outputFormat = OutputFormat.PNG })
            ActionRow(text = "JPEG", icon = Icons.Filled.Image, accent = BrutYellow, selected = outputFormat == OutputFormat.JPEG, busy = busy || docBusy, onClick = { outputFormat = OutputFormat.JPEG })
            ActionRow(text = "WebP", icon = Icons.Filled.Photo, accent = BrutPink, selected = outputFormat == OutputFormat.WEBP, busy = busy || docBusy, onClick = { outputFormat = OutputFormat.WEBP })
            ActionRow(text = "PDF", icon = Icons.Filled.PictureAsPdf, accent = BrutBlue, selected = outputFormat == OutputFormat.PDF, busy = busy || docBusy, onClick = { outputFormat = OutputFormat.PDF })

            Spacer(modifier = Modifier.height(4.dp))
            ActionRow(text = "Word - PDF", icon = Icons.Filled.Description, accent = BrutOrange, busy = busy || docBusy, onClick = {
                pendingWordUris = emptyList()
                pendingPdfUri = null
                selectedUris = emptyList()
                previews = emptyList()
                pickDocx.launch(
                    arrayOf(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/msword",
                    )
                )
            })

            Spacer(modifier = Modifier.height(4.dp))

            // Compression
            SectionTitle("Compression")
            Text(text = "Pick a PDF file to compress, or use quality/resize for image conversion", color = BrutMuted, fontSize = 13.sp)

            NeoButton(
                text = "Pick PDF to compress",
                onClick = {
                    pendingWordUris = emptyList()
                    pendingPdfUri = null
                    selectedUris = emptyList()
                    previews = emptyList()
                    results = emptyList()
                    pickPdf.launch(arrayOf("application/pdf"))
                },
                modifier = Modifier.fillMaxWidth(),
                height = 48.dp,
            )

            // Quality + Resize settings card
            NeoCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Quality
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (outputFormat == OutputFormat.PNG) "PNG is lossless" else "${outputFormat.label} quality: ${quality.toInt()}%",
                            color = BrutBlack, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        if (outputFormat != OutputFormat.PNG && quality.toInt() != 85) {
                            NeoButton(text = "Reset", onClick = { quality = 85f }, height = 32.dp, backgroundColor = BrutGrey)
                        }
                    }
                    NeoSlider(value = quality, onValueChange = { quality = it }, modifier = Modifier.fillMaxWidth())
                    if (outputFormat != OutputFormat.PNG) {
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
                                onClick = { scalePercent = pct },
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

        // ─── Hamburger drawer ───
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(280)),
            exit = slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(240)),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0x66000000)).clickable { showSettings = false },
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
                        DrawerItem(icon = Icons.Filled.Image, label = "Convert Images", description = "PNG, JPEG, WebP")
                        DrawerItem(icon = Icons.Filled.Description, label = "Word to PDF", description = "Convert .doc/.docx to PDF")
                        DrawerItem(icon = Icons.Filled.PictureAsPdf, label = "Compress PDF", description = "Reduce PDF file size")
                        DrawerItem(icon = Icons.Filled.Folder, label = "Saved Files", description = "Browse converted files")
                        Spacer(modifier = Modifier.height(20.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(BrutBlack))
                        Spacer(modifier = Modifier.height(20.dp))
                        Text("About", color = BrutBlack, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Convert & compress images to JPEG, PNG, WebP, or PDF. Images save to Pictures/FileConverter; PDFs to Download/FileConverter.",
                            color = BrutMuted, fontSize = 12.sp, lineHeight = 16.sp,
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        NeoButton(text = "Close", onClick = { showSettings = false }, modifier = Modifier.fillMaxWidth(), height = 44.dp)
                    }
                }
            }
        }

        // ─── Library ───
        if (showRecent) {
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
                            onClick = { showRecent = false },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Saved in Storage",
                            color = BrutBlack,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        NeoIconButton(
                            icon = Icons.Filled.Check,
                            contentDescription = if (librarySelectMode) "Done" else "Select",
                            onClick = {
                                if (librarySelectMode) {
                                    librarySelectMode = false
                                    librarySelected = emptySet()
                                } else {
                                    librarySelectMode = true
                                }
                            },
                            backgroundColor = if (librarySelectMode) BrutBlack else Color.White,
                            iconTint = if (librarySelectMode) Color.White else BrutBlack,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        NeoIconButton(
                            icon = Icons.Filled.Delete,
                            contentDescription = "Delete selected",
                            onClick = {
                                scope.launch {
                                    librarySelected.forEach { uriStr ->
                                        val uri = Uri.parse(uriStr)
                                        ImageConverter.deleteFile(context, uri)
                                    }
                                    librarySelected = emptySet()
                                    librarySelectMode = false
                                    refreshLibrary()
                                }
                            },
                            backgroundColor = BrutPink,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        NeoIconButton(
                            icon = Icons.Filled.Share,
                            contentDescription = "Share",
                            onClick = {
                                if (librarySelected.isNotEmpty()) {
                                    val uris = librarySelected.map { Uri.parse(it) }
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
                                    chunks, libraryThumbs, librarySelectMode, librarySelected,
                                    onToggleSelect = { uri ->
                                        librarySelected = if (uri in librarySelected) librarySelected - uri else librarySelected + uri
                                    },
                                    onClick = { file ->
                                        if (!librarySelectMode) {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(file.uri, context.contentResolver.getType(file.uri) ?: "*/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            try { context.startActivity(intent) } catch (_: Exception) {
                                                Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onLongPress = { file, dims, path ->
                                        infoFile = file
                                        infoDims = dims
                                        infoPath = path
                                    },
                                )
                            }
                        }
                    }
                    // Clear All button
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        NeoButton(
                            text = "Clear All",
                            onClick = {
                                scope.launch {
                                    recentFiles.forEach { ImageConverter.deleteFile(context, it.uri) }
                                    refreshLibrary()
                                }
                            },
                            modifier = Modifier.width(180.dp),
                            height = 44.dp,
                            backgroundColor = BrutGrey,
                        )
                    }
                }
            }
        }

        // ─── Info overlay (long-press) ───
        if (infoFile != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0x99000000)).clickable { infoFile = null; infoEditing = false },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .border(3.dp, BrutBlack, RoundedCornerShape(20.dp))
                    .padding(20.dp)
                    .zIndex(2f),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val ext = infoFile!!.name.substringAfterLast('.', "").uppercase()
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
                            if (infoEditing) {
                                val ctx = LocalContext.current
                                BasicTextField(
                                    value = infoRenameText,
                                    onValueChange = { infoRenameText = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(2.dp, BrutBlack, RoundedCornerShape(4.dp))
                                        .padding(4.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(color = BrutBlack, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                                    singleLine = true,
                                )
                            } else {
                                Text(
                                    text = infoFile!!.name,
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
                                .background(if (infoEditing) BrutYellow else Color.White)
                                .border(2.dp, BrutBlack, RoundedCornerShape(6.dp))
                                .clickable {
                                    if (infoEditing) {
                                        if (infoRenameText.isNotBlank() && infoFile != null) {
                                            val ctx = context
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    ImageConverter.renameFile(ctx, infoFile!!.uri, infoRenameText.trim())
                                                }
                                                infoEditing = false
                                                infoFile = null
                                                refreshLibrary()
                                            }
                                        }
                                    } else {
                                        infoRenameText = infoFile!!.name.substringBeforeLast('.')
                                        infoEditing = true
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (infoEditing) Icons.Filled.Check else Icons.Filled.Edit,
                                contentDescription = if (infoEditing) "Save" else "Rename",
                                tint = BrutBlack,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (infoDims != null) {
                        InfoRow("Dimensions", "${infoDims!!.first} \u00d7 ${infoDims!!.second} px")
                    }
                    InfoRow("Size", formatBytes(infoFile!!.sizeBytes))
                    if (infoPath != null) {
                        InfoRow("Location", infoPath!!)
                    }
                    if (infoFile!!.dateAdded > 0) {
                        InfoRow("Saved", formatTimestamp(infoFile!!.dateAdded))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        NeoButton(text = "Open", onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(infoFile!!.uri, context.contentResolver.getType(infoFile!!.uri) ?: "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }, modifier = Modifier.weight(1f), height = 40.dp)
                        NeoButton(text = "Close", onClick = { infoFile = null; infoEditing = false }, modifier = Modifier.weight(1f), height = 40.dp, backgroundColor = Color.White)
                    }
                }
            }
        }

        // ─── Conversion success overlay ───
        if (showSuccess) {
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
                        onClick = {
                            showSuccess = false
                            results = emptyList()
                            pendingWordUris = emptyList()
                            pendingPdfUri = null
                            refreshLibrary()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        height = 48.dp,
                    )
                }
            }
        }        // ─── Tutorial (popup card overlay) ───
        if (showTutorial) {
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
                                    0 -> Quadruple(Icons.Filled.Image, "Welcome to File Converter", "Convert & compress images to JPEG, PNG, WebP or PDF. Everything stays on your device \u2014 nothing is uploaded.", BrutYellow)
                                    1 -> Quadruple(Icons.Filled.PhotoLibrary, "Convert to any format", "Pick an image, choose your target format under Convert to, then tap +. You can also convert Word documents to PDF the same way.", BrutGreen)
                                    2 -> Quadruple(Icons.Filled.PictureAsPdf, "Compress anything", "The quality slider and resize buttons apply to your selected format. Pick PNG/JPEG/WebP above, then adjust the sliders. For PDFs, tap Pick PDF to compress, adjust sliders, then tap +.", BrutBlue)
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
                                        showTutorial = false
                                        prefs.edit().putBoolean("tutorial_done", true).apply()
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
                                modifier = Modifier.clickable {
                                    showTutorial = false
                                    prefs.edit().putBoolean("tutorial_done", true).apply()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Helpers ───

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, color = BrutMuted, fontSize = 11.sp)
        Text(value, color = BrutBlack, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, description: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { }.padding(vertical = 12.dp, horizontal = 4.dp),
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

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun formatTimestamp(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, yyyy \u00b7 h:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${DecimalFormat("#.#").format(kb)} KB"
    val mb = kb / 1024.0
    return "${DecimalFormat("#.##").format(mb)} MB"
}

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
