package com.ugviewer.chord

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.ugviewer.util.PdfGenerator
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Builds a print-ready PDF of guitar chord diagrams, four diagrams per row with
 * the chord name centred above each diagram.
 */
object ChordShapePdfGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val COLUMNS = 4
    private const val NAME_SIZE = 32f
    private const val NAME_BASELINE = 28f
    private const val NAME_BLOCK = 52f
    private const val DIAGRAM_SCALE = 1.6f
    private const val ROW_GAP = 6f
    private const val HEADER_BLOCK = 34f
    private const val FOOTER_BLOCK = 26f
    private const val PAGE_BOTTOM = PAGE_HEIGHT - MARGIN

    private const val HEADER_FONT_SIZE = 8f

    private val HEADER_FONT = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val NAME_FONT = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val PLAIN_FONT = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    private val usableWidth = PAGE_WIDTH - 2 * MARGIN
    private val cellWidth = usableWidth / COLUMNS
    private val cellHeight = NAME_BLOCK + ChordDiagramRenderer.BOX_HEIGHT * DIAGRAM_SCALE + ROW_GAP
    private val contentTop = MARGIN + HEADER_BLOCK
    private val rowsPerPage =
        ((PAGE_BOTTOM - FOOTER_BLOCK) - contentTop).toInt() / cellHeight.toInt()

    private fun makePaint(color: Int, size: Float, bold: Boolean) = Paint().apply {
        this.color = color
        textSize = size
        typeface = if (bold) NAME_FONT else PLAIN_FONT
        isAntiAlias = true
    }

    /**
     * Where one shape sits on the page, worked out without drawing anything.
     *
     * The point of computing this separately is that the drawing and the tap
     * targets read the same numbers. A hit map worked out afterwards, from a
     * second copy of the grid arithmetic, would drift the first time either side
     * was changed - and a tap that finds the wrong chord is worse than one that
     * finds nothing, because it sounds wrong rather than silent.
     */
    internal data class Cell(
        val index: Int,
        val page: Int,
        val name: String,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        val centreX: Float get() = left + width / 2f
    }

    /**
     * Lays every shape out into its cell, in draw order. Pure arithmetic, so the
     * geometry can be checked without a PDF or a device.
     */
    internal fun layoutCells(shapes: List<ChordShape>): List<Cell> {
        val cellCount = rowsPerPage * COLUMNS
        return shapes.mapIndexed { index, shape ->
            val cell = index % cellCount
            val page = index / cellCount + 1
            val row = cell / COLUMNS
            val col = cell % COLUMNS
            Cell(
                index = index,
                page = page,
                name = shape.name,
                left = MARGIN + col * cellWidth,
                top = contentTop + row * cellHeight,
                width = cellWidth,
                height = cellHeight
            )
        }
    }

    /**
     * The tap targets for [shapes], one per cell, in the same order they were
     * laid out. Reuses the sheet's own hit map rather than a second kind: a tap
     * that finds a chord here and a chord there is the same question asked
     * twice, and it should be answered the same way.
     *
     * The box is the whole cell rather than just the name. The diagram under a
     * name is the same shape, and a finger aiming at a diagram should hear it
     * too.
     */
    fun hitMapFor(shapes: List<ChordShape>): PdfGenerator.ChordHitMap =
        PdfGenerator.ChordHitMap(
            layoutCells(shapes).map { cell ->
                PdfGenerator.ChordHit(
                    page = cell.page,
                    name = cell.name,
                    left = cell.left,
                    top = cell.top,
                    right = cell.right,
                    bottom = cell.bottom
                )
            }
        )

    /**
     * Lays the shapes out four per row and returns the open document. The caller
     * owns it and must [PdfDocument.close] it once written out.
     *
     * [hitMapOut], if given, is handed the tap targets for the pages just drawn.
     */
    fun buildDocument(
        shapes: List<ChordShape>,
        title: String,
        stringLabels: List<String>,
        fretsOnChord: Int = 4,
        hitMapOut: ((PdfGenerator.ChordHitMap) -> Unit)? = null
    ): PdfDocument {
        val document = PdfDocument()
        val renderer = ChordDiagramRenderer(stringLabels, fretsOnChord)
        val inkColor = 0xFF000000.toInt()

        val titlePaint = makePaint(inkColor, HEADER_FONT_SIZE * 1.4f, true)
        val metaPaint = makePaint(inkColor, HEADER_FONT_SIZE, false)
        val footerPaint = makePaint(inkColor, HEADER_FONT_SIZE, false)
        val namePaint = makePaint(inkColor, NAME_SIZE, true).apply {
            textAlign = Paint.Align.CENTER
        }

        // The one layout, read by the drawing below and by the hit map, so the
        // two can never disagree about where a chord is.
        val cells = layoutCells(shapes)
        val pageCount = ((shapes.size + rowsPerPage * COLUMNS - 1) / (rowsPerPage * COLUMNS))
            .coerceAtLeast(1)

        for (page in 1..pageCount) {
            val from = (page - 1) * rowsPerPage * COLUMNS
            val to = (from + rowsPerPage * COLUMNS).coerceAtMost(shapes.size)
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, page).create()
            val pageDoc = document.startPage(pageInfo)
            val canvas = pageDoc.canvas

            val subTitle = if (to > from) {
                "Shapes ${from + 1}-$to of ${shapes.size}"
            } else {
                "No shapes"
            }
            canvas.drawText(title, MARGIN, MARGIN + 4f, titlePaint)
            canvas.drawText(
                "$subTitle  -  ${stringLabels.joinToString(" ")}",
                MARGIN,
                MARGIN + 14f,
                metaPaint
            )

            for (i in from until to) {
                val cell = cells[i]
                val shape = shapes[i]
                val name = PdfGenerator.fitToWidth(
                    shape.name,
                    namePaint,
                    cell.width - 4f
                )
                canvas.drawText(name, cell.centreX, cell.top + NAME_BASELINE, namePaint)

                // Centre the diagram box in the cell: the box spans local
                // x = LEFT..RIGHT, so shifting the grid origin back by LEFT*scale
                // puts the box's left edge where we want it.
                val boxWidth = ChordDiagramRenderer.BOX_WIDTH * DIAGRAM_SCALE
                val boxLeft = cell.left + (cell.width - boxWidth) / 2f
                val originX = boxLeft - ChordDiagramRenderer.LEFT * DIAGRAM_SCALE
                val originY = cell.top + NAME_BLOCK
                renderer.draw(canvas, shape.position, originX, originY, DIAGRAM_SCALE)
            }

            canvas.drawText(
                "Chord shapes from chords-db (MIT)  -  page $page of $pageCount",
                MARGIN,
                PAGE_BOTTOM,
                footerPaint
            )
            document.finishPage(pageDoc)
        }

        hitMapOut?.invoke(hitMapFor(shapes))
        return document
    }

    fun generateChordPdf(
        context: Context,
        shapes: List<ChordShape>,
        title: String,
        stringLabels: List<String>,
        fretsOnChord: Int = 4
    ): File {
        val document = buildDocument(shapes, title, stringLabels, fretsOnChord)
        return try {
            PdfGenerator.saveToDownloads(
                context,
                document,
                PdfGenerator.sanitizeFileName("Chords - $title.pdf")
            )
        } finally {
            document.close()
        }
    }

    fun generateChordPdfToBytes(
        shapes: List<ChordShape>,
        title: String,
        stringLabels: List<String>,
        fretsOnChord: Int = 4,
        hitMapOut: ((PdfGenerator.ChordHitMap) -> Unit)? = null
    ): ByteArray {
        val document = buildDocument(shapes, title, stringLabels, fretsOnChord, hitMapOut)
        return try {
            ByteArrayOutputStream().use { out ->
                document.writeTo(out)
                out.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    /** Exposed for tests/diagnostics. */
    val shapesPerPage: Int get() = rowsPerPage * COLUMNS
}
