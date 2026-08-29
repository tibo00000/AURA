package com.aura.music.data.repository

import android.content.Context
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.NetworkPolicyChecker
import com.aura.music.data.network.SearchResponseData
import com.aura.music.data.network.BestMatch
import com.aura.music.data.network.TrackSummary
import com.aura.music.data.network.ArtistSummary
import com.aura.music.data.network.AlbumSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.aura.music.domain.search.SearchNormalizer
import com.aura.music.domain.search.FuzzyMatcher

/**
 * Result of a hybrid search combining local and online results.
 */
data class HybridSearchResult(
    val query: String,
    val bestMatch: BestMatchResult?,
    val localTracks: List<TrackListRow> = emptyList(),
    val localArtists: List<ArtistBrowseRow> = emptyList(),
    val localAlbums: List<AlbumBrowseRow> = emptyList(),
    val onlineTracks: List<TrackSummary> = emptyList(),
    val onlineArtists: List<ArtistSummary> = emptyList(),
    val onlineAlbums: List<AlbumSummary> = emptyList(),
    val onlineError: String? = null // Non-blocking error from backend
)

/**
 * Represents the "best match" result which can be local or online.
 * Sealed class to enforce type safety.
 */
sealed class BestMatchResult {
    data class LocalTrack(val track: TrackListRow) : BestMatchResult()
    data class LocalArtist(val artist: ArtistBrowseRow) : BestMatchResult()
    data class LocalAlbum(val album: AlbumBrowseRow) : BestMatchResult()
    data class OnlineTrack(val track: TrackSummary, val id: String) : BestMatchResult()
    data class OnlineArtist(val artist: ArtistSummary, val id: String) : BestMatchResult()
    data class OnlineAlbum(val album: AlbumSummary, val id: String) : BestMatchResult()
}

private data class ScoredBestMatch(
    val result: BestMatchResult,
    val score: Int
)

/**
 * SearchRepository manages hybrid search combining local library with online results.
 *
 * AND-009 rules:
 * - Local search is fast and always available, never blocked.
 * - Online search is gated by NetworkPolicyChecker.isAllowed().
 * - getLocalSuggestions() never calls the backend (invoked during typing).
 */
class SearchRepository(
    private val localLibraryRepository: LocalLibraryRepository,
    private val auraApiService: AuraApiService,
    private val enrichmentRepository: EnrichmentRepository? = null,
) {

    /**
     * Perform a hybrid search combining local and online results.
     *
     * Flow:
     * 1. Launch local search immediately (fast, always available)
     * 2. Check network policy; launch online search only if allowed (AND-009)
     * 3. Return combined results with best match
     *
     * @param query              Search query (should be >= 3 characters)
     * @param onlineSearchEnabled Value from user_settings.online_search_enabled
     * @param networkPolicy       Value from user_settings.online_search_network_policy
     * @param context             Application context for network check; null = offline mode
     */
    suspend fun hybridSearch(
        query: String,
        onlineSearchEnabled: Boolean = true,
        networkPolicy: String = "wifi_only",
        context: Context? = null,
    ): HybridSearchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            // Launch local search — always, unconditionally
            val localTracksAsync = async { localLibraryRepository.searchLocalTracks(query) }
            val localArtistsAsync = async { localLibraryRepository.searchLocalArtists(query) }
            val localAlbumsAsync = async { localLibraryRepository.searchLocalAlbums(query) }

            // AND-009: check network policy before any backend call
            val networkAllowed = context != null &&
                    NetworkPolicyChecker.isAllowed(
                        onlineSearchEnabled = onlineSearchEnabled,
                        policy = networkPolicy,
                        context = context,
                    )

            val onlineSearchAsync = if (networkAllowed) {
                async {
                    runCatching {
                        auraApiService.search(
                            query = query,
                            limitTracks = 10,
                            limitArtists = 8,
                            limitAlbums = 8
                        )
                    }
                }
            } else null

            // Await all results
            val localTracks = localTracksAsync.await()
            val localArtists = localArtistsAsync.await()
            val localAlbums = localAlbumsAsync.await()
            val onlineResult = onlineSearchAsync?.await()

            val onlineData = onlineResult?.getOrNull()?.data
            val onlineError = when {
                !networkAllowed -> null // settings block: silent, not an error
                onlineResult?.isFailure == true -> "Recherche en ligne indisponible."
                else -> null
            }

            val bestMatch = determineBestMatch(query, localTracks, localArtists, localAlbums, onlineData)

            HybridSearchResult(
                query = query,
                bestMatch = bestMatch,
                localTracks = localTracks,
                localArtists = localArtists,
                localAlbums = localAlbums,
                onlineTracks = onlineData?.tracks ?: emptyList(),
                onlineArtists = onlineData?.artists ?: emptyList(),
                onlineAlbums = onlineData?.albums ?: emptyList(),
                onlineError = onlineError
            )
        }
    }

    /**
     * Get local suggestions only (for display during typing with 3+ characters).
     * AND-009: This NEVER calls the backend.
     */
    suspend fun getLocalSuggestions(query: String): HybridSearchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val localTracks = async { localLibraryRepository.searchLocalTracks(query) }
            val localArtists = async { localLibraryRepository.searchLocalArtists(query) }
            val localAlbums = async { localLibraryRepository.searchLocalAlbums(query) }

            HybridSearchResult(
                query = query,
                bestMatch = null, // No best match during suggestions phase
                localTracks = localTracks.await(),
                localArtists = localArtists.await(),
                localAlbums = localAlbums.await()
            )
        }
    }

    /**
     * Get hybrid suggestions (local + online) during typing.
     * Online query is limited to 3 tracks, 2 artists, 2 albums and gated by network policy.
     */
    suspend fun getSuggestions(
        query: String,
        onlineSearchEnabled: Boolean = true,
        networkPolicy: String = "wifi_only",
        context: Context? = null
    ): HybridSearchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            // Launch local searches in parallel
            val localTracksAsync = async { localLibraryRepository.searchLocalTracks(query) }
            val localArtistsAsync = async { localLibraryRepository.searchLocalArtists(query) }
            val localAlbumsAsync = async { localLibraryRepository.searchLocalAlbums(query) }

            // Check if online suggestions are allowed
            val networkAllowed = context != null &&
                    NetworkPolicyChecker.isAllowed(
                        onlineSearchEnabled = onlineSearchEnabled,
                        policy = networkPolicy,
                        context = context,
                    )

            val onlineSearchAsync = if (networkAllowed) {
                async {
                    runCatching {
                        auraApiService.search(
                            query = query,
                            limitTracks = 3,
                            limitArtists = 2,
                            limitAlbums = 2
                        )
                    }
                }
            } else null

            val localTracks = localTracksAsync.await()
            val localArtists = localArtistsAsync.await()
            val localAlbums = localAlbumsAsync.await()
            val onlineResult = onlineSearchAsync?.await()

            val onlineData = onlineResult?.getOrNull()?.data
            val onlineError = when {
                !networkAllowed -> null
                onlineResult?.isFailure == true -> "Suggestions en ligne indisponibles."
                else -> null
            }

            HybridSearchResult(
                query = query,
                bestMatch = null,
                localTracks = localTracks,
                localArtists = localArtists,
                localAlbums = localAlbums,
                onlineTracks = onlineData?.tracks ?: emptyList(),
                onlineArtists = onlineData?.artists ?: emptyList(),
                onlineAlbums = onlineData?.albums ?: emptyList(),
                onlineError = onlineError
            )
        }
    }

    /**
     * Determine the best match from local and online results.
     *
     * Priority:
     * 1. Local track with exact title match (scored highest)
     * 2. Online best match if available (score + 8 bonus)
     * 3. All other candidates ranked by score
     */
    private fun determineBestMatch(
        query: String,
        localTracks: List<TrackListRow>,
        localArtists: List<ArtistBrowseRow>,
        localAlbums: List<AlbumBrowseRow>,
        onlineData: SearchResponseData?
    ): BestMatchResult? {
        val scoredCandidates = mutableListOf<ScoredBestMatch>()

        localTracks.forEach { track ->
            scoredCandidates += ScoredBestMatch(
                result = BestMatchResult.LocalTrack(track),
                score = scoreLocalTrack(query, track)
            )
        }

        localArtists.forEach { artist ->
            scoredCandidates += ScoredBestMatch(
                result = BestMatchResult.LocalArtist(artist),
                score = scoreLocalArtist(query, artist)
            )
        }

        localAlbums.forEach { album ->
            scoredCandidates += ScoredBestMatch(
                result = BestMatchResult.LocalAlbum(album),
                score = scoreLocalAlbum(query, album)
            )
        }

        val onlineBestMatch = onlineData?.bestMatch?.toBestMatchResult()
        if (onlineBestMatch != null) {
            scoredCandidates += ScoredBestMatch(
                result = onlineBestMatch,
                score = scoreBestMatchResult(query, onlineBestMatch) + 8
            )
        }

        onlineData?.tracks?.forEach { track ->
            scoredCandidates += ScoredBestMatch(
                result = BestMatchResult.OnlineTrack(track, track.id),
                score = scoreOnlineTrack(query, track)
            )
        }

        onlineData?.artists?.forEach { artist ->
            scoredCandidates += ScoredBestMatch(
                result = BestMatchResult.OnlineArtist(artist, artist.id),
                score = scoreOnlineArtist(query, artist)
            )
        }

        onlineData?.albums?.forEach { album ->
            scoredCandidates += ScoredBestMatch(
                result = BestMatchResult.OnlineAlbum(album, album.id),
                score = scoreOnlineAlbum(query, album)
            )
        }

        return scoredCandidates
            .filter { it.score > 0 }
            .maxByOrNull { it.score }
            ?.result
    }

    private fun BestMatch.toBestMatchResult(): BestMatchResult? = when (kind) {
        "track" -> (item as? TrackSummary)?.let { BestMatchResult.OnlineTrack(it, it.id) }
        "artist" -> (item as? ArtistSummary)?.let { BestMatchResult.OnlineArtist(it, it.id) }
        "album" -> (item as? AlbumSummary)?.let { BestMatchResult.OnlineAlbum(it, it.id) }
        else -> null
    }

    private fun scoreBestMatchResult(query: String, result: BestMatchResult): Int = when (result) {
        is BestMatchResult.LocalTrack -> scoreLocalTrack(query, result.track)
        is BestMatchResult.LocalArtist -> scoreLocalArtist(query, result.artist)
        is BestMatchResult.LocalAlbum -> scoreLocalAlbum(query, result.album)
        is BestMatchResult.OnlineTrack -> scoreOnlineTrack(query, result.track)
        is BestMatchResult.OnlineArtist -> scoreOnlineArtist(query, result.artist)
        is BestMatchResult.OnlineAlbum -> scoreOnlineAlbum(query, result.album)
    }

    private fun scoreLocalTrack(query: String, track: TrackListRow): Int =
        20 + scoreTrack(query, title = track.title, artistName = track.artistName, albumTitle = track.albumTitle)

    private fun scoreLocalArtist(query: String, artist: ArtistBrowseRow): Int =
        20 + scoreArtist(query, artist.name)

    private fun scoreLocalAlbum(query: String, album: AlbumBrowseRow): Int =
        20 + scoreAlbum(query, album.title, album.artistName)

    private fun scoreOnlineTrack(query: String, track: TrackSummary): Int =
        scoreTrack(query, title = track.title, artistName = track.displayArtistName, albumTitle = track.displayAlbumTitle)

    private fun scoreOnlineArtist(query: String, artist: ArtistSummary): Int =
        scoreArtist(query, artist.name)

    private fun scoreOnlineAlbum(query: String, album: AlbumSummary): Int =
        scoreAlbum(query, album.title, album.primaryArtistName)

    private fun scoreTrack(query: String, title: String, artistName: String?, albumTitle: String?): Int {
        val titleScore = scoreTextMatch(query, title)
        val artistScore = scoreTextMatch(query, artistName)
        val albumScore = scoreTextMatch(query, albumTitle)
        return titleScore + (artistScore / 4) + (albumScore / 5)
    }

    private fun scoreArtist(query: String, name: String): Int =
        scoreTextMatch(query, name)

    private fun scoreAlbum(query: String, title: String, artistName: String?): Int {
        val titleScore = scoreTextMatch(query, title)
        val artistScore = scoreTextMatch(query, artistName)
        return titleScore + (artistScore / 5)
    }

    private fun scoreTextMatch(query: String, candidate: String?): Int {
        val normalizedQuery = SearchNormalizer.normalize(query)
        val normalizedCandidate = SearchNormalizer.normalize(candidate)

        if (normalizedQuery.isEmpty() || normalizedCandidate.isEmpty()) {
            return 0
        }
        if (normalizedCandidate == normalizedQuery) {
            return 100
        }
        if (normalizedCandidate.startsWith(normalizedQuery)) {
            return 85
        }
        if (normalizedCandidate.contains(normalizedQuery)) {
            return 72
        }

        val queryTokens = SearchNormalizer.tokenize(query)
        val candidateTokens = SearchNormalizer.tokenize(candidate)
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) {
            return 0
        }

        var matchedCount = 0
        var similaritySum = 0.0f
        for (qToken in queryTokens) {
            var bestSim = 0.0f
            for (cToken in candidateTokens) {
                val sim = FuzzyMatcher.fuzzySimilarity(qToken, cToken)
                if (sim > bestSim) bestSim = sim
            }
            if (bestSim >= 0.7f) {
                matchedCount++
                similaritySum += bestSim
            }
        }

        if (matchedCount == 0) return 0
        val ratio = matchedCount.toFloat() / queryTokens.size
        return minOf(70, (20 + (ratio * 30) + (similaritySum * 15)).toInt())
    }

    private fun normalizeSearchText(value: String?): String =
        SearchNormalizer.normalize(value)

    /**
     * Get user settings.
     */
    suspend fun getSettings() = localLibraryRepository.getSettings()

    /**
     * Get recent search queries.
     */
    suspend fun getRecentQueries(limit: Int = 10): List<String> =
        localLibraryRepository.getRecentQueries(limit)

    /**
     * Save a search query to recent searches.
     */
    suspend fun saveRecentSearch(query: String) =
        localLibraryRepository.saveRecentSearch(query)

    /**
     * Toggle the like status of a local track.
     *
     * @param trackId the track ID to toggle
     * @param currentlyLiked the current like status (for optimization)
     */
    suspend fun toggleLike(
        trackId: String,
        currentlyLiked: Boolean
    ) = localLibraryRepository.toggleLike(
        trackId = trackId,
        currentlyLiked = currentlyLiked,
        contextType = "search"
    )
}
