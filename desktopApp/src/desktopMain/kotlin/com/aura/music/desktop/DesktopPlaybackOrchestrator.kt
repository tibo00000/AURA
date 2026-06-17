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
        startDownloadSyncLoop()
    }

    fun disconnect() {
        progressJob?.cancel()
        audioPlayer.stop()
        stopDownloadSyncLoop()
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
                    val targetFile = File(downloadsDir, "${item.trackId}.mp3")
                    
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

            val targetFile = File(downloadsDir, "$trackId.mp3")
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

            var localCoverUri: String? = null
            val rawTrack = database.trackDao().getRawTrackById(trackId)
            val imageUrl = rawTrack?.coverUri
            if (imageUrl != null && imageUrl.startsWith("http")) {
                val client = HttpClient()
                try {
                    val imageResponse = client.get(imageUrl)
                    if (imageResponse.status.value in 200..299) {
                        val imageBytes = imageResponse.body<ByteArray>()
                        val coversDir = File(appDir, "covers")
                        if (!coversDir.exists()) {
                            coversDir.mkdirs()
                        }
                        val coverFile = File(coversDir, "$trackId.jpg")
                        java.io.FileOutputStream(coverFile).use { fos ->
                            fos.write(imageBytes)
                        }
                        localCoverUri = coverFile.toURI().toString()
                        System.out.println("Downloaded remote cover from $imageUrl for $trackId to $localCoverUri")
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to download remote cover fallback for $trackId: ${e.message}")
                } finally {
                    client.close()
                }
            }

            val now = System.currentTimeMillis()
            val fileUri = targetFile.toURI().toString()

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
                            coverUri = localCoverUri ?: rawTrack.coverUri,
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
}

private data class ScannedTrackInfo(
    val file: File,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUri: String?
)
