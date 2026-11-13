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
    private val inFlightCloudTrackIdsProvider: (() -> Set<String>)? = null,
    private val mediaCoordinator: TrackMediaCoordinator = TrackMediaCoordinator.shared,
    private val readMediaStore: suspend () -> MediaScanResult = { mediaStoreAudioDataSource.getLocalAudioFilesResult() },
) {
    suspend fun syncLocalMedia(): Int = withContext(Dispatchers.IO) {
        val observedLinks = database.trackDao().getAllMediaLinks().associateBy { it.trackId }
        val now = System.currentTimeMillis()
        val scannedArtists = mutableMapOf<String, ArtistEntity>()
        val scannedAlbums = mutableMapOf<String, AlbumEntity>()
        val scannedTracks = mutableListOf<TrackEntity>()
        val scannedMediaLinks = mutableListOf<ScannedLinkMeta>()
        val resolvedArtistIds = mutableMapOf<String, String>()
        val resolvedAlbumIds = mutableMapOf<String, String>()

        val likedTrackIds = database.trackLikeDao().getAllLikedTrackIds().toSet()
        val playlistTrackIds = database.playlistDao().getAllPlaylistTrackIds().toSet()
        val preservedTrackIds = likedTrackIds + playlistTrackIds

        // 1. Scan private downloads/ directory in a single pass
        val downloadsDir = File(context.filesDir, "downloads")
        val validAudioExtensions = setOf("mp3", "m4a", "flac", "wav", "opus", "ogg")
        val downloadedFilesByTrackId = mutableMapOf<String, File>()

        if (downloadsDir.exists() && downloadsDir.isDirectory) {
            val audioFiles = downloadsDir.listFiles { file ->
                file.isFile &&
                file.length() > 0L &&
                !file.name.endsWith(".part", ignoreCase = true) &&
                !file.name.endsWith(".ytdl", ignoreCase = true) &&
                !file.name.endsWith(".tmp", ignoreCase = true) &&
                file.extension.lowercase() in validAudioExtensions &&
                AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false)
            } ?: emptyArray()

            for (file in audioFiles) {
                val trackId = file.nameWithoutExtension.replace(';', ':')
                downloadedFilesByTrackId[trackId] = file
                try {
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

                    val artistId = existingTrack?.primaryArtistId ?: resolveArtistId(artistName, resolvedArtistIds)
                    val albumId = existingTrack?.albumId ?: albumTitle?.let { resolveAlbumId(artistName, it, artistId, resolvedAlbumIds) }
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
                            isLiked = existingTrack?.isLiked == true || likedTrackIds.contains(trackId),
                            isDownloadedByAura = true,
                            isExplicit = existingTrack?.isExplicit,
                            popularity = existingTrack?.popularity,
                            genresJson = existingTrack?.genresJson,
                            createdAt = existingTrack?.createdAt ?: file.lastModified(),
                            updatedAt = existingTrack?.updatedAt ?: file.lastModified(),
                        )
                    )

                    val mimeType = when (file.extension.lowercase()) {
                        "m4a" -> "audio/mp4"
                        "wav" -> "audio/wav"
                        else -> "audio/mpeg"
                    }

                    scannedMediaLinks.add(
                        ScannedLinkMeta(
                            trackId = trackId,
                            contentUri = fileUri,
                            fileSizeBytes = file.length(),
                            mimeType = mimeType
                        )
                    )
                } catch (e: Exception) {
                    Log.e("LocalMediaScanner", "Error retrieving metadata for private file ${file.name}", e)
                }
            }
        }

        // 2. Scan MediaStore if permission is granted
        val mediaScanResult = readMediaStore()
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

                val artistId = existingTrack?.primaryArtistId ?: resolveArtistId(artistName, resolvedArtistIds)
                val albumId = existingTrack?.albumId ?: albumTitle?.let { resolveAlbumId(artistName, it, artistId, resolvedAlbumIds) }
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
                        isLiked = existingTrack?.isLiked == true || likedTrackIds.contains(trackId),
                        isDownloadedByAura = false,
                        isExplicit = existingTrack?.isExplicit,
                        popularity = existingTrack?.popularity,
                        genresJson = existingTrack?.genresJson,
                        createdAt = existingTrack?.createdAt ?: (media.dateModifiedEpochMs ?: now),
                        updatedAt = existingTrack?.updatedAt ?: (media.dateModifiedEpochMs ?: now),
                    )
                )

                scannedMediaLinks.add(
                    ScannedLinkMeta(
                        trackId = trackId,
                        contentUri = media.contentUri,
                        fileSizeBytes = media.fileSizeBytes,
                        mimeType = media.mimeType
                    )
                )
            }
        }

        val linksByTrackId = scannedMediaLinks.associateBy { it.trackId }
        val insertedArtistIds = HashSet<String>()
        val insertedAlbumIds = HashSet<String>()
        var anyMutated = false
        // Metadata was collected without a writer lock. Re-read state under the per-track guard.
        for (scanned in scannedTracks) {
            mediaCoordinator.withTrack(scanned.id) {
                val privateFile = downloadedFilesByTrackId[scanned.id]
                if (privateFile != null && !AudioIntegrityValidator.isValidAudioFile(privateFile, checkMetadata = false)) return@withTrack
                val oldLink = database.trackDao().getMediaLinkByTrackId(scanned.id)
                if (privateFile == null && oldLink != observedLinks[scanned.id]) return@withTrack

                val current = database.trackDao().getRawTrackById(scanned.id)
                val targetTrack = if (current != null && privateFile != null) {
                    current.copy(
                        canonicalAudioSourceType = "downloaded",
                        isDownloadedByAura = true
                    )
                } else if (current != null) {
                    val liveIsLiked = current.isLiked ||
                        (likedTrackIds.contains(scanned.id) && database.trackLikeDao().isTrackLiked(scanned.id))
                    scanned.copy(
                        isLiked = liveIsLiked,
                        isExplicit = scanned.isExplicit ?: current.isExplicit,
                        popularity = scanned.popularity ?: current.popularity,
                        genresJson = scanned.genresJson ?: current.genresJson,
                        createdAt = current.createdAt,
                        updatedAt = current.updatedAt
                    )
                } else {
                    scanned
                }

                val link = linksByTrackId[scanned.id]
                val expectedFileSize = privateFile?.length() ?: link?.fileSizeBytes
                val trackChanged = current == null || targetTrack != current
                val linkChanged = link != null && !hasSameMediaLinkData(
                    existing = oldLink,
                    trackId = scanned.id,
                    contentUri = link.contentUri,
                    fileSizeBytes = expectedFileSize,
                    mimeType = link.mimeType
                )
                if (!trackChanged && !linkChanged) {
                    return@withTrack
                }

                anyMutated = true
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    database.useWriterConnection { writer -> writer.immediateTransaction {
                        if (trackChanged) {
                            targetTrack.primaryArtistId?.let { artistId ->
                                if (artistId !in insertedArtistIds) {
                                    scannedArtists[artistId]?.let {
                                        database.artistDao().insertArtistsIgnore(listOf(it))
                                        insertedArtistIds.add(artistId)
                                    }
                                }
                            }
                            targetTrack.albumId?.let { albumId ->
                                if (albumId !in insertedAlbumIds) {
                                    scannedAlbums[albumId]?.let {
                                        database.albumDao().insertAlbumsIgnore(listOf(it))
                                        insertedAlbumIds.add(albumId)
                                    }
                                }
                            }
                            val trackToWrite = if (current != null) {
                                targetTrack.copy(updatedAt = System.currentTimeMillis())
                            } else {
                                targetTrack
                            }
                            database.trackDao().upsertTrack(trackToWrite)
                        }
                        if (linkChanged) {
                            database.trackDao().upsertTrackMediaLinks(listOf(MediaLinkResolver.resolveOrCreateMediaLink(
                                database, scanned.id, link!!.contentUri, expectedFileSize,
                                link.mimeType, System.currentTimeMillis())))
                        }
                    } }
                }
            }
        }
        // Only initial observations may be purged. The coordinator rechecks disk and generation.
        val scannedIds = scannedTracks.map { it.id }.toSet()
        val activeTrackIds = database.downloadJobDao().getActiveJobs().map { it.trackId }.toSet()
        if (mediaScanResult.isComplete) {
            for ((id, observed) in observedLinks) {
                if (id in scannedIds) continue
                if (id in (inFlightCloudTrackIdsProvider?.invoke() ?: emptySet())) continue
                if (id in activeTrackIds) continue
                if (mediaCoordinator.purgeObserved(database, downloadsDir, observed,
                        mediaStoreMissing = UriPathParser.isContentUri(observed.contentUri))) {
                    anyMutated = true
                }
            }
        }

        if (anyMutated) {
            onInvalidateSearchIndex()
        }
        scannedTracks.size
    }

    private data class ScannedLinkMeta(
        val trackId: String,
        val contentUri: String,
        val fileSizeBytes: Long?,
        val mimeType: String?
    )

    private fun hasSameMediaLinkData(
        existing: TrackMediaLinkEntity?,
        trackId: String,
        contentUri: String,
        fileSizeBytes: Long?,
        mimeType: String?
    ): Boolean {
        if (existing == null) return false
        return existing.trackId == trackId &&
            existing.contentUri == contentUri &&
            existing.fileSizeBytes == fileSizeBytes &&
            existing.mimeType == mimeType &&
            existing.availabilityStatus == "present"
    }

    private suspend fun resolveArtistId(artistName: String, sessionCache: MutableMap<String, String>): String {
        val norm = normalize(artistName)
        sessionCache[norm]?.let { return it }
        val existing = database.artistDao().getArtistByNormalizedName(norm)
        val resolved = existing?.id ?: artistIdOf(artistName)
        sessionCache[norm] = resolved
        return resolved
    }

    private suspend fun resolveAlbumId(
        artistName: String,
        albumTitle: String,
        artistId: String,
        sessionCache: MutableMap<String, String>
    ): String {
        val normTitle = normalize(albumTitle)
        val cacheKey = "$artistId:$normTitle"
        sessionCache[cacheKey]?.let { return it }
        val existing = database.albumDao().getAlbumByNormalizedTitle(normTitle, artistId)
        val resolved = existing?.id ?: albumIdOf(artistName, albumTitle)
        sessionCache[cacheKey] = resolved
        return resolved
    }

    private fun trackIdOf(mediaStoreId: Long): String = "track:local:$mediaStoreId"

    private fun artistIdOf(artistName: String): String =
        "artist:${normalize(artistName).ifBlank { "unknown-artist" }}"

    private fun albumIdOf(artistName: String, albumTitle: String): String =
        "album:${normalize(artistName).ifBlank { "unknown-artist" }}:${normalize(albumTitle).ifBlank { "unknown-album" }}"

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
