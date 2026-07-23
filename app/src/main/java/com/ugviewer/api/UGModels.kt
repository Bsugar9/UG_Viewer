package com.ugviewer.api

import com.google.gson.annotations.SerializedName

data class SearchResponse(
    @SerializedName("tabs") val tabs: List<SearchTab> = emptyList(),
    @SerializedName("artists") val artists: List<String> = emptyList()
)

data class SearchTab(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("song_id") val songId: Long = 0,
    @SerializedName("song_name") val songName: String = "",
    @SerializedName("artist_id") val artistId: Long = 0,
    @SerializedName("artist_name") val artistName: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("part") val part: String = "",
    @SerializedName("version") val version: Long = 0,
    @SerializedName("votes") val votes: Long = 0,
    @SerializedName("rating") val rating: Double = 0.0,
    @SerializedName("date") val date: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("tab_access_type") val tabAccessType: String = "",
    @SerializedName("tonality_name") val tonalityName: String = "",
    @SerializedName("verified") val verified: Long = 0
)

data class TabResult(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("song_name") val songName: String = "",
    @SerializedName("artist_name") val artistName: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("part") val part: String = "",
    @SerializedName("version") val version: Int = 0,
    @SerializedName("votes") val votes: Int = 0,
    @SerializedName("rating") val rating: Double = 0.0,
    @SerializedName("date") val date: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("tab_access_type") val tabAccessType: String = "",
    @SerializedName("tonality_name") val tonalityName: String = "",
    @SerializedName("difficulty") val difficulty: String = "",
    @SerializedName("tuning") val tuning: String = "",
    @SerializedName("capo") val capo: Int = 0,
    @SerializedName("urlWeb") val urlWeb: String = "",
    @SerializedName("content") val content: String = "",
    @SerializedName("applicature") val applicature: List<Applicature> = emptyList(),
    @SerializedName("versions") val versions: List<TabVersion> = emptyList(),
    @SerializedName("recommended") val recommended: List<TabResult> = emptyList()
)

data class TabVersion(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("song_name") val songName: String = "",
    @SerializedName("artist_name") val artistName: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("version") val version: Int = 0,
    @SerializedName("rating") val rating: Double = 0.0
)

data class Applicature(
    @SerializedName("chord") val chord: String = "",
    @SerializedName("variations") val variations: List<ChordVariation> = emptyList()
)

data class ChordVariation(
    @SerializedName("id") val id: String = "",
    @SerializedName("frets") val frets: List<Int> = emptyList(),
    @SerializedName("fingers") val fingers: List<Int> = emptyList(),
    @SerializedName("fret") val fret: Int = 0
)
