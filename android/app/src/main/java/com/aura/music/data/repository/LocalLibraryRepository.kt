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
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.media.MediaStoreAudioDataSource
import androidx.room.withTransaction
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
) {
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
        if (!mediaStoreAudioDataSource.hasReadPermission()) {
            return@withContext 0
        }

        val now = System.currentTimeMillis()
        val mediaFiles = mediaStoreAudioDataSource.getLocalAudioFiles()
        if (mediaFiles.isEmpty()) {
            return@withContext 0
        }

        val artists = mediaFiles
            .map { media ->
                ArtistEntity(
                    id = artistIdOf(media.artistName),
                    name = media.artistName,
                    normalizedName = normalize(media.artistName),
                    pictureUri = null,
                    summary = null,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            .distinctBy { it.id }

        val albums = mediaFiles
            .mapNotNull { media ->
                val albumTitle = media.albumTitle ?: return@mapNotNull null
                AlbumEntity(
                    id = albumIdOf(media.artistName, albumTitle),
                    primaryArtistId = artistIdOf(media.artistName),
                    title = albumTitle,
                    normalizedTitle = normalize(albumTitle),
                    coverUri = null,
                    releaseDate = null,
                    trackCount = null,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            .distinctBy { it.id }

        val tracks = mediaFiles.map { media ->
            TrackEntity(
                id = trackIdOf(media.mediaStoreId),
                primaryArtistId = artistIdOf(media.artistName),
                albumId = media.albumTitle?.let { albumIdOf(media.artistName, it) },
                title = media.title,
                normalizedTitle = normalize(media.title),
                displayArtistName = media.artistName,
                displayAlbumTitle = media.albumTitle,
                durationMs = media.durationMs,
                coverUri = null,
                canonicalAudioSourceType = "local",
                isLiked = false,
                isDownloadedByAura = false,
                isExplicit = null,
                popularity = null,
                genresJson = null,
                createdAt = now,
                updatedAt = now,
            )
        }

        val mediaLinks = mediaFiles.map { media ->
            TrackMediaLinkEntity(
                id = "media-link:${media.mediaStoreId}",
                trackId = trackIdOf(media.mediaStoreId),
                mediaStoreId = media.mediaStoreId,
                contentUri = media.contentUri,
                fileSizeBytes = media.fileSizeBytes,
                mimeType = media.mimeType,
                dateModifiedEpochMs = media.dateModifiedEpochMs,
                availabilityStatus = "present",
                lastScannedAt = now,
            )
        }

        database.artistDao().upsertArtists(artists)
        database.albumDao().upsertAlbums(albums)
        database.trackDao().upsertTracks(tracks)
        database.trackDao().upsertTrackMediaLinks(mediaLinks)

        mediaFiles.size
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

    suspend fun getBrowseAlbums(limit: Int = 8): List<AlbumBrowseRow> =
        withContext(Dispatchers.IO) { database.albumDao().getBrowseAlbums(limit) }

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
        playlistId
    }

    suspend fun renamePlaylist(playlistId: String, name: String) = withContext(Dispatchers.IO) {
        database.playlistDao().renamePlaylist(
            playlistId = playlistId,
            name = name.trim(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        database.playlistDao().deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(
        playlistId: String,
        trackId: String,
        contextType: String = "playlist_detail",
    ) = withContext(Dispatchers.IO) {
        val nextPosition = database.playlistDao().getNextPlaylistPosition(playlistId)
        val now = System.currentTimeMillis()
        database.withTransaction {
            database.playlistDao().insertPlaylistItem(
                PlaylistItemEntity(
                    id = "playlist-item:${UUID.randomUUID()}",
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

    suspend fun removeTrackFromPlaylist(playlistId: String, playlistItemId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.playlistDao().deletePlaylistItem(playlistItemId)
            normalizePlaylistPositions(playlistId)
            database.playlistDao().touchPlaylist(playlistId, System.currentTimeMillis())
        }
    }

    suspend fun movePlaylistItem(
        playlistId: String,
        playlistItemId: String,
        moveBy: Int,
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val items = database.playlistDao().getPlaylistTracks(playlistId).toMutableList()
            val currentIndex = items.indexOfFirst { it.playlistItemId == playlistItemId }
            if (currentIndex == -1) return@withTransaction
            val targetIndex = (currentIndex + moveBy).coerceIn(0, items.lastIndex)
            if (currentIndex == targetIndex) return@withTransaction

            val item = items.removeAt(currentIndex)
            items.add(targetIndex, item)

            items.forEachIndexed { index, row ->
                database.playlistDao().updatePlaylistItemPosition(row.playlistItemId, index)
            }
            database.playlistDao().touchPlaylist(playlistId, System.currentTimeMillis())
        }
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
                    durationMs = row.durationMs,
                    isLiked = false,
                )
            }
        }

    suspend fun getPlaylistCandidateTracks(): List<TrackListRow> =
        withContext(Dispatchers.IO) { database.trackDao().getAllTracks() }

    suspend fun getSettings(): UserSettingsEntity? =
        withContext(Dispatchers.IO) { database.userSettingsDao().getSettings() }

    suspend fun setSyncEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateSyncEnabled(enabled)
    }

    suspend fun setOnlineSearchEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateOnlineSearchEnabled(enabled)
    }

    suspend fun setOnlineSearchNetworkPolicy(policy: String) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateOnlineSearchNetworkPolicy(policy)
    }

    suspend fun setStatsSyncNetworkPolicy(policy: String) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateStatsSyncNetworkPolicy(policy)
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

    private suspend fun normalizePlaylistPositions(playlistId: String) {
        database.playlistDao().getPlaylistTracks(playlistId).forEachIndexed { index, row ->
            database.playlistDao().updatePlaylistItemPosition(row.playlistItemId, index)
        }
    }

    companion object {
        const val ACTIVE_SNAPSHOT_ID = "active"
        private const val DEFAULT_SETTINGS_ID = "default"

        fun trackIdOf(mediaStoreId: Long): String = "track:local:$mediaStoreId"

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
