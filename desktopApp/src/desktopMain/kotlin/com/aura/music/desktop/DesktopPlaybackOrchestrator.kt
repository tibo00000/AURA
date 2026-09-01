package com.aura.music.desktop

import androidx.room3.useWriterConnection
import androidx.room3.immediateTransaction
import com.aura.music.data.local.*
import com.aura.music.data.player.QueueManager
import com.aura.music.domain.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.asCoroutineDispatcher
import com.aura.music.data.network.AuraApiService
import com.aura.music.domain.search.SearchNormalizer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

class DesktopPlaybackOrchestrator(
    val database: AuraDatabase,
    val audioPlayer: DesktopAudioPlayer,
    val queueManager: QueueManager,
    val apiService: AuraApiService
) {
    var apiToken: String? = null
    var autoSyncEnabled: Boolean = true
    var isWindowVisible: Boolean = true

    @OptIn(ExperimentalCoroutinesApi::class)
    val loomDispatcher = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null

    fun connect(onDataChanged: (() -> Unit)? = null) {
        audioPlayer.onCompletionListener = {
            scope.launch(Dispatchers.Default) {
                next()
            }
        }

        audioPlayer.onErrorListener = { error ->
            _uiState.update { it.copy(playbackState = PlaybackState.Error, errorMessage = error) }
        }

        // Periodically refresh track progress slider in the UI (paused if window is hidden)
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

        // Restore last playback session on startup
        scope.launch {
            try {
                restoreSnapshot()
            } catch (e: Exception) {
                System.err.println("Failed to restore playback snapshot: ${e.message}")
            }
        }

        // Initial cloud data sync if token is available
        scope.launch(Dispatchers.IO) {
            val token = apiToken
            if (!token.isNullOrBlank()) {
                syncCloudData(token) {
                    onDataChanged?.invoke()
                }
            }
        }

        startDownloadSyncLoop()
        startCloudFileSyncLoop(onDataChanged)
    }

    fun disconnect() {
        progressJob?.cancel()
        audioPlayer.stop()
        stopDownloadSyncLoop()
        stopCloudFileSyncLoop()
    }

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
            // Track is on cloud: trigger on-demand download and play as soon as downloaded
            scope.launch(Dispatchers.IO) {
                val token = apiToken ?: return@launch
                try {
                    downloadCloudTrack(
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
                            saveSnapshot()
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
        saveSnapshot()
        syncUiState(PlaybackState.Playing)
    }

    fun togglePlayPause() {
        val currentTrack = queueManager.state.value.currentTrack ?: return
        if (audioPlayer.isPlaying()) {
            audioPlayer.pause()
            syncUiState(PlaybackState.Paused)
        } else {
            val currentPos = audioPlayer.getCurrentPosition()
            if (currentPos == 0L && currentTrack.contentUri != null) {
                audioPlayer.play(currentTrack.contentUri!!)
            } else {
                audioPlayer.play(currentTrack.contentUri ?: return)
                audioPlayer.seekTo(currentPos)
            }
            syncUiState(PlaybackState.Playing)
        }
        saveSnapshot()
    }

    fun pause() {
        if (audioPlayer.isPlaying()) {
            audioPlayer.pause()
            syncUiState(PlaybackState.Paused)
            saveSnapshot()
        }
    }

    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
        _uiState.update { it.copy(positionMs = positionMs) }
        saveSnapshot()
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
        saveSnapshot()
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
        saveSnapshot()
    }

    fun toggleShuffle() {
        queueManager.toggleShuffle()
        syncUiState(if (audioPlayer.isPlaying()) PlaybackState.Playing else PlaybackState.Paused)
        saveSnapshot()
    }

    fun cycleRepeatMode() {
        queueManager.cycleRepeatMode()
        syncUiState(if (audioPlayer.isPlaying()) PlaybackState.Playing else PlaybackState.Paused)
        saveSnapshot()
    }

    fun toggleRepeat() {
        cycleRepeatMode()
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

    fun playTrackDirectly(track: QueuedTrack) {
        val uri = track.contentUri
        if (!uri.isNullOrBlank()) {
            audioPlayer.play(uri)
            syncUiState(PlaybackState.Playing)
            saveSnapshot()
        }
    }

    suspend fun createPlaylist(name: String) = withContext(Dispatchers.IO) {
        val plId = "pl_desk_${UUID.randomUUID().toString().take(12)}"
        val now = System.currentTimeMillis()
        database.playlistDao().insertPlaylist(
            PlaylistEntity(
                id = plId,
                name = name,
                coverUri = null,
                isPinned = false,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun renamePlaylist(id: String, newName: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.playlistDao().renamePlaylist(id, newName, now)
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) = withContext(Dispatchers.IO) {
        val nextPos = database.playlistDao().getNextPlaylistPosition(playlistId)
        val now = System.currentTimeMillis()
        database.playlistDao().insertPlaylistItem(
            PlaylistItemEntity(
                id = UUID.randomUUID().toString(),
                playlistId = playlistId,
                trackId = trackId,
                position = nextPos,
                addedAt = now
            )
        )
    }

    suspend fun scanDirectory(dir: File) = withContext(loomDispatcher) {
        scanLocalFolder(dir, {}, {})
    }

    fun triggerTrackDownload(track: com.aura.music.data.network.TrackSummary) {
        scope.launch(Dispatchers.IO) {
            val token = apiToken ?: return@launch
            try {
                apiService.createDownload(
                    token = token,
                    request = com.aura.music.data.network.DownloadRequestDto(
                        trackId = track.id,
                        sourceHint = com.aura.music.data.network.SourceHintDto(
                            providerName = "deezer",
                            providerTrackId = track.id,
                            title = track.title,
                            artistName = track.displayArtistName,
                            albumTitle = track.displayAlbumTitle,
                            coverUri = track.coverUri
                        )
                    )
                )
            } catch (e: Exception) {
                System.err.println("Failed to request download: ${e.message}")
            }
        }
    }

    fun triggerSingleFileDownload(track: TrackListRow) {
        scope.launch(Dispatchers.IO) {
            val token = apiToken ?: return@launch
            downloadCloudTrack(
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
            uploadCloudTrack(token, track.id)
        }
    }

    fun triggerCloudDownloadAll(tracks: List<TrackListRow>) {
        scope.launch(Dispatchers.IO) {
            val token = apiToken ?: return@launch
            tracks.forEach { trk ->
                downloadCloudTrack(
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
                uploadCloudTrack(token, trk.id)
            }
        }
    }

    fun clearCompletedDownloadJobs() {
        scope.launch(Dispatchers.IO) {
            database.downloadJobDao().clearCompletedJobs()
        }
    }

    fun retryDownloadJob(jobId: String) {
        scope.launch(Dispatchers.IO) {
            val token = apiToken ?: return@launch
            try {
                apiService.retryDownload(token, jobId)
            } catch (e: Exception) {
                System.err.println("Failed to retry job: ${e.message}")
            }
        }
    }

    fun cancelDownloadJob(jobId: String) {
        scope.launch(Dispatchers.IO) {
            database.downloadJobDao().deleteJob(jobId)
        }
    }

    fun toggleLike(trackId: String, onComplete: (() -> Unit)? = null) {
        scope.launch {
            val trackRow = database.trackDao().getTrackById(trackId) ?: return@launch
            val currentlyLiked = trackRow.isLiked
            val now = System.currentTimeMillis()
            
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    if (currentlyLiked) {
                        database.trackLikeDao().deleteLike(trackId)
                        database.trackLikeDao().setTrackIsLiked(trackId, liked = false, updatedAt = now)
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
                    }
                }
            }
            
            // Sync UI state with updated liked state
            val currentTrack = _uiState.value.currentTrack
            if (currentTrack != null && currentTrack.trackId == trackId) {
                _uiState.update { it.copy(isCurrentTrackLiked = !currentlyLiked) }
            }

            onComplete?.let {
                withContext(Dispatchers.Main) {
                    it.invoke()
                }
            }

            // Sync with backend API
            val token = apiToken
            if (token != null) {
                try {
                    if (currentlyLiked) {
                        apiService.unlikeTrack(token, trackId)
                    } else {
                        apiService.likeTrack(
                            token = token,
                            trackId = trackId,
                            sourceContextType = _uiState.value.contextType,
                            sourceContextId = _uiState.value.contextId
                        )
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to sync liked track to backend: ${e.message}")
                }
            }
        }
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

    private fun saveSnapshot() {
        scope.launch {
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
        val entity = database.playbackSnapshotDao().getActiveSnapshot() ?: return
        val trackId = entity.currentTrackId ?: return
        val trackRow = database.trackDao().getTrackById(trackId) ?: return

        val queuedTrack = QueuedTrack(
            trackId = trackRow.id,
            title = trackRow.title,
            artistName = trackRow.artistName,
            albumTitle = trackRow.albumTitle,
            contentUri = trackRow.contentUri,
            durationMs = trackRow.durationMs,
            coverUri = trackRow.coverUri,
            source = TrackSource.CONTEXT
        )

        val contextType = entity.playbackContextType ?: "single_track"
        val contextId = entity.playbackContextId ?: trackId

        val contextTracks = reloadContextTracks(contextType, contextId)
        var startIndex = contextTracks.indexOfFirst { it.trackId == trackId }
        if (startIndex == -1) {
            startIndex = 0
        }

        val repeat = when (entity.repeatMode) {
            "one" -> RepeatMode.One
            "all" -> RepeatMode.All
            else -> RepeatMode.Off
        }

        queueManager.restoreModes(entity.shuffleEnabled, repeat)
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
                positionMs = entity.positionMs,
                durationMs = trackRow.durationMs ?: 0L,
                shuffleEnabled = entity.shuffleEnabled,
                repeatMode = repeat,
                priorityQueue = queueManager.state.value.priorityQueue,
                mainQueueTracks = queueManager.getUpcomingContextTracks(),
                contextType = contextType,
                contextId = contextId,
                isCurrentTrackLiked = trackRow.isLiked
            )
        }

        if (trackRow.contentUri != null) {
            audioPlayer.play(trackRow.contentUri!!)
            audioPlayer.pause()
            audioPlayer.seekTo(entity.positionMs)
        }
    }

    suspend fun reloadContextTracks(type: String, id: String): List<QueuedTrack> = withContext(Dispatchers.IO) {
        return@withContext when (type) {
            "library_tracks" -> database.trackDao().getAllTracks().map { row ->
                QueuedTrack(
                    trackId = row.id,
                    title = row.title,
                    artistName = row.artistName,
                    albumTitle = row.albumTitle,
                    contentUri = row.contentUri,
                    durationMs = row.durationMs,
                    coverUri = row.coverUri,
                    source = TrackSource.CONTEXT
                )
            }
            "favorites" -> database.trackDao().getLikedTracks().map { row ->
                QueuedTrack(
                    trackId = row.id,
                    title = row.title,
                    artistName = row.artistName,
                    albumTitle = row.albumTitle,
                    contentUri = row.contentUri,
                    durationMs = row.durationMs,
                    coverUri = row.coverUri,
                    source = TrackSource.CONTEXT
                )
            }
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
            "album" -> database.trackDao().getTracksForAlbum(id).map { row ->
                QueuedTrack(
                    trackId = row.id,
                    title = row.title,
                    artistName = row.artistName,
                    albumTitle = row.albumTitle,
                    contentUri = row.contentUri,
                    durationMs = row.durationMs,
                    coverUri = row.coverUri,
                    source = TrackSource.CONTEXT
                )
            }
            "artist" -> database.trackDao().getTracksForArtist(id, 20).map { row ->
                QueuedTrack(
                    trackId = row.id,
                    title = row.title,
                    artistName = row.artistName,
                    albumTitle = row.albumTitle,
                    contentUri = row.contentUri,
                    durationMs = row.durationMs,
                    coverUri = row.coverUri,
                    source = TrackSource.CONTEXT
                )
            }
            else -> emptyList()
        }
    }

    fun scanLocalFolder(
        folder: File,
        onProgress: (String) -> Unit,
        onComplete: (Int) -> Unit
    ) {
        scope.launch(loomDispatcher) {
            try {
                val now = System.currentTimeMillis()
                val audioExtensions = setOf("mp3", "wav", "m4a")
                val files = folder.walkTopDown().filter {
                    it.isFile && it.extension.lowercase() in audioExtensions
                }.toList()

                val scannedTracks = mutableListOf<ScannedTrackInfo>()
                val semaphore = kotlinx.coroutines.sync.Semaphore(15)
                val resolvedCount = java.util.concurrent.atomic.AtomicInteger(0)

                coroutineScope {
                    val jobs = files.map { file ->
                        async {
                            semaphore.withPermit {
                                val (title, artist, album) = parseMp3Tags(file)
                                val currentResolved = resolvedCount.incrementAndGet()
                                onProgress("Scan: $currentResolved/${files.size} - Recherche de pochette...")
                                
                                var coverUri: String? = null
                                try {
                                    val response = apiService.search("$title $artist", limitTracks = 3)
                                    val matchedTrack = response.data?.tracks?.firstOrNull()
                                    coverUri = matchedTrack?.coverUri
                                } catch (e: Exception) {
                                    // Offline or search error, ignore
                                }
                                
                                ScannedTrackInfo(file, title, artist, album, coverUri)
                            }
                        }
                    }
                    scannedTracks.addAll(jobs.awaitAll())
                }

                var addedCount = 0

                database.useWriterConnection { transactor ->
                    transactor.immediateTransaction {
                        for ((index, trackInfo) in scannedTracks.withIndex()) {
                            onProgress("Sauvegarde: ${index + 1}/${scannedTracks.size} - ${trackInfo.file.name}")
                            
                            val file = trackInfo.file
                            val title = trackInfo.title
                            val artist = trackInfo.artist
                            val album = trackInfo.album
                            val coverUri = trackInfo.coverUri
                            
                            val trackId = "track_${file.absolutePath.hashCode()}"
                            val artistId = "artist_${artist.hashCode()}"
                            val albumId = album?.let { "album_${(artist + "_" + it).hashCode()}" }

                            // 1. Artist
                            database.artistDao().upsertArtists(
                                listOf(
                                    ArtistEntity(
                                        id = artistId,
                                        name = artist,
                                        normalizedName = artist.lowercase(),
                                        pictureUri = null,
                                        createdAt = now,
                                        updatedAt = now
                                    )
                                )
                            )

                            // 2. Album
                            if (albumId != null && album != null) {
                                database.albumDao().upsertAlbums(
                                    listOf(
                                        AlbumEntity(
                                            id = albumId,
                                            primaryArtistId = artistId,
                                            title = album,
                                            normalizedTitle = album.lowercase(),
                                            coverUri = coverUri,
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
                                        title = title,
                                        normalizedTitle = title.lowercase(),
                                        displayArtistName = artist,
                                        displayAlbumTitle = album,
                                        durationMs = 0L,
                                        coverUri = coverUri,
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

                            addedCount++
                        }
                    }
                }
                
                onComplete(addedCount)
            } catch (e: Exception) {
                System.err.println("Error scanning local folder: ${e.message}")
                onComplete(0)
            }
        }
    }

    private fun parseMp3Tags(file: File): Triple<String, String, String?> {
        var title = ""
        var artist = ""
        var album = ""

        // Try reading ID3v1 tags (last 128 bytes of MP3 file)
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
                // Ignore parsing errors and fallback to filename
            }
        }

        // Clean null characters or garbage characters from tags
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

    private var downloadSyncJob: Job? = null
    private val isSyncingDownloads = java.util.concurrent.atomic.AtomicBoolean(false)

    fun startDownloadSyncLoop() {
        downloadSyncJob?.cancel()
        downloadSyncJob = scope.launch(loomDispatcher) {
            while (isActive) {
                val token = apiToken
                if (!token.isNullOrBlank()) {
                    try {
                        syncActiveJobs(token)
                    } catch (e: Exception) {
                        System.err.println("Error syncing download jobs: ${e.message}")
                    }
                }
                delay(3000)
            }
        }
    }

    fun stopDownloadSyncLoop() {
        downloadSyncJob?.cancel()
        downloadSyncJob = null
    }

    suspend fun syncActiveJobs(token: String) {
        if (!isSyncingDownloads.compareAndSet(false, true)) return
        try {
            val response = apiService.listDownloads(token = token)
            val items = response.data?.items ?: return
            
            val now = System.currentTimeMillis()
            val jobs = mutableListOf<DownloadJobEntity>()
            for (item in items) {
                val trackExists = database.trackDao().getRawTrackById(item.trackId) != null
                val isFinished = item.status == "succeeded" || item.status == "completed" || item.status == "failed"
                
                if (!trackExists) {
                    if (isFinished) {
                        continue
                    } else {
                        database.trackDao().upsertTracks(
                            listOf(
                                TrackEntity(
                                    id = item.trackId,
                                    primaryArtistId = null,
                                    albumId = null,
                                    title = "Titre ${item.trackId}",
                                    normalizedTitle = "titre ${item.trackId}",
                                    displayArtistName = "Artiste Inconnu",
                                    displayAlbumTitle = null,
                                    durationMs = 0L,
                                    coverUri = null,
                                    canonicalAudioSourceType = "cloud_only",
                                    isLiked = false,
                                    isDownloadedByAura = false,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        )
                    }
                }

                jobs.add(
                    DownloadJobEntity(
                        id = item.id,
                        trackId = item.trackId,
                        providerName = item.providerName,
                        status = item.status,
                        progressPercent = item.progressPercent,
                        errorCode = item.errorCode,
                        errorMessage = item.errorMessage,
                        attemptCount = item.attemptCount,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
            database.downloadJobDao().upsert(jobs)

            for (item in items) {
                if (item.status == "succeeded" || item.status == "completed") {
                    val appDir = File(System.getProperty("user.home"), ".aura")
                    val downloadsDir = File(appDir, "downloads")
                    val targetFile = File(downloadsDir, "${item.trackId.replace(':', ';')}.mp3")
                    
                    val rawTrack = database.trackDao().getRawTrackById(item.trackId)
                    val isDbLinked = rawTrack != null && rawTrack.canonicalAudioSourceType == "downloaded" && rawTrack.isDownloadedByAura
                    
                    if (!targetFile.exists() || targetFile.length() == 0L || !isDbLinked) {
                        if (rawTrack != null) {
                            fetchDownloadedFile(item.id, item.trackId, token)
                        }
                    }
                }
            }
        } finally {
            isSyncingDownloads.set(false)
        }
    }

    suspend fun fetchDownloadedFile(jobId: String, trackId: String, token: String) = withContext(Dispatchers.IO) {
        try {
            System.out.println("Fetching physical MP3 file for succeeded job $jobId...")
            val response = apiService.downloadFile(token, jobId)
            if (response.status.value !in 200..299) {
                System.err.println("Failed to download physical file for job $jobId: HTTP ${response.status.value}")
                return@withContext
            }

            val appDir = File(System.getProperty("user.home"), ".aura")
            val downloadsDir = File(appDir, "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val targetFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().use { inputStream ->
                java.io.FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }

            System.out.println("Downloaded file saved successfully to ${targetFile.absolutePath} (size: ${targetFile.length()} bytes)")

            val now = System.currentTimeMillis()
            val fileUri = targetFile.toURI().toString()
            val rawTrack = database.trackDao().getRawTrackById(trackId)

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    val mockMediaStoreId = System.currentTimeMillis()
                    val mediaLink = TrackMediaLinkEntity(
                        id = "media-link:$mockMediaStoreId",
                        trackId = trackId,
                        mediaStoreId = mockMediaStoreId,
                        contentUri = fileUri,
                        fileSizeBytes = targetFile.length(),
                        mimeType = "audio/mpeg",
                        dateModifiedEpochMs = now,
                        availabilityStatus = "present",
                        lastScannedAt = now
                    )
                    database.trackDao().upsertTrackMediaLinks(listOf(mediaLink))

                    if (rawTrack != null) {
                        val updatedTrack = rawTrack.copy(
                            canonicalAudioSourceType = "downloaded",
                            isDownloadedByAura = true,
                            updatedAt = now
                        )
                        database.trackDao().upsertTracks(listOf(updatedTrack))
                        System.out.println("Updated local TrackEntity $trackId to downloaded state")
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to retrieve physical file for job $jobId: ${e.message}")
        }
    }

    private var cloudSyncJob: Job? = null
    private val isSyncingCloud = java.util.concurrent.atomic.AtomicBoolean(false)

    fun startCloudFileSyncLoop(onDataChanged: (() -> Unit)? = null) {
        cloudSyncJob?.cancel()
        cloudSyncJob = scope.launch(loomDispatcher) {
            while (isActive) {
                val token = apiToken
                if (!token.isNullOrBlank()) {
                    try {
                        performCloudSync(token, onDataChanged)
                    } catch (e: Exception) {
                        System.err.println("Error in cloud sync loop: ${e.message}")
                    }
                }
                delay(60000) // Every 60 seconds
            }
        }
    }

    fun stopCloudFileSyncLoop() {
        cloudSyncJob?.cancel()
        cloudSyncJob = null
    }

    suspend fun syncCloudData(token: String, onFinished: (() -> Unit)? = null) = withContext(Dispatchers.IO) {
        try {
            System.out.println("Synchronizing cloud metadata (files, playlists, likes)...")
            val filesResp = apiService.listSyncFiles(token)
            val cloudFiles = filesResp.data?.items ?: emptyList()
            val now = System.currentTimeMillis()

            val appDir = File(System.getProperty("user.home"), ".aura")
            val downloadsDir = File(appDir, "downloads")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val artistsToInsert = mutableListOf<ArtistEntity>()
            val albumsToInsert = mutableListOf<AlbumEntity>()
            val tracksToInsert = mutableListOf<TrackEntity>()
            val mediaLinksToInsert = mutableListOf<TrackMediaLinkEntity>()

            for (cloudFile in cloudFiles) {
                val artistName = cloudFile.artistName ?: "Artiste inconnu"
                val artistId = cloudFile.artistId ?: "artist:${artistName.lowercase().trim().replace(" ", "_")}"
                val title = cloudFile.title ?: "Titre inconnu"
                val albumTitle = cloudFile.albumTitle
                val albumId = cloudFile.albumId ?: if (albumTitle != null) "album:${artistName.lowercase().trim().replace(" ", "_")}:${albumTitle.lowercase().trim().replace(" ", "_")}" else null

                artistsToInsert.add(
                    ArtistEntity(
                        id = artistId,
                        name = artistName,
                        normalizedName = SearchNormalizer.normalize(artistName),
                        pictureUri = null,
                        createdAt = now,
                        updatedAt = now
                    )
                )

                if (albumId != null && albumTitle != null) {
                    albumsToInsert.add(
                        AlbumEntity(
                            id = albumId,
                            primaryArtistId = artistId,
                            title = albumTitle,
                            normalizedTitle = SearchNormalizer.normalize(albumTitle),
                            coverUri = cloudFile.coverUri,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }

                val targetFile = File(downloadsDir, "${cloudFile.trackId.replace(':', ';')}.mp3")
                val isDownloaded = targetFile.exists() && targetFile.length() > 0L
                val fileUri = if (isDownloaded) targetFile.toURI().toString() else null

                tracksToInsert.add(
                    TrackEntity(
                        id = cloudFile.trackId,
                        primaryArtistId = artistId,
                        albumId = albumId,
                        title = title,
                        normalizedTitle = SearchNormalizer.normalize(title),
                        displayArtistName = artistName,
                        displayAlbumTitle = albumTitle,
                        durationMs = cloudFile.durationMs,
                        coverUri = cloudFile.coverUri,
                        canonicalAudioSourceType = if (isDownloaded) "downloaded" else "cloud",
                        isLiked = false,
                        isDownloadedByAura = isDownloaded,
                        createdAt = now,
                        updatedAt = now
                    )
                )

                if (fileUri != null) {
                    mediaLinksToInsert.add(
                        TrackMediaLinkEntity(
                            id = "media-link:${cloudFile.trackId}",
                            trackId = cloudFile.trackId,
                            mediaStoreId = System.currentTimeMillis(),
                            contentUri = fileUri,
                            fileSizeBytes = targetFile.length(),
                            mimeType = "audio/mpeg",
                            dateModifiedEpochMs = targetFile.lastModified(),
                            availabilityStatus = "present",
                            lastScannedAt = now
                        )
                    )
                }
            }

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    if (artistsToInsert.isNotEmpty()) {
                        database.artistDao().insertArtistsIgnore(artistsToInsert)
                    }
                    if (albumsToInsert.isNotEmpty()) {
                        database.albumDao().insertAlbumsIgnore(albumsToInsert)
                    }
                    if (tracksToInsert.isNotEmpty()) {
                        database.trackDao().upsertTracks(tracksToInsert)
                    }
                    if (mediaLinksToInsert.isNotEmpty()) {
                        database.trackDao().upsertTrackMediaLinks(mediaLinksToInsert)
                    }
                }
            }

            // Sync Likes
            try {
                val likesResp = apiService.getLikes(token)
                val likes = likesResp.data ?: emptyList()
                for (like in likes) {
                    database.trackLikeDao().setTrackIsLiked(like.trackId, true, now)
                }
            } catch (e: Exception) {
                System.err.println("Failed to sync likes: ${e.message}")
            }

            // Sync Playlists
            try {
                val playlistsResp = apiService.getPlaylists(token)
                val playlists = playlistsResp.data ?: emptyList()
                for (pl in playlists) {
                    database.playlistDao().insertPlaylist(
                        PlaylistEntity(
                            id = pl.id,
                            name = pl.name,
                            coverUri = pl.coverUri,
                            isPinned = false,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
            } catch (e: Exception) {
                System.err.println("Failed to sync playlists: ${e.message}")
            }

            System.out.println("Cloud data sync completed (${cloudFiles.size} tracks from cloud).")
            withContext(Dispatchers.Main) {
                onFinished?.invoke()
            }
        } catch (e: Exception) {
            System.err.println("Failed to sync cloud data: ${e.message}")
        }
    }

    suspend fun performCloudSync(token: String, onDataChanged: (() -> Unit)? = null) {
        if (!autoSyncEnabled) return
        if (!isSyncingCloud.compareAndSet(false, true)) return
        try {
            System.out.println("Starting background Cloud Sync...")
            syncCloudData(token, onDataChanged)
            val response = apiService.listSyncFiles(token)
            val cloudFiles = response.data?.items ?: return
            val syncedTrackIds = cloudFiles.map { it.trackId }.toSet()

            // 1. Cloud -> PC: Download missing files if autoSyncEnabled
            val appDir = File(System.getProperty("user.home"), ".aura")
            val downloadsDir = File(appDir, "downloads")
            for (cloudFile in cloudFiles) {
                val rawTrack = database.trackDao().getRawTrackById(cloudFile.trackId)
                val targetFile = File(downloadsDir, "${cloudFile.trackId.replace(':', ';')}.mp3")
                
                val existsLocally = targetFile.exists() && targetFile.length() > 0L
                val isDbDownloaded = rawTrack != null && rawTrack.canonicalAudioSourceType == "downloaded"
                
                if (!existsLocally || !isDbDownloaded) {
                    try {
                        downloadCloudTrack(
                            token = token,
                            trackId = cloudFile.trackId,
                            title = cloudFile.title ?: "Titre inconnu",
                            artistName = cloudFile.artistName ?: "Artiste inconnu",
                            albumTitle = cloudFile.albumTitle,
                            durationMs = cloudFile.durationMs ?: 0L,
                            coverUri = cloudFile.coverUri
                        )
                        onDataChanged?.invoke()
                    } catch (e: Exception) {
                        System.err.println("Auto-download failed for ${cloudFile.trackId}: ${e.message}")
                    }
                }
            }

            // 2. PC -> Cloud: Auto-upload local files if autoSyncEnabled
            if (autoSyncEnabled) {
                val localTracks = database.trackDao().getAllTracks()
                for (track in localTracks) {
                    val rawTrack = database.trackDao().getRawTrackById(track.id)
                    if (rawTrack != null && rawTrack.canonicalAudioSourceType == "local") {
                        if (!syncedTrackIds.contains(track.id)) {
                            try {
                                uploadCloudTrack(token, track.id)
                            } catch (e: Exception) {
                                System.err.println("Auto-upload failed for ${track.id}: ${e.message}")
                            }
                        }
                    }
                }
            }
            System.out.println("Background Cloud Sync finished.")
        } finally {
            isSyncingCloud.set(false)
        }
    }

    suspend fun downloadCloudTrack(
        token: String,
        trackId: String,
        title: String,
        artistName: String,
        albumTitle: String?,
        durationMs: Long,
        coverUri: String?
    ) = withContext(Dispatchers.IO) {
        try {
            System.out.println("Downloading cloud file for $trackId...")
            val response = apiService.downloadSyncFile(token, trackId)
            if (response.status.value !in 200..299) {
                System.err.println("Failed to download cloud file $trackId: HTTP ${response.status.value}")
                return@withContext
            }

            val appDir = File(System.getProperty("user.home"), ".aura")
            val downloadsDir = File(appDir, "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val targetFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().use { inputStream ->
                java.io.FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }

            System.out.println("Downloaded cloud file saved to ${targetFile.absolutePath}")

            val now = System.currentTimeMillis()
            val fileUri = targetFile.toURI().toString()

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    // Artist
                    var primaryArtistId: String? = null
                    if (artistName.isNotBlank()) {
                        primaryArtistId = "artist:${artistName.lowercase().trim().replace(" ", "_")}"
                        database.artistDao().insertArtistsIgnore(
                            listOf(
                                ArtistEntity(
                                    id = primaryArtistId,
                                    name = artistName,
                                    normalizedName = artistName.lowercase(),
                                    pictureUri = null,
                                    summary = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        )
                    }

                    // Album
                    var albumId: String? = null
                    if (!albumTitle.isNullOrBlank()) {
                        albumId = "album:${albumTitle.lowercase().trim().replace(" ", "_")}"
                        database.albumDao().insertAlbumsIgnore(
                            listOf(
                                AlbumEntity(
                                    id = albumId,
                                    primaryArtistId = primaryArtistId,
                                    title = albumTitle,
                                    normalizedTitle = albumTitle.lowercase(),
                                    coverUri = coverUri,
                                    releaseDate = null,
                                    trackCount = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        )
                    }

                    // Track
                    val existingTrack = database.trackDao().getRawTrackById(trackId)
                    val trackEntity = TrackEntity(
                        id = trackId,
                        primaryArtistId = primaryArtistId,
                        albumId = albumId,
                        title = title,
                        normalizedTitle = title.lowercase(),
                        displayArtistName = artistName,
                        displayAlbumTitle = albumTitle,
                        durationMs = durationMs,
                        coverUri = coverUri ?: existingTrack?.coverUri,
                        canonicalAudioSourceType = "downloaded",
                        isLiked = existingTrack?.isLiked ?: false,
                        isDownloadedByAura = true,
                        createdAt = existingTrack?.createdAt ?: now,
                        updatedAt = now
                    )
                    database.trackDao().upsertTracks(listOf(trackEntity))

                    // Media Link
                    val mockMediaStoreId = System.currentTimeMillis()
                    val mediaLink = TrackMediaLinkEntity(
                        id = "media-link:$mockMediaStoreId",
                        trackId = trackId,
                        mediaStoreId = mockMediaStoreId,
                        contentUri = fileUri,
                        fileSizeBytes = targetFile.length(),
                        mimeType = "audio/mpeg",
                        dateModifiedEpochMs = now,
                        availabilityStatus = "present",
                        lastScannedAt = now
                    )
                    database.trackDao().upsertTrackMediaLinks(listOf(mediaLink))
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to download cloud track $trackId: ${e.message}")
            throw e
        }
    }

    suspend fun uploadCloudTrack(token: String, trackId: String) = withContext(Dispatchers.IO) {
        val trackRow = database.trackDao().getTrackById(trackId) ?: return@withContext
        val rawTrack = database.trackDao().getRawTrackById(trackId) ?: return@withContext
        val uriStr = trackRow.contentUri ?: return@withContext

        System.out.println("Uploading track $trackId ($uriStr) to cloud...")
        val file = if (uriStr.startsWith("file:/")) {
            File(java.net.URI(uriStr))
        } else {
            File(uriStr)
        }

        if (!file.exists() || !file.isFile) {
            System.err.println("File does not exist: ${file.absolutePath}")
            return@withContext
        }

        var uploadCoverUri = rawTrack.coverUri
        if (uploadCoverUri.isNullOrBlank() || !uploadCoverUri.startsWith("http")) {
            try {
                val searchResult = apiService.search("${trackRow.title} ${trackRow.artistName}", limitTracks = 3)
                val resolved = searchResult.data?.tracks?.firstOrNull { it.coverUri?.startsWith("http") == true }?.coverUri
                if (resolved != null) {
                    uploadCoverUri = resolved
                    // Update Room local entry to preserve the resolved HTTPS cover (if it was null/empty)
                    if (rawTrack.coverUri.isNullOrBlank()) {
                        database.useWriterConnection { transactor ->
                            transactor.immediateTransaction {
                                val updatedTrack = rawTrack.copy(coverUri = resolved)
                                database.trackDao().upsertTracks(listOf(updatedTrack))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Failed to resolve cover online for upload of track $trackId: ${e.message}")
            }
        }

        val fileBytes = file.readBytes()
        val response = apiService.uploadSyncFile(
            token = token,
            trackId = trackId,
            fileBytes = fileBytes,
            mimeType = "audio/mpeg",
            title = trackRow.title,
            artistName = trackRow.artistName,
            albumTitle = trackRow.albumTitle,
            durationMs = trackRow.durationMs,
            artistId = rawTrack.primaryArtistId,
            albumId = rawTrack.albumId,
            coverUri = uploadCoverUri
        )

        if (response.data != null) {
            System.out.println("Track $trackId uploaded successfully to cloud.")
        } else {
            System.err.println("Failed to upload track $trackId: ${response.error?.message}")
        }
    }

    suspend fun deleteCloudTrack(token: String, trackId: String) = withContext(Dispatchers.IO) {
        System.out.println("Deleting track $trackId from cloud...")
        val response = apiService.deleteSyncFile(token, trackId)
        if (response.data?.deleted == true) {
            System.out.println("Track $trackId deleted successfully from cloud.")
        } else {
            System.err.println("Failed to delete track $trackId from cloud.")
        }
    }
}

private data class ScannedTrackInfo(
    val file: File,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUri: String?
)
