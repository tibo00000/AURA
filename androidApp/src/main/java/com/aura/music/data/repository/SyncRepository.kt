package com.aura.music.data.repository

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.SyncOutboxEntity
import com.aura.music.data.local.TrackLikeEntity
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.BootstrapRequestDto
import com.aura.music.data.network.PullBatchRequestDto
import com.aura.music.data.network.PushBatchRequestDto
import com.aura.music.data.network.SyncOperationDto
import com.aura.music.data.network.ServerChangeDto
import com.aura.music.data.sync.SyncWorker
import com.aura.music.data.playlist.PlaylistSnapshotReconciler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

class SyncRepository(
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val context: Context,
    private val authSessionManager: com.aura.music.core.AuthSessionManager? = null
) {
    companion object {
        private const val TAG = "SyncRepository"
        private const val WORK_NAME_PERIODIC = "aura_periodic_sync"
        private const val WORK_NAME_ONETIME = "aura_onetime_sync"
    }

    private val syncMutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observePlaylistSyncConflict(): Flow<Boolean> = authSessionManager?.sessionScope
        ?.map { it?.userId }
        ?.distinctUntilChanged()
        ?.flatMapLatest { userId ->
            if (userId == null) flowOf(false) else database.syncOutboxDao().observePlaylistConflict(userId)
        } ?: flowOf(false)

    private fun Map<String, Any?>.toJsonObject(): JsonObject {
        val map = mapValues { (_, value) ->
            when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                is JsonElement -> value
                else -> JsonPrimitive(value.toString())
            }
        }
        return JsonObject(map)
    }

    private fun JsonObject.boolean(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull
    private fun JsonObject.double(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull

    /**
     * Crée une entité SyncOutbox prête à être insérée dans une transaction SQLite Room atomique.
     */
    fun createOutboxEntity(
        entityType: String,
        entityId: String,
        operationType: String,
        payload: Map<String, Any?>
    ): SyncOutboxEntity {
        val payloadJson = json.encodeToString(payload.toJsonObject())
        val now = System.currentTimeMillis()
        return SyncOutboxEntity(
            id = "op_${UUID.randomUUID()}",
            entityType = entityType,
            entityId = entityId,
            operationType = operationType,
            payloadJson = payloadJson,
            status = "pending",
            attemptCount = 0,
            createdAt = now,
            updatedAt = now,
            userId = authSessionManager?.getUserId()?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Inscrire une mutation locale dans la table sync_outbox.
     * Appelé depuis LocalLibraryRepository.
     */
    suspend fun recordLocalOperation(
        entityType: String,
        entityId: String,
        operationType: String,
        payload: Map<String, Any?>
    ) = withContext(Dispatchers.IO) {
        val settings = database.userSettingsDao().getSettings()
        if (settings == null || !settings.syncEnabled) {
            Log.d(TAG, "Sync is disabled. Do not record operation.")
            return@withContext
        }

        try {
            val operation = createOutboxEntity(entityType, entityId, operationType, payload)
            database.syncOutboxDao().insert(operation)
            Log.i(TAG, "Successfully recorded local operation: ${operation.id} ($entityType / $operationType)")
            
            // Proactive one-time sync trigger if network is available
            triggerManualSync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record local operation: ${e.message}", e)
        }
    }

    /**
     * Planifier la synchronisation périodique via WorkManager.
     */
    fun schedulePeriodicSync() {
        val workManager = WorkManager.getInstance(context)
        
        // Load settings to set constraints
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
        Log.i(TAG, "Periodic sync worker scheduled (1h interval)")
    }

    /**
     * Annuler le Worker périodique.
     */
    fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        Log.i(TAG, "Periodic sync worker cancelled")
    }

    /**
     * Déclencher immédiatement une synchronisation ponctuelle.
     */
    fun triggerManualSync() {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME_ONETIME,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
        Log.i(TAG, "One-time sync worker triggered")
    }

    /**
     * Exécuter la routine complète de synchronisation.
     */
    suspend fun performSync(deviceId: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock { syncOnce(deviceId, force) }
    }

    private suspend fun syncOnce(deviceId: String, force: Boolean): Boolean {
        Log.i(TAG, "Starting sync routine for device $deviceId (force=$force)")
        
        val settings = database.userSettingsDao().getSettings()
        if (settings == null) {
            Log.w(TAG, "User settings are not initialized yet. Skipping sync.")
            return false
        }

        if (!settings.syncEnabled) {
            Log.d(TAG, "Sync is disabled in settings. Skipping.")
            return false
        }

        if (!force && !isNetworkAllowed(settings.statsSyncNetworkPolicy)) {
            Log.d(TAG, "Sync skipped due to network policy constraints (Wifi only required).")
            return false
        }

        val auth = authSessionManager ?: return false
        if (!auth.isLoggedIn.value) return false
        return try {
            val scope = auth.captureScope()
            val token = auth.validSession(scope = scope).bearer
            val isCurrent = { auth.isCurrent(scope) }
            val acknowledged = pushOutbox(deviceId, token, scope.userId, isCurrent) ?: return false
            val response = apiService.getPlaylistSnapshot(token)
            val snapshot = response.data
            if (response.error != null || response.meta?.partialFailure == true || snapshot == null) return false
            PlaylistSnapshotReconciler(
                database,
                parseTimestamp = { java.time.Instant.parse(it).toEpochMilli() },
            )
                .apply(snapshot, scope.userId, isCurrent, acknowledged)
            val cursor = settings.syncToken.takeIf { settings.syncTokenUserId == scope.userId }
            if (cursor.isNullOrEmpty()) performBootstrap(deviceId, token, scope.userId, isCurrent)
            else pullChanges(deviceId, token, scope.userId, cursor, isCurrent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync routine failed: ${e.message}", e)
            false
        }
    }

    /**
     * BOOTSTRAP SYNC : Première synchronisation d'hydratation.
     */
    private suspend fun performBootstrap(
        deviceId: String, token: String, userId: String, isCurrent: () -> Boolean,
    ): Boolean {
        val request = BootstrapRequestDto(
            deviceId = deviceId,
            capabilities = mapOf("supports_batch_push" to "true", "playlist_snapshot" to "true")
        )
        
        val response = apiService.bootstrap(token, request)
        val error = response.error
        val data = response.data
        if (error != null || response.meta?.partialFailure == true) {
            Log.e(TAG, "Bootstrap endpoint failed: ${error?.message}")
            return false
        }
        if (data == null) {
            Log.e(TAG, "Bootstrap endpoint failed: response data is null")
            return false
        }

        val snapshot = data.snapshot
        val serverToken = data.syncToken.value

        if (!isCurrent()) return false
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (!isCurrent()) throw CancellationException("Sync session changed")
                // 1. Process server snapshot settings
                val userSettingsMap = snapshot["user_settings"] as? JsonObject
                if (userSettingsMap != null) {
                    val localSettings = database.userSettingsDao().getSettings()
                    if (localSettings != null) {
                        val updatedSettings = localSettings.copy(
                            onlineSearchEnabled = userSettingsMap.boolean("online_search_enabled") ?: localSettings.onlineSearchEnabled,
                            onlineSearchNetworkPolicy = userSettingsMap.string("online_search_network_policy") ?: localSettings.onlineSearchNetworkPolicy,
                            statsSyncNetworkPolicy = userSettingsMap.string("stats_sync_network_policy") ?: localSettings.statsSyncNetworkPolicy
                        )
                        database.userSettingsDao().insertOrReplace(updatedSettings)
                    }
                }

                // 2. Process server snapshot likes (favorites)
                val trackLikesList = snapshot["track_likes"] as? JsonArray
                if (trackLikesList != null) {
                    for (likeElement in trackLikesList) {
                        val likeMap = likeElement as? JsonObject ?: continue
                        val trackId = likeMap.string("track_id") ?: continue
                        val likedAtStr = likeMap.string("liked_at")
                        val likedAt = parseIsoDateToMillis(likedAtStr)

                        ensureTrackStub(trackId)

                        database.trackLikeDao().insertLike(
                            TrackLikeEntity(
                                trackId = trackId,
                                likedAt = likedAt,
                                sourceContextType = likeMap.string("source_context_type"),
                                sourceContextId = likeMap.string("source_context_id")
                            )
                        )
                        database.trackLikeDao().setTrackIsLiked(trackId, true, likedAt)
                    }
                }

                if (!isCurrent()) throw CancellationException("Sync session changed")
                database.userSettingsDao().updateSyncToken(serverToken, userId, System.currentTimeMillis())
            }
        }
        Log.i(TAG, "Bootstrap Sync successfully applied")
        return true
    }

    /**
     * INCREMENTAL SYNC : Push local outbox mutations, then pull server deltas.
     */
    private suspend fun pushOutbox(
        deviceId: String,
        token: String,
        userId: String,
        isCurrent: () -> Boolean,
    ): Map<String, Long>? {
        // --- 1. PUSH PHASE ---
        val pendingOps = database.syncOutboxDao().getPendingOperations().filter { op ->
            if (op.userId != null) return@filter op.userId == userId
            if (op.entityType !in setOf("playlist", "playlist_item", "playlist_reorder")) return@filter true
            val playlistId = if (op.entityType == "playlist_item") {
                json.decodeFromString<JsonObject>(op.payloadJson).string("playlist_id")
            } else op.entityId
            playlistId != null && database.playlistDao().getPlaylistSyncState(playlistId)?.userId == userId
        }
        val acknowledgedPlaylists = mutableMapOf<String, Long>()

        val activeSnapshot = database.playbackSnapshotDao().getActiveSnapshot()

        if (pendingOps.isNotEmpty() || activeSnapshot != null) {
            val chunks = if (pendingOps.isEmpty()) listOf(emptyList()) else pendingOps.chunked(30)
            Log.i(TAG, "Pushing ${pendingOps.size} local mutations (+ active snapshot) in ${chunks.size} chunks to backend...")

            for ((chunkIndex, chunk) in chunks.withIndex()) {
                val isLastChunk = chunkIndex == chunks.size - 1
                val syncOps = chunk.map { op ->
                    val payload = json.decodeFromString<JsonObject>(op.payloadJson)
                    SyncOperationDto(
                        operationId = op.id,
                        entityType = op.entityType,
                        entityId = op.entityId,
                        operationType = op.operationType,
                        deviceId = deviceId,
                        occurredAt = formatMillisToIsoDate(op.createdAt),
                        payload = if (
                            op.entityType in setOf("playlist", "playlist_item", "playlist_reorder")
                        ) JsonObject(payload + ("playlist_sync_version" to JsonPrimitive(2))) else payload,
                    )
                }.toMutableList()

                // Dynamically append the latest active playback snapshot only to the last chunk
                if (isLastChunk && activeSnapshot != null) {
                    syncOps.add(
                        SyncOperationDto(
                            operationId = "op_snap_${System.currentTimeMillis()}",
                            entityType = "playback_snapshot",
                            entityId = "default",
                            operationType = "update",
                            deviceId = deviceId,
                            occurredAt = formatMillisToIsoDate(activeSnapshot.updatedAt),
                            payload = mapOf(
                                "current_track_id" to activeSnapshot.currentTrackId,
                                "playback_context_type" to activeSnapshot.playbackContextType,
                                "playback_context_id" to activeSnapshot.playbackContextId,
                                "playback_context_index" to activeSnapshot.playbackContextIndex,
                                "position_ms" to activeSnapshot.positionMs,
                                "shuffle_enabled" to activeSnapshot.shuffleEnabled,
                                "repeat_mode" to activeSnapshot.repeatMode
                            ).toJsonObject()
                        )
                    )
                }

                if (syncOps.isEmpty()) continue

                val request = PushBatchRequestDto(
                    deviceId = deviceId,
                    batchId = "batch_${UUID.randomUUID()}",
                    sentAt = formatMillisToIsoDate(System.currentTimeMillis()),
                    operations = syncOps
                )

                if (!isCurrent()) return null
                val response = apiService.pushBatch(token, request)
                if (!isCurrent()) return null
                val data = response.data
                if (response.error != null || data == null || data.batchId != request.batchId) return null
                val sentById = chunk.associateBy { it.id }
                val returnedIds = data.results.mapTo(mutableSetOf()) { it.operationId }
                if (returnedIds.size != data.results.size || returnedIds != syncOps.map { it.operationId }.toSet()) return null
                for (result in data.results) {
                    val operation = sentById[result.operationId] ?: continue
                    if (result.entityId != operation.entityId || result.entityType != operation.entityType) return null
                    if (result.status in setOf("applied", "merged", "ignored_duplicate")) {
                        if (operation.entityType in setOf("playlist", "playlist_item", "playlist_reorder")) {
                            val revision = result.playlistRevision ?: return null
                            acknowledgedPlaylists[operation.id] = revision
                        } else {
                            database.syncOutboxDao().deleteOperation(operation.id)
                        }
                    } else if (result.status == "conflict" && operation.entityType in
                        setOf("playlist", "playlist_item", "playlist_reorder")
                    ) {
                        if (result.conflict?.boolean("retryable") != false) return null
                        database.syncOutboxDao().markPlaylistConflict(operation.id, userId, System.currentTimeMillis())
                    }
                }
            }
        }
        return acknowledgedPlaylists
    }

    private suspend fun pullChanges(
        deviceId: String,
        token: String,
        userId: String,
        currentToken: String,
        isCurrent: () -> Boolean,
    ): Boolean {
        var cursor = currentToken
        val seenCursors = mutableSetOf(cursor)
        do {
            if (!isCurrent()) return false
            val response = apiService.pullBatch(
                token,
                PullBatchRequestDto(
                    deviceId = deviceId,
                    sinceToken = cursor,
                    entityTypes = listOf("user_settings", "track_like", "playback_snapshot"),
                ),
            )
            val data = response.data
            if (response.error != null || response.meta?.partialFailure == true || data == null) return false
            val next = data.nextPullToken.value
            if (next.isBlank() || (data.hasMore && !seenCursors.add(next))) return false
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    if (!isCurrent()) throw CancellationException("Sync session changed")
                    for (change in data.changes) applyServerChange(change)
                    if (!isCurrent()) throw CancellationException("Sync session changed")
                    database.userSettingsDao().updateSyncToken(next, userId, System.currentTimeMillis())
                }
            }
            cursor = next
        } while (data.hasMore)
        return true
    }

    /**
     * Appliquer une modification distante reçue du serveur.
     */
    private suspend fun applyServerChange(change: ServerChangeDto) {
        val payload = change.payload
        val entityId = change.entityId
        
        when (change.entityType) {
            "user_settings" -> {
                val localSettings = database.userSettingsDao().getSettings()
                if (localSettings != null) {
                    val updated = localSettings.copy(
                        onlineSearchEnabled = payload.boolean("online_search_enabled") ?: localSettings.onlineSearchEnabled,
                        onlineSearchNetworkPolicy = payload.string("online_search_network_policy") ?: localSettings.onlineSearchNetworkPolicy,
                        statsSyncNetworkPolicy = payload.string("stats_sync_network_policy") ?: localSettings.statsSyncNetworkPolicy
                    )
                    database.userSettingsDao().insertOrReplace(updated)
                }
            }
            "track_like" -> {
                val trackId = entityId
                if (change.changeType == "delete" || (payload.boolean("is_liked") == false)) {
                    database.trackLikeDao().deleteLike(trackId)
                    database.trackLikeDao().setTrackIsLiked(trackId, false, System.currentTimeMillis())
                    Log.d(TAG, "Pulled DELETE track_like $trackId")
                } else {
                    val likedAtStr = payload.string("liked_at")
                    val likedAt = parseIsoDateToMillis(likedAtStr)

                    ensureTrackStub(trackId)

                    val like = TrackLikeEntity(
                        trackId = trackId,
                        likedAt = likedAt,
                        sourceContextType = payload.string("source_context_type"),
                        sourceContextId = payload.string("source_context_id")
                    )
                    database.trackLikeDao().insertLike(like)
                    database.trackLikeDao().setTrackIsLiked(trackId, true, likedAt)
                    Log.d(TAG, "Pulled UPSERT track_like $trackId")
                }
            }
            "playback_snapshot" -> {
                // Update the active playback snapshot
                val currentTrackId = payload.string("current_track_id")
                val positionMs = payload.long("position_ms") ?: (payload.double("position_ms")?.toLong() ?: 0L)
                val shuffleEnabled = payload.boolean("shuffle_enabled") ?: false
                val repeatMode = payload.string("repeat_mode") ?: "none"

                val snapshot = com.aura.music.data.local.PlaybackSnapshotEntity(
                    id = "active",
                    currentTrackId = currentTrackId,
                    playbackContextType = payload.string("playback_context_type"),
                    playbackContextId = payload.string("playback_context_id"),
                    playbackContextIndex = payload.int("playback_context_index") ?: payload.double("playback_context_index")?.toInt(),
                    positionMs = positionMs,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    updatedAt = System.currentTimeMillis()
                )
                database.playbackSnapshotDao().upsert(snapshot)
                Log.d(TAG, "Pulled playback snapshot update")
            }
        }
    }

    /**
     * Helpers date/heure.
     */
    private fun parseIsoDateToMillis(isoStr: String?): Long {
        if (isoStr.isNullOrEmpty()) return System.currentTimeMillis()
        return try {
            val formatted = isoStr.replace("Z", "+00:00")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.time.Instant.parse(formatted).toEpochMilli()
            } else {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(formatted)?.time ?: System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun formatMillisToIsoDate(millis: Long): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            java.time.Instant.ofEpochMilli(millis).toString()
        } else {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.format(java.util.Date(millis))
        }
    }

    /**
     * Valide les contraintes réseau.
     */
    private fun isNetworkAllowed(policy: String): Boolean {
        if (policy == "any_network") return true
        
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        
        // Return true if network is WIFI or Ethernet
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Crée une entrée factuelle minimale dans la table tracks pour satisfaire les contraintes
     * d'intégrité de clé étrangère SQLite de Room, si le trackId n'existe pas localement.
     */
    private suspend fun ensureTrackStub(trackId: String) {
        val existing = database.trackDao().getRawTrackById(trackId)
        if (existing == null) {
            val stub = com.aura.music.data.local.TrackEntity(
                id = trackId,
                primaryArtistId = null,
                albumId = null,
                title = "Morceau synchronisé",
                normalizedTitle = "morceau synchronise",
                displayArtistName = "Artiste inconnu",
                displayAlbumTitle = "Album inconnu",
                durationMs = null,
                coverUri = null,
                canonicalAudioSourceType = "online",
                isLiked = false,
                isDownloadedByAura = false,
                isExplicit = false,
                popularity = 0,
                genresJson = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            database.trackDao().upsertTrack(stub)
            Log.d(TAG, "Created minimal Track stub for ID $trackId to satisfy foreign key constraint")
        }
    }
}
