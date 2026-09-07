package com.aura.music.desktop.domain

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.data.local.*
import com.aura.music.data.network.*
import com.aura.music.domain.search.SearchNormalizer
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestionnaire de synchronisation Cloud pour le client Desktop.
 * Dépile la table sync_outbox en FIFO strict et synchronise les métadonnées distantes (files, likes, playlists).
 */
class DesktopCloudSyncManager(
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val scope: CoroutineScope
) {
    var apiToken: String? = null
    var autoSyncEnabled: Boolean = true

    private val isSyncing = AtomicBoolean(false)
    private var syncJob: Job? = null

    private val _isSyncingState = MutableStateFlow(false)
    val isSyncingState: StateFlow<Boolean> = _isSyncingState.asStateFlow()

    private val _hasInitialSyncCompleted = MutableStateFlow(false)
    val hasInitialSyncCompleted: StateFlow<Boolean> = _hasInitialSyncCompleted.asStateFlow()

    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    fun startLoop(intervalMs: Long = 60000L) {
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val token = apiToken
                if (!token.isNullOrBlank() && autoSyncEnabled) {
                    try {
                        performCloudSync(token)
                    } catch (e: Exception) {
                        System.err.println("Error in cloud sync loop: ${e.message}")
                    }
                }
                delay(intervalMs)
            }
        }
    }

    fun stopLoop() {
        syncJob?.cancel()
        syncJob = null
    }

    fun triggerFlush() {
        val token = apiToken ?: return
        scope.launch(Dispatchers.IO) {
            flushSyncOutbox(token)
        }
    }

    suspend fun performCloudSync(token: String, onFinished: (() -> Unit)? = null) = withContext(Dispatchers.IO) {
        if (!isSyncing.compareAndSet(false, true)) return@withContext
        _isSyncingState.value = true
        _lastSyncError.value = null
        try {
            System.out.println("Starting background Cloud Metadata Sync...")
            flushSyncOutbox(token)
            syncCloudMetadata(token)
            DesktopTrackHydrator.hydrateTrackStubs(database)
            withContext(Dispatchers.Main) {
                onFinished?.invoke()
            }
            System.out.println("Background Cloud Metadata Sync finished.")
        } catch (e: Exception) {
            _lastSyncError.value = e.message
            System.err.println("Cloud sync failed: ${e.message}")
        } finally {
            _hasInitialSyncCompleted.value = true
            isSyncing.set(false)
            _isSyncingState.value = false
        }
    }

    suspend fun flushSyncOutbox(token: String) = withContext(Dispatchers.IO) {
        val pendingOps = database.syncOutboxDao().getPendingOperations()
        if (pendingOps.isEmpty()) return@withContext

        System.out.println("Flushing ${pendingOps.size} pending outbox operations...")
        for (op in pendingOps) {
            try {
                var success = false
                when (op.entityType) {
                    "track_like" -> {
                        if (op.operationType == "set" || op.operationType == "create") {
                            val resp = apiService.likeTrack(token, op.entityId, null, null)
                            if (resp.error == null) success = true
                        } else if (op.operationType == "delete") {
                            val resp = apiService.unlikeTrack(token, op.entityId)
                            if (resp.error == null) success = true
                        }
                    }
                    "playlist" -> {
                        if (op.operationType == "create") {
                            val resp = apiService.createPlaylist(
                                token = token,
                                request = PlaylistCreate(
                                    id = op.entityId,
                                    name = op.payloadJson.ifBlank { "Nouvelle Playlist" }
                                )
                            )
                            if (resp.error == null) success = true
                        } else if (op.operationType == "delete") {
                            val resp = apiService.deletePlaylist(token, op.entityId)
                            if (resp.error == null) success = true
                        }
                    }
                    "playlist_item" -> {
                        if (op.operationType == "create" || op.operationType == "append") {
                            val playlistId = op.entityId
                            val trackId = op.payloadJson
                            val resp = apiService.appendTrackToPlaylist(
                                token = token,
                                id = playlistId,
                                request = PlaylistItemCreate(
                                    id = op.id,
                                    trackId = trackId,
                                    position = 0
                                )
                            )
                            if (resp.error == null) success = true
                        } else if (op.operationType == "delete") {
                            val playlistId = op.entityId
                            val trackId = op.payloadJson
                            val resp = apiService.removeTrackFromPlaylist(
                                token = token,
                                id = playlistId,
                                trackId = trackId
                            )
                            if (resp.error == null) success = true
                        }
                    }
                }

                if (success) {
                    database.syncOutboxDao().deleteOperation(op.id)
                } else {
                    database.syncOutboxDao().updateStatus(op.id, "failed", System.currentTimeMillis())
                }
            } catch (e: Exception) {
                System.err.println("Failed to process outbox op ${op.id}: ${e.message}")
                database.syncOutboxDao().updateStatus(op.id, "failed", System.currentTimeMillis())
                break // Stop on network error to preserve strict FIFO order
            }
        }
    }

    suspend fun syncCloudMetadata(token: String) = withContext(Dispatchers.IO) {
        val filesResp = apiService.listSyncFiles(token)
        val cloudFiles = filesResp.data?.items ?: emptyList()
        val now = System.currentTimeMillis()

        val appDir = File(System.getProperty("user.home"), ".aura")
        val downloadsDir = File(appDir, "downloads")
        if (!downloadsDir.exists()) downloadsDir.mkdirs()

        val artistsToInsert = mutableListOf<ArtistEntity>()
        val albumsToInsert = mutableListOf<AlbumEntity>()
        val tracksToInsert = mutableListOf<TrackEntity>()
        val mediaLinksToInsert = mutableListOf<TrackMediaLinkEntity>()

        val existingTracks = database.trackDao().getAllTracks().associateBy { it.id }
        val existingLikedIds = database.trackDao().getLikedTracks().map { it.id }.toSet()

        for (cloudFile in cloudFiles) {
            val artistName = cloudFile.artistName ?: "Artiste inconnu"
            val artistId = cloudFile.artistId ?: "artist:${artistName.lowercase().trim().replace(" ", "_")}"
            val title = cloudFile.title ?: "Titre inconnu"
            val albumTitle = cloudFile.albumTitle
            val albumId = cloudFile.albumId ?: if (albumTitle != null) "album:${artistName.lowercase().trim().replace(" ", "_")}:${albumTitle.lowercase().trim().replace(" ", "_")}" else null

            val existing = existingTracks[cloudFile.trackId]
            val isLiked = existing?.isLiked ?: existingLikedIds.contains(cloudFile.trackId)
            val durationMs = if (cloudFile.durationMs != null && cloudFile.durationMs > 0L) cloudFile.durationMs else existing?.durationMs
            val coverUri = if (!cloudFile.coverUri.isNullOrBlank()) cloudFile.coverUri else existing?.coverUri
            val resolvedTitle = if (title != "Titre inconnu" && !title.startsWith("Piste ")) title else (existing?.title ?: title)
            val resolvedArtist = if (artistName != "Artiste inconnu") artistName else (existing?.artistName ?: artistName)
            val resolvedAlbum = albumTitle ?: existing?.albumTitle

            artistsToInsert.add(
                ArtistEntity(
                    id = artistId,
                    name = artistName,
                    normalizedName = SearchNormalizer.normalize(artistName),
                    pictureUri = null,
                    createdAt = now,
                    updatedAt = now
                )
            )

            if (albumId != null && resolvedAlbum != null) {
                albumsToInsert.add(
                    AlbumEntity(
                        id = albumId,
                        primaryArtistId = artistId,
                        title = resolvedAlbum,
                        normalizedTitle = SearchNormalizer.normalize(resolvedAlbum),
                        coverUri = coverUri,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }

            val targetFile = File(downloadsDir, "${cloudFile.trackId.replace(':', ';')}.mp3")
            val isDownloaded = targetFile.exists() && targetFile.length() > 0L
            val fileUri = if (isDownloaded) targetFile.toURI().toString() else null

            val newTrack = TrackEntity(
                id = cloudFile.trackId,
                primaryArtistId = artistId,
                albumId = albumId,
                title = resolvedTitle,
                normalizedTitle = SearchNormalizer.normalize(resolvedTitle),
                displayArtistName = resolvedArtist,
                displayAlbumTitle = resolvedAlbum,
                durationMs = durationMs,
                coverUri = coverUri,
                canonicalAudioSourceType = if (isDownloaded) "downloaded" else "cloud",
                isLiked = isLiked,
                isDownloadedByAura = isDownloaded,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )

            // Insertion uniquement si le morceau est nouveau ou a réellement changé
            if (existing == null ||
                existing.title != newTrack.title ||
                existing.artistName != newTrack.displayArtistName ||
                existing.albumTitle != newTrack.displayAlbumTitle ||
                (existing.durationMs ?: 0L) != (newTrack.durationMs ?: 0L) ||
                existing.coverUri != newTrack.coverUri ||
                existing.isLiked != newTrack.isLiked ||
                (isDownloaded && !existing.isDownloaded)
            ) {
                tracksToInsert.add(newTrack)
            }

            if (fileUri != null) {
                mediaLinksToInsert.add(
                    TrackMediaLinkEntity(
                        id = "media-link:${cloudFile.trackId}",
                        trackId = cloudFile.trackId,
                        mediaStoreId = System.currentTimeMillis(),
                        contentUri = fileUri,
                        fileSizeBytes = targetFile.length(),
                        mimeType = "audio/mpeg",
                        dateModifiedEpochMs = targetFile.lastModified(),
                        availabilityStatus = "present",
                        lastScannedAt = now
                    )
                )
            }
        }

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (artistsToInsert.isNotEmpty()) {
                    database.artistDao().insertArtistsIgnore(artistsToInsert)
                }
                if (albumsToInsert.isNotEmpty()) {
                    database.albumDao().insertAlbumsIgnore(albumsToInsert)
                }
                if (tracksToInsert.isNotEmpty()) {
                    database.trackDao().upsertTracks(tracksToInsert)
                }
                if (mediaLinksToInsert.isNotEmpty()) {
                    database.trackDao().upsertTrackMediaLinks(mediaLinksToInsert)
                }
            }
        }

        // 2. Synchronisation des Likes (avec réconciliation atomique)
        try {
            val likesResp = apiService.getLikes(token)
            val likes = likesResp.data ?: emptyList()
            val cloudTrackIds = likes.map { it.trackId }.toSet()

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    // Suppression locale des likes qui ne sont plus présents sur le Cloud
                    val localLikes = database.trackDao().getLikedTracks()
                    for (local in localLikes) {
                        if (local.id !in cloudTrackIds) {
                            database.trackLikeDao().deleteLike(local.id)
                            database.trackLikeDao().setTrackIsLiked(local.id, false, now)
                        }
                    }

                    for (like in likes) {
                        val existingTrack = database.trackDao().getRawTrackById(like.trackId)
                        if (existingTrack == null) {
                            database.trackDao().upsertTrack(
                                TrackEntity(
                                    id = like.trackId,
                                    primaryArtistId = null,
                                    albumId = null,
                                    title = "Piste ${like.trackId.takeLast(6)}",
                                    normalizedTitle = SearchNormalizer.normalize("Piste ${like.trackId.takeLast(6)}"),
                                    displayArtistName = "Artiste inconnu",
                                    displayAlbumTitle = null,
                                    durationMs = 0L,
                                    coverUri = null,
                                    canonicalAudioSourceType = "cloud",
                                    isLiked = true,
                                    isDownloadedByAura = false,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        } else if (!existingTrack.isLiked) {
                            database.trackLikeDao().setTrackIsLiked(like.trackId, true, now)
                        }
                        val likedAtEpoch = try { java.time.Instant.parse(like.likedAt).toEpochMilli() } catch (e: Exception) { now }
                        database.trackLikeDao().insertLike(
                            TrackLikeEntity(
                                trackId = like.trackId,
                                likedAt = likedAtEpoch,
                                sourceContextType = like.sourceContextType,
                                sourceContextId = like.sourceContextId
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to sync remote likes: ${e.message}")
        }

        // 3. Synchronisation des Playlists (avec réconciliation atomique)
        try {
            val playlistsResp = apiService.getPlaylists(token)
            val playlists = playlistsResp.data ?: emptyList()
            val cloudPlaylistIds = playlists.map { it.id }.toSet()

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    // Suppression locale des playlists supprimées du Cloud
                    val localPlaylists = database.playlistDao().getPlaylists()
                    for (localPl in localPlaylists) {
                        if (localPl.id !in cloudPlaylistIds) {
                            database.playlistDao().deletePlaylist(localPl.id)
                        }
                    }

                    for (pl in playlists) {
                        val plCreatedAt = try { java.time.Instant.parse(pl.createdAt).toEpochMilli() } catch (e: Exception) { now }
                        val plUpdatedAt = try { java.time.Instant.parse(pl.updatedAt).toEpochMilli() } catch (e: Exception) { now }
                        database.playlistDao().upsertPlaylist(
                            PlaylistEntity(
                                id = pl.id,
                                name = pl.name,
                                coverUri = pl.coverUri,
                                isPinned = pl.isPinned,
                                createdAt = plCreatedAt,
                                updatedAt = plUpdatedAt
                            )
                        )
                        for (item in pl.items) {
                            val existingTrack = database.trackDao().getRawTrackById(item.trackId)
                            if (existingTrack == null) {
                                database.trackDao().upsertTrack(
                                    TrackEntity(
                                        id = item.trackId,
                                        primaryArtistId = null,
                                        albumId = null,
                                        title = "Piste ${item.trackId.takeLast(6)}",
                                        normalizedTitle = SearchNormalizer.normalize("Piste ${item.trackId.takeLast(6)}"),
                                        displayArtistName = "Artiste inconnu",
                                        displayAlbumTitle = null,
                                        durationMs = 0L,
                                        coverUri = null,
                                        canonicalAudioSourceType = "cloud",
                                        isLiked = false,
                                        isDownloadedByAura = false,
                                        createdAt = now,
                                        updatedAt = now
                                    )
                                )
                            }
                            val itemAddedAt = try { java.time.Instant.parse(item.addedAt).toEpochMilli() } catch (e: Exception) { now }
                            database.playlistDao().upsertPlaylistItem(
                                PlaylistItemEntity(
                                    id = item.id,
                                    playlistId = pl.id,
                                    trackId = item.trackId,
                                    position = item.position,
                                    addedAt = itemAddedAt
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to sync remote playlists: ${e.message}")
        }
    }

    suspend fun cacheCloudStream(
        token: String,
        trackId: String,
        title: String,
        artistName: String,
        albumTitle: String?,
        durationMs: Long,
        coverUri: String?
    ): File? = withContext(Dispatchers.IO) {
        try {
            val appDir = File(System.getProperty("user.home"), ".aura")
            val streamCacheDir = File(appDir, "cache/stream")
            if (!streamCacheDir.exists()) {
                streamCacheDir.mkdirs()
            }

            val cleanId = trackId.replace(':', ';')
            var targetFile = streamCacheDir.listFiles()?.firstOrNull { it.name.startsWith("$cleanId.") && it.length() > 0L }

            if (targetFile == null || targetFile.length() == 0L) {
                System.out.println("Streaming cache miss: caching stream audio for $trackId...")
                val response = apiService.downloadSyncFile(token, trackId)
                if (response.status.value !in 200..299) {
                    System.err.println("Failed to stream audio for $trackId: HTTP ${response.status.value}")
                    return@withContext null
                }

                val tempFile = File(streamCacheDir, "$cleanId.tmp")
                if (tempFile.exists()) tempFile.delete()

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
                val finalFile = File(streamCacheDir, "$cleanId.$ext")
                if (finalFile.exists()) finalFile.delete()
                tempFile.renameTo(finalFile)
                targetFile = finalFile
            } else {
                // Auto-réparation si l'extension existante ne correspond pas aux magic bytes (ex: .mp3 pour un M4A)
                val detectedExt = com.aura.music.desktop.media.DesktopMediaMetadataReader.detectAudioExtension(targetFile, null)
                if (!targetFile.name.endsWith(".$detectedExt", ignoreCase = true)) {
                    val correctedFile = File(streamCacheDir, "$cleanId.$detectedExt")
                    if (correctedFile.exists()) correctedFile.delete()
                    if (targetFile.renameTo(correctedFile)) {
                        targetFile = correctedFile
                    }
                }
            }

            // Met à jour la durée dans SQLite sans modifier canonicalAudioSourceType (reste "cloud")
            if (targetFile.exists() && targetFile.length() > 0L) {
                targetFile.setLastModified(System.currentTimeMillis())
                pruneStreamCacheIfNeeded()

                val meta = com.aura.music.desktop.media.DesktopMediaMetadataReader.readMetadata(targetFile)
                val resolvedDuration = if (durationMs <= 0L && meta.durationMs > 0L) meta.durationMs else durationMs
                val existingTrack = database.trackDao().getRawTrackById(trackId)
                if (existingTrack != null && (existingTrack.durationMs == null || existingTrack.durationMs == 0L) && resolvedDuration > 0L) {
                    val updated = existingTrack.copy(
                        durationMs = resolvedDuration,
                        coverUri = coverUri ?: existingTrack.coverUri ?: meta.localCoverUri,
                        updatedAt = System.currentTimeMillis()
                    )
                    database.trackDao().upsertTrack(updated)
                }
            }

            targetFile
        } catch (e: Exception) {
            System.err.println("Failed to cache cloud stream for $trackId: ${e.message}")
            null
        }
    }

    suspend fun downloadCloudTrack(
        token: String,
        trackId: String,
        title: String,
        artistName: String,
        albumTitle: String?,
        durationMs: Long,
        coverUri: String?
    ) = withContext(Dispatchers.IO) {
        try {
            System.out.println("Downloading cloud file for $trackId...")
            val response = apiService.downloadSyncFile(token, trackId)
            if (response.status.value !in 200..299) {
                System.err.println("Failed to download cloud file $trackId: HTTP ${response.status.value}")
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

            val meta = com.aura.music.desktop.media.DesktopMediaMetadataReader.readMetadata(targetFile)
            val resolvedTitle = if (title.startsWith("Piste ", ignoreCase = true) || title.equals("Titre inconnu", ignoreCase = true) || title.isBlank()) meta.title else title
            val resolvedArtist = if (artistName.equals("Artiste inconnu", ignoreCase = true) || artistName.isBlank()) meta.artist else artistName
            val resolvedAlbum = if (albumTitle.isNullOrBlank() || albumTitle.equals("Album inconnu", ignoreCase = true)) meta.album else albumTitle
            val resolvedDuration = if (durationMs <= 0L && meta.durationMs > 0L) meta.durationMs else durationMs
            val resolvedCover = coverUri ?: meta.localCoverUri

            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    // 1. Artiste
                    var primaryArtistId: String? = null
                    if (resolvedArtist.isNotBlank()) {
                        primaryArtistId = "artist:${resolvedArtist.lowercase().trim().replace(" ", "_")}"
                        database.artistDao().insertArtistsIgnore(
                            listOf(
                                ArtistEntity(
                                    id = primaryArtistId,
                                    name = resolvedArtist,
                                    normalizedName = resolvedArtist.lowercase(),
                                    pictureUri = resolvedCover,
                                    summary = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        )
                    }

                    // 2. Album
                    var albumId: String? = null
                    if (!resolvedAlbum.isNullOrBlank()) {
                        albumId = "album:${resolvedAlbum.lowercase().trim().replace(" ", "_")}"
                        database.albumDao().insertAlbumsIgnore(
                            listOf(
                                AlbumEntity(
                                    id = albumId,
                                    primaryArtistId = primaryArtistId,
                                    title = resolvedAlbum,
                                    normalizedTitle = resolvedAlbum.lowercase(),
                                    coverUri = resolvedCover,
                                    releaseDate = null,
                                    trackCount = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        )
                    }

                    // 3. Track
                    val existingTrack = database.trackDao().getRawTrackById(trackId)
                    val trackEntity = TrackEntity(
                        id = trackId,
                        primaryArtistId = primaryArtistId,
                        albumId = albumId,
                        title = resolvedTitle,
                        normalizedTitle = resolvedTitle.lowercase(),
                        displayArtistName = resolvedArtist,
                        displayAlbumTitle = resolvedAlbum,
                        durationMs = resolvedDuration,
                        coverUri = resolvedCover ?: existingTrack?.coverUri,
                        canonicalAudioSourceType = "downloaded",
                        isLiked = existingTrack?.isLiked ?: false,
                        isDownloadedByAura = true,
                        createdAt = existingTrack?.createdAt ?: now,
                        updatedAt = now
                    )
                    database.trackDao().upsertTracks(listOf(trackEntity))

                    // 4. Media Link
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
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to download cloud track $trackId: ${e.message}")
            throw e
        }
    }

    suspend fun uploadCloudTrack(token: String, trackId: String) = withContext(Dispatchers.IO) {
        val trackRow = database.trackDao().getTrackById(trackId) ?: return@withContext
        val rawTrack = database.trackDao().getRawTrackById(trackId) ?: return@withContext
        val uriStr = trackRow.contentUri ?: return@withContext

        System.out.println("Uploading track $trackId ($uriStr) to cloud...")
        val file = if (uriStr.startsWith("file:/")) {
            File(java.net.URI(uriStr))
        } else {
            File(uriStr)
        }

        if (!file.exists() || !file.isFile) {
            System.err.println("File does not exist: ${file.absolutePath}")
            return@withContext
        }

        var uploadCoverUri = rawTrack.coverUri
        if (uploadCoverUri.isNullOrBlank() || !uploadCoverUri.startsWith("http")) {
            try {
                val searchResult = apiService.search("${trackRow.title} ${trackRow.artistName}", limitTracks = 3)
                val resolved = searchResult.data?.tracks?.firstOrNull { it.coverUri?.startsWith("http") == true }?.coverUri
                if (resolved != null) {
                    uploadCoverUri = resolved
                    if (rawTrack.coverUri.isNullOrBlank()) {
                        database.useWriterConnection { transactor ->
                            transactor.immediateTransaction {
                                val updatedTrack = rawTrack.copy(coverUri = resolved)
                                database.trackDao().upsertTracks(listOf(updatedTrack))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Failed to resolve cover online for upload of track $trackId: ${e.message}")
            }
        }

        val fileBytes = file.readBytes()
        val response = apiService.uploadSyncFile(
            token = token,
            trackId = trackId,
            fileBytes = fileBytes,
            mimeType = "audio/mpeg",
            title = trackRow.title,
            artistName = trackRow.artistName,
            albumTitle = trackRow.albumTitle,
            durationMs = trackRow.durationMs,
            artistId = rawTrack.primaryArtistId,
            albumId = rawTrack.albumId,
            coverUri = uploadCoverUri
        )

        if (response.data != null) {
            System.out.println("Track $trackId uploaded successfully to cloud.")
        } else {
            System.err.println("Failed to upload track $trackId: ${response.error?.message}")
        }
    }

    suspend fun deleteCloudTrack(token: String, trackId: String) = withContext(Dispatchers.IO) {
        System.out.println("Deleting track $trackId from cloud...")
        val response = apiService.deleteSyncFile(token, trackId)
        if (response.data?.deleted == true) {
            System.out.println("Track $trackId deleted successfully from cloud.")
        } else {
            System.err.println("Failed to delete track $trackId from cloud.")
        }
    }

    /**
     * Purge LRU automatique du cache de streaming : si le dossier dépasse maxSizeBytes (défaut: 500 Mo),
     * supprime les fichiers les plus anciens (least recently used) pour ramener l'usage à 80% du quota.
     */
    fun pruneStreamCacheIfNeeded(maxSizeBytes: Long = 500L * 1024 * 1024) {
        try {
            val appDir = File(System.getProperty("user.home"), ".aura")
            val streamCacheDir = File(appDir, "cache/stream")
            if (!streamCacheDir.exists()) return

            val files = streamCacheDir.listFiles() ?: return
            val totalSize = files.sumOf { it.length() }
            if (totalSize > maxSizeBytes) {
                val sorted = files.sortedBy { it.lastModified() }
                var currentSize = totalSize
                for (file in sorted) {
                    val length = file.length()
                    if (file.delete()) {
                        currentSize -= length
                        if (currentSize <= maxSizeBytes * 0.8) {
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Vide manuellement l'intégralité du cache de streaming temporaire.
     */
    fun clearStreamCache(): Long {
        var freedBytes = 0L
        try {
            val appDir = File(System.getProperty("user.home"), ".aura")
            val streamCacheDir = File(appDir, "cache/stream")
            if (streamCacheDir.exists()) {
                val files = streamCacheDir.listFiles() ?: emptyArray()
                for (f in files) {
                    val len = f.length()
                    if (f.delete()) freedBytes += len
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return freedBytes
    }
}
