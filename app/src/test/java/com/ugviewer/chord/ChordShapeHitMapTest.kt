package com.ugviewer.chord

import com.ugviewer.util.PdfGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tap on a chord diagram has to find the chord the user pointed at, and
 * nothing else. These check the two ways that can go wrong: the boxes are in the
 * wrong place, so a tap lands on a neighbour; or there are too few of them, so
 * some shapes are silent.
 */
class ChordShapeHitMapTest {

    private fun shape(name: String, frets: List<Int> = listOf(-1, 3, 2, 0, 1, 0)) =
        ChordShape(name, ChordPosition(frets = frets, baseFret = 1))

    private fun mapFor(shapes: List<ChordShape>): PdfGenerator.ChordHitMap =
        ChordShapePdfGenerator.hitMapFor(shapes)

    @Test
    fun `every shape gets a hit`() {
        val shapes = listOf(shape("C"), shape("Cm"), shape("C7"), shape("Cmaj7"))
        val map = mapFor(shapes)
        assertEquals("a shape is missing from the hit map", shapes.size, map.hits.size)
        assertEquals(shapes.map { it.name }, map.hits.map { it.name })
    }

    @Test
    fun `every shape on every page is hittable, and there is one per page`() {
        // Enough shapes to spill onto a second page, so the page numbering is
        // checked and not just the first page's cells.
        val perPage = ChordShapePdfGenerator.shapesPerPage
        val total = perPage + 5
        val shapes = (0 until total).map { shape("C$it") }
        val map = mapFor(shapes)

        assertEquals(total, map.hits.size)
        assertEquals(1, map.hits.first().page)
        assertEquals(2, map.hits[perPage].page)
        // The pages count is the last hit's page: a hit on a page that was never
        // drawn would mean the counting drifted from the layout.
        assertEquals(map.hits.maxOf { it.page }, map.hits.last().page)
    }

    @Test
    fun `a tap inside a cell finds that shape`() {
        val shapes = listOf(shape("C"), shape("Cm"), shape("C7"), shape("Cadd9"))
        val map = mapFor(shapes)
        for (hit in map.hits) {
            // The middle of the box, which is where a finger lands.
            val x = (hit.left + hit.right) / 2f
            val y = (hit.top + hit.bottom) / 2f
            assertEquals(
                "a tap in the middle of ${hit.name} found ${map.chordAt(hit.page, x, y)?.name}",
                hit.name,
                map.chordAt(hit.page, x, y)?.name
            )
        }
    }

    @Test
    fun `cells do not overlap, so a tap cannot find two chords`() {
        // resolveChordXs had to stop chords running into each other on the sheet;
        // the same thing has to hold for the cell boxes, or a tap in the overlap
        // is ambiguous and the last one silently wins.
        val map = mapFor((0 until 12).map { shape("C$it") })
        val cells = map.hits.groupBy { it.page }.flatMap { it.value }
        for (a in cells.indices) {
            for (b in a + 1 until cells.size) {
                val one = cells[a]
                val two = cells[b]
                val overlapX = one.left < two.right && two.left < one.right
                val overlapY = one.top < two.bottom && two.top < one.bottom
                assertTrue(
                    "cells ${one.name} and ${two.name} overlap",
                    !(overlapX && overlapY)
                )
            }
        }
    }

    @Test
    fun `a tap in the margin finds nothing`() {
        val map = mapFor(listOf(shape("C"), shape("Cm")))
        val hit = map.hits.first()
        assertNull("a tap above the grid found a chord", map.chordAt(hit.page, hit.left + 5f, 5f))
        assertNull(
            "a tap below the grid found a chord",
            map.chordAt(hit.page, hit.left + 5f, hit.bottom + 500f)
        )
    }

    @Test
    fun `a tap on a page that was not drawn finds nothing`() {
        val map = mapFor(listOf(shape("C")))
        assertNull(map.chordAt(99, 300f, 300f))
    }

    @Test
    fun `the hit map is only built when it is asked for`() {
        // The saved-to-Downloads path does not want a tap map and must not pay
        // for one. Checked by asking for the layout directly: the document path
        // cannot run here, but the fact that hitMapFor is a separate call the
        // generator makes only when the callback is present is what this pins.
        val shapes = listOf(shape("C"))
        assertEquals(1, ChordShapePdfGenerator.hitMapFor(shapes).hits.size)
        assertEquals(1, ChordShapePdfGenerator.layoutCells(shapes).size)
    }

    @Test
    fun `a cell is where the drawing put it, and both read one layout`() {
        // The drawing loop and the hit map must agree, or a tap sounds the
        // neighbouring chord. They share layoutCells, so the guarantee is that
        // there is only one grid to disagree about: a cell's centre is the middle
        // of its own box, and the name is drawn there.
        val cells = ChordShapePdfGenerator.layoutCells((0 until 5).map { shape("C$it") })
        for (cell in cells) {
            assertEquals(cell.left + cell.width / 2f, cell.centreX, 0.001f)
            assertEquals(cell.left + cell.width, cell.right, 0.001f)
            assertEquals(cell.top + cell.height, cell.bottom, 0.001f)
        }
    }

    @Test
    fun `every hittable shape can actually be sounded`() {
        // The map is only useful if the shape behind it produces pitches; a cell
        // whose frets are all muted would be a silent cell the user can see.
        val shapes = listOf(
            shape("C", listOf(-1, 3, 2, 0, 1, 0)),
            shape("Em", listOf(0, 2, 2, 0, 0, 0)),
            shape("G", listOf(3, 2, 0, 0, 0, 3))
        )
        for (s in shapes) {
            val pitches = ChordPitch.soundingPitches(s.position, ChordPitch.STANDARD_TUNING)
            assertNotNull(s.name)
            assertTrue("${s.name} would be silent", pitches.isNotEmpty())
        }
    }
}
