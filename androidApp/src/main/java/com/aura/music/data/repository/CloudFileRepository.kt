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
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.call.body
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

    fun isCloudTrackSynced(trackId: String): Boolean {
        val ids = _syncedTrackIds.value
        if (ids.contains(trackId)) return true
        val cleanId = trackId.removePrefix("deezer:").removePrefix("ytm:")
        return ids.any {
            it == trackId || it.removePrefix("deezer:").removePrefix("ytm:") == cleanId
        }
    }

    suspend fun refreshSyncedTrackIds() {
        try {
            val response = apiService.listSyncFiles(SyncRepository.AUTH_TOKEN)
            val data = response.data
            if (response.error == null && data != null) {
                _syncedTrackIds.value = data.items.map { it.trackId }.toSet()
                reconcileCloudTracksWithDatabase(data.items)
                Log.i(TAG, "Refreshed synced track IDs: ${_syncedTrackIds.value.size} tracks")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh synced track IDs", e)
        }
    }

    suspend fun reconcileCloudTracksWithDatabase(items: List<com.aura.music.data.network.SyncedFileResponseData>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val serverCloudIds = items.map { it.trackId }.toSet()

            // 1. Purge any orphan cloud-only tracks from Room that are no longer on the Cloud server
            val allTracks = database.trackDao().getAllTracks()
            val activeJobTrackIds = try {
                database.downloadJobDao().getActiveJobs().map { it.trackId }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
            val playlistTrackIds = try {
                database.playlistDao().getAllPlaylistTrackIds().toSet()
            } catch (e: Exception) {
                emptySet()
            }
            val orphanIds = allTracks.filter { track ->
                track.contentUri.isNullOrBlank() &&
                track.id !in serverCloudIds &&
                track.id !in activeJobTrackIds &&
                track.id !in playlistTrackIds &&
                !track.isLiked &&
                !serverCloudIds.any { com.aura.music.ui.screens.isDeezerTrackMatch(it, track.id) }
            }.map { it.id }
            if (orphanIds.isNotEmpty()) {
                database.trackDao().deleteTracksByIds(orphanIds)
                Log.i(TAG, "Purged ${orphanIds.size} orphan Cloud tracks from Room: $orphanIds")
            }

            // 2. Reconcile / insert missing Cloud tracks
            for (item in items) {
                val existingTrack = database.trackDao().getRawTrackById(item.trackId)
                if (existingTrack == null) {
                    val fileTitle = item.title ?: "Piste Cloud ${item.trackId}"
                    val artist = item.artistName ?: "Artiste Inconnu"
                    val album = item.albumTitle ?: "Album Inconnu"
                    val duration = item.durationMs ?: 0L
                    val artistId = item.artistId
                    val albumId = item.albumId

                    if (artistId != null) {
                        val placeholderArtist = com.aura.music.data.local.ArtistEntity(
                            id = artistId,
                            name = artist,
                            normalizedName = artist.lowercase().trim(),
                            pictureUri = item.coverUri,
                            artworkOrigin = null,
                            artworkLastResolvedAt = null,
                            summary = null,
                            createdAt = now,
                            updatedAt = now
                        )
                        database.artistDao().insertArtistsIgnore(listOf(placeholderArtist))
                    }

                    if (albumId != null) {
                        val placeholderAlbum = com.aura.music.data.local.AlbumEntity(
                            id = albumId,
                            primaryArtistId = artistId,
                            title = album,
                            normalizedTitle = album.lowercase().trim(),
                            coverUri = item.coverUri,
                            artworkOrigin = null,
                            artworkLastResolvedAt = null,
                            releaseDate = null,
                            trackCount = null,
                            createdAt = now,
                            updatedAt = now
                        )
                        database.albumDao().insertAlbumsIgnore(listOf(placeholderAlbum))
                    }

                    val newTrack = com.aura.music.data.local.TrackEntity(
                        id = item.trackId,
                        primaryArtistId = artistId,
                        albumId = albumId,
                        title = fileTitle,
                        normalizedTitle = fileTitle.lowercase().trim(),
                        displayArtistName = artist,
                        displayAlbumTitle = album,
                        durationMs = duration,
                        coverUri = item.coverUri,
                        canonicalAudioSourceType = "cloud",
                        isLiked = false,
                        isDownloadedByAura = false,
                        isExplicit = false,
                        popularity = 0,
                        genresJson = null,
                        createdAt = now,
                        updatedAt = now
                    )
                    database.trackDao().upsertTrack(newTrack)
                    Log.d(TAG, "Reconciled missing Cloud TrackEntity in Room for ${item.trackId} - $fileTitle")
                } else {
                    var updated = false
                    var trackToUpdate = existingTrack
                    if (existingTrack.coverUri.isNullOrBlank() && !item.coverUri.isNullOrBlank()) {
                        trackToUpdate = trackToUpdate.copy(coverUri = item.coverUri)
                        updated = true
                    }
                    if (trackToUpdate.canonicalAudioSourceType != "downloaded" && trackToUpdate.canonicalAudioSourceType != "local" && trackToUpdate.canonicalAudioSourceType != "cloud") {
                        trackToUpdate = trackToUpdate.copy(canonicalAudioSourceType = "cloud")
                        updated = true
                    }
                    if (updated) {
                        database.trackDao().upsertTrack(trackToUpdate.copy(updatedAt = now))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconcile cloud tracks with database", e)
        }
    }

    suspend fun autoDownloadFavoriteTrack(trackId: String) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_download_favorites", false)) return@withContext
        try {
            val trackRow = database.trackDao().getTrackById(trackId)
            if (trackRow != null && !trackRow.contentUri.isNullOrBlank()) {
                Log.i(TAG, "Favorite track $trackId is already present on device.")
                return@withContext
            }
            Log.i(TAG, "Auto-downloading favorite track $trackId...")
            downloadTrack(
                trackId = trackId,
                title = trackRow?.title,
                artistName = trackRow?.artistName,
                albumTitle = trackRow?.albumTitle,
                durationMs = trackRow?.durationMs,
                artistId = trackRow?.artistId,
                albumId = trackRow?.albumId,
                coverUri = trackRow?.coverUri
            ).collect { res ->
                res.onSuccess {
                    Log.i(TAG, "Successfully auto-downloaded favorite track $trackId to ${it.absolutePath}")
                }.onFailure { err ->
                    Log.w(TAG, "Failed auto-downloading favorite track $trackId: ${err.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-download favorite track $trackId exception", e)
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

            val fileBytes: ByteArray? = if (localUri.startsWith("file://") || localUri.startsWith("/")) {
                val filePath = if (localUri.startsWith("file://")) localUri.substring(7) else localUri
                val f = File(filePath)
                if (f.exists()) f.readBytes() else null
            } else {
                try {
                    val uri = Uri.parse(localUri)
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.readBytes()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open input stream for $localUri", e)
                    null
                }
            }

            if (fileBytes == null || fileBytes.isEmpty()) {
                emit(Result.failure(Exception("Impossible de lire les octets du fichier local")))
                return@flow
            }

            val mimeType = if (localUri.endsWith(".m4a", ignoreCase = true)) "audio/mp4" else if (localUri.endsWith(".wav", ignoreCase = true)) "audio/wav" else "audio/mpeg"

            var uploadCoverUri = trackRow.coverUri
            if (uploadCoverUri.isNullOrBlank() || !uploadCoverUri.startsWith("http")) {
                try {
                    val queryArtist = trackRow.artistName ?: ""
                    val searchResult = apiService.search("${trackRow.title} $queryArtist".trim(), limitTracks = 3)
                    val resolved = searchResult.data?.tracks?.firstOrNull { it.coverUri?.startsWith("http") == true }?.coverUri
                    if (resolved != null) {
                        uploadCoverUri = resolved
                        // Update Room local entry to preserve the resolved HTTPS cover (if it was null/empty)
                        val rawTrack = database.trackDao().getRawTrackById(trackId)
                        if (rawTrack != null && rawTrack.coverUri.isNullOrBlank()) {
                            database.useWriterConnection { transactor ->
                                transactor.immediateTransaction {
                                    val updatedTrack = rawTrack.copy(coverUri = resolved)
                                    database.trackDao().upsertTrack(updatedTrack)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve cover online for upload of track $trackId", e)
                }
            }

            val response = apiService.uploadSyncFile(
                token = SyncRepository.AUTH_TOKEN,
                trackId = trackId,
                fileBytes = fileBytes,
                mimeType = mimeType,
                title = trackRow.title,
                artistName = trackRow.artistName,
                albumTitle = trackRow.albumTitle,
                durationMs = trackRow.durationMs,
                artistId = trackRow.artistId,
                albumId = trackRow.albumId,
                coverUri = uploadCoverUri
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
    fun downloadTrack(
        trackId: String,
        title: String? = null,
        artistName: String? = null,
        albumTitle: String? = null,
        durationMs: Long? = null,
        artistId: String? = null,
        albumId: String? = null,
        coverUri: String? = null
    ): Flow<Result<File>> = flow {
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

            val targetFile = File(downloadsDir, "${trackId.replace(':', ';')}.mp3")
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

            // 1. Resolve online cover if needed
            var resolvedCoverUri = coverUri
            if (resolvedCoverUri.isNullOrBlank() || !resolvedCoverUri.startsWith("http")) {
                try {
                    val query = "${title ?: ""} ${artistName ?: ""}".trim()
                    if (query.isNotEmpty()) {
                        val searchResult = apiService.search(query, limitTracks = 3)
                        val resolved = searchResult.data?.tracks?.firstOrNull { it.coverUri?.startsWith("http") == true }?.coverUri
                        if (resolved != null) {
                            resolvedCoverUri = resolved
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve cover online for cloud track $trackId", e)
                }
            }

            // 2. Download cover to local covers cache for offline-first use
            var localCoverUri: String? = null
            if (resolvedCoverUri != null && resolvedCoverUri.startsWith("http")) {
                val client = HttpClient()
                try {
                    val imageResponse = client.get(resolvedCoverUri)
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
                        Log.i(TAG, "Downloaded remote cover from $resolvedCoverUri for $trackId to $localCoverUri")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download remote cover fallback for $trackId", e)
                } finally {
                    client.close()
                }
            }

            // Link in local DB
            val now = System.currentTimeMillis()
            val fileUri = Uri.fromFile(targetFile).toString()

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    val mockMediaStoreId = System.currentTimeMillis()
                    
                    // Reconstruct parent TrackEntity if deleted/missing
                    var rawTrack = database.trackDao().getRawTrackById(trackId)
                    if (rawTrack == null) {
                        var fileTitle = title ?: "Piste Cloud $trackId"
                        var artist = artistName ?: "Artiste Inconnu"
                        var album = albumTitle ?: "Album Inconnu"
                        var duration = durationMs ?: 0L

                        // Fallback to ID3 tags if metadata parameters are missing
                        if (title == null || artistName == null || albumTitle == null || durationMs == null) {
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(targetFile.absolutePath)
                                fileTitle = title ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileTitle
                                artist = artistName ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
                                album = albumTitle ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
                                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                duration = durationMs ?: durationStr?.toLongOrNull() ?: duration
                            } catch (retrieverEx: Exception) {
                                Log.w(TAG, "Could not extract ID3 metadata from $trackId", retrieverEx)
                            } finally {
                                try {
                                    retriever.release()
                                } catch (e: Exception) {}
                            }
                        }

                        val newTrack = com.aura.music.data.local.TrackEntity(
                            id = trackId,
                            primaryArtistId = artistId,
                            albumId = albumId,
                            title = fileTitle,
                            normalizedTitle = fileTitle.lowercase().trim(),
                            displayArtistName = artist,
                            displayAlbumTitle = album,
                            durationMs = duration,
                            coverUri = localCoverUri ?: resolvedCoverUri ?: coverUri,
                            canonicalAudioSourceType = "downloaded",
                            isLiked = false,
                            isDownloadedByAura = true,
                            isExplicit = false,
                            popularity = 0,
                            genresJson = null,
                            createdAt = now,
                            updatedAt = now
                        )

                        // Ensure Artist exists to satisfy Foreign Key constraint
                        if (artistId != null) {
                            val placeholderArtist = com.aura.music.data.local.ArtistEntity(
                                id = artistId,
                                name = artist,
                                normalizedName = artist.lowercase().trim(),
                                pictureUri = null,
                                artworkOrigin = null,
                                artworkLastResolvedAt = null,
                                summary = null,
                                createdAt = now,
                                updatedAt = now
                            )
                            database.artistDao().insertArtistsIgnore(listOf(placeholderArtist))
                            Log.d(TAG, "Created placeholder ArtistEntity for constraint: $artistId")
                        }

                        // Ensure Album exists to satisfy Foreign Key constraint
                        if (albumId != null) {
                            val placeholderAlbum = com.aura.music.data.local.AlbumEntity(
                                id = albumId,
                                primaryArtistId = artistId,
                                title = album,
                                normalizedTitle = album.lowercase().trim(),
                                coverUri = localCoverUri ?: resolvedCoverUri ?: coverUri,
                                artworkOrigin = null,
                                artworkLastResolvedAt = null,
                                releaseDate = null,
                                trackCount = null,
                                createdAt = now,
                                updatedAt = now
                            )
                            database.albumDao().insertAlbumsIgnore(listOf(placeholderAlbum))
                            Log.d(TAG, "Created placeholder AlbumEntity for constraint: $albumId")
                        }

                        database.trackDao().upsertTrack(newTrack)
                        Log.d(TAG, "Dynamically reconstructed and saved TrackEntity for deleted/missing track $trackId")
                        rawTrack = newTrack
                    } else {
                        // Update Track status to downloaded
                        val updatedTrack = rawTrack.copy(
                            canonicalAudioSourceType = "downloaded",
                            isDownloadedByAura = true,
                            coverUri = localCoverUri ?: resolvedCoverUri ?: rawTrack.coverUri,
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
            reconcileCloudTracksWithDatabase(items)
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

            val trackRow = database.trackDao().getTrackById(trackId)
            if (trackRow == null || trackRow.contentUri.isNullOrBlank()) {
                database.trackDao().deleteTracksByIds(listOf(trackId))
                Log.i(TAG, "Deleted cloud-only track $trackId from Room database")
            }

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

    suspend fun getSettings(): com.aura.music.data.local.UserSettingsEntity? = withContext(Dispatchers.IO) {
        database.userSettingsDao().getSettings()
    }

    suspend fun updateSyncEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        database.userSettingsDao().updateSyncEnabled(enabled)
    }

    suspend fun removeLocalFile(trackId: String) = withContext(Dispatchers.IO) {
        try {
            val trackRow = database.trackDao().getTrackById(trackId) ?: return@withContext
            val uriStr = trackRow.contentUri
            if (!uriStr.isNullOrBlank() && (uriStr.startsWith("file://") || uriStr.startsWith("/"))) {
                val path = if (uriStr.startsWith("file://")) uriStr.substring(7) else uriStr
                val file = java.io.File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    database.trackDao().deleteTrackMediaLinksByTrackId(trackId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove local file for track $trackId", e)
        }
    }
}
