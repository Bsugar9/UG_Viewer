package com.ugviewer.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ugviewer.ui.theme.*
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

    LaunchedEffect(tabId) {
        viewModel.loadTab(tabId)
    }

    LaunchedEffect(viewModel.pdfSuccess) {
        viewModel.pdfSuccess?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
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
                        Text(
                            text = viewModel.tab?.songName ?: "Loading...",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        viewModel.tab?.artistName?.let { artist ->
                            Text(
                                text = artist,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 2
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
                    if (viewModel.tab != null && viewModel.isChordType) {
                        IconButton(
                            onClick = { viewModel.savePdf(context) },
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
                                    Icons.Default.Download,
                                    contentDescription = "Save PDF",
                                    tint = Highlight
                                )
                            }
                        }
                    } else if (viewModel.tab != null) {
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
            if (!viewModel.isChordType) {
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
}

@Composable
fun PdfPreviewContent(
    pages: List<Bitmap>,
    padding: PaddingValues,
    viewModel: TabViewerViewModel
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 3f)
                    offset += pan
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            pages.forEachIndexed { index, bitmap ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "PDF Page ${index + 1}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
                            contentScale = ContentScale.Fit
                        )
                        Text(
                            text = "Page ${index + 1}",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val context = LocalContext.current
            Button(
                onClick = { viewModel.savePdf(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Highlight),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save PDF to Downloads", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        TabInfoHeader(tab = tab)

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
