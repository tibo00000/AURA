package com.aura.music.data.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit service for AURA backend API.
 * Covers public online endpoints for search, artist, and album details.
 * Mirrors docs/server/api-contract.md
 */
interface AuraApiService {

    /**
     * GET /search
     * Launch an online search across tracks, artists, and albums.
     *
     * @param query Search query (minimum 3 characters)
     * @param limitTracks Max tracks to return (default 10, max 25)
     * @param limitArtists Max artists to return (default 8, max 20)
     * @param limitAlbums Max albums to return (default 8, max 20)
     */
    @GET("/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit_tracks") limitTracks: Int = 10,
        @Query("limit_artists") limitArtists: Int = 8,
        @Query("limit_albums") limitAlbums: Int = 8
    ): AuraResponse<SearchResponseData>

    /**
     * GET /artists/{id}
     * Retrieve detailed information about an artist.
     * ID must be an opaque AURA backend identifier from /search response.
     */
    @GET("/artists/{id}")
    suspend fun getArtist(
        @Path("id") id: String
    ): AuraResponse<ArtistDetailResponseData>

    /**
     * GET /albums/{id}
     * Retrieve detailed information about an album.
     * ID must be an opaque AURA backend identifier from /search response.
     */
    @GET("/albums/{id}")
    suspend fun getAlbum(
        @Path("id") id: String
    ): AuraResponse<AlbumDetailResponseData>
}
