package com.aura.music.data.repository

import com.aura.music.data.local.AlbumEntity
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.ArtistEntity
import com.aura.music.data.local.PlaybackSnapshotEntity
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.RecentSearchEntity
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.media.MediaStoreAudioDataSource
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

    suspend fun refreshLocalMediaIndex(limit: Int = 120): Int = withContext(Dispatchers.IO) {
        if (!mediaStoreAudioDataSource.hasReadPermission()) {
            return@withContext 0
        }

        val now = System.currentTimeMillis()
        val mediaFiles = mediaStoreAudioDataSource.getLocalAudioFiles(limit)
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
                mediaStoreAudioDataSource.getLocalAudioFiles(limit = 120).size
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

    companion object {
        const val ACTIVE_SNAPSHOT_ID = "active"
        private const val DEFAULT_SETTINGS_ID = "default"

        fun trackIdOf(mediaStoreId: Long): String = "track:local:$mediaStoreId"

        private fun artistIdOf(artistName: String): String = "artist:${normalize(artistName)}"

        private fun albumIdOf(artistName: String, albumTitle: String): String =
            "album:${normalize(artistName)}:${normalize(albumTitle)}"

        private fun normalize(value: String): String =
            value
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { UUID.randomUUID().toString() }
    }
}
