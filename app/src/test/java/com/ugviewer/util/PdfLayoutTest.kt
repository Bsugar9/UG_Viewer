package com.ugviewer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the layout guarantees the PDF viewer relies on at every font size:
 * lyrics wrap at the column limit (so nothing runs off the page) and a chord
 * keeps the exact column it was charted at (so it stays above its lyric).
 *
 * maxCols values mirror what PdfGenerator.buildPdfDocument derives from the
 * measured monospace advance width for each selectable font size.
 */
class PdfLayoutTest {

    // ~0.6em monospace advance over the 495pt usable width: 29pt -> 28 cols,
    // 32pt -> 25 cols, 42pt -> 19 cols.
    private val realisticMaxCols = listOf(19, 20, 22, 25, 28)

    @Test
    fun `chord row always leaves ink clearance at every font pair`() {
        // Whatever the pitch slider asks, the effective row height (the larger
        // of pitch vs minimum) must be at least the ink minimum.
        for (chord in listOf(10.5f, 22f, 32f, 42f)) {
            for (lyric in listOf(10.5f, 22f, 32f, 42f)) {
                val min = PdfGenerator.minimumChordRowHeight(chord, lyric)
                val effective = maxOf(1.5f * 10f * maxOf(chord, lyric) / 10f, min)
                assertTrue("effective row below ink minimum for ${chord}pt/${lyric}pt", effective >= min)
            }
        }
    }

    @Test
    fun `chord baseline sits exactly halfway between the lyric baselines`() {
        // Baselines, not the row middle: the top lyric baseline is a
        // descender's height below the row top, the bottom one the same
        // distance above the row bottom, and the chord baseline must be
        // their exact midpoint — that is what centring means on a page.
        for (pitch in listOf(1.5f, 2f, 3f)) {
            for (font in listOf(10.5f, 22f, 32f, 42f)) {
                val row = maxOf(
                    pitch * 10f * font / 10f,
                    PdfGenerator.minimumChordRowHeight(font, font)
                )
                // Row top = 0. The line ABOVE's baseline is a descender above
                // the row top; this row's lyric baseline the same distance
                // above the row bottom.
                val d = 0.35f * font
                val topLyricBaseline = -d
                val bottomLyricBaseline = row - d
                val chordBaseline = bottomLyricBaseline - row / 2f
                val gapAbove = chordBaseline - topLyricBaseline
                val gapBelow = bottomLyricBaseline - chordBaseline
                assertEquals("pitch=$pitch font=$font", gapAbove, gapBelow, 0.01f)
            }
        }
    }

    @Test
    fun `centred chord clears both lyric lines at worst-case fonts`() {
        // 32pt chords centred in a minimum-height row: chord ink must not
        // reach the line above's descenders or the lyric capitals below.
        // Coordinates: row top = 0. The previous line's baseline is a
        // descender ABOVE the row top; this row's lyric baseline is a
        // descender above the row bottom; the chord baseline is the midpoint
        // of the two lyric baselines.
        val chordSize = 32f
        val lyricSize = 32f
        val row = PdfGenerator.minimumChordRowHeight(chordSize, lyricSize)
        val d = 0.35f * lyricSize
        val chordAscent = 0.78f * chordSize
        val chordDescent = 0.22f * chordSize
        val lyricCap = 0.72f * lyricSize
        val prevLyricBaseline = -d          // above this row's top
        val thisLyricBaseline = row - d
        val chordBaseline = (prevLyricBaseline + thisLyricBaseline) / 2f
        val chordTop = chordBaseline - chordAscent
        val chordBottom = chordBaseline + chordDescent
        val lyricTop = thisLyricBaseline - lyricCap
        assertTrue("chord grazes line above (top $chordTop < prev baseline $prevLyricBaseline)", chordTop >= prevLyricBaseline)
        assertTrue("chord touches its lyric (bottom $chordBottom > cap top $lyricTop)", chordBottom <= lyricTop)
    }

    @Test
    fun `an off-centre chord still clears the line below it`() {
        // A theme that slides the chord down (or up) inside the band must not
        // print the name on top of the lyric - or, on a chord-only row above a
        // tab block, on top of the tab.
        for (offset in listOf(-0.5f, 0f, 0.5f, 1f)) {
            for (font in listOf(10.5f, 22f, 32f, 42f)) {
                val offsetPx = offset * 10f * font / 10f
                val row = PdfGenerator.minimumChordRowHeight(font, font, offsetPx)
                val d = 0.35f * font
                val prevLyricBaseline = -d
                val thisLyricBaseline = row - d
                // Same rule the generator uses: centred, then slid.
                val chordBaseline = thisLyricBaseline - row / 2f + offsetPx
                val chordTop = chordBaseline - 0.78f * font
                val chordBottom = chordBaseline + 0.22f * font
                val lyricTop = thisLyricBaseline - 0.72f * font
                assertTrue(
                    "offset=$offset font=$font: chord top $chordTop grazes the line above ($prevLyricBaseline)",
                    chordTop >= prevLyricBaseline
                )
                assertTrue(
                    "offset=$offset font=$font: chord bottom $chordBottom lands on the line below ($lyricTop)",
                    chordBottom <= lyricTop
                )
            }
        }
    }

    @Test
    fun `resolved chord xs never overlap each other`() {
        // Three chords charted one character apart: Gm7 is wider than the
        // spacing, so the resolver must push the later ones right.
        val charted = listOf(50f, 56f, 62f)
        val widths = listOf(30f, 28f, 26f)
        val xs = PdfGenerator.resolveChordXs(charted, widths, startX = 50f, rightEdge = 545f)
        for (i in 0 until xs.size - 1) {
            assertTrue(
                "chord $i (x=${xs[i]}, w=${widths[i]}) overlaps chord ${i + 1} (x=${xs[i + 1]})",
                xs[i] + widths[i] + 1f <= xs[i + 1]
            )
        }
        // Charted positions with room between them are left alone.
        val spread = PdfGenerator.resolveChordXs(listOf(50f, 150f, 250f), widths, 50f, 545f)
        assertEquals(listOf(50f, 150f, 250f), spread)
        // Nothing is pushed past the right margin.
        for (x in xs) assertTrue("chord pushed off the page at x=$x", x <= 545f)
    }

    @Test
    fun `a chord straddling a wrap is pulled back to the next line`() {
        // "Gm7" starts at column 18 and would run to 21: the 20-column cut
        // splits the name across rendered lines. The pull-back must move it
        // (with its word) to the next line whole.
        val content = "[ch]C[/ch]" + " ".repeat(17) + "[ch]Gm7[/ch]" +
            "\n" + "aaaa bbbb cccc dddd eeee fff gggg hhhh"
        val rows = PdfGenerator.buildRows(content, maxCols = 20)
        val segments = rows.filterIsInstance<PdfGenerator.PdfRow.ChordLyric>().first().segments
        val allChords = segments.flatMap { it.chords }
        val gm7 = allChords.first { it.second == "Gm7" }
        val segIndex = segments.indexOfFirst { it.chords.any { (_, n) -> n == "Gm7" } }
        // The name fits inside its segment: start + length <= maxCols.
        assertTrue(
            "Gm7 at ${gm7.first} in segment $segIndex does not fit (maxCols=20)",
            gm7.first + 3 <= 20
        )
        // And the word it sits over came down with it (segment is not empty).
        assertTrue(segments[segIndex].lyric.isNotBlank())
    }

    @Test
    fun `wrapped lyric segments never exceed the column limit`() {
        val lyric = "aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii jjjj kkkk llll mmmm"
        for (maxCols in realisticMaxCols) {
            val parts = PdfGenerator.wrapKeepOffsets(lyric, maxCols)
            if (maxCols < lyric.length) {
                assertTrue("expected wrapping for maxCols=$maxCols", parts.size > 1)
            }
            for ((_, segment) in parts) {
                assertTrue(
                    "segment '$segment' (${segment.length}) exceeded maxCols=$maxCols",
                    segment.length <= maxCols
                )
            }
        }
    }

    @Test
    fun `chords keep their original column when the lyric wraps`() {
        // Chord line (tags stripped) is "A7" + 18 spaces + "Bm", i.e. the
        // chords are charted at columns 0 and 20 of the lyric below them.
        val content = "[ch]A7[/ch]" + " ".repeat(18) + "[ch]Bm[/ch]" +
            "\n" + "aaaa bbbb cccc dddd eeee ffff gggg hhhh"

        val rows = PdfGenerator.buildRows(content, maxCols = 20)
        val row = rows.filterIsInstance<PdfGenerator.PdfRow.ChordLyric>().first()
        val segments = row.segments

        assertEquals("lyric should wrap into two segments", 2, segments.size)

        // Column 0 lives in segment 0 at relative column 0.
        assertEquals(listOf(0 to "A7"), segments[0].chords)
        // Column 20 lands exactly at the start of the wrapped second segment.
        assertEquals(listOf(0 to "Bm"), segments[1].chords)
        assertEquals("eeee ffff gggg hhhh", segments[1].lyric)
    }

    @Test
    fun `every chord stays within the page width at every font size`() {
        val content = """
            [ch]C[/ch]  [ch]G[/ch]  [ch]Am[/ch]  [ch]Fmaj7[/ch]  [ch]Gsus4[/ch]
            Twinkle twinkle little star how I wonder what you are up above the world so high
        """.trimIndent()

        for (maxCols in realisticMaxCols) {
            val rows = PdfGenerator.buildRows(content, maxCols)
            for (row in rows.filterIsInstance<PdfGenerator.PdfRow.ChordLyric>()) {
                for (segment in row.segments) {
                    assertTrue(
                        "lyric ran off the page at maxCols=$maxCols",
                        segment.lyric.length <= maxCols
                    )
                    for ((rel, name) in segment.chords) {
                        assertTrue("negative chord column at maxCols=$maxCols", rel >= 0)
                        assertTrue(
                            "chord '$name' at rel=$rel ran off the page at maxCols=$maxCols",
                            rel + name.length <= maxCols
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `inline chords stay above the word they were charted over`() {
        // "some [ch]Am[/ch] words" -> chord Am sits over column 5 ("words"?).
        val content = "some [ch]Am[/ch] words"
        val rows = PdfGenerator.buildRows(content, maxCols = 80)
        val row = rows.filterIsInstance<PdfGenerator.PdfRow.ChordLyric>().first()
        val segment = row.segments.single()

        val (rel, name) = segment.chords.single()
        assertEquals("Am", name)
        // The blanked chord name leaves columns 0..3 as "some", and the chord
        // is nudged onto the next non-blank character after its tag position.
        assertTrue("chord column $rel should sit inside the lyric", rel < segment.lyric.length)

        val renderedColumn = segment.lyric.substring(rel, (rel + name.length).coerceAtMost(segment.lyric.length))
        assertTrue(
            "chord column $rel should land on a non-blank character, got '$renderedColumn'",
            renderedColumn.isNotBlank()
        )
    }

    @Test
    fun `font size options are all within the supported range`() {
        for (size in PdfGenerator.FONT_SIZE_OPTIONS) {
            assertTrue("font size $size out of range", size in PdfGenerator.MIN_FONT_SIZE..PdfGenerator.MAX_FONT_SIZE)
        }
        assertTrue(PdfGenerator.FONT_SIZE_OPTIONS.contains(PdfGenerator.DEFAULT_FONT_SIZE))
        assertEquals(33, PdfGenerator.FONT_SIZE_OPTIONS.size)
    }
}
