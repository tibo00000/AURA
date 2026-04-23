package com.aura.music.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.SearchRepository
import com.aura.music.data.repository.HybridSearchResult
import com.aura.music.data.repository.BestMatchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI State for the Search screen.
 */
data class SearchUiState(
    val query: String = "",
    val isLoadingFullSearch: Boolean = false,
    val currentFullSearchResult: HybridSearchResult? = null,
    val localSuggestions: HybridSearchResult? = null, // Used during typing (3+ chars)
    val recentQueries: List<String> = emptyList(), // Recent searches shown at startup
    val showLocalSuggestionsDropdown: Boolean = false,
    val errorMessage: String? = null
) {
    /**
     * Determine which result to display:
     * - During typing with 3+ chars: show local suggestions
     * - After submit/validation: show full hybrid results
     */
    val displayResult: HybridSearchResult?
        get() = currentFullSearchResult ?: localSuggestions

    /**
     * Is the search complete and showing results
     */
    val isSearchComplete: Boolean
        get() = currentFullSearchResult != null

    /**
     * Should show the suggestions dropdown
     */
    val shouldShowSuggestions: Boolean
        get() = !isSearchComplete && showLocalSuggestionsDropdown && query.length >= 3
}

/**
 * ViewModel for the Search screen.
 * Manages query state, local suggestions, and hybrid search orchestration.
 */
class SearchViewModel(
    private val searchRepository: SearchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        // Load recent queries on startup
        loadRecentQueries()
    }

    /**
     * Load recent queries and display them.
     */
    private fun loadRecentQueries() {
        viewModelScope.launch {
            try {
                val recentList = withContext(Dispatchers.IO) {
                    searchRepository.getRecentQueries(10)
                }
                _uiState.update { it.copy(recentQueries = recentList) }
            } catch (e: Exception) {
                // Silent failure - no recent queries available
            }
        }
    }

    /**
     * Update the search query and refresh suggestions if 3+ characters.
     */
    fun updateQuery(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }

        if (newQuery.length >= 3) {
            loadLocalSuggestions(newQuery)
            _uiState.update { it.copy(showLocalSuggestionsDropdown = true) }
        } else {
            _uiState.update {
                it.copy(
                    showLocalSuggestionsDropdown = false,
                    localSuggestions = null
                )
            }
        }
    }

    /**
     * Clear the search query and reset state.
     */
    fun clearQuery() {
        _uiState.update {
            it.copy(
                query = "",
                showLocalSuggestionsDropdown = false,
                localSuggestions = null,
                currentFullSearchResult = null,
                errorMessage = null
            )
        }
    }

    /**
     * Perform a full hybrid search (local + online).
     * Called on keyboard validation or search button press.
     */
    fun submitSearch() {
        val query = _uiState.value.query.trim()
        if (query.length < 3) {
            _uiState.update {
                it.copy(errorMessage = "Minimum 3 characters required")
            }
            return
        }

        loadHybridSearch(query)
    }

    /**
     * Load local suggestions only (for display during typing).
     */
    private fun loadLocalSuggestions(query: String) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    searchRepository.getLocalSuggestions(query)
                }
                _uiState.update { it.copy(localSuggestions = result) }
            } catch (e: Exception) {
                // Silent failure for suggestions - show nothing if error
            }
        }
    }

    /**
     * Load hybrid search results (local + online).
     */
    private fun loadHybridSearch(query: String) {
        _uiState.update {
            it.copy(
                isLoadingFullSearch = true,
                showLocalSuggestionsDropdown = false,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    searchRepository.hybridSearch(query)
                }
                // Persist the query and refresh the recents list
                withContext(Dispatchers.IO) {
                    searchRepository.saveRecentSearch(query)
                }
                loadRecentQueries()

                _uiState.update {
                    it.copy(
                        currentFullSearchResult = result,
                        isLoadingFullSearch = false,
                        errorMessage = result.onlineError // surface online error non-bloquant
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingFullSearch = false,
                        errorMessage = "Erreur de recherche : ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Handle suggestion selection (fill query and search).
     */
    fun selectSuggestion(query: String) {
        _uiState.update { it.copy(query = query) }
        submitSearch()
    }

    /**
     * Handle recent query selection (fill query and search).
     */
    fun selectRecentQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        submitSearch()
    }

    /**
     * Dismiss error message.
     */
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Save the current query to recent searches.
     */
    fun saveRecentSearch() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    searchRepository.saveRecentSearch(_uiState.value.query)
                }
            } catch (e: Exception) {
                // Silent failure - recent search not saved
            }
        }
    }

    /**
     * Like or unlike a local track (toggle).
     *
     * @param trackId the track ID to toggle like status
     * @param currentlyLiked the current like status
     */
    fun likeLocalTrack(trackId: String, currentlyLiked: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    searchRepository.toggleLike(trackId, currentlyLiked)
                }
            } catch (e: Exception) {
                // Log error or surface to UI if needed
                _uiState.update {
                    it.copy(errorMessage = "Erreur lors de la modification du favori")
                }
            }
        }
    }
}

/**
 * Factory for creating SearchViewModel instances.
 */
class SearchViewModelFactory(
    private val searchRepository: SearchRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SearchViewModel(searchRepository) as T
    }
}
