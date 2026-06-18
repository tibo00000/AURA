package com.aura.music.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.SyncedFileResponseData
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CloudFileRepository(
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val context: Context
) {
    companion object {
        private const val TAG = "CloudFileRepository"
    }

    private val _syncedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val syncedTrackIds = _syncedTrackIds.asStateFlow()

    suspend fun refreshSyncedTrackIds() {
        try {
            val response = apiService.listSyncFiles(SyncRepository.AUTH_TOKEN)
            val data = response.data
            if (response.error == null && data != null) {
                _syncedTrackIds.value = data.items.map { it.trackId }.toSet()
                Log.i(TAG, "Refreshed synced track IDs: ${_syncedTrackIds.value.size} tracks")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh synced track IDs", e)
        }
    }

    /**
     * Uploads a locally scanned track to the cloud.
     */
    fun uploadTrack(trackId: String): Flow<Result<SyncedFileResponseData>> = flow {
        try {
            val trackRow = database.trackDao().getTrackById(trackId)
            if (trackRow == null) {
                emit(Result.failure(Exception("Piste introuvable localement")))
                return@flow
            }

            val localUri = trackRow.contentUri
            if (localUri.isNullOrBlank()) {
                emit(Result.failure(Exception("Le fichier physique de la piste est manquant")))
                return@flow
            }

            Log.i(TAG, "Starting cloud upload for track $trackId (URI: $localUri)")

            val contentResolver = context.contentResolver
            val uri = Uri.parse(localUri)
            val fileBytes = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }

            if (fileBytes == null || fileBytes.isEmpty()) {
                emit(Result.failure(Exception("Impossible de lire les octets du fichier local")))
                return@flow
            }

            val mimeType = contentResolver.getType(uri) ?: "audio/mpeg"

            val response = apiService.uploadSyncFile(
                token = SyncRepository.AUTH_TOKEN,
                trackId = trackId,
                fileBytes = fileBytes,
                mimeType = mimeType
            )

            val data = response.data
            if (response.error != null || data == null) {
                val errorMsg = response.error?.message ?: "Erreur inconnue de l'API d'upload"
                emit(Result.failure(Exception(errorMsg)))
                return@flow
            }

            Log.i(TAG, "Successfully uploaded track $trackId to cloud")
            _syncedTrackIds.value = _syncedTrackIds.value + trackId
            emit(Result.success(data))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload track $trackId", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads a track from the cloud to the local device downloads folder.
     */
    fun downloadTrack(trackId: String): Flow<Result<File>> = flow {
        try {
            Log.i(TAG, "Downloading track $trackId from cloud...")
            val response = apiService.downloadSyncFile(SyncRepository.AUTH_TOKEN, trackId)
            
            if (response.status.value !in 200..299) {
                emit(Result.failure(Exception("Erreur serveur lors du téléchargement: HTTP ${response.status.value}")))
                return@flow
            }

            val downloadsDir = File(context.filesDir, "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val targetFile = File(downloadsDir, "$trackId.mp3")
            if (targetFile.exists()) {
                targetFile.delete()
            }

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

            Log.i(TAG, "Saved cloud file to ${targetFile.absolutePath} (${targetFile.length()} bytes)")

            // Link in local DB
            val now = System.currentTimeMillis()
            val fileUri = Uri.fromFile(targetFile).toString()

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    val mockMediaStoreId = System.currentTimeMillis()
                    
                    // Reconstruct parent TrackEntity if deleted/missing
                    var rawTrack = database.trackDao().getRawTrackById(trackId)
                    if (rawTrack == null) {
                        var fileTitle = "Piste Cloud $trackId"
                        var artistName = "Artiste Inconnu"
                        var albumTitle = "Album Inconnu"
                        var durationMs = 0L

                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(targetFile.absolutePath)
                            fileTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileTitle
                            artistName = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artistName
                            albumTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: albumTitle
                            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            durationMs = durationStr?.toLongOrNull() ?: 0L
                        } catch (retrieverEx: Exception) {
                            Log.w(TAG, "Could not extract ID3 metadata from $trackId", retrieverEx)
                        } finally {
                            try {
                                retriever.release()
                            } catch (e: Exception) {}
                        }

                        val newTrack = com.aura.music.data.local.TrackEntity(
                            id = trackId,
                            primaryArtistId = null,
                            albumId = null,
                            title = fileTitle,
                            normalizedTitle = fileTitle.lowercase().trim(),
                            displayArtistName = artistName,
                            displayAlbumTitle = albumTitle,
                            durationMs = durationMs,
                            coverUri = null,
                            canonicalAudioSourceType = "downloaded",
                            isLiked = false,
                            isDownloadedByAura = true,
                            isExplicit = false,
                            popularity = 0,
                            genresJson = null,
                            createdAt = now,
                            updatedAt = now
                        )
                        database.trackDao().upsertTrack(newTrack)
                        Log.d(TAG, "Dynamically reconstructed and saved TrackEntity for deleted/missing track $trackId")
                        rawTrack = newTrack
                    } else {
                        // Update Track status to downloaded
                        val updatedTrack = rawTrack.copy(
                            canonicalAudioSourceType = "downloaded",
                            isDownloadedByAura = true,
                            updatedAt = now
                        )
                        database.trackDao().upsertTrack(updatedTrack)
                        Log.d(TAG, "Updated local TrackEntity $trackId to downloaded state")
                    }

                    // Create media link
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
                }
            }

            emit(Result.success(targetFile))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download track $trackId", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Retrieves all files synchronized on the cloud server.
     */
    fun listCloudFiles(): Flow<Result<List<SyncedFileResponseData>>> = flow {
        try {
            val response = apiService.listSyncFiles(SyncRepository.AUTH_TOKEN)
            val data = response.data
            if (response.error != null || data == null) {
                val errorMsg = response.error?.message ?: "Erreur inconnue de l'API cloud list"
                emit(Result.failure(Exception(errorMsg)))
                return@flow
            }
            val items = data.items
            _syncedTrackIds.value = items.map { it.trackId }.toSet()
            emit(Result.success(items))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list cloud files", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Checks if a track is missing locally but present in the cloud list.
     */
    suspend fun isTrackCloudOnly(trackId: String, cloudFiles: List<SyncedFileResponseData>): Boolean = withContext(Dispatchers.IO) {
        val inCloud = cloudFiles.any { it.trackId == trackId }
        if (!inCloud) return@withContext false
        val trackRow = database.trackDao().getTrackById(trackId)
        return@withContext trackRow == null || trackRow.contentUri.isNullOrBlank()
    }

    /**
     * Deletes a file from the cloud server.
     */
    fun deleteSyncFile(trackId: String): Flow<Result<Unit>> = flow {
        try {
            Log.i(TAG, "Deleting track $trackId from cloud...")
            val response = apiService.deleteSyncFile(SyncRepository.AUTH_TOKEN, trackId)
            val data = response.data
            if (response.error != null || data == null || !data.deleted) {
                val errorMsg = response.error?.message ?: "Erreur de suppression du fichier cloud"
                emit(Result.failure(Exception(errorMsg)))
                return@flow
            }
            Log.i(TAG, "Successfully deleted track $trackId from cloud")
            _syncedTrackIds.value = _syncedTrackIds.value - trackId
            emit(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete cloud file $trackId", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Retrieves all local tracks stored in the database.
     */
    suspend fun getLocalTracks(): List<com.aura.music.data.local.TrackListRow> = withContext(Dispatchers.IO) {
        database.trackDao().getAllTracks()
    }
}
