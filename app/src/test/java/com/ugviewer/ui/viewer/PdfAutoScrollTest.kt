package com.ugviewer.ui.viewer

import com.ugviewer.ui.viewer.AUTO_SCROLL_SPEEDS
import com.ugviewer.ui.viewer.MAX_AUTO_SCROLL_DP_PER_SECOND
import com.ugviewer.ui.viewer.MIN_AUTO_SCROLL_DP_PER_SECOND
import com.ugviewer.ui.viewer.PdfAutoScrollState
import com.ugviewer.ui.viewer.DEFAULT_AUTO_SCROLL_DP_PER_SECOND
import com.ugviewer.ui.viewer.autoScrollFinished
import com.ugviewer.ui.viewer.autoScrollTarget
import com.ugviewer.ui.viewer.nearestSpeedIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-scroll pacing and its end stops, without a frame clock: these are the
 * rules that decide whether a sheet crawls, runs away, or stops dead at the
 * last page.
 */
class PdfAutoScrollTest {

    @Test
    fun `advances by speed times elapsed time`() {
        // 100 px/s for half a second from a standstill.
        assertEquals(50, autoScrollTarget(current = 0, maxValue = 1000, pixelsPerSecond = 100f, deltaSeconds = 0.5f))
    }

    @Test
    fun `accumulates across frames rather than restarting from zero`() {
        // Three frames of 20px: 60px total, which only works if each frame
        // continues from the last instead of recomputing from the start.
        var position = 0
        repeat(3) {
            position = autoScrollTarget(position, maxValue = 1000, pixelsPerSecond = 100f, deltaSeconds = 0.2f)
        }
        assertEquals(60, position)
    }

    @Test
    fun `stops at the last pixel instead of overshooting`() {
        val target = autoScrollTarget(current = 990, maxValue = 1000, pixelsPerSecond = 100f, deltaSeconds = 1f)
        assertEquals(1000, target)
    }

    @Test
    fun `holds position when a frame reports a negative delta`() {
        // A frame clock glitch must not throw the reader back to the title.
        val target = autoScrollTarget(current = 10, maxValue = 1000, pixelsPerSecond = 100f, deltaSeconds = -1f)
        assertEquals(10, target)
    }

    @Test
    fun `stays put when the whole sheet already fits on screen`() {
        // maxValue 0 means nothing to scroll: an auto-scroll started here must
        // not spin frames pretending it is moving.
        assertEquals(0, autoScrollTarget(current = 0, maxValue = 0, pixelsPerSecond = 200f, deltaSeconds = 0.5f))
    }

    @Test
    fun `a zero speed holds position`() {
        assertEquals(120, autoScrollTarget(current = 120, maxValue = 1000, pixelsPerSecond = 0f, deltaSeconds = 0.5f))
    }

    @Test
    fun `is finished only at the end of a scrollable document`() {
        assertTrue(autoScrollFinished(current = 1000, maxValue = 1000))
        assertFalse(autoScrollFinished(current = 999, maxValue = 1000))
        // Not laid out yet: nothing to finish, so the run must not be cancelled.
        assertFalse(autoScrollFinished(current = 0, maxValue = 0))
    }

    @Test
    fun `toggle and pause control the run without disturbing the speed`() {
        val state = PdfAutoScrollState(DEFAULT_AUTO_SCROLL_DP_PER_SECOND)
        assertFalse(state.isRunning)

        state.toggle()
        assertTrue(state.isRunning)

        state.speedUp()
        val tuned = state.dpPerSecond

        // A reader grabbing the sheet stops the self-scroll but keeps the
        // pace they picked, so tapping play again resumes at that speed.
        state.pause()
        assertFalse(state.isRunning)
        assertEquals(tuned, state.dpPerSecond, 0f)
    }

    @Test
    fun `speed stops at the ends of its range`() {
        val state = PdfAutoScrollState(DEFAULT_AUTO_SCROLL_DP_PER_SECOND)

        repeat(50) { state.slowDown() }
        assertEquals(MIN_AUTO_SCROLL_DP_PER_SECOND, state.dpPerSecond, 0f)
        assertFalse(state.canSlowDown)

        repeat(100) { state.speedUp() }
        assertEquals(MAX_AUTO_SCROLL_DP_PER_SECOND, state.dpPerSecond, 0f)
        assertFalse(state.canSpeedUp)
    }

    @Test
    fun `opens slow enough to play along with`() {
        // A sheet scrolls at the default without a tap of - and still leaves a
        // lyric line and its chords on screen long enough to read them. On a
        // roughly 510dp page that is well over ten seconds per page.
        val state = PdfAutoScrollState(DEFAULT_AUTO_SCROLL_DP_PER_SECOND)
        val pageDp = 510f
        val secondsPerPage = pageDp / state.dpPerSecond
        assertTrue("default is $secondsPerPage s/page", secondsPerPage > 10f)
    }

    @Test
    fun `the slowest rung is a crawl, and - reaches it quickly`() {
        val state = PdfAutoScrollState(DEFAULT_AUTO_SCROLL_DP_PER_SECOND)

        // One tap below the default must be a real drop, not a nudge.
        state.slowDown()
        assertEquals(20f, state.dpPerSecond, 0f)

        // ...and the crawl itself is slow enough to freeze on a single chord.
        repeat(7) { state.slowDown() }
        assertEquals(1f, state.dpPerSecond, 0f)
    }

    @Test
    fun `every rung is a distinct speed in ascending order`() {
        assertEquals(AUTO_SCROLL_SPEEDS.sorted(), AUTO_SCROLL_SPEEDS)
        assertEquals(AUTO_SCROLL_SPEEDS.distinct(), AUTO_SCROLL_SPEEDS)
        assertEquals(AUTO_SCROLL_SPEEDS.first(), MIN_AUTO_SCROLL_DP_PER_SECOND, 0f)
        assertEquals(AUTO_SCROLL_SPEEDS.last(), MAX_AUTO_SCROLL_DP_PER_SECOND, 0f)
        assertTrue(AUTO_SCROLL_SPEEDS.contains(DEFAULT_AUTO_SCROLL_DP_PER_SECOND))
    }

    @Test
    fun `an odd starting speed snaps onto the scale`() {
        // So the readout can never show a pace that no button can reach, and a
        // nonsensical request lands on the crawl rather than a frozen sheet.
        assertEquals(1f, PdfAutoScrollState(0f).dpPerSecond, 0f)
        assertEquals(1f, PdfAutoScrollState(-50f).dpPerSecond, 0f)
        assertEquals(5f, PdfAutoScrollState(7f).dpPerSecond, 0f)
        assertEquals(20f, PdfAutoScrollState(26f).dpPerSecond, 0f)
        assertEquals(MAX_AUTO_SCROLL_DP_PER_SECOND, PdfAutoScrollState(9999f).dpPerSecond, 0f)
    }

    @Test
    fun `nearest rung rounds down onto the scale`() {
        assertEquals(0, nearestSpeedIndex(-1f))
        assertEquals(0, nearestSpeedIndex(1f))
        assertEquals(1, nearestSpeedIndex(2f))
        assertEquals(3, nearestSpeedIndex(5f))
        assertEquals(4, nearestSpeedIndex(8f))
        assertEquals(6, nearestSpeedIndex(20f))
        assertEquals(AUTO_SCROLL_SPEEDS.lastIndex, nearestSpeedIndex(1000f))
    }
}
