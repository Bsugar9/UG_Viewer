package com.ugviewer.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
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

    // Chords and lyrics share one monospace grid: a chord drawn at column N
    // sits exactly above lyric column N, even after wrapping.
    private const val CHORD_BASELINE = 10f
    private const val LYRIC_BASELINE = 24f
    private const val CHORD_ROW_HEIGHT = 29f
    private const val PLAIN_ROW_HEIGHT = 13f
    private const val GAP_HEIGHT = 8f

    private data class PdfColors(
        val title: Int = Color.parseColor("#1A1A2E"),
        val artist: Int = Color.parseColor("#0F3460"),
        val chord: Int = Color.parseColor("#E53935"),  // forced red
        val tabText: Int = Color.parseColor("#1A1A2E"),
        val meta: Int = Color.parseColor("#555555"),
        val divider: Int = Color.parseColor("#CCCCCC"),
        val headerBg: Int = Color.parseColor("#F0F0F5"),
        val chordBadge: Int = Color.parseColor("#FFF3E0")
    )

    private val colors = PdfColors()

    private val chordTokenRegex = Regex("""\S+""")
    private val chPairRegex = Regex("""\[ch\](.*?)\[/ch\]""", RegexOption.IGNORE_CASE)
    private val anyTagRegex = Regex("""\[/?(?:ch|tab)\]""", RegexOption.IGNORE_CASE)

    /** One wrapped chord+lyric segment: chord names with column offsets plus lyric text. */
    private class ChordSeg(val chords: List<Pair<Int, String>>, val lyric: String)

    private sealed class PdfRow {
        class ChordLyric(val segments: List<ChordSeg>) : PdfRow()
        class Plain(val text: String) : PdfRow()
        class Gap(val height: Float = GAP_HEIGHT) : PdfRow()
    }

    fun generatePdf(context: Context, tab: TabResult, youtubeUrl: String? = null): File {
        val document = buildPdfDocument(tab, youtubeUrl)
        val fileName = sanitizeFileName("${tab.artistName} - ${tab.songName}.pdf")
        val file = saveToDownloads(context, document, fileName)
        document.close()
        return file
    }

    fun generatePdfToBytes(tab: TabResult, youtubeUrl: String? = null): ByteArray {
        val document = buildPdfDocument(tab, youtubeUrl)
        val outputStream = ByteArrayOutputStream()
        document.writeTo(outputStream)
        document.close()
        return outputStream.toByteArray()
    }

    private fun buildPdfDocument(tab: TabResult, youtubeUrl: String?): PdfDocument {
        val document = PdfDocument()

        // Same monospace size for chords and lyrics so advance widths match.
        val lyricPaint = Paint().apply {
            color = colors.tabText
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
        }
        val chordPaint = Paint().apply {
            color = colors.chord
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }
        val charWidth = lyricPaint.measureText("n")
        val maxCols = maxOf(20, ((PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT) / charWidth).toInt())

        val rows = buildRows(tab.content, maxCols)

        var pageNum = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
        var canvas = page.canvas
        var y = drawHeader(canvas, tab, youtubeUrl)

        for (row in rows) {
            val h = when (row) {
                is PdfRow.ChordLyric -> CHORD_ROW_HEIGHT * row.segments.size
                is PdfRow.Plain -> PLAIN_ROW_HEIGHT
                is PdfRow.Gap -> row.height
            }
            if (y + h > PAGE_HEIGHT - MARGIN_BOTTOM - 24f) {
                drawPageFooter(canvas, pageNum, tab)
                document.finishPage(page)
                pageNum++
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
                canvas = page.canvas
                y = MARGIN_TOP.toFloat()
            }
            when (row) {
                is PdfRow.ChordLyric -> {
                    var sy = y
                    for (seg in row.segments) {
                        val x0 = MARGIN_LEFT.toFloat()
                        for ((rel, name) in seg.chords) {
                            canvas.drawText(name, x0 + rel * charWidth, sy + CHORD_BASELINE, chordPaint)
                        }
                        if (seg.lyric.isNotEmpty()) {
                            canvas.drawText(seg.lyric, x0, sy + LYRIC_BASELINE, lyricPaint)
                        }
                        sy += CHORD_ROW_HEIGHT
                    }
                }
                is PdfRow.Plain -> canvas.drawText(row.text, MARGIN_LEFT.toFloat(), y + 10f, lyricPaint)
                is PdfRow.Gap -> Unit
            }
            y += h
        }

        drawPageFooter(canvas, pageNum, tab)
        document.finishPage(page)
        return document
    }

    /**
     * Parses UG content into layout rows. A chord-only line ([ch]..[/ch] with
     * no other text) is paired with the following lyric line; inline tags such
     * as "some [ch]Am[/ch] words" become a chord at that exact column.
     */
    private fun buildRows(content: String, maxCols: Int): List<PdfRow> {
        val rows = mutableListOf<PdfRow>()
        var pendingChords: List<Pair<Int, String>>? = null
        var inTabBlock = false

        fun addPlain(text: String) {
            val scrubbed = anyTagRegex.replace(text, "")
            if (scrubbed.isBlank()) return
            for ((_, seg) in wrapKeepOffsets(scrubbed.trimEnd(), maxCols)) rows.add(PdfRow.Plain(seg))
        }

        fun addChordLyric(chords: List<Pair<Int, String>>, lyric: String) {
            if (lyric.isBlank()) {
                // Chords only (instrumental line): chunk across columns.
                var base = -1
                val cur = mutableListOf<Pair<Int, String>>()
                val segs = mutableListOf<ChordSeg>()
                for ((off, name) in chords) {
                    if (base < 0) base = off
                    val rel = off - base
                    if (rel + name.length > maxCols && cur.isNotEmpty()) {
                        segs.add(ChordSeg(cur.toList(), ""))
                        cur.clear()
                        base = off
                    }
                    cur.add((off - base) to name)
                }
                if (cur.isNotEmpty()) segs.add(ChordSeg(cur.toList(), ""))
                if (segs.isNotEmpty()) rows.add(PdfRow.ChordLyric(segs))
            } else {
                // Wrap the lyric, then attach each chord to the segment that
                // contains its original character column (relative offset kept).
                val parts = wrapKeepOffsets(lyric, maxCols)
                val chordLists = List(parts.size) { mutableListOf<Pair<Int, String>>() }
                for ((off, name) in chords) {
                    var idx = 0
                    for (i in parts.indices) if (off >= parts[i].first) idx = i
                    val rel = (off - parts[idx].first).coerceIn(0, (maxCols - name.length).coerceAtLeast(0))
                    chordLists[idx].add(rel to name)
                }
                rows.add(PdfRow.ChordLyric(parts.mapIndexed { i, (_, text) -> ChordSeg(chordLists[i].toList(), text) }))
            }
        }

        for (raw in content.lines()) {
            val line = raw.trimEnd('\r')
            val lower = line.lowercase()

            when {
                lower.contains("[/tab]") -> {
                    val idx = lower.indexOf("[/tab]")
                    if (idx > 0) addPlain(line.substring(0, idx))
                    val tail = line.substring(idx + 6)
                    if (tail.isNotBlank()) addPlain(tail)
                    inTabBlock = false
                }
                lower.contains("[tab]") -> {
                    val idx = lower.indexOf("[tab]") + 5
                    if (idx < line.length) addPlain(line.substring(idx))
                    inTabBlock = true
                }
                inTabBlock -> addPlain(line)
                lower.contains("[ch]") || lower.contains("[/ch]") -> {
                    // Robust tag handling: paired [ch]X[/ch] contribute chord
                    // names at their exact mapped column; ANY leftover tag
                    // (unclosed, stray, mixed case) is stripped from the
                    // visible text so tags can never leak into the PDF.
                    val sb = StringBuilder()
                    val inline = mutableListOf<Pair<Int, String>>()
                    val nameSpans = mutableListOf<Pair<Int, Int>>()
                    var cursor = 0
                    for (m in chPairRegex.findAll(line)) {
                        sb.append(anyTagRegex.replace(line.substring(cursor, m.range.first), ""))
                        val inner = m.groupValues[1]
                        for (t in chordTokenRegex.findAll(inner)) {
                            inline.add((sb.length + t.range.first) to t.value)
                        }
                        nameSpans.add(sb.length to inner.length)
                        sb.append(inner)
                        cursor = m.range.last + 1
                    }
                    // Tail after the last pair: handle an unclosed "[ch]X"
                    // gracefully (first token becomes a chord), strip strays.
                    val tail = line.substring(cursor)
                    val strayOpen = tail.lowercase().indexOf("[ch]")
                    if (strayOpen >= 0) {
                        val afterOpen = tail.substring(strayOpen + 4)
                        val token = chordTokenRegex.find(afterOpen)
                        if (token != null) {
                            sb.append(anyTagRegex.replace(tail.substring(0, strayOpen), ""))
                            inline.add(sb.length to token.value)
                            nameSpans.add(sb.length to token.value.length)
                            sb.append(token.value)
                            sb.append(anyTagRegex.replace(tail.substring(strayOpen + 4 + token.value.length), ""))
                        } else {
                            sb.append(anyTagRegex.replace(tail, ""))
                        }
                    } else {
                        sb.append(anyTagRegex.replace(tail, ""))
                    }
                    val residual = sb.toString()
                    // Text outside chord tags decides "chord-only line" status.
                    val outsideText = anyTagRegex.replace(chPairRegex.replace(line, ""), "")
                    when {
                        inline.isEmpty() -> addPlain(residual)
                        outsideText.isBlank() -> pendingChords = inline
                        else -> {
                            // Mixed chord+lyric line: blank out the chord names
                            // in the lyric row so they are not drawn twice.
                            val lyricSb = StringBuilder(residual)
                            for ((start, len) in nameSpans.asReversed()) {
                                for (k in start until start + len) lyricSb.setCharAt(k, ' ')
                            }
                            // Slide each chord right past the blanked span onto
                            // the next sung word (the removed tag leaves a gap).
                            val nudged = inline.map { (off, name) ->
                                var o = off.coerceIn(0, (lyricSb.length - 1).coerceAtLeast(0))
                                while (o < lyricSb.length - 1 && lyricSb[o] == ' ') o++
                                o to name
                            }
                            addChordLyric(nudged, lyricSb.toString().trimEnd())
                        }
                    }
                }
                line.isBlank() -> {
                    pendingChords?.let { addChordLyric(it, "") }
                    pendingChords = null
                    rows.add(PdfRow.Gap())
                }
                pendingChords != null -> {
                    addChordLyric(pendingChords!!, line.trimEnd())
                    pendingChords = null
                }
                else -> addPlain(line)
            }
        }
        pendingChords?.let { addChordLyric(it, "") }
        return rows
    }

    /**
     * Wraps text at maxCols (preferring spaces) and returns (startIndexInOriginal,
     * segment) pairs so chord offsets recorded on the original string stay valid.
     */
    private fun wrapKeepOffsets(text: String, maxCols: Int): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        if (text.isEmpty()) return result
        if (text.length <= maxCols) {
            result.add(0 to text)
            return result
        }
        var start = 0
        while (start < text.length) {
            val end = minOf(start + maxCols, text.length)
            var cut = end
            if (end < text.length) {
                val lastSpace = text.lastIndexOf(' ', end - 1)
                if (lastSpace > start) cut = lastSpace
            }
            result.add(start to text.substring(start, cut))
            start = if (cut < text.length && text[cut] == ' ') cut + 1 else cut
        }
        return result
    }

    private fun drawHeader(canvas: Canvas, tab: TabResult, youtubeUrl: String?): Float {
        val titlePaint = Paint().apply {
            color = colors.title
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val artistPaint = Paint().apply {
            color = colors.artist
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }
        val metaPaint = Paint().apply {
            color = colors.meta
            textSize = 11f
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
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }

        var y = MARGIN_TOP.toFloat()

        canvas.drawText(truncate(tab.songName, 45, titlePaint), MARGIN_LEFT.toFloat(), y + 20f, titlePaint)
        y += 30f

        canvas.drawText(truncate("by ${tab.artistName}", 55, artistPaint), MARGIN_LEFT.toFloat(), y + 18f, artistPaint)
        y += 28f

        canvas.drawLine(MARGIN_LEFT.toFloat(), y, (PAGE_WIDTH - MARGIN_RIGHT).toFloat(), y, dividerPaint)
        y += 14f

        val metaLine = buildString {
            append("Type: ${tab.type.ifEmpty { "Tab" }}")
            if (tab.tuning.isNotEmpty()) append("  |  Tuning: ${tab.tuning}")
            if (tab.capo > 0) append("  |  Capo: ${tab.capo}th fret")
            append("  |  Rating: ${"%.1f".format(tab.rating)}")
        }
        canvas.drawText(truncate(metaLine, 80, metaPaint), MARGIN_LEFT.toFloat(), y + 12f, metaPaint)
        y += 20f

        if (!youtubeUrl.isNullOrEmpty()) {
            val linkPaint = Paint().apply {
                color = Color.parseColor("#1A73E8")
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText("Listen on YouTube: $youtubeUrl", MARGIN_LEFT.toFloat(), y + 12f, linkPaint)
            y += 22f
        }

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

        canvas.drawLine(MARGIN_LEFT.toFloat(), y, (PAGE_WIDTH - MARGIN_RIGHT).toFloat(), y, dividerPaint)
        y += 16f

        return y
    }

    private fun drawPageFooter(canvas: Canvas, pageNum: Int, tab: TabResult) {
        val dividerPaint = Paint().apply {
            color = colors.divider
            strokeWidth = 1f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val pageFooterPaint = Paint().apply {
            color = colors.meta
            textSize = 9f
            isAntiAlias = true
        }
        canvas.drawLine(
            MARGIN_LEFT.toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 5f,
            (PAGE_WIDTH - MARGIN_RIGHT).toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 5f, dividerPaint
        )
        val footerText = "UG Viewer  |  Page $pageNum  |  Source: ${tab.urlWeb.take(60)}"
        canvas.drawText(footerText, MARGIN_LEFT.toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 18f, pageFooterPaint)
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
        return name.replace(Regex("[<>:\"/\\|?*]"), "_").trim()
    }

    private fun truncate(text: String, maxChars: Int, paint: Paint): String {
        if (text.length <= maxChars) return text
        val truncated = text.take(maxChars - 1) + "\u2026"
        return truncated
    }
}
