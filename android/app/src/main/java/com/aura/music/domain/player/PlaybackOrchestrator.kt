package com.aura.music.domain.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aura.music.data.player.PlaybackStateStore
import com.aura.music.data.player.QueueManager
import com.aura.music.data.repository.LocalLibraryRepository
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
            syncUiState()
            if (playbackState == Player.STATE_ENDED) {
                handleTrackEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncUiState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncUiState()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
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
            is PlayerEvent.ToggleShuffle -> handleToggleShuffle()
            is PlayerEvent.CycleRepeatMode -> handleCycleRepeatMode()
        }
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
        val nextTrack = queueManager.next() ?: run {
            controller?.stop()
            _uiState.update { it.copy(playbackState = PlaybackState.Idle, currentTrack = null) }
            saveSnapshot()
            return
        }
        playTrackOnController(nextTrack)
        saveSnapshot()
    }

    private fun handlePrevious() {
        val positionMs = controller?.currentPosition ?: 0L
        val previousTrack = queueManager.previous(positionMs) ?: return
        if (positionMs > QueueManager.RESTART_THRESHOLD_MS) {
            controller?.seekTo(0)
        } else {
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
        syncUiState()
    }

    private fun handleRemoveFromQueue(index: Int) {
        queueManager.removeFromQueue(index)
        syncUiState()
    }

    private fun handleReorderQueue(fromIndex: Int, toIndex: Int) {
        queueManager.reorderQueue(fromIndex, toIndex)
        syncUiState()
    }

    private fun handleToggleShuffle() {
        queueManager.toggleShuffle()
        syncUiState()
        saveSnapshot()
    }

    private fun handleCycleRepeatMode() {
        queueManager.cycleRepeatMode()
        syncUiState()
        saveSnapshot()
    }

    private fun handleTrackEnded() {
        handleNext()
    }

    private fun playTrackOnController(track: QueuedTrack) {
        val ctrl = controller ?: return
        val uri = track.contentUri ?: return
        val mediaItem = MediaItem.Builder()
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

        ctrl.setMediaItem(mediaItem)
        ctrl.prepare()
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

        _uiState.update {
            PlayerUiState(
                playbackState = playbackState,
                currentTrack = queueState.currentTrack,
                positionMs = ctrl?.currentPosition ?: 0L,
                durationMs = ctrl?.duration?.coerceAtLeast(0L) ?: 0L,
                shuffleEnabled = queueState.shuffleEnabled,
                repeatMode = queueState.repeatMode,
                priorityQueue = queueState.priorityQueue,
                contextType = queueState.context?.type,
                contextId = queueState.context?.id,
                errorMessage = ctrl?.playerError?.localizedMessage,
            )
        }
    }

    private suspend fun restoreSnapshot() {
        val snapshot = stateStore.restore() ?: return
        queueManager.restoreModes(snapshot.shuffleEnabled, snapshot.repeatMode)
        
        snapshot.currentTrackId?.let { trackId ->
            val trackRow = repository.getTrackById(trackId)
            if (trackRow != null) {
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
                queueManager.setContext(
                    type = snapshot.contextType ?: "single_track",
                    id = snapshot.contextId ?: trackRow.id,
                    tracks = listOf(queuedTrack),
                    startIndex = 0
                )
                val ctrl = controller
                val uri = queuedTrack.contentUri
                if (ctrl != null && uri != null) {
                    val mediaItem = MediaItem.Builder()
                        .setMediaId(queuedTrack.trackId)
                        .setUri(Uri.parse(uri))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(queuedTrack.title)
                                .setArtist(queuedTrack.artistName)
                                .setAlbumTitle(queuedTrack.albumTitle)
                                .build(),
                        )
                        .build()

                    ctrl.setMediaItem(mediaItem)
                    ctrl.prepare()
                    ctrl.seekTo(snapshot.positionMs)
                }
            }
        }
        
        syncUiState()
    }
}
