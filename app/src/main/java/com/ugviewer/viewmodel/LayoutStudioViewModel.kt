package com.ugviewer.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ugviewer.api.TabResult
import com.ugviewer.api.UGApiClient
import com.ugviewer.util.PdfGenerator
import com.ugviewer.util.PdfThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Backs the Layout Studio: edits the six layout knobs with live, debounced
 * re-render of a demo sheet, and persists the result as the app-wide default.
 */
class LayoutStudioViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** Raster scale for the live preview: sharp enough to judge spacing. */
        private const val RENDER_SCALE = 1.5f

        /** Milliseconds a slider must be still before a re-render runs. */
        private const val RENDER_DEBOUNCE_MS = 250L
    }

    /** A failed demo load or render shows this in the preview area. */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var isLoadingDemo by mutableStateOf(true)
        private set

    /** The demo sheet's content, loaded once. */
    private var demo: TabResult? = null

    // The seven knobs. Sizes start at the Small preset: it is the layout the
    // sheets read best at, and the shipped 32pt start only showed off how
    // badly centred chords collide with the line above.
    var chordSize by mutableStateOf(PdfGenerator.CHORD_SMALL_PT)
    var lyricSize by mutableStateOf(PdfGenerator.LYRIC_SMALL_PT)
    var chordLinePitch by mutableStateOf(PdfGenerator.PdfTheme.DEFAULT.chordLinePitch)
    var plainRowHeight by mutableStateOf(PdfGenerator.PdfTheme.DEFAULT.plainRowHeight)
    var stanzaGap by mutableStateOf(PdfGenerator.PdfTheme.DEFAULT.stanzaGap)
    var wrapFraction by mutableStateOf(PdfGenerator.PdfTheme.DEFAULT.wrapFraction)

    var previewPages by mutableStateOf<List<Bitmap>>(emptyList())
        private set
    var isRendering by mutableStateOf(false)
        private set
    var hasSavedLayout by mutableStateOf(false)
        private set

    private var renderJob: Job? = null

    init {
        hasSavedLayout = PdfThemeStore.load(application) != null
        loadDemo()
    }

    /** Fetches a real chord chart to preview against, once, at startup. */
    private fun loadDemo() {
        isLoadingDemo = true
        viewModelScope.launch {
            try {
                val api = UGApiClient()
                val response = withContext(Dispatchers.IO) {
                    api.search("Pigs on the Wing Pink Floyd")
                }
                val first = response.tabs.firstOrNull { it.type.equals("Chords", ignoreCase = true) }
                    ?: response.tabs.firstOrNull()
                demo = if (first == null) {
                    errorMessage = "Could not reach Ultimate Guitar; showing the built-in demo song."
                    builtInDemo()
                } else {
                    withContext(Dispatchers.IO) { api.getTabById(first.id) }
                }
            } catch (e: Exception) {
                demo = builtInDemo()
                errorMessage = "Offline: showing the built-in demo song."
            } finally {
                isLoadingDemo = false
                scheduleRender()
            }
        }
    }

    /**
     * The offline stand-in for the demo chart: Pigs on the Wing's chord
     * progression and line shape (short lines, one long wrapper, two-chord
     * lines, a section break), with placeholder words so no lyrics are
     * embedded in the app. When online, the real chart is fetched instead.
     */
    private fun builtInDemo(): TabResult = TabResult(
        id = -1L,
        songName = "Pigs on the Wing (offline demo)",
        artistName = "Pink Floyd",
        type = "Chords",
        content = """
            [ch]C[/ch]
            A short opening line to set the tone here
            [ch]F[/ch]          [ch]C[/ch]
            A balanced second line follows it
            [ch]G[/ch]
            This line runs long enough to wrap once the font sizes grow up
            [ch]C[/ch]
            Occasionally a medium-length line wanders by
            [ch]G[/ch]
            Wondering how the wrap column treats this one
            [ch]D7[/ch]       [ch]C[/ch]
            And watching for pigs on the wing

            [ch]C[/ch]
            The second verse keeps the rhythm moving along
            [ch]F[/ch]              [ch]C[/ch]
            With the same shape the first one had
            [ch]G[/ch]
            Dogs and sheep may argue but the pigs fly home
            [ch]C[/ch]
            When the whole thing settles gently down
        """.trimIndent(),
        applicature = emptyList()
    )

    private val theme: PdfGenerator.PdfTheme
        get() = PdfGenerator.PdfTheme(chordLinePitch, plainRowHeight, stanzaGap, wrapFraction)

    /** Queues a re-render; rapid slider moves collapse into one render. */
    fun scheduleRender() {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(RENDER_DEBOUNCE_MS)
            val source = demo ?: return@launch
            isRendering = true
            try {
                val format = PdfGenerator.PdfFormat.Custom(chordSize, lyricSize)
                val bytes = withContext(Dispatchers.Default) {
                    PdfGenerator.generatePdfToBytes(source, null, format, theme)
                }
                val pages = withContext(Dispatchers.IO) { rasterise(bytes) }
                // Draw the wrap guide on every page: a red rule where lines
                // wrap, so the slider's effect is visible without guessing.
                val guided = pages.map { drawWrapGuide(it, theme.wrapFraction) }
                previewPages = guided
            } catch (e: Exception) {
                errorMessage = "Render failed: ${e.message}"
            } finally {
                isRendering = false
            }
        }
    }

    /**
     * Draws the wrap guide onto [page]: a vertical red line at the column
     * where text wraps ([PdfGenerator.PdfTheme.wrapFraction] of the usable
     * width, measured from the left margin). Purely visual — drawn on the
     * preview bitmaps only, never in a saved PDF.
     */
    private fun drawWrapGuide(page: Bitmap, wrapFraction: Float): Bitmap {
        val canvas = android.graphics.Canvas(page)
        val scale = page.width / 892f * 1.5f // page.width is already RENDER_SCALE * 595
        val x = (PdfGenerator.MARGIN_LEFT +
            (PdfGenerator.PAGE_WIDTH - 2 * PdfGenerator.MARGIN_LEFT) * wrapFraction) * scale
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(160, 229, 57, 53)
            strokeWidth = 1.6f * scale
            isAntiAlias = true
        }
        canvas.drawLine(
            x,
            PdfGenerator.MARGIN_TOP.toFloat() * scale,
            x,
            (PdfGenerator.PAGE_HEIGHT - PdfGenerator.MARGIN_BOTTOM).toFloat() * scale,
            paint
        )
        return page
    }

    private fun rasterise(pdf: ByteArray): List<Bitmap> {
        val tempFile = File.createTempFile("studio", ".pdf")
        return try {
            FileOutputStream(tempFile).use { it.write(pdf) }
            val pfd = android.os.ParcelFileDescriptor.open(tempFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            try {
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            (page.width * RENDER_SCALE).toInt().coerceAtLeast(1),
                            (page.height * RENDER_SCALE).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            } finally {
                renderer.close()
                pfd.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    /** Persists the current knobs as the layout every future sheet uses. */
    fun saveAsDefault() {
        val app = getApplication<Application>()
        PdfThemeStore.save(app, theme, chordSize, lyricSize)
        hasSavedLayout = true
    }

    /** A sensible starting name for the save prompt: "32pt · Snug" style. */
    fun suggestedLayoutName(): String = "${chordSize.toInt()}pt/${lyricSize.toInt()}pt"

    /**
     * Saves the current knobs under [name]: set as the app-wide default AND
     * listed in the viewer's PDF format menu under that name.
     */
    fun saveNamedLayout(name: String) {
        val app = getApplication<Application>()
        PdfThemeStore.save(app, theme, chordSize, lyricSize, name)
        PdfThemeStore.addToNamedList(app, name)
        hasSavedLayout = true
    }

    /** Saved layouts by name, for the viewer's format menu. */
    fun namedLayouts(): List<String> = PdfThemeStore.namedList(getApplication())

    /** Throws away the saved layout; sheets return to the shipped defaults. */
    fun resetToShipped() {
        val app = getApplication<Application>()
        PdfThemeStore.clear(app)
        hasSavedLayout = false
        chordSize = PdfGenerator.CHORD_SMALL_PT
        lyricSize = PdfGenerator.LYRIC_SMALL_PT
        val d = PdfGenerator.PdfTheme.DEFAULT
        chordLinePitch = d.chordLinePitch
        plainRowHeight = d.plainRowHeight
        stanzaGap = d.stanzaGap
        wrapFraction = d.wrapFraction
        scheduleRender()
    }
}
