package com.ugviewer.chord

/**
 * Turns a spoken phrase into a chord query the library understands, so
 * "Show me a C chord" or "an A minor seven" becomes "C" / "Am7".
 *
 * Pure Kotlin on purpose: the awkward part of voice search is the wording, not
 * the audio, so it can be exercised without a microphone.
 */
object ChordVoiceQuery {

    /** The 12 roots chords-db actually carries. */
    private val KNOWN_ROOTS = setOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    private val numberWords = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13"
    )

    /** Spoken forms of the two accidentals. */
    private val accidentalWords = setOf("sharp", "flat")

    private val noteLetters = setOf("a", "b", "c", "d", "e", "f", "g")

    /** Quality words mapped to the suffix chords-db stores. */
    private val qualityWords = mapOf(
        "m" to "m", "minor" to "m", "min" to "m",
        "maj" to "maj", "major" to "maj",
        "dim" to "dim", "diminished" to "dim",
        "aug" to "aug", "augmented" to "aug"
    )

    /** "sus 4" / "add 9": a modifier that binds to the number after it. */
    private val modifierWords = setOf("sus", "add")

    /**
     * Parses [phrase] into a chord query, or null when no known root is named.
     *
     * The result is either a bare root ("C", meaning that whole family) or an
     * exact chord name ("Am7"), which is what [ChordLibrary.search] expects.
     */
    fun parse(phrase: String): String? {
        val tokens = tokenize(phrase)
        if (tokens.isEmpty()) return null

        val rootIndex = findRootIndex(tokens) ?: return null
        val root = readRoot(tokens, rootIndex) ?: return null
        val rootLength = if (tokens.getOrNull(rootIndex + 1) in accidentalWords) 2 else 1
        return root + readSuffix(tokens, rootIndex + rootLength)
    }

    private fun tokenize(phrase: String): List<String> =
        phrase.lowercase()
            .map { if (it.isLetterOrDigit()) it.toString() else " " }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { numberWords[it] ?: it }

    /**
     * Locates the root note, telling articles apart from the note A.
     *
     * "a" is both, so it only counts as an article when "an" precedes it, when
     * the noun "chord" follows it, or when a later letter names the real root:
     * "play a chord" is no chord, "play an A chord" is A, and "show me a C
     * chord" is C.
     */
    private fun findRootIndex(tokens: List<String>): Int? {
        val letterRoots = tokens.withIndex()
            .filter { (_, token) -> token in noteLetters }
            .map { it.index }

        val usable = letterRoots.filter { index ->
            if (tokens[index] != "a") {
                true
            } else {
                val previous = tokens.getOrNull(index - 1)
                val next = tokens.getOrNull(index + 1)
                val articleBeforeNoun = next == "chord" || next == "chords"
                val laterRootExists = letterRoots.any { it > index }
                previous == "an" || (!articleBeforeNoun && !laterRootExists)
            }
        }
        return usable.firstOrNull()
    }

    /** Consumes "f sharp" style roots and returns chords-db's spelling. */
    private fun readRoot(tokens: List<String>, index: Int): String? {
        val letter = tokens.getOrNull(index)?.uppercase() ?: return null
        val accidental = tokens.getOrNull(index + 1)
            ?.takeIf { it in accidentalWords }
            ?.let { if (it == "sharp") "#" else "b" }
            ?: ""
        val spelled = letter + accidental
        return spelled.takeIf { it in KNOWN_ROOTS }
    }

    /**
     * Rebuilds the suffix chords-db uses from the words after the root:
     * "minor 7" -> m7, "sus 4" -> sus4, "add 9" -> add9, "major 7" -> maj7.
     *
     * A bare "major" is dropped because the database already stores the major
     * triad as the bare root.
     */
    private fun readSuffix(tokens: List<String>, from: Int): String {
        var modifier: String? = null
        var quality: String? = null
        var digits: String? = null

        var i = from
        while (i < tokens.size) {
            when (val token = tokens[i]) {
                in modifierWords -> {
                    modifier = token
                    tokens.getOrNull(i + 1)
                        ?.takeIf { it.length <= 2 && it.all(Char::isDigit) }
                        ?.let { digits = it; i++ }
                }
                in qualityWords -> quality = qualityWords[token]
                else -> if (token.length <= 2 && token.all(Char::isDigit)) digits = token
            }
            i++
        }

        return when {
            modifier != null -> modifier + (digits ?: "")
            // "major" with no interval is the root triad, spelled bare.
            quality == "maj" && digits == null -> ""
            quality != null -> quality + (digits ?: "")
            else -> digits.orEmpty()
        }
    }
}
