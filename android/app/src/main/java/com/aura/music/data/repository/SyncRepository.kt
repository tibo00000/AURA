package com.aura.music.data.repository

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
import com.aura.music.data.local.PlaylistEntity
import com.aura.music.data.local.PlaylistItemEntity
import com.aura.music.data.local.SyncOutboxEntity
import com.aura.music.data.local.TrackLikeEntity
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.BootstrapRequestDto
import com.aura.music.data.network.PullBatchRequestDto
import com.aura.music.data.network.PushBatchRequestDto
import com.aura.music.data.network.SyncOperationDto
import com.aura.music.data.network.SyncTokenDto
import com.aura.music.data.network.ServerChangeDto
import com.aura.music.data.sync.SyncWorker
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class SyncRepository(
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val context: Context
) {
    companion object {
        private const val TAG = "SyncRepository"
        private const val AUTH_TOKEN = "Bearer 12345678-1234-1234-1234-1234567890ab"
        private const val WORK_NAME_PERIODIC = "aura_periodic_sync"
        private const val WORK_NAME_ONETIME = "aura_onetime_sync"

        // Global flag to prevent writing to sync_outbox during pulled changes integration
        @Volatile
        var isSyncingFromServer: Boolean = false
    }

    private val moshi = Moshi.Builder().build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

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
        if (isSyncingFromServer) {
            Log.d(TAG, "Ignore logging operation because sync is in progress from server: $entityType / $entityId")
            return@withContext
        }

        val settings = database.userSettingsDao().getSettings()
        if (settings == null || !settings.syncEnabled) {
            Log.d(TAG, "Sync is disabled. Do not record operation.")
            return@withContext
        }

        try {
            val payloadJson = mapAdapter.toJson(payload) ?: "{}"
            val operation = SyncOutboxEntity(
                id = "op_${UUID.randomUUID()}",
                entityType = entityType,
                entityId = entityId,
                operationType = operationType,
                payloadJson = payloadJson,
                status = "pending",
                attemptCount = 0,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
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
        Log.i(TAG, "Starting sync routine for device $deviceId (force=$force)")
        
        val settings = database.userSettingsDao().getSettings()
        if (settings == null) {
            Log.w(TAG, "User settings are not initialized yet. Skipping sync.")
            return@withContext false
        }

        if (!settings.syncEnabled) {
            Log.d(TAG, "Sync is disabled in settings. Skipping.")
            return@withContext false
        }

        if (!force && !isNetworkAllowed(settings.statsSyncNetworkPolicy)) {
            Log.d(TAG, "Sync skipped due to network policy constraints (Wifi only required).")
            return@withContext false
        }

        isSyncingFromServer = true
        var success = false

        try {
            val syncToken = settings.syncToken
            if (syncToken.isNullOrEmpty()) {
                Log.i(TAG, "No sync token found. Performing Bootstrap Sync...")
                success = performBootstrap(deviceId)
            } else {
                Log.i(TAG, "Sync token found: $syncToken. Performing Incremental Sync...")
                success = performIncrementalSync(deviceId, syncToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync routine failed: ${e.message}", e)
            success = false
        } finally {
            isSyncingFromServer = false
        }

        return@withContext success
    }

    /**
     * BOOTSTRAP SYNC : Première synchronisation d'hydratation.
     */
    private suspend fun performBootstrap(deviceId: String): Boolean {
        val request = BootstrapRequestDto(
            deviceId = deviceId,
            capabilities = mapOf("supports_batch_push" to "true")
        )
        
        val response = apiService.bootstrap(AUTH_TOKEN, request)
        if (response.error != null || response.data == null) {
            Log.e(TAG, "Bootstrap endpoint failed: ${response.error?.message}")
            return false
        }

        val data = response.data
        val snapshot = data.snapshot
        val serverToken = data.syncToken.value

        // Clear local sync outbox as we are starting fresh
        database.syncOutboxDao().clearAll()

        // 1. Process server snapshot settings
        val userSettingsMap = snapshot["user_settings"] as? Map<String, Any?>
        if (userSettingsMap != null) {
            val localSettings = database.userSettingsDao().getSettings()
            if (localSettings != null) {
                val updatedSettings = localSettings.copy(
                    onlineSearchEnabled = (userSettingsMap["online_search_enabled"] as? Boolean) ?: localSettings.onlineSearchEnabled,
                    onlineSearchNetworkPolicy = (userSettingsMap["online_search_network_policy"] as? String) ?: localSettings.onlineSearchNetworkPolicy,
                    statsSyncNetworkPolicy = (userSettingsMap["stats_sync_network_policy"] as? String) ?: localSettings.statsSyncNetworkPolicy
                )
                database.userSettingsDao().insertOrReplace(updatedSettings)
            }
        }

        // 2. Process server snapshot likes (favorites)
        val trackLikesList = snapshot["track_likes"] as? List<Map<String, Any?>>
        if (trackLikesList != null) {
            for (likeMap in trackLikesList) {
                val trackId = likeMap["track_id"] as? String ?: continue
                val likedAtStr = likeMap["liked_at"] as? String
                val likedAt = parseIsoDateToMillis(likedAtStr)
                
                ensureTrackStub(trackId)
                
                database.trackLikeDao().insertLike(
                    TrackLikeEntity(
                        trackId = trackId,
                        likedAt = likedAt,
                        sourceContextType = likeMap["source_context_type"] as? String,
                        sourceContextId = likeMap["source_context_id"] as? String
                    )
                )
                database.trackLikeDao().setTrackIsLiked(trackId, true, likedAt)
            }
        }

        // 3. Process server snapshot playlists & items
        val playlistsList = snapshot["playlists"] as? List<Map<String, Any?>>
        if (playlistsList != null) {
            for (plMap in playlistsList) {
                val plId = plMap["id"] as? String ?: continue
                val name = plMap["name"] as? String ?: "Unnamed Playlist"
                val coverUri = plMap["cover_uri"] as? String
                val isPinned = (plMap["is_pinned"] as? Boolean) ?: false
                val createdStr = plMap["created_at"] as? String
                val updatedStr = plMap["updated_at"] as? String
                
                val plEntity = PlaylistEntity(
                    id = plId,
                    name = name,
                    coverUri = coverUri,
                    isPinned = isPinned,
                    createdAt = parseIsoDateToMillis(createdStr),
                    updatedAt = parseIsoDateToMillis(updatedStr)
                )
                database.playlistDao().insertPlaylist(plEntity)

                // Process items
                val itemsList = plMap["items"] as? List<Map<String, Any?>>
                if (itemsList != null) {
                    for (itemMap in itemsList) {
                        val itemId = itemMap["id"] as? String ?: continue
                        val trackId = itemMap["track_id"] as? String ?: continue
                        val position = (itemMap["position"] as? Double)?.toInt() ?: 0
                        val addedStr = itemMap["added_at"] as? String
                        
                        ensureTrackStub(trackId)
                        
                        val itemEntity = PlaylistItemEntity(
                            id = itemId,
                            playlistId = plId,
                            trackId = trackId,
                            position = position,
                            addedAt = parseIsoDateToMillis(addedStr),
                            addedFromContextType = itemMap["added_from_context_type"] as? String,
                            addedFromContextId = itemMap["added_from_context_id"] as? String
                        )
                        database.playlistDao().insertPlaylistItem(itemEntity)
                    }
                }
            }
        }

        // Save token
        database.userSettingsDao().updateSyncToken(serverToken, System.currentTimeMillis())
        Log.i(TAG, "Bootstrap Sync successfully applied. Initial token saved.")
        return true
    }

    /**
     * INCREMENTAL SYNC : Push local outbox mutations, then pull server deltas.
     */
    private suspend fun performIncrementalSync(deviceId: String, currentToken: String): Boolean {
        // --- 1. PUSH PHASE ---
        val pendingOps = database.syncOutboxDao().getPendingOperations()
        var activeToken = currentToken

        val activeSnapshot = database.playbackSnapshotDao().getActiveSnapshot()

        if (pendingOps.isNotEmpty() || activeSnapshot != null) {
            Log.i(TAG, "Pushing ${pendingOps.size} local mutations (+ active snapshot) to backend...")
            val syncOps = pendingOps.map { op ->
                SyncOperationDto(
                    operationId = op.id,
                    entityType = op.entityType,
                    entityId = op.entityId,
                    operationType = op.operationType,
                    deviceId = deviceId,
                    occurredAt = formatMillisToIsoDate(op.createdAt),
                    payload = mapAdapter.fromJson(op.payloadJson) ?: emptyMap()
                )
            }.toMutableList()

            // Dynamically append the latest active playback snapshot to the batch
            if (activeSnapshot != null) {
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
                        )
                    )
                )
            }
            
            val request = PushBatchRequestDto(
                deviceId = deviceId,
                batchId = "batch_${UUID.randomUUID()}",
                sentAt = formatMillisToIsoDate(System.currentTimeMillis()),
                operations = syncOps
            )

            val response = apiService.pushBatch(AUTH_TOKEN, request)
            if (response.error != null || response.data == null) {
                Log.e(TAG, "Push batch failed: ${response.error?.message}")
                return false
            }

            val data = response.data
            activeToken = data.nextPullToken.value
            
            // Delete successfully processed operations
            for (res in data.results) {
                if (res.status in listOf("applied", "merged", "ignored_duplicate")) {
                    database.syncOutboxDao().deleteOperation(res.operationId)
                    Log.d(TAG, "Sync operation ${res.operationId} successfully synchronized & deleted from local outbox.")
                } else if (res.status == "conflict") {
                    Log.w(TAG, "Sync operation ${res.operationId} got conflict: ${res.conflict}")
                    // On conflict, let's delete it so the server pull phase overrides it with authority
                    database.syncOutboxDao().deleteOperation(res.operationId)
                }
            }
        }

        // --- 2. PULL PHASE ---
        Log.i(TAG, "Pulling server changes since token $activeToken...")
        val pullRequest = PullBatchRequestDto(
            deviceId = deviceId,
            sinceToken = activeToken,
            limit = 200
        )

        val pullResponse = apiService.pullBatch(AUTH_TOKEN, pullRequest)
        if (pullResponse.error != null || pullResponse.data == null) {
            Log.e(TAG, "Pull batch failed: ${pullResponse.error?.message}")
            return false
        }

        val pullData = pullResponse.data
        val changes = pullData.changes
        Log.i(TAG, "Received ${changes.size} changes from server.")

        for (change in changes) {
            applyServerChange(change)
        }

        // Rotate token & update last_sync_at
        val nextToken = pullData.nextPullToken.value
        database.userSettingsDao().updateSyncToken(nextToken, System.currentTimeMillis())
        Log.i(TAG, "Incremental Sync completed successfully. Saved new token: $nextToken")

        return true
    }

    /**
     * Appliquer une modification distante reçue du serveur.
     */
    private suspend fun applyServerChange(change: ServerChangeDto) {
        val payload = change.payload
        val entityId = change.entityId
        
        try {
            when (change.entityType) {
                "user_settings" -> {
                    val localSettings = database.userSettingsDao().getSettings()
                    if (localSettings != null) {
                        val updated = localSettings.copy(
                            onlineSearchEnabled = (payload["online_search_enabled"] as? Boolean) ?: localSettings.onlineSearchEnabled,
                            onlineSearchNetworkPolicy = (payload["online_search_network_policy"] as? String) ?: localSettings.onlineSearchNetworkPolicy,
                            statsSyncNetworkPolicy = (payload["stats_sync_network_policy"] as? String) ?: localSettings.statsSyncNetworkPolicy
                        )
                        database.userSettingsDao().insertOrReplace(updated)
                    }
                }
                "playlist" -> {
                    if (change.changeType == "delete") {
                        database.playlistDao().deletePlaylist(entityId)
                        Log.d(TAG, "Pulled DELETE playlist $entityId")
                    } else {
                        val name = payload["name"] as? String ?: "Unnamed Playlist"
                        val isPinned = (payload["is_pinned"] as? Boolean) ?: false
                        val coverUri = payload["cover_uri"] as? String
                        val createdStr = payload["created_at"] as? String
                        val updatedStr = payload["updated_at"] as? String
                        
                        val pl = PlaylistEntity(
                            id = entityId,
                            name = name,
                            coverUri = coverUri,
                            isPinned = isPinned,
                            createdAt = parseIsoDateToMillis(createdStr),
                            updatedAt = parseIsoDateToMillis(updatedStr)
                        )
                        database.playlistDao().insertPlaylist(pl)
                        Log.d(TAG, "Pulled UPSERT playlist $entityId")
                    }
                }
                "playlist_item" -> {
                    if (change.changeType == "delete") {
                        database.playlistDao().deletePlaylistItem(entityId)
                        Log.d(TAG, "Pulled DELETE playlist_item $entityId")
                    } else {
                        val playlistId = payload["playlist_id"] as? String ?: return
                        val trackId = payload["track_id"] as? String ?: return
                        val position = (payload["position"] as? Double)?.toInt() ?: 0
                        val addedStr = payload["added_at"] as? String
                        
                        ensureTrackStub(trackId)
                        
                        val item = PlaylistItemEntity(
                            id = entityId,
                            playlistId = playlistId,
                            trackId = trackId,
                            position = position,
                            addedAt = parseIsoDateToMillis(addedStr),
                            addedFromContextType = payload["added_from_context_type"] as? String,
                            addedFromContextId = payload["added_from_context_id"] as? String
                        )
                        database.playlistDao().insertPlaylistItem(item)
                        Log.d(TAG, "Pulled UPSERT playlist_item $entityId (pos $position)")
                    }
                }
                "track_like" -> {
                    val trackId = entityId
                    if (change.changeType == "delete" || (payload["is_liked"] as? Boolean == false)) {
                        database.trackLikeDao().deleteLike(trackId)
                        database.trackLikeDao().setTrackIsLiked(trackId, false, System.currentTimeMillis())
                        Log.d(TAG, "Pulled DELETE track_like $trackId")
                    } else {
                        val likedAtStr = payload["liked_at"] as? String
                        val likedAt = parseIsoDateToMillis(likedAtStr)
                        
                        ensureTrackStub(trackId)
                        
                        val like = TrackLikeEntity(
                            trackId = trackId,
                            likedAt = likedAt,
                            sourceContextType = payload["source_context_type"] as? String,
                            sourceContextId = payload["source_context_id"] as? String
                        )
                        database.trackLikeDao().insertLike(like)
                        database.trackLikeDao().setTrackIsLiked(trackId, true, likedAt)
                        Log.d(TAG, "Pulled UPSERT track_like $trackId")
                    }
                }
                "playback_snapshot" -> {
                    // Update the active playback snapshot
                    val currentTrackId = payload["current_track_id"] as? String
                    val positionMs = (payload["position_ms"] as? Double)?.toLong() ?: 0L
                    val shuffleEnabled = (payload["shuffle_enabled"] as? Boolean) ?: false
                    val repeatMode = payload["repeat_mode"] as? String ?: "none"
                    
                    val snapshot = com.aura.music.data.local.PlaybackSnapshotEntity(
                        id = "active",
                        currentTrackId = currentTrackId,
                        playbackContextType = payload["playback_context_type"] as? String,
                        playbackContextId = payload["playback_context_id"] as? String,
                        playbackContextIndex = (payload["playback_context_index"] as? Double)?.toInt(),
                        positionMs = positionMs,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        updatedAt = System.currentTimeMillis()
                    )
                    database.playbackSnapshotDao().upsert(snapshot)
                    Log.d(TAG, "Pulled playback snapshot update")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying server change: ${e.message}", e)
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
