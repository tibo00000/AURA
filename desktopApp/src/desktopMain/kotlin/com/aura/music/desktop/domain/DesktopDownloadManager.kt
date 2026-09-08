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
    private val activeJobIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failedJobFetchIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cancelledJobIds = java.util.Collections.synchronizedSet(
        object : java.util.LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= 100) {
                    val first = iterator().next()
                    remove(first)
                }
                return super.add(element)
            }
        }
    )

    fun startLoop(intervalMs: Long = 3000L) {
        downloadSyncJob?.cancel()
        downloadSyncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val token = apiToken
                var hasActiveJobs = false
                if (!token.isNullOrBlank()) {
                    try {
                        hasActiveJobs = syncActiveJobs(token)
                    } catch (e: Exception) {
                        System.err.println("Error syncing download jobs: ${e.message}")
                    }
                }
                // Si des téléchargements sont en cours, boucle courte (3s).
                // Sinon, boucle espacée (30s) pour éviter de requêter et rafraîchir inutilement.
                delay(if (hasActiveJobs) intervalMs else 30000L)
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
            val now = System.currentTimeMillis()
            val existing = database.trackDao().getRawTrackById(track.id)
            if (existing == null) {
                database.trackDao().upsertTracks(
                    listOf(
                        TrackEntity(
                            id = track.id,
                            primaryArtistId = null,
                            albumId = null,
                            title = track.title,
                            normalizedTitle = track.title.lowercase().trim(),
                            displayArtistName = track.displayArtistName,
                            displayAlbumTitle = track.displayAlbumTitle,
                            durationMs = track.durationMs.toLong(),
                            coverUri = track.coverUri,
                            canonicalAudioSourceType = "cloud_only",
                            isLiked = false,
                            isDownloadedByAura = false,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                )
            }

            val response = apiService.createDownload(
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
            response.data?.jobId?.let { activeJobIds.add(it) }
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
            activeJobIds.add(jobId)
            apiService.retryDownload(token, jobId)
            syncActiveJobs(token)
        } catch (e: Exception) {
            System.err.println("Failed to retry job $jobId: ${e.message}")
            throw e
        }
    }

    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        cancelledJobIds.add(jobId)
        activeJobIds.remove(jobId)
        database.downloadJobDao().deleteJob(jobId)
    }

    suspend fun clearCompletedJobs() = withContext(Dispatchers.IO) {
        database.downloadJobDao().clearCompletedJobs()
    }

    suspend fun syncActiveJobs(token: String): Boolean = withContext(Dispatchers.IO) {
        if (!isSyncing.compareAndSet(false, true)) return@withContext false
        try {
            val response = apiService.listDownloads(token = token)
            val items = response.data?.items ?: return@withContext false
            val now = System.currentTimeMillis()

            // Purge les IDs qui ne sont plus retournés par le serveur
            val currentRemoteJobIds = items.map { it.id }.toSet()
            cancelledJobIds.retainAll(currentRemoteJobIds)

            val jobsToUpsert = mutableListOf<DownloadJobEntity>()
            val tracksToInsert = mutableListOf<TrackEntity>()

            val allTrackIds = items.map { it.trackId }.distinct()
            val existingTrackIds = database.trackDao().getTracksByIds(allTrackIds).map { it.id }.toSet()
            val knownTrackIds = existingTrackIds.toMutableSet()

            for (item in items) {
                // Ne pas réinsérer un job annulé localement
                if (cancelledJobIds.contains(item.id)) {
                    continue
                }

                // Toujours garantir l'existence de TrackEntity pour respecter la contrainte FOREIGN KEY
                if (!knownTrackIds.contains(item.trackId)) {
                    knownTrackIds.add(item.trackId)
                    val deezerId = com.aura.music.desktop.utils.DesktopTrackMatcher.extractDeezerId(item.trackId)
                    val placeholderTitle = if (deezerId != null) "Piste Deezer $deezerId" else "Titre ${item.trackId}"
                    tracksToInsert.add(
                        TrackEntity(
                            id = item.trackId,
                            primaryArtistId = null,
                            albumId = null,
                            title = placeholderTitle,
                            normalizedTitle = placeholderTitle.lowercase().trim(),
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

                val existing = database.downloadJobDao().getJobById(item.id)
                val isUnchanged = existing != null &&
                    existing.status == item.status &&
                    existing.progressPercent == item.progressPercent &&
                    existing.errorCode == item.errorCode &&
                    existing.errorMessage == item.errorMessage

                if (!isUnchanged) {
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
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now
                        )
                    )
                }
            }

            // Transaction atomique uniquement si des changements réels sont détectés
            if (tracksToInsert.isNotEmpty() || jobsToUpsert.isNotEmpty()) {
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
            }

            // 1. Enregistrer les jobs actifs en cours d'exécution
            for (item in items) {
                if (item.status == "queued" || item.status == "running" || item.status == "downloading" || item.status == "pending") {
                    activeJobIds.add(item.id)
                }
            }

            // 2. Téléchargement physique uniquement pour les jobs activement surveillés qui viennent de se terminer
            for (item in items) {
                if (activeJobIds.contains(item.id)) {
                    if (item.status == "succeeded" || item.status == "completed") {
                        val rawTrack = database.trackDao().getRawTrackById(item.trackId)
                        if (rawTrack != null) {
                            fetchDownloadedFile(item.id, item.trackId, token)
                        }
                        activeJobIds.remove(item.id)
                    } else if (item.status == "failed" || item.status == "cancelled") {
                        activeJobIds.remove(item.id)
                    }
                }
            }

            items.any { it.status == "queued" || it.status == "running" || it.status == "pending" || it.status == "downloading" }
        } finally {
            isSyncing.set(false)
        }
    }

    private suspend fun fetchDownloadedFile(jobId: String, trackId: String, token: String) = withContext(Dispatchers.IO) {
        try {
            System.out.println("Fetching physical MP3 file for succeeded job $jobId...")
            val response = apiService.downloadFile(token, jobId)
            if (response.status.value == 404) {
                System.err.println("Physical file for job $jobId not found on server (HTTP 404). Silencing retries.")
                failedJobFetchIds.add(jobId)
                return@withContext
            }
            if (response.status.value !in 200..299) {
                System.err.println("Failed to download physical file for job $jobId: HTTP ${response.status.value}")
                return@withContext
            }

            val appDir = File(System.getProperty("user.home"), ".aura")
            val downloadsDir = File(appDir, "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val cleanId = trackId.replace(':', ';')
            val tempFile = File(downloadsDir, "$cleanId.tmp")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().use { inputStream ->
                java.io.FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }

            val contentType = response.headers["Content-Type"]
            val ext = com.aura.music.desktop.media.DesktopMediaMetadataReader.detectAudioExtension(tempFile, contentType)
            val targetFile = File(downloadsDir, "$cleanId.$ext")
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)

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
