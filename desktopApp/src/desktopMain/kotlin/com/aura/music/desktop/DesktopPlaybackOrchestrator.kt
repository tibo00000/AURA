package com.aura.music.desktop

import androidx.room3.useWriterConnection
import androidx.room3.immediateTransaction
import com.aura.music.data.local.*
import com.aura.music.data.player.QueueManager
import com.aura.music.domain.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.asCoroutineDispatcher
import com.aura.music.data.network.AuraApiService
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val loomDispatcher = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null

    fun connect() {
        audioPlayer.onCompletionListener = {
            scope.launch {
                next()
            }
        }

        audioPlayer.onErrorListener = { error ->
            _uiState.update { it.copy(playbackState = PlaybackState.Error, errorMessage = error) }
        }

        // Periodically refresh track progress slider in the UI
        progressJob = scope.launch {
            while (isActive) {
                if (audioPlayer.isPlaying()) {
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
    }

    fun disconnect() {
        progressJob?.cancel()
        audioPlayer.stop()
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

    fun toggleLike(trackId: String) {
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

                var addedCount = 0

                database.useWriterConnection { transactor ->
                    transactor.immediateTransaction {
                        for ((index, file) in files.withIndex()) {
                            onProgress("Indexation: ${index + 1}/${files.size} - ${file.name}")
                            
                            val (title, artist, album) = parseMp3Tags(file)
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
                                            coverUri = null,
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
                                        durationMs = 0L, // Will resolve at play time or fallback to 0
                                        coverUri = null,
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
}
