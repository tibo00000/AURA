package com.aura.music.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.SyncedFileResponseData
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class CloudFileRow(
    val trackId: String,
    val title: String,
    val artistName: String,
    val sizeBytes: Long,
    val uploadedAt: String?,
    val isPresentLocally: Boolean,
)

class CloudFileRepository(
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val context: Context,
) {
    suspend fun uploadLocalTrack(trackId: String, token: String): Result<SyncedFileResponseData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mediaLink = database.trackDao().getMediaLinkForTrack(trackId)
                    ?: error("Aucun fichier local associe a cette piste.")
                val uri = Uri.parse(mediaLink.contentUri)
                val resolver = context.contentResolver
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Impossible de lire le fichier audio.")
                val fileName = queryDisplayName(uri) ?: "${trackId.substringAfterLast(':')}.audio"
                val mimeType = mediaLink.mimeType ?: resolver.getType(uri) ?: "application/octet-stream"

                val response = apiService.uploadSyncedFile(
                    token = token,
                    trackId = trackId,
                    fileName = fileName,
                    mimeType = mimeType,
                    bytes = bytes,
                )
                response.data ?: error(response.error?.message ?: "Upload cloud refuse par le serveur.")
            }.onFailure { error ->
                Log.e(TAG, "Failed to upload local track $trackId", error)
            }
        }

    suspend fun listRecentCloudFiles(token: String, limit: Int = 20): Result<List<CloudFileRow>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = apiService.listSyncedFiles(token)
                val items = response.data?.items ?: error(response.error?.message ?: "Liste cloud indisponible.")
                items.take(limit).map { item ->
                    val localTrack = database.trackDao().getTrackById(item.trackId)
                    CloudFileRow(
                        trackId = item.trackId,
                        title = localTrack?.title ?: item.trackId,
                        artistName = localTrack?.artistName ?: "Piste cloud",
                        sizeBytes = item.sizeBytes,
                        uploadedAt = item.uploadedAt ?: item.updatedAt,
                        isPresentLocally = localTrack?.contentUri != null,
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to list synced cloud files", error)
            }
        }

    suspend fun getMissingCloudFileCount(token: String): Int = withContext(Dispatchers.IO) {
        listRecentCloudFiles(token, limit = 100)
            .getOrDefault(emptyList())
            .count { !it.isPresentLocally }
    }

    suspend fun recoverCloudFile(trackId: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val response = apiService.downloadSyncedFile(token, trackId)
            if (response.status.value !in 200..299) {
                error("Telechargement cloud refuse: HTTP ${response.status.value}")
            }

            val downloadsDir = File(context.filesDir, "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val targetFile = File(downloadsDir, "${safeFileStem(trackId)}.audio")
            response.bodyAsChannel().toInputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val now = System.currentTimeMillis()
            val fileUri = Uri.fromFile(targetFile).toString()
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    val rawTrack = database.trackDao().getRawTrackById(trackId)
                    if (rawTrack == null) {
                        database.trackDao().upsertTrack(
                            TrackEntity(
                                id = trackId,
                                primaryArtistId = null,
                                albumId = null,
                                title = trackId,
                                normalizedTitle = trackId.lowercase(),
                                displayArtistName = "Piste cloud",
                                displayAlbumTitle = null,
                                durationMs = null,
                                coverUri = null,
                                canonicalAudioSourceType = "downloaded",
                                isLiked = false,
                                isDownloadedByAura = true,
                                isExplicit = null,
                                popularity = null,
                                genresJson = null,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                    }
                    database.trackDao().upsertTrackMediaLinks(
                        listOf(
                            TrackMediaLinkEntity(
                                id = "media-link:cloud:${safeFileStem(trackId)}",
                                trackId = trackId,
                                mediaStoreId = -now,
                                contentUri = fileUri,
                                fileSizeBytes = targetFile.length(),
                                mimeType = "audio/mpeg",
                                dateModifiedEpochMs = now,
                                availabilityStatus = "present",
                                lastScannedAt = now,
                            )
                        )
                    )

                    if (rawTrack != null) {
                        database.trackDao().upsertTrack(
                            rawTrack.copy(
                                canonicalAudioSourceType = "downloaded",
                                isDownloadedByAura = true,
                                updatedAt = now,
                            )
                        )
                    }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to recover cloud file $trackId", error)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    private fun safeFileStem(trackId: String): String =
        trackId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val TAG = "CloudFileRepository"
    }
}
