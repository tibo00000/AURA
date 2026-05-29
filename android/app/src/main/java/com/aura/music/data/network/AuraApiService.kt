package com.aura.music.data.network

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * Retrofit service for AURA backend API.
 * Covers public online endpoints for search, artist, album details and resolve.
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

    /**
     * GET /resolve/artist?name=...
     * Resolve a local artist name to an opaque AURA backend ID + picture_uri.
     * SRV-008 / AND-009 : called only when online_search_enabled and network policy allow it.
     */
    @GET("/resolve/artist")
    suspend fun resolveArtist(
        @Query("name") name: String
    ): AuraResponse<ResolveArtistResponseData>

    /**
     * GET /resolve/album?title=...&artist_name=...
     * Resolve a local album title to an opaque AURA backend ID + cover_uri.
     * SRV-008 / AND-009 : called only when online_search_enabled and network policy allow it.
     */
    @GET("/resolve/album")
    suspend fun resolveAlbum(
        @Query("title") title: String,
        @Query("artist_name") artistName: String? = null
    ): AuraResponse<ResolveAlbumResponseData>

    /**
     * POST /downloads
     * Request a track download.
     */
    @POST("/downloads")
    suspend fun createDownload(
        @Header("Authorization") token: String,
        @Body request: DownloadRequestDto
    ): AuraResponse<DownloadCreateResponseData>

    /**
     * GET /downloads
     * List download jobs.
     */
    @GET("/downloads")
    suspend fun listDownloads(
        @Header("Authorization") token: String,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("cursor") cursor: String? = null
    ): AuraResponse<DownloadJobListResponseData>

    /**
     * POST /downloads/{job_id}/retry
     * Retry a failed or cancelled download job.
     */
    @POST("/downloads/{job_id}/retry")
    suspend fun retryDownload(
        @Header("Authorization") token: String,
        @Path("job_id") jobId: String
    ): AuraResponse<DownloadCreateResponseData>

    /**
     * POST /downloads/{job_id}/resolve
     * Resolve a pending download job by choosing one of the YouTube Music candidates.
     */
    @POST("/downloads/{job_id}/resolve")
    suspend fun resolveDownload(
        @Header("Authorization") token: String,
        @Path("job_id") jobId: String,
        @Body request: ResolveDownloadRequestDto
    ): AuraResponse<DownloadCreateResponseData>

    /**
     * GET /jobs/{job_id}
     * Get the status of an asynchronous job (such as a track download).
     */
    @GET("/jobs/{job_id}")
    suspend fun getJobStatus(
        @Header("Authorization") token: String,
        @Path("job_id") jobId: String
    ): AuraResponse<JobStatusResponseData>

    /**
     * POST /me/settings/cookies
     * Upload Netscape format cookies to bypass YouTube blocks.
     */
    @POST("/me/settings/cookies")
    suspend fun uploadCookies(
        @Header("Authorization") token: String,
        @Body request: CookieUploadRequestDto
    ): AuraResponse<CookieUploadResponseData>

    /**
     * GET /downloads/{job_id}/file
     * Fetch the physical downloaded audio file (MP3).
     * Streaming annotation ensures the file is read in chunks to prevent OOM errors.
     */
    @Streaming
    @GET("/downloads/{job_id}/file")
    suspend fun downloadFile(
        @Header("Authorization") token: String,
        @Path("job_id") jobId: String
    ): Response<ResponseBody>
}

