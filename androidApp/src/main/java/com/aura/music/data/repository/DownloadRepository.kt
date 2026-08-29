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
import com.aura.music.data.network.SourceHintDto
import com.aura.music.data.network.CookieUploadRequestDto
import com.aura.music.data.network.DownloadJobListResponseData
import com.aura.music.data.network.ResolveDownloadRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isPolling = false
    private val consecutiveFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val _downloadSuccessFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val downloadSuccessFlow = _downloadSuccessFlow.asSharedFlow()

    companion object {
        private const val TAG = "DownloadRepository"
    }

    /**
     * Translates technical and backend error codes into user-friendly explanations.
     */
    fun mapFriendlyErrorMessage(errorCode: String?, rawMessage: String?): String {
        val lowerMsg = rawMessage?.lowercase() ?: ""
        val lowerCode = errorCode?.lowercase() ?: ""
        return when {
            lowerCode.contains("bot") || lowerMsg.contains("bot") || lowerMsg.contains("sign in") || lowerMsg.contains("cookie") ->
                "YouTube a bloqué la requête anti-robot. Vos cookies YouTube sont peut-être expirés ou manquants."
            lowerCode.contains("po_token") || lowerMsg.contains("po_token") ->
                "Le jeton PO-Token YouTube a expiré. Une mise à jour du serveur est nécessaire."
            lowerCode.contains("timeout") || lowerCode.contains("504") || lowerMsg.contains("timeout") || lowerMsg.contains("504") ->
                "Délai d'attente dépassé : le serveur VPS n'a pas répondu à temps."
            lowerCode == "polling_failed" ->
                "Le serveur ne répond pas après plusieurs tentatives. Vérifiez la connexion."
            lowerCode == "fetch_error" || lowerMsg.contains("récupérer le fichier") ->
                "Impossible de télécharger le fichier audio final depuis le serveur VPS."
            lowerCode.contains("no_match") || lowerMsg.contains("not found") ->
                "Aucune version audio correspondante n'a été trouvée sur YouTube Music."
            !rawMessage.isNullOrBlank() ->
                rawMessage
            else ->
                "Une erreur inattendue est survenue pendant le téléchargement."
        }
    }

    private fun parseIsoDateToEpoch(isoString: String?, fallback: Long = System.currentTimeMillis()): Long {
        if (isoString.isNullOrBlank()) return fallback
        return try {
            java.time.Instant.parse(isoString).toEpochMilli()
        } catch (e: Exception) {
            fallback
        }
    }

    /**
     * Ensures the background polling loop is active as long as there are pending jobs.
     */
    fun ensurePollingStarted(userToken: String) {
        if (isPolling) return
        repositoryScope.launch {
            startPolling(userToken)
        }
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
                request = DownloadRequestDto(
                    trackId = trackId,
                    sourceHint = SourceHintDto(
                        providerName = "youtube",
                        providerTrackId = trackId,
                        title = title,
                        artistName = artistName,
                        albumTitle = albumTitle,
                        coverUri = coverUri
                    )
                )
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

            // Auto-start polling in repository background scope
            ensurePollingStarted(userToken)

            emit(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering download for track $trackId", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Start the background polling loop for any queued or running jobs.
     * Uses batch listDownloads polling (1 network request for all jobs) every 2s to avoid server connection exhaustion.
     * Automatically exits when there are no active jobs left.
     */
    suspend fun startPolling(userToken: String) = withContext(Dispatchers.IO) {
        if (isPolling) return@withContext
        isPolling = true
        Log.d(TAG, "Starting active download jobs polling loop (batch-optimized)")
        try {
            while (isPolling) {
                val activeJobs = database.downloadJobDao().getActiveJobs()
                if (activeJobs.isEmpty()) {
                    Log.d(TAG, "No active download jobs remaining, stopping polling loop automatically.")
                    break
                }

                try {
                    val response = apiService.listDownloads(token = userToken, limit = 50)
                    val items = response.data?.items
                    if (items != null && response.error == null) {
                        val now = System.currentTimeMillis()
                        val activeJobMap = activeJobs.associateBy { it.id }
                        val updatedEntities = mutableListOf<DownloadJobEntity>()

                        for (item in items) {
                            val existing = activeJobMap[item.id]
                            val friendlyError = if (item.errorMessage != null || item.errorCode != null) {
                                mapFriendlyErrorMessage(item.errorCode, item.errorMessage)
                            } else null

                            val entity = DownloadJobEntity(
                                id = item.id,
                                trackId = item.trackId,
                                providerName = item.providerName,
                                status = item.status,
                                progressPercent = item.progressPercent,
                                errorCode = item.errorCode,
                                errorMessage = friendlyError ?: item.errorMessage,
                                attemptCount = item.attemptCount,
                                createdAt = existing?.createdAt ?: parseIsoDateToEpoch(item.createdAt, now),
                                updatedAt = now
                            )
                            updatedEntities.add(entity)

                            if (item.status == "succeeded" && (existing == null || existing.status != "succeeded")) {
                                markJobAsCloudReady(item.trackId)
                            }
                        }

                        if (updatedEntities.isNotEmpty()) {
                            database.downloadJobDao().upsert(updatedEntities)
                        }
                    } else {
                        // Fallback: poll top 3 active jobs with slight delay between requests
                        for (job in activeJobs.take(3)) {
                            pollSingleJob(userToken, job)
                            delay(100)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during batch download polling, attempting fallback poll", e)
                    for (job in activeJobs.take(2)) {
                        pollSingleJob(userToken, job)
                        delay(100)
                    }
                }
                delay(2000) // Poll interval of 2 seconds
            }
        } finally {
            isPolling = false
            Log.d(TAG, "Stopped download jobs polling loop")
        }
    }

    private suspend fun pollSingleJob(userToken: String, job: DownloadJobEntity) {
        try {
            val response = apiService.getJobStatus(userToken, job.id)
            val jobData = response.data
            if (jobData != null && response.error == null) {
                consecutiveFailures.remove(job.id)
                val friendlyError = if (jobData.error != null) {
                    mapFriendlyErrorMessage(jobData.error?.code, jobData.error?.message)
                } else null

                val updatedJob = job.copy(
                    status = jobData.status,
                    progressPercent = jobData.progressPercent,
                    errorCode = jobData.error?.code,
                    errorMessage = friendlyError ?: jobData.error?.message,
                    updatedAt = System.currentTimeMillis()
                )
                database.downloadJobDao().upsert(updatedJob)
                if (jobData.status == "succeeded") {
                    markJobAsCloudReady(job.trackId)
                }
            } else {
                handlePollingFailure(job)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll status for job ${job.id}", e)
            handlePollingFailure(job)
        }
    }

    private suspend fun markJobAsCloudReady(trackId: String) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    val rawTrack = database.trackDao().getRawTrackById(trackId)
                    if (rawTrack != null && rawTrack.canonicalAudioSourceType != "downloaded") {
                        val updatedTrack = rawTrack.copy(
                            canonicalAudioSourceType = "cloud",
                            isDownloadedByAura = false,
                            updatedAt = now
                        )
                        database.trackDao().upsertTrack(updatedTrack)
                        Log.d(TAG, "Registered track $trackId as cloud-ready")
                    }
                }
            }
            _downloadSuccessFlow.tryEmit(trackId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark track $trackId as cloud-ready", e)
        }
    }

    private suspend fun handlePollingFailure(job: DownloadJobEntity) {
        val currentFailures = (consecutiveFailures[job.id] ?: 0) + 1
        consecutiveFailures[job.id] = currentFailures
        if (currentFailures >= 5) {
            consecutiveFailures.remove(job.id)
            val failedJob = job.copy(
                status = "failed",
                errorCode = "polling_failed",
                errorMessage = mapFriendlyErrorMessage("polling_failed", "Le serveur ne répond pas après 5 tentatives."),
                updatedAt = System.currentTimeMillis()
            )
            database.downloadJobDao().upsert(failedJob)
            Log.e(TAG, "Job ${job.id} marked as failed after 5 consecutive polling failures.")
        }
    }

    /**
     * Stop active polling manually.
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
                val createdAtEpoch = parseIsoDateToEpoch(item.createdAt, now)
                val updatedAtEpoch = parseIsoDateToEpoch(item.updatedAt, now)
                val friendlyError = if (item.errorMessage != null || item.errorCode != null) {
                    mapFriendlyErrorMessage(item.errorCode, item.errorMessage)
                } else null

                DownloadJobEntity(
                    id = item.id,
                    trackId = item.trackId,
                    providerName = item.providerName,
                    status = item.status,
                    progressPercent = item.progressPercent,
                    errorCode = item.errorCode,
                    errorMessage = friendlyError ?: item.errorMessage,
                    attemptCount = item.attemptCount,
                    createdAt = createdAtEpoch,
                    updatedAt = updatedAtEpoch
                )
            }
            database.downloadJobDao().upsert(jobs)
            Log.i(TAG, "Synchronized ${jobs.size} download jobs from server")

            // Auto-start polling if there are pending/running jobs
            if (jobs.any { it.status == "queued" || it.status == "running" }) {
                ensurePollingStarted(userToken)
            }

            for (item in items) {
                if (database.trackDao().getRawTrackById(item.trackId) == null) {
                    val placeholderTrack = TrackEntity(
                        id = item.trackId,
                        primaryArtistId = null,
                        albumId = null,
                        title = "Piste ${item.trackId.takeLast(6)}",
                        normalizedTitle = "piste",
                        displayArtistName = "Téléchargement",
                        displayAlbumTitle = null,
                        durationMs = null,
                        coverUri = null,
                        canonicalAudioSourceType = if (item.status == "succeeded") "cloud" else "cloud_only",
                        isLiked = false,
                        isDownloadedByAura = false,
                        isExplicit = null,
                        popularity = null,
                        genresJson = null,
                        createdAt = now,
                        updatedAt = now
                    )
                    database.trackDao().upsertTrack(placeholderTrack)
                }
                if (item.status == "succeeded") {
                    markJobAsCloudReady(item.trackId)
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
                markJobAsFetchFailed(jobId)
                return@withContext
            }

            // Write target file in private directory context.filesDir/downloads/
            val downloadsDir = File(context.filesDir, "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val targetFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")
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

            // 1. Try to download official remote cover first (from DB)
            var localCoverUri: String? = null
            val rawTrack = database.trackDao().getRawTrackById(trackId)
            val imageUrl = rawTrack?.coverUri
            if (imageUrl != null && imageUrl.startsWith("http")) {
                val client = HttpClient()
                try {
                    val imageResponse = client.get(imageUrl)
                    if (imageResponse.status.value in 200..299) {
                        val imageBytes = imageResponse.body<ByteArray>()
                        val coversDir = File(context.filesDir, "covers")
                        if (!coversDir.exists()) {
                            coversDir.mkdirs()
                        }
                        val coverFile = File(coversDir, "${trackId.replace(':', ';')}.jpg")
                        FileOutputStream(coverFile).use { fos ->
                            fos.write(imageBytes)
                        }
                        localCoverUri = Uri.fromFile(coverFile).toString()
                        Log.i(TAG, "Downloaded remote cover from $imageUrl for $trackId to $localCoverUri")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download remote cover fallback for $trackId", e)
                } finally {
                    client.close()
                }
            }

            // 2. Fallback: Extract embedded cover artwork from MP3 (usually the YouTube thumbnail)
            if (localCoverUri == null) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(targetFile.absolutePath)
                    val embeddedPicture = retriever.embeddedPicture
                    if (embeddedPicture != null) {
                        val coversDir = File(context.filesDir, "covers")
                        if (!coversDir.exists()) {
                            coversDir.mkdirs()
                        }
                        val coverFile = File(coversDir, "${trackId.replace(':', ';')}.jpg")
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
                        _downloadSuccessFlow.tryEmit(trackId)
                    }
                }
            }

            // Trigger background upload if auto-sync is enabled
            val settings = database.userSettingsDao().getSettings()
            if (settings != null && settings.syncEnabled) {
                Log.i(TAG, "Auto-sync is enabled, uploading downloaded track $trackId to cloud...")
                try {
                    val fileBytes = targetFile.readBytes()
                    val mimeType = "audio/mpeg"
                    val finalTrack = database.trackDao().getRawTrackById(trackId)
                    if (finalTrack != null) {
                        val uploadResponse = apiService.uploadSyncFile(
                            token = SyncRepository.AUTH_TOKEN,
                            trackId = trackId,
                            fileBytes = fileBytes,
                            mimeType = mimeType,
                            title = finalTrack.title,
                            artistName = finalTrack.displayArtistName,
                            albumTitle = finalTrack.displayAlbumTitle,
                            durationMs = finalTrack.durationMs,
                            artistId = finalTrack.primaryArtistId,
                            albumId = finalTrack.albumId,
                            coverUri = finalTrack.coverUri
                        )
                        val uploadError = uploadResponse.error
                        if (uploadError != null) {
                            Log.e(TAG, "Auto-sync upload failed for $trackId: ${uploadError.message}")
                        } else {
                            Log.i(TAG, "Auto-sync upload succeeded for $trackId")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to auto-sync upload track $trackId to cloud", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve physical file for job $jobId", e)
            markJobAsFetchFailed(jobId)
        }
    }

    private suspend fun markJobAsFetchFailed(jobId: String) {
        try {
            val localJob = database.downloadJobDao().getJobById(jobId)
            if (localJob != null) {
                val updatedJob = localJob.copy(
                    status = "failed",
                    errorCode = "FETCH_ERROR",
                    errorMessage = "Impossible de récupérer le fichier audio du serveur.",
                    updatedAt = System.currentTimeMillis()
                )
                database.downloadJobDao().upsert(updatedJob)
                Log.d(TAG, "Marked job $jobId as failed with FETCH_ERROR")
            }
        } catch (dbEx: Exception) {
            Log.e(TAG, "Failed to update job status to failed for $jobId", dbEx)
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
                    ensurePollingStarted(userToken)
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
                    ensurePollingStarted(userToken)
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
     * Clear all download jobs from local database.
     */
    suspend fun clearAllJobs(): Unit = withContext(Dispatchers.IO) {
        database.downloadJobDao().clearAllJobs()
    }

    /**
     * Delete a single download job by ID.
     */
    suspend fun deleteJob(jobId: String): Unit = withContext(Dispatchers.IO) {
        database.downloadJobDao().deleteJob(jobId)
    }

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
