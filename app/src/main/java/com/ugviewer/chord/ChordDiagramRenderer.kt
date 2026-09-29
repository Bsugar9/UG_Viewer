package com.ugviewer.chord

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max

/**
 * Draws one guitar chord diagram onto a [Canvas].
 *
 * The geometry mirrors the reference renderer of the chords-db project so the
 * output matches the diagrams the bundled data is drawn for: six strings spaced
 * 10 units apart, four fret rows spaced 12 units apart, finger dots on the
 * midpoint of their row, a thick nut only when the shape starts at fret 1.
 */
class ChordDiagramRenderer(
    private val stringLabels: List<String>,
    private val fretsOnChord: Int = 4
) {

    companion object {
        private const val GRID_COLOR = 0xFF444444.toInt()
        private const val FINGER_ON_DOT = 0xFFFFFFFF.toInt()

        // Local units. LEFT/TOP reserve room for the "8fr" position label and for
        // the open/muted markers that sit above the nut.
        const val STRING_SPACING = 10f
        const val FRET_SPACING = 12f
        const val LEFT = -15f
        const val RIGHT = 56f
        const val TOP = -15f
        const val BOTTOM = 58f
        const val BOX_WIDTH = RIGHT - LEFT
        const val BOX_HEIGHT = BOTTOM - TOP

        private val LABEL = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private val LABEL_BOLD = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

        /** Baseline of the centre of finger row [fretValue] (1-based, >= 1). */
        fun dotY(fretValue: Int): Float = 6.5f + FRET_SPACING * (fretValue - 1)
    }

    private val gridPaint = Paint().apply {
        color = GRID_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 0.9f
        isAntiAlias = true
    }
    private val nutPaint = Paint().apply {
        color = GRID_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        color = GRID_COLOR
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val openPaint = Paint().apply {
        color = GRID_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        isAntiAlias = true
    }
    private val fingerTextPaint = Paint().apply {
        color = FINGER_ON_DOT
        textAlign = Paint.Align.CENTER
        typeface = LABEL_BOLD
        isAntiAlias = true
    }
    private val stringLabelPaint = Paint().apply {
        color = GRID_COLOR
        textAlign = Paint.Align.CENTER
        typeface = LABEL
        isAntiAlias = true
    }
    private val positionLabelPaint = Paint().apply {
        color = GRID_COLOR
        textAlign = Paint.Align.LEFT
        typeface = LABEL
        isAntiAlias = true
    }
    private val mutedTextPaint = Paint().apply {
        color = GRID_COLOR
        textAlign = Paint.Align.CENTER
        typeface = LABEL
        isAntiAlias = true
    }

    /**
     * Draws [position] with its grid origin (left end of the nut line) at
     * ([originX], [originY]) in canvas coordinates, scaled by [scale].
     */
    fun draw(
        canvas: Canvas,
        position: ChordPosition,
        originX: Float,
        originY: Float,
        scale: Float
    ) {
        val frets = position.frets
        if (frets.isEmpty()) return
        val lastString = frets.size - 1
        val gridWidth = lastString * STRING_SPACING

        fun x(local: Float) = originX + local * scale
        fun y(local: Float) = originY + local * scale

        // Neck: FRET_ROWS + 1 fret lines and one line per string.
        for (row in 0..fretsOnChord) {
            val ly = y(row * FRET_SPACING)
            canvas.drawLine(x(0f), ly, x(gridWidth), ly, gridPaint)
        }
        for (i in 0..lastString) {
            val lx = x(i * STRING_SPACING)
            canvas.drawLine(lx, y(0f), lx, y(fretsOnChord * FRET_SPACING), gridPaint)
        }

        // A nut only makes sense when the shape starts at the first fret.
        if (position.baseFret <= 1) {
            nutPaint.strokeWidth = max(2.4f, 2.2f * scale)
            canvas.drawLine(x(0f), y(0f), x(gridWidth), y(0f), nutPaint)
        } else {
            positionLabelPaint.textSize = 7.5f * scale
            canvas.drawText("${position.baseFret}fr", x(LEFT + 1f), y(9f), positionLabelPaint)
        }

        // Barre bars sit under the individual dots so any finger placed on a
        // different row inside the bar stays visible.
        for (barre in position.barres) {
            val held = frets.indices.filter { frets[it] == barre }
            if (held.isEmpty()) continue
            val left = x(held.first() * STRING_SPACING)
            val right = x(held.last() * STRING_SPACING)
            val centre = y(dotY(barre))
            val halfHeight = 4.1f * scale
            val radius = minOf(halfHeight, (right - left) / 2f)
            canvas.drawRoundRect(left, centre - halfHeight, right, centre + halfHeight, radius, radius, dotPaint)

            val finger = position.finger(held.first())
            if (finger > 0) {
                fingerTextPaint.textSize = 6.4f * scale
                canvas.drawText(
                    finger.toString(),
                    (left + right) / 2f,
                    centre + 2.2f * scale,
                    fingerTextPaint
                )
            }
        }

        // Individual finger dots, skipping anything covered by a barre.
        for (i in frets.indices) {
            val value = frets[i]
            if (value < 1 || position.barres.contains(value)) continue
            val cx = x(i * STRING_SPACING)
            val cy = y(dotY(value))
            canvas.drawCircle(cx, cy, 4f * scale, dotPaint)
            val finger = position.finger(i)
            if (finger > 0) {
                fingerTextPaint.textSize = 6.4f * scale
                canvas.drawText(finger.toString(), cx, cy + 2.2f * scale, fingerTextPaint)
            }
        }

        // Muted (x) and open (o) markers above the nut.
        for (i in frets.indices) {
            val cx = x(i * STRING_SPACING)
            when (frets[i]) {
                -1 -> {
                    mutedTextPaint.textSize = 9f * scale
                    canvas.drawText("x", cx, y(-4.5f), mutedTextPaint)
                }
                0 -> canvas.drawCircle(cx, y(-5.5f), 2.2f * scale, openPaint)
            }
        }

        // Open-to-high string names under the neck.
        for (i in frets.indices) {
            val label = stringLabels.getOrNull(i) ?: continue
            stringLabelPaint.textSize = 6.2f * scale
            canvas.drawText(label, x(i * STRING_SPACING), y(54f), stringLabelPaint)
        }
    }

}
