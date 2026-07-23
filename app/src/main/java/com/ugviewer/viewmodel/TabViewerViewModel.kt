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
import kotlinx.coroutines.Dispatchers
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

    var isChordType by mutableStateOf(false)
    var pdfPages by mutableStateOf<List<Bitmap>>(emptyList())
    var isRenderingPdf by mutableStateOf(false)
    private var pdfBytes: ByteArray? = null

    fun loadTab(tabId: Long) {
        isLoading = true
        errorMessage = null
        pdfPages = emptyList()
        pdfBytes = null

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.getTabById(tabId)
                }
                tab = result
                val type = result.type.lowercase()
                val hasChordMarkers = result.content.contains("[ch]", ignoreCase = true)
                isChordType = type.contains("chord") || hasChordMarkers

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

    private suspend fun generatePdfPreview(tabResult: TabResult) {
        isRenderingPdf = true
        try {
            val bytes = withContext(Dispatchers.IO) {
                PdfGenerator.generatePdfToBytes(tabResult)
            }
            pdfBytes = bytes
            val pages = renderPdfPages(bytes)
            pdfPages = pages
        } catch (e: Exception) {
            errorMessage = "PDF preview failed: ${e.message}"
        } finally {
            isRenderingPdf = false
        }
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
                val file = withContext(Dispatchers.IO) {
                    PdfGenerator.generatePdf(context, currentTab)
                }
                pdfSuccess = "Saved to Downloads/UG Viewer/${file.name}"
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
        pdfPages.forEach { it.recycle() }
        pdfPages = emptyList()
    }
}
