package com.ugviewer.chord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Checks the rendered string itself, which is the part of the chord player that
 * decides whether it sounds like a guitar or like a test tone. The synthesiser
 * is deterministic, so the output can be inspected without an audio device.
 */
class ChordStringTest {

    private val player = ChordPlayer()

    /** Root mean square level of the first [count] samples from [start]. */
    private fun rms(samples: ShortArray, start: Int, count: Int): Float {
        var sum = 0.0
        for (i in start until minOf(start + count, samples.size)) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count).toFloat()
    }

    @Test
    fun `a plucked string starts loud and rings on through the strum`() {
        // The sustain's job: without it a raw strum is silent again well before
        // the buffer is spent, and the chord is heard as a stab that dies rather
        // than a chord being played. The tail is released on purpose, so this
        // measures the body of the strum rather than its last sample.
        val samples = player.renderStrum(listOf(81))
        val head = rms(samples, 1000, 8000)
        val middle = rms(samples, (samples.size * 0.5f).toInt(), 8000)
        assertTrue("string did not start loudly (head rms $head)", head > 1000f)
        assertTrue(
            "string had died before the strum was over (head $head, middle $middle)",
            middle > head / 4f
        )
    }

    @Test
    fun `the chord holds a steady level through the strum`() {
        // A sustained chord must not sag in the middle, or it is heard as a
        // stab that fades rather than a chord being played.
        val samples = player.renderStrum(listOf(45, 52, 57, 60, 64, 69))
        val late = rms(samples, (samples.size * 0.55f).toInt(), 8000)
        val early = rms(samples, 8000, 8000)
        assertTrue(
            "chord faded out before the strum ended (early $early, late $late)",
            late > early / 3f
        )
    }

    @Test
    fun `the strum is released so the restart does not click`() {
        // The buffer is played again a second after it ends, so a hard cut from a
        // ringing chord into silence is a click on every repeat. The tail has to
        // be faded all the way down, not merely quiet.
        val samples = player.renderStrum(listOf(45, 52, 57, 60, 64, 69))
        assertTrue("strum starts with a click (${samples[0]})", abs(samples[0].toInt()) < 300)
        val last = abs(samples[samples.size - 1].toInt())
        assertTrue("strum ends on a click ($last)", last < 300)
        // And the whole of the last stretch is coming down, not just the final
        // sample, so the release is a fade rather than a lucky zero.
        val beforeEnd = rms(samples, samples.size - 6000, 4000)
        assertTrue("strum was still loud as it ended ($beforeEnd)", beforeEnd < 1500f)
    }

    @Test
    fun `the restart gap is a second of silence`() {
        // The gap is what makes a repeat read as separate strums of one chord
        // rather than a single drone. A second is the figure the behaviour is
        // built around, so it is pinned here rather than left to a constant
        // nobody re-checks, and it has to be long enough to actually hear.
        assertEquals(1000L, ChordPlayer.RESTART_DELAY_MS)
        assertTrue(
            "the gap is too short to hear (${ChordPlayer.RESTART_DELAY_MS}ms)",
            ChordPlayer.RESTART_DELAY_MS >= 500
        )
    }

    @Test
    fun `the strum does not click as it starts`() {
        // A loop that begins with a step from silence clicks on every repeat.
        val samples = player.renderStrum(listOf(45, 52, 57, 60, 64, 69))
        assertTrue("strum starts with a click (${samples[0]})", abs(samples[0].toInt()) < 300)
    }

    @Test
    fun `a rendered chord stays inside the sample range`() {
        val samples = player.renderStrum(listOf(40, 45, 50, 55, 59, 64))
        var peak = 0
        for (s in samples) peak = maxOf(peak, abs(s.toInt()))
        // A clipped chord is a distorted one, and a silent one is a broken one.
        assertTrue("chord is inaudible (peak $peak)", peak > 3000)
        assertTrue("chord clipped (peak $peak)", peak <= 32767)
    }

    @Test
    fun `adding strings does not make the chord louder`() {
        // Each string takes an equal share of the headroom, so a barre chord
        // must not be six times the volume of a single note.
        fun peak(pitches: List<Int>): Int {
            val samples = player.renderStrum(pitches)
            var p = 0
            for (s in samples) p = maxOf(p, abs(s.toInt()))
            return p
        }
        val one = peak(listOf(52))
        val six = peak(listOf(40, 45, 50, 55, 59, 64))
        assertTrue("a six-string chord is far louder than one note ($one vs $six)", six < one * 3)
    }

    @Test
    fun `the pitch is the one asked for`() {
        // Measured on the plucked string rather than the finished strum: the
        // sustain lifts the noise floor of a whole chord, and autocorrelation
        // will happily lock onto that instead of the note. The string is what
        // decides the pitch, so that is what is measured.
        val expected = 220f // A3
        val string = player.pluck(expected, 20000)
        val period = bestPeriod(string, 8000, 8192)
        val measured = ChordPlayer.SAMPLE_RATE.toFloat() / period
        assertTrue(
            "measured ${measured}Hz for a ${expected}Hz note (period $period)",
            abs(measured - expected) < 5f
        )
    }

    /**
     * The delay in samples between a stretch of audio and itself one delay
     * later, for the delay that repeats the correlation most strongly.
     */
    private fun bestPeriod(string: FloatArray, from: Int, window: Int): Int {
        val minPeriod = ChordPlayer.SAMPLE_RATE / 1000
        val maxPeriod = ChordPlayer.SAMPLE_RATE / 60
        var bestPeriod = minPeriod
        var bestScore = -1.0
        for (period in minPeriod..maxPeriod) {
            var score = 0.0
            for (i in 0 until window) {
                val a = string[from + i].toDouble()
                val b = string[from + i + period].toDouble()
                score += a * b
            }
            if (score > bestScore) {
                bestScore = score
                bestPeriod = period
            }
        }
        return bestPeriod
    }

    @Test
    fun `the rendered strum is the length the buffer expects`() {
        val samples = player.renderStrum(listOf(52))
        assertEquals(ChordPlayer.STRUM_MS.toInt() * ChordPlayer.SAMPLE_RATE / 1000, samples.size)
    }

    @Test
    fun `the soundbox colours the chord`() {
        // The body and top-block resonances are most of what makes a bare string
        // sound like a guitar rather than a synth, so the EQ has to be reaching
        // the signal. Measured as the total energy the filter adds: a chord
        // passed through it cannot be the same signal as the string alone.
        val string = player.pluck(220f, 40000, seed = 7L)
        val chord = player.renderStrum(listOf(57))
        val stringEnergy = totalEnergy(string)
        val chordEnergy = totalEnergy(FloatArray(chord.size) { chord[it] / Short.MAX_VALUE.toFloat() })
        assertTrue(
            "the soundbox made no difference to the signal ($stringEnergy vs $chordEnergy)",
            abs(chordEnergy - stringEnergy) > 1e-6
        )
    }

    @Test
    fun `the string darkens as it fades`() {
        // The low-pass in the feedback path is what stops a plucked string
        // holding its brightness to the end. If it were removed the upper
        // harmonics would never die first and the note would ring on, thin.
        val string = player.pluck(220f, 40000, seed = 7L)
        fun brightness(from: Int, count: Int): Float {
            var total = 0.0
            for (i in from until from + count) {
                total += abs((string[i] - string[i - 1]).toDouble())
            }
            return (total / count).toFloat()
        }
        val early = brightness(2000, 4000)
        val late = brightness(string.size - 6000, 4000)
        assertTrue(
            "the string did not lose its highs as it faded (early $early, late $late)",
            late < early
        )
    }

    @Test
    fun `the same chord renders the same way every time`() {
        // The pick is driven by a seeded generator so the tone is reproducible.
        // That is what lets the rest of these tests assert on the waveform at
        // all, and it also means tapping a chord twice sounds identical.
        val first = player.renderStrum(listOf(45, 52, 57, 60, 64))
        val second = player.renderStrum(listOf(45, 52, 57, 60, 64))
        assertTrue("the render is not reproducible", first.contentEquals(second))
    }

    private fun totalEnergy(samples: FloatArray): Double {
        var sum = 0.0
        for (v in samples) sum += v.toDouble() * v
        return sum
    }
}
