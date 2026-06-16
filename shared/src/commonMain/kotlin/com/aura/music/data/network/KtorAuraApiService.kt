package com.aura.music.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Concrete implementation of [AuraApiService] using Ktor HTTP Client.
 */
class KtorAuraApiService(
    private val client: HttpClient,
    baseUrl: String = "http://212.90.121.80:8000"
) : AuraApiService {

    private val cleanBaseUrl = baseUrl.removeSuffix("/")

    override suspend fun search(
        query: String,
        limitTracks: Int,
        limitArtists: Int,
        limitAlbums: Int
    ): AuraResponse<SearchResponseData> = client.get("$cleanBaseUrl/search") {
        parameter("q", query)
        parameter("limit_tracks", limitTracks)
        parameter("limit_artists", limitArtists)
        parameter("limit_albums", limitAlbums)
    }.body()

    override suspend fun getArtist(id: String): AuraResponse<ArtistDetailResponseData> = client.get("$cleanBaseUrl/artists/$id").body()

    override suspend fun getAlbum(id: String): AuraResponse<AlbumDetailResponseData> = client.get("$cleanBaseUrl/albums/$id").body()

    override suspend fun resolveArtist(name: String): AuraResponse<ResolveArtistResponseData> = client.get("$cleanBaseUrl/resolve/artist") {
        parameter("name", name)
    }.body()

    override suspend fun resolveAlbum(title: String, artistName: String?): AuraResponse<ResolveAlbumResponseData> = client.get("$cleanBaseUrl/resolve/album") {
        parameter("title", title)
        artistName?.let { parameter("artist_name", it) }
    }.body()

    override suspend fun createDownload(token: String, request: DownloadRequestDto): AuraResponse<DownloadCreateResponseData> = client.post("$cleanBaseUrl/downloads") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun listDownloads(
        token: String,
        status: String?,
        limit: Int,
        cursor: String?
    ): AuraResponse<DownloadJobListResponseData> = client.get("$cleanBaseUrl/downloads") {
        header("Authorization", token)
        status?.let { parameter("status", it) }
        parameter("limit", limit)
        cursor?.let { parameter("cursor", it) }
    }.body()

    override suspend fun retryDownload(token: String, jobId: String): AuraResponse<DownloadCreateResponseData> = client.post("$cleanBaseUrl/downloads/$jobId/retry") {
        header("Authorization", token)
    }.body()

    override suspend fun resolveDownload(
        token: String,
        jobId: String,
        request: ResolveDownloadRequestDto
    ): AuraResponse<DownloadCreateResponseData> = client.post("$cleanBaseUrl/downloads/$jobId/resolve") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun getJobStatus(token: String, jobId: String): AuraResponse<JobStatusResponseData> = client.get("$cleanBaseUrl/jobs/$jobId") {
        header("Authorization", token)
    }.body()

    override suspend fun uploadCookies(token: String, request: CookieUploadRequestDto): AuraResponse<CookieUploadResponseData> = client.post("$cleanBaseUrl/me/settings/cookies") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun downloadFile(token: String, jobId: String): HttpResponse = client.get("$cleanBaseUrl/downloads/$jobId/file") {
        header("Authorization", token)
    }

    override suspend fun bootstrap(token: String, request: BootstrapRequestDto): AuraResponse<BootstrapResponseDto> = client.post("$cleanBaseUrl/me/sync/bootstrap") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun pushBatch(token: String, request: PushBatchRequestDto): AuraResponse<PushBatchResponseDto> = client.post("$cleanBaseUrl/me/sync/push-batch") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun pullBatch(token: String, request: PullBatchRequestDto): AuraResponse<PullBatchResponseDto> = client.post("$cleanBaseUrl/me/sync/pull-batch") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun getPlaylists(token: String): AuraResponse<List<PlaylistResponse>> = client.get("$cleanBaseUrl/me/playlists") {
        header("Authorization", token)
    }.body()

    override suspend fun getLikes(token: String): AuraResponse<List<LikeResponse>> = client.get("$cleanBaseUrl/me/likes") {
        header("Authorization", token)
    }.body()

    override suspend fun likeTrack(
        token: String,
        trackId: String,
        sourceContextType: String?,
        sourceContextId: String?
    ): AuraResponse<LikeResponse> = client.put("$cleanBaseUrl/me/tracks/$trackId/like") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(mapOf(
            "source_context_type" to sourceContextType,
            "source_context_id" to sourceContextId
        ))
    }.body()

    override suspend fun unlikeTrack(token: String, trackId: String): AuraResponse<LikeResponse> = client.delete("$cleanBaseUrl/me/tracks/$trackId/like") {
        header("Authorization", token)
    }.body()

    override suspend fun getPlaybackSnapshot(token: String): AuraResponse<PlaybackSnapshotResponse?> = client.get("$cleanBaseUrl/me/playback-snapshot") {
        header("Authorization", token)
    }.body()

    override suspend fun updatePlaybackSnapshot(
        token: String,
        snapshot: PlaybackSnapshotResponse
    ): AuraResponse<PlaybackSnapshotResponse> = client.put("$cleanBaseUrl/me/playback-snapshot") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(snapshot)
    }.body()

    override suspend fun getHistory(token: String): AuraResponse<HistoryResponseData> = client.get("$cleanBaseUrl/me/history") {
        header("Authorization", token)
    }.body()

    override suspend fun createPlaylist(token: String, request: PlaylistCreate): AuraResponse<PlaylistResponse> = client.post("$cleanBaseUrl/me/playlists") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun deletePlaylist(token: String, id: String): AuraResponse<PlaylistResponse> = client.delete("$cleanBaseUrl/me/playlists/$id") {
        header("Authorization", token)
    }.body()

    override suspend fun appendTrackToPlaylist(
        token: String,
        id: String,
        request: PlaylistItemCreate
    ): AuraResponse<PlaylistItemResponse> = client.post("$cleanBaseUrl/me/playlists/$id/tracks") {
        header("Authorization", token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun removeTrackFromPlaylist(
        token: String,
        id: String,
        trackId: String
    ): AuraResponse<List<PlaylistItemResponse>> = client.delete("$cleanBaseUrl/me/playlists/$id/tracks/$trackId") {
        header("Authorization", token)
    }.body()


    companion object {
        /**
         * Helper to create a configured KtorAuraApiService.
         */
        fun createDefault(baseUrl: String = "http://212.90.121.80:8000"): KtorAuraApiService {
            val json = Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                prettyPrint = true
                encodeDefaults = true
            }
            val client = HttpClient {
                install(ContentNegotiation) {
                    json(json)
                }
            }
            return KtorAuraApiService(client, baseUrl)
        }
    }
}
