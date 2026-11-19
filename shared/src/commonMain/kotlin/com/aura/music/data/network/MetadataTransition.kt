package com.aura.music.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MetadataResolveRequestDto(
    @SerialName("track_id") val trackId: String,
    val title: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("album_title") val albumTitle: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val artists: List<ArtistCreditDto> = emptyList(),
)

@Serializable
data class MetadataResolveResultDto(
    val status: String,
    val version: Int = 1,
    val artists: List<ArtistCreditDto> = emptyList(),
    val title: String? = null,
    @SerialName("album_title") val albumTitle: String? = null,
    @SerialName("album_id") val albumId: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("cover_uri") val coverUri: String? = null,
)
