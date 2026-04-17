package com.aura.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.domain.player.PlaybackOrchestrator
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    val uiState: StateFlow<PlayerUiState> = orchestrator.uiState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    init {
        startProgressUpdater()
        startPeriodicSnapshotSave()
        startLikeStateObserver()
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
     * Bascule le like de la piste courante.
     * Persistance atomique via repository, puis relecture Room pour mise a jour de l'etat.
     * Gouverne par : docs/android/room-schema.md, docs/android/player/states-and-events.md
     */
    private fun handleToggleLike() {
        val currentTrack = orchestrator.uiState.value.currentTrack ?: return
        val currentlyLiked = orchestrator.uiState.value.isCurrentTrackLiked
        val contextType = orchestrator.uiState.value.contextType
        val contextId = orchestrator.uiState.value.contextId

        viewModelScope.launch {
            repository.toggleLike(
                trackId = currentTrack.trackId,
                currentlyLiked = currentlyLiked,
                contextType = contextType,
                contextId = contextId,
            )
            // Relecture Room pour garantir la coherence avec la regle de denormalisation
            val fresh = repository.getTrackById(currentTrack.trackId)
            orchestrator.updateLikedState(fresh?.isLiked ?: !currentlyLiked)
        }
    }

    /**
     * Observe les changements de piste et recharge isLiked depuis Room a chaque transition.
     * Evite d'afficher un coeur incorrect quand on navigue dans la queue.
     */
    private fun startLikeStateObserver() {
        viewModelScope.launch {
            orchestrator.uiState
                .distinctUntilChangedBy { it.currentTrack?.trackId }
                .collect { state ->
                    val trackId = state.currentTrack?.trackId ?: run {
                        orchestrator.updateLikedState(false)
                        return@collect
                    }
                    val fresh = repository.getTrackById(trackId)
                    orchestrator.updateLikedState(fresh?.isLiked ?: false)
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

