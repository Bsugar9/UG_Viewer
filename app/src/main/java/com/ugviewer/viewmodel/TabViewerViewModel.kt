package com.ugviewer.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ugviewer.api.TabResult
import com.ugviewer.api.UGApiClient
import com.ugviewer.util.PdfGenerator
import com.ugviewer.util.YouTubeHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class TabViewerViewModel : ViewModel() {

    private val api = UGApiClient()

    var tab by mutableStateOf<TabResult?>(null)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var fontSize by mutableFloatStateOf(14f)
    var isGeneratingPdf by mutableStateOf(false)
    var pdfSuccess by mutableStateOf<String?>(null)
    var isFetchingYouTube by mutableStateOf(false)
    var youtubeUrl by mutableStateOf<String?>(null)

    var isChordType by mutableStateOf(false)
    var pdfPages by mutableStateOf<List<Bitmap>>(emptyList())
    var isRenderingPdf by mutableStateOf(false)
    var pdfFontSize by mutableFloatStateOf(PdfGenerator.DEFAULT_FONT_SIZE)
    private var pdfBytes: ByteArray? = null
    private var renderJob: Job? = null

    fun loadTab(tabId: Long) {
        isLoading = true
        errorMessage = null
        // Drop the old previews by reference only; recycling here could race a
        // frame that is still drawing them. GC reclaims them safely.
        pdfPages = emptyList()
        pdfBytes = null
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
                val size = pdfFontSize
                val bytes = withContext(Dispatchers.IO) {
                    PdfGenerator.generatePdfToBytes(tabResult, link, size)
                }
                pdfBytes = bytes
                val pages = withContext(Dispatchers.IO) { renderPdfPages(bytes) }
                // A newer font size may have been picked while we were rendering.
                if (size != pdfFontSize) return@launch
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

    /** Re-renders the PDF preview at the chosen font size. */
    fun updatePdfFontSize(size: Float) {
        if (size == pdfFontSize) return
        pdfFontSize = size
        tab?.takeIf { isChordType }?.let { generatePdfPreview(it) }
    }

    private fun renderPdfPages(pdfData: ByteArray): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        val tempFile = File.createTempFile("preview", ".pdf")
        try {
            FileOutputStream(tempFile).use { it.write(pdfData) }
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)

            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val scale = 2
                val bitmap = Bitmap.createBitmap(
                    page.width * scale,
                    page.height * scale,
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

    fun savePdf(context: Context) {
        val currentTab = tab ?: return
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

                val file = withContext(Dispatchers.IO) {
                    PdfGenerator.generatePdf(context, currentTab, link, pdfFontSize)
                }
                pdfSuccess = if (link != null) {
                    "Saved to Downloads/UG Viewer/${file.name} • YouTube link included"
                } else {
                    "Saved to Downloads/UG Viewer/${file.name}"
                }
            } catch (e: Exception) {
                errorMessage = "PDF save failed: ${e.message}"
            } finally {
                isGeneratingPdf = false
            }
        }
    }

    fun generatePdf(context: Context) {
        savePdf(context)
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
        // Safe to free here: the screen has been disposed, nothing is drawing.
        pdfPages.forEach { it.recycle() }
        pdfPages = emptyList()
    }
}
