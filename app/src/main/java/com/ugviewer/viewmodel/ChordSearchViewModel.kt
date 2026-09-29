package com.ugviewer.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ugviewer.chord.ChordLibrary
import com.ugviewer.chord.ChordShape
import com.ugviewer.chord.ChordShapePdfGenerator
import com.ugviewer.chord.ChordVoiceQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Everything the chord print screen needs to draw itself. */
data class ChordSearchUiState(
    val query: String = "",
    val roots: List<String> = emptyList(),
    val matchingNames: List<String> = emptyList(),
    val shapeCount: Int = 0,
    val pages: List<Bitmap> = emptyList(),
    val isRendering: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val savedFileName: String? = null
)

/**
 * Backs the chord-shape search bar: resolves the query against the bundled
 * chords-db asset and renders the result as a printable PDF.
 */
class ChordSearchViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * Pages are rasterised at 1.5x the A4 point size. That stays sharp while
         * keeping a 12-page chord book near 54 MB of bitmaps instead of the ~150 MB
         * a full-resolution render would need on a mid-range device.
         */
        private const val RENDER_SCALE = 1.5f
    }

    private val library = ChordLibrary.get(application)
    private val _uiState = MutableStateFlow(
        ChordSearchUiState(roots = library.rootNotes)
    )
    val uiState: StateFlow<ChordSearchUiState> = _uiState.asStateFlow()

    private var shapes: List<ChordShape> = emptyList()
    private var generation = 0

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        if (value.isBlank()) {
            generation++
            shapes = emptyList()
            _uiState.update {
                it.copy(shapeCount = 0, matchingNames = emptyList(), pages = emptyList(), isRendering = false)
            }
        }
    }

    /** Runs the search and (re)renders the PDF preview. */
    fun submit(rawQuery: String = _uiState.value.query) {
        val query = rawQuery.trim()
        val matches = library.search(query)
        if (matches.isEmpty()) {
            generation++
            shapes = emptyList()
            _uiState.update {
                it.copy(
                    query = query,
                    shapeCount = 0,
                    matchingNames = emptyList(),
                    pages = emptyList(),
                    isRendering = false,
                    message = "No chords found for \"$query\". Try a root note such as C."
                )
            }
            return
        }

        val token = ++generation
        shapes = matches
        _uiState.update {
            it.copy(
                query = query,
                shapeCount = matches.size,
                matchingNames = library.distinctNames(matches),
                pages = emptyList(),
                isRendering = true,
                message = null
            )
        }

        viewModelScope.launch {
            val title = titleFor(query, matches)
            val pages = withContext(Dispatchers.Default) {
                renderPages(matches, title, library.fretsOnChord)
            }
            // A newer query already started rendering: drop these bitmaps so the
            // stale pages are never shown.
            if (token != generation) {
                pages.forEach { it.recycle() }
                return@launch
            }
            _uiState.update { it.copy(pages = pages, isRendering = false) }
        }
    }

    /**
     * Runs a spoken phrase. [ChordVoiceQuery] turns the wording into something the
     * library understands, so "show me a C chord" lands on the same shapes as "C".
     */
    fun submitSpoken(phrase: String) {
        val query = ChordVoiceQuery.parse(phrase)
        if (query == null) {
            _uiState.update {
                it.copy(message = "Heard \"$phrase\", but no chord name in it. Try \"show me a C chord\".")
            }
            return
        }
        submit(query)
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun savePdf() {
        val current = shapes
        if (current.isEmpty()) return
        val query = _uiState.value.query
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ChordShapePdfGenerator.generateChordPdf(
                        context = getApplication(),
                        shapes = current,
                        title = titleFor(query, current),
                        stringLabels = library.stringLabels,
                        fretsOnChord = library.fretsOnChord
                    )
                }
            }
            result.onSuccess { file ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        message = "Saved ${file.name} to Downloads/UG Viewer",
                        savedFileName = file.name
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isSaving = false, message = error.message ?: "Save failed") }
            }
        }
    }

    private fun titleFor(query: String, matches: List<ChordShape>): String {
        val names = matches.map { it.name }.distinct()
        return if (names.size == 1) names.first() else "$query (${names.size} chords)"
    }

    private fun renderPages(
        shapes: List<ChordShape>,
        title: String,
        fretsOnChord: Int
    ): List<Bitmap> {
        val bytes = ChordShapePdfGenerator.generateChordPdfToBytes(
            shapes = shapes,
            title = title,
            stringLabels = library.stringLabels,
            fretsOnChord = fretsOnChord
        )
        return rasterise(bytes, RENDER_SCALE)
    }

    private fun rasterise(pdf: ByteArray, scale: Float): List<Bitmap> {
        // PdfRenderer needs a real file descriptor, so spill the bytes to a temp
        // file rather than holding a second copy of the PDF in memory.
        val tempFile = File.createTempFile("chords", ".pdf")
        return try {
            FileOutputStream(tempFile).use { it.write(pdf) }
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            try {
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(android.graphics.Color.WHITE)
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
}
