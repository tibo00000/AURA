package com.aura.music.data.repository

import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.PlaylistDetailRow
import com.aura.music.data.local.PlaylistEntity
import com.aura.music.data.local.PlaylistItemEntity
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import android.util.Log
import androidx.room3.useWriterConnection
import androidx.room3.immediateTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface AddToPlaylistResult {
    data class Success(val playlistName: String) : AddToPlaylistResult
    data class AlreadyExists(val trackId: String, val playlistId: String, val playlistName: String) : AddToPlaylistResult
    data class Error(val message: String) : AddToPlaylistResult
}

/**
 * Gestionnaire dédié des playlists et items de playlist.
 * Porte l'intégralité de la logique de modification et garantit l'atomicité
 * Room + Outbox (P1-3 / P2-1) au sein d'une unique transaction immédiate.
 */
class PlaylistManager(
    private val database: AuraDatabase,
    private val syncRepositoryProvider: () -> SyncRepository,
    private val ensureTrackExists: (suspend (
        trackId: String,
        title: String?,
        artistName: String?,
        albumTitle: String?,
        durationMs: Long?,
        coverUri: String?,
        contextType: String?
    ) -> Unit)? = null,
    private val cloudFileRepositoryProvider: (() -> CloudFileRepository)? = null,
    private val context: android.content.Context? = null,
) {
    private val syncRepository: SyncRepository get() = syncRepositoryProvider()

    suspend fun getPlaylistDetail(playlistId: String): PlaylistDetail? = withContext(Dispatchers.IO) {
        val summary = database.playlistDao().getPlaylistDetail(playlistId) ?: return@withContext null
        PlaylistDetail(
            summary = summary,
            tracks = database.playlistDao().getPlaylistTracks(playlistId),
        )
    }

    suspend fun createPlaylist(name: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val playlistId = "playlist:${normalize(name)}:${UUID.randomUUID().toString().take(8)}"
        val outboxOp = syncRepository.createOutboxEntity(
            entityType = "playlist",
            entityId = playlistId,
            operationType = "create",
            payload = mapOf(
                "name" to name.trim(),
                "is_pinned" to false,
                "cover_uri" to null,
                "created_at" to LocalLibraryRepository.formatMillisToIsoDate(now),
                "updated_at" to LocalLibraryRepository.formatMillisToIsoDate(now)
            )
        )
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
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
                database.syncOutboxDao().insert(outboxOp)
            }
        }
        syncRepository.triggerManualSync()
        playlistId
    }

    suspend fun renamePlaylist(playlistId: String, name: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val outboxOp = syncRepository.createOutboxEntity(
            entityType = "playlist",
            entityId = playlistId,
            operationType = "update",
            payload = mapOf(
                "name" to name.trim(),
                "updated_at" to LocalLibraryRepository.formatMillisToIsoDate(now)
            )
        )
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().renamePlaylist(
                    playlistId = playlistId,
                    name = name.trim(),
                    updatedAt = now,
                )
                database.syncOutboxDao().insert(outboxOp)
            }
        }
        syncRepository.triggerManualSync()
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        val outboxOp = syncRepository.createOutboxEntity(
            entityType = "playlist",
            entityId = playlistId,
            operationType = "delete",
            payload = emptyMap()
        )
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().deletePlaylist(playlistId)
                database.syncOutboxDao().insert(outboxOp)
            }
        }
        syncRepository.triggerManualSync()
    }

    suspend fun addTrackToPlaylist(
        playlistId: String,
        trackId: String,
        contextType: String = "playlist_detail",
        allowDuplicate: Boolean = false,
        title: String? = null,
        artistName: String? = null,
        albumTitle: String? = null,
        durationMs: Long? = null,
        coverUri: String? = null,
    ): AddToPlaylistResult = withContext(Dispatchers.IO) {
        try {
            val result = database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    val playlistSummary = database.playlistDao().getPlaylistDetail(playlistId)
                    val playlistName = playlistSummary?.name ?: "Playlist"

                    val alreadyInPlaylist = database.playlistDao().isTrackInPlaylist(playlistId, trackId)
                    if (alreadyInPlaylist && !allowDuplicate) {
                        return@immediateTransaction AddToPlaylistResult.AlreadyExists(
                            trackId = trackId,
                            playlistId = playlistId,
                            playlistName = playlistName
                        )
                    }

                    val nextPosition = database.playlistDao().getNextPlaylistPosition(playlistId)
                    val now = System.currentTimeMillis()
                    val itemId = "playlist-item:${UUID.randomUUID()}"
                    val outboxOp = syncRepository.createOutboxEntity(
                        entityType = "playlist_item",
                        entityId = itemId,
                        operationType = "create",
                        payload = mapOf(
                            "playlist_id" to playlistId,
                            "track_id" to trackId,
                            "position" to nextPosition,
                            "added_at" to LocalLibraryRepository.formatMillisToIsoDate(now),
                            "added_from_context_type" to contextType,
                            "added_from_context_id" to playlistId
                        )
                    )
                    ensureTrackExists?.invoke(trackId, title, artistName, albumTitle, durationMs, coverUri, contextType)
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
                    database.syncOutboxDao().insert(outboxOp)
                    AddToPlaylistResult.Success(playlistName)
                }
            }
            if (result !is AddToPlaylistResult.Success) return@withContext result
            syncRepository.triggerManualSync()

            // Trigger auto-download if the playlist has auto-download enabled
            val ctx = context
            val cloudProvider = cloudFileRepositoryProvider
            if (ctx != null && cloudProvider != null) {
                try {
                    val prefs = ctx.getSharedPreferences("aura_prefs", android.content.Context.MODE_PRIVATE)
                    if (prefs.getBoolean("auto_download_playlist_$playlistId", false)) {
                        cloudProvider.invoke().autoDownloadPlaylistTrack(playlistId, trackId)
                    }
                } catch (e: Exception) {
                    Log.w("PlaylistManager", "Failed to trigger auto-download for playlist $playlistId track $trackId", e)
                }
            }

            result
        } catch (e: Exception) {
            Log.e("PlaylistManager", "Failed to add track $trackId to playlist $playlistId", e)
            AddToPlaylistResult.Error(e.message ?: "Erreur lors de l'ajout à la playlist")
        }
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, playlistItemId: String) = withContext(Dispatchers.IO) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val item = database.playlistDao().getPlaylistItem(playlistItemId)
                if (item?.playlistId != playlistId) return@immediateTransaction
                val track = database.trackDao().getRawTrackById(item.trackId)
                val outboxOp = syncRepository.createOutboxEntity(
                    entityType = "playlist_item",
                    entityId = playlistItemId,
                    operationType = "delete",
                    payload = mapOf(
                        "playlist_id" to playlistId, "track_id" to item.trackId,
                        "removed_title" to track?.title, "removed_artist" to track?.displayArtistName,
                    ),
                )
                database.playlistDao().deletePlaylistItem(playlistItemId)
                normalizePlaylistPositions(playlistId)
                database.playlistDao().touchPlaylist(playlistId, System.currentTimeMillis())
                database.syncOutboxDao().insert(outboxOp)
            }
        }
        syncRepository.triggerManualSync()
    }

    suspend fun deduplicatePlaylist(playlistId: String): Int = withContext(Dispatchers.IO) {
        val tracks = database.playlistDao().getPlaylistTracks(playlistId)
        if (tracks.isEmpty()) return@withContext 0

        val seenTrackIds = mutableSetOf<String>()
        val itemsToDelete = mutableListOf<com.aura.music.data.local.PlaylistTrackRow>()
        val itemsToKeep = mutableListOf<com.aura.music.data.local.PlaylistTrackRow>()

        for (item in tracks) {
            if (seenTrackIds.add(item.trackId)) {
                itemsToKeep.add(item)
            } else {
                itemsToDelete.add(item)
            }
        }

        if (itemsToDelete.isEmpty()) return@withContext 0

        val now = System.currentTimeMillis()

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                for (item in itemsToDelete) {
                    database.playlistDao().deletePlaylistItem(item.playlistItemId)
                    val outboxOp = syncRepository.createOutboxEntity(
                        entityType = "playlist_item",
                        entityId = item.playlistItemId,
                        operationType = "delete",
                        payload = mapOf(
                            "playlist_id" to playlistId
                        )
                    )
                    database.syncOutboxDao().insert(outboxOp)
                }

                // Réordonnancement compact des positions
                itemsToKeep.forEachIndexed { newIndex, item ->
                    if (item.position != newIndex) {
                        database.playlistDao().updatePlaylistItemPosition(item.playlistItemId, newIndex)
                    }
                }

                database.playlistDao().touchPlaylist(playlistId, now)
            }
        }

        syncRepository.triggerManualSync()
        itemsToDelete.size
    }

    suspend fun movePlaylistItem(
        playlistId: String,
        playlistItemId: String,
        moveBy: Int,
    ) = withContext(Dispatchers.IO) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val items = database.playlistDao().getPlaylistTracks(playlistId).toMutableList()
                val currentIndex = items.indexOfFirst { it.playlistItemId == playlistItemId }
                if (currentIndex == -1) return@immediateTransaction
                val targetIndex = (currentIndex + moveBy).coerceIn(0, items.lastIndex)
                if (currentIndex == targetIndex) return@immediateTransaction

                val anchor = items[targetIndex]
                val item = items.removeAt(currentIndex)
                items.add(targetIndex, item)

                val affected = minOf(currentIndex, targetIndex)..maxOf(currentIndex, targetIndex)
                for (index in affected) {
                    database.playlistDao().updatePlaylistItemPosition(items[index].playlistItemId, -index - 1)
                }
                val sortedPositions = items.map { it.position }.sorted()
                for (index in affected) {
                    database.playlistDao().updatePlaylistItemPosition(items[index].playlistItemId, sortedPositions[index])
                }
                database.playlistDao().touchPlaylist(playlistId, System.currentTimeMillis())

                val outboxOp = syncRepository.createOutboxEntity(
                    entityType = "playlist_reorder",
                    entityId = playlistId,
                    operationType = "update",
                    payload = mapOf(
                        "playlist_sync_version" to 2,
                        "item_id" to item.playlistItemId,
                        "anchor_id" to anchor.playlistItemId,
                        "side" to if (targetIndex < currentIndex) "before" else "after",
                    )
                )
                database.syncOutboxDao().insert(outboxOp)
            }
        }

        syncRepository.triggerManualSync()
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

    private suspend fun normalizePlaylistPositions(playlistId: String) {
        database.playlistDao().getPlaylistTracks(playlistId).forEachIndexed { index, row ->
            database.playlistDao().updatePlaylistItemPosition(row.playlistItemId, index)
        }
    }

    private fun normalize(value: String): String {
        val slug = value
            .trim()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
        if (slug.isNotBlank()) return slug
        val bytes = value.trim().lowercase().toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.take(16).ifBlank { "unknown" }
    }
}
