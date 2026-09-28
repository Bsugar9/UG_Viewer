package com.ugviewer.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Finds a matching YouTube video for a song so users can hear it while playing.
 *
 * Uses YouTube's public search page and pulls the top video id out of the
 * embedded ytInitialData JSON. No API key required.
 */
object YouTubeHelper {

    private const val RESULTS_URL = "https://www.youtube.com/results?search_query="

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a watch URL (https://www.youtube.com/watch?v=...) for the best
     * match of "artist - song", or null if nothing suitable was found.
     */
    suspend fun findSongVideo(artist: String, song: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$artist $song", "UTF-8")
            val request = Request.Builder()
                .url(RESULTS_URL + query)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: return@withContext null
                extractFirstVideoId(html)?.let { "https://www.youtube.com/watch?v=$it" }
            }
        } catch (e: Exception) {
            android.util.Log.w("YouTubeHelper", "YouTube lookup failed: ${e.message}")
            null
        }
    }

    /**
     * The search page embeds its results as JSON in ytInitialData. The first
     * "videoId" entry (11 chars, YouTube's standard id alphabet) is the top hit.
     */
    private fun extractFirstVideoId(html: String): String? {
        val videoIdRegex = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
        return videoIdRegex.find(html)?.groupValues?.get(1)
    }
}
