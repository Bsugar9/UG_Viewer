package com.ugviewer.api

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class UGApiClient {

    companion object {
        private const val API_BASE = "https://api.ultimate-guitar.com/api/v1"
        private const val USER_AGENT = "UGT_ANDROID/7.0.7 (Pixel 4; 11)"
        private const val TIME_FORMAT = "yyyy-MM-dd"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val deviceId = generateDeviceId()

    private fun generateDeviceId(): String {
        val bytes = Random.nextBytes(16)
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun generateApiKey(): String {
        val utc = TimeZone.getTimeZone("UTC")
        val hour = SimpleDateFormat("H", Locale.US).apply { timeZone = utc }.format(Date())
        val date = SimpleDateFormat(TIME_FORMAT, Locale.US).apply { timeZone = utc }.format(Date())
        val payload = "${deviceId}${date}:${hour}createLog()"
        android.util.Log.d("UGApiClient", "Payload: $payload")
        return md5(payload)
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun buildRequest(path: String): Request.Builder {
        val url = "$API_BASE$path"
        return Request.Builder()
            .url(url)
            .header("Accept-Charset", "utf-8")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Connection", "close")
            .header("X-UG-CLIENT-ID", deviceId)
            .header("X-UG-API-KEY", generateApiKey())
    }

    fun search(query: String, type: Int = 300, page: Int = 1): SearchResponse {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val typeParams = "type%5B%5D=$type"

        val titlePath = "/tab/search?title=${encodedQuery}&$typeParams&page=$page"
        val artistPath = "/tab/search?artist_name=${encodedQuery}&$typeParams&page=$page"

        val titleResults = executeSearch(titlePath)
        val artistResults = executeSearch(artistPath)

        val seen = mutableSetOf<Long>()
        val merged = mutableListOf<SearchTab>()
        for (tab in artistResults + titleResults) {
            if (seen.add(tab.id)) {
                merged.add(tab)
            }
        }

        return SearchResponse(tabs = merged)
    }

    private fun executeSearch(path: String): List<SearchTab> {
        val request = buildRequest(path).get().build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            android.util.Log.d("UGApiClient", "Search Response (${response.code}): $body")

            if (!response.isSuccessful) {
                return emptyList()
            }

            return try {
                gson.fromJson(body, SearchResponse::class.java).tabs
            } catch (e: Exception) {
                android.util.Log.e("UGApiClient", "JSON Parse error", e)
                emptyList()
            }
        }
    }

    fun getTabById(tabId: Long): TabResult {
        val path = "/tab/info?tab_id=$tabId&tab_access_type=private"
        val request = buildRequest(path).get().build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            android.util.Log.d("UGApiClient", "Tab Info Response (${response.code}): $body")

            if (!response.isSuccessful) {
                throw Exception("Failed to get tab info (${response.code})")
            }

            return gson.fromJson(body, TabResult::class.java)
        }
    }

    fun getTabByUrl(url: String): TabResult {
        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
        val path = "/tab/url?url=$encodedUrl"
        val request = buildRequest(path).get().build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        android.util.Log.d("UGApiClient", "Response: $body")
        response.close()

        val urlResult = gson.fromJson(body, Map::class.java)
        val tabId = (urlResult["id"] as? Number)?.toLong()
            ?: throw Exception("No tab ID found in URL response")

        return getTabById(tabId)
    }
}
