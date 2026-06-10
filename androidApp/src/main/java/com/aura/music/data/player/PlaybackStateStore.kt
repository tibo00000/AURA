package com.aura.music.data.player

import com.aura.music.data.local.PlaybackSnapshotDao
import com.aura.music.data.local.PlaybackSnapshotEntity
import com.aura.music.domain.player.RepeatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Snapshot de reprise persiste.
 */
data class PersistedPlaybackSnapshot(
    val currentTrackId: String?,
    val contextType: String?,
    val contextId: String?,
    val contextIndex: Int?,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
)

/**
 * Persiste le minimum necessaire pour reprendre une session de lecture.
 *
 * Gouverne par :
 * - docs/android/player/architecture.md
 * - docs/android/local-persistence.md
 *
 * Donnees persistees :
 * - identifiant piste courante
 * - position courante
 * - contexte de lecture (type + id + index)
 * - modes shuffle et repeat
 */
class PlaybackStateStore(
    private val snapshotDao: PlaybackSnapshotDao,
) {
    companion object {
        private const val ACTIVE_ID = "active"
    }

    suspend fun save(
        currentTrackId: String?,
        contextType: String?,
        contextId: String?,
        contextIndex: Int?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: RepeatMode,
    ) = withContext(Dispatchers.IO) {
        snapshotDao.upsert(
            PlaybackSnapshotEntity(
                id = ACTIVE_ID,
                currentTrackId = currentTrackId,
                playbackContextType = contextType,
                playbackContextId = contextId,
                playbackContextIndex = contextIndex,
                positionMs = positionMs,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode.name.lowercase(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun restore(): PersistedPlaybackSnapshot? = withContext(Dispatchers.IO) {
        val entity = snapshotDao.getActiveSnapshot() ?: return@withContext null
        PersistedPlaybackSnapshot(
            currentTrackId = entity.currentTrackId,
            contextType = entity.playbackContextType,
            contextId = entity.playbackContextId,
            contextIndex = entity.playbackContextIndex,
            positionMs = entity.positionMs,
            shuffleEnabled = entity.shuffleEnabled,
            repeatMode = when (entity.repeatMode) {
                "one" -> RepeatMode.One
                "all" -> RepeatMode.All
                else -> RepeatMode.Off
            },
        )
    }
}
