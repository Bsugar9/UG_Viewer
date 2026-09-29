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
    // sits exactly above lyric column N, even after wrapping. The chord band is
    // deliberately tight so a chord hugs the lyric line it belongs to.
    private const val CHORD_BASELINE = 8.5f
    private const val LYRIC_BASELINE = 17f
    private const val CHORD_ROW_HEIGHT = 22f
    private const val PLAIN_ROW_HEIGHT = 12f
    private const val GAP_HEIGHT = 6f
    private const val BASE_FONT_SIZE = 10f

    // Header/footer text is fixed and small: it exists only for context and must
    // never scale with the body font, which would steal lines from the tab.
    private const val HEADER_FONT_SIZE = 8f

    val FONT_SIZE_OPTIONS = (29..42).map { it.toFloat() }
    const val DEFAULT_FONT_SIZE = 32f
    const val MIN_FONT_SIZE = 29f
    const val MAX_FONT_SIZE = 42f

    /**
     * Every draw metric is derived from one body font size. Chords and lyrics
     * always use the same point size, so their monospace advance widths match
     * and a chord stays exactly above its lyric column at any size.
     */
    private data class PdfStyle(
        val fontSize: Float,
        val chordBaseline: Float,
        val lyricBaseline: Float,
        val chordRowHeight: Float,
        val plainRowHeight: Float,
        val gapHeight: Float,
        val titleSize: Float,
        val artistSize: Float,
        val metaSize: Float,
        val linkSize: Float,
        val badgeTextSize: Float,
        val footerSize: Float,
        val badgeHeight: Float
    ) {
        companion object {
            fun forFontSize(size: Float): PdfStyle {
                val k = size / BASE_FONT_SIZE
                return PdfStyle(
                    fontSize = size,
                    chordBaseline = CHORD_BASELINE * k,
                    lyricBaseline = LYRIC_BASELINE * k,
                    chordRowHeight = CHORD_ROW_HEIGHT * k,
                    plainRowHeight = PLAIN_ROW_HEIGHT * k,
                    gapHeight = GAP_HEIGHT * k,
                    // Header/footer metrics are fixed (not scaled) so a large
                    // body font cannot inflate the header and consume the page.
                    titleSize = HEADER_FONT_SIZE,
                    artistSize = HEADER_FONT_SIZE,
                    metaSize = HEADER_FONT_SIZE,
                    linkSize = HEADER_FONT_SIZE,
                    badgeTextSize = HEADER_FONT_SIZE,
                    footerSize = HEADER_FONT_SIZE,
                    badgeHeight = 11f
                )
            }
        }
    }

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

    // Chord-name recognition for plain (untagged) lines.
    private val chordQualityStartChars = "madsbM(#0123456789"
    private val chordQualityChars = "majnidusbgM()#0123456789"
    private val chordWordBlocklist = setOf(
        "as", "ed", "add", "bad", "cad", "dad", "sad", "fab", "cab", "dab",
        "baa", "bass", "dabs", "dada", "abba", "cabs", "fabs", "babs", "gabs", "ads", "adds"
    )

    /** True if the token looks like a chord name, e.g. C, Am7, F#m7, Cadd9, G/B, Dsus4. */
    private fun isChordName(token: String): Boolean {
        if (token.isEmpty() || token.length > 12) return false
        if (token[0] !in 'A'..'G') return false
        if (token.lowercase() in chordWordBlocklist) return false
        var i = 1
        if (i < token.length && (token[i] == '#' || token[i] == 'b')) i++
        val quality = token.substring(i)
        val slash = quality.indexOf('/')
        val main = if (slash >= 0) quality.substring(0, slash) else quality
        val tail = if (slash >= 0) quality.substring(slash + 1) else null
        if (tail != null && (tail.isEmpty() || tail[0] !in 'A'..'G')) return false
        if (main.isNotEmpty()) {
            // Quality must start with a known chord-quality prefix; this keeps
            // ordinary words (bed, gabs, ed...) from matching as chords.
            val c0 = main[0]
            val c1 = main.getOrElse(1) { ' ' }
            val knownQuality = when {
                c0.isDigit() || c0 == '(' -> true              // 7, 9, (add9)...
                c0 == 'm' || c0 == 'M' -> true                 // m, m7, maj, M7...
                c0 == 's' -> c1 == 'u'                         // sus2, sus4
                c0 == 'd' -> c1 == 'i'                         // dim
                c0 == 'a' -> c1 == 'd' || c1 == 'u'            // add9, aug
                c0 == 'b' -> c1 == '5'                         // b5
                else -> false
            }
            if (!knownQuality) return false
            if (main.any { it !in chordQualityChars }) return false
        }
        if (tail != null && tail.any { it !in chordQualityChars && it !in 'A'..'G' }) return false
        return true
    }

    /** One wrapped chord+lyric segment: chord names with column offsets plus lyric text. */
    internal class ChordSeg(val chords: List<Pair<Int, String>>, val lyric: String)

    internal sealed class PdfRow {
        class ChordLyric(val segments: List<ChordSeg>) : PdfRow()
        class Plain(val text: String) : PdfRow()
        class Gap(val height: Float = GAP_HEIGHT) : PdfRow()
    }

    fun generatePdf(
        context: Context,
        tab: TabResult,
        youtubeUrl: String? = null,
        fontSize: Float = DEFAULT_FONT_SIZE
    ): File {
        val document = buildPdfDocument(tab, youtubeUrl, fontSize)
        val fileName = sanitizeFileName("${tab.artistName} - ${tab.songName}.pdf")
        val file = saveToDownloads(context, document, fileName)
        document.close()
        return file
    }

    fun generatePdfToBytes(
        tab: TabResult,
        youtubeUrl: String? = null,
        fontSize: Float = DEFAULT_FONT_SIZE
    ): ByteArray {
        val document = buildPdfDocument(tab, youtubeUrl, fontSize)
        val outputStream = ByteArrayOutputStream()
        document.writeTo(outputStream)
        document.close()
        return outputStream.toByteArray()
    }

    private fun buildPdfDocument(
        tab: TabResult,
        youtubeUrl: String?,
        fontSize: Float
    ): PdfDocument {
        val document = PdfDocument()
        val style = PdfStyle.forFontSize(fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))

        // Same monospace size for chords and lyrics so advance widths match.
        val lyricPaint = Paint().apply {
            color = colors.tabText
            textSize = style.fontSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
        }
        val chordPaint = Paint().apply {
            color = colors.chord
            textSize = style.fontSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }
        // Columns are measured from the actual paint, so a larger font simply
        // yields fewer columns per line and the wrapper reflows the text
        // instead of letting it run off the right edge of the page.
        val charWidth = lyricPaint.measureText("n")
        val usableWidth = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT
        // Trust the measured advance width: at 42pt monospace a whole page is only
        // ~22 columns, and a hard floor would push text past the right margin.
        val maxCols = (usableWidth / charWidth).toInt().coerceAtLeast(1)

        val rows = buildRows(tab.content, maxCols)

        var pageNum = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
        var canvas = page.canvas
        var y = drawHeader(canvas, tab, youtubeUrl, style)
        val pageLimit = PAGE_HEIGHT - MARGIN_BOTTOM - 24f
        // Guards pagination: a row taller than a whole page must still be drawn,
        // otherwise the page-break loop would never terminate.
        var rowsOnPage = 0

        for (row in rows) {
            val h = when (row) {
                is PdfRow.ChordLyric -> style.chordRowHeight * row.segments.size
                is PdfRow.Plain -> style.plainRowHeight
                is PdfRow.Gap -> row.height * (style.fontSize / BASE_FONT_SIZE)
            }
            if (rowsOnPage > 0 && y + h > pageLimit) {
                drawPageFooter(canvas, pageNum, tab, style)
                document.finishPage(page)
                pageNum++
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
                canvas = page.canvas
                y = MARGIN_TOP.toFloat()
                rowsOnPage = 0
            }
            when (row) {
                is PdfRow.ChordLyric -> {
                    var sy = y
                    for (seg in row.segments) {
                        val x0 = MARGIN_LEFT.toFloat()
                        for ((rel, name) in seg.chords) {
                            canvas.drawText(name, x0 + rel * charWidth, sy + style.chordBaseline, chordPaint)
                        }
                        if (seg.lyric.isNotEmpty()) {
                            canvas.drawText(seg.lyric, x0, sy + style.lyricBaseline, lyricPaint)
                        }
                        sy += style.chordRowHeight
                    }
                }
                is PdfRow.Plain -> canvas.drawText(row.text, MARGIN_LEFT.toFloat(), y + style.plainRowHeight, lyricPaint)
                is PdfRow.Gap -> Unit
            }
            y += h
            rowsOnPage++
        }

        drawPageFooter(canvas, pageNum, tab, style)
        document.finishPage(page)
        return document
    }

    /**
     * Parses UG content into layout rows. A chord-only line ([ch]..[/ch] with
     * no other text) is paired with the following lyric line; inline tags such
     * as "some [ch]Am[/ch] words" become a chord at that exact column.
     */
    internal fun buildRows(content: String, maxCols: Int): List<PdfRow> {
        val rows = mutableListOf<PdfRow>()
        var pendingChords: List<Pair<Int, String>>? = null
        var inTabBlock = false

        fun addPlain(text: String) {
            val scrubbed = anyTagRegex.replace(text, "")
            if (scrubbed.isBlank()) return
            val trimmed = scrubbed.trimEnd()
            val tokens = chordTokenRegex.findAll(trimmed).toList()
            val chordCount = tokens.count { isChordName(it.value) }
            val lyricWords = tokens.size - chordCount
            // Only treat the line as a chord line when chord names dominate it
            // (e.g. "C  G  Am" or "Intro: C G Am"); lyrics with an occasional
            // chord-like word stay plain text.
            if (chordCount == 0 || lyricWords >= chordCount) {
                for ((_, seg) in wrapKeepOffsets(trimmed, maxCols)) rows.add(PdfRow.Plain(seg))
                return
            }
            val parts = wrapKeepOffsets(trimmed, maxCols)
            val chordLists = List(parts.size) { mutableListOf<Pair<Int, String>>() }
            for (m in tokens) {
                if (!isChordName(m.value)) continue
                val off = m.range.first
                var idx = 0
                for (i in parts.indices) if (off >= parts[i].first) idx = i
                val rel = (off - parts[idx].first).coerceIn(0, (maxCols - m.value.length).coerceAtLeast(0))
                chordLists[idx].add(rel to m.value)
            }
            val segments = if (lyricWords == 0) {
                // Pure chord line: blank the words in the lyric row so the red
                // chord names are the only thing drawn there.
                val blanked = StringBuilder(trimmed)
                for (m in tokens.asReversed()) {
                    if (isChordName(m.value)) for (k in m.range) blanked.setCharAt(k, ' ')
                }
                val lyric = blanked.toString()
                parts.mapIndexed { i, part ->
                    val segText = lyric.substring(part.first, part.first + part.second.length)
                    ChordSeg(chordLists[i].toList(), segText.trimEnd())
                }
            } else {
                parts.mapIndexed { i, (_, t) -> ChordSeg(chordLists[i].toList(), t) }
            }
            rows.add(PdfRow.ChordLyric(segments))
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
    internal fun wrapKeepOffsets(text: String, maxCols: Int): List<Pair<Int, String>> {
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

    private fun drawHeader(canvas: Canvas, tab: TabResult, youtubeUrl: String?, metrics: PdfStyle): Float {
        val titlePaint = Paint().apply {
            color = colors.title
            textSize = metrics.titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val artistPaint = Paint().apply {
            color = colors.artist
            textSize = metrics.artistSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }
        val metaPaint = Paint().apply {
            color = colors.meta
            textSize = metrics.metaSize
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
            textSize = metrics.badgeTextSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }

        val right = (PAGE_WIDTH - MARGIN_RIGHT).toFloat()
        val usableWidth = right - MARGIN_LEFT
        var y = MARGIN_TOP.toFloat()

        // Fixed, compact header: the title/artist/meta are context only, so they
        // stay at a constant small size instead of growing with the body font.
        canvas.drawText(fitToWidth(tab.songName, titlePaint, usableWidth), MARGIN_LEFT.toFloat(), y + 8f, titlePaint)
        y += 11f

        canvas.drawText(fitToWidth("by ${tab.artistName}", artistPaint, usableWidth), MARGIN_LEFT.toFloat(), y + 8f, artistPaint)
        y += 11f

        canvas.drawLine(MARGIN_LEFT.toFloat(), y, right, y, dividerPaint)
        y += 6f

        val metaLine = buildString {
            append("Type: ${tab.type.ifEmpty { "Tab" }}")
            if (tab.tuning.isNotEmpty()) append("  |  Tuning: ${tab.tuning}")
            if (tab.capo > 0) append("  |  Capo: ${tab.capo}th fret")
            append("  |  Rating: ${"%.1f".format(tab.rating)}")
        }
        canvas.drawText(fitToWidth(metaLine, metaPaint, usableWidth), MARGIN_LEFT.toFloat(), y + 8f, metaPaint)
        y += 11f

        if (!youtubeUrl.isNullOrEmpty()) {
            val linkPaint = Paint().apply {
                color = Color.parseColor("#1A73E8")
                textSize = metrics.linkSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText(fitToWidth("Listen on YouTube: $youtubeUrl", linkPaint, usableWidth), MARGIN_LEFT.toFloat(), y + 8f, linkPaint)
            y += 11f
        }

        if (tab.applicature.isNotEmpty()) {
            val chords = tab.applicature.map { it.chord }
            var x = MARGIN_LEFT.toFloat()
            val badgeSpacing = 4f
            val badgePad = 4f
            for (chord in chords) {
                val chordWidth = badgeTextPaint.measureText(chord) + 8f
                if (x + chordWidth > right) {
                    y += metrics.badgeHeight + 2f
                    x = MARGIN_LEFT.toFloat()
                }
                canvas.drawRoundRect(x, y, x + chordWidth, y + metrics.badgeHeight, 2f, 2f, badgePaint)
                canvas.drawText(chord, x + badgePad, y + metrics.badgeHeight * 0.72f, badgeTextPaint)
                x += chordWidth + badgeSpacing
            }
            y += metrics.badgeHeight + 5f
        }

        canvas.drawLine(MARGIN_LEFT.toFloat(), y, right, y, dividerPaint)
        y += 9f

        return y
    }

    private fun drawPageFooter(canvas: Canvas, pageNum: Int, tab: TabResult, metrics: PdfStyle) {
        val dividerPaint = Paint().apply {
            color = colors.divider
            strokeWidth = 1f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val pageFooterPaint = Paint().apply {
            color = colors.meta
            textSize = metrics.footerSize
            isAntiAlias = true
        }
        canvas.drawLine(
            MARGIN_LEFT.toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 5f,
            (PAGE_WIDTH - MARGIN_RIGHT).toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 5f, dividerPaint
        )
        val footerText = "UG Viewer  |  Page $pageNum  |  Font ${metrics.fontSize.toInt()}pt  |  Source: ${tab.urlWeb}"
        val usableWidth = (PAGE_WIDTH - MARGIN_RIGHT - MARGIN_LEFT).toFloat()
        canvas.drawText(
            fitToWidth(footerText, pageFooterPaint, usableWidth),
            MARGIN_LEFT.toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 18f, pageFooterPaint
        )
    }

    internal fun saveToDownloads(context: Context, document: PdfDocument, fileName: String): File {
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

    internal fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[<>:\"/\\|?*]"), "_").trim()
    }

    /**
     * Clips text to the available width using the paint's real glyph metrics,
     * appending an ellipsis. Character-count truncation is not enough for the
     * proportional header/footer fonts, which can overflow the page once the
     * selected font size (and therefore the header scale) grows.
     */
    internal fun fitToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "\u2026"
        val avail = maxWidth - paint.measureText(ellipsis)
        if (avail <= 0f) return ellipsis
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(text, 0, mid) <= avail) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo).trimEnd() + ellipsis
    }
}
