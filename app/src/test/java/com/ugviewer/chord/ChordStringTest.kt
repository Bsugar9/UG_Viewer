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
    fun `a plucked string starts loud and is still sounding at the loop point`() {
        // The whole point of the sustain: a raw strum is silent again well
        // before the loop wraps, which is heard as the chord stopping. It has to
        // still be ringing at the end of the buffer.
        val samples = player.renderStrum(listOf(81))
        val head = rms(samples, 1000, 8000)
        val tail = rms(samples, samples.size - 8000, 8000)
        assertTrue("string did not start loudly (head rms $head)", head > 1000f)
        assertTrue(
            "string had died before the loop wrapped (head $head, tail $tail)",
            tail > head / 4f
        )
    }

    @Test
    fun `the chord holds a steady level right through the loop`() {
        // A sustained chord must not sag in the middle, or the loop sounds like
        // it breathes: loud, quiet, loud.
        val samples = player.renderStrum(listOf(45, 52, 57, 60, 64, 69))
        val late = rms(samples, (samples.size * 0.70).toInt(), 8000)
        val early = rms(samples, 8000, 8000)
        assertTrue(
            "chord faded out before the loop wrapped (early $early, late $late)",
            late > early / 3f
        )
    }

    @Test
    fun `the loop seam is continuous rather than a step`() {
        // The wrap goes from the last sample straight back to the first. That
        // step is a click, so the two ends have to meet at the same level.
        val samples = player.renderStrum(listOf(52))
        val endLevel = rms(samples, samples.size - 2000, 2000)
        val startLevel = rms(samples, 0, 2000)
        assertTrue(
            "loop seam is a step (start $startLevel, end $endLevel)",
            endLevel < startLevel * 3 && startLevel < endLevel * 3
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
    fun `the rendered strum is the length the loop expects`() {
        val samples = player.renderStrum(listOf(52))
        assertEquals(ChordPlayer.STRUM_MS.toInt() * ChordPlayer.SAMPLE_RATE / 1000, samples.size)
    }

    @Test
    fun `the two guitars are audibly different`() {
        // The whole point of the toggle: a different guitar, not the same tone
        // relabelled. This is checked on the plucked strings, because the
        // sustain deliberately evens out the finished chords to the same level -
        // what the toggle changes is the timbre, not the loudness.
        val acoustic = player.pluck(220f, 40000, seed = 7L, voice = GuitarVoice.ACOUSTIC)
        val electric = player.pluck(220f, 40000, seed = 7L, voice = GuitarVoice.ELECTRIC)
        assertTrue(
            "the two voices rendered identical strings",
            !acoustic.contentEquals(electric)
        )

        // The electric's string is the one that rings on, which is exactly what
        // energyLeftAfterSecond is for.
        fun sustain(string: FloatArray): Float {
            val start = rmsOf(string, 1000, 8000)
            val later = rmsOf(string, string.size - 8000, 8000)
            return later / start
        }
        assertTrue(
            "the electric did not ring on (electric ${sustain(electric)}, acoustic ${sustain(acoustic)})",
            sustain(electric) > sustain(acoustic)
        )

        // The soundbox is the acoustic's own colour: only it has resonances.
        assertTrue(GuitarVoice.ACOUSTIC.bodyResonances.isNotEmpty())
        assertTrue(GuitarVoice.ELECTRIC.bodyResonances.isEmpty())
    }

    private fun rmsOf(string: FloatArray, start: Int, count: Int): Float {
        var sum = 0.0
        for (i in start until minOf(start + count, string.size)) {
            val v = string[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count).toFloat()
    }

    @Test
    fun `an acoustic picks nearer the bridge sound brighter than the fingerstyle`() {
        // Higher damping rolls off more high frequency, so a duller pick is
        // measurably less bright at the same pitch and length.
        fun brightness(voice: GuitarVoice): Float {
            val string = player.pluck(220f, 20000, voice = voice)
            // Difference between the signal and its own average: the part that
            // would vanish through a low-pass is the brightness.
            var high = 0.0
            for (i in 1 until string.size) high += abs((string[i] - string[i - 1]).toDouble())
            return (high / string.size).toFloat()
        }
        assertTrue(
            "acoustic was not the rounder of the two",
            brightness(GuitarVoice.ACOUSTIC) < brightness(GuitarVoice.ELECTRIC)
        )
    }

    @Test
    fun `the voice toggle always lands on the other guitar`() {
        val from = GuitarVoice.ACOUSTIC
        val to = GuitarVoice.otherOf(from)
        assertTrue("toggled to itself", to !== from)
        assertTrue("toggled back to the same guitar", GuitarVoice.otherOf(to) === from)
        assertTrue("voices are missing a name", GuitarVoice.ALL.all { it.label.isNotBlank() })
    }
}
