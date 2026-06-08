package com.aura.music.domain.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aura.music.data.player.PlaybackStateStore
import com.aura.music.data.player.QueueManager
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.PlaylistDetail
import com.aura.music.data.repository.AlbumDetail
import com.aura.music.data.repository.ArtistDetail
import com.aura.music.data.local.TrackListRow
import com.aura.music.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestre la lecture en appliquant les regles play, pause, next, prev, seek.
 *
 * Gouverne par :
 * - docs/android/player/architecture.md
 * - docs/domain/playback-user-flows.md
 *
 * Responsabilites :
 * - Recoit les PlayerEvent de la couche UI
 * - Utilise QueueManager pour la resolution de la prochaine piste
 * - Pilote le MediaController (lien vers le PlaybackService/ExoPlayer)
 * - Sauvegarde l'etat via PlaybackStateStore
 * - Expose un StateFlow<PlayerUiState> agrege
 */
class PlaybackOrchestrator(
    private val context: Context,
    private val queueManager: QueueManager,
    private val stateStore: PlaybackStateStore,
    private val repository: LocalLibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateStr = playbackStateToString(playbackState)
            android.util.Log.d("PlaybackOrchestrator", "onPlaybackStateChanged: state=$stateStr, isPlaying=${controller?.isPlaying}, playWhenReady=${controller?.playWhenReady}")
            syncUiState()
            if (playbackState == Player.STATE_ENDED) {
                android.util.Log.d("PlaybackOrchestrator", "onPlaybackStateChanged: track ended, triggering handleTrackEnded()")
                handleTrackEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            android.util.Log.d("PlaybackOrchestrator", "onIsPlayingChanged: isPlaying=$isPlaying")
            syncUiState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val transitionedTrackId = mediaItem?.mediaId
            val reasonStr = transitionReasonToString(reason)
            android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: transitionedTrackId=$transitionedTrackId, reason=$reasonStr")
            
            val ctrl = controller
            if (ctrl != null) {
                android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: Player status - currentMediaItemIndex=${ctrl.currentMediaItemIndex}, mediaItemCount=${ctrl.mediaItemCount}")
                for (i in 0 until ctrl.mediaItemCount) {
                    android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: ExoPlayer item[$i] = ${ctrl.getMediaItemAt(i).mediaId}")
                }
            }
            
            syncUiState()
            val trackId = mediaItem?.mediaId
            if (trackId != null) {
                scope.launch {
                    val fresh = repository.getTrackById(trackId)
                    updateLikedState(fresh?.isLiked ?: false)
                }
            } else {
                updateLikedState(false)
            }

            if (ctrl == null) {
                android.util.Log.e("PlaybackOrchestrator", "onMediaItemTransition: controller is null!")
                return
            }

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: IGNORED queue adjustment because transition was caused by a PLAYLIST_CHANGED event.")
                return
            }

            val transitionedTrackIdResolved = mediaItem?.mediaId ?: return
            
            val currentTrack = queueManager.state.value.currentTrack
            android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: queueManager currentTrack=${currentTrack?.trackId}")
            
            if (transitionedTrackIdResolved != currentTrack?.trackId) {
                val targetNextTrack = queueManager.state.value.priorityQueue.firstOrNull()
                    ?: queueManager.getUpcomingContextTracks().firstOrNull()
                val targetPrevTrack = queueManager.state.value.history.lastOrNull()
                
                android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: mismatch! transitioned=$transitionedTrackIdResolved, targetNext=${targetNextTrack?.trackId}, targetPrev=${targetPrevTrack?.trackId}")
                
                if (transitionedTrackIdResolved == targetNextTrack?.trackId) {
                    android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: matches targetNextTrack. Shifting context to next...")
                    queueManager.next()
                    syncExoPlayerPlaylist()
                    saveSnapshot()
                } else if (transitionedTrackIdResolved == targetPrevTrack?.trackId) {
                    android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: matches targetPrevTrack. Shifting context to previous...")
                    queueManager.previous(0L)
                    syncExoPlayerPlaylist()
                    saveSnapshot()
                } else {
                    android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: external client transition detected to=$transitionedTrackIdResolved. Handling external transition...")
                    scope.launch {
                        handleExternalTrackTransition(transitionedTrackIdResolved)
                    }
                }
            } else {
                android.util.Log.d("PlaybackOrchestrator", "onMediaItemTransition: matches currentTrack (${currentTrack?.trackId}). Syncing playlist...")
                syncExoPlayerPlaylist()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e("PlaybackOrchestrator", "onPlayerError: error=${error.localizedMessage}", error)
            _uiState.update { current ->
                current.copy(
                    playbackState = PlaybackState.Error,
                    errorMessage = error.localizedMessage ?: "Playback error",
                )
            }
        }
    }

    /**
     * Connecte le MediaController au PlaybackService.
     * Doit etre appele au demarrage de l'app.
     */
    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync().also { future ->
            future.addListener(
                {
                    controller = future.get()
                    controller?.addListener(playerListener)
                    scope.launch { restoreSnapshot() }
                },
                MoreExecutors.directExecutor(),
            )
        }
    }

    /**
     * Deconnecte le MediaController. Appele lors de la destruction de l'app.
     */
    fun disconnect() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
    }

    /**
     * Traite un evenement utilisateur.
     */
    fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.PlayTrack -> handlePlay(event)
            is PlayerEvent.Pause -> handlePause()
            is PlayerEvent.TogglePlayPause -> handleTogglePlayPause()
            is PlayerEvent.Next -> handleNext()
            is PlayerEvent.Previous -> handlePrevious()
            is PlayerEvent.SeekTo -> handleSeek(event.positionMs)
            is PlayerEvent.AddToQueue -> handleAddToQueue(event.track)
            is PlayerEvent.RemoveFromQueue -> handleRemoveFromQueue(event.index)
            is PlayerEvent.ReorderQueue -> handleReorderQueue(event.fromIndex, event.toIndex)
            is PlayerEvent.RemoveFromMainQueue -> handleRemoveFromMainQueue(event.internalId)
            is PlayerEvent.ReorderMainQueue -> handleReorderMainQueue(event.fromInternalId, event.toInternalId)
            is PlayerEvent.ToggleShuffle -> handleToggleShuffle()
            is PlayerEvent.CycleRepeatMode -> handleCycleRepeatMode()
            // ToggleLike est traite dans PlayerViewModel (persistance Room, pas ExoPlayer)
            is PlayerEvent.ToggleLike -> Unit
        }
    }

    /**
     * Met a jour uniquement l'etat du like dans le uiState.
     * Appele par PlayerViewModel apres relecture Room.
     */
    fun updateLikedState(liked: Boolean) {
        _uiState.update { it.copy(isCurrentTrackLiked = liked) }
    }

    /**
     * Retourne la position courante du player en ms.
     */
    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L

    /**
     * Met a jour uniquement la position dans le uiState sans toucher au controller.
     * Appele periodiquement par le ViewModel pour rafraichir la barre de progression.
     */
    fun refreshPosition() {
        val ctrl = controller ?: return
        val pos = ctrl.currentPosition
        val dur = ctrl.duration.coerceAtLeast(0L)
        _uiState.update { it.copy(positionMs = pos, durationMs = dur) }
    }

    /**
     * Sauvegarde le snapshot actuel de facon explicite.
     * Utile pour sauvegarder periodiquement depuis le ViewModel.
     */
    fun saveSnapshot() {
        scope.launch {
            val state = queueManager.state.value
            stateStore.save(
                currentTrackId = state.currentTrack?.trackId,
                contextType = state.context?.type,
                contextId = state.context?.id,
                contextIndex = state.context?.currentIndex,
                positionMs = controller?.currentPosition ?: 0L,
                shuffleEnabled = state.shuffleEnabled,
                repeatMode = state.repeatMode,
            )
        }
    }

    private fun handlePlay(event: PlayerEvent.PlayTrack) {
        queueManager.setContext(
            type = event.contextType,
            id = event.contextId,
            tracks = event.contextTracks,
            startIndex = event.startIndex,
        )
        val track = queueManager.state.value.currentTrack ?: return
        playTrackOnController(track)
        saveSnapshot()
    }

    private fun handlePause() {
        controller?.pause()
        saveSnapshot()
    }

    private fun handleTogglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) {
            ctrl.pause()
        } else {
            ctrl.play()
        }
        saveSnapshot()
    }

    private fun handleNext() {
        android.util.Log.d("PlaybackOrchestrator", "handleNext: currentTrack=${queueManager.state.value.currentTrack?.trackId}")
        val nextTrack = queueManager.next() ?: run {
            android.util.Log.d("PlaybackOrchestrator", "handleNext: no next track, stopping player")
            controller?.stop()
            _uiState.update { it.copy(playbackState = PlaybackState.Idle, currentTrack = null) }
            saveSnapshot()
            return
        }
        android.util.Log.d("PlaybackOrchestrator", "handleNext: zapping to nextTrack=${nextTrack.trackId}")
        playTrackOnController(nextTrack)
        saveSnapshot()
    }

    private fun handlePrevious() {
        val positionMs = controller?.currentPosition ?: 0L
        android.util.Log.d("PlaybackOrchestrator", "handlePrevious: positionMs=$positionMs")
        val previousTrack = queueManager.previous(positionMs) ?: return
        android.util.Log.d("PlaybackOrchestrator", "handlePrevious: resolved previousTrack=${previousTrack.trackId}")
        if (positionMs > QueueManager.RESTART_THRESHOLD_MS) {
            android.util.Log.d("PlaybackOrchestrator", "handlePrevious: seek to 0 because position exceeds threshold")
            controller?.seekTo(0)
        } else {
            android.util.Log.d("PlaybackOrchestrator", "handlePrevious: play previous track")
            playTrackOnController(previousTrack)
        }
        saveSnapshot()
    }

    private fun handleSeek(positionMs: Long) {
        controller?.seekTo(positionMs)
        saveSnapshot()
    }

    private fun handleAddToQueue(track: QueuedTrack) {
        queueManager.addToQueue(track)
        syncExoPlayerPlaylist()
        syncUiState()
    }

    private fun handleRemoveFromQueue(index: Int) {
        queueManager.removeFromQueue(index)
        syncExoPlayerPlaylist()
        syncUiState()
    }

    private fun handleReorderQueue(fromIndex: Int, toIndex: Int) {
        queueManager.reorderQueue(fromIndex, toIndex)
        syncExoPlayerPlaylist()
        syncUiState()
    }

    private fun handleRemoveFromMainQueue(internalId: String) {
        queueManager.removeUpcomingContextTrack(internalId)
        syncExoPlayerPlaylist()
        syncUiState()
    }

    private fun handleReorderMainQueue(fromInternalId: String, toInternalId: String) {
        queueManager.reorderUpcomingContextTrack(fromInternalId, toInternalId)
        syncExoPlayerPlaylist()
        syncUiState()
    }

    private fun handleToggleShuffle() {
        queueManager.toggleShuffle()
        syncExoPlayerPlaylist()
        syncUiState()
        saveSnapshot()
    }

    private fun handleCycleRepeatMode() {
        queueManager.cycleRepeatMode()
        syncExoPlayerPlaylist()
        syncUiState()
        saveSnapshot()
    }

    private fun handleTrackEnded() {
        handleNext()
    }

    private fun playTrackOnController(track: QueuedTrack) {
        val ctrl = controller ?: run {
            android.util.Log.e("PlaybackOrchestrator", "playTrackOnController: controller is null!")
            return
        }
        android.util.Log.d("PlaybackOrchestrator", "playTrackOnController: trackId=${track.trackId}")
        syncExoPlayerPlaylist()
        ctrl.play()
        syncUiState()
    }

    private fun syncUiState() {
        val ctrl = controller
        val queueState = queueManager.state.value

        val playbackState = when {
            ctrl == null -> PlaybackState.Idle
            ctrl.playerError != null -> PlaybackState.Error
            ctrl.playbackState == Player.STATE_BUFFERING -> PlaybackState.Buffering
            ctrl.playbackState == Player.STATE_ENDED -> PlaybackState.Completed
            ctrl.playbackState == Player.STATE_IDLE -> PlaybackState.Idle
            ctrl.isPlaying -> PlaybackState.Playing
            ctrl.playbackState == Player.STATE_READY -> PlaybackState.Paused
            else -> PlaybackState.Preparing
        }

        _uiState.update { current ->
            current.copy(
                playbackState = playbackState,
                currentTrack = queueState.currentTrack,
                positionMs = ctrl?.currentPosition ?: 0L,
                durationMs = ctrl?.duration?.coerceAtLeast(0L) ?: 0L,
                shuffleEnabled = queueState.shuffleEnabled,
                repeatMode = queueState.repeatMode,
                priorityQueue = queueState.priorityQueue,
                mainQueueTracks = queueManager.getUpcomingContextTracks(),
                contextType = queueState.context?.type,
                contextId = queueState.context?.id,
                errorMessage = ctrl?.playerError?.localizedMessage,
                // isCurrentTrackLiked est preserve et mis a jour via updateLikedState()
                // pour eviter un reset a false a chaque sync de progression
            )
        }
    }

    private suspend fun restoreSnapshot() {
        val snapshot = stateStore.restore() ?: return
        queueManager.restoreModes(snapshot.shuffleEnabled, snapshot.repeatMode)
        
        snapshot.currentTrackId?.let { trackId ->
            val trackRow = repository.getTrackById(trackId)
            if (trackRow != null) {
                updateLikedState(trackRow.isLiked)
                val queuedTrack = QueuedTrack(
                    trackId = trackRow.id,
                    title = trackRow.title,
                    artistName = trackRow.artistName,
                    albumTitle = trackRow.albumTitle,
                    contentUri = trackRow.contentUri,
                    durationMs = trackRow.durationMs,
                    coverUri = null,
                    source = com.aura.music.domain.player.TrackSource.CONTEXT
                )
                
                val contextType = snapshot.contextType ?: "single_track"
                val contextId = snapshot.contextId ?: trackRow.id
                
                var finalTracks = reloadContextTracks(contextType, contextId)
                var startIndex = finalTracks.indexOfFirst { it.trackId == trackId }
                if (startIndex == -1) {
                    finalTracks = listOf(queuedTrack)
                    startIndex = 0
                }
                
                queueManager.setContext(
                    type = contextType,
                    id = contextId,
                    tracks = finalTracks,
                    startIndex = startIndex
                )
                
                val ctrl = controller
                if (ctrl != null) {
                    syncExoPlayerPlaylist()
                    val activeIndex = if (queueManager.state.value.history.isNotEmpty()) 1 else 0
                    ctrl.seekTo(activeIndex, snapshot.positionMs)
                }
            }
        }
        
        syncUiState()
    }

    private suspend fun reloadContextTracks(type: String, id: String): List<QueuedTrack> {
        return when (type) {
            "favorites" -> repository.getLikedTracks().map { row ->
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
            "playlist" -> repository.getPlaylistDetail(id)?.tracks?.map { row ->
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
            } ?: emptyList()
            "album" -> repository.getAlbumDetail(id)?.tracks?.map { row ->
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
            } ?: emptyList()
            "artist" -> repository.getArtistDetail(id)?.topTracks?.map { row ->
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
            } ?: emptyList()
            "library_tracks" -> repository.getAllTracks().map { row ->
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

    private fun syncExoPlayerPlaylist() {
        val ctrl = controller ?: return
        
        val state = queueManager.state.value
        val currentTrack = state.currentTrack ?: run {
            android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: currentTrack is null")
            return
        }
        
        android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: START. currentTrack=${currentTrack.trackId}, currentMediaItemIndex=${ctrl.currentMediaItemIndex}, mediaItemCount=${ctrl.mediaItemCount}")
        for (i in 0 until ctrl.mediaItemCount) {
            android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: ExoPlayer item[$i] = ${ctrl.getMediaItemAt(i).mediaId}")
        }

        val rawPrev = state.history.lastOrNull()
        val rawNext = state.priorityQueue.firstOrNull() ?: queueManager.getUpcomingContextTracks().firstOrNull()

        // Filter out adjacent duplicates of the current track to avoid duplicates in the 3-item sliding window
        val prev = if (rawPrev?.trackId != currentTrack.trackId) rawPrev else null
        val next = if (rawNext?.trackId != currentTrack.trackId) rawNext else null
        
        if (ctrl.mediaItemCount < 1) {
            val desiredTracks = mutableListOf<QueuedTrack>()
            if (prev != null) desiredTracks.add(prev)
            desiredTracks.add(currentTrack)
            if (next != null) desiredTracks.add(next)

            val mediaItems = desiredTracks.mapNotNull { createMediaItem(it) }
            val activeIndex = if (prev != null) 1 else 0
            android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: empty playlist. Setting items: ${desiredTracks.map { it.trackId }}, activeIndex=$activeIndex")
            ctrl.setMediaItems(mediaItems, activeIndex, C.TIME_UNSET)
            ctrl.prepare()
            return
        }

        val state_ = queueManager.state.value
        val currentTrack_ = state_.currentTrack ?: return
        
        val desiredTracks = mutableListOf<QueuedTrack>()
        if (prev != null) {
            desiredTracks.add(prev)
        }
        desiredTracks.add(currentTrack_)
        if (next != null) {
            desiredTracks.add(next)
        }
        
        android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: Desired layout: prev=${prev?.trackId}, current=${currentTrack_.trackId}, next=${next?.trackId}")

        // Safely resolve the index of the current track in ExoPlayer, preferring the active index if it matches.
        var currentIndexInPlayer = -1
        val activeIndex = ctrl.currentMediaItemIndex
        if (activeIndex in 0 until ctrl.mediaItemCount && ctrl.getMediaItemAt(activeIndex).mediaId == currentTrack_.trackId) {
            currentIndexInPlayer = activeIndex
        } else {
            for (i in 0 until ctrl.mediaItemCount) {
                if (ctrl.getMediaItemAt(i).mediaId == currentTrack_.trackId) {
                    currentIndexInPlayer = i
                    break
                }
            }
        }
        android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: currentIndexInPlayer=$currentIndexInPlayer")
        
        if (currentIndexInPlayer == -1) {
            val mediaItems = desiredTracks.mapNotNull { createMediaItem(it) }
            val startIndex = if (prev != null) 1 else 0
            android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: track not in playlist. Rebuilding: ${desiredTracks.map { it.trackId }}, startIndex=$startIndex")
            ctrl.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
            ctrl.prepare()
            return
        }
        
        val desiredNext = next
        val hasNextInPlayer = ctrl.mediaItemCount > currentIndexInPlayer + 1
        val currentNextMediaId = if (hasNextInPlayer) ctrl.getMediaItemAt(currentIndexInPlayer + 1).mediaId else null
        
        if (desiredNext != null) {
            val nextMediaItem = createMediaItem(desiredNext)
            if (nextMediaItem != null) {
                if (currentNextMediaId != desiredNext.trackId) {
                    if (hasNextInPlayer) {
                        android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: removing next item at index ${currentIndexInPlayer + 1} (was $currentNextMediaId)")
                        ctrl.removeMediaItem(currentIndexInPlayer + 1)
                    }
                    android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: adding next item ${desiredNext.trackId} at index ${currentIndexInPlayer + 1}")
                    ctrl.addMediaItem(currentIndexInPlayer + 1, nextMediaItem)
                }
                while (ctrl.mediaItemCount > currentIndexInPlayer + 2) {
                    android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: removing trailing item at index ${currentIndexInPlayer + 2}")
                    ctrl.removeMediaItem(currentIndexInPlayer + 2)
                }
            } else {
                while (ctrl.mediaItemCount > currentIndexInPlayer + 1) {
                    android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: removing trailing item at index ${currentIndexInPlayer + 1}")
                    ctrl.removeMediaItem(currentIndexInPlayer + 1)
                }
            }
        } else {
            while (ctrl.mediaItemCount > currentIndexInPlayer + 1) {
                android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: removing trailing item at index ${currentIndexInPlayer + 1}")
                ctrl.removeMediaItem(currentIndexInPlayer + 1)
            }
        }
        
        val desiredPrev = prev
        val hasPrevInPlayer = currentIndexInPlayer > 0
        val currentPrevMediaId = if (hasPrevInPlayer) ctrl.getMediaItemAt(currentIndexInPlayer - 1).mediaId else null
        
        if (desiredPrev != null) {
            val prevMediaItem = createMediaItem(desiredPrev)
            if (prevMediaItem != null) {
                if (currentPrevMediaId != desiredPrev.trackId) {
                    if (hasPrevInPlayer) {
                        for (i in 0 until currentIndexInPlayer) {
                            android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: removing preceding item at index 0 (was ${ctrl.getMediaItemAt(0).mediaId})")
                            ctrl.removeMediaItem(0)
                        }
                    }
                    android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: adding prev item ${desiredPrev.trackId} at index 0")
                    ctrl.addMediaItem(0, prevMediaItem)
                } else {
                    if (currentIndexInPlayer > 1) {
                        for (i in 0 until currentIndexInPlayer - 1) {
                            android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: removing redundant preceding item at index 0 (was ${ctrl.getMediaItemAt(0).mediaId})")
                            ctrl.removeMediaItem(0)
                        }
                    }
                }
            } else {
                for (i in 0 until currentIndexInPlayer) {
                    android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: removing preceding item at index 0 (was ${ctrl.getMediaItemAt(0).mediaId})")
                    ctrl.removeMediaItem(0)
                }
            }
        } else {
            for (i in 0 until currentIndexInPlayer) {
                android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: removing preceding item at index 0 (was ${ctrl.getMediaItemAt(0).mediaId})")
                ctrl.removeMediaItem(0)
            }
        }

        val targetActiveIndex = if (desiredPrev != null) 1 else 0
        android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: targetActiveIndex=$targetActiveIndex, currentActiveIndex=${ctrl.currentMediaItemIndex}")
        if (ctrl.currentMediaItemIndex != targetActiveIndex || ctrl.playbackState == Player.STATE_ENDED) {
            android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: calling seekTo($targetActiveIndex, 0L) because index differs or state is ENDED")
            ctrl.seekTo(targetActiveIndex, 0L)
        }
        
        android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: END. currentMediaItemIndex=${ctrl.currentMediaItemIndex}, mediaItemCount=${ctrl.mediaItemCount}")
        for (i in 0 until ctrl.mediaItemCount) {
            android.util.Log.d("PlaybackOrchestrator", "syncExoPlayerPlaylist: post-sync ExoPlayer item[$i] = ${ctrl.getMediaItemAt(i).mediaId}")
        }
    }

    private fun createMediaItem(track: QueuedTrack): MediaItem? {
        val uri = track.contentUri ?: return null
        return MediaItem.Builder()
            .setMediaId(track.trackId)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistName)
                    .setAlbumTitle(track.albumTitle)
                    .build(),
            )
            .build()
    }

    private suspend fun handleExternalTrackTransition(trackId: String) {
        val state = queueManager.state.value
        val indexInContext = state.context?.tracks?.indexOfFirst { it.trackId == trackId } ?: -1
        if (indexInContext != -1) {
            queueManager.setContext(
                type = state.context!!.type,
                id = state.context.id,
                tracks = state.context.tracks,
                startIndex = indexInContext
            )
            syncExoPlayerPlaylist()
            saveSnapshot()
        } else {
            val track = repository.getTrackById(trackId)
            if (track != null) {
                val queuedTrack = QueuedTrack(
                    trackId = track.id,
                    title = track.title,
                    artistName = track.artistName,
                    albumTitle = track.albumTitle,
                    contentUri = track.contentUri,
                    durationMs = track.durationMs,
                    coverUri = track.coverUri,
                    source = TrackSource.CONTEXT
                )
                queueManager.setContext(
                    type = "single_track",
                    id = trackId,
                    tracks = listOf(queuedTrack),
                    startIndex = 0
                )
                syncExoPlayerPlaylist()
                saveSnapshot()
            }
        }
    }

    private fun transitionReasonToString(reason: Int): String {
        return when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            else -> "UNKNOWN($reason)"
        }
    }

    private fun playbackStateToString(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
    }
}
