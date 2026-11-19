package com.aura.music.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.media.AudioIntegrityValidator
import com.aura.music.data.media.MediaLinkResolver
import com.aura.music.data.media.UriPathParser
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.SyncedFileResponseData
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.call.body
import java.io.File
import java.io.FileOutputStream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class CloudFileRepository(
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val context: Context,
    private val downloadRepositoryProvider: (() -> DownloadRepository)? = null,
    private val authTokenProvider: () -> String = { com.aura.music.core.AuthSessionManager.getInstance(context).getBearerHeader() },
    private val authSessionManagerProvider: () -> com.aura.music.core.AuthSessionManager = { com.aura.music.core.AuthSessionManager.getInstance(context) },
    private val onLibraryChanged: () -> Unit = {},
) {
    companion object {
        private const val TAG = "CloudFileRepository"
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshLock = Any()
    private val reconcileMutex = Mutex()
    private var ongoingFetchDeferred: CompletableDeferred<Result<List<SyncedFileResponseData>>>? = null
    private var fetchGeneration: Long = 0L
    private var cachedScope: com.aura.music.core.AuthSessionManager.Scope? = null
    private var hasCachedCloudFiles: Boolean = false

    private val inFlightAutoDownloads = ConcurrentHashMap.newKeySet<String>()
    val inFlightTrackIds: Set<String> get() = inFlightAutoDownloads
    private val autoDownloadSemaphore = Semaphore(1)
    private val trackDownloadMutexes = ConcurrentHashMap<String, Mutex>()
    private val cloudDownloadSemaphore = Semaphore(1)
    private val mediaCoordinator = com.aura.music.data.media.TrackMediaCoordinator.shared
    private val _networkTransfers = MutableStateFlow<Set<String>>(emptySet())
    val networkTransfers = _networkTransfers.asStateFlow()

    private fun getAuthToken(): String = authTokenProvider()

    private val _syncedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val syncedTrackIds = _syncedTrackIds.asStateFlow()

    private val _cachedCloudFiles = MutableStateFlow<List<SyncedFileResponseData>>(emptyList())
    val cachedCloudFiles = _cachedCloudFiles.asStateFlow()

    init {
        repositoryScope.launch {
            runCatching { authSessionManagerProvider() }.getOrNull()?.sessionScope?.collect { newScope ->
                synchronized(refreshLock) {
                    syncCacheScopeLocked(newScope)
                }
            }
        }
    }

    private fun syncCacheScopeLocked(currentScope: com.aura.music.core.AuthSessionManager.Scope?) {
        if (currentScope != cachedScope) {
            _syncedTrackIds.value = emptySet()
            _cachedCloudFiles.value = emptyList()
            hasCachedCloudFiles = false
            cachedScope = currentScope
            ongoingFetchDeferred = null
        }
    }

    private fun captureAuthScope(): com.aura.music.core.AuthSessionManager.Scope? =
        runCatching { authSessionManagerProvider().sessionScope.value }.getOrNull()

    private fun isScopeStillValid(expectedScope: com.aura.music.core.AuthSessionManager.Scope?): Boolean {
        val authManager = runCatching { authSessionManagerProvider() }.getOrNull() ?: return true
        if (expectedScope != null && !authManager.isLoggedIn.value) return false
        return authManager.sessionScope.value == expectedScope
    }

    private fun ensureScopeCurrent() {
        val currentScope = captureAuthScope()
        synchronized(refreshLock) {
            syncCacheScopeLocked(currentScope)
        }
    }

    fun getCachedCloudFile(trackId: String): SyncedFileResponseData? {
        ensureScopeCurrent()
        val list = _cachedCloudFiles.value
        return list.firstOrNull { it.trackId == trackId }
            ?: list.firstOrNull { com.aura.music.ui.screens.isDeezerTrackMatch(it.trackId, trackId) }
    }

    fun isCloudTrackSynced(trackId: String): Boolean {
        ensureScopeCurrent()
        val ids = _syncedTrackIds.value
        if (ids.contains(trackId)) return true
        val cleanId = trackId.removePrefix("deezer:").removePrefix("ytm:")
        return ids.any {
            it == trackId || it.removePrefix("deezer:").removePrefix("ytm:") == cleanId
        }
    }

    fun getCachedCloudTrackIds(): Set<String> {
        ensureScopeCurrent()
        return _syncedTrackIds.value
    }

    suspend fun getOrFetchCloudFiles(forceRefresh: Boolean = false): Result<List<SyncedFileResponseData>> {
        var forceThresholdGen: Long? = null

        while (true) {
            val authManager = runCatching { authSessionManagerProvider() }.getOrNull()
            val requestScope = authManager?.sessionScope?.value
            if (authManager != null && !authManager.isLoggedIn.value) {
                synchronized(refreshLock) {
                    syncCacheScopeLocked(requestScope)
                    _syncedTrackIds.value = emptySet()
                    _cachedCloudFiles.value = emptyList()
                    hasCachedCloudFiles = false
                }
                return Result.success(emptyList())
            }

            var joinExisting: Deferred<Result<List<SyncedFileResponseData>>>? = null
            var startedByMe: CompletableDeferred<Result<List<SyncedFileResponseData>>>? = null
            var joinedGen = 0L

            synchronized(refreshLock) {
                syncCacheScopeLocked(requestScope)
                val stillNeedsForce = forceRefresh &&
                    (forceThresholdGen == null || fetchGeneration <= forceThresholdGen!!)
                val existing = ongoingFetchDeferred
                if (existing != null && existing.isActive) {
                    if (!forceRefresh && hasCachedCloudFiles) {
                        return Result.success(_cachedCloudFiles.value)
                    }
                    joinedGen = fetchGeneration
                    if (stillNeedsForce && forceThresholdGen == null) {
                        forceThresholdGen = joinedGen
                    }
                    joinExisting = existing
                } else if (!stillNeedsForce && hasCachedCloudFiles) {
                    return Result.success(_cachedCloudFiles.value)
                } else {
                    fetchGeneration += 1L
                    val created = CompletableDeferred<Result<List<SyncedFileResponseData>>>()
                    ongoingFetchDeferred = created
                    startedByMe = created
                }
            }

            if (joinExisting != null) {
                val res = joinExisting!!.await()
                val currentAuthScope = runCatching { authSessionManagerProvider().sessionScope.value }.getOrNull()
                if (currentAuthScope != requestScope) {
                    // Scope changed while waiting; loop to observe or fetch for the new session.
                    forceThresholdGen = null
                    continue
                }
                val mustFollowUp = forceRefresh &&
                    (forceThresholdGen != null && joinedGen <= forceThresholdGen!!)
                if (!mustFollowUp) {
                    return res
                }
                // A forced refresh arrived while an older fetch was in-flight; loop so a post-threshold fetch executes.
                continue
            }

            val deferred = startedByMe!!
            val result = try {
                doFetchAndReconcileCloudFiles(requestScope)
            } catch (e: CancellationException) {
                synchronized(refreshLock) {
                    if (ongoingFetchDeferred === deferred) {
                        ongoingFetchDeferred = null
                    }
                }
                deferred.cancel(e)
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                synchronized(refreshLock) {
                    if (ongoingFetchDeferred === deferred) {
                        ongoingFetchDeferred = null
                    }
                }
            }
            deferred.complete(result)
            return result
        }
    }

    suspend fun refreshSyncedTrackIds() {
        getOrFetchCloudFiles(forceRefresh = true)
    }

    private suspend fun doFetchAndReconcileCloudFiles(
        requestScope: com.aura.music.core.AuthSessionManager.Scope?
    ): Result<List<SyncedFileResponseData>> = withContext(Dispatchers.IO) {
        val authManager = runCatching { authSessionManagerProvider() }.getOrNull()
        if (authManager != null && (!authManager.isLoggedIn.value || authManager.sessionScope.value != requestScope)) {
            synchronized(refreshLock) {
                syncCacheScopeLocked(authManager.sessionScope.value)
            }
            return@withContext Result.success(emptyList())
        }

        try {
            val response = apiService.listSyncFiles(getAuthToken())
            val postNetworkAuth = runCatching { authSessionManagerProvider() }.getOrNull()
            if (postNetworkAuth != null && (!postNetworkAuth.isLoggedIn.value || postNetworkAuth.sessionScope.value != requestScope)) {
                Log.i(TAG, "Ignoring cloud file list response: auth session changed during request")
                synchronized(refreshLock) {
                    syncCacheScopeLocked(postNetworkAuth.sessionScope.value)
                }
                return@withContext Result.success(emptyList())
            }

            val data = response.data
            if (response.error != null || data == null) {
                val errorMsg = response.error?.message ?: "Erreur inconnue de l'API cloud list"
                Log.w(TAG, "Failed to fetch cloud files: $errorMsg")
                return@withContext Result.failure(Exception(errorMsg))
            }

            val items = data.items
            // Publish the shared inventory only once Room and the local index agree.
            reconcileCloudTracksWithDatabase(items, expectedScope = requestScope)
            var scopeRemainsCurrent = true
            synchronized(refreshLock) {
                val liveScope = captureAuthScope()
                if (authManager != null && (!authManager.isLoggedIn.value || liveScope != requestScope)) {
                    syncCacheScopeLocked(liveScope)
                    scopeRemainsCurrent = false
                } else {
                    cachedScope = requestScope
                    _syncedTrackIds.value = items.map { it.trackId }.toSet()
                    _cachedCloudFiles.value = items
                    hasCachedCloudFiles = true
                }
            }
            if (!scopeRemainsCurrent) {
                return@withContext Result.success(emptyList())
            }

            if (!isScopeStillValid(requestScope)) {
                return@withContext Result.success(emptyList())
            }
            Log.i(TAG, "Refreshed synced track IDs: ${items.size} tracks")
            Result.success(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh cloud files", e)
            Result.failure(e)
        }
    }

    suspend fun reconcileCloudTracksWithDatabase(
        items: List<SyncedFileResponseData>,
        expectedScope: com.aura.music.core.AuthSessionManager.Scope? = captureAuthScope(),
    ) = withContext(Dispatchers.IO) {
        if (!isScopeStillValid(expectedScope)) return@withContext
        reconcileMutex.withLock {
            if (!isScopeStillValid(expectedScope)) return@withLock
            val now = System.currentTimeMillis()
            try {
                val serverCloudIds = items.map { it.trackId }.toSet()

                // 1. Purge any orphan cloud-only tracks from Room that are no longer on the Cloud server
                val allTracks = database.trackDao().getAllTracks()
                val activeJobTrackIds = try {
                    database.downloadJobDao().getActiveJobs().map { it.trackId }.toSet()
                } catch (e: Exception) {
                    emptySet()
                }
                val playlistTrackIds = try {
                    database.playlistDao().getAllPlaylistTrackIds().toSet()
                } catch (e: Exception) {
                    emptySet()
                }

                val candidateOrphanIds = allTracks.filter { track ->
                    track.contentUri.isNullOrBlank() &&
                    track.id !in serverCloudIds &&
                    track.id !in activeJobTrackIds &&
                    track.id !in playlistTrackIds &&
                    !track.isLiked &&
                    !serverCloudIds.any { com.aura.music.ui.screens.isDeezerTrackMatch(it, track.id) }
                }.map { it.id }

                val orphanIds = if (candidateOrphanIds.isEmpty()) {
                    emptyList()
                } else {
                    candidateOrphanIds.filter { id ->
                        val raw = database.trackDao().getRawTrackById(id)
                        raw != null &&
                            raw.canonicalAudioSourceType != "downloaded" &&
                            raw.canonicalAudioSourceType != "local" &&
                            !raw.isDownloadedByAura
                    }
                }

                // 2. Collect inserts and genuine metadata updates in memory first
                val artistsToInsert = linkedMapOf<String, com.aura.music.data.local.ArtistEntity>()
                val albumsToInsert = linkedMapOf<String, com.aura.music.data.local.AlbumEntity>()
                val tracksToUpsert = mutableListOf<com.aura.music.data.local.TrackEntity>()
                val existingById = mutableMapOf<String, com.aura.music.data.local.TrackEntity>()

                for (item in items) {
                    val existingTrack = database.trackDao().getRawTrackById(item.trackId)
                    if (existingTrack == null) {
                        val fileTitle = item.title?.takeIf { it.isNotBlank() } ?: "Piste Cloud ${item.trackId}"
                        val artist = item.artistName?.takeIf { it.isNotBlank() } ?: "Artiste Inconnu"
                        val album = item.albumTitle?.takeIf { it.isNotBlank() } ?: "Album Inconnu"
                        val duration = item.durationMs ?: 0L
                        val artistId = item.artistId
                        val albumId = item.albumId

                        if (artistId != null && !artistsToInsert.containsKey(artistId)) {
                            artistsToInsert[artistId] = com.aura.music.data.local.ArtistEntity(
                                id = artistId,
                                name = artist,
                                normalizedName = artist.lowercase().trim(),
                                pictureUri = item.coverUri,
                                artworkOrigin = null,
                                artworkLastResolvedAt = null,
                                summary = null,
                                createdAt = now,
                                updatedAt = now
                            )
                        }

                        if (albumId != null && !albumsToInsert.containsKey(albumId)) {
                            albumsToInsert[albumId] = com.aura.music.data.local.AlbumEntity(
                                id = albumId,
                                primaryArtistId = artistId,
                                title = album,
                                normalizedTitle = album.lowercase().trim(),
                                coverUri = item.coverUri,
                                artworkOrigin = null,
                                artworkLastResolvedAt = null,
                                releaseDate = null,
                                trackCount = null,
                                createdAt = now,
                                updatedAt = now
                            )
                        }

                        tracksToUpsert.add(
                            com.aura.music.data.local.TrackEntity(
                                id = item.trackId,
                                primaryArtistId = artistId,
                                albumId = albumId,
                                title = fileTitle,
                                normalizedTitle = fileTitle.lowercase().trim(),
                                displayArtistName = artist,
                                displayAlbumTitle = album,
                                durationMs = duration,
                                coverUri = item.coverUri,
                                canonicalAudioSourceType = "cloud",
                                isLiked = false,
                                isDownloadedByAura = false,
                                isExplicit = false,
                                popularity = 0,
                                genresJson = null,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    } else {
                        existingById[existingTrack.id] = existingTrack
                        var trackToUpdate = existingTrack

                        val isPlaceholderTitle = existingTrack.title.startsWith("Piste Cloud ") ||
                            existingTrack.title.isBlank() ||
                            existingTrack.title == "Titre inconnu"
                        val isPlaceholderArtist = existingTrack.displayArtistName.isBlank() ||
                            existingTrack.displayArtistName.equals("Artiste Inconnu", ignoreCase = true)
                        val isPlaceholderAlbum = existingTrack.displayAlbumTitle.isNullOrBlank() ||
                            existingTrack.displayAlbumTitle.equals("Album Inconnu", ignoreCase = true)

                        val itemTitle = item.title?.takeIf { it.isNotBlank() }
                        val itemArtist = item.artistName?.takeIf { it.isNotBlank() }
                        val itemAlbum = item.albumTitle?.takeIf { it.isNotBlank() }

                        val resolvedTitle = if (isPlaceholderTitle && itemTitle != null) itemTitle else trackToUpdate.title
                        val resolvedArtist = if ((isPlaceholderTitle || isPlaceholderArtist) && itemArtist != null) itemArtist else trackToUpdate.displayArtistName
                        val resolvedAlbum = if ((isPlaceholderTitle || isPlaceholderAlbum) && itemAlbum != null) itemAlbum else trackToUpdate.displayAlbumTitle
                        val resolvedArtistId = if (item.artistId != null &&
                            (isPlaceholderTitle || isPlaceholderArtist ||
                                (trackToUpdate.primaryArtistId == null && itemArtist == trackToUpdate.displayArtistName))
                        ) item.artistId else trackToUpdate.primaryArtistId
                        val resolvedAlbumId = if (item.albumId != null &&
                            (isPlaceholderTitle || isPlaceholderAlbum ||
                                (trackToUpdate.albumId == null && itemAlbum == trackToUpdate.displayAlbumTitle))
                        ) item.albumId else trackToUpdate.albumId
                        val itemDuration = item.durationMs
                        val resolvedDuration = if ((trackToUpdate.durationMs == null || trackToUpdate.durationMs == 0L || isPlaceholderTitle) && itemDuration != null && itemDuration > 0L) {
                            itemDuration
                        } else {
                            trackToUpdate.durationMs
                        }

                        if (resolvedTitle != trackToUpdate.title ||
                            resolvedArtist != trackToUpdate.displayArtistName ||
                            resolvedAlbum != trackToUpdate.displayAlbumTitle ||
                            resolvedArtistId != trackToUpdate.primaryArtistId ||
                            resolvedAlbumId != trackToUpdate.albumId ||
                            resolvedDuration != trackToUpdate.durationMs
                        ) {
                            if (resolvedArtistId != null && !artistsToInsert.containsKey(resolvedArtistId)) {
                                artistsToInsert[resolvedArtistId] = com.aura.music.data.local.ArtistEntity(
                                    id = resolvedArtistId,
                                    name = resolvedArtist,
                                    normalizedName = resolvedArtist.lowercase().trim(),
                                    pictureUri = item.coverUri ?: existingTrack.coverUri,
                                    artworkOrigin = null,
                                    artworkLastResolvedAt = null,
                                    summary = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            }
                            if (resolvedAlbumId != null && resolvedAlbum != null && !albumsToInsert.containsKey(resolvedAlbumId)) {
                                albumsToInsert[resolvedAlbumId] = com.aura.music.data.local.AlbumEntity(
                                    id = resolvedAlbumId,
                                    primaryArtistId = resolvedArtistId,
                                    title = resolvedAlbum,
                                    normalizedTitle = resolvedAlbum.lowercase().trim(),
                                    coverUri = item.coverUri ?: existingTrack.coverUri,
                                    artworkOrigin = null,
                                    artworkLastResolvedAt = null,
                                    releaseDate = null,
                                    trackCount = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            }

                            trackToUpdate = trackToUpdate.copy(
                                title = resolvedTitle,
                                normalizedTitle = resolvedTitle.lowercase().trim(),
                                displayArtistName = resolvedArtist,
                                displayAlbumTitle = resolvedAlbum,
                                primaryArtistId = resolvedArtistId,
                                albumId = resolvedAlbumId,
                                durationMs = resolvedDuration
                            )
                        }

                        if (trackToUpdate.coverUri.isNullOrBlank() && !item.coverUri.isNullOrBlank()) {
                            trackToUpdate = trackToUpdate.copy(coverUri = item.coverUri)
                        }
                        if (trackToUpdate.canonicalAudioSourceType != "downloaded" &&
                            trackToUpdate.canonicalAudioSourceType != "local" &&
                            trackToUpdate.canonicalAudioSourceType != "cloud"
                        ) {
                            trackToUpdate = trackToUpdate.copy(canonicalAudioSourceType = "cloud")
                        }

                        if (trackToUpdate != existingTrack) {
                            tracksToUpsert.add(trackToUpdate.copy(updatedAt = now))
                        }
                    }
                }

                if (orphanIds.isEmpty() &&
                    artistsToInsert.isEmpty() &&
                    albumsToInsert.isEmpty() &&
                    tracksToUpsert.isEmpty()
                ) {
                    Log.d(TAG, "Cloud reconciliation: 0 changes across ${items.size} items, skipping DB write")
                    return@withLock
                }

                // 3. Apply all mutations atomically in a single immediate transaction
                val committed = withContext(NonCancellable) {
                    database.useWriterConnection { transactor ->
                        transactor.immediateTransaction {
                            if (!isScopeStillValid(expectedScope)) return@immediateTransaction false
                            if (orphanIds.isNotEmpty()) {
                                val liveActiveJobTrackIds = try {
                                    database.downloadJobDao().getActiveJobs().map { it.trackId }.toSet()
                                } catch (e: Exception) {
                                    emptySet()
                                }
                                val livePlaylistTrackIds = try {
                                    database.playlistDao().getAllPlaylistTrackIds().toSet()
                                } catch (e: Exception) {
                                    emptySet()
                                }
                                val safeOrphans = orphanIds.filter { id ->
                                    val live = database.trackDao().getRawTrackById(id)
                                    val liveContentUri = database.trackDao().getTrackContentUri(id)
                                    live != null &&
                                        !live.isLiked &&
                                        live.canonicalAudioSourceType != "downloaded" &&
                                        live.canonicalAudioSourceType != "local" &&
                                        !live.isDownloadedByAura &&
                                        liveContentUri.isNullOrBlank() &&
                                        id !in liveActiveJobTrackIds &&
                                        id !in livePlaylistTrackIds
                                }
                                if (safeOrphans.isNotEmpty()) {
                                    database.trackDao().deleteTracksByIds(safeOrphans)
                                    Log.i(TAG, "Purged ${safeOrphans.size} orphan Cloud tracks from Room: $safeOrphans")
                                }
                            }
                            if (artistsToInsert.isNotEmpty()) {
                                database.artistDao().insertArtistsIgnore(artistsToInsert.values.toList())
                            }
                            if (albumsToInsert.isNotEmpty()) {
                                database.albumDao().insertAlbumsIgnore(albumsToInsert.values.toList())
                            }
                            if (tracksToUpsert.isNotEmpty()) {
                                val safeTracks = tracksToUpsert.map { candidate ->
                                    val live = database.trackDao().getRawTrackById(candidate.id)
                                    if (live == null) {
                                        candidate
                                    } else if (existingById[candidate.id]?.let { it != live } == true) {
                                        // A manual edit or download landed after the read phase.
                                        // Keep that row; the next reconciliation can fill missing IDs.
                                        live
                                    } else {
                                        val liveCover = live.coverUri
                                        val preserveLocalCover = !liveCover.isNullOrBlank() && (
                                            liveCover.startsWith("file:") ||
                                                liveCover.startsWith("content:") ||
                                                liveCover.startsWith("/")
                                            )
                                        candidate.copy(
                                            isLiked = candidate.isLiked || live.isLiked,
                                            canonicalAudioSourceType = if (live.canonicalAudioSourceType == "downloaded" || live.canonicalAudioSourceType == "local") {
                                                live.canonicalAudioSourceType
                                            } else {
                                                candidate.canonicalAudioSourceType
                                            },
                                            isDownloadedByAura = candidate.isDownloadedByAura || live.isDownloadedByAura,
                                            coverUri = if (preserveLocalCover) liveCover else candidate.coverUri
                                        )
                                    }
                                }
                                database.trackDao().upsertTracks(safeTracks)
                                Log.d(TAG, "Batch-reconciled ${safeTracks.size} Cloud TrackEntity rows in single transaction")
                            }
                            true
                        }
                    }
                }
                if (committed && isScopeStillValid(expectedScope)) onLibraryChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconcile cloud tracks with database", e)
                throw e
            }
        }
    }

    suspend fun autoDownloadFavoriteTrack(trackId: String) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_download_favorites", false)) return@withContext

        if (!inFlightAutoDownloads.add(trackId)) {
            Log.d(TAG, "Auto-download for $trackId already in-flight, skipping duplicate.")
            return@withContext
        }

        try {
            val trackRow = database.trackDao().getTrackById(trackId)
            val downloadsDir = File(context.filesDir, "downloads")
            val physicalFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")

            // If track already has a valid existing contentUri
            val contentUri = trackRow?.contentUri
            if (!contentUri.isNullOrBlank()) {
                val existingLocal = UriPathParser.parseLocalFile(contentUri)
                if (existingLocal != null && existingLocal.exists() && AudioIntegrityValidator.isValidAudioFile(existingLocal, checkMetadata = false)) {
                    Log.i(TAG, "Favorite track $trackId is already present on device.")
                    return@withContext
                }
            }

            // Self-healing: if physical file already exists on disk, re-link immediately without downloading
            if (physicalFile.exists() && AudioIntegrityValidator.isValidAudioFile(physicalFile, checkMetadata = false)) {
                Log.i(TAG, "Physical file already exists for favorite track $trackId (${physicalFile.length()} bytes). Re-linking in DB...")
                linkDownloadedTrack(
                    trackId = trackId,
                    targetFile = physicalFile,
                    title = trackRow?.title,
                    artistName = trackRow?.artistName,
                    albumTitle = trackRow?.albumTitle,
                    durationMs = trackRow?.durationMs,
                    artistId = trackRow?.artistId,
                    albumId = trackRow?.albumId,
                    coverUri = trackRow?.coverUri
                )
                return@withContext
            }

            autoDownloadSemaphore.withPermit {
                Log.i(TAG, "Auto-downloading favorite track $trackId...")
                downloadTrack(
                    trackId = trackId,
                    title = trackRow?.title,
                    artistName = trackRow?.artistName,
                    albumTitle = trackRow?.albumTitle,
                    durationMs = trackRow?.durationMs,
                    artistId = trackRow?.artistId,
                    albumId = trackRow?.albumId,
                    coverUri = trackRow?.coverUri
                ).collect { res ->
                    res.onSuccess {
                        Log.i(TAG, "Successfully auto-downloaded favorite track $trackId to ${it.absolutePath}")
                    }.onFailure { err ->
                        Log.w(TAG, "Failed auto-downloading favorite track $trackId: ${err.message}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Auto-download favorite track $trackId exception", e)
        } finally {
            inFlightAutoDownloads.remove(trackId)
        }
    }

    suspend fun autoDownloadPlaylistTrack(playlistId: String, trackId: String) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_download_playlist_$playlistId", false)) return@withContext

        if (!inFlightAutoDownloads.add(trackId)) {
            Log.d(TAG, "Auto-download for $trackId already in-flight, skipping duplicate.")
            return@withContext
        }

        try {
            val trackRow = database.trackDao().getTrackById(trackId)
            val downloadsDir = File(context.filesDir, "downloads")
            val physicalFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")

            // If track already has a valid existing contentUri
            val contentUri = trackRow?.contentUri
            if (!contentUri.isNullOrBlank()) {
                val existingLocal = UriPathParser.parseLocalFile(contentUri)
                if (existingLocal != null && existingLocal.exists() && AudioIntegrityValidator.isValidAudioFile(existingLocal, checkMetadata = false)) {
                    Log.i(TAG, "Playlist track $trackId is already present on device.")
                    return@withContext
                }
            }

            // Self-healing: if physical file already exists on disk, re-link immediately without downloading
            if (physicalFile.exists() && AudioIntegrityValidator.isValidAudioFile(physicalFile, checkMetadata = false)) {
                Log.i(TAG, "Physical file already exists for playlist track $trackId (${physicalFile.length()} bytes). Re-linking in DB...")
                linkDownloadedTrack(
                    trackId = trackId,
                    targetFile = physicalFile,
                    title = trackRow?.title,
                    artistName = trackRow?.artistName,
                    albumTitle = trackRow?.albumTitle,
                    durationMs = trackRow?.durationMs,
                    artistId = trackRow?.artistId,
                    albumId = trackRow?.albumId,
                    coverUri = trackRow?.coverUri
                )
                return@withContext
            }

            autoDownloadSemaphore.withPermit {
                Log.i(TAG, "Auto-downloading playlist track $trackId...")
                downloadTrack(
                    trackId = trackId,
                    title = trackRow?.title,
                    artistName = trackRow?.artistName,
                    albumTitle = trackRow?.albumTitle,
                    durationMs = trackRow?.durationMs,
                    artistId = trackRow?.artistId,
                    albumId = trackRow?.albumId,
                    coverUri = trackRow?.coverUri
                ).collect { res ->
                    res.onSuccess {
                        Log.i(TAG, "Successfully auto-downloaded playlist track $trackId to ${it.absolutePath}")
                    }.onFailure { err ->
                        Log.w(TAG, "Failed auto-downloading playlist track $trackId: ${err.message}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Auto-download playlist track $trackId exception", e)
        } finally {
            inFlightAutoDownloads.remove(trackId)
        }
    }

    /**
     * Téléverse automatiquement vers le Cloud un morceau local lorsqu'il est ajouté aux favoris,
     * assurant ainsi sa disponibilité immédiate en streaming et téléchargement sur tous les appareils.
     */
    suspend fun autoUploadFavoriteTrack(trackId: String) = withContext(Dispatchers.IO) {
        try {
            val trackRow = database.trackDao().getTrackById(trackId)
            val rawTrack = database.trackDao().getRawTrackById(trackId)
            val isLocal = trackId.startsWith("track:local:") || trackId.startsWith("local:") || (rawTrack?.canonicalAudioSourceType == "local")
            if (!isLocal || trackRow?.contentUri.isNullOrBlank()) {
                return@withContext
            }

            if (_syncedTrackIds.value.contains(trackId)) {
                return@withContext
            }

            Log.i(TAG, "Auto-uploading liked local track $trackId to cloud...")
            uploadTrack(trackId).collect { res ->
                res.onSuccess {
                    Log.i(TAG, "Successfully auto-uploaded favorite track $trackId to cloud")
                }.onFailure { err ->
                    Log.w(TAG, "Failed auto-uploading favorite track $trackId: ${err.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-upload favorite track $trackId exception", e)
        }
    }

    fun uploadTrack(trackId: String): Flow<Result<SyncedFileResponseData>> = flow {
        try {
            val requestScope = captureAuthScope()
            val trackRow = database.trackDao().getTrackById(trackId)
            if (trackRow == null) {
                emit(Result.failure(Exception("Piste introuvable localement")))
                return@flow
            }

            val localUri = trackRow.contentUri
            if (localUri.isNullOrBlank()) {
                emit(Result.failure(Exception("Le fichier physique de la piste est manquant")))
                return@flow
            }

            Log.i(TAG, "Starting cloud upload for track $trackId (URI: $localUri)")

            val fileBytes: ByteArray? = (try {
                when {
                    localUri.startsWith("file:") -> {
                        val path = try { java.net.URI.create(localUri).path } catch (e: Exception) { localUri.removePrefix("file://").removePrefix("file:") }
                        val f = File(path)
                        if (f.exists()) f.readBytes() else null
                    }
                    localUri.startsWith("/") -> {
                        val f = File(localUri)
                        if (f.exists()) f.readBytes() else null
                    }
                    localUri.startsWith("content://") -> {
                        val uri = Uri.parse(localUri)
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    else -> {
                        val f = File(localUri)
                        if (f.exists()) f.readBytes() else {
                            context.contentResolver.openInputStream(Uri.parse(localUri))?.use { it.readBytes() }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read bytes for $localUri", e)
                null
            }) ?: run {
                // Fallback de secours : chercher dans le cache downloads local
                val downloadsDir = File(context.filesDir, "downloads")
                val localDownloadFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")
                if (localDownloadFile.exists()) localDownloadFile.readBytes() else null
            }

            if (fileBytes == null || fileBytes.isEmpty()) {
                emit(Result.failure(Exception("Impossible de lire les octets du fichier local ($localUri)")))
                return@flow
            }

            val mimeType = if (localUri.endsWith(".m4a", ignoreCase = true)) "audio/mp4" else if (localUri.endsWith(".wav", ignoreCase = true)) "audio/wav" else "audio/mpeg"

            var uploadCoverUri = trackRow.coverUri
            if (uploadCoverUri.isNullOrBlank() || !uploadCoverUri.startsWith("http")) {
                try {
                    val queryArtist = trackRow.artistName ?: ""
                    val searchResult = apiService.search("${trackRow.title} $queryArtist".trim(), limitTracks = 3)
                    val resolved = searchResult.data?.tracks?.firstOrNull { it.coverUri?.startsWith("http") == true }?.coverUri
                    if (resolved != null) {
                        uploadCoverUri = resolved
                        // Update Room local entry to preserve the resolved HTTPS cover (if it was null/empty)
                        val rawTrack = database.trackDao().getRawTrackById(trackId)
                        if (rawTrack != null && rawTrack.coverUri.isNullOrBlank()) {
                            database.useWriterConnection { transactor ->
                                transactor.immediateTransaction {
                                    val updatedTrack = rawTrack.copy(coverUri = resolved)
                                    database.trackDao().upsertTrack(updatedTrack)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve cover online for upload of track $trackId", e)
                }
            }

            val response = apiService.uploadSyncFile(
                token = getAuthToken(),
                trackId = trackId,
                fileBytes = fileBytes,
                mimeType = mimeType,
                title = trackRow.title,
                artistName = trackRow.artistName,
                albumTitle = trackRow.albumTitle,
                durationMs = trackRow.durationMs,
                artistId = trackRow.artistId,
                albumId = trackRow.albumId,
                coverUri = uploadCoverUri
            )

            val data = response.data
            if (response.error != null || data == null) {
                val errorMsg = response.error?.message ?: "Erreur inconnue de l'API d'upload"
                emit(Result.failure(Exception(errorMsg)))
                return@flow
            }

            Log.i(TAG, "Successfully uploaded track $trackId to cloud")
            synchronized(refreshLock) {
                if (isScopeStillValid(requestScope)) {
                    syncCacheScopeLocked(requestScope)
                    _syncedTrackIds.value = _syncedTrackIds.value + trackId
                    if (hasCachedCloudFiles) {
                        _cachedCloudFiles.value = _cachedCloudFiles.value.filterNot { it.trackId == data.trackId } + data
                    }
                } else {
                    ensureScopeCurrent()
                }
            }
            emit(Result.success(data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload track $trackId", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads a track from the cloud to the local device downloads folder.
     */
    fun downloadTrack(
        trackId: String,
        title: String? = null,
        artistName: String? = null,
        albumTitle: String? = null,
        durationMs: Long? = null,
        artistId: String? = null,
        albumId: String? = null,
        coverUri: String? = null
    ): Flow<Result<File>> = flow {
        val mutex = trackDownloadMutexes.computeIfAbsent(trackId) { Mutex() }
        mutex.withLock {
            var pendingFile: File? = null
            try {
                // Dynamic disk space check: require at least 50MB of usable storage
                val usableSpace = context.filesDir.usableSpace
                if (usableSpace < 50 * 1024 * 1024L) {
                    Log.w(TAG, "Not enough disk space ($usableSpace bytes available, requires 50MB) to download track $trackId")
                    emit(Result.failure(Exception("Espace disque insuffisant (minimum 50 Mo requis)")))
                    return@withLock
                }

                val downloadsDir = File(context.filesDir, "downloads")
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }

                val targetFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")
                val tempFile = File.createTempFile("aura-transfer-", ".tmp", downloadsDir).also { pendingFile = it }

                // If file already exists physically with valid size & magic bytes, self-heal DB link and return immediately
                if (targetFile.exists()) {
                    if (AudioIntegrityValidator.isValidAudioFile(targetFile, checkMetadata = false)) {
                        val trackRow = database.trackDao().getTrackById(trackId)
                        val rawTrack = database.trackDao().getRawTrackById(trackId)
                        if (trackRow?.contentUri != targetFile.toURI().toString() || rawTrack?.canonicalAudioSourceType != "downloaded") {
                            linkDownloadedTrack(
                                trackId = trackId,
                                targetFile = targetFile,
                                title = title,
                                artistName = artistName,
                                albumTitle = albumTitle,
                                durationMs = durationMs,
                                artistId = artistId,
                                albumId = albumId,
                                coverUri = coverUri
                            )
                        }
                        Log.i(TAG, "Track $trackId already exists on disk (${targetFile.length()} bytes), skipping download.")
                        emit(Result.success(targetFile))
                        return@withLock
                    }
                }

                if (tempFile.exists()) {
                    tempFile.delete()
                }

                _networkTransfers.update { it + trackId }
                Log.i(TAG, "Downloading track $trackId from cloud...")
                var downloadedFromCloud = false
                var cloudHttpStatus = 0

                cloudDownloadSemaphore.withPermit {
                    val response = apiService.downloadSyncFile(getAuthToken(), trackId)
                    if (response.status.value == 401 || response.status.value == 403) {
                        throw com.aura.music.core.SessionUnavailableException()
                    }

                    if (response.status.value in 200..299) {
                        try {
                            val channel = response.bodyAsChannel()
                            channel.toInputStream().use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    val buffer = ByteArray(32768)
                                    var bytesRead: Int
                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        outputStream.write(buffer, 0, bytesRead)
                                    }
                                }
                            }

                            if (AudioIntegrityValidator.isValidAudioFile(tempFile, checkMetadata = false)) {
                                mediaCoordinator.withTrack(trackId) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                        mediaCoordinator.publish(tempFile, targetFile)
                                        linkDownloadedTrackLocked(trackId, targetFile, title, artistName, albumTitle,
                                            durationMs, artistId, albumId, coverUri)
                                    }
                                }
                                downloadedFromCloud = true
                            } else {
                                Log.w(TAG, "Cloud sync file for track $trackId failed audio integrity check. Discarding temp file.")
                                if (tempFile.exists()) tempFile.delete()
                            }
                        } catch (streamEx: Exception) {
                            if (tempFile.exists()) {
                                tempFile.delete()
                            }
                            throw streamEx
                        }
                    } else {
                        cloudHttpStatus = response?.status?.value ?: 0
                    }
                }

                if (!downloadedFromCloud) {
                    val downloadRepo = downloadRepositoryProvider?.invoke()
                    if (downloadRepo != null) {
                        val trackRow = database.trackDao().getTrackById(trackId)
                        val resolvedTitle = title ?: trackRow?.title ?: "Piste ${trackId.takeLast(6)}"
                        val resolvedArtist = artistName ?: trackRow?.artistName ?: "Artiste"
                        val resolvedAlbum = albumTitle ?: trackRow?.albumTitle
                        val resolvedCover = coverUri ?: trackRow?.coverUri

                        Log.i(TAG, "Track $trackId not found in cloud sync (HTTP $cloudHttpStatus). Falling back to DownloadRepository (VPS trigger)...")
                        var triggerError: Throwable? = null
                        downloadRepo.triggerDownload(
                            trackId = trackId,
                            title = resolvedTitle,
                            artistName = resolvedArtist,
                            albumTitle = resolvedAlbum,
                            coverUri = resolvedCover,
                            userToken = getAuthToken()
                        ).collect { res ->
                            res.onFailure { triggerError = it }
                        }

                        if (triggerError != null) {
                            emit(Result.failure(triggerError ?: Exception("Échec de la soumission du téléchargement au serveur")))
                            return@withLock
                        }

                        // Wait for completion via downloadSuccessFlow
                        try {
                            withTimeout(120_000L) {
                                downloadRepo.downloadSuccessFlow.first { it == trackId }
                            }
                            if (targetFile.exists() && AudioIntegrityValidator.isValidAudioFile(targetFile, checkMetadata = false)) {
                                Log.i(TAG, "Track $trackId successfully downloaded via VPS backend.")
                                emit(Result.success(targetFile))
                                return@withLock
                            } else {
                                emit(Result.failure(Exception("Le fichier téléchargé via le serveur n'a pas pu être validé.")))
                                return@withLock
                            }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            emit(Result.failure(Exception("Délai d'attente du téléchargement serveur dépassé (120s).")))
                            return@withLock
                        }
                    } else {
                        emit(Result.failure(Exception("Erreur serveur lors du téléchargement: HTTP $cloudHttpStatus")))
                        return@withLock
                    }
                }

                Log.i(TAG, "Saved cloud file to ${targetFile.absolutePath} (${targetFile.length()} bytes)")

                // 1. Resolve online cover if needed
                var resolvedCoverUri = coverUri
                if (resolvedCoverUri.isNullOrBlank() || !resolvedCoverUri.startsWith("http")) {
                    try {
                        val query = "${title ?: ""} ${artistName ?: ""}".trim()
                        if (query.isNotEmpty()) {
                            val searchResult = apiService.search(query, limitTracks = 3)
                            val resolved = searchResult.data?.tracks?.firstOrNull { it.coverUri?.startsWith("http") == true }?.coverUri
                            if (resolved != null) {
                                resolvedCoverUri = resolved
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve cover online for cloud track $trackId", e)
                    }
                }

                // 2. Download cover to local covers cache for offline-first use
                var localCoverUri: String? = null
                if (resolvedCoverUri != null && resolvedCoverUri.startsWith("http")) {
                    val client = HttpClient()
                    try {
                        val imageResponse = client.get(resolvedCoverUri)
                        if (imageResponse.status.value in 200..299) {
                            val imageBytes = imageResponse.body<ByteArray>()
                            val coversDir = File(context.filesDir, "covers")
                            if (!coversDir.exists()) {
                                coversDir.mkdirs()
                            }
                            val coverFile = File(coversDir, "${trackId.replace(':', ';')}.jpg")
                            FileOutputStream(coverFile).use { fos ->
                                fos.write(imageBytes)
                            }
                            localCoverUri = Uri.fromFile(coverFile).toString()
                            Log.i(TAG, "Downloaded remote cover from $resolvedCoverUri for $trackId to $localCoverUri")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to download remote cover fallback for $trackId", e)
                    } finally {
                        client.close()
                    }
                }

                // Link in local DB
                linkDownloadedTrack(
                    trackId = trackId,
                    targetFile = targetFile,
                    title = title,
                    artistName = artistName,
                    albumTitle = albumTitle,
                    durationMs = durationMs,
                    artistId = artistId,
                    albumId = albumId,
                    coverUri = localCoverUri ?: resolvedCoverUri ?: coverUri
                )

                emit(Result.success(targetFile))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "Failed to download track $trackId", e)
                emit(Result.failure(e))
            } finally {
                pendingFile?.delete()
                _networkTransfers.update { it - trackId }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun linkDownloadedTrack(
        trackId: String,
        targetFile: File,
        title: String? = null,
        artistName: String? = null,
        albumTitle: String? = null,
        durationMs: Long? = null,
        artistId: String? = null,
        albumId: String? = null,
        coverUri: String? = null
    ) {
        mediaCoordinator.withTrack(trackId) {
            check(AudioIntegrityValidator.isValidAudioFile(targetFile, checkMetadata = false))
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                linkDownloadedTrackLocked(trackId, targetFile, title, artistName, albumTitle, durationMs,
                    artistId, albumId, coverUri)
            }
        }
    }

    private suspend fun linkDownloadedTrackLocked(
        trackId: String,
        targetFile: File,
        title: String? = null,
        artistName: String? = null,
        albumTitle: String? = null,
        durationMs: Long? = null,
        artistId: String? = null,
        albumId: String? = null,
        coverUri: String? = null
    ) {
        val now = System.currentTimeMillis()
        val fileUri = Uri.fromFile(targetFile).toString()

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                var rawTrack = database.trackDao().getRawTrackById(trackId)
                if (rawTrack == null) {
                    var fileTitle = title ?: "Piste Cloud $trackId"
                    var artist = artistName ?: "Artiste Inconnu"
                    var album = albumTitle ?: "Album Inconnu"
                    var duration = durationMs ?: 0L

                    if (title == null || artistName == null || albumTitle == null || durationMs == null) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(targetFile.absolutePath)
                            fileTitle = title ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileTitle
                            artist = artistName ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
                            album = albumTitle ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
                            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            duration = durationMs ?: durationStr?.toLongOrNull() ?: duration
                        } catch (retrieverEx: Exception) {
                            Log.w(TAG, "Could not extract ID3 metadata from $trackId", retrieverEx)
                        } finally {
                            try {
                                retriever.release()
                            } catch (e: Exception) {}
                        }
                    }

                    if (artistId != null) {
                        val placeholderArtist = com.aura.music.data.local.ArtistEntity(
                            id = artistId,
                            name = artist,
                            normalizedName = artist.lowercase().trim(),
                            pictureUri = null,
                            artworkOrigin = null,
                            artworkLastResolvedAt = null,
                            summary = null,
                            createdAt = now,
                            updatedAt = now
                        )
                        database.artistDao().insertArtistsIgnore(listOf(placeholderArtist))
                        Log.d(TAG, "Created placeholder ArtistEntity for constraint: $artistId")
                    }

                    if (albumId != null) {
                        val placeholderAlbum = com.aura.music.data.local.AlbumEntity(
                            id = albumId,
                            primaryArtistId = artistId,
                            title = album,
                            normalizedTitle = album.lowercase().trim(),
                            coverUri = coverUri,
                            artworkOrigin = null,
                            artworkLastResolvedAt = null,
                            releaseDate = null,
                            trackCount = null,
                            createdAt = now,
                            updatedAt = now
                        )
                        database.albumDao().insertAlbumsIgnore(listOf(placeholderAlbum))
                        Log.d(TAG, "Created placeholder AlbumEntity for constraint: $albumId")
                    }

                    val newTrack = com.aura.music.data.local.TrackEntity(
                        id = trackId,
                        primaryArtistId = artistId,
                        albumId = albumId,
                        title = fileTitle,
                        normalizedTitle = fileTitle.lowercase().trim(),
                        displayArtistName = artist,
                        displayAlbumTitle = album,
                        durationMs = duration,
                        coverUri = coverUri,
                        canonicalAudioSourceType = "downloaded",
                        isLiked = false,
                        isDownloadedByAura = true,
                        isExplicit = false,
                        popularity = 0,
                        genresJson = null,
                        createdAt = now,
                        updatedAt = now
                    )
                    database.trackDao().upsertTrack(newTrack)
                    Log.d(TAG, "Dynamically reconstructed and saved TrackEntity for deleted/missing track $trackId")
                } else {
                    val updatedTrack = rawTrack.copy(
                        canonicalAudioSourceType = "downloaded",
                        isDownloadedByAura = true,
                        coverUri = coverUri ?: rawTrack.coverUri,
                        updatedAt = now
                    )
                    database.trackDao().upsertTrack(updatedTrack)
                    Log.d(TAG, "Updated local TrackEntity $trackId to downloaded state")
                }

                val mediaLink = MediaLinkResolver.resolveOrCreateMediaLink(
                    database = database,
                    trackId = trackId,
                    contentUri = fileUri,
                    fileSizeBytes = targetFile.length(),
                    mimeType = "audio/mpeg",
                    now = now
                )
                database.trackDao().upsertTrackMediaLinks(listOf(mediaLink))
                Log.d(TAG, "Persisted media link for track $trackId (id=${mediaLink.id}, mediaStoreId=${mediaLink.mediaStoreId})")
            }
        }
    }

    /**
     * Retrieves all files synchronized on the cloud server.
     */
    fun listCloudFiles(forceRefresh: Boolean = false): Flow<Result<List<SyncedFileResponseData>>> = flow {
        emit(getOrFetchCloudFiles(forceRefresh = forceRefresh))
    }.flowOn(Dispatchers.IO)

    /**
     * Checks if a track is missing locally but present in the cloud list.
     */
    suspend fun isTrackCloudOnly(trackId: String, cloudFiles: List<SyncedFileResponseData>): Boolean = withContext(Dispatchers.IO) {
        val inCloud = cloudFiles.any { it.trackId == trackId }
        if (!inCloud) return@withContext false
        val trackRow = database.trackDao().getTrackById(trackId)
        return@withContext trackRow == null || trackRow.contentUri.isNullOrBlank()
    }

    /**
     * Deletes a file from the cloud server.
     */
    fun deleteSyncFile(trackId: String): Flow<Result<Unit>> = flow {
        try {
            val requestScope = captureAuthScope()
            Log.i(TAG, "Deleting track $trackId from cloud...")
            val response = apiService.deleteSyncFile(getAuthToken(), trackId)
            val data = response.data
            if (response.error != null || data == null || !data.deleted) {
                val errorMsg = response.error?.message ?: "Erreur de suppression du fichier cloud"
                emit(Result.failure(Exception(errorMsg)))
                return@flow
            }
            Log.i(TAG, "Successfully deleted track $trackId from cloud")
            synchronized(refreshLock) {
                if (isScopeStillValid(requestScope)) {
                    syncCacheScopeLocked(requestScope)
                    _syncedTrackIds.value = _syncedTrackIds.value - trackId
                    _cachedCloudFiles.value = _cachedCloudFiles.value.filterNot { it.trackId == trackId }
                } else {
                    ensureScopeCurrent()
                }
            }

            if (!isScopeStillValid(requestScope)) {
                emit(Result.success(Unit))
                return@flow
            }

            reconcileMutex.withLock {
                if (!isScopeStillValid(requestScope)) return@withLock
                withContext(NonCancellable) {
                    database.useWriterConnection { transactor ->
                        transactor.immediateTransaction {
                            if (!isScopeStillValid(requestScope)) return@immediateTransaction
                            val rawTrack = database.trackDao().getRawTrackById(trackId)
                            val contentUri = database.trackDao().getTrackContentUri(trackId)
                            val inPlaylist = try {
                                database.playlistDao().getAllPlaylistTrackIds().contains(trackId)
                            } catch (e: Exception) {
                                false
                            }
                            val isActiveJob = try {
                                database.downloadJobDao().getActiveJobs().any { it.trackId == trackId }
                            } catch (e: Exception) {
                                false
                            }
                            if (rawTrack != null &&
                                !rawTrack.isLiked &&
                                !rawTrack.isDownloadedByAura &&
                                rawTrack.canonicalAudioSourceType != "downloaded" &&
                                rawTrack.canonicalAudioSourceType != "local" &&
                                !inPlaylist &&
                                !isActiveJob &&
                                contentUri.isNullOrBlank()
                            ) {
                                database.trackDao().deleteTracksByIds(listOf(trackId))
                                Log.i(TAG, "Deleted unliked cloud-only track $trackId from Room database")
                            }
                        }
                    }
                }
            }

            emit(Result.success(Unit))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete cloud file $trackId", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Retrieves all local tracks stored in the database.
     */
    suspend fun getLocalTracks(): List<com.aura.music.data.local.TrackListRow> = withContext(Dispatchers.IO) {
        database.trackDao().getDownloadedTracks()
    }

    suspend fun getSettings(): com.aura.music.data.local.UserSettingsEntity? = withContext(Dispatchers.IO) {
        database.userSettingsDao().getSettings()
    }

    suspend fun updateSyncEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateSyncEnabled(enabled)
    }

    /**
     * Répare et synchronise toutes les métadonnées locales vers le serveur Cloud.
     * Pour tous les morceaux du Cloud dont le titre ou l'artiste est inconnu ou qui correspondent
     * à un morceau local du téléphone, pousse les vraies métadonnées locales vers le serveur.
     */
    fun repairAndSyncAllCloudMetadata(): Flow<Result<Int>> = flow {
        try {
            Log.i(TAG, "Starting Cloud metadata repair and synchronization from local phone...")
            val localTracks = database.trackDao().getAllTracks()
            val cloudFilesResponse = apiService.listSyncFiles(getAuthToken())
            val cloudItems = cloudFilesResponse.data?.items ?: emptyList()

            if (cloudItems.isEmpty()) {
                emit(Result.success(0))
                return@flow
            }

            val updates = mutableListOf<com.aura.music.data.network.SyncedFileMetadataUpdateRequest>()

            for (cloudItem in cloudItems) {
                val cloudDur = cloudItem.durationMs
                // Trouver la correspondance locale
                val matchingLocal = localTracks.firstOrNull { local ->
                    val localDur = local.durationMs
                    local.id == cloudItem.trackId ||
                    (local.title.equals(cloudItem.title, ignoreCase = true) && local.artistName.equals(cloudItem.artistName, ignoreCase = true)) ||
                    (cloudItem.title == null && localDur != null && cloudDur != null && Math.abs(localDur - cloudDur) < 2000L)
                }

                if (matchingLocal != null) {
                    val resolvedTitle = matchingLocal.title
                    val resolvedArtist = matchingLocal.artistName ?: "Artiste Inconnu"
                    val resolvedAlbum = matchingLocal.albumTitle
                    val resolvedDuration = matchingLocal.durationMs ?: cloudItem.durationMs
                    val resolvedCover = matchingLocal.coverUri ?: cloudItem.coverUri

                    val needsUpdate = cloudItem.title.isNullOrBlank() ||
                            cloudItem.title?.startsWith("Piste ", ignoreCase = true) == true ||
                            cloudItem.title.equals("Titre inconnu", ignoreCase = true) ||
                            cloudItem.artistName.isNullOrBlank() ||
                            cloudItem.artistName.equals("Artiste Inconnu", ignoreCase = true) ||
                            cloudItem.durationMs == null || cloudItem.durationMs == 0L ||
                            (cloudItem.coverUri.isNullOrBlank() && !resolvedCover.isNullOrBlank())

                    if (needsUpdate) {
                        updates.add(
                            com.aura.music.data.network.SyncedFileMetadataUpdateRequest(
                                trackId = cloudItem.trackId,
                                title = resolvedTitle,
                                artistName = resolvedArtist,
                                albumTitle = resolvedAlbum,
                                durationMs = resolvedDuration,
                                artistId = matchingLocal.artistId,
                                albumId = matchingLocal.albumId,
                                coverUri = resolvedCover
                            )
                        )
                    }
                }
            }

            if (updates.isNotEmpty()) {
                Log.i(TAG, "Pushing ${updates.size} metadata fixes to cloud server...")
                apiService.batchUpdateSyncFilesMetadata(getAuthToken(), updates)
                refreshSyncedTrackIds()
            }

            emit(Result.success(updates.size))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to repair cloud metadata", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun removeLocalFile(trackId: String) = withContext(Dispatchers.IO) {
        mediaCoordinator.withTrack(trackId) {
            try {
                val trackRow = database.trackDao().getTrackById(trackId)
                val uriStr = trackRow?.contentUri

                // 1. Suppression physique du fichier s'il existe sur le disque
                if (!uriStr.isNullOrBlank()) {
                    try {
                        when {
                            uriStr.startsWith("file:") -> {
                                val path = try { java.net.URI.create(uriStr).path } catch (e: Exception) { uriStr.removePrefix("file://").removePrefix("file:") }
                                val file = File(path)
                                if (file.exists()) file.delete()
                            }
                            uriStr.startsWith("/") -> {
                                val file = File(uriStr)
                                if (file.exists()) file.delete()
                            }
                            uriStr.startsWith("content://") -> {
                                try {
                                    context.contentResolver.delete(Uri.parse(uriStr), null, null)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not delete content URI via resolver: $uriStr", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error deleting file from disk: $uriStr", e)
                    }
                }

                // Supprimer également du dossier downloads si présent
                val downloadsDir = File(context.filesDir, "downloads")
                val localDownloadFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")
                if (localDownloadFile.exists()) {
                    localDownloadFile.delete()
                }

                // 2. Nettoyage de la base de données Room
                database.useWriterConnection { transactor ->
                    transactor.immediateTransaction {
                        database.trackDao().deleteTrackMediaLinksByTrackId(trackId)

                        val rawTrack = database.trackDao().getRawTrackById(trackId)
                        if (rawTrack != null && !rawTrack.isLiked) {
                            val inPlaylist = try {
                                database.playlistDao().getAllPlaylistTrackIds().contains(trackId)
                            } catch (e: Exception) {
                                false
                            }
                            if (!inPlaylist) {
                                database.trackDao().deleteTracksByIds(listOf(trackId))
                            }
                        }
                    }
                }
                Log.i(TAG, "Successfully removed local file and links for track $trackId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove local file for track $trackId", e)
            }
    }
    }
}
