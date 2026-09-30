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
                .minBy { kotlin.math.abs(it - reference) }
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
 * Which guitar is being played.
 *
 * The two are the same physical model with different numbers, which is the point:
 * a steel-string through a soundbox and a solid-body electric are not different
 * instruments so much as the same string excited and damped differently. So the
 * difference lives in the three things a player actually changes between them.
 */
class GuitarVoice(
    /** Shown on the toggle, and what the user recognises the guitar by. */
    val label: String,

    /**
     * Where the pick strikes, as a fraction of the string from the bridge. A
     * steel-string fingerstyle sits further back, which is rounder; a flatpick
     * on an electric sits right over the bridge, which is thinner and brighter.
     */
    val pickPosition: Float,

    /**
     * How much of the neighbouring sample is blended into each one. Higher is a
     * brighter, more slowly dying string. An electric's magnetic pickup barely
     * loads the string, so it rings on; a steel string against a soundbox loses
     * its highs quickly.
     */
    val damping: Float,

    /**
     * Energy left after one second of ringing. A steel string through a soundbox
     * dies noticeably faster than a solid body's.
     */
    val energyLeftAfterSecond: Float,

    /**
     * How hard the pick hits. A flatpick on an electric is a harder attack, which
     * is most of why that instrument cuts through a mix.
     */
    val pickStrength: Float,

    /**
     * Resonant peaks of a soundbox, in Hz. An acoustic guitar's top end comes
     * from the air and the wood resonating, which is what gives it the woody
     * edge an electric cannot produce; an electric has no such body, so it is
     * left with none.
     */
    val bodyResonances: List<Float> = emptyList()
) {
    companion object {
        /** Steel string through a soundbox: round, woody, dies at a natural rate. */
        val ACOUSTIC = GuitarVoice(
            label = "Acoustic",
            pickPosition = 0.30f,
            damping = 0.42f,
            energyLeftAfterSecond = 0.10f,
            pickStrength = 0.75f,
            bodyResonances = listOf(100f, 200f, 400f)
        )

        /** Solid body with a magnetic pickup: bright, hard attack, rings longer. */
        val ELECTRIC = GuitarVoice(
            label = "Electric",
            pickPosition = 0.10f,
            damping = 0.14f,
            energyLeftAfterSecond = 0.30f,
            pickStrength = 1f
        )

        val ALL = listOf(ACOUSTIC, ELECTRIC)

        /** The other guitar, for the toggle. */
        fun otherOf(voice: GuitarVoice): GuitarVoice =
            if (voice === ACOUSTIC) ELECTRIC else ACOUSTIC
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
 * until the user stops it. Which guitar it sounds like is [GuitarVoice]: the
 * same model with a different pick, damping and decay, plus the soundbox
 * resonance that only an acoustic has.
 */
class ChordPlayer {

    private var track: AudioTrack? = null

    companion object {
        const val SAMPLE_RATE = 44100

        /** One strum, and the length of the loop that repeats it. */
        private const val STRUM_SECONDS = 1.6f

        /**
         * How much of the next strum is mixed over the end of this one, so the
         * loop point is inaudible. Without it the chord is cut off and starts
         * again, which is a gap the ear reads as the sound having stopped.
         */
        private const val CROSSFADE_SECONDS = 0.45f

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
    }

    /**
     * Sounds [pitches] (MIDI, any order) as one strum on [voice] and keeps it
     * going in a loop until [stop]. A tap for a different chord replaces the one
     * sounding rather than layering on top of it.
     */
    @Synchronized
    fun play(pitches: List<Int>, voice: GuitarVoice = GuitarVoice.ACOUSTIC) {
        if (pitches.isEmpty()) return
        stop()
        playLooped(renderStrum(pitches.sorted(), voice))
    }

    /**
     * Renders one strum of [pitches] into a buffer the track can loop.
     *
     * A raw strum fades to nothing well before the loop comes round again, which
     * is heard as the chord stopping and restarting rather than continuing. So
     * two things are done about it: the note is sustained to hold a steady level
     * for the length of the loop, and the end of the strum is crossfaded into
     * the start of the next one, so the wrap has no seam to click on.
     */
    internal fun renderStrum(
        pitches: List<Int>,
        voice: GuitarVoice = GuitarVoice.ACOUSTIC
    ): ShortArray {
        val loopSamples = (SAMPLE_RATE * STRUM_SECONDS).roundToInt()
        val crossfade = (SAMPLE_RATE * CROSSFADE_SECONDS).roundToInt().coerceAtMost(loopSamples / 2)
        val mix = FloatArray(loopSamples)
        // Each string takes an equal share of the headroom, so a six-note chord
        // is no louder than a three-note one.
        val gain = 0.5f / pitches.size

        for ((stringIndex, midi) in pitches.withIndex()) {
            val frequency = 440f * Math.pow(2.0, (midi - 69) / 12.0).toFloat()
            val startSample = (stringIndex * STRUM_STEP_SECONDS * SAMPLE_RATE).roundToInt()
            val string = pluck(frequency, loopSamples - startSample, voice = voice)
            for (i in string.indices) {
                val at = i + startSample
                if (at < mix.size) mix[at] += string[i] * gain
            }
        }

        if (voice.bodyResonances.isNotEmpty()) applyBody(mix, voice)
        sustain(mix)

        // The loop wraps from the end of the strum straight back to the start, and
        // that seam is audible as a click. So the first stretch of the loop is
        // crossfaded against the last stretch: the strum's tail is faded in over
        // its own attack, which is exactly what the ear is about to hear next.
        val out = FloatArray(loopSamples)
        for (i in 0 until loopSamples) {
            out[i] = if (i < crossfade) {
                val t = i.toFloat() / crossfade
                mix[i] * t + mix[loopSamples - crossfade + i] * (1f - t)
            } else {
                mix[i]
            }
        }

        // The loop now arrives at the seam already carrying the tail, so a step
        // from silence is what would click; a few milliseconds of fade removes it.
        val edge = (0.004f * SAMPLE_RATE).roundToInt()
        for (i in 0 until edge) out[i] *= i.toFloat() / edge

        return ShortArray(loopSamples) {
            (out[it] * Short.MAX_VALUE)
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
     * Runs the mix through the soundbox: a couple of peaking filters at the
     * body and top-block resonances, which is where an acoustic's woody edge
     * comes from. An electric has no body to resonate, so it skips this entirely
     * - that absence is as much a part of its sound as the filters are of the
     * acoustic's.
     */
    private fun applyBody(mix: FloatArray, voice: GuitarVoice) {
        for (frequency in voice.bodyResonances) {
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
        seed: Long = 12345L,
        voice: GuitarVoice = GuitarVoice.ACOUSTIC
    ): FloatArray {
        val out = FloatArray(length)
        if (frequency <= 0f || length < 2) return out
        // The delay line is one period long, which is what fixes the pitch.
        val period = (SAMPLE_RATE / frequency).roundToInt().coerceIn(2, length)
        val line = FloatArray(period)

        // The pick: a burst of noise. Striking the string only excites the
        // length between the pick and the end, and the pick position is what
        // knocks out a harmonic - the hollow attack a pick halfway along a
        // string makes, and the bright one it makes near the bridge.
        val random = java.util.Random(seed)
        val pick = (period * voice.pickPosition).roundToInt().coerceIn(1, period - 1)
        for (i in 0 until period) {
            val noise = random.nextFloat() * 2f - 1f
            line[i] = if (i <= pick) noise * voice.pickStrength else noise * 0.5f * voice.pickStrength
        }

        // The decay is wanted per second, and one pass takes one period, so scale
        // it to the pitch. Without this a bass note would make a fraction of the
        // passes a treble note makes and ring on long after it should have gone.
        val decayPerPass = Math.pow(voice.energyLeftAfterSecond.toDouble(), period.toDouble() / SAMPLE_RATE)
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
            line[index] = (current * (1f - voice.damping) + next * voice.damping) * decayPerPass
            index = (index + 1) % period
        }
        return out
    }

    private fun playLooped(samples: ShortArray) {
        val bytes = samples.size * 2
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
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(samples, 0, samples.size)
        track = audioTrack

        // The chord repeats from the top of the strum until stopped, so the
        // user hears it as long as they want without touching anything.
        runCatching { audioTrack.setLoopPoints(0, samples.size, -1) }
        audioTrack.play()
    }

    /** True while a chord is sounding. */
    @Synchronized
    fun isPlaying(): Boolean = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /** Silences the chord. */
    @Synchronized
    fun stop() {
        track?.let { running ->
            runCatching {
                if (running.state == AudioTrack.STATE_INITIALIZED) {
                    // A loop has to be cleared before the track can be stopped
                    // cleanly; leaving one set makes the next play() a no-op.
                    running.setLoopPoints(0, 0, 0)
                    running.stop()
                }
            }
            runCatching { running.release() }
        }
        track = null
    }

    /** Stops the chord and frees the track; call when leaving the screen. */
    @Synchronized
    fun release() = stop()
}
