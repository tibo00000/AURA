package com.aura.music.desktop.domain

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.PlaylistEntity
import com.aura.music.data.local.PlaylistItemEntity
import com.aura.music.data.local.SyncOutboxEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Gestionnaire des playlists pour le client Desktop.
 * Garantit la Règle Normative #3 (Zéro Dual-Write) : toute modification locale
 * est exécutée dans une transaction SQLite immédiate unique avec son insertion dans sync_outbox.
 */
class DesktopPlaylistManager(
    private val database: AuraDatabase,
    var cloudSyncManager: DesktopCloudSyncManager? = null
) {
    suspend fun createPlaylist(name: String): String = withContext(Dispatchers.IO) {
        val plId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val opId = "outbox_pl_${UUID.randomUUID().toString().take(12)}"
        val cleanName = name.trim().ifBlank { "Nouvelle Playlist" }

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().insertPlaylist(
                    PlaylistEntity(
                        id = plId,
                        name = cleanName,
                        coverUri = null,
                        isPinned = false,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                database.syncOutboxDao().insert(
                    SyncOutboxEntity(
                        id = opId,
                        entityType = "playlist",
                        entityId = plId,
                        operationType = "create",
                        payloadJson = cleanName,
                        status = "pending",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        cloudSyncManager?.triggerFlush()
        plId
    }

    suspend fun renamePlaylist(id: String, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim().ifBlank { "Playlist" }
        val now = System.currentTimeMillis()
        val opId = "outbox_pl_ren_${UUID.randomUUID().toString().take(12)}"

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().renamePlaylist(id, trimmed, now)
                database.syncOutboxDao().insert(
                    SyncOutboxEntity(
                        id = opId,
                        entityType = "playlist",
                        entityId = id,
                        operationType = "update",
                        payloadJson = trimmed,
                        status = "pending",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        cloudSyncManager?.triggerFlush()
    }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val opId = "outbox_pl_del_${UUID.randomUUID().toString().take(12)}"

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().deletePlaylist(id)
                database.syncOutboxDao().insert(
                    SyncOutboxEntity(
                        id = opId,
                        entityType = "playlist",
                        entityId = id,
                        operationType = "delete",
                        payloadJson = "",
                        status = "pending",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        cloudSyncManager?.triggerFlush()
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) = withContext(Dispatchers.IO) {
        val nextPos = database.playlistDao().getNextPlaylistPosition(playlistId)
        val now = System.currentTimeMillis()
        val opId = "outbox_pli_${UUID.randomUUID().toString().take(12)}"

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().insertPlaylistItem(
                    PlaylistItemEntity(
                        id = UUID.randomUUID().toString(),
                        playlistId = playlistId,
                        trackId = trackId,
                        position = nextPos,
                        addedAt = now
                    )
                )
                database.playlistDao().touchPlaylist(playlistId, now)
                database.syncOutboxDao().insert(
                    SyncOutboxEntity(
                        id = opId,
                        entityType = "playlist_item",
                        entityId = playlistId,
                        operationType = "create",
                        payloadJson = trackId,
                        status = "pending",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        cloudSyncManager?.triggerFlush()
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val opId = "outbox_pli_del_${UUID.randomUUID().toString().take(12)}"

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                database.playlistDao().deletePlaylistItemByTrack(playlistId, trackId)
                database.playlistDao().touchPlaylist(playlistId, now)
                database.syncOutboxDao().insert(
                    SyncOutboxEntity(
                        id = opId,
                        entityType = "playlist_item",
                        entityId = playlistId,
                        operationType = "delete",
                        payloadJson = trackId,
                        status = "pending",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        cloudSyncManager?.triggerFlush()
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
                    val opId = "outbox_pli_del_${UUID.randomUUID().toString().take(12)}"
                    database.syncOutboxDao().insert(
                        SyncOutboxEntity(
                            id = opId,
                            entityType = "playlist_item",
                            entityId = playlistId,
                            operationType = "delete",
                            payloadJson = item.trackId,
                            status = "pending",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
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

        cloudSyncManager?.triggerFlush()
        itemsToDelete.size
    }
}
