package com.aura.music.data.repository

import com.aura.music.data.local.AlbumEntity
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.AlbumDetailRow
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.ArtistEntity
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.ArtistDetailRow
import com.aura.music.data.local.PlaybackSnapshotEntity
import com.aura.music.data.local.PlaylistDetailRow
import com.aura.music.data.local.PlaylistEntity
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.PlaylistItemEntity
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.RecentSearchEntity
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.TrackLikeEntity
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.NetworkPolicyChecker
import android.util.Log
import androidx.room3.useWriterConnection
import androidx.room3.immediateTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aura.music.domain.search.LocalSearchEngine
import com.aura.music.domain.search.LocalSearchIndex
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

data class LibraryDashboardSummary(
    val hasAudioPermission: Boolean,
    val roomTrackCount: Int,
    val mediaStoreTrackCount: Int,
    val playlistCount: Int,
    val recentSearchCount: Int,
    val activeSnapshot: PlaybackSnapshotEntity?,
)

data class PlaylistDetail(
    val summary: PlaylistDetailRow,
    val tracks: List<PlaylistTrackRow>,
)

data class ArtistDetail(
    val summary: ArtistDetailRow,
    val topTracks: List<TrackListRow>,
    val albums: List<AlbumBrowseRow>,
)

data class AlbumDetail(
    val summary: AlbumDetailRow,
    val tracks: List<TrackListRow>,
)

class LocalLibraryRepository(
    private val database: AuraDatabase,
    private val mediaStoreAudioDataSource: MediaStoreAudioDataSource,
    private val syncRepositoryProvider: () -> SyncRepository,
    private val cloudFileRepositoryProvider: (() -> CloudFileRepository)? = null,
    private val apiService: AuraApiService,
    private val context: android.content.Context,
) {
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncRepository: SyncRepository get() = syncRepositoryProvider()
    private val searchIndexRef = AtomicReference<LocalSearchIndex?>(null)

    private val localMediaScanner by lazy {
        com.aura.music.data.media.LocalMediaScanner(
            database = database,
            mediaStoreAudioDataSource = mediaStoreAudioDataSource,
            context = context,
            onInvalidateSearchIndex = { invalidateSearchIndex() }
        )
    }

    private val audioMetadataEditor by lazy {
        com.aura.music.data.metadata.AudioMetadataEditor(
            database = database,
            context = context,
            onInvalidateSearchIndex = { invalidateSearchIndex() }
        )
    }

    private val playlistManager by lazy {
        PlaylistManager(
            database = database,
            syncRepositoryProvider = syncRepositoryProvider
        )
    }

    private fun getAuthToken(): String = com.aura.music.core.AuthSessionManager.getInstance(context).getBearerHeader()

    suspend fun getOrBuildSearchIndex(): LocalSearchIndex {
        val cached = searchIndexRef.get()
        if (cached != null) return cached

        return withContext(Dispatchers.Default) {
            val allTracks = database.trackDao().getAllTracks()
            val allArtists = database.artistDao().getAllBrowseArtists()
            val allAlbums = database.albumDao().getAllBrowseAlbums()

            val built = LocalSearchIndex.build(allTracks, allArtists, allAlbums)
            searchIndexRef.set(built)
            built
        }
    }

    fun invalidateSearchIndex() {
        searchIndexRef.set(null)
    }
    suspend fun ensureDefaults() = withContext(Dispatchers.IO) {
        if (database.userSettingsDao().getSettings() == null) {
            database.userSettingsDao().insertOrReplace(
                UserSettingsEntity(
                    id = DEFAULT_SETTINGS_ID,
                    syncEnabled = false,
                    onlineSearchEnabled = true,
                    onlineSearchNetworkPolicy = "wifi_only",
                    statsSyncNetworkPolicy = "wifi_only",
                    lastSyncAt = null,
                ),
            )
        }
    }

    private suspend fun shouldSyncDirectly(): Boolean {
        val settings = database.userSettingsDao().getSettings()
        return settings != null && settings.syncEnabled && NetworkPolicyChecker.isConnected(context)
    }

    suspend fun refreshLocalMediaIndex(): Int = localMediaScanner.syncLocalMedia()

    suspend fun getLibraryDashboardSummary(): LibraryDashboardSummary = coroutineScope {
        val hasPermission = mediaStoreAudioDataSource.hasReadPermission()
        val roomTrackCountDeferred = async { database.trackDao().getTrackCount() }
        val playlistCountDeferred = async { database.playlistDao().getPlaylistCount() }
        val recentSearchCountDeferred = async { database.recentSearchDao().getRecentSearchCount() }
        val snapshotDeferred = async { database.playbackSnapshotDao().getActiveSnapshot() }
        val mediaCountDeferred = async {
            if (hasPermission) {
                mediaStoreAudioDataSource.getLocalAudioFiles().size
            } else {
                0
            }
        }

        LibraryDashboardSummary(
            hasAudioPermission = hasPermission,
            roomTrackCount = roomTrackCountDeferred.await(),
            mediaStoreTrackCount = mediaCountDeferred.await(),
            playlistCount = playlistCountDeferred.await(),
            recentSearchCount = recentSearchCountDeferred.await(),
            activeSnapshot = snapshotDeferred.await(),
        )
    }

    suspend fun getRecentTracks(limit: Int = 12): List<TrackListRow> =
        withContext(Dispatchers.IO) { database.trackDao().getRecentTracks(limit) }

    suspend fun recordHistoryItem(
        trackId: String,
        contextType: String? = null,
        contextId: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val historyId = "history_item:${UUID.randomUUID()}"
        syncRepository.recordLocalOperation(
            entityType = "history_item",
            entityId = historyId,
            operationType = "create",
            payload = mapOf(
                "track_id" to trackId,
                "played_at" to formatMillisToIsoDate(now),
                "completion_percent" to 1.0,
                "was_skipped" to false,
                "source_context_type" to contextType,
                "source_context_id" to contextId
            )
        )
    }

    suspend fun getRecentPlaybackHistory(limit: Int = 12): List<TrackListRow> = withContext(Dispatchers.IO) {
        if (shouldSyncDirectly()) {
            try {
                val response = apiService.getHistory(getAuthToken())
                val data = response.data
                if (response.error == null && data != null) {
                    val historyItems = data.items.take(limit)
                    val tracks = historyItems.mapNotNull { item ->
                        database.trackDao().getTrackById(item.trackId)
                    }
                    return@withContext tracks
                }
            } catch (e: Exception) {
                Log.e("LocalLibraryRepository", "Failed to fetch live playback history: ${e.message}", e)
            }
        }
        database.trackDao().getRecentTracks(limit)
    }

    suspend fun getAllTracks(): List<TrackListRow> =
        withContext(Dispatchers.IO) { database.trackDao().getAllTracks() }

    suspend fun getTrackById(trackId: String): TrackListRow? =
        withContext(Dispatchers.IO) { database.trackDao().getTrackById(trackId) }

    suspend fun getLikedTracks(): List<TrackListRow> =
        withContext(Dispatchers.IO) { database.trackDao().getLikedTracks() }

    suspend fun getDownloadedTracks(): List<TrackListRow> =
        withContext(Dispatchers.IO) { database.trackDao().getDownloadedTracks() }

    suspend fun searchLocalTracks(query: String, limit: Int = 12): List<TrackListRow> =
        withContext(Dispatchers.Default) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                emptyList()
            } else {
                val index = getOrBuildSearchIndex()
                LocalSearchEngine.searchTracks(
                    index = index,
                    query = trimmed,
                    limit = limit
                )
            }
        }

    suspend fun upsertCloudTrackPlaceholder(
        title: String,
        artistName: String,
        albumTitle: String? = null,
        durationMs: Long? = null,
        coverUri: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val normArtist = normalize(artistName)
        val normTitle = normalize(title)
        val cleanArtist = normArtist.filter { it.isLetterOrDigit() || it == '_' }
        val cleanTitle = normTitle.filter { it.isLetterOrDigit() || it == '_' }
        val trackId = "trk_cloud_${cleanArtist}_${cleanTitle}".take(64)

        val existing = database.trackDao().getTrackById(trackId)
        if (existing != null) return@withContext trackId

        val now = System.currentTimeMillis()
        val artistId = "art_cloud_${cleanArtist}".take(64)
        val albumId = albumTitle?.takeIf { it.isNotBlank() }?.let { "alb_cloud_${normalize(it).filter { it.isLetterOrDigit() || it == '_' }}".take(64) }

        database.artistDao().insertArtistsIgnore(
            listOf(
                com.aura.music.data.local.ArtistEntity(
                    id = artistId,
                    name = artistName,
                    normalizedName = normArtist,
                    pictureUri = coverUri,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        )

        if (albumId != null && !albumTitle.isNullOrBlank()) {
            database.albumDao().insertAlbumsIgnore(
                listOf(
                    com.aura.music.data.local.AlbumEntity(
                        id = albumId,
                        primaryArtistId = artistId,
                        title = albumTitle,
                        normalizedTitle = normalize(albumTitle),
                        coverUri = coverUri,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            )
        }

        database.trackDao().upsertTrack(
            com.aura.music.data.local.TrackEntity(
                id = trackId,
                primaryArtistId = artistId,
                albumId = albumId,
                title = title,
                normalizedTitle = normTitle,
                displayArtistName = artistName,
                displayAlbumTitle = albumTitle,
                durationMs = durationMs,
                coverUri = coverUri,
                canonicalAudioSourceType = "cloud",
                isLiked = false,
                isDownloadedByAura = false, // Pas de téléchargement local automatique sur le téléphone
                createdAt = now,
                updatedAt = now,
            )
        )

        trackId
    }

    suspend fun saveRecentSearch(query: String) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return@withContext
        }

        database.recentSearchDao().upsert(
            RecentSearchEntity(
                id = "recent-search:${normalize(trimmed)}",
                query = trimmed,
                searchedAt = System.currentTimeMillis(),
            ),
        )
        database.recentSearchDao().trimTo(10)
    }

    suspend fun getRecentQueries(limit: Int = 10): List<String> =
        withContext(Dispatchers.IO) { database.recentSearchDao().getRecentQueries(limit) }

    suspend fun getPlaylists(): List<PlaylistListRow> =
        withContext(Dispatchers.IO) { database.playlistDao().getPlaylists() }

    suspend fun getPlaylistCoverPreviews(playlistId: String): List<String> =
        withContext(Dispatchers.IO) { database.playlistDao().getPlaylistCoverPreviews(playlistId) }

    suspend fun getBrowseArtists(limit: Int = 8): List<ArtistBrowseRow> =
        withContext(Dispatchers.IO) { database.artistDao().getBrowseArtists(limit) }

    suspend fun getAllBrowseArtists(): List<ArtistBrowseRow> =
        withContext(Dispatchers.IO) { database.artistDao().getAllBrowseArtists() }

    suspend fun getBrowseAlbums(limit: Int = 8): List<AlbumBrowseRow> =
        withContext(Dispatchers.IO) { database.albumDao().getBrowseAlbums(limit) }

    suspend fun getAllBrowseAlbums(): List<AlbumBrowseRow> =
        withContext(Dispatchers.IO) { database.albumDao().getAllBrowseAlbums() }

    suspend fun searchLocalArtists(query: String, limit: Int = 8): List<ArtistBrowseRow> =
        withContext(Dispatchers.Default) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                emptyList()
            } else {
                val index = getOrBuildSearchIndex()
                LocalSearchEngine.searchArtists(
                    index = index,
                    query = trimmed,
                    limit = limit
                )
            }
        }

    suspend fun searchLocalAlbums(query: String, limit: Int = 8): List<AlbumBrowseRow> =
        withContext(Dispatchers.Default) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                emptyList()
            } else {
                val index = getOrBuildSearchIndex()
                LocalSearchEngine.searchAlbums(
                    index = index,
                    query = trimmed,
                    limit = limit
                )
            }
        }

    suspend fun getArtistDetail(
        artistId: String,
        topTrackLimit: Int = 8,
        albumLimit: Int = 12,
    ): ArtistDetail? = withContext(Dispatchers.IO) {
        val summary = database.artistDao().getArtistDetail(artistId) ?: return@withContext null
        ArtistDetail(
            summary = summary,
            topTracks = database.trackDao().getTracksForArtist(artistId, topTrackLimit),
            albums = database.albumDao().getAlbumsForArtist(artistId, albumLimit),
        )
    }

    suspend fun getAlbumDetail(albumId: String): AlbumDetail? = withContext(Dispatchers.IO) {
        val summary = database.albumDao().getAlbumDetail(albumId) ?: return@withContext null
        AlbumDetail(
            summary = summary,
            tracks = database.trackDao().getTracksForAlbum(albumId),
        )
    }

    suspend fun getTracksForAlbum(albumId: String): List<TrackListRow> = withContext(Dispatchers.IO) {
        database.trackDao().getTracksForAlbum(albumId)
    }

    suspend fun getTracksForAlbumByText(albumTitle: String, artistName: String): List<TrackListRow> = withContext(Dispatchers.IO) {
        database.trackDao().getTracksForAlbumByText(albumTitle, artistName)
    }

    suspend fun getAlbumByTitleAndArtist(title: String, artistName: String): AlbumBrowseRow? = withContext(Dispatchers.IO) {
        database.albumDao().getAlbumByTitleAndArtist(title, artistName)
    }

    suspend fun getPlaylistDetail(playlistId: String): PlaylistDetail? =
        playlistManager.getPlaylistDetail(playlistId)

    suspend fun createPlaylist(name: String): String =
        playlistManager.createPlaylist(name)

    suspend fun renamePlaylist(playlistId: String, name: String) =
        playlistManager.renamePlaylist(playlistId, name)

    suspend fun deletePlaylist(playlistId: String) =
        playlistManager.deletePlaylist(playlistId)

    suspend fun addTrackToPlaylist(
        playlistId: String,
        trackId: String,
        contextType: String = "playlist_detail",
    ) = playlistManager.addTrackToPlaylist(playlistId, trackId, contextType)

    suspend fun removeTrackFromPlaylist(playlistId: String, playlistItemId: String) =
        playlistManager.removeTrackFromPlaylist(playlistId, playlistItemId)

    suspend fun deduplicatePlaylist(playlistId: String): Int =
        playlistManager.deduplicatePlaylist(playlistId)

    suspend fun movePlaylistItem(
        playlistId: String,
        playlistItemId: String,
        moveBy: Int,
    ) = playlistManager.movePlaylistItem(playlistId, playlistItemId, moveBy)

    suspend fun getPlaylistTrackQueue(playlistId: String): List<TrackListRow> =
        playlistManager.getPlaylistTrackQueue(playlistId)

    suspend fun getPlaylistCandidateTracks(): List<TrackListRow> =
        withContext(Dispatchers.IO) { database.trackDao().getAllTracks() }

    suspend fun getSettings(): UserSettingsEntity? =
        withContext(Dispatchers.IO) { database.userSettingsDao().getSettings() }

    suspend fun setSyncEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateSyncEnabled(enabled)
        // If enabling sync, record it and trigger immediate sync
        if (enabled) {
            recordUserSettingsMutation()
            syncRepository.schedulePeriodicSync()
        }
    }

    suspend fun setOnlineSearchEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateOnlineSearchEnabled(enabled)
        recordUserSettingsMutation()
    }

    suspend fun setOnlineSearchNetworkPolicy(policy: String) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateOnlineSearchNetworkPolicy(policy)
        recordUserSettingsMutation()
    }

    suspend fun setStatsSyncNetworkPolicy(policy: String) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateStatsSyncNetworkPolicy(policy)
        recordUserSettingsMutation()
    }

    private suspend fun recordUserSettingsMutation() {
        val settings = database.userSettingsDao().getSettings() ?: return
        syncRepository.recordLocalOperation(
            entityType = "user_settings",
            entityId = DEFAULT_SETTINGS_ID,
            operationType = "set",
            payload = mapOf(
                "online_search_enabled" to settings.onlineSearchEnabled,
                "online_search_network_policy" to settings.onlineSearchNetworkPolicy,
                "stats_sync_network_policy" to settings.statsSyncNetworkPolicy
            )
        )
    }

    /**
     * Bascule l'etat de like d'une piste de maniere atomique.
     * La transaction garantit l'invariant : tracks.is_liked reflete track_likes.
     * Gouverne par : docs/android/room-schema.md, docs/android/local-persistence.md
     *
     * @param trackId identifiant AURA de la piste
     * @param currentlyLiked etat connu au moment du toggle (optimise la lecture avant ecriture)
     * @param contextType contexte source du like (optionnel)
     * @param contextId identifiant du contexte source (optionnel)
     */
    suspend fun toggleLike(
        trackId: String,
        currentlyLiked: Boolean,
        contextType: String? = null,
        contextId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // 1. Mise à jour Room locale synchrone immédiate (0 ms)
        val outboxOp = syncRepository.createOutboxEntity(
            entityType = "track_like",
            entityId = trackId,
            operationType = "set",
            payload = mapOf(
                "track_id" to trackId,
                "is_liked" to !currentlyLiked,
                "liked_at" to if (!currentlyLiked) formatMillisToIsoDate(now) else null,
                "source_context_type" to contextType,
                "source_context_id" to contextId
            )
        )

        // 1. Mise à jour Room locale et enregistrement Outbox synchrones et atomiques (0 ms)
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (currentlyLiked) {
                    database.trackLikeDao().deleteLike(trackId)
                    database.trackLikeDao().setTrackIsLiked(trackId, liked = false, updatedAt = now)
                } else {
                    database.trackLikeDao().insertLike(
                        TrackLikeEntity(
                            trackId = trackId,
                            likedAt = now,
                            sourceContextType = contextType,
                            sourceContextId = contextId,
                        ),
                    )
                    database.trackLikeDao().setTrackIsLiked(trackId, liked = true, updatedAt = now)
                }
                database.syncOutboxDao().insert(outboxOp)
            }
        }

        // 2. Déclenchement de la synchronisation en arrière-plan et auto-téléchargement si activé
        repositoryScope.launch {
            try {
                syncRepository.triggerManualSync()

                if (!currentlyLiked) {
                    val prefs = context.getSharedPreferences("aura_prefs", android.content.Context.MODE_PRIVATE)
                    if (prefs.getBoolean("auto_download_favorites", false)) {
                        try {
                            cloudFileRepositoryProvider?.invoke()?.autoDownloadFavoriteTrack(trackId)
                        } catch (e: Exception) {
                            Log.w("LocalLibraryRepository", "Auto download favorite failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("LocalLibraryRepository", "Background like sync error: ${e.message}")
            }
        }
    }

    suspend fun seedPlaybackPreview(trackId: String, contextType: String = "single_track") =
        withContext(Dispatchers.IO) {
            database.playbackSnapshotDao().upsert(
                PlaybackSnapshotEntity(
                    id = ACTIVE_SNAPSHOT_ID,
                    currentTrackId = trackId,
                    playbackContextType = contextType,
                    playbackContextId = null,
                    playbackContextIndex = 0,
                    positionMs = 0L,
                    shuffleEnabled = false,
                    repeatMode = "off",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

    suspend fun removeTrackFromDatabase(trackId: String) = withContext(Dispatchers.IO) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val isCloudSynced = cloudFileRepositoryProvider?.invoke()?.isCloudTrackSynced(trackId) == true
                val downloadsDir = java.io.File(context.filesDir, "downloads")
                val targetFile = java.io.File(downloadsDir, "${trackId.replace(':', ';')}.mp3")
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (isCloudSynced) {
                    // Conserve la ligne TrackEntity dans Room pour le streaming Cloud, supprime juste le lien local
                    database.trackDao().deleteTrackMediaLinksByTrackId(trackId)
                } else {
                    // Non synchronisé au Cloud : suppression définitive de la base Room
                    database.trackDao().deleteTracksByIds(listOf(trackId))
                }
            }
        }
        invalidateSearchIndex()
    }

    suspend fun deleteTrack(trackId: String): android.app.PendingIntent? = withContext(Dispatchers.IO) {
        var pendingIntent: android.app.PendingIntent? = null
        val track = database.trackDao().getTrackById(trackId)
        
        var securityExceptionThrown = false
        
        track?.contentUri?.let { uriString ->
            if (uriString.startsWith("content://")) {
                try {
                    val uri = android.net.Uri.parse(uriString)
                    context.contentResolver.delete(uri, null, null)
                } catch (securityException: SecurityException) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val recoverableSecurityException = securityException as? android.app.RecoverableSecurityException
                        if (recoverableSecurityException != null) {
                            pendingIntent = recoverableSecurityException.userAction.actionIntent
                            securityExceptionThrown = true
                        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            val uriList = listOf(android.net.Uri.parse(uriString))
                            pendingIntent = android.provider.MediaStore.createDeleteRequest(context.contentResolver, uriList)
                            securityExceptionThrown = true
                        } else {
                            securityExceptionThrown = true
                        }
                    } else {
                        securityExceptionThrown = true
                    }
                } catch (e: Exception) {
                    Log.w("LocalLibraryRepository", "MediaStore deletion non-security error: ${e.message}")
                }
            }
        }
        
        if (!securityExceptionThrown) {
            removeTrackFromDatabase(trackId)
        }
        pendingIntent
    }

    suspend fun updateTrackMetadata(
        trackId: String,
        newTitle: String,
        newArtistName: String,
        newAlbumTitle: String?,
        coverSourceUriOrUrl: String? = null,
        coverSourceBytes: ByteArray? = null,
        trackNumber: String? = null,
        year: String? = null,
    ): Result<TrackListRow> = audioMetadataEditor.updateTrackMetadata(
        trackId = trackId,
        newTitle = newTitle,
        newArtistName = newArtistName,
        newAlbumTitle = newAlbumTitle,
        coverSourceUriOrUrl = coverSourceUriOrUrl,
        coverSourceBytes = coverSourceBytes,
        trackNumber = trackNumber,
        year = year,
    )

    companion object {
        const val ACTIVE_SNAPSHOT_ID = "active"
        private const val DEFAULT_SETTINGS_ID = "default"

        fun trackIdOf(mediaStoreId: Long): String = "track:local:$mediaStoreId"

        fun formatMillisToIsoDate(millis: Long): String {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.time.Instant.ofEpochMilli(millis).toString()
            } else {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.format(java.util.Date(millis))
            }
        }

        private fun artistIdOf(artistName: String): String = "artist:${normalize(artistName)}"

        private fun albumIdOf(artistName: String, albumTitle: String): String =
            "album:${normalize(artistName)}:${normalize(albumTitle)}"

        private fun normalize(value: String): String {
            val slug = value
                .trim()
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
                .trim('-')
            if (slug.isNotBlank()) return slug
            // Fallback deterministe : hash SHA-256 tronque pour les noms
            // composes uniquement de ponctuation ou vides.
            val bytes = value.trim().lowercase().toByteArray(Charsets.UTF_8)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = digest.joinToString("") { "%02x".format(it) }
            return hex.take(16).ifBlank { "unknown" }
        }
    }
}
