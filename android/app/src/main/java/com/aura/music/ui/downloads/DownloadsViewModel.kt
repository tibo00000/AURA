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
    val selectedTab: String = "En attente",
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
    private val userToken: String
) : ViewModel() {

    private val _selectedTab = MutableStateFlow("En attente")
    val selectedTab = _selectedTab.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DownloadsUiState> = combine(
        _selectedTab,
        downloadRepository.getAllJobsWithTrack(),
        _isSyncing,
        _errorMessage
    ) { tab, allJobs, isSyncing, errorMsg ->
        val queued = allJobs.filter { it.status == "queued" }
        val running = allJobs.filter { it.status == "running" }
        val succeeded = allJobs.filter { it.status == "succeeded" }
        val failed = allJobs.filter { it.status == "failed" || it.status == "cancelled" }

        val filteredJobs = when (tab) {
            "En attente" -> queued
            "En cours" -> running
            "Terminés" -> succeeded
            else -> failed
        }

        DownloadsUiState(
            selectedTab = tab,
            jobs = filteredJobs,
            queuedCount = queued.size,
            runningCount = running.size,
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

    init {
        // Automatically sync active states and spin up polling loop
        viewModelScope.launch {
            downloadRepository.syncActiveJobs(userToken)
            downloadRepository.startPolling(userToken)
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

    fun dismissError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Gracefully release polling loops
        downloadRepository.stopPolling()
    }

    class Factory(
        private val downloadRepository: DownloadRepository,
        private val userToken: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DownloadsViewModel(downloadRepository, userToken) as T
    }
}
