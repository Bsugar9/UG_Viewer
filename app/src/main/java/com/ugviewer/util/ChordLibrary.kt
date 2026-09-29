package com.ugviewer.util

/**
 * Offline chord-shape library. Parses chord names and produces fretboard
 * positions for all playable shapes: a theory-based barre-chord engine
 * (E-shape and A-shape forms realized from chord intervals) plus curated
 * open voicings for the most common chords.
 */
object ChordLibrary {

    /** A playable chord shape. frets: 6 entries low E -> high E (-1 = muted, 0 = open). */
    data class ChordShape(
        val chordName: String,
        val type: String,
        val frets: List<Int>,
        val fingers: List<Int>,
        val baseFret: Int,
        val shapeName: String
    )

    data class ParsedChord(val root: String, val quality: String)

    // Pitch classes, C-anchored (C=0).
    private val NOTE_INDEX = mapOf(
        "C" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3,
        "E" to 4, "F" to 5, "F#" to 6, "Gb" to 6, "G" to 7, "G#" to 8,
        "Ab" to 8, "A" to 9, "A#" to 10, "Bb" to 10, "B" to 11
    )

    private val ROOT_NORMALIZATION = mapOf(
        "Db" to "C#", "D#" to "Eb", "Gb" to "F#", "G#" to "Ab", "A#" to "Bb"
    )

    val supportedTypes: List<String> =
        listOf("", "m", "7", "m7", "maj7", "sus2", "sus4", "6", "add9", "dim", "aug")

    private val TYPE_LABEL = mapOf(
        "" to "Major", "m" to "Minor", "7" to "7", "m7" to "m7", "maj7" to "maj7",
        "sus2" to "sus2", "sus4" to "sus4", "6" to "6", "add9" to "add9",
        "dim" to "dim", "aug" to "aug"
    )

    /** Intervals (semitones from root) per supported chord type. */
    private val TYPE_INTERVALS = mapOf(
        "" to listOf(0, 4, 7),
        "m" to listOf(0, 3, 7),
        "7" to listOf(0, 4, 7, 10),
        "m7" to listOf(0, 3, 7, 10),
        "maj7" to listOf(0, 4, 7, 11),
        "6" to listOf(0, 4, 7, 9),
        // add9 as 0,2,4,7 mod 12: the 9th (14) lands on the same string
        // choice the 2nd does inside one octave of frets.
        "add9" to listOf(0, 2, 4, 7),
        "sus2" to listOf(0, 2, 7),
        "sus4" to listOf(0, 5, 7),
        "dim" to listOf(0, 3, 6),
        "aug" to listOf(0, 4, 8)
    )

    // Open-string pitch classes, C-anchored (C=0), low E -> high E. This must
    // share NOTE_INDEX's anchor: pitch classes are only comparable to root
    // indices when both come out of the same table.
    private val OPEN_PC = listOf(4, 9, 2, 7, 11, 4)

    /** Parses "C", "Am7", "F#m7", "Cadd9", "G/B" (bass note ignored for shapes). */
    fun parseChord(name: String): ParsedChord? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        var i = 1
        if (trimmed.length > 1 && (trimmed[1] == '#' || trimmed[1] == 'b')) i = 2
        val root = trimmed.substring(0, i)
        if (root[0] !in 'A'..'G') return null
        var quality = trimmed.substring(i)
        val slash = quality.indexOf('/')
        if (slash >= 0) quality = quality.substring(0, slash)
        return ParsedChord(ROOT_NORMALIZATION[root] ?: root, quality)
    }

    /**
     * All shapes for one chord: barre forms first, then an open voicing.
     * Empty if the name/quality is not supported.
     */
    fun getShapes(name: String): List<ChordShape> {
        val parsed = parseChord(name) ?: return emptyList()
        val intervals = TYPE_INTERVALS[parsed.quality] ?: return emptyList()
        val rootIdx = NOTE_INDEX[parsed.root] ?: return emptyList()
        val shapes = mutableListOf<ChordShape>()
        barreShape(parsed, rootIdx, intervals, eShape = true)?.let { shapes.add(it) }
        barreShape(parsed, rootIdx, intervals, eShape = false)?.let { shapes.add(it) }
        openShape(parsed)?.let { shapes.add(it) }
        return shapes
    }

    /** Every supported type for a root: all "C" chords when called with "C". */
    fun search(root: String): List<ChordShape> {
        val parsedRoot = parseChord(root)?.root ?: return emptyList()
        val results = mutableListOf<ChordShape>()
        for (t in supportedTypes) results.addAll(getShapes(parsedRoot + t))
        return results
    }

    /** Search entry point: "C" -> all C chords; "Am7" -> that chord's shapes. */
    fun searchChords(query: String): List<ChordShape> {
        val parsed = parseChord(query) ?: return emptyList()
        return if (parsed.quality.isEmpty()) search(parsed.root)
        else getShapes(parsed.root + parsed.quality)
    }

    /**
     * Realizes a moveable barre shape from chord intervals. For each string
     * (high to low) pick the lowest fret 0..4 above the barre whose pitch is a
     * chord tone. Chord tones may repeat across strings (majors have to: the
     * third appears once but the root and fifth twice over six strings), so no
     * uniqueness is demanded. frets are absolute; baseFret is the diagram
     * position label, 1 when the shape sits at the nut (barre on the open
     * pitch, i.e. E- and A-shaped roots).
     */
    private fun barreShape(
        parsed: ParsedChord,
        rootIdx: Int,
        intervals: List<Int>,
        eShape: Boolean
    ): ChordShape? {
        // Fret that puts the chord root on the shape's root string.
        val barre = if (eShape) (rootIdx - 4 + 12) % 12   // 6th string open = E (pc 4)
                    else (rootIdx - 9 + 12) % 12          // 5th string open = A (pc 9)
        // Barre 0 is legitimate: the E-root and A-root open shapes ARE the
        // moveable form at fret 0.
        if (barre !in 0..14) return null

        val frets = IntArray(6) { -1 }
        val fingers = IntArray(6)
        for (s in 5 downTo 0) {
            var assigned = false
            for (f in 0..4) {
                val pc = (OPEN_PC[s] + barre + f) % 12
                val iv = (pc - rootIdx + 12) % 12
                if (iv in intervals) {
                    frets[s] = barre + f
                    fingers[s] = if (f == 0) 1 else (f + 1).coerceAtMost(4)
                    assigned = true
                    break
                }
            }
            if (!assigned) return null
        }
        val name = displayRoot(parsed) + parsed.quality
        return ChordShape(
            chordName = name,
            type = TYPE_LABEL[parsed.quality] ?: parsed.quality,
            frets = frets.toList(),
            fingers = fingers.toList(),
            // Diagram position label: the lowest fretted fret, floored at 1 so
            // open-position shapes (E and A roots) read as nut shapes. Off by
            // one here and every dot on the diagram shifts a row.
            baseFret = barre.coerceAtLeast(1),
            shapeName = if (eShape) "E-form barre" else "A-form barre"
        )
    }

    /** Curated open (nut-position) voicings, self-verified against theory. */
    private fun openShape(parsed: ParsedChord): ChordShape? {
        val key = parsed.root + parsed.quality
        val entry = OPEN_SHAPES[key] ?: return null
        val rootIdx = NOTE_INDEX[parsed.root] ?: return null
        val intervals = TYPE_INTERVALS[parsed.quality] ?: return null
        val frets = entry.first
        // Verify every ringing string is a chord tone; drop bad transcriptions.
        for (s in 0..5) {
            val f = frets[s]
            if (f < 0) continue
            val iv = (OPEN_PC[s] + f - rootIdx + 12) % 12
            if (iv !in intervals) return null
        }
        return ChordShape(
            chordName = key,
            type = TYPE_LABEL[parsed.quality] ?: parsed.quality,
            frets = frets,
            fingers = entry.second,
            baseFret = 1,
            shapeName = "Open"
        )
    }

    private fun displayRoot(parsed: ParsedChord): String = parsed.root

    // frets (low E -> high E; -1 = muted) and finger hints for common open chords.
    // Entries that are not pure chord tones are rejected at runtime by openShape.
    private val OPEN_SHAPES = mapOf(
        "C" to (listOf(-1, 3, 2, 0, 1, 0) to listOf(0, 3, 2, 0, 1, 0)),
        "Cmaj7" to (listOf(-1, 3, 2, 0, 0, 0) to listOf(0, 3, 2, 0, 0, 0)),
        "C7" to (listOf(-1, 3, 2, 3, 1, 0) to listOf(0, 3, 2, 4, 1, 0)),
        "C6" to (listOf(-1, 3, 2, 2, 1, 0) to listOf(0, 3, 2, 4, 1, 0)),
        "Cadd9" to (listOf(-1, 3, 2, 0, 3, 0) to listOf(0, 3, 2, 0, 3, 0)),
        "Csus2" to (listOf(-1, 3, 0, 0, 1, 3) to listOf(0, 3, 0, 0, 1, 3)),
        "A" to (listOf(-1, 0, 2, 2, 2, 0) to listOf(0, 0, 1, 2, 3, 0)),
        "Am" to (listOf(-1, 0, 2, 2, 1, 0) to listOf(0, 0, 2, 3, 1, 0)),
        "A7" to (listOf(-1, 0, 2, 0, 2, 0) to listOf(0, 0, 2, 0, 3, 0)),
        "Am7" to (listOf(-1, 0, 2, 0, 1, 0) to listOf(0, 0, 2, 0, 1, 0)),
        "Amaj7" to (listOf(-1, 0, 2, 1, 2, 0) to listOf(0, 0, 2, 1, 3, 0)),
        "Asus2" to (listOf(-1, 0, 2, 2, 0, 0) to listOf(0, 0, 1, 2, 0, 0)),
        "Asus4" to (listOf(-1, 0, 2, 2, 3, 0) to listOf(0, 0, 1, 2, 4, 0)),
        "A6" to (listOf(-1, 0, 2, 2, 2, 2) to listOf(0, 0, 1, 2, 3, 4)),
        "Aadd9" to (listOf(-1, 0, 2, 4, 0, 0) to listOf(0, 0, 1, 2, 0, 0)),
        "E" to (listOf(0, 2, 2, 1, 0, 0) to listOf(0, 2, 3, 1, 0, 0)),
        "Em" to (listOf(0, 2, 2, 0, 0, 0) to listOf(0, 2, 3, 0, 0, 0)),
        "E7" to (listOf(0, 2, 0, 1, 0, 0) to listOf(0, 2, 3, 1, 0, 0)),
        "Em7" to (listOf(0, 2, 0, 0, 0, 0) to listOf(0, 2, 3, 0, 0, 0)),
        "Emaj7" to (listOf(0, 2, 1, 1, 0, 0) to listOf(0, 3, 1, 2, 0, 0)),
        "Esus4" to (listOf(0, 2, 2, 2, 0, 0) to listOf(0, 2, 3, 4, 0, 0)),
        "E6" to (listOf(0, 2, 2, 1, 2, 0) to listOf(0, 2, 3, 1, 4, 0)),
        "Eadd9" to (listOf(0, 2, 2, 1, 0, 2) to listOf(0, 2, 3, 1, 0, 3)),
        "D" to (listOf(-1, -1, 0, 2, 3, 2) to listOf(0, 0, 0, 1, 3, 2)),
        "Dm" to (listOf(-1, -1, 0, 2, 3, 1) to listOf(0, 0, 0, 2, 3, 1)),
        "D7" to (listOf(-1, -1, 0, 2, 1, 2) to listOf(0, 0, 0, 2, 1, 3)),
        "Dm7" to (listOf(-1, -1, 0, 2, 1, 1) to listOf(0, 0, 0, 2, 1, 1)),
        "Dmaj7" to (listOf(-1, -1, 0, 2, 2, 2) to listOf(0, 0, 0, 1, 1, 1)),
        "Dsus2" to (listOf(-1, -1, 0, 2, 3, 0) to listOf(0, 0, 0, 1, 3, 0)),
        "Dsus4" to (listOf(-1, -1, 0, 2, 3, 3) to listOf(0, 0, 0, 1, 3, 4)),
        "D6" to (listOf(-1, -1, 0, 2, 0, 2) to listOf(0, 0, 0, 1, 0, 3)),
        "G" to (listOf(3, 2, 0, 0, 0, 3) to listOf(2, 1, 0, 0, 0, 3)),
        "G7" to (listOf(3, 2, 0, 0, 0, 1) to listOf(3, 2, 0, 0, 0, 1)),
        "Gmaj7" to (listOf(3, 2, 0, 0, 0, 2) to listOf(3, 2, 0, 0, 0, 2)),
        "Gsus4" to (listOf(3, 3, 0, 0, 1, 3) to listOf(2, 3, 0, 0, 1, 4)),
        "G6" to (listOf(3, 2, 0, 0, 0, 0) to listOf(3, 2, 0, 0, 0, 0)),
        "Gadd9" to (listOf(3, -1, 0, 2, 0, 3) to listOf(2, 0, 0, 1, 0, 3)),
        "B7" to (listOf(-1, 2, 1, 2, 0, 2) to listOf(0, 2, 1, 3, 0, 4)),
        "Bm7" to (listOf(-1, 2, 0, 2, 0, 2) to listOf(0, 2, 0, 3, 0, 4)),
        "Bbdim" to (listOf(-1, 1, 2, 3, 2, 0) to listOf(0, 1, 2, 4, 3, 0))
    )
}
