package com.aura.music.data.repository

import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.PlaylistDetailRow
import com.aura.music.data.local.PlaylistEntity
import com.aura.music.data.local.PlaylistItemEntity
import com.aura.music.data.local.PlaylistItemWithTrack
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import androidx.room3.useWriterConnection
import androidx.room3.immediateTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Gestionnaire dédié des playlists et items de playlist.
 * Porte l'intégralité de la logique de modification et garantit l'atomicité
 * Room + Outbox (P1-3 / P2-1) au sein d'une unique transaction immédiate.
 */
class PlaylistManager(
    private val database: AuraDatabase,
    private val syncRepositoryProvider: () -> SyncRepository,
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
    ) = withContext(Dispatchers.IO) {
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
                database.syncOutboxDao().insert(outboxOp)
            }
        }
        syncRepository.triggerManualSync()
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, playlistItemId: String) = withContext(Dispatchers.IO) {
        val outboxOp = syncRepository.createOutboxEntity(
            entityType = "playlist_item",
            entityId = playlistItemId,
            operationType = "delete",
            payload = mapOf(
                "playlist_id" to playlistId
            )
        )
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().deletePlaylistItem(playlistItemId)
                normalizePlaylistPositions(playlistId)
                database.playlistDao().touchPlaylist(playlistId, System.currentTimeMillis())
                database.syncOutboxDao().insert(outboxOp)
            }
        }
        syncRepository.triggerManualSync()
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
                    val updatedStr = LocalLibraryRepository.formatMillisToIsoDate(pl.updatedAt)
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

                val outboxOp = syncRepository.createOutboxEntity(
                    entityType = "playlist_reorder",
                    entityId = playlistId,
                    operationType = "update",
                    payload = mapOf(
                        "base_order_token" to baseOrderToken,
                        "items" to itemsToReorder
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
