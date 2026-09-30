package com.ugviewer.ui.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ugviewer.ui.theme.Accent
import com.ugviewer.ui.theme.ChordYellow
import com.ugviewer.ui.theme.DarkBg
import com.ugviewer.ui.theme.Highlight
import com.ugviewer.ui.theme.Surface
import com.ugviewer.ui.theme.TextPrimary
import com.ugviewer.ui.theme.TextSecondary
import com.ugviewer.ui.viewer.PdfPagesPreview
import com.ugviewer.viewmodel.LayoutStudioViewModel

/**
 * PDF Design Studio: tune chord size, lyric size, chord offset within its
 * white space, row height, stanza gap, and wrap width using compact up/down
 * steppers with a live, real-time PDF preview right below without requiring
 * scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutStudioScreen(
    onBack: () -> Unit,
    viewModel: LayoutStudioViewModel = viewModel()
) {
    var showNamePrompt by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PDF Design Studio",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
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
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            if (viewModel.errorMessage != null) {
                Text(
                    text = viewModel.errorMessage!!,
                    color = ChordYellow,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            // Compact 2-column control grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Left Column Controls
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StudioStepper(
                        label = "Chords",
                        valueText = "${viewModel.chordSize.toInt()}pt",
                        onDecrement = {
                            viewModel.chordSize = (viewModel.chordSize - 1f).coerceIn(10f, 42f)
                            viewModel.scheduleRender()
                        },
                        onIncrement = {
                            viewModel.chordSize = (viewModel.chordSize + 1f).coerceIn(10f, 42f)
                            viewModel.scheduleRender()
                        },
                        canDecrement = viewModel.chordSize > 10f,
                        canIncrement = viewModel.chordSize < 42f
                    )

                    StudioStepper(
                        label = "Lyrics",
                        valueText = "${viewModel.lyricSize.toInt()}pt",
                        onDecrement = {
                            viewModel.lyricSize = (viewModel.lyricSize - 1f).coerceIn(10f, 42f)
                            viewModel.scheduleRender()
                        },
                        onIncrement = {
                            viewModel.lyricSize = (viewModel.lyricSize + 1f).coerceIn(10f, 42f)
                            viewModel.scheduleRender()
                        },
                        canDecrement = viewModel.lyricSize > 10f,
                        canIncrement = viewModel.lyricSize < 42f
                    )

                    StudioStepper(
                        label = "Offset",
                        valueText = "%.2f".format(viewModel.chordOffset),
                        onDecrement = {
                            viewModel.chordOffset = (viewModel.chordOffset - 0.05f).coerceIn(-1.0f, 1.0f)
                            viewModel.scheduleRender()
                        },
                        onIncrement = {
                            viewModel.chordOffset = (viewModel.chordOffset + 0.05f).coerceIn(-1.0f, 1.0f)
                            viewModel.scheduleRender()
                        },
                        canDecrement = viewModel.chordOffset > -1.0f,
                        canIncrement = viewModel.chordOffset < 1.0f
                    )
                }

                // Right Column Controls
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StudioStepper(
                        label = "Height",
                        valueText = "%.2f".format(viewModel.plainRowHeight),
                        onDecrement = {
                            viewModel.plainRowHeight = (viewModel.plainRowHeight - 0.05f).coerceIn(0.9f, 1.8f)
                            viewModel.scheduleRender()
                        },
                        onIncrement = {
                            viewModel.plainRowHeight = (viewModel.plainRowHeight + 0.05f).coerceIn(0.9f, 1.8f)
                            viewModel.scheduleRender()
                        },
                        canDecrement = viewModel.plainRowHeight > 0.9f,
                        canIncrement = viewModel.plainRowHeight < 1.8f
                    )

                    StudioStepper(
                        label = "Gap",
                        valueText = "%.2f".format(viewModel.stanzaGap),
                        onDecrement = {
                            viewModel.stanzaGap = (viewModel.stanzaGap - 0.05f).coerceIn(0.3f, 1.5f)
                            viewModel.scheduleRender()
                        },
                        onIncrement = {
                            viewModel.stanzaGap = (viewModel.stanzaGap + 0.05f).coerceIn(0.3f, 1.5f)
                            viewModel.scheduleRender()
                        },
                        canDecrement = viewModel.stanzaGap > 0.3f,
                        canIncrement = viewModel.stanzaGap < 1.5f
                    )

                    StudioStepper(
                        label = "Wrap",
                        valueText = "${(viewModel.wrapFraction * 100).toInt()}%",
                        onDecrement = {
                            viewModel.wrapFraction = (viewModel.wrapFraction - 0.05f).coerceIn(0.4f, 1f)
                            viewModel.scheduleRender()
                        },
                        onIncrement = {
                            viewModel.wrapFraction = (viewModel.wrapFraction + 0.05f).coerceIn(0.4f, 1f)
                            viewModel.scheduleRender()
                        },
                        canDecrement = viewModel.wrapFraction > 0.4f,
                        canIncrement = viewModel.wrapFraction < 1f
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action row with Song, Reset, and Save
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.showPromptDialog = true },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Accent,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Song", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = { viewModel.resetToShipped() },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Accent,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Reset", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Button(
                        onClick = { showNamePrompt = true },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Highlight),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (viewModel.isRendering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Highlight
                        )
                    }
                }
            }

            // Initial Popup Prompt Dialog: Default or New
            if (viewModel.showPromptDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.showPromptDialog = false },
                    title = { Text("Select Test PDF", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                    text = {
                        Column {
                            Text(
                                "Choose a song to preview layout adjustments against.",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "Current Default:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Highlight
                            )
                            Text(
                                viewModel.currentSongTitle,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.loadDefaultSong() },
                            colors = ButtonDefaults.buttonColors(containerColor = Highlight),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Default", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = {
                                viewModel.showPromptDialog = false
                                viewModel.showSearchDialog = true
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("New", fontWeight = FontWeight.Bold)
                        }
                    },
                    containerColor = Surface
                )
            }

            // Song Search Dialog when "New" is selected
            if (viewModel.showSearchDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.showSearchDialog = false },
                    title = { Text("Search New Test Song", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = viewModel.searchQuery,
                                    onValueChange = { viewModel.searchQuery = it },
                                    placeholder = { Text("Artist or song name...", fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary)
                                )
                                Button(
                                    onClick = { viewModel.searchSongs(viewModel.searchQuery) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Highlight),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Search", fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (viewModel.isSearchingSongs) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = Highlight)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                ) {
                                    items(viewModel.searchResults) { tab ->
                                        Card(
                                            onClick = { viewModel.selectNewSong(tab) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            colors = CardDefaults.cardColors(containerColor = DarkBg)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                Text(
                                                    tab.songName,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = TextPrimary
                                                )
                                                Text(
                                                    "${tab.artistName} • ${tab.type}",
                                                    fontSize = 11.sp,
                                                    color = TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        OutlinedButton(onClick = { viewModel.showSearchDialog = false }) {
                            Text("Cancel")
                        }
                    },
                    containerColor = Surface
                )
            }

            if (showNamePrompt) {
                SaveLayoutDialog(
                    defaultName = viewModel.suggestedLayoutName(),
                    onConfirm = { name ->
                        viewModel.saveNamedLayout(name)
                        showNamePrompt = false
                    },
                    onDismiss = { showNamePrompt = false }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live PDF Preview Area filling all remaining vertical space
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (viewModel.isLoadingDemo) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Highlight)
                    }
                } else {
                    PdfPagesPreview(
                        pages = viewModel.previewPages,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * A compact stepper control with a 1-word label, value display, and - / + arrow buttons.
 */
@Composable
private fun StudioStepper(
    label: String,
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    canDecrement: Boolean,
    canIncrement: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DarkBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                IconButton(
                    onClick = onDecrement,
                    enabled = canDecrement,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Decrease $label",
                        tint = if (canDecrement) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(15.dp)
                    )
                }

                Text(
                    text = valueText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Highlight,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )

                IconButton(
                    onClick = onIncrement,
                    enabled = canIncrement,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Increase $label",
                        tint = if (canIncrement) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

/**
 * Asks the user what to call the layout being saved.
 */
@Composable
private fun SaveLayoutDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this layout", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = Surface
    )
}
