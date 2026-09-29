package com.ugviewer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the shape engine's musical correctness: the E-form barre must
 * reproduce the standard shapes guitarists already know, every fretted string
 * must be a chord tone, and every curated open voicing must survive its own
 * verification instead of being silently dropped.
 */
class ChordLibraryTest {

    @Test
    fun `e-form barre reproduces the standard open-position shapes`() {
        val expected = listOf(
            "E" to listOf(0, 2, 2, 1, 0, 0),
            "F" to listOf(1, 3, 3, 2, 1, 1),
            "G" to listOf(3, 5, 5, 4, 3, 3),
            "A" to listOf(5, 7, 7, 6, 5, 5),
            "B" to listOf(7, 9, 9, 8, 7, 7),
            "C" to listOf(8, 10, 10, 9, 8, 8)
        )
        for ((name, frets) in expected) {
            val shape = ChordLibrary.getShapes(name).firstOrNull { it.shapeName == "E-form barre" }
            assertNotNull("no E-form barre for $name", shape)
            assertEquals("wrong frets for $name", frets, shape!!.frets)
        }
    }

    @Test
    fun `minor sevenths and sevenths barre correctly`() {
        val expected = listOf(
            "Fm" to listOf(1, 3, 3, 1, 1, 1),
            "Am" to listOf(5, 7, 7, 5, 5, 5),
            "Bm" to listOf(7, 9, 9, 7, 7, 7),
            "F7" to listOf(1, 3, 1, 2, 1, 1),
            "B7" to listOf(7, 9, 7, 8, 7, 7),
            "Bm7" to listOf(7, 9, 7, 7, 7, 7),
            "Dm" to listOf(10, 12, 12, 10, 10, 10)
        )
        for ((name, frets) in expected) {
            val shape = ChordLibrary.getShapes(name).firstOrNull { it.shapeName == "E-form barre" }
            assertEquals("wrong E-form barre for $name", frets, shape?.frets)
        }
    }

    /** Low E -> high E open pitch classes, C-anchored (same table as the engine). */
    private val openPc = listOf(4, 9, 2, 7, 11, 4)

    private val intervals = mapOf(
        "" to listOf(0, 4, 7), "m" to listOf(0, 3, 7), "7" to listOf(0, 4, 7, 10),
        "m7" to listOf(0, 3, 7, 10), "maj7" to listOf(0, 4, 7, 11),
        "sus2" to listOf(0, 2, 7), "sus4" to listOf(0, 5, 7), "6" to listOf(0, 4, 7, 9),
        "add9" to listOf(0, 2, 4, 7), "dim" to listOf(0, 3, 6), "aug" to listOf(0, 4, 8)
    )

    @Test
    fun `every fretted string of every barre shape is a chord tone`() {
        for (root in listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")) {
            for (quality in intervals.keys) {
                for (shapeName in listOf("E-form barre", "A-form barre")) {
                    val shape = ChordLibrary.getShapes(root + quality)
                        .firstOrNull { it.shapeName == shapeName } ?: continue
                    val rootIdx = mapOf(
                        "C" to 0, "C#" to 1, "D" to 2, "Eb" to 3, "E" to 4, "F" to 5,
                        "F#" to 6, "G" to 7, "Ab" to 8, "A" to 9, "Bb" to 10, "B" to 11
                    )[root]!!
                    shape.frets.forEachIndexed { s, f ->
                        if (f >= 0) {
                            val iv = (openPc[s] + f - rootIdx + 12) % 12
                            assertTrue(
                                "$root$quality ($shapeName) string $s fret $f is interval $iv, not a chord tone",
                                iv in intervals[quality]!!
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `every curated open shape survives verification`() {
        // If an entry is musically wrong, openShape silently drops it; assert
        // the well-known ones actually come back out.
        for (name in listOf(
            "C", "Cmaj7", "C7", "C6", "Cadd9", "Csus2",
            "A", "Am", "A7", "Am7", "Amaj7", "Asus2", "Asus4", "A6", "Aadd9",
            "E", "Em", "E7", "Em7", "Emaj7", "Esus4", "E6", "Eadd9",
            "D", "Dm", "D7", "Dm7", "Dmaj7", "Dsus2", "Dsus4", "D6",
            "G", "G7", "Gmaj7", "Gsus4", "G6", "Gadd9",
            "B7", "Bm7", "Bbdim"
        )) {
            val open = ChordLibrary.getShapes(name).firstOrNull { it.shapeName == "Open" }
            assertNotNull("curated open shape for $name was rejected by its own verification", open)
        }
    }

    @Test
    fun `curated open voicings match the standard fingerings`() {
        val expected = listOf(
            "C" to listOf(-1, 3, 2, 0, 1, 0),
            "Am" to listOf(-1, 0, 2, 2, 1, 0),
            "E" to listOf(0, 2, 2, 1, 0, 0),
            "Em" to listOf(0, 2, 2, 0, 0, 0),
            "D" to listOf(-1, -1, 0, 2, 3, 2),
            "Dm" to listOf(-1, -1, 0, 2, 3, 1),
            "G" to listOf(3, 2, 0, 0, 0, 3),
            "A" to listOf(-1, 0, 2, 2, 2, 0),
            "B7" to listOf(-1, 2, 1, 2, 0, 2),
            "Gsus4" to listOf(3, 3, 0, 0, 1, 3),
            "Csus2" to listOf(-1, 3, 0, 0, 1, 3)
        )
        for ((name, frets) in expected) {
            val open = ChordLibrary.getShapes(name).firstOrNull { it.shapeName == "Open" }
            assertEquals("wrong open shape for $name", frets, open?.frets)
        }
    }

    @Test
    fun `slash chords resolve to their plain name`() {
        val plain = ChordLibrary.getShapes("G")
        val slash = ChordLibrary.getShapes("G/B")
        assertEquals("slash bass note should be ignored", plain.map { it.frets }, slash.map { it.frets })
    }

    @Test
    fun `every root gets at least one shape for every supported type`() {
        for (root in listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")) {
            for (quality in intervals.keys - "dim") {
                val shapes = ChordLibrary.getShapes(root + quality)
                assertTrue("no shapes at all for $root$quality", shapes.isNotEmpty())
            }
        }
    }

    @Test
    fun `non-chords parse to nothing`() {
        assertTrue(ChordLibrary.getShapes("").isEmpty())
        assertTrue(ChordLibrary.getShapes("bed").isEmpty())
        assertTrue(ChordLibrary.getShapes("123").isEmpty())
        assertFalse(ChordLibrary.getShapes("C").isEmpty())
    }
}
