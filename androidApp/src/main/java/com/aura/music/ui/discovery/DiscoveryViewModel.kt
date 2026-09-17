package com.aura.music.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aura.music.data.network.DiscoveryItemResponseData
import com.aura.music.data.repository.DiscoveryFeedState
import com.aura.music.data.repository.DiscoveryRepository
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.domain.player.PlaybackOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val isLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val items: List<DiscoveryItemResponseData> = emptyList(),
    val batchId: String? = null,
    val isStale: Boolean = false,
    val errorMessage: String? = null,
    val likedTrackIds: Set<String> = emptySet(),
    val currentPlayingTrackId: String? = null,
)

class DiscoveryViewModel(
    private val discoveryRepository: DiscoveryRepository,
    private val localLibraryRepository: LocalLibraryRepository,
    private val playbackOrchestrator: PlaybackOrchestrator,
) : ViewModel() {

    private val _isGenerating = MutableStateFlow(false)
    private val _likedTrackIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<DiscoveryUiState> = combine(
        discoveryRepository.feedState,
        _isGenerating,
        _likedTrackIds,
        playbackOrchestrator.uiState
    ) { feedState, isGenerating, likedIds, playerState ->
        when (feedState) {
            is DiscoveryFeedState.Initial,
            is DiscoveryFeedState.Loading -> {
                DiscoveryUiState(
                    isLoading = true,
                    isGenerating = isGenerating,
                    likedTrackIds = likedIds,
                    currentPlayingTrackId = playerState.currentTrack?.trackId
                )
            }
            is DiscoveryFeedState.Success -> {
                DiscoveryUiState(
                    isLoading = false,
                    isGenerating = isGenerating,
                    items = feedState.items,
                    batchId = feedState.batchId,
                    isStale = feedState.isStale,
                    likedTrackIds = likedIds,
                    currentPlayingTrackId = playerState.currentTrack?.trackId
                )
            }
            is DiscoveryFeedState.Error -> {
                DiscoveryUiState(
                    isLoading = false,
                    isGenerating = isGenerating,
                    errorMessage = feedState.message,
                    likedTrackIds = likedIds,
                    currentPlayingTrackId = playerState.currentTrack?.trackId
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DiscoveryUiState(isLoading = true)
    )

    init {
        loadFeed(forceGenerate = false)
        syncLikedStatus()
    }

    fun loadFeed(forceGenerate: Boolean = false) {
        viewModelScope.launch {
            if (forceGenerate) {
                _isGenerating.value = true
            }
            try {
                discoveryRepository.refresh(forceGenerate = forceGenerate)
                syncLikedStatus()
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun syncLikedStatus() {
        viewModelScope.launch {
            try {
                val likedTracks = localLibraryRepository.getLikedTracks()
                val likedSet = likedTracks.map { it.id }.toSet()
                _likedTrackIds.value = likedSet
            } catch (_: Exception) {
            }
        }
    }

    fun markSeen(items: List<DiscoveryItemResponseData>) {
        discoveryRepository.markItemsSeen(items)
    }

    fun playMix(startIndex: Int = 0) {
        val currentItems = (discoveryRepository.feedState.value as? DiscoveryFeedState.Success)?.items
            ?: uiState.value.items
        if (currentItems.isEmpty()) return

        val queuedTracks = currentItems.map { discoveryRepository.toQueuedTrack(it) }
        val targetIndex = startIndex.coerceIn(0, queuedTracks.lastIndex)
        val selectedItem = currentItems[targetIndex]
        val selectedTrack = queuedTracks[targetIndex]

        viewModelScope.launch {
            // Démarre la lecture dans le contexte de découverte
            playbackOrchestrator.handlePlay(
                trackId = selectedTrack.trackId,
                contextType = "discovery",
                contextId = uiState.value.batchId ?: "discovery_mix",
                contextTracks = queuedTracks,
                startIndex = targetIndex
            )
            // Envoi du feedback played
            discoveryRepository.recordFeedback(selectedItem.id, "played")
        }
    }

    fun toggleLike(item: DiscoveryItemResponseData) {
        val effectiveTrackId = item.auraTrackId?.ifBlank { null } ?: "trk_deezer_${item.deezerTrackId}"
        val isCurrentlyLiked = _likedTrackIds.value.contains(effectiveTrackId)

        viewModelScope.launch {
            // Optimistic update
            _likedTrackIds.update { current ->
                if (isCurrentlyLiked) current - effectiveTrackId else current + effectiveTrackId
            }

            try {
                localLibraryRepository.setTrackLiked(
                    trackId = effectiveTrackId,
                    currentlyLiked = isCurrentlyLiked,
                    contextType = "discovery",
                    contextId = uiState.value.batchId,
                    title = item.trackTitle,
                    artistName = item.artistName,
                    albumTitle = item.albumTitle,
                    durationMs = item.durationMs,
                    coverUri = item.coverUrl,
                )

                if (!isCurrentlyLiked) {
                    discoveryRepository.recordFeedback(item.id, "liked")
                }
            } catch (e: Exception) {
                // Rollback en cas d'erreur
                _likedTrackIds.update { current ->
                    if (isCurrentlyLiked) current + effectiveTrackId else current - effectiveTrackId
                }
            }
        }
    }

    class Factory(
        private val discoveryRepository: DiscoveryRepository,
        private val localLibraryRepository: LocalLibraryRepository,
        private val playbackOrchestrator: PlaybackOrchestrator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DiscoveryViewModel(
                discoveryRepository = discoveryRepository,
                localLibraryRepository = localLibraryRepository,
                playbackOrchestrator = playbackOrchestrator,
            ) as T
        }
    }
}
