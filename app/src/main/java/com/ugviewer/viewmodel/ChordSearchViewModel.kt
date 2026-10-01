package com.ugviewer.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ugviewer.chord.ChordLibrary
import com.ugviewer.chord.ChordPitch
import com.ugviewer.chord.ChordPlayer
import com.ugviewer.chord.ChordShape
import com.ugviewer.chord.ChordShapePdfGenerator
import com.ugviewer.chord.ChordVoiceQuery
import com.ugviewer.util.PdfGenerator
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

    /**
     * Where each chord name sits on the rendered pages, so a tap can find the
     * shape under the finger. Produced alongside the PDF rather than guessed from
     * the layout afterwards, so it cannot drift out of step with the drawing.
     */
    @Volatile
    private var hitMap: PdfGenerator.ChordHitMap = PdfGenerator.ChordHitMap.EMPTY

    /**
     * The shapes the current hit map was built from, in the order they were
     * drawn. Kept so a hit can be turned back into the exact voicing it stands
     * for.
     */
    @Volatile
    private var renderedShapes: List<ChordShape> = emptyList()

    /** The chord sounded by the last tap, and whether it is still playing. */
    @Volatile
    private var player: ChordPlayer? = null

    @Volatile
    private var lastPlayedShape: ChordShape? = null

    /** True while a tapped chord is sounding, so the button matches the audio. */
    @Volatile
    var isChordPlaying: Boolean = false
        private set

    /** The chord the play/stop button would act on, or null if nothing yet. */
    @Volatile
    var lastPlayedChordName: String? = null
        private set

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
            // Rendered into locals rather than a nullable var: withContext hands
            // back through a suspension point, and the compiler cannot promise
            // the var was assigned across it.
            var map = PdfGenerator.ChordHitMap.EMPTY
            val rendered = withContext(Dispatchers.Default) {
                renderPages(matches, title, library.fretsOnChord) { map = it }
            }
            // A newer query already started rendering: drop these bitmaps so the
            // stale pages are never shown.
            if (token != generation) {
                rendered.forEach { it.recycle() }
                return@launch
            }
            hitMap = map
            renderedShapes = matches
            _uiState.update { it.copy(pages = rendered, isRendering = false) }
        }
    }

    /**
     * The chord under a tap on preview page [page] at PDF-point coordinates, or
     * null when the tap landed in the margin or a gap between cells.
     */
    fun chordAt(page: Int, x: Float, y: Float): PdfGenerator.ChordHit? =
        hitMap.chordAt(page, x, y)

    /**
     * Sounds the shape drawn at a tap on preview page [page].
     *
     * The exact shape, not a fresh lookup of its name: a family page carries
     * several voicings of the same chord, and the one the user pointed at is the
     * one they want to hear. The generator records one hit per shape in the order
     * it drew them, so a hit's own position in the list is the shape it belongs
     * to — no second identifier to keep in step with the drawing.
     */
    fun playChordAt(page: Int, x: Float, y: Float) {
        val hit = chordAt(page, x, y) ?: return
        val index = hitMap.hits.indexOfFirst { it === hit }
        val shape = renderedShapes.getOrNull(index) ?: return
        playChordShape(shape)
    }

    /**
     * Sounds the shape whose name is at the tap, in the same standard tuning the
     * diagrams are drawn in, and keeps it going until [stopChord] or another
     * chord replaces it.
     *
     * A bare root is looked up under its own name, which is how the search screen
     * resolves it, so "C" and "Cm" each play the shape the user actually tapped
     * rather than a different voicing of the same root.
     */
    fun playChordShape(shape: ChordShape) {
        val p = player ?: ChordPlayer().also { player = it }
        val pitches = ChordPitch.soundingPitches(shape.position, ChordPitch.STANDARD_TUNING)
        if (pitches.isEmpty()) return
        lastPlayedShape = shape
        lastPlayedChordName = shape.name
        p.play(pitches)
        isChordPlaying = true
    }

    /** Replays or cuts off the last tapped chord, for the play/stop button. */
    fun toggleChordPlayback() {
        val shape = lastPlayedShape
        val p = player
        if (shape == null || p == null) return
        if (isChordPlaying) {
            p.stop()
            isChordPlaying = false
        } else {
            playChordShape(shape)
        }
    }

    /** Silences a chord still ringing, e.g. when the screen goes away. */
    fun stopChord() {
        player?.stop()
        isChordPlaying = false
    }

    override fun onCleared() {
        player?.release()
        player = null
        super.onCleared()
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
        fretsOnChord: Int,
        onHitMap: (PdfGenerator.ChordHitMap) -> Unit
    ): List<Bitmap> {
        val bytes = ChordShapePdfGenerator.generateChordPdfToBytes(
            shapes = shapes,
            title = title,
            stringLabels = library.stringLabels,
            fretsOnChord = fretsOnChord,
            hitMapOut = onHitMap
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
