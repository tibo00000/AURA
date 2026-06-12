package com.aura.music.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.ArtistEntity
import com.aura.music.data.local.AlbumEntity
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.DownloadJobEntity
import com.aura.music.data.local.DownloadJobRowModel
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.DownloadRequestDto
import com.aura.music.data.network.CookieUploadRequestDto
import com.aura.music.data.network.DownloadJobListResponseData
import com.aura.music.data.network.ResolveDownloadRequestDto
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import androidx.room3.useWriterConnection
import androidx.room3.immediateTransaction
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Repository to orchestrate background downloads of tracks from the AURA backend.
 *
 * Implements Phase 3 repository & sync rules:
 * - Creates local entities dynamically when requesting a download of an online track.
 * - Submits download job requests to the backend.
 * - Polls progress of active download jobs at regular intervals.
 * - Streams succeeded audio files to local private storage (context.filesDir/downloads/).
 * - Updates Room persistent layers for offline play.
 */
class DownloadRepository(
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val context: Context
) {
    private var isPolling = false

    companion object {
        private const val TAG = "DownloadRepository"
    }

    /**
     * Trigger a new download for an online track.
     * Inserts temporary entities in the local Room DB to preserve referential integrity,
     * calls the AURA backend API to enqueue a download job, and saves the job state in Room.
     */
    fun triggerDownload(
        trackId: String,
        title: String,
        artistName: String,
        albumTitle: String?,
        coverUri: String?,
        userToken: String
    ): Flow<Result<Unit>> = flow {
        try {
            val now = System.currentTimeMillis()
            val slugArtist = normalize(artistName)
            val artistId = "artist:$slugArtist"
            val albumId = albumTitle?.let { "album:$slugArtist:${normalize(it)}" }

            // 1. Ensure local relational structure is satisfied (Room constraints)
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    // Ensure Artist exists
                    if (database.artistDao().getArtistDetail(artistId) == null) {
                        database.artistDao().upsertArtists(
                            listOf(
                                ArtistEntity(
                                    id = artistId,
                                    name = artistName,
                                    normalizedName = slugArtist,
                                    pictureUri = null,
                                    artworkOrigin = "unknown",
                                    artworkLastResolvedAt = null,
                                    summary = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        )
                    }

                    // Ensure Album exists if specified
                    if (albumId != null && albumTitle != null) {
                        if (database.albumDao().getAlbumDetail(albumId) == null) {
                            database.albumDao().upsertAlbums(
                                listOf(
                                    AlbumEntity(
                                        id = albumId,
                                        primaryArtistId = artistId,
                                        title = albumTitle,
                                        normalizedTitle = normalize(albumTitle),
                                        coverUri = coverUri,
                                        artworkOrigin = "unknown",
                                        artworkLastResolvedAt = null,
                                        releaseDate = null,
                                        trackCount = null,
                                        createdAt = now,
                                        updatedAt = now
                                    )
                                )
                            )
                        }
                    }

                    // Ensure Track exists
                    if (database.trackDao().getRawTrackById(trackId) == null) {
                        database.trackDao().upsertTrack(
                            TrackEntity(
                                id = trackId,
                                primaryArtistId = artistId,
                                albumId = albumId,
                                title = title,
                                normalizedTitle = normalize(title),
                                displayArtistName = artistName,
                                displayAlbumTitle = albumTitle,
                                durationMs = null,
                                coverUri = coverUri,
                                canonicalAudioSourceType = "cloud_only",
                                isLiked = false,
                                isDownloadedByAura = false,
                                isExplicit = null,
                                popularity = null,
                                genresJson = null,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }
                }
            }

            // 2. Submit download request to backend
            val response = apiService.createDownload(
                token = userToken,
                request = DownloadRequestDto(trackId = trackId)
            )

            val createData = response.data
            if (createData == null) {
                val errorMsg = response.error?.message ?: "Erreur de soumission du téléchargement"
                emit(Result.failure(Exception(errorMsg)))
                return@flow
            }

            // 3. Persist the download job in local Room database
            val jobEntity = DownloadJobEntity(
                id = createData.jobId,
                trackId = trackId,
                providerName = "aura_backend",
                status = createData.status,
                progressPercent = 0f,
                createdAt = now,
                updatedAt = now
            )
            database.downloadJobDao().upsert(jobEntity)

            emit(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering download for track $trackId", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Start the background polling loop for any queued or running jobs.
     * Polls the backend every 2s until jobs reach succeeded or failed status.
     */
    suspend fun startPolling(userToken: String) = withContext(Dispatchers.IO) {
        if (isPolling) return@withContext
        isPolling = true
        Log.d(TAG, "Starting active download jobs polling loop")
        try {
            while (isPolling) {
                val activeJobs = database.downloadJobDao().getActiveJobs()
                if (activeJobs.isEmpty()) {
                    delay(3000) // Sleep slightly longer if there are no active jobs
                    continue
                }

                for (job in activeJobs) {
                    try {
                        val response = apiService.getJobStatus(userToken, job.id)
                        val jobData = response.data
                        if (jobData != null) {
                            val updatedJob = job.copy(
                                status = jobData.status,
                                progressPercent = jobData.progressPercent,
                                errorCode = jobData.error?.code,
                                errorMessage = jobData.error?.message,
                                updatedAt = System.currentTimeMillis()
                            )
                            database.downloadJobDao().upsert(updatedJob)
                            Log.d(TAG, "Polled job ${job.id}: status=${jobData.status}, progress=${jobData.progressPercent}%")

                            if (jobData.status == "succeeded") {
                                // Transition to downloading the physical file
                                fetchDownloadedFile(job.id, job.trackId, userToken)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to poll status for job ${job.id}", e)
                    }
                }
                delay(2000) // Poll interval of 2 seconds
            }
        } finally {
            isPolling = false
            Log.d(TAG, "Stopped download jobs polling loop")
        }
    }

    /**
     * Stop active polling manually (e.g. when viewmodel is cleared).
     */
    fun stopPolling() {
        isPolling = false
    }

    /**
     * Fetches active jobs from the backend server to sync the local database state.
     */
    suspend fun syncActiveJobs(userToken: String) = withContext(Dispatchers.IO) {
        try {
            val response = apiService.listDownloads(token = userToken)
            val items = response.data?.items ?: return@withContext
            
            val now = System.currentTimeMillis()
            val jobs = items.map { item ->
                DownloadJobEntity(
                    id = item.id,
                    trackId = item.trackId,
                    providerName = item.providerName,
                    status = item.status,
                    progressPercent = item.progressPercent,
                    errorCode = item.errorCode,
                    errorMessage = item.errorMessage,
                    attemptCount = item.attemptCount,
                    createdAt = now,
                    updatedAt = now
                )
            }
            database.downloadJobDao().upsert(jobs)
            Log.i(TAG, "Synchronized ${jobs.size} download jobs from server")

            // Auto-fetch physical files for succeeded jobs that are missing or incomplete locally
            for (item in items) {
                if (item.status == "succeeded") {
                    val downloadsDir = File(context.filesDir, "downloads")
                    val targetFile = File(downloadsDir, "${item.trackId}.mp3")
                    
                    // Verify database link is correct and points to "downloaded" status
                    val rawTrack = database.trackDao().getRawTrackById(item.trackId)
                    val isDbLinked = rawTrack != null && rawTrack.canonicalAudioSourceType == "downloaded" && rawTrack.isDownloadedByAura
                    
                    if (!targetFile.exists() || targetFile.length() == 0L || !isDbLinked) {
                        Log.i(TAG, "Succeeded job ${item.id} (track ${item.trackId}) is missing local file, has 0 bytes or lacks DB link. Self-healing/fetching now...")
                        fetchDownloadedFile(item.id, item.trackId, userToken)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync active jobs with backend", e)
        }
    }

    /**
     * Downloads the physical audio MP3 from the backend server and stores it in the private internal app storage.
     * Registers the content URI in the database so ExoPlayer can access it offline.
     */
    suspend fun fetchDownloadedFile(jobId: String, trackId: String, userToken: String) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Fetching physical MP3 file for succeeded job $jobId...")
            val response = apiService.downloadFile(userToken, jobId)
            if (response.status.value !in 200..299) {
                Log.e(TAG, "Failed to download physical file for job $jobId: HTTP ${response.status.value}")
                return@withContext
            }

            // Write target file in private directory context.filesDir/downloads/
            val downloadsDir = File(context.filesDir, "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val targetFile = File(downloadsDir, "$trackId.mp3")
            if (targetFile.exists()) {
                targetFile.delete()
            }

            // Stream download to prevent OOM
            val channel = response.bodyAsChannel()
            channel.toInputStream().use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }

            Log.i(TAG, "Downloaded file saved successfully to ${targetFile.absolutePath} (size: ${targetFile.length()} bytes)")

            // Extract embedded cover artwork if present
            var localCoverUri: String? = null
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(targetFile.absolutePath)
                val embeddedPicture = retriever.embeddedPicture
                if (embeddedPicture != null) {
                    val coversDir = File(context.filesDir, "covers")
                    if (!coversDir.exists()) {
                        coversDir.mkdirs()
                    }
                    val coverFile = File(coversDir, "$trackId.jpg")
                    FileOutputStream(coverFile).use { fos ->
                        fos.write(embeddedPicture)
                    }
                    localCoverUri = Uri.fromFile(coverFile).toString()
                    Log.i(TAG, "Extracted embedded cover for $trackId to $localCoverUri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract embedded cover for $trackId", e)
            } finally {
                try {
                    retriever.release()
                } catch (ignored: Exception) {}
            }

            // Update DB values
            val now = System.currentTimeMillis()
            val fileUri = Uri.fromFile(targetFile).toString()

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    // 1. Create media link
                    val mockMediaStoreId = System.currentTimeMillis() // High unique ID for downloaded tracks
                    val mediaLink = TrackMediaLinkEntity(
                        id = "media-link:$mockMediaStoreId",
                        trackId = trackId,
                        mediaStoreId = mockMediaStoreId,
                        contentUri = fileUri,
                        fileSizeBytes = targetFile.length(),
                        mimeType = "audio/mpeg",
                        dateModifiedEpochMs = now,
                        availabilityStatus = "present",
                        lastScannedAt = now
                    )
                    database.trackDao().upsertTrackMediaLinks(listOf(mediaLink))

                    // 2. Update TrackEntity source type to "downloaded" and denormalized flag
                    val rawTrack = database.trackDao().getRawTrackById(trackId)
                    if (rawTrack != null) {
                        val updatedTrack = rawTrack.copy(
                            canonicalAudioSourceType = "downloaded",
                            isDownloadedByAura = true,
                            coverUri = localCoverUri ?: rawTrack.coverUri,
                            updatedAt = now
                        )
                        database.trackDao().upsertTrack(updatedTrack)
                        Log.d(TAG, "Updated local TrackEntity $trackId to downloaded state")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve physical file for job $jobId", e)
        }
    }

    /**
     * Retry a failed or cancelled job.
     * Calls the backend retry route, updates Room to "queued", and restarts polling.
     */
    suspend fun retryJob(jobId: String, userToken: String) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Requesting retry for job $jobId...")
            val response = apiService.retryDownload(userToken, jobId)
            val createData = response.data
            if (createData != null) {
                // Update local Room entity
                val job = database.downloadJobDao().getJobById(jobId)
                if (job != null) {
                    val updatedJob = job.copy(
                        status = createData.status,
                        progressPercent = 0f,
                        errorCode = null,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    database.downloadJobDao().upsert(updatedJob)
                    Log.d(TAG, "Local job $jobId reset to ${createData.status}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrying job $jobId", e)
        }
    }

    /**
     * Resolve a pending download job by choosing one of the YouTube Music candidates.
     * Calls the backend resolve route, updates Room to "queued", and restarts polling.
     */
    suspend fun resolveJob(jobId: String, videoId: String, userToken: String) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Resolving job $jobId with videoId $videoId...")
            val response = apiService.resolveDownload(
                token = userToken,
                jobId = jobId,
                request = ResolveDownloadRequestDto(videoId = videoId)
            )
            val createData = response.data
            if (createData != null) {
                // Update local Room entity
                val job = database.downloadJobDao().getJobById(jobId)
                if (job != null) {
                    val updatedJob = job.copy(
                        status = createData.status,
                        progressPercent = 0f,
                        errorCode = null,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    database.downloadJobDao().upsert(updatedJob)
                    Log.d(TAG, "Local job $jobId reset to ${createData.status} after resolution")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving job $jobId", e)
        }
    }

    /**
     * Expose jobs reactive Flow from Room by status
     */
    fun getJobsByStatus(status: String): Flow<List<DownloadJobEntity>> {
        return database.downloadJobDao().getJobsByStatusFlow(status)
    }

    /**
     * Expose all jobs reactive Flow from Room
     */
    fun getAllJobs(): Flow<List<DownloadJobEntity>> {
        return database.downloadJobDao().getAllJobsFlow()
    }

    /**
     * Expose all jobs with track metadata reactive Flow from Room
     */
    fun getAllJobsWithTrack(): Flow<List<DownloadJobRowModel>> {
        return database.downloadJobDao().getAllJobsWithTrackFlow()
    }

    /**
     * Upload Netscape format cookies to bypass YouTube blocks.
     */
    fun uploadCookies(cookiesText: String, userToken: String): Flow<Result<Boolean>> = flow {
        try {
            val response = apiService.uploadCookies(
                token = userToken,
                request = CookieUploadRequestDto(cookiesText = cookiesText)
            )
            val data = response.data
            if (data?.success == true) {
                emit(Result.success(true))
            } else {
                val errorMsg = response.error?.message ?: "Erreur de téléversement des cookies"
                emit(Result.failure(Exception(errorMsg)))
            }
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Error uploading cookies", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Fetch candidates for a specific job from the backend.
     */
    suspend fun getCandidatesForJob(jobId: String, userToken: String): List<com.aura.music.data.network.YtmCandidateDto>? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getJobStatus(userToken, jobId)
            return@withContext response.data?.candidates
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching candidates for job $jobId", e)
            return@withContext null
        }
    }

    private fun normalize(value: String): String {
        val slug = value
            .trim()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
        if (slug.isNotBlank()) return slug
        
        val bytes = value.trim().lowercase().toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.take(16).ifBlank { "unknown" }
    }
}
