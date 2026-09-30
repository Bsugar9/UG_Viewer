package com.ugviewer.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ugviewer.api.SearchTab
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

    /** Controls whether the initial "Default" or "New" popup is showing. */
    var showPromptDialog by mutableStateOf(true)

    /** Controls whether the search dialog for picking a new test song is open. */
    var showSearchDialog by mutableStateOf(false)

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<SearchTab>>(emptyList())
        private set
    var isSearchingSongs by mutableStateOf(false)
        private set

    /** The demo sheet's content, loaded once. */
    private var demo: TabResult? = null

    val currentSongTitle: String
        get() = demo?.let { "${it.songName} (${it.artistName})" }
            ?: PdfThemeStore.loadDefaultTestSong(getApplication())?.let { "${it.songName} (${it.artistName})" }
            ?: "Pigs on the Wing (Pink Floyd)"

    // The six knobs. These start at the shipped values and are then pointed at
    // the last saved layout in [init], so the Studio always opens on the layout
    // that is actually in force.
    var chordSize by mutableStateOf(PdfGenerator.CHORD_SMALL_PT)
    var lyricSize by mutableStateOf(PdfGenerator.LYRIC_SMALL_PT)
    var chordOffset by mutableStateOf(PdfGenerator.PdfTheme.DEFAULT.chordOffset)
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
        // Open on the last save, not on the shipped values: this Studio view is
        // the editor for the app-wide default, so it has to start from that
        // default rather than from a blank sheet the user has to re-tune.
        PdfThemeStore.load(application)?.let { saved ->
            // The shipped Small layout is a reset, not a tuned save, so it must
            // not report the Studio as holding a saved layout.
            hasSavedLayout = !PdfThemeStore.isShipped(saved)
            applyLayout(saved.theme, saved.chordSize, saved.lyricSize)
        }
        loadDefaultSong()
    }

    /**
     * Points every knob at one layout: the values the Studio shows, previews and
     * saves. Shared by loading the last save and by Reset, which hands back the
     * shipped sizes along with the shipped spacing.
     */
    private fun applyLayout(theme: PdfGenerator.PdfTheme, chord: Float, lyric: Float) {
        chordSize = chord
        lyricSize = lyric
        chordOffset = theme.chordOffset
        plainRowHeight = theme.plainRowHeight
        stanzaGap = theme.stanzaGap
        wrapFraction = theme.wrapFraction
    }

    /** Loads the default test song from preferences or network fallback. */
    fun loadDefaultSong() {
        isLoadingDemo = true
        showPromptDialog = false
        viewModelScope.launch {
            try {
                val saved = PdfThemeStore.loadDefaultTestSong(getApplication())
                if (saved != null) {
                    demo = saved
                } else {
                    val api = UGApiClient()
                    val response = withContext(Dispatchers.IO) {
                        api.search("Pigs on the Wing Pink Floyd")
                    }
                    val first = response.tabs.firstOrNull { it.type.equals("Chords", ignoreCase = true) }
                        ?: response.tabs.firstOrNull()
                    demo = if (first == null) {
                        builtInDemo()
                    } else {
                        withContext(Dispatchers.IO) { api.getTabById(first.id) }
                    }
                    demo?.let { PdfThemeStore.saveDefaultTestSong(getApplication(), it) }
                }
            } catch (e: Exception) {
                demo = builtInDemo()
                errorMessage = "Offline: showing the built-in demo song."
            } finally {
                renderCurrentDemo()
                isLoadingDemo = false
            }
        }
    }

    /** Searches Ultimate Guitar for a song to use as the test PDF. */
    fun searchSongs(query: String) {
        if (query.isBlank()) return
        isSearchingSongs = true
        viewModelScope.launch {
            try {
                val api = UGApiClient()
                val response = withContext(Dispatchers.IO) {
                    api.search(query)
                }
                searchResults = response.tabs
            } catch (e: Exception) {
                errorMessage = "Search failed: ${e.message}"
            } finally {
                isSearchingSongs = false
            }
        }
    }

    /** Selects a new song from search, sets it as default, and renders preview. */
    fun selectNewSong(selectedTab: com.ugviewer.api.SearchTab) {
        isLoadingDemo = true
        showSearchDialog = false
        showPromptDialog = false
        viewModelScope.launch {
            try {
                val api = UGApiClient()
                val fullTab = withContext(Dispatchers.IO) { api.getTabById(selectedTab.id) }
                demo = fullTab
                PdfThemeStore.saveDefaultTestSong(getApplication(), fullTab)
            } catch (e: Exception) {
                errorMessage = "Failed to load selected song: ${e.message}"
            } finally {
                renderCurrentDemo()
                isLoadingDemo = false
            }
        }
    }

    private suspend fun renderCurrentDemo() {
        val source = demo ?: builtInDemo()
        try {
            val format = PdfGenerator.PdfFormat.Custom(chordSize, lyricSize)
            val bytes = withContext(Dispatchers.Default) {
                PdfGenerator.generatePdfToBytes(source, null, format, theme)
            }
            val pages = withContext(Dispatchers.IO) { rasterise(bytes) }
            val guided = pages.map { drawWrapGuide(it, theme.wrapFraction) }
            previewPages = guided
        } catch (e: Exception) {
            errorMessage = "Render failed: ${e.message}"
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

    /** The line pitch is fixed: the Studio only tunes the other four rhythm knobs. */
    private val theme: PdfGenerator.PdfTheme
        get() = PdfGenerator.PdfTheme(
            plainRowHeight = plainRowHeight,
            stanzaGap = stanzaGap,
            wrapFraction = wrapFraction,
            chordOffset = chordOffset
        )

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
        val canvas = Canvas(page)
        val scale = page.width / 892f * 1.5f
        val x = (PdfGenerator.MARGIN_LEFT +
            (PdfGenerator.PAGE_WIDTH - 2 * PdfGenerator.MARGIN_LEFT) * wrapFraction) * scale
        val paint = Paint().apply {
            color = Color.argb(160, 229, 57, 53)
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
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            try {
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            (page.width * RENDER_SCALE).toInt().coerceAtLeast(1),
                            (page.height * RENDER_SCALE).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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

    /**
     * Throws the tuned layout away: sheets, and this Studio's own knobs, go
     * back to the shipped Small preset.
     *
     * The Small layout is saved rather than the store cleared, so the viewer
     * receives it the same way it receives a save — and so the chosen test song
     * and the named-layout list survive the reset.
     */
    fun resetToShipped() {
        val app = getApplication<Application>()
        val shipped = PdfThemeStore.SHIPPED
        PdfThemeStore.save(app, shipped.theme, shipped.chordSize, shipped.lyricSize)
        hasSavedLayout = false
        applyLayout(shipped.theme, shipped.chordSize, shipped.lyricSize)
        scheduleRender()
    }
}
