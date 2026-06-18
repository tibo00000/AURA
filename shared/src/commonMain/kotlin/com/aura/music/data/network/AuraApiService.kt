package com.aura.music.data.network

import io.ktor.client.statement.HttpResponse

/**
 * Common Multiplatform interface for the AURA API.
 * Defines all public catalog, download, resolve, and sync endpoints.
 */
interface AuraApiService {

    suspend fun search(
        query: String,
        limitTracks: Int = 10,
        limitArtists: Int = 8,
        limitAlbums: Int = 8
    ): AuraResponse<SearchResponseData>

    suspend fun getArtist(id: String): AuraResponse<ArtistDetailResponseData>

    suspend fun getAlbum(id: String): AuraResponse<AlbumDetailResponseData>

    suspend fun resolveArtist(name: String): AuraResponse<ResolveArtistResponseData>

    suspend fun resolveAlbum(title: String, artistName: String? = null): AuraResponse<ResolveAlbumResponseData>

    suspend fun createDownload(token: String, request: DownloadRequestDto): AuraResponse<DownloadCreateResponseData>

    suspend fun listDownloads(
        token: String,
        status: String? = null,
        limit: Int = 20,
        cursor: String? = null
    ): AuraResponse<DownloadJobListResponseData>

    suspend fun retryDownload(token: String, jobId: String): AuraResponse<DownloadCreateResponseData>

    suspend fun resolveDownload(
        token: String,
        jobId: String,
        request: ResolveDownloadRequestDto
    ): AuraResponse<DownloadCreateResponseData>

    suspend fun getJobStatus(token: String, jobId: String): AuraResponse<JobStatusResponseData>

    suspend fun uploadCookies(token: String, request: CookieUploadRequestDto): AuraResponse<CookieUploadResponseData>

    suspend fun downloadFile(token: String, jobId: String): HttpResponse

    suspend fun bootstrap(token: String, request: BootstrapRequestDto): AuraResponse<BootstrapResponseDto>

    suspend fun pushBatch(token: String, request: PushBatchRequestDto): AuraResponse<PushBatchResponseDto>

    suspend fun pullBatch(token: String, request: PullBatchRequestDto): AuraResponse<PullBatchResponseDto>

    suspend fun getPlaylists(token: String): AuraResponse<List<PlaylistResponse>>

    suspend fun getLikes(token: String): AuraResponse<List<LikeResponse>>

    suspend fun likeTrack(
        token: String,
        trackId: String,
        sourceContextType: String? = null,
        sourceContextId: String? = null
    ): AuraResponse<LikeResponse>

    suspend fun unlikeTrack(token: String, trackId: String): AuraResponse<LikeResponse>

    suspend fun getPlaybackSnapshot(token: String): AuraResponse<PlaybackSnapshotResponse?>

    suspend fun updatePlaybackSnapshot(token: String, snapshot: PlaybackSnapshotResponse): AuraResponse<PlaybackSnapshotResponse>

    suspend fun getHistory(token: String): AuraResponse<HistoryResponseData>

    suspend fun createPlaylist(token: String, request: PlaylistCreate): AuraResponse<PlaylistResponse>

    suspend fun deletePlaylist(token: String, id: String): AuraResponse<PlaylistResponse>

    suspend fun appendTrackToPlaylist(
        token: String,
        id: String,
        request: PlaylistItemCreate
    ): AuraResponse<PlaylistItemResponse>

    suspend fun removeTrackFromPlaylist(
        token: String,
        id: String,
        trackId: String
    ): AuraResponse<List<PlaylistItemResponse>>

    suspend fun uploadSyncFile(
        token: String,
        trackId: String,
        fileBytes: ByteArray,
        mimeType: String,
        title: String? = null,
        artistName: String? = null,
        albumTitle: String? = null,
        durationMs: Long? = null
    ): AuraResponse<SyncedFileResponseData>

    suspend fun downloadSyncFile(token: String, trackId: String): HttpResponse

    suspend fun listSyncFiles(token: String): AuraResponse<SyncedFileListResponseData>

    suspend fun deleteSyncFile(token: String, trackId: String): AuraResponse<SyncedFileDeleteResponse>
}
