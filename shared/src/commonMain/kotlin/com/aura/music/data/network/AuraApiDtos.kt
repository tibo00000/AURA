package com.aura.music.data.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Canonical response envelope for all AURA API responses.
 * Mirrors the backend contract defined in docs/server/api-contract.md
 */
@Serializable
data class AuraResponse<T>(
    @SerialName("data")
    val data: T?,
    @SerialName("error")
    val error: ApiError?,
    @SerialName("meta")
    val meta: ResponseMeta?
)

@Serializable
data class ApiError(
    @SerialName("code")
    val code: String,
    @SerialName("message")
    val message: String,
    @SerialName("retryable")
    val retryable: Boolean = false,
    @SerialName("details")
    val details: JsonObject? = null
)

@Serializable
data class ResponseMeta(
    @SerialName("request_id")
    val requestId: String?,
    @SerialName("partial_failure")
    val partialFailure: Boolean = false,
    @SerialName("provider_status")
    val providerStatus: Map<String, String>? = null,
    @SerialName("next_cursor")
    val nextCursor: String? = null
)

// Search response structure
@Serializable
data class SearchResponseData(
    @SerialName("query")
    val query: String,
    @SerialName("best_match")
    val bestMatch: BestMatch?,
    @SerialName("tracks")
    val tracks: List<TrackSummary> = emptyList(),
    @SerialName("artists")
    val artists: List<ArtistSummary> = emptyList(),
    @SerialName("albums")
    val albums: List<AlbumSummary> = emptyList()
)

@Serializable(with = BestMatchSerializer::class)
data class BestMatch(
    val kind: String, // "track" | "artist" | "album"
    val item: BestMatchItem?
)

// Union-like sealed interface to represent different best match types
@Serializable
sealed interface BestMatchItem

// Summary objects matching backend contract
@Serializable
data class TrackSummary(
    @SerialName("id")
    val id: String,
    @SerialName("title")
    val title: String,
    @SerialName("display_artist_name")
    val displayArtistName: String,
    @SerialName("display_album_title")
    val displayAlbumTitle: String?,
    @SerialName("duration_ms")
    val durationMs: Int,
    @SerialName("cover_uri")
    val coverUri: String?,
    @SerialName("is_explicit")
    val isExplicit: Boolean = false,
    @SerialName("is_liked")
    val isLiked: Boolean = false,
    @SerialName("is_local_available")
    val isLocalAvailable: Boolean = false,
    @SerialName("is_downloaded_by_aura")
    val isDownloadedByAura: Boolean = false
) : BestMatchItem

@Serializable
data class ArtistSummary(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("picture_uri")
    val pictureUri: String? = null
) : BestMatchItem

@Serializable
data class AlbumSummary(
    @SerialName("id")
    val id: String,
    @SerialName("title")
    val title: String,
    @SerialName("primary_artist_name")
    val primaryArtistName: String,
    @SerialName("cover_uri")
    val coverUri: String? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("track_count")
    val trackCount: Int? = null,
    @SerialName("release_type")
    val releaseType: String? = null
) : BestMatchItem

// Detail responses for /artists/{id} and /albums/{id}
@Serializable
data class ArtistDetailResponseData(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("picture_uri")
    val pictureUri: String? = null,
    @SerialName("summary")
    val summary: String? = null,
    @SerialName("top_tracks")
    val topTracks: List<TrackSummary> = emptyList(),
    @SerialName("albums")
    val albums: List<AlbumSummary> = emptyList()
)

@Serializable
data class AlbumDetailResponseData(
    @SerialName("id")
    val id: String,
    @SerialName("title")
    val title: String,
    @SerialName("primary_artist_name")
    val primaryArtistName: String,
    @SerialName("cover_uri")
    val coverUri: String? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("track_count")
    val trackCount: Int? = null,
    @SerialName("release_type")
    val releaseType: String? = null,
    @SerialName("tracks")
    val tracks: List<TrackSummary> = emptyList()
)

// Resolve responses
@Serializable
data class ResolvedArtistData(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("picture_uri") val pictureUri: String? = null
)

@Serializable
data class ResolveArtistResponseData(
    @SerialName("resolved") val resolved: Boolean,
    @SerialName("match_confidence") val matchConfidence: Double,
    @SerialName("artist") val artist: ResolvedArtistData?
)

@Serializable
data class ResolvedAlbumData(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("primary_artist_name") val primaryArtistName: String,
    @SerialName("cover_uri") val coverUri: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("track_count") val trackCount: Int? = null,
    @SerialName("release_type") val releaseType: String? = null
)

@Serializable
data class ResolveAlbumResponseData(
    @SerialName("resolved") val resolved: Boolean,
    @SerialName("match_confidence") val matchConfidence: Double,
    @SerialName("album") val album: ResolvedAlbumData?
)

// Download & Job API DTOs
@Serializable
data class SourceHintDto(
    @SerialName("provider_name") val providerName: String,
    @SerialName("provider_track_id") val providerTrackId: String
)

@Serializable
data class DownloadRequestDto(
    @SerialName("track_id") val trackId: String,
    @SerialName("source_hint") val sourceHint: SourceHintDto? = null
)

@Serializable
data class DownloadCreateResponseData(
    @SerialName("job_id") val jobId: String,
    @SerialName("track_id") val trackId: String,
    @SerialName("status") val status: String
)

@Serializable
data class DownloadJobResponseData(
    @SerialName("id") val id: String,
    @SerialName("track_id") val trackId: String,
    @SerialName("provider_name") val providerName: String,
    @SerialName("status") val status: String,
    @SerialName("progress_percent") val progressPercent: Float = 0f,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("attempt_count") val attemptCount: Int = 1,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class DownloadJobListResponseData(
    @SerialName("items") val items: List<DownloadJobResponseData>
)

@Serializable
data class JobErrorPayload(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String
)

@Serializable
data class YtmCandidateDto(
    @SerialName("video_id") val videoId: String,
    @SerialName("title") val title: String,
    @SerialName("artist") val artist: String,
    @SerialName("album") val album: String? = null,
    @SerialName("duration") val duration: String? = null,
    @SerialName("cover_uri") val coverUri: String? = null
)

@Serializable
data class ResolveDownloadRequestDto(
    @SerialName("video_id") val videoId: String
)

@Serializable
data class JobStatusResponseData(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: String,
    @SerialName("status") val status: String,
    @SerialName("progress_percent") val progressPercent: Float = 0f,
    @SerialName("error") val error: JobErrorPayload? = null,
    @SerialName("candidates") val candidates: List<YtmCandidateDto>? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CookieUploadRequestDto(
    @SerialName("cookies_text") val cookiesText: String
)

@Serializable
data class CookieUploadResponseData(
    @SerialName("success") val success: Boolean
)

// Sync DTOs
@Serializable
data class SyncTokenDto(
    @SerialName("value") val value: String,
    @SerialName("issued_at") val issuedAt: String
)

@Serializable
data class BootstrapRequestDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("app_version") val appVersion: String = "0.1.0",
    @SerialName("capabilities") val capabilities: Map<String, String> = emptyMap()
)

@Serializable
data class BootstrapResponseDto(
    @SerialName("sync_token") val syncToken: SyncTokenDto,
    @SerialName("snapshot") val snapshot: JsonObject
)

@Serializable
data class SyncOperationDto(
    @SerialName("operation_id") val operationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("operation_type") val operationType: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("base_server_updated_at") val baseServerUpdatedAt: String? = null,
    @SerialName("payload") val payload: JsonObject
)

@Serializable
data class PushBatchRequestDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("batch_id") val batchId: String,
    @SerialName("sent_at") val sentAt: String,
    @SerialName("operations") val operations: List<SyncOperationDto>
)

@Serializable
data class SyncOperationResultDto(
    @SerialName("operation_id") val operationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("status") val status: String, // "applied", "merged", "conflict", "ignored_duplicate"
    @SerialName("server_updated_at") val serverUpdatedAt: String,
    @SerialName("resolved_entity") val resolvedEntity: JsonObject? = null,
    @SerialName("conflict") val conflict: JsonObject? = null
)

@Serializable
data class PushBatchResponseDto(
    @SerialName("batch_id") val batchId: String,
    @SerialName("results") val results: List<SyncOperationResultDto>,
    @SerialName("next_pull_token") val nextPullToken: SyncTokenDto
)

@Serializable
data class PullBatchRequestDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("since_token") val sinceToken: String,
    @SerialName("limit") val limit: Int = 200,
    @SerialName("entity_types") val entityTypes: List<String>? = null
)

@Serializable
data class ServerChangeDto(
    @SerialName("change_id") val changeId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("change_type") val changeType: String, // "upsert" or "delete"
    @SerialName("server_updated_at") val serverUpdatedAt: String,
    @SerialName("payload") val payload: JsonObject
)

@Serializable
data class PullBatchResponseDto(
    @SerialName("changes") val changes: List<ServerChangeDto>,
    @SerialName("next_pull_token") val nextPullToken: SyncTokenDto,
    @SerialName("has_more") val hasMore: Boolean
)

// Custom BestMatch Serializer for Kotlinx Serialization
object BestMatchSerializer : KSerializer<BestMatch> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("BestMatch") {
        element("kind", buildClassSerialDescriptor("kind").descriptor)
        element("item", buildClassSerialDescriptor("item").descriptor)
    }

    override fun deserialize(decoder: Decoder): BestMatch {
        val jsonDecoder = decoder as? JsonDecoder ?: throw IllegalStateException("This serializer can only be used with JSON format")
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject
        val kind = jsonObject["kind"]?.jsonPrimitive?.contentOrNull ?: ""
        val itemElement = jsonObject["item"] ?: return BestMatch(kind, null)
        
        val item = when (kind) {
            "track" -> jsonDecoder.json.decodeFromJsonElement(TrackSummary.serializer(), itemElement)
            "artist" -> jsonDecoder.json.decodeFromJsonElement(ArtistSummary.serializer(), itemElement)
            "album" -> jsonDecoder.json.decodeFromJsonElement(AlbumSummary.serializer(), itemElement)
            else -> null
        }
        return BestMatch(kind, item)
    }

    override fun serialize(encoder: Encoder, value: BestMatch) {
        val jsonEncoder = encoder as? JsonEncoder ?: throw IllegalStateException("This serializer can only be used with JSON format")
        val json = jsonEncoder.json
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        map["kind"] = kotlinx.serialization.json.JsonPrimitive(value.kind)
        val itemElement = when (val item = value.item) {
            is TrackSummary -> json.encodeToJsonElement(TrackSummary.serializer(), item)
            is ArtistSummary -> json.encodeToJsonElement(ArtistSummary.serializer(), item)
            is AlbumSummary -> json.encodeToJsonElement(AlbumSummary.serializer(), item)
            else -> kotlinx.serialization.json.JsonNull
        }
        map["item"] = itemElement
        jsonEncoder.encodeJsonElement(JsonObject(map))
    }
}
