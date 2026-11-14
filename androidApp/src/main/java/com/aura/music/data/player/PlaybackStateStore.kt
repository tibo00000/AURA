package com.aura.music.data.player

import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.PlaybackSnapshotDao
import com.aura.music.data.local.PlaybackSnapshotEntity
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.NetworkPolicyChecker
import com.aura.music.data.network.PlaybackSnapshotResponse
import com.aura.music.core.AuthSessionManager
import com.aura.music.domain.player.RepeatMode
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

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
    val playlistOccurrenceId: String? = null,
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
    private val auth: AuthSessionManager = AuthSessionManager.getInstance(context),
) {
    companion object {
        private const val ACTIVE_ID = "active"
        private const val DEBOUNCE_DELAY_MS = 1_500L
    }

    private data class PendingSnapshot(val owner: AuthSessionManager.Scope, val payload: PlaybackSnapshotResponse)

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSyncJob: Job? = null
    private val pendingSnapshot = AtomicReference<PendingSnapshot?>(null)
    internal val sessionScope get() = auth.sessionScope.value

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
        playlistOccurrenceId: String? = null,
    ) {
        val owner = auth.sessionScope.value
        withContext(Dispatchers.IO) {
            if (contextType == "playlist" && contextId != null && !canAccessPlaylist(contextId, owner)) return@withContext
            if (auth.sessionScope.value != owner) return@withContext
            val now = System.currentTimeMillis()

            // 1. Save locally first (synchronous 0 ms)
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
                    playlistOccurrenceId = playlistOccurrenceId,
                    snapshotUserId = owner?.userId,
                ),
            )

            // 2. Debounced remote sync (1500 ms)
            if (owner != null && shouldSyncDirectly() && auth.isCurrent(owner)) {
                val isoDate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    java.time.Instant.ofEpochMilli(now).toString()
                } else {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    sdf.format(java.util.Date(now))
                }
                val payload = PlaybackSnapshotResponse(
                    currentTrackId = currentTrackId,
                    playbackContextType = contextType,
                    playbackContextId = contextId,
                    playbackContextIndex = contextIndex,
                    positionMs = positionMs,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode.name.lowercase(),
                    playlistOccurrenceId = playlistOccurrenceId,
                    updatedAt = isoDate
                )
                val pending = PendingSnapshot(owner, payload)
                pendingSnapshot.set(pending)

                pendingSyncJob?.cancel()
                pendingSyncJob = storeScope.launch {
                    delay(DEBOUNCE_DELAY_MS)
                    performPushSnapshot(pending)
                }
            }
        }
    }

    private suspend fun performPushSnapshot(pending: PendingSnapshot) {
        if (pendingSnapshot.get() !== pending) return
        if (!auth.isCurrent(pending.owner)) {
            pendingSnapshot.compareAndSet(pending, null)
            return
        }
        try {
            val lease = auth.validSession(scope = pending.owner)
            if (!auth.isCurrent(lease) || pendingSnapshot.get() !== pending) return
            val res = apiService.updatePlaybackSnapshot(lease.bearer, pending.payload)
            if (res.error != null) {
                Log.w("PlaybackStateStore", "Debounced save snapshot REST error: ${res.error?.message}")
            } else {
                Log.d("PlaybackStateStore", "Debounced save snapshot REST succeeded")
                pendingSnapshot.compareAndSet(pending, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PlaybackStateStore", "Debounced save snapshot REST failed: ${e.message}", e)
        }
    }

    /**
     * Exécute immédiatement tout envoi réseau en attente lors des transitions de cycle de vie critiques (onDestroy, onTaskRemoved).
     * Protégé par NonCancellable pour garantir que la requête réseau va jusqu'au bout.
     */
    suspend fun flushPendingPlaybackSnapshot() = withContext(NonCancellable + Dispatchers.IO) {
        val pending = pendingSnapshot.get() ?: return@withContext
        pendingSyncJob?.cancel()
        pendingSyncJob = null
        performPushSnapshot(pending)
    }

    suspend fun restore(): PersistedPlaybackSnapshot? = withContext(Dispatchers.IO) {
        val owner = auth.sessionScope.value
        val local = localSnapshot(owner)
        // A previously known playlist is restorable from Room even with a
        // stalled network. Cloud availability is refreshed outside the queue.
        if (auth.sessionScope.value != owner) return@withContext null
        if (local?.playbackContextType == "playlist") return@withContext local.toPersistedSnapshot()
        if (owner != null && shouldSyncDirectly()) {
            try {
                val lease = auth.validSession(scope = owner)
                if (!auth.isCurrent(lease)) return@withContext null
                val response = apiService.getPlaybackSnapshot(lease.bearer)
                if (!auth.isCurrent(lease)) return@withContext null
                val remote = response.data
                if (response.error == null && remote != null) {
                    val entity = PlaybackSnapshotEntity(
                        id = ACTIVE_ID,
                        currentTrackId = remote.currentTrackId,
                        playbackContextType = remote.playbackContextType,
                        playbackContextId = remote.playbackContextId,
                        playbackContextIndex = remote.playbackContextIndex,
                        playlistOccurrenceId = remote.playlistOccurrenceId,
                        snapshotUserId = owner.userId,
                        positionMs = remote.positionMs,
                        shuffleEnabled = remote.shuffleEnabled,
                        repeatMode = remote.repeatMode,
                        updatedAt = System.currentTimeMillis()
                    )
                    snapshotDao.upsert(entity)

                    return@withContext entity.toPersistedSnapshot().takeIf { auth.isCurrent(lease) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PlaybackStateStore", "Direct restore snapshot REST failed: ${e.message}", e)
            }
        }

        local?.toPersistedSnapshot()?.takeIf { auth.sessionScope.value == owner }
    }

    suspend fun restoreLocalPlaylist(): PersistedPlaybackSnapshot? = withContext(Dispatchers.IO) {
        val owner = auth.sessionScope.value
        localSnapshot(owner)?.takeIf { it.playbackContextType == "playlist" && auth.sessionScope.value == owner }
            ?.toPersistedSnapshot()
    }

    private suspend fun localSnapshot(owner: AuthSessionManager.Scope?): PlaybackSnapshotEntity? {
        val snapshot = snapshotDao.getActiveSnapshot() ?: return null
        if (snapshot.snapshotUserId != null && snapshot.snapshotUserId != owner?.userId) return null
        if (snapshot.playbackContextType == "playlist") {
            val playlistId = snapshot.playbackContextId ?: return null
            if (!canAccessPlaylist(playlistId, owner)) return null
        }
        return snapshot
    }

    private suspend fun canAccessPlaylist(playlistId: String, owner: AuthSessionManager.Scope?): Boolean {
        val syncOwner = database.playlistDao().getPlaylistSyncState(playlistId)?.userId
            ?: database.playlistDao().getPlaylistSyncState(playlistId.removePrefix("playlist:"))?.userId
        return syncOwner == null || syncOwner == owner?.userId
    }

    private fun PlaybackSnapshotEntity.toPersistedSnapshot() =
        PersistedPlaybackSnapshot(
            currentTrackId = currentTrackId,
            contextType = playbackContextType,
            contextId = playbackContextId,
            contextIndex = playbackContextIndex,
            playlistOccurrenceId = playlistOccurrenceId,
            positionMs = positionMs,
            shuffleEnabled = shuffleEnabled,
            repeatMode = when (repeatMode) {
                "one" -> RepeatMode.One
                "all" -> RepeatMode.All
                else -> RepeatMode.Off
            },
        )
}
