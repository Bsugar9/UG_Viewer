package com.ugviewer.chord

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * One playable voicing of a chord.
 *
 * Data comes from the chords-db project (MIT) via
 * tools/fetch_chords_db.py, which keeps the reference renderer's semantics:
 *
 *  * [frets] is ordered LOW string first (6th, E2) and HIGH string last (1st, E4).
 *  * a fret of -1 is a muted string, 0 is an open string.
 *  * a fret of 1 or more is a row index into the diagram, i.e. it is relative to
 *    [baseFret]: a value of `v` means the fret `baseFret + v - 1`. Values 1..4
 *    land in the four rows drawn on the neck.
 *  * [barres] holds fret values that are barred; the bar spans from the first to
 *    the last string carrying that value.
 */
data class ChordPosition(
    @SerializedName("frets") val frets: List<Int> = emptyList(),
    @SerializedName("baseFret") val baseFret: Int = 1,
    @SerializedName("fingers") val fingers: List<Int> = emptyList(),
    @SerializedName("barres") val barres: List<Int> = emptyList(),
    @SerializedName("capo") val capo: Boolean = false
) {
    /** Finger number for [stringIndex], or 0 when the db does not specify one. */
    fun finger(stringIndex: Int): Int = fingers.getOrElse(stringIndex) { 0 }
}

data class ChordEntry(
    @SerializedName("key") val key: String = "",
    @SerializedName("suffix") val suffix: String = "",
    @SerializedName("positions") val positions: List<ChordPosition> = emptyList()
) {
    /** "major" collapses to the bare root and "minor" becomes the usual "m". */
    val displayName: String
        get() = when (suffix) {
            "major" -> key
            "minor" -> key + "m"
            else -> key + suffix
        }
}

/** A single diagram to print: the chord name plus the voicing to draw. */
class ChordShape(val name: String, val position: ChordPosition)

private data class ChordDb(
    @SerializedName("strings") val strings: Int = 6,
    @SerializedName("fretsOnChord") val fretsOnChord: Int = 4,
    @SerializedName("tuning") val tuning: List<String> = emptyList(),
    @SerializedName("keys") val keys: List<String> = emptyList(),
    @SerializedName("chords") val chords: Map<String, List<ChordEntry>> = emptyMap()
)

/**
 * Offline guitar chord reference bundled in assets/guitar_chords.json.
 *
 * Parsed once and shared; the payload is ~185 KB so it is cheap to keep resident
 * and makes chord search work with no network round trip.
 */
class ChordLibrary private constructor(private val db: ChordDb) {

    /** The 12 root notes the database is keyed by, e.g. C, C#, D, Eb, ... */
    val rootNotes: List<String> = db.keys

    /** Open-to-high string labels, used for the axis under each diagram. */
    val stringLabels: List<String> = db.tuning

    val strings: Int = db.strings
    val fretsOnChord: Int = db.fretsOnChord

    /**
     * Resolves what the user typed into printable shapes.
     *
     * A bare root note such as "C" returns that whole family (C, Cm, C7, Cmaj7,
     * Cadd9 ...) so it can be printed as a one-page-per-row chord reference. Any
     * other input matches a single chord name exactly, ignoring case and spaces.
     */
    fun search(rawQuery: String): List<ChordShape> =
        matchEntries(rawQuery, db.keys, db.chords.values.flatten())
            .flatMap { entry -> entry.positions.map { ChordShape(entry.displayName, it) } }

    /** Distinct chord names in [shapes], keeping database order. */
    fun distinctNames(shapes: List<ChordShape>): List<String> =
        shapes.map { it.name }.distinct()

    companion object {
        private const val ASSET_NAME = "guitar_chords.json"

        @Volatile
        private var instance: ChordLibrary? = null

        fun get(context: Context): ChordLibrary =
            instance ?: synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }

        private fun load(context: Context): ChordLibrary {
            val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            return ChordLibrary(Gson().fromJson(json, ChordDb::class.java))
        }

        /**
         * Pure query matching, split out so it can be unit tested without the
         * asset.
         *
         * Families are resolved through each entry's own [ChordEntry.key] rather
         * than the key of the enclosing `chords` map: chords-db identifies sharps
         * as "Csharp"/"Fsharp" in that map while the display name is "C#"/"F#",
         * so indexing the map by the display name would miss those two roots.
         */
        internal fun matchEntries(
            rawQuery: String,
            rootNotes: List<String>,
            allEntries: List<ChordEntry>
        ): List<ChordEntry> {
            val query = rawQuery.trim().replace(Regex("[\\s_-]+"), "")
            if (query.isEmpty()) return emptyList()

            val root = rootNotes.firstOrNull { it.equals(query, ignoreCase = true) }
            return if (root != null) {
                allEntries.filter { it.key.equals(root, ignoreCase = true) }
            } else {
                allEntries.filter { it.displayName.equals(query, ignoreCase = true) }
            }
        }
    }
}
