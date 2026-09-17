package com.aura.music.data.repository

import android.util.Log
import com.aura.music.data.network.BuildConfig
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.DiscoveryFeedResponseData
import com.aura.music.data.network.DiscoveryItemResponseData
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.TrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DiscoveryFeedState {
    data object Initial : DiscoveryFeedState
    data object Loading : DiscoveryFeedState
    data class Success(
        val items: List<DiscoveryItemResponseData>,
        val batchId: String?,
        val isStale: Boolean
    ) : DiscoveryFeedState
    data class Error(val message: String) : DiscoveryFeedState
}

class DiscoveryRepository(
    private val apiService: AuraApiService,
    private val tokenProvider: () -> String,
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DiscoveryRepository"
    }

    private val _feedState = MutableStateFlow<DiscoveryFeedState>(DiscoveryFeedState.Initial)
    val feedState: StateFlow<DiscoveryFeedState> = _feedState.asStateFlow()

    private val seenItemIds = mutableSetOf<String>()

    /**
     * Charge le fil de découvertes depuis l'API.
     * Si [forceGenerate] est vrai, force la génération d'un nouveau lot sur le serveur.
     */
    suspend fun refresh(forceGenerate: Boolean = false) = withContext(Dispatchers.IO) {
        val token = tokenProvider().ifBlank { return@withContext }
        _feedState.value = DiscoveryFeedState.Loading

        try {
            if (forceGenerate) {
                Log.d(TAG, "Génération d'un nouveau lot de découvertes demandée...")
                val genResp = apiService.generateDiscoveryBatch(token)
                val genError = genResp.error
                if (genError != null) {
                    Log.w(TAG, "Erreur génération batch: ${genError.message}")
                }
            }

            val feedResp = apiService.getDiscoveryFeed(token)
            val feedError = feedResp.error
            if (feedError != null) {
                _feedState.value = DiscoveryFeedState.Error(feedError.message)
                return@withContext
            }

            val data = feedResp.data
            if (data == null || data.items.isEmpty()) {
                // Si le fil est vide et qu'on n'a pas encore tenté de générer, on déclenche une génération
                if (!forceGenerate) {
                    Log.d(TAG, "Fil de découverte vide, tentative de génération initiale...")
                    apiService.generateDiscoveryBatch(token)
                    val retryResp = apiService.getDiscoveryFeed(token)
                    val retryData = retryResp.data
                    if (retryData != null && retryData.items.isNotEmpty()) {
                        _feedState.value = DiscoveryFeedState.Success(
                            items = retryData.items,
                            batchId = retryData.batchId,
                            isStale = retryData.isStale
                        )
                        return@withContext
                    }
                }
                _feedState.value = DiscoveryFeedState.Success(
                    items = emptyList(),
                    batchId = null,
                    isStale = true
                )
            } else {
                _feedState.value = DiscoveryFeedState.Success(
                    items = data.items,
                    batchId = data.batchId,
                    isStale = data.isStale
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Échec du chargement du fil de découverte", e)
            _feedState.value = DiscoveryFeedState.Error(e.message ?: "Erreur réseau inconnue")
        }
    }

    /**
     * Marque un lot d'éléments comme affichés à l'écran (feedback 'seen').
     */
    fun markItemsSeen(items: List<DiscoveryItemResponseData>) {
        val newItems = items.filter { !seenItemIds.contains(it.id) }
        if (newItems.isEmpty()) return

        newItems.forEach { seenItemIds.add(it.id) }
        repositoryScope.launch {
            val token = tokenProvider().ifBlank { return@launch }
            for (item in newItems) {
                try {
                    apiService.sendDiscoveryFeedback(token, item.id, "seen")
                } catch (e: Exception) {
                    Log.d(TAG, "Erreur envoi feedback seen pour ${item.id}: ${e.message}")
                }
            }
        }
    }

    /**
     * Envoie une action de feedback (played, completed, skipped, liked, etc.).
     */
    fun recordFeedback(itemId: String, action: String) {
        repositoryScope.launch {
            val token = tokenProvider().ifBlank { return@launch }
            try {
                apiService.sendDiscoveryFeedback(token, itemId, action)
                Log.d(TAG, "Feedback '$action' envoyé pour l'item $itemId")
            } catch (e: Exception) {
                Log.w(TAG, "Échec envoi feedback '$action' pour $itemId: ${e.message}")
            }
        }
    }

    /**
     * Convertit un élément de découverte en QueuedTrack pour la lecture.
     */
    fun toQueuedTrack(item: DiscoveryItemResponseData): QueuedTrack {
        val effectiveTrackId = item.auraTrackId?.ifBlank { null } ?: "trk_deezer_${item.deezerTrackId}"
        val effectiveUri = if (item.downloadStatus == "ready" && !item.auraTrackId.isNullOrBlank()) {
            "${BuildConfig.API_BASE_URL.trimEnd('/')}/me/sync/files/${item.auraTrackId}"
        } else {
            item.previewUrl
        }
        return QueuedTrack(
            trackId = effectiveTrackId,
            title = item.trackTitle,
            artistName = item.artistName,
            albumTitle = item.albumTitle,
            contentUri = effectiveUri,
            durationMs = item.durationMs,
            coverUri = item.coverUrl,
            source = TrackSource.CONTEXT
        )
    }
}
