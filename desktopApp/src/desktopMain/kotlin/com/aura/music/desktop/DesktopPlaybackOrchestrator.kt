package com.aura.music.desktop

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.data.local.*
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.TrackSummary
import com.aura.music.data.player.QueueManager
import com.aura.music.desktop.domain.DesktopCloudSyncManager
import com.aura.music.desktop.domain.DesktopDownloadManager
import com.aura.music.desktop.domain.DesktopPlaylistManager
import com.aura.music.domain.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Orchestrateur de lecture audio pour le client Desktop AURA.
 * Recentré exclusivement sur le moteur audio (DesktopAudioPlayer), la gestion
 * de file d'attente (QueueManager), les snapshots LWW debouncés et le streaming à la demande.
 */
class DesktopPlaybackOrchestrator(
    val database: AuraDatabase,
    val audioPlayer: DesktopAudioPlayer,
    val queueManager: QueueManager,
    val apiService: AuraApiService,
    var playlistManager: DesktopPlaylistManager? = null,
    var cloudSyncManager: DesktopCloudSyncManager? = null,
    var downloadManager: DesktopDownloadManager? = null
) {
    var apiToken: String?
        get() = cloudSyncManager?.apiToken
        set(value) {
            cloudSyncManager?.apiToken = value
            downloadManager?.apiToken = value
        }

    var autoSyncEnabled: Boolean
        get() = cloudSyncManager?.autoSyncEnabled ?: true
        set(value) {
            cloudSyncManager?.autoSyncEnabled = value
        }

    var isWindowVisible: Boolean = true

    @OptIn(ExperimentalCoroutinesApi::class)
    val loomDispatcher = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null
    private var snapshotDebounceJob: Job? = null

    fun connect(onDataChanged: (() -> Unit)? = null) {
        audioPlayer.onCompletionListener = {
            scope.launch(Dispatchers.Default) {
                next()
            }
        }

        audioPlayer.onErrorListener = { error ->
            _uiState.update { it.copy(playbackState = PlaybackState.Error, errorMessage = error) }
        }

        // Rafraîchissement périodique de la timeline (mis en pause si la fenêtre est masquée)
        progressJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (isWindowVisible && audioPlayer.isPlaying()) {
                    val pos = audioPlayer.getCurrentPosition()
                    val dur = audioPlayer.getDuration()
                    _uiState.update { it.copy(positionMs = pos, durationMs = dur) }
                }
                delay(250)
            }
        }

        // Restauration de session LWW
        scope.launch {
            try {
                restoreSnapshot()
            } catch (e: Exception) {
                System.err.println("Failed to restore playback snapshot: ${e.message}")
            }
        }

        // Démarrage des boucles de fond via les managers dédiés
        downloadManager?.startLoop()
        cloudSyncManager?.startLoop()

        // Hydratation en arrière-plan des stubs de pistes distantes
        scope.launch(Dispatchers.IO) {
            try {
                DesktopTrackHydrator.hydrateTrackStubs(database)
                withContext(Dispatchers.Main) {
                    onDataChanged?.invoke()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        val token = apiToken
        if (!token.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                cloudSyncManager?.performCloudSync(token) {
                    onDataChanged?.invoke()
                }
            }
        }
    }

    fun disconnect() {
        progressJob?.cancel()
        snapshotDebounceJob?.cancel()
        audioPlayer.stop()
        downloadManager?.stopLoop()
        cloudSyncManager?.stopLoop()
    }

    // =======================================================================
    // CONTRÔLES DE LECTURE
    // =======================================================================

    fun playTrack(
        trackId: String,
        contextType: String,
        contextId: String,
        contextTracks: List<QueuedTrack>,
        startIndex: Int
    ) {
        queueManager.setContext(
            type = contextType,
            id = contextId,
            tracks = contextTracks,
            startIndex = startIndex
        )
        val track = queueManager.state.value.currentTrack ?: return

        if (track.contentUri == null) {
            // Piste sur le cloud : streaming à la demande
            scope.launch(Dispatchers.IO) {
                val token = apiToken ?: return@launch
                try {
                    cloudSyncManager?.downloadCloudTrack(
                        token = token,
                        trackId = track.trackId,
                        title = track.title,
                        artistName = track.artistName,
                        albumTitle = track.albumTitle,
                        durationMs = track.durationMs ?: 0L,
                        coverUri = track.coverUri
                    )
                    val localUri = database.trackDao().getTrackContentUri(track.trackId)
                    if (localUri != null) {
                        withContext(Dispatchers.Main) {
                            audioPlayer.play(localUri)
                            scheduleDebouncedSnapshotSave()
                            syncUiState(PlaybackState.Playing)
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("Failed on-demand cloud stream for ${track.trackId}: ${e.message}")
                }
            }
            return
        }

        val uri = track.contentUri ?: return
        audioPlayer.play(uri)
        scheduleDebouncedSnapshotSave()
        syncUiState(PlaybackState.Playing)
    }

    fun playTrackDirectly(track: QueuedTrack) {
        val uri = track.contentUri
        if (!uri.isNullOrBlank()) {
            audioPlayer.play(uri)
            syncUiState(PlaybackState.Playing)
            scheduleDebouncedSnapshotSave()
        }
    }

    fun playOnlineTrack(
        track: TrackSummary,
        contextTracks: List<TrackSummary>
    ) {
        val queuedTracks = contextTracks.map { t ->
            QueuedTrack(
                trackId = t.id,
                title = t.title,
                artistName = t.displayArtistName,
                albumTitle = t.displayAlbumTitle,
                contentUri = null,
                durationMs = t.durationMs.toLong(),
                coverUri = t.coverUri,
                source = TrackSource.CONTEXT
            )
        }

        val startIndex = contextTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        queueManager.setContext(
            type = "online_search",
            id = "search_${track.id}",
            tracks = queuedTracks,
            startIndex = startIndex
        )

        scope.launch(Dispatchers.IO) {
            val token = apiToken ?: return@launch
            try {
                cloudSyncManager?.downloadCloudTrack(
                    token = token,
                    trackId = track.id,
                    title = track.title,
                    artistName = track.displayArtistName,
                    albumTitle = track.displayAlbumTitle,
                    durationMs = track.durationMs.toLong(),
                    coverUri = track.coverUri
                )
                val localUri = database.trackDao().getTrackContentUri(track.id)
                if (localUri != null) {
                    withContext(Dispatchers.Main) {
                        audioPlayer.play(localUri)
                        scheduleDebouncedSnapshotSave()
                        syncUiState(PlaybackState.Playing)
                    }
                }
            } catch (e: Exception) {
                System.err.println("Failed to stream online search track ${track.id}: ${e.message}")
            }
        }
    }

    fun togglePlayPause() {
        val currentTrack = queueManager.state.value.currentTrack ?: return
        if (audioPlayer.isPlaying()) {
            audioPlayer.pause()
            syncUiState(PlaybackState.Paused)
            scheduleDebouncedSnapshotSave()
        } else {
            val currentPos = _uiState.value.positionMs
            if (currentTrack.contentUri != null) {
                audioPlayer.play(currentTrack.contentUri!!)
                if (currentPos > 0) {
                    audioPlayer.seekTo(currentPos)
                }
                syncUiState(PlaybackState.Playing)
                scheduleDebouncedSnapshotSave()
            } else {
                scope.launch(Dispatchers.IO) {
                    val token = apiToken ?: return@launch
                    try {
                        cloudSyncManager?.downloadCloudTrack(
                            token = token,
                            trackId = currentTrack.trackId,
                            title = currentTrack.title,
                            artistName = currentTrack.artistName,
                            albumTitle = currentTrack.albumTitle,
                            durationMs = currentTrack.durationMs ?: 0L,
                            coverUri = currentTrack.coverUri
                        )
                        val localUri = database.trackDao().getTrackContentUri(currentTrack.trackId)
                        if (localUri != null) {
                            withContext(Dispatchers.Main) {
                                audioPlayer.play(localUri)
                                if (currentPos > 0) {
                                    audioPlayer.seekTo(currentPos)
                                }
                                scheduleDebouncedSnapshotSave()
                                syncUiState(PlaybackState.Playing)
                            }
                        }
                    } catch (e: Exception) {
                        System.err.println("Failed on-demand cloud stream for ${currentTrack.trackId}: ${e.message}")
                    }
                }
            }
        }
    }

    fun pause() {
        if (audioPlayer.isPlaying()) {
            audioPlayer.pause()
            syncUiState(PlaybackState.Paused)
            scheduleDebouncedSnapshotSave()
        }
    }

    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
        _uiState.update { it.copy(positionMs = positionMs) }
        scheduleDebouncedSnapshotSave()
    }

    fun setVolume(volume: Float) {
        audioPlayer.setVolume(volume)
    }

    fun next() {
        val nextTrack = queueManager.next()
        if (nextTrack != null && nextTrack.contentUri != null) {
            audioPlayer.play(nextTrack.contentUri!!)
            syncUiState(PlaybackState.Playing)
        } else {
            audioPlayer.stop()
            syncUiState(PlaybackState.Idle)
        }
        scheduleDebouncedSnapshotSave()
    }

    fun previous() {
        val currentPos = audioPlayer.getCurrentPosition()
        val prevTrack = queueManager.previous(currentPos)
        if (prevTrack != null && prevTrack.contentUri != null) {
            if (currentPos > 3000L) {
                audioPlayer.seekTo(0)
            } else {
                audioPlayer.play(prevTrack.contentUri!!)
            }
            syncUiState(PlaybackState.Playing)
        } else {
            audioPlayer.seekTo(0)
            syncUiState(PlaybackState.Playing)
        }
        scheduleDebouncedSnapshotSave()
    }

    fun toggleShuffle() {
        queueManager.toggleShuffle()
        syncUiState(if (audioPlayer.isPlaying()) PlaybackState.Playing else PlaybackState.Paused)
        scheduleDebouncedSnapshotSave()
    }

    fun cycleRepeatMode() {
        queueManager.cycleRepeatMode()
        syncUiState(if (audioPlayer.isPlaying()) PlaybackState.Playing else PlaybackState.Paused)
        scheduleDebouncedSnapshotSave()
    }

    fun toggleRepeat() {
        cycleRepeatMode()
    }

    fun addToQueue(track: QueuedTrack) {
        queueManager.addToQueue(track)
        syncUiState(if (audioPlayer.isPlaying()) PlaybackState.Playing else PlaybackState.Paused)
    }

    fun removeFromQueue(index: Int) {
        queueManager.removeFromQueue(index)
        syncUiState(if (audioPlayer.isPlaying()) PlaybackState.Playing else PlaybackState.Paused)
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        queueManager.reorderQueue(fromIndex, toIndex)
        syncUiState(if (audioPlayer.isPlaying()) PlaybackState.Playing else PlaybackState.Paused)
    }

    fun removeFromMainQueue(internalId: String) {
        queueManager.removeUpcomingContextTrack(internalId)
        syncUiState(if (audioPlayer.isPlaying()) PlaybackState.Playing else PlaybackState.Paused)
    }

    fun reorderMainQueue(fromInternalId: String, toInternalId: String) {
        queueManager.reorderUpcomingContextTrack(fromInternalId, toInternalId)
        syncUiState(if (audioPlayer.isPlaying()) PlaybackState.Playing else PlaybackState.Paused)
    }

    fun toQueuedTrack(row: TrackListRow): QueuedTrack {
        return QueuedTrack(
            trackId = row.id,
            title = row.title,
            artistName = row.artistName,
            albumTitle = row.albumTitle,
            contentUri = row.contentUri,
            durationMs = row.durationMs,
            coverUri = row.coverUri,
            source = TrackSource.CONTEXT,
            internalId = UUID.randomUUID().toString()
        )
    }

    // =======================================================================
    // ATOMIC LIKES & OUTBOX (RÈGLE #3)
    // =======================================================================

    fun toggleLike(trackId: String, onComplete: (() -> Unit)? = null) {
        scope.launch {
            val trackRow = database.trackDao().getTrackById(trackId) ?: return@launch
            val currentlyLiked = trackRow.isLiked
            val now = System.currentTimeMillis()
            val opId = "outbox_like_${UUID.randomUUID().toString().take(12)}"

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    if (currentlyLiked) {
                        database.trackLikeDao().deleteLike(trackId)
                        database.trackLikeDao().setTrackIsLiked(trackId, liked = false, updatedAt = now)
                        database.syncOutboxDao().insert(
                            SyncOutboxEntity(
                                id = opId,
                                entityType = "track_like",
                                entityId = trackId,
                                operationType = "delete",
                                payloadJson = "",
                                status = "pending",
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    } else {
                        database.trackLikeDao().insertLike(
                            TrackLikeEntity(
                                trackId = trackId,
                                likedAt = now,
                                sourceContextType = _uiState.value.contextType,
                                sourceContextId = _uiState.value.contextId,
                            )
                        )
                        database.trackLikeDao().setTrackIsLiked(trackId, liked = true, updatedAt = now)
                        database.syncOutboxDao().insert(
                            SyncOutboxEntity(
                                id = opId,
                                entityType = "track_like",
                                entityId = trackId,
                                operationType = "set",
                                payloadJson = "",
                                status = "pending",
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }
                }
            }

            val currentTrack = _uiState.value.currentTrack
            if (currentTrack != null && currentTrack.trackId == trackId) {
                _uiState.update { it.copy(isCurrentTrackLiked = !currentlyLiked) }
            }

            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }

            cloudSyncManager?.triggerFlush()
        }
    }

    // =======================================================================
    // SNAPSHOT DE LECTURE (DEBOUNCED 1500ms - RÈGLE #5)
    // =======================================================================

    private fun scheduleDebouncedSnapshotSave() {
        snapshotDebounceJob?.cancel()
        snapshotDebounceJob = scope.launch(Dispatchers.IO) {
            delay(1500)
            saveSnapshot()
        }
    }

    fun saveSnapshot() {
        scope.launch(Dispatchers.IO) {
            val state = queueManager.state.value
            val currentPos = audioPlayer.getCurrentPosition()
            database.playbackSnapshotDao().upsert(
                PlaybackSnapshotEntity(
                    id = "active",
                    currentTrackId = state.currentTrack?.trackId,
                    playbackContextType = state.context?.type,
                    playbackContextId = state.context?.id,
                    playbackContextIndex = state.context?.currentIndex,
                    positionMs = currentPos,
                    shuffleEnabled = state.shuffleEnabled,
                    repeatMode = state.repeatMode.name.lowercase(),
                    updatedAt = System.currentTimeMillis()
                )
            )

            val token = apiToken
            if (token != null) {
                try {
                    apiService.updatePlaybackSnapshot(
                        token = token,
                        snapshot = com.aura.music.data.network.PlaybackSnapshotResponse(
                            currentTrackId = state.currentTrack?.trackId,
                            playbackContextType = state.context?.type,
                            playbackContextId = state.context?.id,
                            playbackContextIndex = state.context?.currentIndex,
                            positionMs = currentPos,
                            shuffleEnabled = state.shuffleEnabled,
                            repeatMode = state.repeatMode.name.lowercase()
                        )
                    )
                } catch (e: Exception) {
                    System.err.println("Failed to sync playback snapshot to backend: ${e.message}")
                }
            }
        }
    }

    private suspend fun restoreSnapshot() {
        val localEntity = database.playbackSnapshotDao().getActiveSnapshot()
        var targetTrackId = localEntity?.currentTrackId
        var targetContextType = localEntity?.playbackContextType
        var targetContextId = localEntity?.playbackContextId
        var targetPositionMs = localEntity?.positionMs ?: 0L
        var targetShuffle = localEntity?.shuffleEnabled ?: false
        var targetRepeatStr = localEntity?.repeatMode ?: "off"

        val token = apiToken
        if (!token.isNullOrBlank()) {
            try {
                val cloudResp = apiService.getPlaybackSnapshot(token)
                val cloudSnap = cloudResp.data
                if (cloudSnap != null && cloudSnap.currentTrackId != null) {
                    val localUpdatedAt = localEntity?.updatedAt ?: 0L
                    val cloudEpochMs = try {
                        cloudSnap.updatedAt?.let { java.time.Instant.parse(it).toEpochMilli() } ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                    if (localEntity == null || cloudEpochMs >= localUpdatedAt) {
                        targetTrackId = cloudSnap.currentTrackId
                        targetContextType = cloudSnap.playbackContextType
                        targetContextId = cloudSnap.playbackContextId
                        targetPositionMs = cloudSnap.positionMs
                        targetShuffle = cloudSnap.shuffleEnabled
                        targetRepeatStr = cloudSnap.repeatMode
                    }
                }
            } catch (e: Exception) {
                System.err.println("Could not fetch remote playback snapshot: ${e.message}")
            }
        }

        val trackId = targetTrackId ?: return
        val trackRow = database.trackDao().getTrackById(trackId)
        val rawTrack = if (trackRow == null) database.trackDao().getRawTrackById(trackId) else null
        if (trackRow == null && rawTrack == null) {
            return
        }

        val trackTitle = trackRow?.title ?: rawTrack?.title ?: "Piste $trackId"
        val artistName = trackRow?.artistName ?: rawTrack?.displayArtistName ?: "Artiste inconnu"
        val albumTitle = trackRow?.albumTitle ?: rawTrack?.displayAlbumTitle
        val contentUri = trackRow?.contentUri ?: (if (rawTrack != null) database.trackDao().getTrackContentUri(rawTrack.id) else null)
        val durationMs = trackRow?.durationMs ?: rawTrack?.durationMs ?: 0L
        val coverUri = trackRow?.coverUri ?: rawTrack?.coverUri
        val isLiked = trackRow?.isLiked ?: rawTrack?.isLiked ?: false

        val queuedTrack = QueuedTrack(
            trackId = trackId,
            title = trackTitle,
            artistName = artistName,
            albumTitle = albumTitle,
            contentUri = contentUri,
            durationMs = durationMs,
            coverUri = coverUri,
            source = TrackSource.CONTEXT
        )

        val contextType = targetContextType ?: "single_track"
        val contextId = targetContextId ?: trackId

        val contextTracks = reloadContextTracks(contextType, contextId)
        var startIndex = contextTracks.indexOfFirst { it.trackId == trackId }
        if (startIndex == -1) startIndex = 0

        val repeat = when (targetRepeatStr) {
            "one" -> RepeatMode.One
            "all" -> RepeatMode.All
            else -> RepeatMode.Off
        }

        queueManager.restoreModes(targetShuffle, repeat)
        queueManager.setContext(
            type = contextType,
            id = contextId,
            tracks = contextTracks.ifEmpty { listOf(queuedTrack) },
            startIndex = startIndex
        )

        val currentTrack = queueManager.state.value.currentTrack
        _uiState.update { current ->
            current.copy(
                playbackState = PlaybackState.Paused,
                currentTrack = currentTrack,
                positionMs = targetPositionMs,
                durationMs = durationMs,
                shuffleEnabled = targetShuffle,
                repeatMode = repeat,
                priorityQueue = queueManager.state.value.priorityQueue,
                mainQueueTracks = queueManager.getUpcomingContextTracks(),
                contextType = contextType,
                contextId = contextId,
                isCurrentTrackLiked = isLiked
            )
        }

        if (contentUri != null) {
            audioPlayer.play(contentUri)
            audioPlayer.pause()
            audioPlayer.seekTo(targetPositionMs)
        }
    }

    private suspend fun reloadContextTracks(type: String, id: String): List<QueuedTrack> = withContext(Dispatchers.IO) {
        return@withContext when (type) {
            "library_tracks" -> database.trackDao().getAllTracks().map { toQueuedTrack(it) }
            "favorites" -> database.trackDao().getLikedTracks().map { toQueuedTrack(it) }
            "playlist" -> database.playlistDao().getPlaylistTracks(id).map { row ->
                QueuedTrack(
                    trackId = row.trackId,
                    title = row.title,
                    artistName = row.artistName,
                    albumTitle = row.albumTitle,
                    contentUri = row.contentUri,
                    durationMs = row.durationMs,
                    coverUri = row.coverUri,
                    source = TrackSource.CONTEXT
                )
            }
            "album" -> database.trackDao().getTracksForAlbum(id).map { toQueuedTrack(it) }
            "artist" -> database.trackDao().getTracksForArtist(id, 20).map { toQueuedTrack(it) }
            else -> emptyList()
        }
    }

    private fun syncUiState(state: PlaybackState) {
        val queueState = queueManager.state.value
        val track = queueState.currentTrack
        scope.launch {
            val isLiked = if (track != null) {
                database.trackDao().getTrackById(track.trackId)?.isLiked ?: false
            } else false

            _uiState.update { current ->
                current.copy(
                    playbackState = state,
                    currentTrack = track,
                    positionMs = audioPlayer.getCurrentPosition(),
                    durationMs = audioPlayer.getDuration(),
                    shuffleEnabled = queueState.shuffleEnabled,
                    repeatMode = queueState.repeatMode,
                    priorityQueue = queueState.priorityQueue,
                    mainQueueTracks = queueManager.getUpcomingContextTracks(),
                    contextType = queueState.context?.type,
                    contextId = queueState.context?.id,
                    isCurrentTrackLiked = isLiked
                )
            }
        }
    }

    // =======================================================================
    // SCAN DE DOSSIERS LOCAUX (VIRTUAL THREADS LOOM)
    // =======================================================================

    suspend fun scanDirectory(dir: File) = withContext(loomDispatcher) {
        scanLocalFolder(dir, {}, {})
    }

    fun scanLocalFolder(
        folder: File,
        onProgress: (String) -> Unit,
        onComplete: (Int) -> Unit
    ) {
        scope.launch(loomDispatcher) {
            try {
                val now = System.currentTimeMillis()
                val audioExtensions = setOf("mp3", "wav", "m4a", "flac", "aac")
                val files = folder.walkTopDown().filter {
                    it.isFile && it.extension.lowercase() in audioExtensions
                }.toList()

                var addedCount = 0
                for ((index, file) in files.withIndex()) {
                    onProgress("Indexation: ${index + 1}/${files.size} - ${file.name}")
                    val meta = DesktopMediaMetadataReader.readMetadata(file)

                    val trackId = "track_${file.absolutePath.hashCode()}"
                    val artistId = "artist_${meta.artist.hashCode()}"
                    val albumId = meta.album?.let { "album_${(meta.artist + "_" + it).hashCode()}" }

                    database.useWriterConnection { transactor ->
                        transactor.immediateTransaction {
                            // 1. Artist
                            database.artistDao().upsertArtists(
                                listOf(
                                    ArtistEntity(
                                        id = artistId,
                                        name = meta.artist,
                                        normalizedName = meta.artist.lowercase(),
                                        pictureUri = meta.localCoverUri,
                                        createdAt = now,
                                        updatedAt = now
                                    )
                                )
                            )

                            // 2. Album
                            if (albumId != null && meta.album != null) {
                                database.albumDao().upsertAlbums(
                                    listOf(
                                        AlbumEntity(
                                            id = albumId,
                                            primaryArtistId = artistId,
                                            title = meta.album!!,
                                            normalizedTitle = meta.album!!.lowercase(),
                                            coverUri = meta.localCoverUri,
                                            createdAt = now,
                                            updatedAt = now
                                        )
                                    )
                                )
                            }

                            // 3. Track
                            database.trackDao().upsertTracks(
                                listOf(
                                    TrackEntity(
                                        id = trackId,
                                        primaryArtistId = artistId,
                                        albumId = albumId,
                                        title = meta.title,
                                        normalizedTitle = meta.title.lowercase(),
                                        displayArtistName = meta.artist,
                                        displayAlbumTitle = meta.album,
                                        durationMs = meta.durationMs,
                                        coverUri = meta.localCoverUri,
                                        canonicalAudioSourceType = "local",
                                        isLiked = false,
                                        isDownloadedByAura = false,
                                        createdAt = file.lastModified(),
                                        updatedAt = file.lastModified()
                                    )
                                )
                            )

                            // 4. Media Link
                            database.trackDao().upsertTrackMediaLinks(
                                listOf(
                                    TrackMediaLinkEntity(
                                        id = "media_link_${file.absolutePath.hashCode()}",
                                        trackId = trackId,
                                        mediaStoreId = file.absolutePath.hashCode().toLong(),
                                        contentUri = file.toURI().toString(),
                                        fileSizeBytes = file.length(),
                                        mimeType = "audio/" + file.extension.lowercase(),
                                        dateModifiedEpochMs = file.lastModified(),
                                        availabilityStatus = "present",
                                        lastScannedAt = now
                                    )
                                )
                            )
                        }
                    }
                    addedCount++
                }
                onComplete(addedCount)
            } catch (e: Exception) {
                System.err.println("Error scanning local folder: ${e.message}")
                onComplete(0)
            }
        }
    }

    private fun parseBasicTags(file: File): Triple<String, String, String?> {
        var title = ""
        var artist = ""
        var album = ""

        if (file.extension.lowercase() == "mp3" && file.length() > 128) {
            try {
                file.inputStream().use { input ->
                    input.skip(file.length() - 128)
                    val buffer = ByteArray(128)
                    val read = input.read(buffer)
                    if (read == 128 && buffer[0] == 'T'.toByte() && buffer[1] == 'A'.toByte() && buffer[2] == 'G'.toByte()) {
                        title = String(buffer, 3, 30, StandardCharsets.ISO_8859_1).trim()
                        artist = String(buffer, 33, 30, StandardCharsets.ISO_8859_1).trim()
                        album = String(buffer, 63, 30, StandardCharsets.ISO_8859_1).trim()
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        title = title.replace("\u0000", "").trim()
        artist = artist.replace("\u0000", "").trim()
        album = album.replace("\u0000", "").trim()

        if (title.isEmpty() || artist.isEmpty()) {
            val nameWithoutExtension = file.nameWithoutExtension
            if (nameWithoutExtension.contains(" - ")) {
                val parts = nameWithoutExtension.split(" - ", limit = 2)
                artist = parts[0].trim()
                title = parts[1].trim()
            } else {
                artist = "Artiste Inconnu"
                title = nameWithoutExtension.trim()
            }
        }
        val displayAlbum = if (album.isBlank()) null else album
        return Triple(title, artist, displayAlbum)
    }

    // =======================================================================
    // DÉLÉGATIONS PRATIQUES POUR LES COMPOSANTS UI
    // =======================================================================

    suspend fun createPlaylist(name: String) = playlistManager?.createPlaylist(name) ?: ""
    suspend fun renamePlaylist(id: String, newName: String) = playlistManager?.renamePlaylist(id, newName)
    suspend fun deletePlaylist(id: String) = playlistManager?.deletePlaylist(id)
    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) = playlistManager?.addTrackToPlaylist(playlistId, trackId)
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) = playlistManager?.removeTrackFromPlaylist(playlistId, trackId)
    suspend fun deduplicatePlaylist(playlistId: String): Int = playlistManager?.deduplicatePlaylist(playlistId) ?: 0

    fun triggerTrackDownload(track: TrackSummary) {
        scope.launch(Dispatchers.IO) {
            downloadManager?.createDownload(track)
        }
    }
    fun clearCompletedDownloadJobs() {
        scope.launch(Dispatchers.IO) {
            downloadManager?.clearCompletedJobs()
        }
    }
    fun retryDownloadJob(jobId: String) {
        scope.launch(Dispatchers.IO) {
            downloadManager?.retryJob(jobId)
        }
    }
    fun cancelDownloadJob(jobId: String) {
        scope.launch(Dispatchers.IO) {
            downloadManager?.cancelJob(jobId)
        }
    }

    fun triggerSingleFileDownload(track: TrackListRow) {
        scope.launch(Dispatchers.IO) {
            val token = apiToken ?: return@launch
            cloudSyncManager?.downloadCloudTrack(
                token = token,
                trackId = track.id,
                title = track.title,
                artistName = track.artistName,
                albumTitle = track.albumTitle,
                durationMs = track.durationMs ?: 0L,
                coverUri = track.coverUri
            )
        }
    }

    fun triggerSingleFileUpload(track: TrackListRow) {
        scope.launch(Dispatchers.IO) {
            val token = apiToken ?: return@launch
            cloudSyncManager?.uploadCloudTrack(token, track.id)
        }
    }

    fun triggerCloudDownloadAll(tracks: List<TrackListRow>) {
        scope.launch(Dispatchers.IO) {
            val token = apiToken ?: return@launch
            tracks.forEach { trk ->
                cloudSyncManager?.downloadCloudTrack(
                    token = token,
                    trackId = trk.id,
                    title = trk.title,
                    artistName = trk.artistName,
                    albumTitle = trk.albumTitle,
                    durationMs = trk.durationMs ?: 0L,
                    coverUri = trk.coverUri
                )
            }
        }
    }

    fun triggerCloudUploadAll(tracks: List<TrackListRow>) {
        scope.launch(Dispatchers.IO) {
            val token = apiToken ?: return@launch
            tracks.forEach { trk ->
                cloudSyncManager?.uploadCloudTrack(token, trk.id)
            }
        }
    }

    suspend fun syncCloudData(token: String, onFinished: (() -> Unit)? = null) =
        cloudSyncManager?.performCloudSync(token, onFinished)

    suspend fun performCloudSync(token: String, onFinished: (() -> Unit)? = null) =
        cloudSyncManager?.performCloudSync(token, onFinished)

    suspend fun downloadCloudTrack(
        token: String,
        trackId: String,
        title: String,
        artistName: String,
        albumTitle: String?,
        durationMs: Long,
        coverUri: String?
    ) = cloudSyncManager?.downloadCloudTrack(token, trackId, title, artistName, albumTitle, durationMs, coverUri)

    suspend fun uploadCloudTrack(token: String, trackId: String) =
        cloudSyncManager?.uploadCloudTrack(token, trackId)

    suspend fun deleteCloudTrack(token: String, trackId: String) =
        cloudSyncManager?.deleteCloudTrack(token, trackId)
}

private data class ScannedTrackInfo(
    val file: File,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUri: String?
)
