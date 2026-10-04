package com.ugviewer.chord

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where a chord's strings actually sound, worked out before any audio exists.
 *
 * [ChordPosition.frets] is indexed low string first (6th) and holds fret ROWS
 * relative to [ChordPosition.baseFret], not absolute fret numbers, so the pitch
 * of a string is its open pitch shifted by the capo, the base fret and the row.
 * A row of -1 is a muted string and sounds nothing.
 *
 * Pure Kotlin so the arithmetic can be tested without an audio device.
 */
object ChordPitch {

    /** Standard guitar, low string first, as MIDI note numbers. */
    val STANDARD_TUNING = listOf(40, 45, 50, 55, 59, 64) // E2 A2 D3 G3 B3 E4

    private val LETTER_SEMITONES = mapOf(
        'C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11
    )

    /**
     * MIDI note number for a written pitch such as "E2", "A#4" or "Bb3", or null
     * when the text is not a note.
     *
     * Middle C is C4 (60), which is the convention every guitar tab uses when it
     * writes a string name with a number.
     */
    fun midiOfNote(text: String): Int? {
        val note = text.trim()
        if (note.length < 2) return null
        val letter = LETTER_SEMITONES[note[0].uppercaseChar()] ?: return null
        val accidental = when (note.getOrNull(1)) {
            '#', '\u266F' -> 1
            'b', '\u266D' -> -1
            else -> 0
        }
        val octave = note.drop(1 + if (accidental == 0) 0 else 1).toIntOrNull() ?: return null
        // C4 = 60, so an octave number adds 12 per step up from C0.
        return (octave + 1) * 12 + letter + accidental
    }

    /**
     * The open pitch of every string, low first, for a tab's free-text
     * [tuningLabel].
     *
     * Tabs name tunings as a list of note letters ("Drop D" means D A D G B E)
     * far more often than with octaves, so when the letters come without octave
     * numbers each one is placed in the octave nearest where that string already
     * sits in standard tuning. That is what distinguishes "D A D G B E" (Drop D,
     * only the 6th string moved) from "E A D G B E" (standard), and it is why a
     * bare D on the 6th string becomes D2 rather than the D an octave up.
     *
     * Anything that does not name exactly as many notes as there are strings
     * ("Standard", "Half Step Down", "") is not a note list, so the standard
     * tuning is used.
     */
    fun openPitchesFor(tuningLabel: String, strings: Int = 6): List<Int> {
        // The trailing lookahead is what makes this work on real labels: the D
        // in "Drop D" is a note name, the one inside "Drop" is just a letter, and
        // only a note that is not part of a longer word counts.
        val names = Regex("[A-Ga-g](?:#|b|\u266F|\u266D)?-?\\d*(?![A-Za-z])")
            .findAll(tuningLabel)
            .map { it.value }
            .toList()
        if (names.size != strings) return STANDARD_TUNING

        val spelled = names.map { midiOfNote(it) }
        // A label where every note carries its octave is taken at face value.
        if (spelled.all { it != null }) return spelled.map { it!! }

        return names.mapIndexed { i, name ->
            val pitchClass = midiOfNoteClass(name)
            val reference = STANDARD_TUNING.getOrElse(i) { 40 + 5 * i }
            // Five octaves of headroom: the 1st string sits 24 semitones above
            // the 6th, so the candidates have to span past the reference or the
            // top string resolves down an octave.
            (0..5)
                .map { pitchClass + 12 * it }
                .minBy { abs(it - reference) }
        }
    }

    /**
     * The pitch class of a note name, ignoring any octave, or null when the text
     * is not a note. Used to place un-octaved names on a real neck.
     */
    private fun midiOfNoteClass(text: String): Int {
        val letter = LETTER_SEMITONES[text.trim()[0].uppercaseChar()] ?: 0
        val accidental = when (text.getOrNull(1)) {
            '#', '\u266F' -> 1
            'b', '\u266D' -> -1
            else -> 0
        }
        return letter + accidental
    }

    /**
     * Every sounding pitch of [position] in MIDDLE-to-HIGH order (the order a
     * strum travels), muted strings dropped.
     *
     * A capo shortens the neck, so every open string sounds [capoFret] higher and
     * every fretted string one capo-fret higher too. The base fret is separate:
     * it is where the four drawn rows sit on THIS instrument, so it only ever
     * moves the fretted rows, never an open string. Conflating the two would
     * double-count a capo on a shape above the nut.
     */
    fun soundingPitches(
        position: ChordPosition,
        openPitches: List<Int> = STANDARD_TUNING,
        capoFret: Int = 0
    ): List<Int> {
        val rowShift = position.baseFret - 1
        return position.frets.indices
            .mapNotNull { i ->
                val fret = position.frets[i]
                if (fret < 0) return@mapNotNull null
                val open = openPitches.getOrNull(i) ?: return@mapNotNull null
                open + capoFret + if (fret == 0) 0 else rowShift + fret
            }
            .sorted()
    }
}

/**
 * A plucked guitar chord, synthesised on the spot and left ringing until it is
 * stopped.
 *
 * There are no samples to ship. Instead each string is modelled the way a real
 * one behaves: a burst of noise for the pick, then that noise recirculating
 * through a delay line one period long with the energy bleeding away each trip
 * round. This is the Karplus-Strong model, and it is why the result reads as a
 * guitar rather than as an electronic tone. Two details do most of the work:
 *
 *  * the low-pass in the feedback path, which is what makes a real string go
 *    dull as it fades instead of holding its brightness;
 *  * the pick position, which knocks out the harmonics that land on the finger
 *    and gives the attack its edge. Picking nearer the bridge is brighter, as
 *    it is on a real guitar.
 *
 * The strings are staggered so the chord arrives as a strum, and the rendered
 * buffer is looped, so the chord keeps sounding - and keeps being re-strummed -
 * until the user stops it. The string is a steel one through a soundbox: the
 * numbers below are all that separates an acoustic from other guitars, and
 * they are what give it its round attack and its woody top end.
 */
class ChordPlayer {

    private var track: AudioTrack? = null
    private var worker: Thread? = null

    /**
     * Read by the writer thread on every pass, so it has to be visible across
     * threads: without the volatile a stop could sit in the thread's cache and
     * the chord would keep going after Stop.
     */
    @Volatile
    private var running = false

    companion object {
        const val SAMPLE_RATE = 44100

        /** One strum, and the length of the buffer that is played on repeat. */
        private const val STRUM_SECONDS = 1.6f

        /**
         * Silence between one strum and the next, in milliseconds. The gap is
         * what makes the repeat read as separate strokes of the same chord
         * rather than one chord held down.
         */
        const val RESTART_DELAY_MS = 1000L

        /**
         * How long the strum is released into that silence. A hard cut from a
         * ringing chord into a second of nothing is a click, so the tail is
         * faded rather than stopped.
         */
        private const val RELEASE_SECONDS = 0.35f

        /** Gap between strings in a strum, low to high. */
        private const val STRUM_STEP_SECONDS = 0.022f

        /**
         * How far the sustain may lift a fading note.
         *
         * This has to be generous: a chord that dies to a thirtieth of its
         * starting amplitude over the length of the loop needs something like
         * thirty times the gain to be held up, and a cap below that just lets
         * the note fade anyway. It is bounded all the same so a note that has
         * genuinely gone is not dragged back up out of its own noise floor.
         */
        private const val MAX_SUSTAIN_GAIN = 400f

        /**
         * Where a held chord sits, as a fraction of full scale. An absolute
         * level rather than a fraction of the strum, so the hold is the same
         * loudness whichever guitar is playing it.
         */
        private const val HELD_LEVEL = 0.09f

        /**
         * The average level a strum is played at, as a fraction of full scale.
         * A hard strum, and well above [HELD_LEVEL]: the note is struck, then
         * settles into the hold.
         */
        private const val STRUM_LEVEL = 0.16f

        /**
         * Below this the signal is noise, not a note, so the sustain lets it go
         * rather than amplifying it into a hiss.
         */
        private const val NOISE_FLOOR = 1e-4f

        /** Milliseconds in one strum, so the UI can reason about the loop. */
        val STRUM_MS: Long = (STRUM_SECONDS * 1000f).toLong()

        // The string itself: a steel one through a soundbox. Together these five
        // numbers are what make the chord sound like an acoustic guitar rather
        // than like a bare synthesised string.

        /**
         * Where the pick strikes, as a fraction of the string from the bridge.
         * Further back is rounder; right over the bridge is thin and bright.
         */
        private const val PICK_POSITION = 0.30f

        /**
         * How much of the neighbouring sample is blended into each one. Higher is
         * a brighter, more slowly dying string; a steel string against a soundbox
         * loses its highs quickly.
         */
        private const val DAMPING = 0.42f

        /** Energy left after one second of ringing, as a fraction. */
        private const val ENERGY_LEFT_AFTER_SECOND = 0.10f

        /** How hard the pick hits. */
        private const val PICK_STRENGTH = 0.75f

        /** Resonant peaks of the soundbox, in Hz. */
        private val BODY_RESONANCES = listOf(100f, 200f, 400f)
    }

    /**
     * Sounds [pitches] (MIDI, any order) as one strum, and keeps re-strumming it
     * — with [RESTART_DELAY_MS] of silence between each one — until [stop]. A tap
     * for a different chord replaces the one sounding rather than layering on top
     * of it.
     */
    @Synchronized
    fun play(pitches: List<Int>) {
        if (pitches.isEmpty()) return
        val samples = renderStrum(pitches.sorted())
        stop()

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        running = true
        track = audioTrack
        audioTrack.play()

        // The strum is fed to the track over and over by a thread of its own
        // rather than by the static-track loop points. Those looked like the
        // obvious way to do it, but they are not dependable: a static track that
        // will not re-arm its loop simply plays once and then sits there, which
        // is a silent failure with nothing to show for it. Writing the buffer
        // again is explicit, so either the chord is still sounding or the thread
        // has died loudly.
        worker = Thread({
            try {
                while (running) {
                    // Blocking, so each strum is played in full before the next
                    // one is written and the timing needs no arithmetic.
                    val written = audioTrack.write(samples, 0, samples.size)
                    if (written < 0) break
                    // The gap the user asked for: a second of silence between
                    // strums, so the chord is heard as separate strokes rather
                    // than one held drone.
                    Thread.sleep(RESTART_DELAY_MS)
                }
            } catch (_: InterruptedException) {
                // Stop interrupts the wait; the thread is finished.
            }
        }, "chord-player").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Renders one strum of [pitches] into the buffer that is played on repeat.
     *
     * A raw strum fades to nothing well before the strum is over, which is heard
     * as the chord dying rather than being played. So the note is sustained to
     * hold a steady level for the length of the strum, and then released: the
     * tail is faded out at the end, because the buffer is played again a second
     * later and a hard cut into that silence is a click.
     */
    internal fun renderStrum(pitches: List<Int>): ShortArray {
        val totalSamples = (SAMPLE_RATE * STRUM_SECONDS).roundToInt()
        val mix = FloatArray(totalSamples)
        // Each string takes an equal share of the headroom, so a six-note chord
        // is no louder than a three-note one.
        val gain = 0.5f / pitches.size

        for ((stringIndex, midi) in pitches.withIndex()) {
            val frequency = 440f * Math.pow(2.0, (midi - 69) / 12.0).toFloat()
            val startSample = (stringIndex * STRUM_STEP_SECONDS * SAMPLE_RATE).roundToInt()
            val string = pluck(frequency, totalSamples - startSample)
            for (i in string.indices) {
                val at = i + startSample
                if (at < mix.size) mix[at] += string[i] * gain
            }
        }

        applyBody(mix)
        sustain(mix)

        // Released at both ends. A step from silence at the start is a click, and
        // so is a step into it at the end, where the next strum is a second away
        // and would otherwise arrive on top of a hard cut.
        val attack = (0.004f * SAMPLE_RATE).roundToInt()
        for (i in 0 until attack) mix[i] *= i.toFloat() / attack
        val release = (RELEASE_SECONDS * SAMPLE_RATE).roundToInt().coerceAtMost(totalSamples)
        for (i in 0 until release) {
            mix[totalSamples - 1 - i] *= i.toFloat() / release
        }

        return ShortArray(totalSamples) {
            (mix[it] * Short.MAX_VALUE)
                .coerceIn(-Short.MAX_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                .roundToInt().toShort()
        }
    }

    /**
     * Holds a fading chord up to a steady level for as long as the loop lasts.
     *
     * The gain tracks a slow running average of the signal and is capped, so a
     * chord that is still ringing is left alone and one that has nearly died is
     * lifted - but only so far, and only while there is real signal to lift. A
     * note that has genuinely gone quiet stays quiet rather than being pumped
     * back up out of its own noise floor.
     */
    private fun sustain(mix: FloatArray) {
        val window = (0.05f * SAMPLE_RATE).roundToInt().coerceAtLeast(1)
        // The level is measured on the ORIGINAL signal. Reading back from the
        // buffer being rewritten would feed the gain its own output, so the
        // running average would chase its own boosted values and the sustain
        // would climb instead of settling.
        val source = mix.copyOf()
        // Both guitars are brought to the same average level before the hold is
        // applied, measured over the whole strum. Peak would be the wrong
        // reference: the acoustic's attack is a far bigger spike on a quieter
        // body than the electric's is on a sustained tone, so normalising peaks
        // would leave the two several decibels apart. A real electric is
        // amplified too; this is where that happens.
        var squares = 0.0
        for (v in source) squares += v.toDouble() * v
        val average = Math.sqrt(squares / source.size).toFloat()
        val normalise = if (average > 1e-6f) STRUM_LEVEL / average else 1f
        val target = HELD_LEVEL

        var sumSquares = 0.0
        var gain = 1f
        for (i in mix.indices) {
            val v = source[i].toDouble()
            sumSquares += v * v
            if (i >= window) {
                val old = source[i - window].toDouble()
                sumSquares -= old * old
            }
            val rms = Math.sqrt(sumSquares / window.coerceAtMost(i + 1))
            // The level the gain is measured against has to be the NORMALISED
            // one, because that is what the gain is about to be applied to. Using
            // the raw level here would set a gain for the raw signal and then
            // multiply by the trim as well, and the two compound.
            val level = rms * normalise
            if (level > NOISE_FLOOR && target > 0f) {
                val wanted = (target / level).toFloat().coerceAtMost(MAX_SUSTAIN_GAIN)
                // Moved towards the wanted gain rather than jumped to it, so the
                // chord settles into the hold instead of pumping.
                gain += (wanted - gain) * 0.0002f
            }
            mix[i] = (source[i] * normalise * gain)
                .coerceIn(-Short.MAX_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
        }
    }

    /**
     * Runs the mix through the soundbox: a peaking filter at each of the body
     * and top-block resonances, which is where an acoustic's woody edge comes
     * from. The air and the wood resonating is most of what separates this from
     * a bare string, so the chord is not recognisable as a guitar without it.
     */
    private fun applyBody(mix: FloatArray) {
        for (frequency in BODY_RESONANCES) {
            val w = 2.0 * PI * frequency / SAMPLE_RATE
            val gain = 1.8
            val bandwidth = 0.06
            // A standard two-pole peaking EQ, solved once for this frequency.
            val alpha = sin(w) / (2.0 * (10.0.pow(bandwidth / 20.0) - 1.0))
            val a0 = 1.0 + alpha / gain
            val b0 = 1.0 + alpha * gain
            val b1 = -2.0 * cos(w)
            val b2 = 1.0 - alpha * gain
            val a1 = -2.0 * cos(w)
            val a2 = 1.0 - alpha / gain
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (i in mix.indices) {
                val x0 = mix[i].toDouble()
                val y0 = (b0 / a0) * x0 + (b1 / a0) * x1 + (b2 / a0) * x2 -
                    (a1 / a0) * y1 - (a2 / a0) * y2
                mix[i] = y0.toFloat()
                x2 = x1
                x1 = x0
                y2 = y1
                y1 = y0
            }
        }
    }

    /**
     * One plucked string: noise for the pick, then that noise recirculating
     * through a delay line a period long until it fades.
     *
     * Deterministic for a given [seed], so the rendered tone can be checked
     * without an audio device.
     */
    internal fun pluck(
        frequency: Float,
        length: Int,
        seed: Long = 12345L
    ): FloatArray {
        val out = FloatArray(length)
        if (frequency <= 0f || length < 2) return out
        // The delay line is one period long, which is what fixes the pitch.
        val period = (SAMPLE_RATE / frequency).roundToInt().coerceIn(2, length)
        val line = FloatArray(period)

        // The pick: a burst of noise. Striking the string only excites the
        // length between the pick and the end, and striking it back from the
        // bridge is what knocks out a harmonic - the round attack a fingerstyle
        // player gets, rather than the thin one a pick at the bridge makes.
        val random = java.util.Random(seed)
        val pick = (period * PICK_POSITION).roundToInt().coerceIn(1, period - 1)
        for (i in 0 until period) {
            val noise = random.nextFloat() * 2f - 1f
            line[i] = if (i <= pick) noise * PICK_STRENGTH else noise * 0.5f * PICK_STRENGTH
        }

        // The decay is wanted per second, and one pass takes one period, so scale
        // it to the pitch. Without this a bass note would make a fraction of the
        // passes a treble note makes and ring on long after it should have gone.
        val decayPerPass = Math.pow(ENERGY_LEFT_AFTER_SECOND.toDouble(), period.toDouble() / SAMPLE_RATE)
            .toFloat()

        var index = 0
        for (i in 0 until length) {
            val current = line[index]
            out[i] = current
            // One trip round the delay line, and the whole life of the note:
            // blend towards the next sample in the line, which is a low-pass, so
            // the upper harmonics go first, then scale by the decay so the whole
            // thing fades.
            val next = line[(index + 1) % period]
            line[index] = (current * (1f - DAMPING) + next * DAMPING) * decayPerPass
            index = (index + 1) % period
        }
        return out
    }

    /** True while a chord is sounding. */
    @Synchronized
    fun isPlaying(): Boolean = running && track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /** Silences the chord and stops the writer that keeps feeding it. */
    @Synchronized
    fun stop() {
        running = false
        // The thread is usually asleep in the gap between strums rather than
        // writing, so interrupting is what actually ends it. The track is
        // stopped first so a write in progress returns rather than blocking.
        worker?.interrupt()
        track?.let { running ->
            runCatching {
                if (running.state == AudioTrack.STATE_INITIALIZED) running.stop()
            }
            runCatching { running.release() }
        }
        // Not joined: the thread is a daemon on its way out, and blocking the
        // caller for a join it does not need would make Stop feel laggy.
        worker = null
        track = null
    }

    /** Stops the chord and frees the track; call when leaving the screen. */
    @Synchronized
    fun release() = stop()
}
