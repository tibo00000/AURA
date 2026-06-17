package com.aura.music.data.player

import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.PlaybackSnapshotDao
import com.aura.music.data.local.PlaybackSnapshotEntity
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.NetworkPolicyChecker
import com.aura.music.data.repository.SyncRepository
import com.aura.music.domain.player.RepeatMode
import android.util.Log
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
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val context: android.content.Context,
) {
    companion object {
        private const val ACTIVE_ID = "active"
    }

    private suspend fun shouldSyncDirectly(): Boolean {
        val settings = database.userSettingsDao().getSettings()
        return settings != null && settings.syncEnabled && NetworkPolicyChecker.isConnected(context)
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
        val now = System.currentTimeMillis()
        
        // Save locally first
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
                updatedAt = now,
            ),
        )

        // Sync directly to backend if online and sync enabled
        if (shouldSyncDirectly()) {
            try {
                val isoDate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    java.time.Instant.ofEpochMilli(now).toString()
                } else {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    sdf.format(java.util.Date(now))
                }
                val snapshotResponse = com.aura.music.data.network.PlaybackSnapshotResponse(
                    currentTrackId = currentTrackId,
                    playbackContextType = contextType,
                    playbackContextId = contextId,
                    playbackContextIndex = contextIndex,
                    positionMs = positionMs,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode.name.lowercase(),
                    updatedAt = isoDate
                )
                val res = apiService.updatePlaybackSnapshot(SyncRepository.AUTH_TOKEN, snapshotResponse)
                if (res.error != null) {
                    Log.w("PlaybackStateStore", "Direct save snapshot REST returned API error: ${res.error?.message} (code: ${res.error?.code})")
                } else {
                    Log.i("PlaybackStateStore", "Direct save snapshot REST succeeded")
                }
            } catch (e: Exception) {
                Log.w("PlaybackStateStore", "Direct save snapshot REST failed: ${e.message}", e)
            }
        }
    }

    suspend fun restore(): PersistedPlaybackSnapshot? = withContext(Dispatchers.IO) {
        if (shouldSyncDirectly()) {
            try {
                val response = apiService.getPlaybackSnapshot(SyncRepository.AUTH_TOKEN)
                val remote = response.data
                if (response.error == null && remote != null) {
                    val entity = PlaybackSnapshotEntity(
                        id = ACTIVE_ID,
                        currentTrackId = remote.currentTrackId,
                        playbackContextType = remote.playbackContextType,
                        playbackContextId = remote.playbackContextId,
                        playbackContextIndex = remote.playbackContextIndex,
                        positionMs = remote.positionMs,
                        shuffleEnabled = remote.shuffleEnabled,
                        repeatMode = remote.repeatMode,
                        updatedAt = System.currentTimeMillis()
                    )
                    snapshotDao.upsert(entity)

                    return@withContext PersistedPlaybackSnapshot(
                        currentTrackId = remote.currentTrackId,
                        contextType = remote.playbackContextType,
                        contextId = remote.playbackContextId,
                        contextIndex = remote.playbackContextIndex,
                        positionMs = remote.positionMs,
                        shuffleEnabled = remote.shuffleEnabled,
                        repeatMode = when (remote.repeatMode) {
                            "one" -> RepeatMode.One
                            "all" -> RepeatMode.All
                            else -> RepeatMode.Off
                        }
                    )
                }
            } catch (e: Exception) {
                Log.w("PlaybackStateStore", "Direct restore snapshot REST failed: ${e.message}", e)
            }
        }

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
