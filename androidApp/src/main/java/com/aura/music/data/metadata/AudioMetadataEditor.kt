package com.aura.music.data.metadata

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.aura.music.core.ImageCompressionUtils
import com.aura.music.data.local.AlbumEntity
import com.aura.music.data.local.ArtistEntity
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LocalLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Composant spécialisé dans l'édition de métadonnées audio, la compression de pochettes
 * et la mise à jour synchronisée des tags physiques ID3v2.3 et de la base Room.
 */
class AudioMetadataEditor(
    private val database: AuraDatabase,
    private val context: Context,
    private val onInvalidateSearchIndex: () -> Unit,
) {
    suspend fun editTrackMetadata(
        trackId: String,
        title: String,
        artistName: String,
        albumTitle: String?,
        trackNumber: Int?,
        year: Int?,
        coverSourceUriOrUrl: String?,
        coverSourceBytes: ByteArray?,
    ): TrackListRow = withContext(Dispatchers.IO) {
        val existingTrack = database.trackDao().getRawTrackById(trackId)
            ?: throw IllegalArgumentException("Track not found in database: $trackId")

        val now = System.currentTimeMillis()
        val artistId = artistIdOf(artistName)
        val albumId = albumTitle?.let { albumIdOf(artistName, it) }

        // 1. Gestion et compression de la pochette (500x500 max, JPEG 80%)
        var finalCoverUri: String? = existingTrack.coverUri
        var coverJpegBytes: ByteArray? = null

        val coversDir = File(context.filesDir, "covers")
        if (!coversDir.exists()) coversDir.mkdirs()
        val targetCoverFile = File(coversDir, "${trackId.replace(':', ';')}.jpg")

        if (coverSourceBytes != null && coverSourceBytes.isNotEmpty()) {
            val success = ImageCompressionUtils.compressAndSaveBytes(coverSourceBytes, targetCoverFile)
            if (success) {
                finalCoverUri = Uri.fromFile(targetCoverFile).toString()
                coverJpegBytes = ImageCompressionUtils.getCompressedJpegBytes(targetCoverFile)
            }
        } else if (!coverSourceUriOrUrl.isNullOrBlank()) {
            val success = if (coverSourceUriOrUrl.startsWith("http://") || coverSourceUriOrUrl.startsWith("https://")) {
                ImageCompressionUtils.downloadAndCompressImage(coverSourceUriOrUrl, targetCoverFile)
            } else {
                val uri = Uri.parse(coverSourceUriOrUrl)
                ImageCompressionUtils.compressAndSaveUri(context, uri, targetCoverFile)
            }
            if (success) {
                finalCoverUri = Uri.fromFile(targetCoverFile).toString()
                coverJpegBytes = ImageCompressionUtils.getCompressedJpegBytes(targetCoverFile)
            }
        } else if (targetCoverFile.exists()) {
            coverJpegBytes = ImageCompressionUtils.getCompressedJpegBytes(targetCoverFile)
        }

        // 2. Écriture physique atomique des tags ID3 si fichier présent
        val contentUri = database.trackDao().getTrackContentUri(trackId)
        var physicalFile: File? = null

        if (!contentUri.isNullOrBlank()) {
            if (contentUri.startsWith("file://") || contentUri.startsWith("/")) {
                val path = if (contentUri.startsWith("file://")) contentUri.removePrefix("file://") else contentUri
                val f = File(path)
                if (f.exists()) physicalFile = f
            } else if (contentUri.startsWith("content://")) {
                try {
                    val uri = Uri.parse(contentUri)
                    val proj = arrayOf(MediaStore.Audio.Media.DATA)
                    context.contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                            if (dataIdx != -1) {
                                val path = cursor.getString(dataIdx)
                                if (!path.isNullOrBlank()) {
                                    val f = File(path)
                                    if (f.exists()) physicalFile = f
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AudioMetadataEditor", "Could not resolve physical file from contentUri: ${e.message}")
                }
            }
        }

        // Recherche alternative dans le dossier downloads interne
        if (physicalFile == null) {
            val localFile = File(context.filesDir, "downloads/${trackId.replace(':', ';')}.mp3")
            if (localFile.exists()) physicalFile = localFile
        }

        val targetFile = physicalFile
        if (targetFile != null && targetFile.exists()) {
            try {
                AudioTagWriter.writeMp3Tags(
                    audioFile = targetFile,
                    title = title,
                    artistName = artistName,
                    albumTitle = albumTitle,
                    trackNumber = trackNumber,
                    year = year,
                    coverJpegBytes = coverJpegBytes
                )
                // Notification MediaScanner
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("audio/mpeg"),
                    null
                )
            } catch (e: Exception) {
                Log.w("AudioMetadataEditor", "Physical tag write failed: ${e.message}")
            }
        }

        // 3. Persistance Room (Artist, Album, Track)
        val artistEntity = ArtistEntity(
            id = artistId,
            name = artistName,
            normalizedName = normalize(artistName),
            pictureUri = null,
            summary = null,
            createdAt = now,
            updatedAt = now
        )
        database.artistDao().upsertArtists(listOf(artistEntity))

        if (albumId != null && albumTitle != null) {
            val albumEntity = AlbumEntity(
                id = albumId,
                primaryArtistId = artistId,
                title = albumTitle,
                normalizedTitle = normalize(albumTitle),
                coverUri = finalCoverUri,
                releaseDate = year,
                trackCount = null,
                createdAt = now,
                updatedAt = now
            )
            database.albumDao().upsertAlbums(listOf(albumEntity))
        }

        val updatedTrack = existingTrack.copy(
            title = title,
            normalizedTitle = normalize(title),
            displayArtistName = artistName,
            displayAlbumTitle = albumTitle,
            primaryArtistId = artistId,
            albumId = albumId,
            coverUri = finalCoverUri,
            updatedAt = now
        )
        database.trackDao().upsertTrack(updatedTrack)
        onInvalidateSearchIndex()

        TrackListRow(
            id = updatedTrack.id,
            artistId = artistId,
            albumId = albumId,
            title = updatedTrack.title,
            artistName = updatedTrack.displayArtistName,
            albumTitle = updatedTrack.displayAlbumTitle,
            contentUri = contentUri,
            durationMs = updatedTrack.durationMs ?: 0L,
            coverUri = updatedTrack.coverUri,
            isLiked = updatedTrack.isLiked
        )
    }

    private fun artistIdOf(artistName: String): String = "artist:${normalize(artistName)}"

    private fun albumIdOf(artistName: String, albumTitle: String): String =
        "album:${normalize(artistName)}:${normalize(albumTitle)}"

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
