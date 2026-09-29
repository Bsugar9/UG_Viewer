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
     * Lays the shapes out four per row and returns the open document. The caller
     * owns it and must [PdfDocument.close] it once written out.
     */
    fun buildDocument(
        shapes: List<ChordShape>,
        title: String,
        stringLabels: List<String>,
        fretsOnChord: Int = 4
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

        val cellCount = rowsPerPage * COLUMNS
        val pageCount = ((shapes.size + cellCount - 1) / cellCount).coerceAtLeast(1)

        for (page in 1..pageCount) {
            val from = (page - 1) * cellCount
            val to = (from + cellCount).coerceAtMost(shapes.size)
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
                val cell = i - from
                val row = cell / COLUMNS
                val col = cell % COLUMNS
                val rowTop = contentTop + row * cellHeight
                val cellLeft = MARGIN + col * cellWidth
                val centreX = cellLeft + cellWidth / 2f

                val shape = shapes[i]
                val name = PdfGenerator.fitToWidth(
                    shape.name,
                    namePaint,
                    cellWidth - 4f
                )
                canvas.drawText(name, centreX, rowTop + NAME_BASELINE, namePaint)

                // Centre the diagram box in the cell: the box spans local
                // x = LEFT..RIGHT, so shifting the grid origin back by LEFT*scale
                // puts the box's left edge where we want it.
                val boxWidth = ChordDiagramRenderer.BOX_WIDTH * DIAGRAM_SCALE
                val boxLeft = cellLeft + (cellWidth - boxWidth) / 2f
                val originX = boxLeft - ChordDiagramRenderer.LEFT * DIAGRAM_SCALE
                val originY = rowTop + NAME_BLOCK
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
        fretsOnChord: Int = 4
    ): ByteArray {
        val document = buildDocument(shapes, title, stringLabels, fretsOnChord)
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
