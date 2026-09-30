package com.ugviewer.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ugviewer.api.TabResult
import com.ugviewer.api.UGApiClient
import com.ugviewer.chord.ChordLibrary
import com.ugviewer.chord.ChordShapePdfGenerator
import com.ugviewer.util.PdfGenerator
import com.ugviewer.util.PdfThemeStore
import com.ugviewer.util.YouTubeHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class TabViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val api = UGApiClient()

    /** The chords-db library behind the Chord Shape Search screen; same source for the popup. */
    private val chordBook = ChordLibrary.get(application)

    var tab by mutableStateOf<TabResult?>(null)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var fontSize by mutableStateOf(14f)
    var isGeneratingPdf by mutableStateOf(false)
    var pdfSuccess by mutableStateOf<String?>(null)
    var isFetchingYouTube by mutableStateOf(false)
    var youtubeUrl by mutableStateOf<String?>(null)

    var isChordType by mutableStateOf(false)
    var pdfPages by mutableStateOf<List<Bitmap>>(emptyList())
    var isRenderingPdf by mutableStateOf(false)

    /**
     * Body sizes for the preview and for saving. The app's default is the Small
     * preset: the Studio's Reset means "use Small", and a tuned layout only
     * takes over once it has been saved.
     */
    var pdfFormat by mutableStateOf<PdfGenerator.PdfFormat>(PdfGenerator.PdfFormat.Small)

    /** Spacing theme for the generated sheet; starts from the saved layout. */
    var pdfTheme by mutableStateOf(PdfGenerator.PdfTheme.DEFAULT)
        private set

    /** Human-readable name of the folder PDFs are saved into. */
    var pdfSaveFolder by mutableStateOf<String?>(null)

    /** Set while the save-filename panel is up. */
    var showSavePrompt by mutableStateOf(false)
    var pendingSaveFileName by mutableStateOf<String?>(null)

    private var pdfBytes: ByteArray? = null

    /**
     * Where every chord name sits on each rendered preview page, in PDF points.
     * Built while the PDF is generated so a tap on the preview can find the
     * chord it landed on without any bitmap pixel peeping.
     */
    var chordHitMap by mutableStateOf(PdfGenerator.ChordHitMap.EMPTY)
        private set

    /** Set while the tapped-chord popup is up. */
    var showChordPopup by mutableStateOf(false)
        private set
    var popupChordName by mutableStateOf<String?>(null)
        private set

    /**
     * The popup's pictures: pages from the same chord-book renderer the Chord
     * Shape Search screen prints with, so the popup reads exactly like the
     * saved PDF — white paper, black ink.
     */
    var popupChordPages by mutableStateOf<List<Bitmap>>(emptyList())
        private set
    var popupChordLoading by mutableStateOf(false)
        private set

    /** Rendered pages per chord name; the oldest entry falls out past eight. */
    private val popupPagesCache = object : LinkedHashMap<String, List<Bitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Bitmap>>): Boolean =
            size > 8
    }
    private var popupRenderJob: Job? = null

    private var renderJob: Job? = null
    private var saveTreeUri: Uri? = null

    init {
        applySavedLayout(force = true)
    }

    /** The Studio layout this view last adopted, so a newer save can be spotted. */
    private var adoptedLayout: PdfThemeStore.Saved? = null

    /**
     * Adopts the Studio's saved layout: its two body sizes and its spacing
     * theme, which together are how the sheets print.
     *
     * This view is activity-scoped, so it outlives the Studio: without
     * re-reading the store here, a layout saved after the first song was
     * opened would never reach the sheets that follow. Only a layout that
     * actually changed is adopted, so a format picked in this viewer's own
     * format bar survives until the user saves a newer layout in the Studio.
     */
    private fun applySavedLayout(force: Boolean = false) {
        val saved = PdfThemeStore.load(getApplication())
        if (saved == null) {
            // Nothing stored yet — a first run, not a Reset: the shipped Small
            // layout the format already starts on is the right answer, so only
            // a discarded saved layout has anything to undo here.
            if (force || adoptedLayout == null) return
            adoptedLayout = PdfThemeStore.SHIPPED
            pdfTheme = PdfThemeStore.SHIPPED.theme
            pdfFormat = formatFor(PdfThemeStore.SHIPPED)
            return
        }
        if (!force && saved == adoptedLayout) return
        adoptedLayout = saved
        pdfTheme = saved.theme
        pdfFormat = formatFor(saved)
    }

    /**
     * The format that prints [saved]. A saved layout only has point sizes to
     * carry, so it needs Custom — except when those sizes are the shipped Small
     * ones, which is what the Studio's Reset stores: keeping the named preset
     * then leaves the format bar reading "Small" rather than "Custom".
     */
    private fun formatFor(saved: PdfThemeStore.Saved): PdfGenerator.PdfFormat =
        if (PdfThemeStore.isShipped(saved)) {
            PdfGenerator.PdfFormat.Small
        } else {
            PdfGenerator.PdfFormat.Custom(saved.chordSize, saved.lyricSize)
        }

    companion object {
        /** Matches the chord search screen's raster scale, so the pictures look the same. */
        private const val POPUP_RENDER_SCALE = 1.5f
    }

    fun loadTab(tabId: Long) {
        isLoading = true
        errorMessage = null
        // Pick up a Studio layout saved since the last song before rendering,
        // so the preview the user is about to see is the one they saved.
        applySavedLayout()
        // Drop the old previews by reference only; recycling here could race a
        // frame that is still drawing them. GC reclaims them safely.
        pdfPages = emptyList()
        pdfBytes = null
        chordHitMap = PdfGenerator.ChordHitMap.EMPTY
        youtubeUrl = null

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.getTabById(tabId)
                }
                tab = result
                val type = result.type.lowercase()
                val hasChordMarkers = result.content.contains("[ch]", ignoreCase = true)
                isChordType = type.contains("chord") || hasChordMarkers

                // Fetch the matching YouTube video in the background so the
                // viewer renders immediately and the link appears when ready.
                fetchYouTubeLink(result)

                if (isChordType) {
                    generatePdfPreview(result)
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load tab: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    private fun fetchYouTubeLink(tabResult: TabResult) {
        if (tabResult.artistName.isBlank() || tabResult.songName.isBlank()) return
        isFetchingYouTube = true
        viewModelScope.launch {
            try {
                youtubeUrl = YouTubeHelper.findSongVideo(tabResult.artistName, tabResult.songName)
            } finally {
                isFetchingYouTube = false
            }
        }
    }

    /** Opens the matched YouTube video in the YouTube app (or browser). */
    fun openYouTube(context: Context) {
        val url = youtubeUrl ?: return
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            errorMessage = "Could not open YouTube: ${e.message}"
        }
    }

    private fun generatePdfPreview(tabResult: TabResult) {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            isRenderingPdf = true
            try {
                val link = youtubeUrl
                val format = pdfFormat
                var hitMap = PdfGenerator.ChordHitMap.EMPTY
                val bytes = withContext(Dispatchers.IO) {
                    PdfGenerator.generatePdfToBytes(tabResult, link, format, pdfTheme) { hitMap = it }
                }
                pdfBytes = bytes
                val pages = withContext(Dispatchers.IO) { renderPdfPages(bytes) }
                // A newer font size may have been picked while we were rendering.
                if (format != pdfFormat) return@launch
                chordHitMap = hitMap
                pdfPages = pages
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "PDF preview failed: ${e.message}"
            } finally {
                isRenderingPdf = false
            }
        }
    }

    /** Re-renders the PDF preview with the chosen format. */
    fun updatePdfFormat(format: PdfGenerator.PdfFormat, theme: PdfGenerator.PdfTheme? = null) {
        var changed = format != pdfFormat
        pdfFormat = format
        // A named layout brings its own spacing theme along.
        if (theme != null && theme != pdfTheme) {
            pdfTheme = theme
            changed = true
        }
        if (changed) tab?.takeIf { isChordType }?.let { generatePdfPreview(it) }
    }

    /** Saved studio layouts by name, shown in the format menu. */
    fun namedLayouts(): List<String> = PdfThemeStore.namedList(getApplication())

    /** The stored theme for a named layout, or null if it vanished. */
    fun themeForNamed(name: String): PdfGenerator.PdfTheme? =
        PdfThemeStore.load(getApplication())
            ?.takeIf { PdfThemeStore.savedName(getApplication()) == name }
            ?.theme

    /** The chord under a tap on preview page [page] at PDF-point coordinates, or null. */
    fun chordAt(page: Int, x: Float, y: Float): PdfGenerator.ChordHit? =
        chordHitMap.chordAt(page, x, y)

    /**
     * Slash chords ("G/B") are looked up under their plain name; everything
     * else goes through untouched.
     */
    private fun normalizeChordName(name: String): String {
        val slash = name.indexOf('/')
        return if (slash > 0) name.substring(0, slash).trim() else name.trim()
    }

    /** Opens the quick-look popup for the chord tapped in the preview. */
    fun showChordPopup(name: String) {
        popupChordName = name
        showChordPopup = true

        // The pictures come from the same chord-book search the Chord Shape
        // Search screen runs, so tapping Cadd9 shows Cadd9's own voicings —
        // the same pages that search would print. Rendered per chord name and
        // cached, so every tap on the same chord reuses the last tap's pages.
        popupRenderJob?.cancel()
        val query = normalizeChordName(name)
        if (query.isEmpty()) {
            popupChordPages = emptyList()
            popupChordLoading = false
            return
        }
        val cached = popupPagesCache[query]
        if (cached != null) {
            popupChordPages = cached
            popupChordLoading = false
            return
        }
        popupChordPages = emptyList()
        popupChordLoading = true
        popupRenderJob = viewModelScope.launch {
            val pages = withContext(Dispatchers.Default) {
                runCatching {
                    val shapes = chordBook.search(query)
                    if (shapes.isEmpty()) return@runCatching emptyList<Bitmap>()
                    renderPdfPages(
                        ChordShapePdfGenerator.generateChordPdfToBytes(
                            shapes = shapes,
                            title = query,
                            stringLabels = chordBook.stringLabels,
                            fretsOnChord = chordBook.fretsOnChord
                        ),
                        scale = POPUP_RENDER_SCALE
                    )
                }.getOrDefault(emptyList())
            }
            if (pages.isNotEmpty()) popupPagesCache[query] = pages
            popupChordPages = pages
            popupChordLoading = false
        }
    }

    fun dismissChordPopup() {
        showChordPopup = false
        popupRenderJob?.cancel()
        popupChordLoading = false
    }

    private fun renderPdfPages(pdfData: ByteArray, scale: Float = 2f): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        val tempFile = File.createTempFile("preview", ".pdf")
        try {
            FileOutputStream(tempFile).use { it.write(pdfData) }
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)

            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmaps.add(bitmap)
                page.close()
            }

            renderer.close()
            pfd.close()
        } finally {
            tempFile.delete()
        }
        return bitmaps
    }

    /** Opens the save panel pre-filled with the default name for this sheet. */
    fun requestSavePdf() {
        val currentTab = tab ?: return
        pendingSaveFileName = PdfGenerator.sanitizeFileName("${currentTab.artistName} - ${currentTab.songName}.pdf")
        showSavePrompt = true
    }

    fun dismissSavePrompt() {
        showSavePrompt = false
    }

    /** Remembers the folder the user picked; the name sticks between sheets. */
    fun setSaveFolder(context: Context, treeUri: Uri) {
        saveTreeUri = treeUri
        pdfSaveFolder = folderDisplayName(context, treeUri)
    }

    private fun folderDisplayName(context: Context, treeUri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            resolver.query(
                treeUri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun confirmSavePdf(context: Context) {
        val currentTab = tab ?: return
        val treeUri = saveTreeUri ?: return
        val fileName = pendingSaveFileName ?: return
        isGeneratingPdf = true
        pdfSuccess = null
        errorMessage = null

        viewModelScope.launch {
            try {
                // Make sure we have a YouTube link for the PDF; wait for the
                // in-flight lookup or run one now if it hasn't finished/started.
                val link = youtubeUrl ?: YouTubeHelper.findSongVideo(
                    currentTab.artistName, currentTab.songName
                )
                if (youtubeUrl == null) youtubeUrl = link

                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val document = PdfGenerator.buildDocumentForSave(currentTab, link, pdfFormat, pdfTheme)
                        try {
                            PdfGenerator.saveToFolder(context, document, fileName, treeUri)
                        } finally {
                            document.close()
                        }
                    }
                }
                result.fold(
                    onSuccess = { file ->
                        pdfSuccess = "Saved ${file.name} to ${pdfSaveFolder ?: "the selected folder"}"
                        showSavePrompt = false
                    },
                    onFailure = { error ->
                        errorMessage = "PDF save failed: ${error.message}"
                    }
                )
            } catch (e: Exception) {
                errorMessage = "PDF save failed: ${e.message}"
            } finally {
                isGeneratingPdf = false
            }
        }
    }

    fun generatePdf(context: Context) {
        // Text tabs keep the one-tap save: straight to Downloads, no panel.
        val currentTab = tab ?: return
        isGeneratingPdf = true
        pdfSuccess = null
        errorMessage = null

        viewModelScope.launch {
            try {
                val link = youtubeUrl ?: YouTubeHelper.findSongVideo(
                    currentTab.artistName, currentTab.songName
                )
                if (youtubeUrl == null) youtubeUrl = link

                val file = withContext(Dispatchers.IO) {
                    PdfGenerator.generatePdf(context, currentTab, link, pdfFormat, pdfTheme)
                }
                pdfSuccess = "Saved to Downloads/UG Viewer/${file.name}"
            } catch (e: Exception) {
                errorMessage = "PDF save failed: ${e.message}"
            } finally {
                isGeneratingPdf = false
            }
        }
    }

    fun increaseFontSize() {
        if (fontSize < 32f) fontSize += 2f
    }

    fun decreaseFontSize() {
        if (fontSize > 8f) fontSize -= 2f
    }

    override fun onCleared() {
        super.onCleared()
        renderJob?.cancel()
        popupRenderJob?.cancel()
        // Safe to free here: the screen has been disposed, nothing is drawing.
        pdfPages.forEach { it.recycle() }
        pdfPages = emptyList()
    }
}
