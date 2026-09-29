package com.ugviewer.chord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in how chords-db suffixes are presented to the reader. The database
 * stores every quality as a suffix, but the printed chart has to use the names
 * players actually recognise.
 */
class ChordEntryNameTest {

    @Test
    fun `major collapses to the bare root and minor uses the usual m`() {
        assertEquals("C", ChordEntry(key = "C", suffix = "major").displayName)
        assertEquals("A", ChordEntry(key = "A", suffix = "major").displayName)
        assertEquals("Cm", ChordEntry(key = "C", suffix = "minor").displayName)
        assertEquals("F#m", ChordEntry(key = "F#", suffix = "minor").displayName)
        assertEquals("Bbm", ChordEntry(key = "Bb", suffix = "minor").displayName)
    }

    @Test
    fun `every other suffix is appended to the root verbatim`() {
        assertEquals("C7", ChordEntry(key = "C", suffix = "7").displayName)
        assertEquals("Cmaj7", ChordEntry(key = "C", suffix = "maj7").displayName)
        assertEquals("Cadd9", ChordEntry(key = "C", suffix = "add9").displayName)
        assertEquals("Csus4", ChordEntry(key = "C", suffix = "sus4").displayName)
        assertEquals("Cdim7", ChordEntry(key = "C", suffix = "dim7").displayName)
        assertEquals("Eb", ChordEntry(key = "E", suffix = "b").displayName)
        assertEquals("C#", ChordEntry(key = "C", suffix = "#").displayName)
    }

    @Test
    fun `frets are read low string first and fingers align with their string`() {
        // As stored by chords-db: frets[0] is the 6th string, frets[5] the 1st.
        val position = ChordPosition(
            frets = listOf(-1, 3, 2, 0, 1, 0),
            baseFret = 5,
            fingers = listOf(0, 3, 2, 0, 1, 0),
            barres = listOf(3)
        )
        assertEquals(6, position.frets.size)
        assertEquals(-1, position.frets[0])
        assertEquals(0, position.frets[5])
        assertEquals(5, position.baseFret)
        assertEquals(3, position.finger(1))
        assertEquals(1, position.finger(4))
        // Out-of-range lookups must not throw: the renderer asks for every string.
        assertEquals(0, position.finger(99))
    }

    @Test
    fun `an unknown finger defaults to zero so no number is printed`() {
        val position = ChordPosition(frets = listOf(0, 0, 0, 0, 0, 0), baseFret = 1)
        assertEquals(0, position.finger(0))
        assertEquals(emptyList<Int>(), position.barres)
        assertEquals(false, position.capo)
    }

    /**
     * chords-db identifies sharp roots as "Csharp"/"Fsharp" in the enclosing
     * chords map while the display name is "C#"/"F#". Matching a family must
     * therefore go through the entry's own key, or those roots come back empty.
     */
    @Test
    fun `a bare root returns its whole family including sharp roots`() {
        val roots = listOf("C", "C#", "F#", "Eb", "A")
        val entries = listOf(
            entry("C", "major"), entry("C", "minor"), entry("C", "7"),
            entry("C#", "major"), entry("C#", "minor"), entry("C#", "7"),
            entry("F#", "minor"),
            entry("Eb", "major"),
            entry("A", "minor")
        )

        for (root in roots) {
            val family = ChordLibrary.matchEntries(root, roots, entries)
            assertTrue("root $root returned nothing", family.isNotEmpty())
            assertTrue(
                "root $root leaked a foreign family: ${family.map { it.displayName }}",
                family.all { it.key.equals(root, ignoreCase = true) }
            )
        }
        assertEquals(listOf("C", "Cm", "C7"), ChordLibrary.matchEntries("C", roots, entries).map { it.displayName })
        assertEquals(listOf("C#", "C#m", "C#7"), ChordLibrary.matchEntries("C#", roots, entries).map { it.displayName })
        assertEquals(listOf("Eb"), ChordLibrary.matchEntries("Eb", roots, entries).map { it.displayName })
    }

    @Test
    fun `an exact chord name matches only that chord`() {
        val roots = listOf("C", "A", "F#")
        val entries = listOf(
            entry("C", "major"), entry("C", "add9"),
            entry("A", "minor"), entry("A", "m7"),
            entry("F#", "major")
        )
        assertEquals(listOf("Am7"), ChordLibrary.matchEntries("Am7", roots, entries).map { it.displayName })
        assertEquals(listOf("Cadd9"), ChordLibrary.matchEntries("Cadd9", roots, entries).map { it.displayName })
    }

    @Test
    fun `queries are case insensitive and ignore spaces underscores and hyphens`() {
        val roots = listOf("C", "A", "F#")
        val entries = listOf(entry("F#", "major"), entry("A", "m7"), entry("C", "add9"))
        for (query in listOf("am7", "AM7", " a m 7 ", "a_m7", "a-m7", "  A  M  7  ")) {
            assertEquals(
                "query '$query' did not resolve",
                listOf("Am7"),
                ChordLibrary.matchEntries(query, roots, entries).map { it.displayName }
            )
        }
        assertEquals(listOf("F#"), ChordLibrary.matchEntries("f#", roots, entries).map { it.displayName })
    }

    @Test
    fun `blank and unknown queries return nothing rather than everything`() {
        val roots = listOf("C", "A")
        val entries = listOf(entry("C", "major"), entry("A", "minor"))
        assertEquals(emptyList<ChordEntry>(), ChordLibrary.matchEntries("", roots, entries))
        assertEquals(emptyList<ChordEntry>(), ChordLibrary.matchEntries("   ", roots, entries))
        assertEquals(emptyList<ChordEntry>(), ChordLibrary.matchEntries("H", roots, entries))
        assertEquals(emptyList<ChordEntry>(), ChordLibrary.matchEntries("Cmaj13", roots, entries))
    }

    private fun entry(key: String, suffix: String) = ChordEntry(key = key, suffix = suffix)
}
