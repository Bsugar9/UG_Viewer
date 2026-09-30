package com.ugviewer.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ugviewer.ui.theme.Accent
import com.ugviewer.ui.theme.DarkBg
import com.ugviewer.ui.theme.Highlight
import com.ugviewer.ui.theme.TextPrimary
import com.ugviewer.ui.theme.TextSecondary
import androidx.compose.foundation.ScrollState
import kotlin.math.roundToInt

/**
 * The paces - and + step through, slowest first.
 *
 * Discrete rungs rather than a flat step, because a linear step that spans
 * 1-600 dp/s is uselessly coarse where it matters: a 50 dp/s jump is fine when
 * skimming and far too coarse when you are trying to read a tricky bar. Rungs
 * are packed tightly at the slow end (1-30 dp/s, where you actually play along)
 * and spread out at the fast end, and every tap moves exactly one rung no
 * matter where you are on the scale.
 */
val AUTO_SCROLL_SPEEDS = listOf(
    1f, 2f, 3f, 5f, 8f, 12f, 20f, 30f, 45f, 60f, 90f, 120f, 180f, 270f, 400f, 600f
)

/** Slowest auto-scroll: about 8 minutes for a page, for freezing on a single chord. */
const val MIN_AUTO_SCROLL_DP_PER_SECOND = 1f

/** Fastest auto-scroll: roughly a page a second, for skimming. */
const val MAX_AUTO_SCROLL_DP_PER_SECOND = 600f

/**
 * The pace a sheet starts scrolling at: about 17 seconds for a page. Slow
 * enough to read a lyric line and its chords before it leaves the screen, and
 * the first tap of - drops to 20 dp/s, then 12, 8, 5, 3, 2, 1.
 */
const val DEFAULT_AUTO_SCROLL_DP_PER_SECOND = 30f

/**
 * The rung at or just below [dpPerSecond], so an out-of-band starting speed
 * snaps onto the scale instead of scrolling at a pace no button can show.
 */
internal fun nearestSpeedIndex(dpPerSecond: Float): Int {
    var index = 0
    while (index + 1 < AUTO_SCROLL_SPEEDS.size && AUTO_SCROLL_SPEEDS[index + 1] <= dpPerSecond) {
        index++
    }
    return index
}

/**
 * Where an auto-scrolling preview should be [deltaSeconds] after the last frame.
 *
 * Kept as a pure function so the pacing and the clamping at both ends are
 * testable without a running frame clock. A preview whose content already fits
 * ([maxValue] of 0) has nowhere to go and stays put.
 */
internal fun autoScrollTarget(
    current: Int,
    maxValue: Int,
    pixelsPerSecond: Float,
    deltaSeconds: Float
): Int {
    val limit = maxValue.coerceAtLeast(0)
    if (limit <= 0) return 0
    if (pixelsPerSecond <= 0f || deltaSeconds <= 0f) return current.coerceIn(0, limit)
    return (current + pixelsPerSecond * deltaSeconds).roundToInt().coerceIn(0, limit)
}

/**
 * Whether an auto-scroll has run out of document: true only once there is a
 * document to scroll and the position has reached its end. A not-yet-laid-out
 * preview ([maxValue] of 0) is not "finished", it is just not ready.
 */
internal fun autoScrollFinished(current: Int, maxValue: Int): Boolean =
    maxValue > 0 && current >= maxValue

/**
 * Auto-scroll for a PDF preview: a play/pause toggle and a speed the user
 * raises or lowers. The speed is held in dp per second so a sheet scrolls at
 * the same readable pace on a cheap phone and a tablet.
 */
@Stable
class PdfAutoScrollState internal constructor(initialDpPerSecond: Float) {

    /** True while the preview is scrolling itself. */
    var isRunning by mutableStateOf(false)
        private set

    private var rung by mutableStateOf(nearestSpeedIndex(initialDpPerSecond))

    /** Current pace in dp per second, always one of [AUTO_SCROLL_SPEEDS]. */
    val dpPerSecond: Float get() = AUTO_SCROLL_SPEEDS[rung]

    /** True when - has nothing left to give, so the button can grey out. */
    val canSlowDown: Boolean get() = rung > 0

    /** True when + has nothing left to give, so the button can grey out. */
    val canSpeedUp: Boolean get() = rung < AUTO_SCROLL_SPEEDS.lastIndex

    fun toggle() {
        isRunning = !isRunning
    }

    /** Stops scrolling without touching the speed, e.g. when the user scrolls. */
    fun pause() {
        isRunning = false
    }

    fun speedUp() {
        if (canSpeedUp) rung++
    }

    fun slowDown() {
        if (canSlowDown) rung--
    }
}

/** Remembers the auto-scroll controls for one preview. */
@Composable
fun rememberPdfAutoScrollState(
    initialDpPerSecond: Float = DEFAULT_AUTO_SCROLL_DP_PER_SECOND
): PdfAutoScrollState = remember { PdfAutoScrollState(initialDpPerSecond) }

/**
 * Drives [scrollState] while [state] is running, one frame at a time so the
 * motion matches the display's refresh and the speed buttons take effect
 * immediately instead of restarting the run. Stops itself at the end of the
 * document, and leaves scrolling alone when it is not running.
 *
 * Uses a sub-pixel accumulator so very slow speeds (e.g. 1 dp/s) still make
 * progress: each frame adds (px/s × dt) to an accumulator, and we only call
 * scrollTo when the accumulator crosses an integer pixel boundary.
 */
@Composable
fun PdfAutoScrollEffect(
    state: PdfAutoScrollState,
    scrollState: ScrollState
) {
    val density = LocalDensity.current.density
    val currentState by rememberUpdatedState(state)

    LaunchedEffect(state.isRunning) {
        if (!state.isRunning) return@LaunchedEffect
        // Pressing play at the end of the sheet replays it from the top; a
        // button that appears to do nothing reads as broken.
        if (autoScrollFinished(scrollState.value, scrollState.maxValue)) {
            scrollState.scrollTo(0)
        }
        var lastNanos = withFrameNanos { it }
        var pixelAccumulator = 0f
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - lastNanos) / 1_000_000_000f
            lastNanos = now
            val pixelsPerSecond = currentState.dpPerSecond * density
            pixelAccumulator += pixelsPerSecond * seconds
            val pixelStep = pixelAccumulator.roundToInt()
            if (pixelStep != 0) {
                pixelAccumulator -= pixelStep.toFloat()
                val target = (scrollState.value + pixelStep).coerceIn(0, scrollState.maxValue.coerceAtLeast(0))
                if (target != scrollState.value) scrollState.scrollTo(target)
                // Reached the last page: stop rather than sit spinning frames.
                if (autoScrollFinished(target, scrollState.maxValue)) {
                    currentState.pause()
                }
            }
        }
    }
}

/**
 * The floating play/pause and speed control. Sits over the preview in a corner
 * so it never takes a line of the sheet away from the reader.
 */
@Composable
fun PdfAutoScrollControls(
    state: PdfAutoScrollState,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = DarkBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, Accent.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val scope = rememberCoroutineScope()
            IconButton(
                onClick = { scope.launch { scrollState.scrollTo(0) } },
                enabled = scrollState.value > 0,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "Jump to top of sheet",
                    tint = if (scrollState.value > 0) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(
                onClick = state::slowDown,
                enabled = state.canSlowDown,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Slow down auto scroll",
                    tint = if (state.canSlowDown) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = "${state.dpPerSecond.toInt()} dp/s",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Highlight,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            IconButton(
                onClick = state::speedUp,
                enabled = state.canSpeedUp,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Speed up auto scroll",
                    tint = if (state.canSpeedUp) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(
                onClick = state::toggle,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = if (state.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isRunning) "Pause auto scroll" else "Start auto scroll",
                    tint = Highlight,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
