package com.aura.music.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aura.music.data.local.DownloadJobRowModel
import com.aura.music.data.repository.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI State for the Downloads Screen.
 * Tracks current active filters and count badges for each tab.
 */
data class DownloadsUiState(
    val selectedTab: String = "En cours",
    val jobs: List<DownloadJobRowModel> = emptyList(),
    val queuedCount: Int = 0,
    val runningCount: Int = 0,
    val succeededCount: Int = 0,
    val failedCount: Int = 0,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null
)

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

    private val _candidates = MutableStateFlow<Map<String, List<com.aura.music.data.network.YtmCandidateDto>>>(emptyMap())
    val candidates = _candidates.asStateFlow()

    private val _selectedErrorJob = MutableStateFlow<DownloadJobRowModel?>(null)
    val selectedErrorJob = _selectedErrorJob.asStateFlow()

    val uiState: StateFlow<DownloadsUiState> = combine(
        _selectedTab,
        downloadRepository.getAllJobsWithTrack(),
        _isSyncing,
        _errorMessage
    ) { tab, allJobs, isSyncing, errorMsg ->
        val active = allJobs.filter { it.status == "queued" || it.status == "requires_resolution" || it.status == "running" }
        val succeeded = allJobs.filter { it.status == "succeeded" }
        val failed = allJobs.filter { it.status == "failed" || it.status == "cancelled" }

        val filteredJobs = when (tab) {
            "En cours" -> active
            "Terminés" -> succeeded
            else -> failed
        }

        DownloadsUiState(
            selectedTab = tab,
            jobs = filteredJobs,
            queuedCount = active.size,
            runningCount = 0,
            succeededCount = succeeded.size,
            failedCount = failed.size,
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
            val jobCandidates = downloadRepository.getCandidatesForJob(jobId, userToken)
            if (jobCandidates != null) {
                _candidates.value = _candidates.value.toMutableMap().apply {
                    put(jobId, jobCandidates)
                }
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
            } catch (e: Exception) {
                // Ignore
            }
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
