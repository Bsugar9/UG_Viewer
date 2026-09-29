package com.ugviewer.chord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the wording the voice search accepts. "a" is the awkward one: it is
 * both an article and the note A, so these cases pin down which reading wins.
 */
class ChordVoiceQueryTest {

    @Test
    fun `the headline phrase resolves to a root chord`() {
        assertEquals("C", ChordVoiceQuery.parse("Show me a C chord"))
        assertEquals("C", ChordVoiceQuery.parse("show me a c chord"))
        assertEquals("C", ChordVoiceQuery.parse("C chord"))
        assertEquals("A", ChordVoiceQuery.parse("play an A chord"))
    }

    @Test
    fun `spoken sharps and flats map onto the roots the database carries`() {
        assertEquals("F#", ChordVoiceQuery.parse("show me an F sharp chord"))
        assertEquals("C#", ChordVoiceQuery.parse("c sharp"))
        assertEquals("Eb", ChordVoiceQuery.parse("E flat major"))
        assertEquals("Ab", ChordVoiceQuery.parse("show me an A flat chord"))
        // The article "a" must not win over the "b" that follows it.
        assertEquals("Bb", ChordVoiceQuery.parse("show me a B flat chord"))
    }

    @Test
    fun `roots the database does not carry resolve to nothing`() {
        // chords-db only has C, C#, D, Eb, E, F, F#, G, Ab, A, Bb and B.
        assertNull(ChordVoiceQuery.parse("D sharp chord"))
        assertNull(ChordVoiceQuery.parse("G flat chord"))
    }

    @Test
    fun `qualities and intervals rebuild the database suffix`() {
        assertEquals("Am", ChordVoiceQuery.parse("A minor"))
        assertEquals("Am7", ChordVoiceQuery.parse("show me an A minor seven chord"))
        assertEquals("Am7", ChordVoiceQuery.parse("A. M. 7."))
        assertEquals("Cmaj7", ChordVoiceQuery.parse("C major seven"))
        assertEquals("Csus4", ChordVoiceQuery.parse("C sus four"))
        assertEquals("Csus2", ChordVoiceQuery.parse("c sus 2"))
        assertEquals("Cadd9", ChordVoiceQuery.parse("C add nine"))
        assertEquals("Cdim7", ChordVoiceQuery.parse("C diminished seven"))
        assertEquals("C6", ChordVoiceQuery.parse("C six"))
        assertEquals("Caug", ChordVoiceQuery.parse("C augmented"))
        assertEquals("F#m", ChordVoiceQuery.parse("F sharp minor"))
    }

    @Test
    fun `a bare major collapses to the root triad`() {
        assertEquals("C", ChordVoiceQuery.parse("C major chord"))
        assertEquals("Bb", ChordVoiceQuery.parse("B flat major"))
    }

    @Test
    fun `phrases with no root note resolve to nothing`() {
        assertNull(ChordVoiceQuery.parse("show me a chord"))
        assertNull(ChordVoiceQuery.parse("guitar chords"))
        assertNull(ChordVoiceQuery.parse("hello there"))
        assertNull(ChordVoiceQuery.parse(""))
        assertNull(ChordVoiceQuery.parse("   "))
    }

    @Test
    fun `the article a is not mistaken for the note A`() {
        // "a" directly before the noun, or before a later named root, is an article.
        assertEquals("C", ChordVoiceQuery.parse("show me a C chord"))
        assertEquals("Gmaj7", ChordVoiceQuery.parse("give me a G major seven"))
        // Preceded by "an" it is unambiguously the note.
        assertEquals("Am", ChordVoiceQuery.parse("an A minor chord"))
        // With no later root and no noun after it, it is the note.
        assertEquals("A", ChordVoiceQuery.parse("show me a"))
        assertEquals("Am7", ChordVoiceQuery.parse("a minor seven"))
    }

    @Test
    fun `parsed queries are accepted by the library matcher`() {
        val roots = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
        val entries = listOf(
            ChordEntry("C", "major"), ChordEntry("C", "maj7"), ChordEntry("C", "sus4"),
            ChordEntry("A", "m7"), ChordEntry("F#", "minor")
        )
        // A bare root deliberately matches the whole family; an exact name matches alone.
        // Either way the query has to be the match the library leads with.
        for (phrase in listOf(
            "show me a C chord", "C major seven", "C sus four", "a minor seven", "F sharp minor"
        )) {
            val query = ChordVoiceQuery.parse(phrase)!!
            val matches = ChordLibrary.matchEntries(query, roots, entries).map { it.displayName }
            assertEquals(
                "phrase '$phrase' produced '$query', which the library matched as $matches",
                query,
                matches.firstOrNull()
            )
        }
    }
}
