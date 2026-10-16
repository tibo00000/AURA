package com.aura.music.desktop.domain

import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.SyncOutboxEntity
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.TrackLikeEntity
import com.aura.music.data.network.*
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests d'intégration SQLite/Room + DesktopCloudSyncManager couvrant :
 * 1. L'ordre de dépilement FIFO strict par `ORDER BY rowid ASC` (Règle Normative #3) même en cas de collision de `created_at`.
 * 2. L'arrêt immédiat (`break`) sur erreur réseau dans `flushSyncOutbox` pour préserver l'ordre causal FIFO.
 * 3. L'invariant Room v4/v5 `getLikedTracks()` basé sur `INNER JOIN track_likes` sans filtrage sur `tracks.is_liked = 1`.
 */
class DesktopCloudSyncOutboxTest {

    private lateinit var database: AuraDatabase
    private lateinit var tempDbFile: File

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("aura_sync_outbox_test_", ".db")
        database = AuraDatabase.getInstance(tempDbFile.absolutePath)
    }

    @After
    fun tearDown() {
        try {
            if (::database.isInitialized) {
                database.close()
            }
        } catch (_: Throwable) {}
        if (::tempDbFile.isInitialized && tempDbFile.exists()) {
            tempDbFile.delete()
            File("${tempDbFile.absolutePath}-wal").delete()
            File("${tempDbFile.absolutePath}-shm").delete()
        }
    }

    @Test
    fun `SyncOutboxDao returns pending operations in strict SQLite rowid FIFO order regardless of createdAt`() = runBlocking {
        // Insert 3 operations in causal order, but with reversed createdAt timestamps to prove ORDER BY rowid ASC is used
        val op1 = SyncOutboxEntity(
            id = "op_z_first_inserted",
            entityType = "playlist",
            entityId = "pl_1",
            operationType = "create",
            payloadJson = "My Playlist",
            status = "pending",
            createdAt = 3000L,
            updatedAt = 3000L
        )
        val op2 = SyncOutboxEntity(
            id = "op_a_second_inserted",
            entityType = "playlist_item",
            entityId = "pl_1",
            operationType = "create",
            payloadJson = "trk_1",
            status = "pending",
            createdAt = 2000L,
            updatedAt = 2000L
        )
        val op3 = SyncOutboxEntity(
            id = "op_m_third_inserted",
            entityType = "playlist",
            entityId = "pl_1",
            operationType = "update",
            payloadJson = """{"name":"Renamed Playlist"}""",
            status = "pending",
            createdAt = 1000L,
            updatedAt = 1000L
        )

        database.syncOutboxDao().insert(op1)
        database.syncOutboxDao().insert(op2)
        database.syncOutboxDao().insert(op3)

        val pending = database.syncOutboxDao().getPendingOperations()
        assertEquals(
            listOf("op_z_first_inserted", "op_a_second_inserted", "op_m_third_inserted"),
            pending.map { it.id }
        )
    }

    @Test
    fun `flushSyncOutbox processes operations in FIFO order and halts on network exception to preserve causal chain`() = runBlocking {
        val executedLog = mutableListOf<String>()
        var failOnPlaylistItem = true

        val fakeApi = object : StubAuraApiService() {
            override suspend fun createPlaylist(token: String, request: PlaylistCreate): AuraResponse<PlaylistResponse> {
                executedLog.add("create_playlist:${request.id}:${request.name}")
                return AuraResponse(
                    data = PlaylistResponse(
                        id = request.id,
                        userId = "usr_1",
                        name = request.name,
                        isPinned = false,
                        createdAt = "2026-10-16T00:00:00Z",
                        updatedAt = "2026-10-16T00:00:00Z"
                    )
                )
            }

            override suspend fun appendTrackToPlaylist(
                token: String,
                id: String,
                request: PlaylistItemCreate
            ): AuraResponse<PlaylistItemResponse> {
                executedLog.add("append_item:$id:${request.trackId}")
                if (failOnPlaylistItem) {
                    throw RuntimeException("Simulated network timeout")
                }
                return AuraResponse(
                    data = PlaylistItemResponse(
                        id = request.id,
                        playlistId = id,
                        trackId = request.trackId,
                        position = request.position,
                        addedAt = "2026-10-16T00:00:00Z"
                    )
                )
            }

            override suspend fun likeTrack(
                token: String,
                trackId: String,
                sourceContextType: String?,
                sourceContextId: String?
            ): AuraResponse<LikeResponse> {
                executedLog.add("like:$trackId")
                return AuraResponse(
                    data = LikeResponse(
                        trackId = trackId,
                        likedAt = "2026-10-16T00:00:00Z"
                    )
                )
            }
        }

        val now = 1000L
        database.syncOutboxDao().insert(
            SyncOutboxEntity("op_1", "playlist", "pl_100", "create", "Roadtrip", "pending", 0, now, now)
        )
        database.syncOutboxDao().insert(
            SyncOutboxEntity("op_2", "playlist_item", "pl_100", "create", "trk_55", "pending", 0, now, now)
        )
        database.syncOutboxDao().insert(
            SyncOutboxEntity("op_3", "track_like", "trk_55", "set", "", "pending", 0, now, now)
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val syncManager = DesktopCloudSyncManager(database, fakeApi, scope)

        syncManager.flushSyncOutbox("jwt_token")

        // op_1 succeeded and was deleted; op_2 threw network exception and halted the loop; op_3 was NOT executed yet
        assertEquals(listOf("create_playlist:pl_100:Roadtrip", "append_item:pl_100:trk_55"), executedLog)

        // op_3 must still be pending in outbox
        val remainingPending = database.syncOutboxDao().getPendingOperations()
        assertEquals(listOf("op_3"), remainingPending.map { it.id })
    }

    @Test
    fun `getLikedTracks returns tracks joined with track_likes even when tracks is_liked flag is false`() = runBlocking {
        val now = System.currentTimeMillis()
        // Insert a cloud stub track where tracks.is_liked is false
        database.trackDao().upsertTracks(
            listOf(
                TrackEntity(
                    id = "trk_cloud_liked",
                    primaryArtistId = null,
                    albumId = null,
                    title = "Midnight City",
                    normalizedTitle = "midnight city",
                    displayArtistName = "M83",
                    displayAlbumTitle = "Hurry Up, We're Dreaming",
                    canonicalAudioSourceType = "cloud",
                    isLiked = false,
                    isDownloadedByAura = false,
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
        database.trackLikeDao().insertLike(
            TrackLikeEntity(
                trackId = "trk_cloud_liked",
                likedAt = now
            )
        )

        val likedTracks = database.trackDao().getLikedTracks()
        assertEquals(1, likedTracks.size)
        assertEquals("trk_cloud_liked", likedTracks.first().id)
        assertTrue(likedTracks.first().isLiked)
    }

    private open class StubAuraApiService : AuraApiService {
        override suspend fun search(query: String, limitTracks: Int, limitArtists: Int, limitAlbums: Int): AuraResponse<SearchResponseData> = throw UnsupportedOperationException()
        override suspend fun getArtist(id: String): AuraResponse<ArtistDetailResponseData> = throw UnsupportedOperationException()
        override suspend fun getAlbum(id: String): AuraResponse<AlbumDetailResponseData> = throw UnsupportedOperationException()
        override suspend fun resolveArtist(name: String, trackTitle: String?, albumTitle: String?): AuraResponse<ResolveArtistResponseData> = throw UnsupportedOperationException()
        override suspend fun resolveAlbum(title: String, artistName: String?, trackTitle: String?): AuraResponse<ResolveAlbumResponseData> = throw UnsupportedOperationException()
        override suspend fun createDownload(token: String, request: DownloadRequestDto): AuraResponse<DownloadCreateResponseData> = throw UnsupportedOperationException()
        override suspend fun listDownloads(token: String, status: String?, limit: Int, cursor: String?): AuraResponse<DownloadJobListResponseData> = throw UnsupportedOperationException()
        override suspend fun retryDownload(token: String, jobId: String): AuraResponse<DownloadCreateResponseData> = throw UnsupportedOperationException()
        override suspend fun resolveDownload(token: String, jobId: String, request: ResolveDownloadRequestDto): AuraResponse<DownloadCreateResponseData> = throw UnsupportedOperationException()
        override suspend fun searchCandidates(token: String, artist: String?, title: String?, query: String?, limit: Int): AuraResponse<CandidatesQueryResponseData> = throw UnsupportedOperationException()
        override suspend fun reassignAudio(token: String, request: ReassignAudioRequestDto): AuraResponse<DownloadCreateResponseData> = throw UnsupportedOperationException()
        override suspend fun getJobStatus(token: String, jobId: String): AuraResponse<JobStatusResponseData> = throw UnsupportedOperationException()
        override suspend fun uploadCookies(token: String, request: CookieUploadRequestDto): AuraResponse<CookieUploadResponseData> = throw UnsupportedOperationException()
        override suspend fun downloadFile(token: String, jobId: String): HttpResponse = throw UnsupportedOperationException()
        override suspend fun deleteDownloadJob(token: String, jobId: String): HttpResponse = throw UnsupportedOperationException()
        override suspend fun bootstrap(token: String, request: BootstrapRequestDto): AuraResponse<BootstrapResponseDto> = throw UnsupportedOperationException()
        override suspend fun pushBatch(token: String, request: PushBatchRequestDto): AuraResponse<PushBatchResponseDto> = throw UnsupportedOperationException()
        override suspend fun pullBatch(token: String, request: PullBatchRequestDto): AuraResponse<PullBatchResponseDto> = throw UnsupportedOperationException()
        override suspend fun getPlaylists(token: String): AuraResponse<List<PlaylistResponse>> = throw UnsupportedOperationException()
        override suspend fun getLikes(token: String): AuraResponse<List<LikeResponse>> = throw UnsupportedOperationException()
        override suspend fun likeTrack(token: String, trackId: String, sourceContextType: String?, sourceContextId: String?): AuraResponse<LikeResponse> = throw UnsupportedOperationException()
        override suspend fun unlikeTrack(token: String, trackId: String): AuraResponse<LikeResponse> = throw UnsupportedOperationException()
        override suspend fun getPlaybackSnapshot(token: String): AuraResponse<PlaybackSnapshotResponse?> = throw UnsupportedOperationException()
        override suspend fun updatePlaybackSnapshot(token: String, snapshot: PlaybackSnapshotResponse): AuraResponse<PlaybackSnapshotResponse> = throw UnsupportedOperationException()
        override suspend fun getHistory(token: String): AuraResponse<HistoryResponseData> = throw UnsupportedOperationException()
        override suspend fun createPlaylist(token: String, request: PlaylistCreate): AuraResponse<PlaylistResponse> = throw UnsupportedOperationException()
        override suspend fun updatePlaylist(token: String, id: String, request: PlaylistUpdate): AuraResponse<PlaylistResponse> = throw UnsupportedOperationException()
        override suspend fun deletePlaylist(token: String, id: String): AuraResponse<PlaylistResponse> = throw UnsupportedOperationException()
        override suspend fun appendTrackToPlaylist(token: String, id: String, request: PlaylistItemCreate): AuraResponse<PlaylistItemResponse> = throw UnsupportedOperationException()
        override suspend fun removeTrackFromPlaylist(token: String, id: String, trackId: String): AuraResponse<List<PlaylistItemResponse>> = throw UnsupportedOperationException()
        override suspend fun uploadSyncFile(token: String, trackId: String, fileBytes: ByteArray, mimeType: String, title: String?, artistName: String?, albumTitle: String?, durationMs: Long?, artistId: String?, albumId: String?, coverUri: String?): AuraResponse<SyncedFileResponseData> = throw UnsupportedOperationException()
        override suspend fun downloadSyncFile(token: String, trackId: String): HttpResponse = throw UnsupportedOperationException()
        override suspend fun listSyncFiles(token: String): AuraResponse<SyncedFileListResponseData> = throw UnsupportedOperationException()
        override suspend fun updateSyncFileMetadata(token: String, trackId: String, request: SyncedFileMetadataUpdateRequest): AuraResponse<SyncedFileResponseData> = throw UnsupportedOperationException()
        override suspend fun batchUpdateSyncFilesMetadata(token: String, items: List<SyncedFileMetadataUpdateRequest>): AuraResponse<SyncedFileBatchMetadataResponse> = throw UnsupportedOperationException()
        override suspend fun deleteSyncFile(token: String, trackId: String): AuraResponse<SyncedFileDeleteResponse> = throw UnsupportedOperationException()
        override suspend fun getAppVersion(platform: String): AuraResponse<AppVersionResponseData> = throw UnsupportedOperationException()
    }
}
