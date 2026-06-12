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
import androidx.room3.useWriterConnection
import androidx.room3.immediateTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
    private val context: android.content.Context,
) {
    private val syncRepository: SyncRepository get() = syncRepositoryProvider()
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

    suspend fun refreshLocalMediaIndex(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val scannedTracks = mutableListOf<TrackEntity>()
        val scannedMediaLinks = mutableListOf<TrackMediaLinkEntity>()
        val scannedArtists = mutableMapOf<String, ArtistEntity>()
        val scannedAlbums = mutableMapOf<String, AlbumEntity>()

        // 1. Scan Private downloads directory (always available, requires no permissions)
        val downloadsDir = java.io.File(context.filesDir, "downloads")
        if (downloadsDir.exists() && downloadsDir.isDirectory) {
            val supportedExtensions = setOf("mp3", "m4a", "wav")
            val audioFiles = downloadsDir.listFiles { file -> file.isFile && file.extension.lowercase() in supportedExtensions } ?: emptyArray()
            val retriever = android.media.MediaMetadataRetriever()
            for (file in audioFiles) {
                try {
                    retriever.setDataSource(file.absolutePath)
                    val rawTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val rawArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    val rawAlbum = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull()

                    val title = rawTitle?.ifBlank { null } ?: file.nameWithoutExtension
                    val artistName = rawArtist?.ifBlank { null } ?: "Unknown artist"
                    val albumTitle = rawAlbum?.ifBlank { null }

                    val trackId = file.nameWithoutExtension // Typically the download trackId
                    val artistId = artistIdOf(artistName)
                    val albumId = albumTitle?.let { albumIdOf(artistName, it) }
                    val fileUri = android.net.Uri.fromFile(file).toString()

                    val existingTrack = database.trackDao().getRawTrackById(trackId)
                    var coverUri: String? = existingTrack?.coverUri
                    if (coverUri == null) {
                        val embeddedPicture = retriever.embeddedPicture
                        if (embeddedPicture != null) {
                            try {
                                val coversDir = java.io.File(context.filesDir, "covers")
                                if (!coversDir.exists()) {
                                    coversDir.mkdirs()
                                }
                                val coverFile = java.io.File(coversDir, "$trackId.jpg")
                                java.io.FileOutputStream(coverFile).use { fos ->
                                    fos.write(embeddedPicture)
                                }
                                coverUri = android.net.Uri.fromFile(coverFile).toString()
                            } catch (e: Exception) {
                                android.util.Log.e("LocalLibraryRepository", "Failed to extract embedded cover for $trackId", e)
                            }
                        }
                    }

                    // Upsert artist
                    if (!scannedArtists.containsKey(artistId)) {
                        scannedArtists[artistId] = ArtistEntity(
                            id = artistId,
                            name = artistName,
                            normalizedName = normalize(artistName),
                            pictureUri = null,
                            summary = null,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }

                    // Upsert album
                    if (albumId != null && albumTitle != null) {
                        if (!scannedAlbums.containsKey(albumId)) {
                            scannedAlbums[albumId] = AlbumEntity(
                                id = albumId,
                                primaryArtistId = artistId,
                                title = albumTitle,
                                normalizedTitle = normalize(albumTitle),
                                coverUri = coverUri,
                                releaseDate = null,
                                trackCount = null,
                                createdAt = now,
                                updatedAt = now,
                            )
                        }
                    }

                    // Create track
                    scannedTracks.add(
                        TrackEntity(
                            id = trackId,
                            primaryArtistId = artistId,
                            albumId = albumId,
                            title = title,
                            normalizedTitle = normalize(title),
                            displayArtistName = artistName,
                            displayAlbumTitle = albumTitle,
                            durationMs = durationMs,
                            coverUri = coverUri,
                            canonicalAudioSourceType = "downloaded",
                            isLiked = existingTrack?.isLiked ?: false,
                            isDownloadedByAura = true,
                            isExplicit = null,
                            popularity = null,
                            genresJson = null,
                            createdAt = file.lastModified(),
                            updatedAt = file.lastModified(),
                        )
                    )

                    val mimeType = when (file.extension.lowercase()) {
                        "m4a" -> "audio/mp4"
                        "wav" -> "audio/wav"
                        else -> "audio/mpeg"
                    }

                    // Create media link
                    scannedMediaLinks.add(
                        TrackMediaLinkEntity(
                            id = "media-link:${file.nameWithoutExtension.hashCode()}",
                            trackId = trackId,
                            mediaStoreId = file.nameWithoutExtension.hashCode().toLong(),
                            contentUri = fileUri,
                            fileSizeBytes = file.length(),
                            mimeType = mimeType,
                            dateModifiedEpochMs = file.lastModified(),
                            availabilityStatus = "present",
                            lastScannedAt = now,
                        )
                    )
                } catch (e: Exception) {
                    android.util.Log.e("LocalLibraryRepository", "Error retrieving metadata for private file ${file.name}", e)
                }
            }
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }

        // 2. Scan MediaStore if permission is granted
        if (mediaStoreAudioDataSource.hasReadPermission()) {
            val mediaFiles = mediaStoreAudioDataSource.getLocalAudioFiles()
            for (media in mediaFiles) {
                val trackId = trackIdOf(media.mediaStoreId)
                val artistId = artistIdOf(media.artistName)
                val albumId = media.albumTitle?.let { albumIdOf(media.artistName, it) }

                // Upsert artist
                if (!scannedArtists.containsKey(artistId)) {
                    scannedArtists[artistId] = ArtistEntity(
                        id = artistId,
                        name = media.artistName,
                        normalizedName = normalize(media.artistName),
                        pictureUri = null,
                        summary = null,
                        createdAt = now,
                        updatedAt = now,
                    )
                }

                // Upsert album
                if (albumId != null && media.albumTitle != null) {
                    if (!scannedAlbums.containsKey(albumId)) {
                        scannedAlbums[albumId] = AlbumEntity(
                            id = albumId,
                            primaryArtistId = artistId,
                            title = media.albumTitle,
                            normalizedTitle = normalize(media.albumTitle),
                            coverUri = media.coverUri,
                            releaseDate = null,
                            trackCount = null,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                }

                // Create track
                val existingTrack = database.trackDao().getRawTrackById(trackId)
                scannedTracks.add(
                    TrackEntity(
                        id = trackId,
                        primaryArtistId = artistId,
                        albumId = albumId,
                        title = media.title,
                        normalizedTitle = normalize(media.title),
                        displayArtistName = media.artistName,
                        displayAlbumTitle = media.albumTitle,
                        durationMs = media.durationMs,
                        coverUri = media.coverUri,
                        canonicalAudioSourceType = "local",
                        isLiked = existingTrack?.isLiked ?: false,
                        isDownloadedByAura = false,
                        isExplicit = null,
                        popularity = null,
                        genresJson = null,
                        createdAt = media.dateModifiedEpochMs ?: now,
                        updatedAt = media.dateModifiedEpochMs ?: now,
                    )
                )

                // Create media link
                scannedMediaLinks.add(
                    TrackMediaLinkEntity(
                        id = "media-link:${media.mediaStoreId}",
                        trackId = trackId,
                        mediaStoreId = media.mediaStoreId,
                        contentUri = media.contentUri,
                        fileSizeBytes = media.fileSizeBytes,
                        mimeType = media.mimeType,
                        dateModifiedEpochMs = media.dateModifiedEpochMs,
                        availabilityStatus = "present",
                        lastScannedAt = now,
                    )
                )
            }
        }

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val existingLocalIds = database.trackDao().getLocalTrackIds()
                val existingDownloadedIds = database.trackDao().getDownloadedTrackIds()
                val scannedIds = scannedTracks.map { it.id }.toSet()

                val obsoleteIds = mutableListOf<String>()
                for (id in existingLocalIds) {
                    if (id !in scannedIds) {
                        obsoleteIds.add(id)
                    }
                }
                for (id in existingDownloadedIds) {
                    if (id !in scannedIds) {
                        obsoleteIds.add(id)
                    }
                }

                if (obsoleteIds.isNotEmpty()) {
                    database.trackDao().deleteTracksByIds(obsoleteIds)
                }

                if (scannedTracks.isNotEmpty()) {
                    database.artistDao().upsertArtists(scannedArtists.values.toList())
                    database.albumDao().upsertAlbums(scannedAlbums.values.toList())
                    database.trackDao().upsertTracks(scannedTracks)
                    database.trackDao().upsertTrackMediaLinks(scannedMediaLinks)
                }
            }
        }

        scannedTracks.size
    }

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

    suspend fun getAllTracks(): List<TrackListRow> =
        withContext(Dispatchers.IO) { database.trackDao().getAllTracks() }

    suspend fun getTrackById(trackId: String): TrackListRow? =
        withContext(Dispatchers.IO) { database.trackDao().getTrackById(trackId) }

    suspend fun getLikedTracks(): List<TrackListRow> =
        withContext(Dispatchers.IO) { database.trackDao().getLikedTracks() }

    suspend fun getDownloadedTracks(): List<TrackListRow> =
        withContext(Dispatchers.IO) { database.trackDao().getDownloadedTracks() }

    suspend fun searchLocalTracks(query: String, limit: Int = 12): List<TrackListRow> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                emptyList()
            } else {
                database.trackDao().searchTracks(query.trim(), limit)
            }
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

    suspend fun getBrowseArtists(limit: Int = 8): List<ArtistBrowseRow> =
        withContext(Dispatchers.IO) { database.artistDao().getBrowseArtists(limit) }

    suspend fun getAllBrowseArtists(): List<ArtistBrowseRow> =
        withContext(Dispatchers.IO) { database.artistDao().getAllBrowseArtists() }

    suspend fun getBrowseAlbums(limit: Int = 8): List<AlbumBrowseRow> =
        withContext(Dispatchers.IO) { database.albumDao().getBrowseAlbums(limit) }

    suspend fun getAllBrowseAlbums(): List<AlbumBrowseRow> =
        withContext(Dispatchers.IO) { database.albumDao().getAllBrowseAlbums() }

    suspend fun searchLocalArtists(query: String, limit: Int = 8): List<ArtistBrowseRow> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) emptyList()
            else database.artistDao().searchArtists(query.trim(), limit)
        }

    suspend fun searchLocalAlbums(query: String, limit: Int = 8): List<AlbumBrowseRow> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) emptyList()
            else database.albumDao().searchAlbums(query.trim(), limit)
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
        withContext(Dispatchers.IO) {
            val summary = database.playlistDao().getPlaylistDetail(playlistId) ?: return@withContext null
            PlaylistDetail(
                summary = summary,
                tracks = database.playlistDao().getPlaylistTracks(playlistId),
            )
        }

    suspend fun createPlaylist(name: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val playlistId = "playlist:${normalize(name)}:${UUID.randomUUID().toString().take(8)}"
        database.playlistDao().insertPlaylist(
            PlaylistEntity(
                id = playlistId,
                name = name.trim(),
                coverUri = null,
                isPinned = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
        syncRepository.recordLocalOperation(
            entityType = "playlist",
            entityId = playlistId,
            operationType = "create",
            payload = mapOf(
                "name" to name.trim(),
                "is_pinned" to false,
                "cover_uri" to null,
                "created_at" to formatMillisToIsoDate(now),
                "updated_at" to formatMillisToIsoDate(now)
            )
        )
        playlistId
    }

    suspend fun renamePlaylist(playlistId: String, name: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.playlistDao().renamePlaylist(
            playlistId = playlistId,
            name = name.trim(),
            updatedAt = now,
        )
        syncRepository.recordLocalOperation(
            entityType = "playlist",
            entityId = playlistId,
            operationType = "update",
            payload = mapOf(
                "name" to name.trim(),
                "updated_at" to formatMillisToIsoDate(now)
            )
        )
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        database.playlistDao().deletePlaylist(playlistId)
        syncRepository.recordLocalOperation(
            entityType = "playlist",
            entityId = playlistId,
            operationType = "delete",
            payload = emptyMap()
        )
    }

    suspend fun addTrackToPlaylist(
        playlistId: String,
        trackId: String,
        contextType: String = "playlist_detail",
    ) = withContext(Dispatchers.IO) {
        val nextPosition = database.playlistDao().getNextPlaylistPosition(playlistId)
        val now = System.currentTimeMillis()
        val itemId = "playlist-item:${UUID.randomUUID()}"
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().insertPlaylistItem(
                    PlaylistItemEntity(
                        id = itemId,
                        playlistId = playlistId,
                        trackId = trackId,
                        position = nextPosition,
                        addedAt = now,
                        addedFromContextType = contextType,
                        addedFromContextId = playlistId,
                    ),
                )
                database.playlistDao().touchPlaylist(playlistId, now)
            }
        }
        syncRepository.recordLocalOperation(
            entityType = "playlist_item",
            entityId = itemId,
            operationType = "create",
            payload = mapOf(
                "playlist_id" to playlistId,
                "track_id" to trackId,
                "position" to nextPosition,
                "added_at" to formatMillisToIsoDate(now),
                "added_from_context_type" to contextType,
                "added_from_context_id" to playlistId
            )
        )
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, playlistItemId: String) = withContext(Dispatchers.IO) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().deletePlaylistItem(playlistItemId)
                normalizePlaylistPositions(playlistId)
                database.playlistDao().touchPlaylist(playlistId, System.currentTimeMillis())
            }
        }
        syncRepository.recordLocalOperation(
            entityType = "playlist_item",
            entityId = playlistItemId,
            operationType = "delete",
            payload = mapOf(
                "playlist_id" to playlistId
            )
        )
    }

    suspend fun movePlaylistItem(
        playlistId: String,
        playlistItemId: String,
        moveBy: Int,
    ) = withContext(Dispatchers.IO) {
        var baseOrderToken = ""
        val itemsToReorder = mutableListOf<Map<String, Any?>>()
        
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val items = database.playlistDao().getPlaylistTracks(playlistId).toMutableList()
                val pl = database.playlistDao().getPlaylistDetail(playlistId)
                if (pl != null) {
                    val updatedStr = formatMillisToIsoDate(pl.updatedAt)
                    val digest = java.security.MessageDigest.getInstance("MD5").digest(updatedStr.toByteArray(Charsets.UTF_8))
                    val hex = digest.joinToString("") { "%02x".format(it) }
                    baseOrderToken = "ord_${hex.take(8)}"
                }

                val currentIndex = items.indexOfFirst { it.playlistItemId == playlistItemId }
                if (currentIndex == -1) return@immediateTransaction
                val targetIndex = (currentIndex + moveBy).coerceIn(0, items.lastIndex)
                if (currentIndex == targetIndex) return@immediateTransaction

                val item = items.removeAt(currentIndex)
                items.add(targetIndex, item)

                items.forEachIndexed { index, row ->
                    database.playlistDao().updatePlaylistItemPosition(row.playlistItemId, index)
                    itemsToReorder.add(
                        mapOf(
                            "playlist_item_id" to row.playlistItemId,
                            "position" to index
                        )
                    )
                }
                database.playlistDao().touchPlaylist(playlistId, System.currentTimeMillis())
            }
        }

        syncRepository.recordLocalOperation(
            entityType = "playlist_reorder",
            entityId = playlistId,
            operationType = "update",
            payload = mapOf(
                "base_order_token" to baseOrderToken,
                "items" to itemsToReorder
            )
        )
    }

    suspend fun getPlaylistTrackQueue(playlistId: String): List<TrackListRow> =
        withContext(Dispatchers.IO) {
            database.playlistDao().getPlaylistTracks(playlistId).map { row ->
                TrackListRow(
                    id = row.trackId,
                    artistId = null,
                    albumId = null,
                    title = row.title,
                    artistName = row.artistName,
                    albumTitle = row.albumTitle,
                    contentUri = row.contentUri,
                    coverUri = row.coverUri,
                    durationMs = row.durationMs,
                    isLiked = row.isLiked,
                )
            }
        }

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
     * La transaction garantit l'invaiant : tracks.is_liked reflete track_likes.
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
            }
        }
        syncRepository.recordLocalOperation(
            entityType = "track_like",
            entityId = trackId,
            operationType = "set",
            payload = mapOf(
                "track_id" to trackId,
                "is_liked" to !currentlyLiked,
                "liked_at" to formatMillisToIsoDate(now),
                "source_context_type" to contextType,
                "source_context_id" to contextId
            )
        )
    }

    suspend fun seedPlaybackPreview(trackId: String, contextType: String = "single_track") =
        withContext(Dispatchers.IO) {
            database.playbackSnapshotDao().upsert(
                PlaybackSnapshotEntity(
                    id = ACTIVE_SNAPSHOT_ID,
                    currentTrackId = trackId,
                    playbackContextType = contextType,
                    playbackContextId = trackId,
                    playbackContextIndex = 0,
                    positionMs = 0L,
                    shuffleEnabled = false,
                    repeatMode = "off",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
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
                    securityExceptionThrown = true
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val recoverableSecurityException = securityException as? android.app.RecoverableSecurityException
                        if (recoverableSecurityException != null) {
                            pendingIntent = recoverableSecurityException.userAction.actionIntent
                        } else {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                val uriList = listOf(android.net.Uri.parse(uriString))
                                pendingIntent = android.provider.MediaStore.createDeleteRequest(context.contentResolver, uriList)
                            }
                        }
                    } else {
                        throw securityException
                    }
                }
            }
        }
        
        if (!securityExceptionThrown) {
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    database.trackDao().deleteTracksByIds(listOf(trackId))
                    val downloadsDir = java.io.File(context.filesDir, "downloads")
                    val targetFile = java.io.File(downloadsDir, "$trackId.mp3")
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                }
            }
        }
        pendingIntent
    }

    private suspend fun normalizePlaylistPositions(playlistId: String) {
        database.playlistDao().getPlaylistTracks(playlistId).forEachIndexed { index, row ->
            database.playlistDao().updatePlaylistItemPosition(row.playlistItemId, index)
        }
    }

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
