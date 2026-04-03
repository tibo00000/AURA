package com.aura.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aura.music.domain.player.PlaybackOrchestrator
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
 * - Met a jour la progression periodiquement (250ms) pendant la lecture
 * - Sauvegarde periodiquement le snapshot (10s)
 */
class PlayerViewModel(
    private val orchestrator: PlaybackOrchestrator,
) : ViewModel() {

    val uiState: StateFlow<PlayerUiState> = orchestrator.uiState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    init {
        startProgressUpdater()
        startPeriodicSnapshotSave()
    }

    fun onEvent(event: PlayerEvent) {
        orchestrator.onEvent(event)
    }

    /**
     * Retourne la position courante du player.
     */
    fun currentPositionMs(): Long = orchestrator.currentPositionMs()

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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(orchestrator) as T
        }
    }
}
