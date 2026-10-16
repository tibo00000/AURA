package com.aura.music.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aura.music.data.local.DownloadJobRowModel
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.DownloadRepository
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.toQueuedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return "0 Mo"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1000.0) {
        val gb = mb / 1024.0
        String.format(java.util.Locale.US, "%.1f Go", gb)
    } else {
        String.format(java.util.Locale.US, "%.1f Mo", mb)
    }
}

/**
 * UI State for the Downloads Screen.
 * Tracks current active filters, storage used, and count badges for each tab.
 */
data class DownloadsUiState(
    val selectedTab: String = "En cours",
    val jobs: List<DownloadJobRowModel> = emptyList(),
    val queuedCount: Int = 0,
    val runningCount: Int = 0,
    val succeededCount: Int = 0,
    val failedCount: Int = 0,
    val totalStorageBytes: Long = 0L,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null
) {
    val formattedStorageSize: String get() = formatStorageSize(totalStorageBytes)
}

/**
 * DownloadsViewModel to handle reactive download lists, counts and background synchronization/polling actions.
 * Uses DownloadJobRowModel to stream complete track titles and artist names instantly.
 */
class DownloadsViewModel(
    private val downloadRepository: DownloadRepository,
    private val tokenProvider: () -> String
) : ViewModel() {

    constructor(
        downloadRepository: DownloadRepository,
        userToken: String
    ) : this(downloadRepository, { userToken })

    private val userToken: String get() = tokenProvider()

    private val _selectedTab = MutableStateFlow("En cours")
    val selectedTab = _selectedTab.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _storageBytes = MutableStateFlow(0L)

    private val _candidates = MutableStateFlow<Map<String, List<com.aura.music.data.network.YtmCandidateDto>>>(emptyMap())
    val candidates = _candidates.asStateFlow()

    private val _resolutionErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val resolutionErrorEvents = _resolutionErrorEvents.asSharedFlow()

    private val _selectedErrorJob = MutableStateFlow<DownloadJobRowModel?>(null)
    val selectedErrorJob = _selectedErrorJob.asStateFlow()

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadRepository.getAllJobsWithTrack(),
        _isSyncing,
        _errorMessage
    ) { allJobs, isSyncing, errorMsg ->
        val sortedJobs = allJobs.sortedWith(
            compareByDescending<DownloadJobRowModel> { it.createdAt }
                .thenByDescending { it.updatedAt }
        )
        val active = allJobs.filter { it.status == "queued" || it.status == "requires_resolution" || it.status == "running" }
        val succeeded = allJobs.filter { it.status == "succeeded" }
        val failed = allJobs.filter { it.status == "failed" || it.status == "cancelled" }

        DownloadsUiState(
            selectedTab = "Tous",
            jobs = sortedJobs,
            queuedCount = active.size,
            runningCount = 0,
            succeededCount = succeeded.size,
            failedCount = failed.size,
            totalStorageBytes = 0L,
            isSyncing = isSyncing,
            errorMessage = errorMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DownloadsUiState()
    )

    fun loadCandidatesForJob(jobId: String) {
        viewModelScope.launch {
            // Verify job is still valid and in requires_resolution state
            val localJob = downloadRepository.getJobById(jobId)
            if (localJob == null || localJob.status != "requires_resolution") {
                _resolutionErrorEvents.tryEmit("Ce téléchargement n'est plus en attente de choix.")
                _candidates.value = _candidates.value.toMutableMap().apply {
                    remove(jobId)
                }
                return@launch
            }

            val jobCandidates = downloadRepository.getCandidatesForJob(jobId, userToken)
            if (jobCandidates != null) {
                _candidates.value = _candidates.value.toMutableMap().apply {
                    put(jobId, jobCandidates)
                }
            } else {
                _resolutionErrorEvents.tryEmit("Impossible de charger les propositions YouTube Music.")
            }
        }
    }

    fun resolveJob(jobId: String, videoId: String) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                downloadRepository.resolveJob(jobId, videoId, userToken)
                _candidates.value = _candidates.value.toMutableMap().apply {
                    remove(jobId)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Impossible de résoudre le téléchargement."
            }
        }
    }

    fun inspectError(job: DownloadJobRowModel?) {
        _selectedErrorJob.value = job
    }

    fun refreshStorageSize() {
        // Storage display removed per user preference
    }

    init {
        // Automatically sync active states on screen init
        viewModelScope.launch {
            downloadRepository.syncActiveJobs(userToken)
        }
    }

    fun selectTab(tab: String) {
        _selectedTab.value = tab
    }

    /**
     * Force synchronization with backend for user downloads history.
     */
    fun forceRefresh() {
        viewModelScope.launch {
            _isSyncing.value = true
            _errorMessage.value = null
            try {
                downloadRepository.syncActiveJobs(userToken)
            } catch (e: Exception) {
                _errorMessage.value = "Erreur de rafraîchissement réseau."
            } finally {
                _isSyncing.value = false
            }
        }
        refreshStorageSize()
    }

    /**
     * Trigger retry action on backend.
     */
    fun retryDownload(jobId: String) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                downloadRepository.retryJob(jobId, userToken)
            } catch (e: Exception) {
                _errorMessage.value = "Impossible de relancer le téléchargement."
            }
        }
    }

    /**
     * Clear all download jobs from local database.
     */
    fun clearAllJobs() {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                downloadRepository.clearAllJobs()
                refreshStorageSize()
            } catch (e: Exception) {
                _errorMessage.value = "Erreur lors de la suppression de la file d'attente."
            }
        }
    }

    /**
     * Delete a single download job by ID.
     */
    fun deleteJob(jobId: String) {
        viewModelScope.launch {
            try {
                downloadRepository.deleteJob(jobId)
                refreshStorageSize()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Play all downloaded tracks in random or sequential order.
     */
    fun playAll(playerViewModel: PlayerViewModel, shuffle: Boolean = true) {
        viewModelScope.launch {
            val tracks = downloadRepository.getDownloadedTracks()
            if (tracks.isEmpty()) return@launch
            val listToPlay = if (shuffle) tracks.shuffled() else tracks
            val queuedTracks = withContext(Dispatchers.Default) {
                listToPlay.map { it.toQueuedTrack() }
            }
            playerViewModel.onEvent(
                PlayerEvent.PlayTrack(
                    trackId = listToPlay.first().id,
                    contextType = "downloads",
                    contextId = "downloads",
                    contextTracks = queuedTracks,
                    startIndex = 0
                )
            )
        }
    }

    /**
     * Play a single track within the full downloaded tracks context.
     */
    fun playJob(job: DownloadJobRowModel, playerViewModel: PlayerViewModel) {
        viewModelScope.launch {
            val tracks = downloadRepository.getDownloadedTracks()
            val index = tracks.indexOfFirst { it.id == job.trackId }
            val finalTracks = if (index >= 0) tracks else {
                listOf(
                    TrackListRow(
                        id = job.trackId,
                        artistId = null,
                        albumId = null,
                        title = job.title,
                        artistName = job.artistName,
                        albumTitle = null,
                        contentUri = null,
                        durationMs = null,
                        coverUri = job.coverUri,
                        isLiked = false
                    )
                )
            }
            val targetIndex = if (index >= 0) index else 0
            val queuedTracks = withContext(Dispatchers.Default) {
                finalTracks.map { it.toQueuedTrack() }
            }
            playerViewModel.onEvent(
                PlayerEvent.PlayTrack(
                    trackId = job.trackId,
                    contextType = "downloads",
                    contextId = "downloads",
                    contextTracks = queuedTracks,
                    startIndex = targetIndex
                )
            )
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    class Factory(
        private val downloadRepository: DownloadRepository,
        private val tokenProvider: () -> String
    ) : ViewModelProvider.Factory {

        constructor(
            downloadRepository: DownloadRepository,
            userToken: String
        ) : this(downloadRepository, { userToken })

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DownloadsViewModel(downloadRepository, tokenProvider) as T
    }
}
