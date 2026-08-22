package com.example.fileconverter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * Root of the app UI: owns all state and conversion logic, and composes the individual
 * screens ([MainScreen], [LibraryScreen], [SettingsDrawer], overlays) inside a common
 * neo-brutalist background.
 */
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

    // Pie-chart slices derived from saved files on the phone
    val pieChartSlices by remember {
        derivedStateOf {
            val counts = countFormatsByExtension(recentFiles)
            val colorMap = mapOf(
                "PNG" to BrutGreen,
                "JPEG" to BrutYellow,
                "WebP" to BrutPink,
                "GIF" to BrutPurple,
                "BMP" to BrutOrange,
                "PDF" to BrutBlue,
            )
            counts.map { (label, count) ->
                PieSlice(label, count.toFloat(), colorMap[label] ?: BrutGrey)
            }
        }
    }

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
        BackgroundBlobs()

        if (!showRecent) {
            MainScreen(
                previews = previews,
                selectedCount = selectedUris.size,
                originalSize = originalSize,
                pendingWordUris = pendingWordUris,
                pendingPdfUri = pendingPdfUri,
                outputFormat = outputFormat,
                quality = quality,
                scalePercent = scalePercent,
                originalDims = originalDims,
                busy = busy,
                docBusy = docBusy,
                pdfBusy = pdfBusy,
                convertedCount = convertedCount,
                pieChartSlices = pieChartSlices,
                onMenu = { showSettings = true },
                onOpenLibrary = { showRecent = true },
                onFieldClick = {
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
                onConvert = {
                    when {
                        pendingWordUris.isNotEmpty() -> convertDocx(pendingWordUris)
                        pendingPdfUri != null -> compressPdf(pendingPdfUri!!)
                        else -> convert()
                    }
                },
                onPickWord = {
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
                },
                onPickPdf = {
                    pendingWordUris = emptyList()
                    pendingPdfUri = null
                    selectedUris = emptyList()
                    previews = emptyList()
                    results = emptyList()
                    pickPdf.launch(arrayOf("application/pdf"))
                },
                onFormatSelected = { outputFormat = it },
                onQualityChanged = { quality = it },
                onResetQuality = { quality = 85f },
                onScaleChanged = { scalePercent = it },
            )
        }

        SettingsDrawer(
            visible = showSettings,
            onDismiss = { showSettings = false },
        )

        if (showRecent) {
            LibraryScreen(
                recentFiles = recentFiles,
                thumbs = libraryThumbs,
                selectMode = librarySelectMode,
                selected = librarySelected,
                onToggleSelect = { uri ->
                    librarySelected = if (uri in librarySelected) librarySelected - uri else librarySelected + uri
                },
                onBack = { showRecent = false; infoFile = null; infoEditing = false },
                onToggleSelectMode = {
                    if (librarySelectMode) {
                        librarySelectMode = false
                        librarySelected = emptySet()
                    } else {
                        librarySelectMode = true
                    }
                },
                onDeleteSelected = {
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
                onLongPress = { file, dims, path ->
                    infoFile = file
                    infoDims = dims
                    infoPath = path
                },
                onClearAll = {
                    scope.launch {
                        recentFiles.forEach { ImageConverter.deleteFile(context, it.uri) }
                        refreshLibrary()
                    }
                },
            )
        }

        // Info overlay — only inside the Saved in Storage screen
        if (showRecent && infoFile != null) {
            FileInfoOverlay(
                file = infoFile!!,
                dims = infoDims,
                path = infoPath,
                editing = infoEditing,
                renameText = infoRenameText,
                onRenameTextChange = { infoRenameText = it },
                onEditClick = {
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
                onDismiss = { infoFile = null; infoEditing = false },
            )
        }

        if (showSuccess) {
            SuccessOverlay(
                results = results,
                onAwesome = {
                    showSuccess = false
                    results = emptyList()
                    pendingWordUris = emptyList()
                    pendingPdfUri = null
                    refreshLibrary()
                },
            )
        }

        if (showTutorial) {
            TutorialOverlay(
                onFinish = {
                    showTutorial = false
                    prefs.edit().putBoolean("tutorial_done", true).apply()
                },
            )
        }
    }
}

/** Decorative neo-brutalist circles peeking in from the screen corners. */
@Composable
private fun BackgroundBlobs() {
    // Use BoxWithConstraints so they scale across screen sizes
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sw = maxWidth   // screen width in dp
        val sh = maxHeight  // screen height in dp
        val blobPink = (sw * 0.38f).coerceAtLeast(140.dp)
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
}
