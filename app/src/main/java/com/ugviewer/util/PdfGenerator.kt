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

    const val PAGE_WIDTH = 595   // A4 at 72 DPI
    const val PAGE_HEIGHT = 842
    private const val MARGIN_RIGHT = 50

    /** Page margins; visible to the Studio so it can draw the wrap guide. */
    const val MARGIN_LEFT = 50
    const val MARGIN_TOP = 50
    const val MARGIN_BOTTOM = 50

    // Chords and lyrics share one monospace grid: a chord drawn at column N
    // sits exactly above lyric column N, even after wrapping. The vertical
    // rhythm comes from [PdfTheme] so the chord placement can be tuned
    // without touching the layout code.
    private const val BASE_FONT_SIZE = 10f

    // Header/footer text is fixed and small: it exists only for context and must
    // never scale with the body font, which would steal lines from the tab.
    private const val HEADER_FONT_SIZE = 8f

    val FONT_SIZE_OPTIONS = (10..42).map { it.toFloat() }
    const val DEFAULT_FONT_SIZE = 32f
    const val MIN_FONT_SIZE = 10f
    const val MAX_FONT_SIZE = 42f

    // Custom dial defaults: where the two sizes start the first time Custom is
    // picked, matching the old single "font size" behaviour.
    const val DEFAULT_CUSTOM_CHORD_FONT_SIZE = 32f
    const val DEFAULT_CUSTOM_LYRIC_FONT_SIZE = 32f

    // Preset page sizes pin the body sizes: a preset sheet is meant to print
    // the same way every time, so the custom font setting cannot touch it.
    // Small: the compact preset for dense sheets.
    const val CHORD_SMALL_PT = 10.5f
    const val LYRIC_SMALL_PT = 11f

    /** Most digits a typed size can hold ("42" has two). */
    const val MAX_FONT_SIZE_DIGITS = 2

    /**
     * A size typed into a field, held to the printable range. Null while the
     * digits are still a prefix no printable size can start with, so the field
     * can be left alone instead of fighting what is being typed.
     */
    fun fontSizeFromTyped(digits: String): Float? {
        if (digits.isEmpty() || digits.length > MAX_FONT_SIZE_DIGITS) return null
        val value = digits.toFloatOrNull() ?: return null
        if (value < MIN_FONT_SIZE / 10f) return null
        return value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    }

    /** Room left under a lyric baseline for descenders (g, y, p) — not a knob. */
    private const val DESCENDER = 0.35f

    // Glyph-metric estimates used to keep ink apart: how far a chord name
    // rises above its baseline, how far it hangs below, and how far a lyric
    // capital rises above its baseline.
    private const val CHORD_ASCENT = 0.78f
    private const val CHORD_DESCENT = 0.22f
    private const val LYRIC_CAP = 0.72f

    /**
     * The visual rhythm of a chord sheet, in multiples of the 10pt base size.
     *
     * [chordLinePitch] is the distance from one lyric line's baseline to the
     * next (across the chord band). The chord is ALWAYS drawn exactly halfway
     * between the two lyric lines — centring is a guarantee, not a setting —
     * so this one number is how close together the top and bottom lyric lines
     * sit.
     *
     * [plainRowHeight] governs lyric-only lines and [stanzaGap] the blank-line
     * spacing between sections. [wrapFraction] decides how much of the page
     * width a line may use before wrapping: 1.0 fills the margins, 0.6 wraps
     * at roughly 60% of the columns, giving short, easy-to-follow lines. The
     * chord-over-its-word guarantee holds at every setting.
     */
    data class PdfTheme(
        val chordLinePitch: Float = 1.5f,
        val plainRowHeight: Float = 1.2f,
        val stanzaGap: Float = 0.6f,
        val wrapFraction: Float = 1.0f
    ) {
        companion object {
            /** The shipped default: snug, with the chord centred as always. */
            val DEFAULT = PdfTheme()

            /** The original roomier layout, kept for comparison. */
            val LOOSE = PdfTheme(chordLinePitch = 2.2f)
        }
    }

    /**
     * Every draw metric is derived from the body font sizes and the theme.
     * Chords and lyrics share one monospace grid, so their advance widths
     * still match and a chord stays exactly above its lyric column.
     */
    private data class PdfStyle(
        val chordFontSize: Float,
        val lyricFontSize: Float,
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
            fun forFontSizes(chordSize: Float, lyricSize: Float, theme: PdfTheme): PdfStyle {
                val kc = chordSize / BASE_FONT_SIZE
                val kl = lyricSize / BASE_FONT_SIZE
                // One number sets the desired lyric-to-lyric pitch; the chord's
                // baseline is then EXACTLY halfway between the two lyric
                // BASELINES. The row is not symmetric around its middle: the
                // bottom lyric baseline sits a descender's height above the row
                // bottom and the top one the same distance below the row top,
                // so the true midpoint is h/2 - d, not h/2. Centring is
                // geometry — no slider can de-centre it. When the fonts are too
                // big for the chosen pitch, the ROW GROWS to the ink-clearance
                // minimum (see [minimumChordRowHeight]) instead of the chord
                // moving or touching either lyric line.
                val chordRowHeight = maxOf(
                    theme.chordLinePitch * BASE_FONT_SIZE * maxOf(kc, kl),
                    minimumChordRowHeight(chordSize, lyricSize)
                )
                val lyricBaseline = chordRowHeight - DESCENDER * BASE_FONT_SIZE * kl
                val chordBaseline = lyricBaseline - chordRowHeight / 2f
                return PdfStyle(
                    chordFontSize = chordSize,
                    lyricFontSize = lyricSize,
                    chordBaseline = chordBaseline,
                    lyricBaseline = lyricBaseline,
                    chordRowHeight = chordRowHeight,
                    plainRowHeight = theme.plainRowHeight * BASE_FONT_SIZE * kl,
                    gapHeight = theme.stanzaGap * BASE_FONT_SIZE * kl,
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

    /**
     * The smallest chord+lyric row (in points) in which a centred chord name
     * fits without its ink touching either neighbouring lyric line: half the
     * row must cover the chord's rise above its baseline plus the line-above's
     * descenders, and half must cover the lyric's capitals plus the chord's
     * hang below its baseline. Exposed for tests.
     */
    internal fun minimumChordRowHeight(chordFontSize: Float, lyricFontSize: Float): Float {
        val chordAscent = CHORD_ASCENT * chordFontSize
        val lyricDescender = DESCENDER * lyricFontSize
        val lyricCap = LYRIC_CAP * lyricFontSize
        val chordDescent = CHORD_DESCENT * chordFontSize
        // The centred chord baseline sits at h/2 - d (the lyric baselines'
        // midpoint; the previous line's baseline is d ABOVE this row's top),
        // so each half-row must cover, from that midpoint, with a pad:
        //  - upward: chord ascent + the line-above's descender depth
        //  - downward: chord descent + the lyric's cap height (the -d in the
        //    midpoint cancels the descender allowance on this side)
        val pad = 0.12f * maxOf(chordFontSize, lyricFontSize)
        return 2f * maxOf(
            chordAscent + lyricDescender,
            chordDescent + lyricCap
        ) + 2f * pad
    }

    /**
     * Horizontal no-overlap resolution: chords are charted at their lyric
     * columns, but two chord names can be closer than the first one is wide.
     * Walks left to right pushing each name past the previous one's end, never
     * past the right margin. Exposed for tests.
     */
    internal fun resolveChordXs(
        charted: List<Float>,
        widths: List<Float>,
        startX: Float,
        rightEdge: Float
    ): List<Float> {
        val resolved = MutableList(charted.size) { 0f }
        var cursor = startX
        val gap = 1.5f
        for (i in charted.indices) {
            val width = widths.getOrElse(i) { 0f }
            val x = maxOf(charted[i], cursor).coerceAtMost((rightEdge - width).coerceAtLeast(startX))
            resolved[i] = x
            cursor = x + width + gap
        }
        return resolved
    }

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

        /**
         * A lyric-only line. Every lyric line reserves the chord band above it
         * ("a gap where a chord should be placed"), wrapped continuations
         * included; [isTabLine] marks [tab]-block rows, which stay tight.
         */
        class Plain(val text: String, val isTabLine: Boolean = false) : PdfRow()
        class Gap(val height: Float) : PdfRow()
    }

    /** Where a drawn chord name lives on its page, in PDF points. */
    class ChordHit(val page: Int, val name: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        /** True when [x]/[y] (page points) fall inside this chord's box. */
        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom
    }

    /** Per-page chord hit boxes produced alongside a generated document. */
    class ChordHitMap(val hits: List<ChordHit>) {
        /** The chord under (x, y) on [page], or null when the tap lands on nothing. */
        fun chordAt(page: Int, x: Float, y: Float): ChordHit? =
            hits.lastOrNull { it.page == page && it.contains(x, y) }

        companion object {
            val EMPTY = ChordHitMap(emptyList())
        }
    }

    /**
     * Page setup for a chord sheet. Small is a fixed size that ignores the
     * font setting; anything else picks the body size directly.
     */
    sealed class PdfFormat {
        object Small : PdfFormat()
        object FitToPage : PdfFormat()
        data class Custom(val chordFontSize: Float, val lyricFontSize: Float) : PdfFormat()
    }

    val PDF_FORMATS: List<PdfFormat> =
        listOf(PdfFormat.Small, PdfFormat.FitToPage, PdfFormat.Custom(DEFAULT_FONT_SIZE, DEFAULT_FONT_SIZE))

    fun labelOf(format: PdfFormat): String = when (format) {
        PdfFormat.Small -> "Small"
        PdfFormat.FitToPage -> "Fit To Page"
        is PdfFormat.Custom -> "Custom"
    }

    /** Presets compare by kind only, so the menu marks Custom selected however its sizes were last set. */
    fun isSameKindAs(a: PdfFormat, b: PdfFormat): Boolean = when {
        a is PdfFormat.Custom && b is PdfFormat.Custom -> true
        else -> a == b
    }

    fun generatePdf(
        context: Context,
        tab: TabResult,
        youtubeUrl: String? = null,
        format: PdfFormat = PdfFormat.FitToPage,
        theme: PdfTheme = PdfTheme.DEFAULT
    ): File {
        val document = buildPdfDocument(tab, youtubeUrl, format, theme)
        val fileName = sanitizeFileName("${tab.artistName} - ${tab.songName}.pdf")
        val file = saveToDownloads(context, document, fileName)
        document.close()
        return file
    }

    fun generatePdfToBytes(
        tab: TabResult,
        youtubeUrl: String? = null,
        format: PdfFormat = PdfFormat.FitToPage,
        theme: PdfTheme = PdfTheme.DEFAULT,
        hitMapOut: (ChordHitMap) -> Unit = {}
    ): ByteArray {
        val document = buildPdfDocument(tab, youtubeUrl, format, theme, hitMapOut)
        val outputStream = ByteArrayOutputStream()
        document.writeTo(outputStream)
        document.close()
        return outputStream.toByteArray()
    }

    private fun PdfFormat.bodyFontSizes(): Pair<Float, Float> = when (this) {
        PdfFormat.Small -> CHORD_SMALL_PT to LYRIC_SMALL_PT
        PdfFormat.FitToPage -> DEFAULT_FONT_SIZE to DEFAULT_FONT_SIZE
        is PdfFormat.Custom ->
            chordFontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) to
                lyricFontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    }

    /** Builds the document for the save flow; the caller owns and closes it. */
    fun buildDocumentForSave(
        tab: TabResult,
        youtubeUrl: String?,
        format: PdfFormat,
        theme: PdfTheme = PdfTheme.DEFAULT
    ): PdfDocument = buildPdfDocument(tab, youtubeUrl, format, theme)

    private fun buildPdfDocument(
        tab: TabResult,
        youtubeUrl: String?,
        format: PdfFormat,
        theme: PdfTheme,
        hitMapOut: (ChordHitMap) -> Unit = {}
    ): PdfDocument {
        val document = PdfDocument()
        val (chordSize, lyricSize) = format.bodyFontSizes()
        // Chords and lyrics each get their own style; the shared monospace grid
        // below is what still keeps a chord above its lyric column.
        val style = PdfStyle.forFontSizes(chordSize, lyricSize, theme)

        // Same monospace size for chords and lyrics so advance widths match.
        val lyricPaint = Paint().apply {
            color = colors.tabText
            textSize = style.lyricFontSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
        }
        val chordPaint = Paint().apply {
            color = colors.chord
            textSize = style.chordFontSize
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
        // The theme's wrap fraction narrows that further: 0.6 wraps at ~60% of
        // the columns for short, easy-to-follow lines.
        val maxCols = ((usableWidth / charWidth) * theme.wrapFraction).toInt().coerceAtLeast(1)

        val rows = buildRows(tab.content, maxCols, theme)

        var pageNum = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
        var canvas = page.canvas
        var y = drawHeader(canvas, tab, youtubeUrl, style)
        val pageLimit = PAGE_HEIGHT - MARGIN_BOTTOM - 24f
        // Guards pagination: a row taller than a whole page must still be drawn,
        // otherwise the page-break loop would never terminate.
        var rowsOnPage = 0
        val hitMap = ChordHitMap(mutableListOf())
        val chordHits = hitMap.hits as MutableList<ChordHit>

        for (row in rows) {
            val h = when (row) {
                is PdfRow.ChordLyric -> style.chordRowHeight * row.segments.size
                // Lyric lines carry the same band as chorded lines, so a
                // pencil-in chord has somewhere to live everywhere; tab rows
                // stay at their own tight height.
                is PdfRow.Plain -> if (row.isTabLine) style.plainRowHeight else style.chordRowHeight
                is PdfRow.Gap -> row.height * (style.lyricFontSize / BASE_FONT_SIZE)
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
                        if (seg.chords.isNotEmpty()) {
                            // Resolve horizontal collisions before drawing: a
                            // pushed chord keeps its own name whole but may sit
                            // a character or two right of its lyric column.
                            val charted = seg.chords.map { x0 + it.first * charWidth }
                            val widths = seg.chords.map { chordPaint.measureText(it.second) }
                            // The no-overlap resolver must respect the WRAP
                            // column, not the page margin: a chord pushed right
                            // stops where the text wraps, matching the red
                            // guide rule in the studio.
                            val wrapColumnX = x0 + maxCols * charWidth
                            val xs = resolveChordXs(charted, widths, x0, wrapColumnX)
                            for (entry in seg.chords.withIndex()) {
                                val i = entry.index
                                val name = entry.value.second
                                val x = xs[i]
                                canvas.drawText(name, x, sy + style.chordBaseline, chordPaint)
                                // Same box the tap target uses: half a character of
                                // slack around the glyphs so a near miss still hits.
                                chordHits.add(
                                    ChordHit(
                                        page = pageNum,
                                        name = name,
                                        left = x - charWidth / 2f,
                                        top = sy,
                                        right = x + widths[i] + charWidth / 2f,
                                        bottom = sy + style.chordRowHeight
                                    )
                                )
                            }
                        }
                        if (seg.lyric.isNotEmpty()) {
                            canvas.drawText(seg.lyric, x0, sy + style.lyricBaseline, lyricPaint)
                        }
                        sy += style.chordRowHeight
                    }
                }
                is PdfRow.Plain -> {
                    if (row.isTabLine) {
                        canvas.drawText(row.text, MARGIN_LEFT.toFloat(), y + style.plainRowHeight, lyricPaint)
                    } else {
                        // Same baseline a chorded line's lyric uses, so lyric
                        // text lines up across the whole sheet.
                        canvas.drawText(row.text, MARGIN_LEFT.toFloat(), y + style.lyricBaseline, lyricPaint)
                    }
                }
                is PdfRow.Gap -> Unit
            }
            y += h
            rowsOnPage++
        }

        drawPageFooter(canvas, pageNum, tab, style)
        document.finishPage(page)
        hitMapOut(hitMap)
        return document
    }

    /**
     * Parses UG content into layout rows. A chord-only line ([ch]..[/ch] with
     * no other text) is paired with the following lyric line; inline tags such
     * as "some [ch]Am[/ch] words" become a chord at that exact column.
     */
    internal fun buildRows(content: String, maxCols: Int, theme: PdfTheme = PdfTheme.DEFAULT): List<PdfRow> {
        val rows = mutableListOf<PdfRow>()
        var pendingChords: List<Pair<Int, String>>? = null
        var inTabBlock = false

        fun addPlain(text: String, isTabLine: Boolean = false) {
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
                for ((_, seg) in wrapKeepOffsets(trimmed, maxCols)) rows.add(PdfRow.Plain(seg, isTabLine = inTabBlock))
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
                // The wrap itself is chord-aware: if a chord's name would be
                // split across two rendered lines by the column cut, the break
                // is pulled back before the chord instead, so the chord and the
                // word it sits over stay visibly together.
                val parts = wrapKeepOffsets(lyric, maxCols)
                val adjusted = pullBackChordStraddlingBreaks(chords, parts, maxCols)
                val chordLists = List(parts.size) { mutableListOf<Pair<Int, String>>() }
                for ((off, name) in adjusted) {
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
                    if (idx > 0) addPlain(line.substring(0, idx), isTabLine = true)
                    val tail = line.substring(idx + 6)
                    if (tail.isNotBlank()) addPlain(tail)
                    inTabBlock = false
                }
                lower.contains("[tab]") -> {
                    val idx = lower.indexOf("[tab]") + 5
                    if (idx < line.length) addPlain(line.substring(idx), isTabLine = true)
                    inTabBlock = true
                }
                inTabBlock -> addPlain(line, isTabLine = true)
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
                        inline.isEmpty() -> addPlain(residual, isTabLine = inTabBlock)
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
                    rows.add(PdfRow.Gap(theme.stanzaGap * BASE_FONT_SIZE))
                }
                pendingChords != null -> {
                    addChordLyric(pendingChords!!, line.trimEnd())
                    pendingChords = null
                }
                else -> addPlain(line, isTabLine = inTabBlock)
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

    /**
     * Chord-aware wrapping: keeps a chord name and the word it sits over on
     * the same rendered line.
     *
     * A chord placed near the column limit can end up straddling a wrap: its
     * name would start on rendered line N and continue on line N+1, or the cut
     * can fall inside the very word the chord is anchored to. Since a PDF has
     * no reflow, the chord would be drawn split or detached from its word.
     * Whenever that happens, the break is pulled back to just before the
     * chord's column; the earlier words ride down to the next line with it.
     *
     * Returns the chords with their columns adjusted to the new layout.
     */
    internal fun pullBackChordStraddlingBreaks(
        chords: List<Pair<Int, String>>,
        parts: List<Pair<Int, String>>,
        maxCols: Int
    ): List<Pair<Int, String>> {
        if (parts.size < 2 || chords.isEmpty()) return chords
        val adjusted = chords.toMutableList()
        // Walk the breaks left to right; each pull-back reflows the segments
        // that follow, so the break positions are recomputed after each move.
        var changed = true
        var guard = 0
        while (changed && guard++ < 10) {
            changed = false
            for (b in 0 until parts.size - 1) {
                val nextStart = parts[b + 1].first
                // The chord that would straddle this break: the last one
                // starting before the cut whose name would not fit whole.
                val straddler = adjusted.lastOrNull { (off, name) ->
                    off < nextStart && off + name.length > nextStart
                } ?: continue
                // Re-flow: everything from the straddler on moves down, and
                // words pack from there as the segments allow.
                val moved = reflowFrom(adjusted, parts, b, straddler.first, maxCols)
                if (moved != adjusted) {
                    adjusted.clear(); adjusted.addAll(moved)
                    changed = true
                }
            }
        }
        return adjusted
    }

    /**
     * Moves every chord from [fromColumn] on down to the first position the
     * reflowed layout gives it: the earliest column of the next line's word
     * content, preserving each chord's offset within its own line's tail.
     */
    private fun reflowFrom(
        chords: List<Pair<Int, String>>,
        parts: List<Pair<Int, String>>,
        breakIndex: Int,
        fromColumn: Int,
        maxCols: Int
    ): List<Pair<Int, String>> {
        val out = chords.toMutableList()
        val tail = out.filter { it.first >= fromColumn }
        if (tail.isEmpty()) return out
        out.removeAll { it.first >= fromColumn }
        // First moved chord anchors at the start of the segment after the
        // break; the rest keep their spacing relative to it, clamped so a
        // chord never runs past its segment's columns.
        val anchor = parts.getOrNull(breakIndex + 1)?.first ?: fromColumn
        val base = tail.first().first
        for ((off, name) in tail) {
            val target = (anchor + (off - base)).coerceAtMost((maxCols - name.length).coerceAtLeast(0))
            out.add(target to name)
        }
        return out.sortedBy { it.first }
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
        val footerText = "UG Viewer  |  Page $pageNum  |  Font ${metrics.lyricFontSize.toInt()}pt  |  Source: ${tab.urlWeb}"
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

    /**
     * Writes the document into a folder the user picked with the system folder
     * picker. The tree URI persists across processes, so the grant is taken
     * again here rather than being assumed. Files colliding with an existing
     * name get the usual " (1)" suffix instead of overwriting.
     */
    fun saveToFolder(context: Context, document: PdfDocument, fileName: String, treeUri: android.net.Uri): File {
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        )
        val cleanName = sanitizeFileName(fileName)
        val finalName = uniqueNameIn(resolver, root, cleanName)
        val target = android.provider.DocumentsContract.createDocument(
            resolver, root, "application/pdf", finalName
        ) ?: throw Exception("Failed to create file in the selected folder")
        resolver.openOutputStream(target)?.use { outputStream ->
            document.writeTo(outputStream)
        } ?: throw Exception("Failed to open output stream")
        return File(File(cleanName).name)
    }

    /** Finds a display name not already taken, appending " (n)" before the extension. */
    private fun uniqueNameIn(resolver: android.content.ContentResolver, folder: android.net.Uri, fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            folder,
            android.provider.DocumentsContract.getTreeDocumentId(folder)
        )
        val taken = mutableSetOf<String>()
        try {
            resolver.query(childrenUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) taken.add(cursor.getString(0))
            }
        } catch (e: Exception) {
            // If the listing fails, keep the requested name; SAF createDocument
            // deconflicts on its own for providers that must.
            return fileName
        }
        if (fileName !in taken) return fileName
        var n = 1
        while ("$base ($n)$ext" in taken) n++
        return "$base ($n)$ext"
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
