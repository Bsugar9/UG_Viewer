package com.ugviewer.ui.chord

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ugviewer.ui.theme.Accent
import com.ugviewer.ui.theme.ChordYellow
import com.ugviewer.ui.theme.DarkBg
import com.ugviewer.ui.theme.Highlight
import com.ugviewer.ui.theme.Surface as SurfaceColor
import com.ugviewer.ui.theme.TextPrimary
import com.ugviewer.ui.theme.TextSecondary
import com.ugviewer.ui.viewer.PdfBarContentPadding
import com.ugviewer.ui.viewer.PdfPagesPreview
import com.ugviewer.util.PdfGenerator
import com.ugviewer.viewmodel.ChordSearchViewModel

/** Four per row keeps each of the 12 root chips wide enough for "C#" and "F#". */
private const val COLUMNS = 4

/**
 * Chord shape search bar: type, say or pick a chord name and get a printable
 * PDF of every voicing, four diagrams per row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChordSearchScreen(
    onBack: () -> Unit,
    viewModel: ChordSearchViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // A chord left ringing when the screen is closed would follow the user off
    // it, so leaving silences whatever is sounding.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopChord() }
    }

    fun submit(rawQuery: String) {
        keyboard?.hide()
        focusManager.clearFocus()
        viewModel.submit(rawQuery)
    }

    var isListening by remember { mutableStateOf(false) }
    var showMicRationale by remember { mutableStateOf(false) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            viewModel.submitSpoken(heard?.firstOrNull().orEmpty())
        }
    }

    fun recognizeChordSpoken() {
        val prompt = "Say a chord, e.g. show me a C chord"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        isListening = true
        speechLauncher.launch(intent)
    }

    // Asking for the microphone mid-recognition would drop the tap, so the
    // permission result starts the recognizer instead.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showMicRationale = false
            recognizeChordSpoken()
        } else {
            showMicRationale = true
            isListening = false
        }
    }

    val onMicClick = {
        // Re-checked per tap rather than remembered: the user may have granted
        // the permission in Settings and come straight back.
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            recognizeChordSpoken()
        } else {
            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Chord Shapes",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = "Print-ready guitar diagrams",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1
                        )
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        bottomBar = {
            if (state.shapeCount > 0) {
                Surface(color = SurfaceColor, shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Replays whatever chord was last tapped, or cuts it off.
                        // It sits beside Save rather than on its own row so the
                        // sheet keeps the height it had before playback existed.
                        Button(
                            onClick = { viewModel.toggleChordPlayback() },
                            enabled = viewModel.lastPlayedChordName != null,
                            contentPadding = PdfBarContentPadding,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = if (viewModel.isChordPlaying) {
                                    Icons.Default.Stop
                                } else {
                                    Icons.Default.PlayArrow
                                },
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (viewModel.isChordPlaying) "Stop" else "Play",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { viewModel.savePdf() },
                            enabled = !state.isSaving,
                            modifier = Modifier.weight(1f),
                            contentPadding = PdfBarContentPadding,
                            colors = ButtonDefaults.buttonColors(containerColor = Highlight),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = TextPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state.isSaving) "Saving..." else "Save PDF to Downloads",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChordSearchField(
                    query = state.query,
                    isRendering = state.isRendering,
                    onQueryChange = viewModel::onQueryChange,
                    onSubmit = ::submit,
                    // An explicit height: sharing a Row with the button otherwise
                    // lets the field stretch to the full remaining column height.
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilledIconButton(
                    onClick = onMicClick,
                    modifier = Modifier.size(56.dp),
                    enabled = !isListening && !state.isRendering,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isListening) Highlight else Accent
                    ),
                    shape = CircleShape
                ) {
                    if (isListening) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = TextPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Say a chord",
                            tint = TextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            if (showMicRationale) {
                Text(
                    text = "Microphone permission is needed to say a chord. Grant it in Settings > Apps > UG Viewer > Permissions.",
                    color = ChordYellow,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            RootQuickPick(
                roots = state.roots,
                enabled = !state.isRendering,
                onPick = { root -> viewModel.submit(root) }
            )

            when {
                state.shapeCount == 0 && !state.isRendering -> ChordSearchHint()
                state.pages.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Highlight)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Laying out chord diagrams...", color = TextSecondary)
                    }
                }

                else -> PdfPagesPreview(
                    pages = state.pages,
                    // No auto-scroll here: this is a reference grid the user
                    // scans and taps, not something to be read top to bottom, and
                    // the control sat over the diagrams it was scrolling past.
                    showAutoScrollControls = false,
                    topContent = {
                        ChordResultSummary(
                            state,
                            lastPlayedChordName = viewModel.lastPlayedChordName,
                            isChordPlaying = viewModel.isChordPlaying
                        )
                    },
                    onPageTapped = { pageIndex, pointInPage ->
                        val bitmap = state.pages.getOrNull(pageIndex) ?: return@PdfPagesPreview
                        // Preview pixels -> PDF points, which is the space the
                        // hit map was built in.
                        val pdfX = pointInPage.x * PdfGenerator.PAGE_WIDTH / bitmap.width
                        val pdfY = pointInPage.y * PdfGenerator.PAGE_HEIGHT / bitmap.height
                        viewModel.playChordAt(pageIndex + 1, pdfX, pdfY)
                    }
                )
            }
        }
    }
}

@Composable
private fun ChordSearchField(
    query: String,
    isRendering: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text("Search chords, e.g. C, Am7, F#m", color = TextSecondary)
        },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
        },
        trailingIcon = {
            if (isRendering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Highlight,
                    strokeWidth = 2.dp
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = TextPrimary,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit(query) }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Highlight,
            unfocusedBorderColor = Accent,
            focusedContainerColor = SurfaceColor,
            unfocusedContainerColor = SurfaceColor,
            cursorColor = Highlight
        )
    )
}

/**
 * One-tap access to the 12 root notes the database is keyed by.
 *
 * A 4x3 grid rather than a single row: 12 chips in one row left ~27dp each,
 * which clipped the sharp names.
 */
@Composable
private fun RootQuickPick(
    roots: List<String>,
    enabled: Boolean,
    onPick: (String) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "Root notes",
            fontSize = 11.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            roots.chunked(COLUMNS).forEach { rowRoots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowRoots.forEach { root ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (enabled) Accent else Accent.copy(alpha = 0.4f))
                                .clickable(enabled = enabled) { onPick(root) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = root,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = ChordYellow,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChordResultSummary(
    state: com.ugviewer.viewmodel.ChordSearchUiState,
    lastPlayedChordName: String? = null,
    isChordPlaying: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceColor)
            .padding(12.dp)
    ) {
        Text(
            text = "${state.shapeCount} shapes - ${state.matchingNames.size} chords",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Highlight
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.matchingNames.joinToString("  "),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(8.dp))
        // Says what a tap does, because nothing on the page looks pressable. Once
        // a chord has been tapped it reports which one is sounding, so the
        // summary doubles as the answer to "what am I hearing".
        Text(
            text = if (isChordPlaying && lastPlayedChordName != null) {
                "Playing $lastPlayedChordName - tap another shape to hear it"
            } else {
                "Tap any shape to hear it"
            },
            fontSize = 12.sp,
            color = if (isChordPlaying) ChordYellow else TextSecondary
        )
    }
}

@Composable
private fun ChordSearchHint() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Pick a root note, type a chord, or tap the mic and say one",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A root note such as C prints every C chord; an exact name such as Am7 prints just its voicings. \"Show me a C chord\" works just as well. Four diagrams per row, ready to print.",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}
