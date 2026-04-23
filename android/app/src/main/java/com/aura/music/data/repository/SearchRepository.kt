package com.aura.music.data.repository

import com.aura.music.data.local.TrackListRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.SearchResponseData
import com.aura.music.data.network.BestMatch
import com.aura.music.data.network.TrackSummary
import com.aura.music.data.network.ArtistSummary
import com.aura.music.data.network.AlbumSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

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

/**
 * SearchRepository manages hybrid search combining local library with online results.
 * - Local search is fast and always available
 * - Online search is optional and handled gracefully on error
 * - Fusion of results is done on the Android side
 */
class SearchRepository(
    private val localLibraryRepository: LocalLibraryRepository,
    private val auraApiService: AuraApiService,
) {

    /**
     * Perform a hybrid search combining local and online results.
     * 
     * Flow:
     * 1. Launch local search immediately (fast, always available)
     * 2. Launch online search in parallel (optional, non-blocking on error)
     * 3. Return combined results with best match
     * 
     * @param query Search query (should be >= 3 characters per product spec)
     * @return HybridSearchResult with local and online results
     */
    suspend fun hybridSearch(query: String): HybridSearchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            // Launch local search
            val localTracksAsync = async { localLibraryRepository.searchLocalTracks(query) }
            val localArtistsAsync = async { localLibraryRepository.searchLocalArtists(query) }
            val localAlbumsAsync = async { localLibraryRepository.searchLocalAlbums(query) }

            // Launch online search in parallel (non-blocking)
            val onlineSearchAsync = async {
                runCatching {
                    auraApiService.search(
                        query = query,
                        limitTracks = 10,
                        limitArtists = 8,
                        limitAlbums = 8
                    )
                }
            }

            // Await all results
            val localTracks = localTracksAsync.await()
            val localArtists = localArtistsAsync.await()
            val localAlbums = localAlbumsAsync.await()
            val onlineResult = onlineSearchAsync.await()
            
            val onlineData = onlineResult.getOrNull()?.data
            val onlineError = onlineResult.exceptionOrNull()?.let { 
                "Recherche en ligne indisponible."
            }

            // Determine best match (prefer local if strong match)
            val bestMatch = determineBestMatch(query, localTracks, localArtists, localAlbums, onlineData)

            // Construct result
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
     * This is fast and doesn't call the backend.
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
     * Determine the best match from local and online results.
     * 
     * Priority:
     * 1. Local track with exact title match
     * 2. Online best match if available
     * 3. First local track
     * 4. First local artist
     * 5. First local album
     * 6. None
     */
    private suspend fun determineBestMatch(
        query: String,
        localTracks: List<TrackListRow>,
        localArtists: List<ArtistBrowseRow>,
        localAlbums: List<AlbumBrowseRow>,
        onlineData: SearchResponseData?
    ): BestMatchResult? {
        // Try exact local track match
        val exactTrackMatch = localTracks.find {
            it.title.equals(query, ignoreCase = true)
        }
        if (exactTrackMatch != null) {
            return BestMatchResult.LocalTrack(exactTrackMatch)
        }

        // Try online best match
        if (onlineData?.bestMatch != null) {
            val bestMatch = onlineData.bestMatch
            return when (bestMatch.kind) {
                "track" -> {
                    val trackModel = bestMatch.item as? TrackSummary
                    if (trackModel != null) BestMatchResult.OnlineTrack(trackModel, trackModel.id) else null
                }
                "artist" -> {
                    val artistModel = bestMatch.item as? ArtistSummary
                    if (artistModel != null) BestMatchResult.OnlineArtist(artistModel, artistModel.id) else null
                }
                "album" -> {
                    val albumModel = bestMatch.item as? AlbumSummary
                    if (albumModel != null) BestMatchResult.OnlineAlbum(albumModel, albumModel.id) else null
                }
                else -> null
            }
        }

        // Fallback to first online track from the search results
        if (onlineData?.tracks?.isNotEmpty() == true) {
            val track = onlineData.tracks.first()
            return BestMatchResult.OnlineTrack(track, track.id)
        }

        // Fallback to first online artist
        if (onlineData?.artists?.isNotEmpty() == true) {
            val artist = onlineData.artists.first()
            return BestMatchResult.OnlineArtist(artist, artist.id)
        }

        // Fallback to first online album
        if (onlineData?.albums?.isNotEmpty() == true) {
            val album = onlineData.albums.first()
            return BestMatchResult.OnlineAlbum(album, album.id)
        }

        // Fallback to first local result
        return when {
            localTracks.isNotEmpty() -> BestMatchResult.LocalTrack(localTracks.first())
            localArtists.isNotEmpty() -> BestMatchResult.LocalArtist(localArtists.first())
            localAlbums.isNotEmpty() -> BestMatchResult.LocalAlbum(localAlbums.first())
            else -> null
        }
    }

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
