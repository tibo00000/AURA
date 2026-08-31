package com.aura.music.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.room3.useWriterConnection
import androidx.room3.immediateTransaction
import com.aura.music.data.local.AlbumEntity
import com.aura.music.data.local.ArtistEntity
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.TrackMediaLinkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Composant spécialisé dans l'indexation, l'analyse et la réconciliation
 * des fichiers audio locaux (MediaStore et dossier privé downloads/).
 */
class LocalMediaScanner(
    private val database: AuraDatabase,
    private val mediaStoreAudioDataSource: MediaStoreAudioDataSource,
    private val context: Context,
    private val onInvalidateSearchIndex: () -> Unit,
) {
    suspend fun syncLocalMedia(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val scannedArtists = mutableMapOf<String, ArtistEntity>()
        val scannedAlbums = mutableMapOf<String, AlbumEntity>()
        val scannedTracks = mutableListOf<TrackEntity>()
        val scannedMediaLinks = mutableListOf<TrackMediaLinkEntity>()

        // 1. Scan private downloads/ directory
        val downloadsDir = File(context.filesDir, "downloads")
        if (downloadsDir.exists() && downloadsDir.isDirectory) {
            val audioFiles = downloadsDir.listFiles { file ->
                file.isFile && (file.extension.equals("mp3", ignoreCase = true) ||
                                file.extension.equals("m4a", ignoreCase = true) ||
                                file.extension.equals("flac", ignoreCase = true) ||
                                file.extension.equals("wav", ignoreCase = true))
            } ?: emptyArray()

            for (file in audioFiles) {
                try {
                    val trackId = file.nameWithoutExtension.replace(';', ':')
                    val existingTrack = database.trackDao().getRawTrackById(trackId)

                    var rawTitle: String? = null
                    var rawArtist: String? = null
                    var rawAlbum: String? = null
                    var durationMs: Long? = existingTrack?.durationMs
                    var coverUri: String? = existingTrack?.coverUri

                    if (existingTrack == null || existingTrack.title.isBlank() || existingTrack.displayArtistName.isNullOrBlank()) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            durationMs = durationStr?.toLongOrNull() ?: durationMs

                            if (coverUri == null) {
                                val embeddedPicture = retriever.embeddedPicture
                                if (embeddedPicture != null) {
                                    val coversDir = File(context.filesDir, "covers")
                                    if (!coversDir.exists()) coversDir.mkdirs()
                                    val coverFile = File(coversDir, "${trackId.replace(':', ';')}.jpg")
                                    FileOutputStream(coverFile).use { fos ->
                                        fos.write(embeddedPicture)
                                    }
                                    coverUri = Uri.fromFile(coverFile).toString()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("LocalMediaScanner", "Could not extract metadata via retriever for ${file.name}: ${e.message}")
                        } finally {
                            try {
                                retriever.release()
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }

                    val title = if (existingTrack != null && existingTrack.title.isNotBlank()) {
                        existingTrack.title
                    } else {
                        rawTitle?.ifBlank { null } ?: file.nameWithoutExtension
                    }

                    val artistName = if (existingTrack != null && !existingTrack.displayArtistName.isNullOrBlank()) {
                        existingTrack.displayArtistName ?: "Unknown artist"
                    } else {
                        rawArtist?.ifBlank { null } ?: "Unknown artist"
                    }

                    val albumTitle = if (existingTrack != null && !existingTrack.displayAlbumTitle.isNullOrBlank()) {
                        existingTrack.displayAlbumTitle
                    } else {
                        rawAlbum?.ifBlank { null }
                    }

                    val artistId = existingTrack?.primaryArtistId ?: artistIdOf(artistName)
                    val albumId = existingTrack?.albumId ?: albumTitle?.let { albumIdOf(artistName, it) }
                    val fileUri = Uri.fromFile(file).toString()

                    if (!scannedArtists.containsKey(artistId)) {
                        scannedArtists[artistId] = ArtistEntity(
                            id = artistId,
                            name = artistName,
                            normalizedName = normalize(artistName),
                            pictureUri = null,
                            summary = null,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }

                    if (albumId != null && albumTitle != null) {
                        if (!scannedAlbums.containsKey(albumId)) {
                            scannedAlbums[albumId] = AlbumEntity(
                                id = albumId,
                                primaryArtistId = artistId,
                                title = albumTitle,
                                normalizedTitle = normalize(albumTitle),
                                coverUri = coverUri,
                                releaseDate = null,
                                trackCount = null,
                                createdAt = now,
                                updatedAt = now,
                            )
                        }
                    }

                    scannedTracks.add(
                        TrackEntity(
                            id = trackId,
                            primaryArtistId = artistId,
                            albumId = albumId,
                            title = title,
                            normalizedTitle = normalize(title),
                            displayArtistName = artistName,
                            displayAlbumTitle = albumTitle,
                            durationMs = durationMs,
                            coverUri = coverUri,
                            canonicalAudioSourceType = "downloaded",
                            isLiked = existingTrack?.isLiked ?: false,
                            isDownloadedByAura = true,
                            isExplicit = null,
                            popularity = null,
                            genresJson = null,
                            createdAt = file.lastModified(),
                            updatedAt = file.lastModified(),
                        )
                    )

                    val mimeType = when (file.extension.lowercase()) {
                        "m4a" -> "audio/mp4"
                        "wav" -> "audio/wav"
                        else -> "audio/mpeg"
                    }

                    scannedMediaLinks.add(
                        TrackMediaLinkEntity(
                            id = "media-link:${file.nameWithoutExtension.hashCode()}",
                            trackId = trackId,
                            mediaStoreId = file.nameWithoutExtension.hashCode().toLong(),
                            contentUri = fileUri,
                            fileSizeBytes = file.length(),
                            mimeType = mimeType,
                            dateModifiedEpochMs = file.lastModified(),
                            availabilityStatus = "present",
                            lastScannedAt = now,
                        )
                    )
                } catch (e: Exception) {
                    Log.e("LocalMediaScanner", "Error retrieving metadata for private file ${file.name}", e)
                }
            }
        }

        // 2. Scan MediaStore if permission is granted
        val mediaScanResult = mediaStoreAudioDataSource.getLocalAudioFilesResult()
        if (mediaScanResult.isComplete) {
            val mediaFiles = mediaScanResult.tracks
            for (media in mediaFiles) {
                val trackId = trackIdOf(media.mediaStoreId)
                val existingTrack = database.trackDao().getRawTrackById(trackId)

                val title = if (existingTrack != null && existingTrack.title.isNotBlank()) {
                    existingTrack.title
                } else {
                    media.title
                }

                val artistName = if (existingTrack != null && !existingTrack.displayArtistName.isNullOrBlank()) {
                    existingTrack.displayArtistName ?: media.artistName
                } else {
                    media.artistName
                }

                val albumTitle = if (existingTrack != null && !existingTrack.displayAlbumTitle.isNullOrBlank()) {
                    existingTrack.displayAlbumTitle
                } else {
                    media.albumTitle
                }

                val artistId = existingTrack?.primaryArtistId ?: artistIdOf(artistName)
                val albumId = existingTrack?.albumId ?: albumTitle?.let { albumIdOf(artistName, it) }
                val coverUri = existingTrack?.coverUri ?: media.coverUri

                if (!scannedArtists.containsKey(artistId)) {
                    scannedArtists[artistId] = ArtistEntity(
                        id = artistId,
                        name = artistName,
                        normalizedName = normalize(artistName),
                        pictureUri = null,
                        summary = null,
                        createdAt = now,
                        updatedAt = now,
                    )
                }

                if (albumId != null && albumTitle != null) {
                    if (!scannedAlbums.containsKey(albumId)) {
                        scannedAlbums[albumId] = AlbumEntity(
                            id = albumId,
                            primaryArtistId = artistId,
                            title = albumTitle,
                            normalizedTitle = normalize(albumTitle),
                            coverUri = coverUri,
                            releaseDate = null,
                            trackCount = null,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                }

                scannedTracks.add(
                    TrackEntity(
                        id = trackId,
                        primaryArtistId = artistId,
                        albumId = albumId,
                        title = title,
                        normalizedTitle = normalize(title),
                        displayArtistName = artistName,
                        displayAlbumTitle = albumTitle,
                        durationMs = existingTrack?.durationMs ?: media.durationMs,
                        coverUri = coverUri,
                        canonicalAudioSourceType = "local",
                        isLiked = existingTrack?.isLiked ?: false,
                        isDownloadedByAura = false,
                        isExplicit = null,
                        popularity = null,
                        genresJson = null,
                        createdAt = existingTrack?.createdAt ?: (media.dateModifiedEpochMs ?: now),
                        updatedAt = existingTrack?.updatedAt ?: (media.dateModifiedEpochMs ?: now),
                    )
                )

                scannedMediaLinks.add(
                    TrackMediaLinkEntity(
                        id = "media-link:${media.mediaStoreId}",
                        trackId = trackId,
                        mediaStoreId = media.mediaStoreId,
                        contentUri = media.contentUri,
                        fileSizeBytes = media.fileSizeBytes,
                        mimeType = media.mimeType,
                        dateModifiedEpochMs = media.dateModifiedEpochMs,
                        availabilityStatus = "present",
                        lastScannedAt = now,
                    )
                )
            }
        }

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val scannedIds = scannedTracks.map { it.id }.toSet()
                val obsoleteIds = mutableListOf<String>()

                // Purge obsolete MediaStore tracks ONLY if the MediaStore scan completed successfully
                if (mediaScanResult.isComplete) {
                    val existingLocalIds = database.trackDao().getLocalTrackIds()
                    for (id in existingLocalIds) {
                        if (id !in scannedIds) {
                            obsoleteIds.add(id)
                        }
                    }
                } else {
                    Log.w("LocalMediaScanner", "MediaStore scan incomplete or permission missing. Skipped purging local MediaStore tracks to prevent data loss.")
                }

                // Purge obsolete downloaded tracks based on physical file scan
                val existingDownloadedIds = database.trackDao().getDownloadedTrackIds()
                for (id in existingDownloadedIds) {
                    if (id !in scannedIds) {
                        obsoleteIds.add(id)
                    }
                }

                if (obsoleteIds.isNotEmpty()) {
                    database.trackDao().deleteTracksByIds(obsoleteIds)
                }

                if (scannedTracks.isNotEmpty()) {
                    database.artistDao().upsertArtists(scannedArtists.values.toList())
                    database.albumDao().upsertAlbums(scannedAlbums.values.toList())
                    database.trackDao().upsertTracks(scannedTracks)
                    database.trackDao().upsertTrackMediaLinks(scannedMediaLinks)
                }
            }
        }

        onInvalidateSearchIndex()
        scannedTracks.size
    }

    private fun trackIdOf(mediaStoreId: Long): String = "track:local:$mediaStoreId"

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
