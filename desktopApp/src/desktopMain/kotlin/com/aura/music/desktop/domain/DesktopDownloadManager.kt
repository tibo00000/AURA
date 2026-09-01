package com.aura.music.desktop.domain

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.data.local.*
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.DownloadRequestDto
import com.aura.music.data.network.SourceHintDto
import com.aura.music.data.network.TrackSummary
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestionnaire des téléchargements asynchrones pour le client Desktop.
 * Coordonne la création de jobs auprès du backend AURA (yt-dlp), le suivi de progression
 * et l'ingestion atomique des fichiers audio MP3 terminés dans Room.
 */
class DesktopDownloadManager(
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val scope: CoroutineScope
) {
    var apiToken: String? = null

    private val isSyncing = AtomicBoolean(false)
    private var downloadSyncJob: Job? = null

    fun startLoop(intervalMs: Long = 3000L) {
        downloadSyncJob?.cancel()
        downloadSyncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val token = apiToken
                if (!token.isNullOrBlank()) {
                    try {
                        syncActiveJobs(token)
                    } catch (e: Exception) {
                        System.err.println("Error syncing download jobs: ${e.message}")
                    }
                }
                delay(intervalMs)
            }
        }
    }

    fun stopLoop() {
        downloadSyncJob?.cancel()
        downloadSyncJob = null
    }

    suspend fun createDownload(track: TrackSummary) = withContext(Dispatchers.IO) {
        val token = apiToken ?: return@withContext
        try {
            apiService.createDownload(
                token = token,
                request = DownloadRequestDto(
                    trackId = track.id,
                    sourceHint = SourceHintDto(
                        providerName = "deezer",
                        providerTrackId = track.id,
                        title = track.title,
                        artistName = track.displayArtistName,
                        albumTitle = track.displayAlbumTitle,
                        coverUri = track.coverUri
                    )
                )
            )
            // Déclenche une synchronisation immédiate des jobs
            syncActiveJobs(token)
        } catch (e: Exception) {
            System.err.println("Failed to request download for ${track.id}: ${e.message}")
            throw e
        }
    }

    suspend fun retryJob(jobId: String) = withContext(Dispatchers.IO) {
        val token = apiToken ?: return@withContext
        try {
            apiService.retryDownload(token, jobId)
            syncActiveJobs(token)
        } catch (e: Exception) {
            System.err.println("Failed to retry job $jobId: ${e.message}")
            throw e
        }
    }

    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        database.downloadJobDao().deleteJob(jobId)
    }

    suspend fun clearCompletedJobs() = withContext(Dispatchers.IO) {
        database.downloadJobDao().clearCompletedJobs()
    }

    suspend fun syncActiveJobs(token: String) = withContext(Dispatchers.IO) {
        if (!isSyncing.compareAndSet(false, true)) return@withContext
        try {
            val response = apiService.listDownloads(token = token)
            val items = response.data?.items ?: return@withContext
            val now = System.currentTimeMillis()

            val jobsToUpsert = mutableListOf<DownloadJobEntity>()
            val tracksToInsert = mutableListOf<TrackEntity>()

            for (item in items) {
                val trackExists = database.trackDao().getRawTrackById(item.trackId) != null
                val isFinished = item.status == "succeeded" || item.status == "completed" || item.status == "failed"

                if (!trackExists && !isFinished) {
                    tracksToInsert.add(
                        TrackEntity(
                            id = item.trackId,
                            primaryArtistId = null,
                            albumId = null,
                            title = "Titre ${item.trackId}",
                            normalizedTitle = "titre ${item.trackId}",
                            displayArtistName = "Artiste Inconnu",
                            displayAlbumTitle = null,
                            durationMs = 0L,
                            coverUri = null,
                            canonicalAudioSourceType = "cloud_only",
                            isLiked = false,
                            isDownloadedByAura = false,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }

                jobsToUpsert.add(
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
                )
            }

            // Transaction atomique pour l'insertion des jobs et tracks associés
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    if (tracksToInsert.isNotEmpty()) {
                        database.trackDao().upsertTracks(tracksToInsert)
                    }
                    if (jobsToUpsert.isNotEmpty()) {
                        database.downloadJobDao().upsert(jobsToUpsert)
                    }
                }
            }

            // Vérification et téléchargement physique des MP3 terminés
            for (item in items) {
                if (item.status == "succeeded" || item.status == "completed") {
                    val appDir = File(System.getProperty("user.home"), ".aura")
                    val downloadsDir = File(appDir, "downloads")
                    val targetFile = File(downloadsDir, "${item.trackId.replace(':', ';')}.mp3")

                    val rawTrack = database.trackDao().getRawTrackById(item.trackId)
                    val isDbLinked = rawTrack != null && rawTrack.canonicalAudioSourceType == "downloaded" && rawTrack.isDownloadedByAura

                    if (!targetFile.exists() || targetFile.length() == 0L || !isDbLinked) {
                        if (rawTrack != null) {
                            fetchDownloadedFile(item.id, item.trackId, token)
                        }
                    }
                }
            }
        } finally {
            isSyncing.set(false)
        }
    }

    private suspend fun fetchDownloadedFile(jobId: String, trackId: String, token: String) = withContext(Dispatchers.IO) {
        try {
            System.out.println("Fetching physical MP3 file for succeeded job $jobId...")
            val response = apiService.downloadFile(token, jobId)
            if (response.status.value !in 200..299) {
                System.err.println("Failed to download physical file for job $jobId: HTTP ${response.status.value}")
                return@withContext
            }

            val appDir = File(System.getProperty("user.home"), ".aura")
            val downloadsDir = File(appDir, "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val targetFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().use { inputStream ->
                java.io.FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }

            val now = System.currentTimeMillis()
            val fileUri = targetFile.toURI().toString()
            val rawTrack = database.trackDao().getRawTrackById(trackId)

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    val mockMediaStoreId = System.currentTimeMillis()
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

                    if (rawTrack != null) {
                        val updatedTrack = rawTrack.copy(
                            canonicalAudioSourceType = "downloaded",
                            isDownloadedByAura = true,
                            updatedAt = now
                        )
                        database.trackDao().upsertTracks(listOf(updatedTrack))
                    }
                }
            }
            System.out.println("Downloaded file for job $jobId saved and committed to Room successfully.")
        } catch (e: Exception) {
            System.err.println("Failed to retrieve physical file for job $jobId: ${e.message}")
        }
    }
}
