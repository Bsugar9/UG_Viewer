package com.ugviewer.chord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in how a chord's strings are turned into pitches, since a wrong answer
 * is inaudible in a way a wrong diagram is not: the arithmetic has to be right
 * before a single sample is synthesised.
 */
class ChordPitchTest {

    @Test
    fun `note names map to midi with middle C as C4`() {
        assertEquals(60, ChordPitch.midiOfNote("C4"))
        assertEquals(64, ChordPitch.midiOfNote("E4"))
        assertEquals(40, ChordPitch.midiOfNote("E2"))
        assertEquals(61, ChordPitch.midiOfNote("C#4"))
        assertEquals(70, ChordPitch.midiOfNote("Bb4"))
        // Lower case is how a tab label often reads.
        assertEquals(45, ChordPitch.midiOfNote("a2"))
    }

    @Test
    fun `text that is not a note has no pitch`() {
        assertNull(ChordPitch.midiOfNote("Standard"))
        assertNull(ChordPitch.midiOfNote("C"))
        assertNull(ChordPitch.midiOfNote(""))
    }

    @Test
    fun `a tuning label naming every string is read as that tuning`() {
        // Bare letters, no octaves: the 6th string is the only one Drop D moves.
        assertEquals(
            listOf(38, 45, 50, 55, 59, 64),
            ChordPitch.openPitchesFor("D A D G B E")
        )
        // Spelled with octaves, the label is taken at face value.
        assertEquals(
            listOf(38, 45, 50, 55, 59, 64),
            ChordPitch.openPitchesFor("D2 A2 D3 G3 B3 E4")
        )
        // The same six letters in standard order is standard tuning.
        assertEquals(
            ChordPitch.STANDARD_TUNING,
            ChordPitch.openPitchesFor("E A D G B E")
        )
        // "Drop" begins with a D that is only a letter; it must not be counted as
        // a seventh string, which would throw the whole label away.
        assertEquals(
            ChordPitch.STANDARD_TUNING,
            ChordPitch.openPitchesFor("Drop D (D A D G B E)")
        )
    }

    @Test
    fun `a tuning label with no note list falls back to standard`() {
        assertEquals(ChordPitch.STANDARD_TUNING, ChordPitch.openPitchesFor("Standard"))
        assertEquals(ChordPitch.STANDARD_TUNING, ChordPitch.openPitchesFor(""))
        // One note is not a six-string tuning.
        assertEquals(ChordPitch.STANDARD_TUNING, ChordPitch.openPitchesFor("Drop D"))
    }

    @Test
    fun `an open C major sounds the notes it is fretted to`() {
        // chords-db's C major: x32010, low string first.
        val cMajor = ChordPosition(
            frets = listOf(-1, 3, 2, 0, 1, 0),
            baseFret = 1,
            fingers = listOf(0, 3, 2, 0, 1, 0),
            barres = emptyList()
        )
        assertEquals(
            listOf(48, 52, 55, 60, 64),
            ChordPitch.soundingPitches(cMajor)
        )
    }

    @Test
    fun `muted strings sound nothing and the rest stay in strum order`() {
        // x02220, an A minor barre at the 1st fret.
        val aMinor = ChordPosition(frets = listOf(-1, 0, 2, 2, 2, 0), baseFret = 1)
        assertEquals(listOf(45, 52, 57, 61, 64), ChordPitch.soundingPitches(aMinor))
    }

    @Test
    fun `a capo moves the whole shape up without moving the fingers`() {
        val open = ChordPosition(frets = listOf(-1, 0, 2, 2, 2, 0), baseFret = 1)
        val capped = ChordPitch.soundingPitches(open, ChordPitch.STANDARD_TUNING, capoFret = 3)
        assertEquals(
            open.let { ChordPitch.soundingPitches(it) }.map { it + 3 },
            capped
        )
    }

    @Test
    fun `a shape above the nut keeps its base fret`() {
        // The A barre at the 5th fret: x57765, so baseFret 5 puts the fingers on
        // frets 7-9 and the open-looking 3s really mean the 7th.
        val aMajor = ChordPosition(frets = listOf(-1, 3, 5, 5, 5, 3), baseFret = 5)
        // A2+7, D3+9, G3+9, B3+9, E4+7
        assertEquals(listOf(52, 59, 64, 68, 71), ChordPitch.soundingPitches(aMajor))
    }

    @Test
    fun `a non-standard tuning shifts the chord to that tuning`() {
        val dropD = ChordPitch.openPitchesFor("D A D G B E")
        val cMajor = ChordPosition(frets = listOf(-1, 3, 2, 0, 1, 0), baseFret = 1)
        // Only the low string changes, because that is the only one Drop D moved.
        assertEquals(
            listOf(48, 52, 55, 60, 64),
            ChordPitch.soundingPitches(cMajor, dropD)
        )
    }
}
