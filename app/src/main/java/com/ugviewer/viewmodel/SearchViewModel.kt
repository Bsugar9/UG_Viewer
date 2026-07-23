package com.ugviewer.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ugviewer.api.SearchTab
import com.ugviewer.api.UGApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchViewModel : ViewModel() {

    private val api = UGApiClient()

    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchTab>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    fun search() {
        if (query.isBlank()) return
        isLoading = true
        errorMessage = null

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.search(query.trim())
                }
                results = response.tabs
                if (results.isEmpty()) {
                    errorMessage = "No results found for \"$query\""
                }
            } catch (e: Exception) {
                errorMessage = "Search failed: ${e.message}"
                results = emptyList()
            } finally {
                isLoading = false
            }
        }
    }
}
