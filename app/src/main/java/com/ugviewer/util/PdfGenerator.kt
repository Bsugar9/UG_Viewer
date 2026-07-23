package com.ugviewer.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.ugviewer.api.TabResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object PdfGenerator {

    private const val PAGE_WIDTH = 595   // A4 at 72 DPI
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_LEFT = 50
    private const val MARGIN_RIGHT = 50
    private const val MARGIN_TOP = 50
    private const val MARGIN_BOTTOM = 50
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT

    private data class PdfColors(
        val title: Int = Color.parseColor("#1A1A2E"),
        val artist: Int = Color.parseColor("#0F3460"),
        val chord: Int = Color.parseColor("#E94560"),
        val tabText: Int = Color.parseColor("#1A1A2E"),
        val meta: Int = Color.parseColor("#555555"),
        val divider: Int = Color.parseColor("#CCCCCC"),
        val headerBg: Int = Color.parseColor("#F0F0F5"),
        val chordBadge: Int = Color.parseColor("#FFF3E0")
    )

    private val colors = PdfColors()

    fun generatePdf(context: Context, tab: TabResult): File {
        val document = buildPdfDocument(tab)
        val fileName = sanitizeFileName("${tab.artistName} - ${tab.songName}.pdf")
        val file = saveToDownloads(context, document, fileName)
        document.close()
        return file
    }

    fun generatePdfToBytes(tab: TabResult): ByteArray {
        val document = buildPdfDocument(tab)
        val outputStream = ByteArrayOutputStream()
        document.writeTo(outputStream)
        document.close()
        return outputStream.toByteArray()
    }

    private fun buildPdfDocument(tab: TabResult): PdfDocument {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()

        var pageNum = 1
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        var y = drawPage(null, tab, canvas, pageNum, null)

        while (y > 0f && y > PAGE_HEIGHT - MARGIN_BOTTOM) {
            document.finishPage(page)
            pageNum++
            val newPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
            page = document.startPage(newPageInfo)
            canvas = page.canvas
            y = drawPage(null, tab, canvas, pageNum, y)
        }
        document.finishPage(page)
        return document
    }

    private fun drawPage(
        context: Context?,
        tab: TabResult,
        canvas: Canvas,
        pageNum: Int,
        continuationY: Float?
    ): Float {
        val titlePaint = Paint().apply {
            color = colors.title
            textSize = 22f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            isAntiAlias = true
        }

        val artistPaint = Paint().apply {
            color = colors.artist
            textSize = 16f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
            isAntiAlias = true
        }

        val metaPaint = Paint().apply {
            color = colors.meta
            textSize = 11f
            isAntiAlias = true
        }

        val chordLabelPaint = Paint().apply {
            color = colors.chord
            textSize = 12f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            isAntiAlias = true
        }

        val tabPaint = Paint().apply {
            color = colors.tabText
            textSize = 10f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = colors.divider
            strokeWidth = 1f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }

        val badgePaint = Paint().apply {
            color = colors.chordBadge
            style = Paint.Style.FILL
        }

        val badgeTextPaint = Paint().apply {
            color = colors.chord
            textSize = 10f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            isAntiAlias = true
        }

        val pageFooterPaint = Paint().apply {
            color = colors.meta
            textSize = 9f
            isAntiAlias = true
        }

        var y = continuationY ?: MARGIN_TOP.toFloat()

        if (pageNum == 1) {
            // Title
            canvas.drawText(truncate(tab.songName, 45, titlePaint), MARGIN_LEFT.toFloat(), y + 20f, titlePaint)
            y += 30f

            // Artist
            canvas.drawText(truncate("by ${tab.artistName}", 55, artistPaint), MARGIN_LEFT.toFloat(), y + 18f, artistPaint)
            y += 28f

            // Divider line
            canvas.drawLine(MARGIN_LEFT.toFloat(), y, (PAGE_WIDTH - MARGIN_RIGHT).toFloat(), y, dividerPaint)
            y += 14f

            // Metadata row
            val metaLine = buildString {
                append("Type: ${tab.type.ifEmpty { "Tab" }}")
                if (tab.tuning.isNotEmpty()) append("  |  Tuning: ${tab.tuning}")
                if (tab.capo > 0) append("  |  Capo: ${tab.capo}th fret")
                append("  |  Rating: ${"%.1f".format(tab.rating)}")
            }
            canvas.drawText(truncate(metaLine, 80, metaPaint), MARGIN_LEFT.toFloat(), y + 12f, metaPaint)
            y += 20f

            // Chord badges
            if (tab.applicature.isNotEmpty()) {
                val chords = tab.applicature.map { it.chord }
                var x = MARGIN_LEFT.toFloat()
                val badgeSpacing = 6f
                for (chord in chords) {
                    val chordWidth = badgeTextPaint.measureText(chord) + 16f
                    if (x + chordWidth > PAGE_WIDTH - MARGIN_RIGHT) {
                        y += 20f
                        x = MARGIN_LEFT.toFloat()
                    }
                    canvas.drawRoundRect(x, y, x + chordWidth, y + 18f, 4f, 4f, badgePaint)
                    canvas.drawText(chord, x + 8f, y + 13f, badgeTextPaint)
                    x += chordWidth + badgeSpacing
                }
                y += 26f
            }

            // Another divider
            canvas.drawLine(MARGIN_LEFT.toFloat(), y, (PAGE_WIDTH - MARGIN_RIGHT).toFloat(), y, dividerPaint)
            y += 16f
        }

        // Tab content
        val lines = tab.content.lines()
        val lineHeight = 13f
        val tagRegex = Regex("""\[/?(tab|ch)\]""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            if (y + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM - 20f) {
                return y
            }

            val cleanLine = line.replace(tagRegex, "").trim()
            if (cleanLine.isEmpty()) continue

            if (line.lowercase().contains("[ch]")) {
                canvas.drawText(cleanLine, MARGIN_LEFT.toFloat(), y + 10f, chordLabelPaint)
            } else {
                canvas.drawText(cleanLine, MARGIN_LEFT.toFloat(), y + 10f, tabPaint)
            }
            y += lineHeight
        }

        // Page footer
        canvas.drawLine(MARGIN_LEFT.toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 5f, (PAGE_WIDTH - MARGIN_RIGHT).toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 5f, dividerPaint)
        val footerText = "UG Viewer  |  Page $pageNum  |  Source: ${tab.urlWeb.take(60)}"
        canvas.drawText(footerText, MARGIN_LEFT.toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 18f, pageFooterPaint)

        return -1f // Signal we're done (all lines fit)
    }

    private fun saveToDownloads(context: Context, document: PdfDocument, fileName: String): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/UG Viewer")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("Failed to create file in Downloads")

            resolver.openOutputStream(uri)?.use { outputStream ->
                document.writeTo(outputStream)
            } ?: throw Exception("Failed to open output stream")

            return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val ugDir = File(downloadsDir, "UG Viewer")
            if (!ugDir.exists()) ugDir.mkdirs()
            val file = File(ugDir, fileName)
            FileOutputStream(file).use { outputStream ->
                document.writeTo(outputStream)
            }
            return file
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[<>:\"/\\\\|?*]"), "_").trim()
    }

    private fun truncate(text: String, maxChars: Int, paint: Paint): String {
        if (text.length <= maxChars) return text
        val truncated = text.take(maxChars - 1) + "\u2026"
        return truncated
    }
}
