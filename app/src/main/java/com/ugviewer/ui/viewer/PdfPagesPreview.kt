package com.ugviewer.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

const val MIN_PDF_SCALE = 1f
const val MAX_PDF_SCALE = 4f
const val DOUBLE_TAP_SCALE = 2.5f

/** Keeps the zoomed content covering the viewport on both axes. */
fun clampPan(offset: Offset, viewport: IntSize, scale: Float): Offset {
    if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}

/**
 * Scrollable, pinch-zoomable list of rendered PDF pages. Shared by the tab
 * viewer and the chord-shape print screen so both get identical navigation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfPagesPreview(
    pages: List<Bitmap>,
    modifier: Modifier = Modifier,
    topContent: @Composable () -> Unit = {},
    bottomContent: @Composable () -> Unit = {}
) {
    var scale by remember { mutableFloatStateOf(MIN_PDF_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTransformGestures(
                    onGesture = { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(MIN_PDF_SCALE, MAX_PDF_SCALE)
                        scale = newScale
                        offset = if (newScale <= MIN_PDF_SCALE) {
                            Offset.Zero
                        } else {
                            clampPan(offset + pan, viewport, newScale)
                        }
                    }
                )
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
                .verticalScroll(scrollState)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > MIN_PDF_SCALE) MIN_PDF_SCALE else DOUBLE_TAP_SCALE
                            offset = Offset.Zero
                        }
                    )
                }
                .padding(horizontal = 8.dp)
                .align(Alignment.TopCenter)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            topContent()

            Spacer(modifier = Modifier.height(16.dp))

            pages.forEachIndexed { index, bitmap ->
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
                        contentDescription = "PDF Page ${index + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            bottomContent()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
