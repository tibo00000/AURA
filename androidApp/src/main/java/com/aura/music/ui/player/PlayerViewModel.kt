package com.aura.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.AddToPlaylistResult
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.domain.player.PlaybackOrchestrator
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.PlayerUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PlaylistUiEvent {
    data class ShowDuplicatePrompt(val trackId: String, val playlistId: String, val playlistName: String) : PlaylistUiEvent
    data class ShowSnackbar(val message: String) : PlaylistUiEvent
}

/**
 * Transforme l'etat metier du player en etat ecran.
 *
 * Gouverne par :
 * - docs/android/player/architecture.md
 * - docs/android/app-architecture.md
 *
 * Responsabilites :
 * - Expose StateFlow<PlayerUiState>
 * - Recoit les PlayerEvent de la couche UI et delegue a l'orchestrateur
 * - Gere ToggleLike : persistance Room + mise a jour de isCurrentTrackLiked
 * - Recharge isCurrentTrackLiked a chaque changement de piste
 * - Met a jour la progression periodiquement (250ms) pendant la lecture
 * - Sauvegarde periodiquement le snapshot (10s)
 */
class PlayerViewModel(
    private val orchestrator: PlaybackOrchestrator,
    private val repository: LocalLibraryRepository,
) : ViewModel() {

    private val _playlistEvents = Channel<PlaylistUiEvent>(Channel.BUFFERED)
    val playlistEvents: Flow<PlaylistUiEvent> = _playlistEvents.receiveAsFlow()

    private val _togglingTrackIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<PlayerUiState> = orchestrator.uiState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    val staticUiState: StateFlow<PlayerUiState> = orchestrator.uiState
        .distinctUntilChanged { old, new ->
            old.currentTrack?.trackId == new.currentTrack?.trackId &&
            old.playbackState == new.playbackState &&
            old.shuffleEnabled == new.shuffleEnabled &&
            old.repeatMode == new.repeatMode &&
            old.priorityQueue == new.priorityQueue &&
            old.mainQueueTracks == new.mainQueueTracks &&
            old.contextType == new.contextType &&
            old.contextId == new.contextId &&
            old.errorMessage == new.errorMessage &&
            old.isCurrentTrackLiked == new.isCurrentTrackLiked &&
            old.sleepTimerRemainingSeconds == new.sleepTimerRemainingSeconds &&
            old.isSleepTimerEndOfTrack == new.isSleepTimerEndOfTrack
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    init {
        startProgressUpdater()
        startPeriodicSnapshotSave()
        observeCurrentTrackLike()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCurrentTrackLike() {
        viewModelScope.launch {
            orchestrator.uiState
                .map { it.currentTrack?.trackId }
                .distinctUntilChanged()
                .flatMapLatest { trackId ->
                    if (trackId == null) flowOf(false)
                    else repository.isLikedFlow(trackId)
                }
                .collect { isLiked ->
                    orchestrator.updateLikedState(isLiked)
                }
        }
    }

    fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.ToggleLike -> handleToggleLike()
            else -> orchestrator.onEvent(event)
        }
    }

    /**
     * Retourne la position courante du player.
     */
    fun currentPositionMs(): Long = orchestrator.currentPositionMs()

    /**
     * Récupère un morceau de la bibliothèque locale par son ID.
     */
    suspend fun getTrackById(trackId: String): TrackListRow? = repository.getTrackById(trackId)

    /**
     * Récupère toutes les listes de lecture locales.
     */
    suspend fun getPlaylists(): List<PlaylistListRow> = repository.getPlaylists()

    /**
     * Ajoute un morceau à une liste de lecture.
     */
    fun addTrackToPlaylist(playlistId: String, trackId: String, allowDuplicate: Boolean = false) {
        viewModelScope.launch {
            val currentTrack = orchestrator.uiState.value.currentTrack
            val title = if (currentTrack?.trackId == trackId) currentTrack.title else null
            val artistName = if (currentTrack?.trackId == trackId) currentTrack.artistName else null
            val albumTitle = if (currentTrack?.trackId == trackId) currentTrack.albumTitle else null
            val durationMs = if (currentTrack?.trackId == trackId) currentTrack.durationMs else null
            val coverUri = if (currentTrack?.trackId == trackId) currentTrack.coverUri?.toString() else null

            when (val result = repository.addTrackToPlaylist(
                playlistId = playlistId,
                trackId = trackId,
                contextType = "player",
                allowDuplicate = allowDuplicate,
                title = title,
                artistName = artistName,
                albumTitle = albumTitle,
                durationMs = durationMs,
                coverUri = coverUri,
            )) {
                is AddToPlaylistResult.Success -> {
                    _playlistEvents.send(PlaylistUiEvent.ShowSnackbar("Ajouté à ${result.playlistName}"))
                }
                is AddToPlaylistResult.AlreadyExists -> {
                    _playlistEvents.send(
                        PlaylistUiEvent.ShowDuplicatePrompt(
                            trackId = result.trackId,
                            playlistId = result.playlistId,
                            playlistName = result.playlistName,
                        )
                    )
                }
                is AddToPlaylistResult.Error -> {
                    _playlistEvents.send(PlaylistUiEvent.ShowSnackbar(result.message))
                }
            }
        }
    }

    /**
     * Bascule le like de la piste courante.
     * Persistance atomique via repository avec garantie d'existence de la piste (stub si cloud/distant).
     * Gouverne par : docs/android/room-schema.md, docs/android/player/states-and-events.md
     */
    private fun handleToggleLike() {
        val currentTrack = orchestrator.uiState.value.currentTrack ?: return
        val trackId = currentTrack.trackId
        if (_togglingTrackIds.value.contains(trackId)) return

        val currentlyLiked = orchestrator.uiState.value.isCurrentTrackLiked
        val contextType = orchestrator.uiState.value.contextType
        val contextId = orchestrator.uiState.value.contextId

        _togglingTrackIds.update { it + trackId }
        // Mise à jour optimiste immédiate (0 ms)
        orchestrator.updateLikedState(!currentlyLiked)

        viewModelScope.launch {
            try {
                repository.toggleLike(
                    trackId = trackId,
                    currentlyLiked = currentlyLiked,
                    contextType = contextType,
                    contextId = contextId,
                    title = currentTrack.title,
                    artistName = currentTrack.artistName,
                    albumTitle = currentTrack.albumTitle,
                    durationMs = currentTrack.durationMs,
                    coverUri = currentTrack.coverUri?.toString(),
                )
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "Error toggling like for $trackId", e)
                orchestrator.updateLikedState(currentlyLiked)
            } finally {
                _togglingTrackIds.update { it - trackId }
            }
        }
    }

    private fun startProgressUpdater() {
        viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_UPDATE_INTERVAL_MS)
                val current = orchestrator.uiState.value
                if (current.playbackState == PlaybackState.Playing) {
                    // Met a jour uniquement la position dans le uiState
                    // sans toucher au controller (pas de seekTo)
                    orchestrator.refreshPosition()
                }
            }
        }
    }

    private fun startPeriodicSnapshotSave() {
        viewModelScope.launch {
            while (isActive) {
                delay(SNAPSHOT_SAVE_INTERVAL_MS)
                val current = orchestrator.uiState.value
                if (current.playbackState == PlaybackState.Playing ||
                    current.playbackState == PlaybackState.Paused
                ) {
                    orchestrator.saveSnapshot()
                }
            }
        }
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L
        private const val SNAPSHOT_SAVE_INTERVAL_MS = 10_000L
    }

    class Factory(
        private val orchestrator: PlaybackOrchestrator,
        private val repository: LocalLibraryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(orchestrator, repository) as T
        }
    }
}

