package com.ugviewer.ui.viewer

import android.graphics.Bitmap
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ugviewer.ui.theme.*
import com.ugviewer.util.PdfGenerator
import com.ugviewer.viewmodel.TabViewerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabViewerScreen(
    tabId: Long,
    onBack: () -> Unit,
    viewModel: TabViewerViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Keep the screen on (no dimming, no sleep timeout) for as long as this
    // screen is composed, which covers the whole time a PDF is displayed.
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(tabId) {
        viewModel.loadTab(tabId)
    }

    // The system folder picker: the user picks where sheets go, and Android
    // hands back a tree URI we hold a persistable write grant on.
    var pickingFolder by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        pickingFolder = false
        if (treeUri != null) viewModel.setSaveFolder(context, treeUri)
    }

    LaunchedEffect(viewModel.pdfSuccess) {
        viewModel.pdfSuccess?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.pdfSuccess = null
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        ShrinkToFitText(
                            text = viewModel.tab?.songName ?: "Loading...",
                            maxSp = 18,
                            minSp = 13,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        viewModel.tab?.artistName?.let { artist ->
                            ShrinkToFitText(
                                text = artist,
                                maxSp = 12,
                                minSp = 9,
                                color = TextSecondary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    // Nothing on a chord sheet: the sheet is saved from the
                    // Save PDF button at the bottom, which is where the thumb
                    // already is and where the progress shows.
                    if (viewModel.tab != null && !viewModel.isChordType) {
                        IconButton(
                            onClick = { viewModel.generatePdf(context) },
                            enabled = !viewModel.isGeneratingPdf
                        ) {
                            if (viewModel.isGeneratingPdf) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Highlight,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.PictureAsPdf,
                                    contentDescription = "Save as PDF",
                                    tint = Highlight
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBg
                )
            )
        },
        bottomBar = {
            if (viewModel.isChordType) {
                PdfFontSizeBar(
                    format = viewModel.pdfFormat,
                    isRendering = viewModel.isRenderingPdf,
                    isSaving = viewModel.isGeneratingPdf,
                    namedLayouts = viewModel.namedLayouts(),
                    themeForNamed = { viewModel.themeForNamed(it) },
                    onSelect = { viewModel.updatePdfFormat(it.first, it.second) },
                    onSave = { viewModel.requestSavePdf() }
                )
            } else {
                FontSizeBar(
                    fontSize = viewModel.fontSize,
                    onDecrease = { viewModel.decreaseFontSize() },
                    onIncrease = { viewModel.increaseFontSize() }
                )
            }
        },
        containerColor = DarkBg
    ) { padding ->
        when {
            viewModel.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Highlight)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading...", color = TextSecondary)
                    }
                }
            }

            viewModel.errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = viewModel.errorMessage ?: "",
                            color = Highlight,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.loadTab(tabId) },
                            colors = ButtonDefaults.buttonColors(containerColor = Highlight)
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            viewModel.tab != null -> {
                val tab = viewModel.tab!!

                if (viewModel.isChordType) {
                    if (viewModel.pdfPages.isNotEmpty()) {
                        PdfPreviewContent(
                            pages = viewModel.pdfPages,
                            padding = padding,
                            viewModel = viewModel
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Highlight)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Generating PDF preview...", color = TextSecondary)
                            }
                        }
                    }
                } else {
                    TextTabContent(
                        tab = tab,
                        padding = padding,
                        viewModel = viewModel,
                        scale = scale,
                        offset = offset,
                        onScaleChange = { scale = it },
                        onOffsetChange = { offset = it }
                    )
                }
            }
        }
    }

    if (viewModel.showSavePrompt) {
        SavePdfPanel(
            fileName = viewModel.pendingSaveFileName ?: "",
            folder = viewModel.pdfSaveFolder ?: "Downloads / UG Viewer",
            isSaving = viewModel.isGeneratingPdf,
            isPickingFolder = pickingFolder,
            onSave = { viewModel.confirmSavePdf(context) },
            onBack = { viewModel.dismissSavePrompt() },
            onPickFolder = {
                pickingFolder = true
                folderPicker.launch(null)
            }
        )
    }

    if (viewModel.showChordPopup) {
        ChordDiagramPopup(
            chordName = viewModel.popupChordName,
            pages = viewModel.popupChordPages,
            isLoading = viewModel.popupChordLoading,
            onDismiss = { viewModel.dismissChordPopup() }
        )
    }
}

@Composable
fun YouTubeListenBar(
    youtubeUrl: String?,
    isLoading: Boolean,
    onOpen: () -> Unit
) {
    if (youtubeUrl == null && !isLoading) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = Surface,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (youtubeUrl != null) {
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = ChordYellow,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (youtubeUrl != null) {
                Text(
                    text = "Listen on YouTube",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TabGreen
                )
            } else {
                Text(
                    text = "Finding song on YouTube...",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun PdfPreviewContent(
    pages: List<Bitmap>,
    padding: PaddingValues,
    viewModel: TabViewerViewModel
) {
    val context = LocalContext.current

    PdfPagesPreview(
        pages = pages,
        modifier = Modifier.padding(padding),
        topContent = {
            YouTubeListenBar(
                youtubeUrl = viewModel.youtubeUrl,
                isLoading = viewModel.isFetchingYouTube,
                onOpen = { viewModel.openYouTube(context) }
            )
        },
        onPageTapped = { pageIndex, pointInPage ->
            val bitmap = pages.getOrNull(pageIndex) ?: return@PdfPagesPreview
            // Preview pixels -> PDF points: the preview is the page scaled
            // down, and the hit map is in page points.
            val pdfX = pointInPage.x * PdfGenerator.PAGE_WIDTH / bitmap.width
            val pdfY = pointInPage.y * PdfGenerator.PAGE_HEIGHT / bitmap.height
            val hit = viewModel.chordAt(pageIndex + 1, pdfX, pdfY) ?: return@PdfPagesPreview
            viewModel.showChordPopup(hit.name)
        }
    )
}

/**
 * Quick-look popup for a chord tapped in the PDF preview.
 *
 * The pictures are pages straight from the same chord-book generator the
 * Chord Shape Search screen prints with, so the popup reads exactly like the
 * saved PDF: white paper, black ink, identical diagrams — one tap lands on the
 * family page with the tapped chord's name on it first.
 *
 * Dismissed by a tap outside the card, the back gesture, or the Close button.
 */
@Composable
fun ChordDiagramPopup(
    chordName: String?,
    pages: List<Bitmap>,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(horizontal = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = ChordYellow,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = chordName ?: "",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1A2E)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (pages.isNotEmpty()) {
                    // One root family per render, so these pages are already
                    // the tapped chord's pages in the book's own order. Bounded
                    // so a big family scrolls instead of growing the dialog
                    // past the screen.
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        pages.forEachIndexed { index, bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Chord diagrams page ${index + 1}",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                } else if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 32.dp),
                        color = Highlight
                    )
                } else {
                    Text(
                        text = "No diagram available for this chord.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Accent,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TextTabContent(
    tab: com.ugviewer.api.TabResult,
    padding: PaddingValues,
    viewModel: TabViewerViewModel,
    scale: Float,
    offset: androidx.compose.ui.geometry.Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (androidx.compose.ui.geometry.Offset) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        TabInfoHeader(tab = tab)

        YouTubeListenBar(
            youtubeUrl = viewModel.youtubeUrl,
            isLoading = viewModel.isFetchingYouTube,
            onOpen = { viewModel.openYouTube(context) }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onScaleChange((scale * zoom).coerceIn(0.5f, 3f))
                        onOffsetChange(offset + pan)
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (tab.applicature.isNotEmpty()) {
                    ChordBadges(chords = tab.applicature.map { it.chord })
                    Spacer(modifier = Modifier.height(12.dp))
                }

                TabContent(
                    content = tab.content,
                    fontSize = viewModel.fontSize
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (tab.urlWeb.isNotEmpty()) {
                    Text(
                        text = "Source: ${tab.urlWeb}",
                        fontSize = 10.sp,
                        color = TextSecondary.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun TabInfoHeader(tab: com.ugviewer.api.TabResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            InfoChip(label = "Type", value = tab.type, modifier = Modifier.weight(1f))
            InfoChip(label = "Tuning", value = tab.tuning.ifEmpty { "Standard" }, modifier = Modifier.weight(1.5f))
            InfoChip(label = "Capo", value = if (tab.capo > 0) "${tab.capo}th" else "None", modifier = Modifier.weight(1f))
            InfoChip(label = "Rating", value = "${"%.1f".format(tab.rating)}", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun InfoChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextSecondary,
            maxLines = 1
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TabGreen,
            maxLines = 1
        )
    }
}

@Composable
fun ChordBadges(chords: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chords.take(12).forEach { chord ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = chord,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChordYellow
                )
            }
        }
    }
}

@Composable
fun TabContent(content: String, fontSize: Float) {
    val parsedContent = remember(content) { parseTabContent(content) }

    Column {
        parsedContent.forEach { segment ->
            when (segment) {
                is TabSegment.Chord -> {
                    Text(
                        text = segment.text,
                        fontSize = (fontSize - 2).sp,
                        fontWeight = FontWeight.Bold,
                        color = ChordYellow,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = (fontSize + 6).sp
                    )
                }
                is TabSegment.TabLine -> {
                    Text(
                        text = segment.text,
                        fontSize = fontSize.sp,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = (fontSize + 4).sp
                    )
                }
                is TabSegment.PlainText -> {
                    Text(
                        text = segment.text,
                        fontSize = fontSize.sp,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = (fontSize + 4).sp
                    )
                }
            }
        }
    }
}

sealed class TabSegment {
    data class Chord(val text: String) : TabSegment()
    data class TabLine(val text: String) : TabSegment()
    data class PlainText(val text: String) : TabSegment()
}

fun parseTabContent(content: String): List<TabSegment> {
    val segments = mutableListOf<TabSegment>()
    val regex = Regex("""\[ch\](.*?)\[/ch\]|\[tab\](.*?)\[/tab\]""", RegexOption.IGNORE_CASE)

    var lastIndex = 0
    for (match in regex.findAll(content)) {
        if (match.range.first > lastIndex) {
            val plain = content.substring(lastIndex, match.range.first)
            if (plain.isNotEmpty()) {
                segments.add(TabSegment.PlainText(plain))
            }
        }

        val chord = match.groupValues[1]
        val tab = match.groupValues[2]

        if (chord.isNotEmpty()) {
            segments.add(TabSegment.Chord(chord))
        } else if (tab.isNotEmpty()) {
            segments.add(TabSegment.TabLine(tab.trim('\n')))
        }

        lastIndex = match.range.last + 1
    }

    if (lastIndex < content.length) {
        val remaining = content.substring(lastIndex)
        if (remaining.isNotBlank()) {
            segments.add(TabSegment.PlainText(remaining))
        }
    }

    if (segments.isEmpty()) {
        segments.add(TabSegment.TabLine(content))
    }

    return segments
}

/**
 * Full-width confirmation for saving the PDF. The file name runs along the top
 * on a single line instead of wrapping down a narrow column, and the two
 * actions sit side by side underneath.
 */
@Composable
fun SavePdfPanel(
    fileName: String,
    folder: String,
    isSaving: Boolean,
    isPickingFolder: Boolean = false,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onPickFolder: () -> Unit
) {
    // The panel is dismissed by a tap outside it, and the system folder picker
    // takes the window away for a moment. Treating that hand-off as a dismissal
    // would throw away a panel the user is halfway through filling in, so the
    // picker keeps it standing.
    Dialog(onDismissRequest = { if (!isSaving && !isPickingFolder) onBack() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = Highlight,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Save PDF",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Tapping the destination opens the system folder picker, so the
                // sheet can go somewhere other than Downloads and that choice
                // sticks for the next one.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Accent)
                        .clickable(enabled = !isSaving, onClick = onPickFolder)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = folder,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Change folder",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                SavePdfFileName(fileName = fileName)

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Accent,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Back", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onSave,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Highlight,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The artist and song name the sheet will be saved under, at 12pt so a normal
 * "Artist - Song.pdf" fits the panel on one line.
 *
 * A cut-off name is worse than useless here: the three dots hide the part of the
 * title that tells two versions of a song apart, and a name the user cannot read
 * is a name they will not recognise in their Downloads folder. So instead of an
 * ellipsis the type steps down a point at a time while the line overflows, and
 * only falls back to wrapping once shrinking has run out.
 */
@Composable
fun SavePdfFileName(fileName: String) {
    var fontSize by remember(fileName) { mutableIntStateOf(SAVE_NAME_MAX_SP) }
    var wraps by remember(fileName) { mutableStateOf(false) }

    Text(
        text = fileName,
        fontSize = fontSize.sp,
        color = TextPrimary,
        maxLines = if (wraps) 2 else 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
        onTextLayout = { result ->
            if (result.hasVisualOverflow) {
                if (fontSize > SAVE_NAME_MIN_SP) {
                    fontSize -= 1
                } else {
                    wraps = true
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

private const val SAVE_NAME_MAX_SP = 12
private const val SAVE_NAME_MIN_SP = 9

/**
 * A name that has to live on one line, shrunk a point at a time instead of
 * sliced. A half-lettered artist or title is worse than a smaller one: the
 * missing letters are the part that tells two recordings apart, and a cut edge
 * just reads as a rendering fault. Only once shrinking has run out does the tail
 * turn into dots, so anything still missing is plainly marked as missing.
 */
@Composable
fun ShrinkToFitText(
    text: String,
    maxSp: Int,
    minSp: Int,
    color: Color,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier
) {
    var fontSize by remember(text, maxSp) { mutableIntStateOf(maxSp) }

    Text(
        text = text,
        fontSize = fontSize.sp,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > minSp) fontSize -= 1
        },
        modifier = modifier
    )
}

@Composable
fun FontSizeBar(fontSize: Float, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Surface(
        color = Surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDecrease,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Accent)
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Decrease font size",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = "${fontSize.toInt()}sp",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            IconButton(
                onClick = onIncrease,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Accent)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Increase font size",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Unwraps any ContextWrapper chain to reach the hosting Activity. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/**
 * Bottom bar for the chord sheet: the size picker sits next to the save button
 * and the two boxes are the same width, so nothing is squeezed by a label and
 * the save action is always one tap away at the bottom of the screen.
 *
 * Picking Custom opens the size dials above the bar rather than pushing the
 * buttons about: chords and lyrics are set independently, and the sheet reprints
 * under the preview as either number moves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfFontSizeBar(
    format: PdfGenerator.PdfFormat,
    isRendering: Boolean,
    isSaving: Boolean = false,
    namedLayouts: List<String> = emptyList(),
    themeForNamed: (String) -> PdfGenerator.PdfTheme? = { null },
    onSelect: (Pair<PdfGenerator.PdfFormat, PdfGenerator.PdfTheme?>) -> Unit,
    onSave: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var customPanelExpanded by remember { mutableStateOf(true) }
    val custom = format as? PdfGenerator.PdfFormat.Custom

    // What the dials were last set to, so a trip to a preset and back does not
    // wipe out a size that was worked out by eye.
    var customSizes by remember {
        mutableStateOf(
            PdfGenerator.PdfFormat.Custom(
                chordFontSize = PdfGenerator.DEFAULT_CUSTOM_CHORD_FONT_SIZE,
                lyricFontSize = PdfGenerator.DEFAULT_CUSTOM_LYRIC_FONT_SIZE
            )
        )
    }
    LaunchedEffect(custom) {
        if (custom != null) customSizes = custom
    }

    Surface(color = Surface, shadowElevation = 8.dp) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (custom != null && customPanelExpanded) {
                CustomSizePanel(
                    chordFontSize = custom.chordFontSize,
                    lyricFontSize = custom.lyricFontSize,
                    onChordFontSizeChange = { onSelect(custom.copy(chordFontSize = it) to null) },
                    onLyricFontSizeChange = { onSelect(custom.copy(lyricFontSize = it) to null) }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = !isRendering,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PdfBarContentPadding,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Accent,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isRendering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = PdfGenerator.labelOf(format),
                                fontSize = PDF_BAR_LABEL_SP.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        // Layouts saved in the PDF Design Studio appear first,
                        // under the names the user gave them.
                        namedLayouts.forEach { layoutName ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = layoutName,
                                        color = Highlight,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onSelect(
                                        PdfGenerator.PdfFormat.Named(layoutName) to
                                            themeForNamed(layoutName)
                                    )
                                }
                            )
                        }
                        if (namedLayouts.isNotEmpty()) {
                            HorizontalDivider()
                        }
                        PdfGenerator.PDF_FORMATS.forEach { option ->
                            val isSelected = PdfGenerator.isSameKindAs(option, format)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = PdfGenerator.labelOf(option),
                                        color = if (isSelected) Highlight else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onSelect(
                                        (if (option is PdfGenerator.PdfFormat.Custom) customSizes else option) to null
                                    )
                                }
                            )
                        }
                    }
                }

                if (custom != null) {
                    OutlinedButton(
                        onClick = { customPanelExpanded = !customPanelExpanded },
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Accent,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (customPanelExpanded) "Hide" else "Sizes",
                            fontSize = PDF_BAR_LABEL_SP.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving,
                    contentPadding = PdfBarContentPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = Highlight),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSaving) {
                        // The save is the only PDF action on a chord sheet now
                        // that the top bar icon is gone, so this is where the
                        // wait is shown.
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Save PDF",
                            fontSize = PDF_BAR_LABEL_SP.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * The two size dials, shown once Custom is picked. A number can be typed straight
 * into its box for an exact size, or nudged a point at a time with the arrows,
 * which is the quicker way to walk a size up until the lines stop wrapping.
 */
@Composable
private fun CustomSizePanel(
    chordFontSize: Float,
    lyricFontSize: Float,
    onChordFontSizeChange: (Float) -> Unit,
    onLyricFontSizeChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Text(
            text = "Custom size (pt)",
            fontSize = 11.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FontSizeDial(
                label = "Chords",
                fontSize = chordFontSize,
                onFontSizeChange = onChordFontSizeChange,
                modifier = Modifier.weight(1f)
            )
            FontSizeDial(
                label = "Lyrics",
                fontSize = lyricFontSize,
                onFontSizeChange = onLyricFontSizeChange,
                modifier = Modifier.weight(1f)
            )
        }
    }
    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))
    Spacer(modifier = Modifier.height(4.dp))
}

private val DialShape = RoundedCornerShape(10.dp)

/**
 * One point size: the number on the left, which is a field the size can be typed
 * into, and the arrows on the right for stepping it. Kept out of the Material
 * text field so the arrows can sit inside the same box as the number.
 */
@Composable
private fun FontSizeDial(
    label: String,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(DialShape)
            .background(Accent)
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 9.sp,
                color = TextSecondary
            )
            FontSizeField(fontSize = fontSize, onFontSizeChange = onFontSizeChange)
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            StepperArrow(Icons.Default.KeyboardArrowUp, "Larger $label") {
                onFontSizeChange((fontSize + FONT_SIZE_STEP).coerceAtMost(PdfGenerator.MAX_FONT_SIZE))
            }
            StepperArrow(Icons.Default.KeyboardArrowDown, "Smaller $label") {
                onFontSizeChange((fontSize - FONT_SIZE_STEP).coerceAtLeast(PdfGenerator.MIN_FONT_SIZE))
            }
        }
    }
}

/** The number itself, typed straight in. Nudged to the range the PDF accepts. */
@Composable
private fun FontSizeField(fontSize: Float, onFontSizeChange: (Float) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // The size this box last put there itself, so a change from anywhere else
    // can be told apart from one still being typed.
    var ownValue by remember { mutableStateOf<Float?>(null) }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(sizeText(fontSize), sizeSelection(sizeText(fontSize))))
    }

    // Follows the arrows, the other dial and a switch of preset, but never
    // fights the number being typed: a size this box set is left as it stands.
    LaunchedEffect(fontSize) {
        if (focused && ownValue == fontSize) return@LaunchedEffect
        val shown = sizeText(fontSize)
        if (fieldValue.text != shown) {
            ownValue = null
            fieldValue = TextFieldValue(shown, sizeSelection(shown))
        }
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { typed ->
            val digits = typed.text.filter { it.isDigit() }
                .take(PdfGenerator.MAX_FONT_SIZE_DIGITS)
            fieldValue = typed.copy(text = digits, selection = TextRange(digits.length))
            // A size past the range is held at the nearest one that prints, and
            // the box says so once it is done being typed into.
            PdfGenerator.fontSizeFromTyped(digits)?.let { size ->
                ownValue = size
                onFontSizeChange(size)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) {
                    fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
                } else {
                    ownValue = null
                    val shown = sizeText(fontSize)
                    fieldValue = TextFieldValue(shown, sizeSelection(shown))
                }
            },
        textStyle = TextStyle(
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start
        ),
        singleLine = true,
        cursorBrush = SolidColor(Highlight),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun StepperArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    // Kept out of the Material button so the two arrows can sit in the same box
    // as the number, but given a tap area well past the glyph: the step is one
    // point at a time, so a missed tap costs a lot of walking.
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun sizeText(fontSize: Float): String = fontSize.toInt().toString()

/** Selects the whole number, so typing over it replaces the size. */
private fun sizeSelection(text: String): TextRange = TextRange(0, text.length)

private const val FONT_SIZE_STEP = 1f

/**
 * The two boxes share the bar, so each gets half of it and neither may squeeze
 * the other. A Material button keeps a 40dp minimum height on its own, so the
 * boxes get their size from that and take no vertical padding: padding here used
 * to eat the height down to 20dp, and a phone with a large font scale then had
 * line boxes taller than the space left over, which clipped the bottoms off the
 * letters. Letting the box grow to fit its label means a word is whole at any
 * font size, and both boxes still come out the same height as each other.
 *
 * The horizontal pad is what keeps the room for the words: a Material button
 * spends 24dp of that on its own padding, which was cutting "Fit To Page" off
 * mid-word, so the label drops to 13pt and the pad to 8dp. With no vertical pad
 * the label sits in the middle of the box on its own, so there is no fudge
 * factor to keep in step with the font scale.
 */
private val PdfBarContentPadding = PaddingValues(horizontal = 8.dp)
private const val PDF_BAR_LABEL_SP = 13
