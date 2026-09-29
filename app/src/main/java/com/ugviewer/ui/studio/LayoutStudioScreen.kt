package com.ugviewer.ui.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ugviewer.ui.theme.*
import com.ugviewer.viewmodel.LayoutStudioViewModel
import kotlinx.coroutines.withContext

/**
 * PDF Design Studio: tune how chord sheets look — font sizes, chord
 * placement, and where lines wrap (with a visible guide rule on the page) —
 * against a live preview, then save it as the default every future sheet
 * uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutStudioScreen(
    onBack: () -> Unit,
    viewModel: LayoutStudioViewModel = viewModel()
) {
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
                .padding(horizontal = 16.dp)
        ) {
            if (viewModel.errorMessage != null) {
                Text(
                    text = viewModel.errorMessage!!,
                    color = ChordYellow,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Drag a slider — the sheet re-renders as you go.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (viewModel.isRendering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Highlight
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                StudioSlider(
                    label = "Chord size",
                    value = viewModel.chordSize,
                    valueText = "${viewModel.chordSize.toInt()}pt",
                    range = 10f..42f,
                    onChange = {
                        viewModel.chordSize = it
                        viewModel.scheduleRender()
                    }
                )
                StudioSlider(
                    label = "Lyric size",
                    value = viewModel.lyricSize,
                    valueText = "${viewModel.lyricSize.toInt()}pt",
                    range = 10f..42f,
                    onChange = {
                        viewModel.lyricSize = it
                        viewModel.scheduleRender()
                    }
                )
                StudioSlider(
                    label = "Space above chord",
                    value = viewModel.gapAboveChord,
                    valueText = "%.2f".format(viewModel.gapAboveChord),
                    range = 0.3f..2f,
                    onChange = {
                        viewModel.gapAboveChord = it
                        viewModel.scheduleRender()
                    }
                )
                StudioSlider(
                    label = "Chord-to-word gap",
                    value = viewModel.gapBelowChord,
                    valueText = "%.2f".format(viewModel.gapBelowChord),
                    range = 0.3f..2f,
                    onChange = {
                        viewModel.gapBelowChord = it
                        viewModel.scheduleRender()
                    }
                )
                StudioSlider(
                    label = "Lyric line height",
                    value = viewModel.plainRowHeight,
                    valueText = "%.2f".format(viewModel.plainRowHeight),
                    range = 0.9f..1.8f,
                    onChange = {
                        viewModel.plainRowHeight = it
                        viewModel.scheduleRender()
                    }
                )
                StudioSlider(
                    label = "Stanza gap",
                    value = viewModel.stanzaGap,
                    valueText = "%.2f".format(viewModel.stanzaGap),
                    range = 0.3f..1.5f,
                    onChange = {
                        viewModel.stanzaGap = it
                        viewModel.scheduleRender()
                    }
                )
                StudioSlider(
                    label = "Wrap width (% of page used before lines wrap)",
                    value = viewModel.wrapFraction,
                    valueText = "${(viewModel.wrapFraction * 100).toInt()}%",
                    range = 0.4f..1f,
                    onChange = {
                        viewModel.wrapFraction = it
                        viewModel.scheduleRender()
                    }
                )
                Text(
                    text = "The red rule on the preview marks the wrap column.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.resetToShipped() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Accent,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Reset", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { viewModel.saveAsDefault() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Highlight),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (viewModel.hasSavedLayout) "Saved ✓" else "Save as default",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (viewModel.isLoadingDemo) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Highlight)
                    }
                } else {
                    viewModel.previewPages.forEachIndexed { index, bitmap ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Preview page ${index + 1}",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StudioSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 12.sp, color = TextSecondary)
            Text(
                valueText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Highlight
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Highlight,
                activeTrackColor = Highlight,
                inactiveTrackColor = Accent
            )
        )
    }
}
