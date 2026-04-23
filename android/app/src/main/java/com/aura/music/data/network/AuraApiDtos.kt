package com.aura.music.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Canonical response envelope for all AURA API responses.
 * Mirrors the backend contract defined in docs/server/api-contract.md
 */
@JsonClass(generateAdapter = true)
data class AuraResponse<T>(
    @Json(name = "data")
    val data: T?,
    @Json(name = "error")
    val error: ApiError?,
    @Json(name = "meta")
    val meta: ResponseMeta?
)

@JsonClass(generateAdapter = true)
data class ApiError(
    @Json(name = "code")
    val code: String,
    @Json(name = "message")
    val message: String,
    @Json(name = "retryable")
    val retryable: Boolean = false,
    @Json(name = "details")
    val details: Map<String, Any>? = null
)

@JsonClass(generateAdapter = true)
data class ResponseMeta(
    @Json(name = "request_id")
    val requestId: String?,
    @Json(name = "partial_failure")
    val partialFailure: Boolean = false,
    @Json(name = "provider_status")
    val providerStatus: Map<String, String>? = null,
    @Json(name = "next_cursor")
    val nextCursor: String? = null
)

// Search response structure
@JsonClass(generateAdapter = true)
data class SearchResponseData(
    @Json(name = "query")
    val query: String,
    @Json(name = "best_match")
    val bestMatch: BestMatch?,
    @Json(name = "tracks")
    val tracks: List<TrackSummary> = emptyList(),
    @Json(name = "artists")
    val artists: List<ArtistSummary> = emptyList(),
    @Json(name = "albums")
    val albums: List<AlbumSummary> = emptyList()
)

@JsonClass(generateAdapter = false)
data class BestMatch(
    @Json(name = "kind")
    val kind: String, // "track" | "artist" | "album"
    @Json(name = "item")
    val item: Any? // Can be TrackSummary, ArtistSummary, or AlbumSummary depending on kind
)

// Union-like sealed class to represent different best match types
sealed class BestMatchItem {
    @JsonClass(generateAdapter = true)
    data class Track(val track: TrackSummary) : BestMatchItem()

    @JsonClass(generateAdapter = true)
    data class Artist(val artist: ArtistSummary) : BestMatchItem()

    @JsonClass(generateAdapter = true)
    data class Album(val album: AlbumSummary) : BestMatchItem()
}

// Summary objects matching backend contract
@JsonClass(generateAdapter = true)
data class TrackSummary(
    @Json(name = "id")
    val id: String,
    @Json(name = "title")
    val title: String,
    @Json(name = "display_artist_name")
    val displayArtistName: String,
    @Json(name = "display_album_title")
    val displayAlbumTitle: String?,
    @Json(name = "duration_ms")
    val durationMs: Int,
    @Json(name = "cover_uri")
    val coverUri: String?,
    @Json(name = "is_explicit")
    val isExplicit: Boolean = false,
    @Json(name = "is_liked")
    val isLiked: Boolean = false,
    @Json(name = "is_local_available")
    val isLocalAvailable: Boolean = false,
    @Json(name = "is_downloaded_by_aura")
    val isDownloadedByAura: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ArtistSummary(
    @Json(name = "id")
    val id: String,
    @Json(name = "name")
    val name: String,
    @Json(name = "picture_uri")
    val pictureUri: String? = null
)

@JsonClass(generateAdapter = true)
data class AlbumSummary(
    @Json(name = "id")
    val id: String,
    @Json(name = "title")
    val title: String,
    @Json(name = "primary_artist_name")
    val primaryArtistName: String,
    @Json(name = "cover_uri")
    val coverUri: String? = null,
    @Json(name = "release_date")
    val releaseDate: String? = null,
    @Json(name = "track_count")
    val trackCount: Int? = null
)

// Detail responses for /artists/{id} and /albums/{id}
@JsonClass(generateAdapter = true)
data class ArtistDetailResponseData(
    @Json(name = "id")
    val id: String,
    @Json(name = "name")
    val name: String,
    @Json(name = "picture_uri")
    val pictureUri: String? = null,
    @Json(name = "summary")
    val summary: String? = null,
    @Json(name = "top_tracks")
    val topTracks: List<TrackSummary> = emptyList(),
    @Json(name = "albums")
    val albums: List<AlbumSummary> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AlbumDetailResponseData(
    @Json(name = "id")
    val id: String,
    @Json(name = "title")
    val title: String,
    @Json(name = "primary_artist_name")
    val primaryArtistName: String,
    @Json(name = "cover_uri")
    val coverUri: String? = null,
    @Json(name = "release_date")
    val releaseDate: String? = null,
    @Json(name = "track_count")
    val trackCount: Int? = null,
    @Json(name = "tracks")
    val tracks: List<TrackSummary> = emptyList()
)
