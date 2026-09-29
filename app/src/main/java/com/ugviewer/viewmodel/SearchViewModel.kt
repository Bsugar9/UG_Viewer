package com.ugviewer.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ugviewer.api.SearchTab
import com.ugviewer.api.UGApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One previous lookup, shown on the home screen to search again in one tap. */
data class SearchHistoryEntry(
    val query: String,
    val artistName: String,
    val songName: String
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val api = UGApiClient()

    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchTab>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    /** Previous lookups, newest first; persisted across app restarts. */
    var history by mutableStateOf<List<SearchHistoryEntry>>(emptyList())
        private set

    private val prefs by lazy {
        getApplication<Application>()
            .getSharedPreferences("search_history", Context.MODE_PRIVATE)
    }

    init {
        history = loadHistory()
    }

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
                } else {
                    // The first result identifies the lookup ("Artist - Song")
                    // so the home screen can relaunch it verbatim later.
                    response.tabs.firstOrNull()?.let { first ->
                        addToHistory(first.artistName, first.songName)
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Search failed: ${e.message}"
                results = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    /** Runs a search straight from a history tap and fills the search box. */
    fun searchFromHistory(entry: SearchHistoryEntry) {
        val q = buildString {
            append(entry.artistName)
            append(' ')
            append(entry.songName)
        }.trim()
        if (q.isEmpty()) return
        query = q
        search()
    }

    /** Clears the whole history list. */
    fun clearHistory() {
        history = emptyList()
        prefs.edit().clear().apply()
    }

    private fun addToHistory(artistName: String, songName: String) {
        val entry = SearchHistoryEntry(
            query = "$artistName $songName",
            artistName = artistName,
            songName = songName
        )
        // Newest first, duplicates collapse onto the front.
        val updated = listOf(entry) + history.filterNot {
            it.query.equals(entry.query, ignoreCase = true)
        }
        history = updated.take(MAX_HISTORY)
        saveHistory(history)
    }

    private fun loadHistory(): List<SearchHistoryEntry> {
        return try {
            val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
            com.google.gson.Gson().fromJson(json, Array<SearchHistoryEntry>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(entries: List<SearchHistoryEntry>) {
        try {
            prefs.edit().putString(KEY_HISTORY, com.google.gson.Gson().toJson(entries)).apply()
        } catch (e: Exception) {
            // History is a convenience; a failed write must never break a search.
        }
    }

    companion object {
        private const val KEY_HISTORY = "entries"
        private const val MAX_HISTORY = 25
    }
}
