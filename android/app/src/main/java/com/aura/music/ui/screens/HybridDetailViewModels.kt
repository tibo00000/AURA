package com.aura.music.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.AlbumDetailResponseData
import com.aura.music.data.network.ArtistDetailResponseData
import com.aura.music.data.repository.ArtistDetail
import com.aura.music.data.repository.AlbumDetail
import com.aura.music.data.repository.EnrichmentRepository
import com.aura.music.data.repository.LocalLibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// ArtistDetailViewModel
// ---------------------------------------------------------------------------

/**
 * UI state for the hybrid Artist detail screen (AND-010).
 *
 * localData is loaded instantly from Room.
 * onlineEnrichment is loaded asynchronously if settings allow it.
 */
data class ArtistDetailUiState(
    /** null = loading, non-null = local data available (or not found) */
    val localData: ArtistDetail? = null,
    val isLocalLoading: Boolean = true,
    val onlineData: ArtistDetailResponseData? = null,
    val isEnrichmentLoading: Boolean = false,
    /** Enrichment was attempted but blocked by network policy */
    val enrichmentBlocked: Boolean = false,
    /** Non-null = backend returned an error (non-blocking) */
    val enrichmentError: String? = null,
)

class ArtistDetailViewModel(
    private val artistId: String,
    private val localRepo: LocalLibraryRepository,
    private val apiService: AuraApiService,
    private val enrichmentRepo: EnrichmentRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistDetailUiState())
    val state = _state.asStateFlow()

    init {
        loadLocal()
    }

    /**
     * Step 1: Load from Room immediately — always called, no network.
     */
    private fun loadLocal() {
        viewModelScope.launch {
            if (artistId.startsWith("art_")) {
                _state.value = _state.value.copy(
                    isLocalLoading = false,
                )
                triggerEnrichmentIfAllowed(null)
            } else {
                val detail = localRepo.getArtistDetail(artistId)
                _state.value = _state.value.copy(
                    localData = detail,
                    isLocalLoading = false,
                )
                triggerEnrichmentIfAllowed(detail)
            }
        }
    }

    fun refreshLocal() {
        loadLocal()
    }

    /**
     * Step 2: Async enrichment — respects AND-009 network governance.
     *
     * First enriches the picture_uri, then, if a backend ID was persisted,
     * fetches the full /artists/{id} payload for top tracks + albums.
     */
    private fun triggerEnrichmentIfAllowed(localDetail: ArtistDetail?) {
        viewModelScope.launch {
            val settings = localRepo.getSettings() ?: return@launch
            if (!isEnrichmentAllowed(settings)) {
                _state.value = _state.value.copy(enrichmentBlocked = true)
                return@launch
            }

            _state.value = _state.value.copy(isEnrichmentLoading = true)

            try {
                if (artistId.startsWith("art_")) {
                    // It's already a backend ID, fetch directly
                    val response = runCatching { apiService.getArtist(artistId) }
                    val onlineDetail = response.getOrNull()?.data
                    _state.value = _state.value.copy(
                        onlineData = onlineDetail,
                        enrichmentError = if (response.isFailure) "Artiste en ligne indisponible." else null,
                    )
                } else {
                    // Enrich artwork (persists in Room automatically)
                    val artistName = localDetail?.summary?.name ?: return@launch
                    enrichmentRepo.enrichArtistArtwork(artistId, artistName)

                    // Reload local after artwork enrichment
                    val refreshed = localRepo.getArtistDetail(artistId)
                    _state.value = _state.value.copy(localData = refreshed)

                    // Try to fetch full online detail if backend ID is known
                    val backendId = enrichmentRepo.getBackendArtistId(artistId)
                    if (backendId != null) {
                        val response = runCatching { apiService.getArtist(backendId) }
                        val onlineDetail = response.getOrNull()?.data
                        _state.value = _state.value.copy(
                            onlineData = onlineDetail,
                            enrichmentError = if (response.isFailure) "Enrichissement indisponible." else null,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    enrichmentError = "Enrichissement indisponible."
                )
            } finally {
                _state.value = _state.value.copy(isEnrichmentLoading = false)
            }
        }
    }

    private fun isEnrichmentAllowed(settings: UserSettingsEntity): Boolean {
        return com.aura.music.data.network.NetworkPolicyChecker.isAllowed(
            onlineSearchEnabled = settings.onlineSearchEnabled,
            policy = settings.onlineSearchNetworkPolicy,
            context = appContext,
        )
    }

    class Factory(
        private val artistId: String,
        private val localRepo: LocalLibraryRepository,
        private val apiService: AuraApiService,
        private val enrichmentRepo: EnrichmentRepository,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ArtistDetailViewModel(artistId, localRepo, apiService, enrichmentRepo, appContext) as T
    }
}

// ---------------------------------------------------------------------------
// AlbumDetailViewModel
// ---------------------------------------------------------------------------

/**
 * UI state for the hybrid Album detail screen (AND-010).
 */
data class AlbumDetailUiState(
    val localData: AlbumDetail? = null,
    val isLocalLoading: Boolean = true,
    val onlineData: AlbumDetailResponseData? = null,
    val isEnrichmentLoading: Boolean = false,
    val enrichmentBlocked: Boolean = false,
    val enrichmentError: String? = null,
)

class AlbumDetailViewModel(
    private val albumId: String,
    private val localRepo: LocalLibraryRepository,
    private val apiService: AuraApiService,
    private val enrichmentRepo: EnrichmentRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailUiState())
    val state = _state.asStateFlow()

    init {
        loadLocal()
    }

    private fun loadLocal() {
        viewModelScope.launch {
            if (albumId.startsWith("alb_")) {
                _state.value = _state.value.copy(
                    isLocalLoading = false,
                )
                triggerEnrichmentIfAllowed(null)
            } else {
                val detail = localRepo.getAlbumDetail(albumId)
                _state.value = _state.value.copy(
                    localData = detail,
                    isLocalLoading = false,
                )
                triggerEnrichmentIfAllowed(detail)
            }
        }
    }

    fun refreshLocal() {
        loadLocal()
    }

    private fun triggerEnrichmentIfAllowed(localDetail: AlbumDetail?) {
        viewModelScope.launch {
            val settings = localRepo.getSettings() ?: return@launch
            if (!isEnrichmentAllowed(settings)) {
                _state.value = _state.value.copy(enrichmentBlocked = true)
                return@launch
            }

            _state.value = _state.value.copy(isEnrichmentLoading = true)

            try {
                if (albumId.startsWith("alb_")) {
                    val response = runCatching { apiService.getAlbum(albumId) }
                    val onlineDetail = response.getOrNull()?.data
                    _state.value = _state.value.copy(
                        onlineData = onlineDetail,
                        enrichmentError = if (response.isFailure) "Album en ligne indisponible." else null,
                    )
                } else {
                    val albumTitle = localDetail?.summary?.title ?: return@launch
                    val artistName = localDetail.summary.artistName

                    // Enrich artwork (persists in Room automatically)
                    enrichmentRepo.enrichAlbumArtwork(albumId, albumTitle, artistName)

                    // Reload local after artwork enrichment
                    val refreshed = localRepo.getAlbumDetail(albumId)
                    _state.value = _state.value.copy(localData = refreshed)

                    // Try to fetch full online detail if backend ID is known
                    val backendId = enrichmentRepo.getBackendAlbumId(albumId)
                    if (backendId != null) {
                        val response = runCatching { apiService.getAlbum(backendId) }
                        val onlineDetail = response.getOrNull()?.data
                        _state.value = _state.value.copy(
                            onlineData = onlineDetail,
                            enrichmentError = if (response.isFailure) "Enrichissement indisponible." else null,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    enrichmentError = "Enrichissement indisponible."
                )
            } finally {
                _state.value = _state.value.copy(isEnrichmentLoading = false)
            }
        }
    }

    private fun isEnrichmentAllowed(settings: UserSettingsEntity): Boolean {
        return com.aura.music.data.network.NetworkPolicyChecker.isAllowed(
            onlineSearchEnabled = settings.onlineSearchEnabled,
            policy = settings.onlineSearchNetworkPolicy,
            context = appContext,
        )
    }

    class Factory(
        private val albumId: String,
        private val localRepo: LocalLibraryRepository,
        private val apiService: AuraApiService,
        private val enrichmentRepo: EnrichmentRepository,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AlbumDetailViewModel(albumId, localRepo, apiService, enrichmentRepo, appContext) as T
    }
}
