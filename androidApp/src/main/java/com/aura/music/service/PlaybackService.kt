package com.aura.music.service

import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.aura.music.AuraApplication
import com.aura.music.data.local.TrackListRow
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.TrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.common.collect.ImmutableList

/**
 * Service Android de lecture audio et de bibliotheque lie au cycle de vie systeme.
 * Supporte la navigation hierarchique via Android Auto.
 */
class PlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastBrowsedFolderId: String? = null

    override fun onCreate() {
        super.onCreate()
        val mediaSourceFactory = com.aura.music.core.MediaCacheManager.createMediaSourceFactory(this)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaLibrarySession.Builder(this, exoPlayer, librarySessionCallback).build()
        player = exoPlayer
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    private val librarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Filtrage des clients de confiance (système, Android Auto, Assistant, package local)
            val isTrusted = browser.isTrusted ||
                browser.packageName == "com.google.android.projection.gearhead" ||
                browser.packageName == "com.google.android.googlequicksearchbox" ||
                browser.packageName == packageName

            if (!isTrusted) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }

            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setTitle("AURA")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            lastBrowsedFolderId = parentId

            serviceScope.launch {
                try {
                    val container = (application as AuraApplication).container
                    val repository = container.localLibraryRepository
                    val connectedPackages = session.connectedControllers.map { it.packageName }

                    val items = when (parentId) {
                        "root" -> {
                            listOf(
                                createFolderItem("favorites", "Favoris", MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                                createFolderItem("downloads", "Téléchargements", MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                                createFolderItem("playlists", "Playlists", MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                                createFolderItem("albums", "Albums", MediaMetadata.FOLDER_TYPE_ALBUMS),
                                createFolderItem("tracks", "Titres", MediaMetadata.FOLDER_TYPE_MIXED)
                            )
                        }
                        "favorites" -> {
                            repository.getLikedTracks().map { it.toMediaItem(this@PlaybackService, browser.packageName, connectedPackages) }
                        }
                        "downloads" -> {
                            repository.getDownloadedTracks().map { it.toMediaItem(this@PlaybackService, browser.packageName, connectedPackages) }
                        }
                        "tracks" -> {
                            repository.getAllTracks().map { it.toMediaItem(this@PlaybackService, browser.packageName, connectedPackages) }
                        }
                        "playlists" -> {
                            repository.getPlaylists().map { playlist ->
                                createFolderItem(
                                    "playlist:${playlist.id}",
                                    playlist.name,
                                    MediaMetadata.FOLDER_TYPE_PLAYLISTS
                                )
                            }
                        }
                        "albums" -> {
                            repository.getAllBrowseAlbums().map { album ->
                                createFolderItem(
                                    "album:${album.id}",
                                    album.title,
                                    MediaMetadata.FOLDER_TYPE_ALBUMS,
                                    subtitle = album.artistName,
                                    artworkUri = album.coverUri?.let { Uri.parse(it) }
                                )
                            }
                        }
                        else -> {
                            if (parentId.startsWith("playlist:")) {
                                val playlistId = parentId.substringAfter("playlist:")
                                repository.getPlaylistTrackQueue(playlistId).map { it.toMediaItem(this@PlaybackService, browser.packageName, connectedPackages) }
                            } else if (parentId.startsWith("album:")) {
                                val albumId = parentId.substringAfter("album:")
                                repository.getTracksForAlbum(albumId).map { it.toMediaItem(this@PlaybackService, browser.packageName, connectedPackages) }
                            } else {
                                emptyList()
                            }
                        }
                    }

                    // Application de la pagination pour eviter les problemes de memoire sur de grandes listes
                    val paginatedItems = items.paginate(page, pageSize)
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(paginatedItems), params))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                try {
                    val container = (application as AuraApplication).container
                    val repository = container.localLibraryRepository
                    val connectedPackages = session.connectedControllers.map { it.packageName }

                    val item = when (mediaId) {
                        "favorites" -> createFolderItem("favorites", "Favoris", MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                        "downloads" -> createFolderItem("downloads", "Téléchargements", MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                        "playlists" -> createFolderItem("playlists", "Playlists", MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                        "albums" -> createFolderItem("albums", "Albums", MediaMetadata.FOLDER_TYPE_ALBUMS)
                        "tracks" -> createFolderItem("tracks", "Titres", MediaMetadata.FOLDER_TYPE_MIXED)
                        else -> {
                            if (mediaId.startsWith("playlist:")) {
                                val playlistId = mediaId.substringAfter("playlist:")
                                val playlist = repository.getPlaylistDetail(playlistId)
                                playlist?.let {
                                    createFolderItem("playlist:${it.summary.id}", it.summary.name, MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                                }
                            } else if (mediaId.startsWith("album:")) {
                                val albumId = mediaId.substringAfter("album:")
                                val album = repository.getAlbumDetail(albumId)
                                album?.let {
                                    createFolderItem(
                                        "album:${it.summary.id}",
                                        it.summary.title,
                                        MediaMetadata.FOLDER_TYPE_ALBUMS,
                                        subtitle = it.summary.artistName,
                                        artworkUri = it.summary.coverUri?.let { uri -> Uri.parse(uri) }
                                    )
                                }
                            } else {
                                val track = repository.getTrackById(mediaId)
                                track?.toMediaItem(this@PlaybackService, browser.packageName, connectedPackages)
                            }
                        }
                    }

                    if (item != null) {
                        future.set(LibraryResult.ofItem(item, null))
                    } else {
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        private suspend fun resolveMediaItemsHelper(
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): List<MediaItem> {
            val container = (application as AuraApplication).container
            val repository = container.localLibraryRepository
            val queueManager = container.queueManager
            val connectedPackages = mediaSession?.connectedControllers?.map { it.packageName } ?: emptyList()

            val resolvedItems = mutableListOf<MediaItem>()

            // Resolution de la file d'attente (reprise du contexte de navigation)
            if (mediaItems.size == 1 && controller.packageName != packageName) {
                val targetTrackId = mediaItems[0].mediaId
                var parentFolderId = lastBrowsedFolderId

                // Helper function to load context tracks dynamically
                suspend fun getTracksForFolder(folderId: String): List<TrackListRow> {
                    return when (folderId) {
                        "favorites" -> repository.getLikedTracks()
                        "downloads" -> repository.getDownloadedTracks()
                        "tracks" -> repository.getAllTracks()
                        else -> {
                            if (folderId.startsWith("playlist:")) {
                                val playlistId = folderId.substringAfter("playlist:")
                                repository.getPlaylistTrackQueue(playlistId)
                            } else if (folderId.startsWith("album:")) {
                                val albumId = folderId.substringAfter("album:")
                                repository.getTracksForAlbum(albumId)
                            } else {
                                emptyList()
                            }
                        }
                    }
                }

                // Validate that the browsed folder contains our target track, or search for it
                var contextTracks = emptyList<TrackListRow>()
                if (parentFolderId != null) {
                    contextTracks = getTracksForFolder(parentFolderId)
                    if (contextTracks.none { it.id == targetTrackId }) {
                        parentFolderId = null
                    }
                }

                // Fallback search priorities if parentFolderId is null or track wasn't found in it
                if (parentFolderId == null) {
                    val likedTracks = repository.getLikedTracks()
                    if (likedTracks.any { it.id == targetTrackId }) {
                        parentFolderId = "favorites"
                        contextTracks = likedTracks
                    } else {
                        val downloadedTracks = repository.getDownloadedTracks()
                        if (downloadedTracks.any { it.id == targetTrackId }) {
                            parentFolderId = "downloads"
                            contextTracks = downloadedTracks
                        } else {
                            val allTracks = repository.getAllTracks()
                            if (allTracks.any { it.id == targetTrackId }) {
                                parentFolderId = "tracks"
                                contextTracks = allTracks
                            }
                        }
                    }
                }

                if (parentFolderId != null) {
                    val mappedQueuedTracks = contextTracks.map {
                        QueuedTrack(
                            trackId = it.id,
                            title = it.title,
                            artistName = it.artistName,
                            albumTitle = it.albumTitle,
                            contentUri = it.contentUri,
                            durationMs = it.durationMs,
                            coverUri = it.coverUri,
                            source = TrackSource.CONTEXT
                        )
                    }

                    val startIndex = mappedQueuedTracks.indexOfFirst { it.trackId == targetTrackId }
                    if (startIndex != -1) {
                        queueManager.setContext(
                            type = when {
                                parentFolderId == "favorites" -> "favorites"
                                parentFolderId == "downloads" -> "downloads"
                                parentFolderId == "tracks" -> "library_tracks"
                                parentFolderId.startsWith("playlist:") -> "playlist"
                                parentFolderId.startsWith("album:") -> "album"
                                else -> "library_tracks"
                            },
                            id = parentFolderId,
                            tracks = mappedQueuedTracks,
                            startIndex = startIndex
                        )
                    }
                } else {
                    // Fallback single track context if not found in any common folders
                    val track = repository.getTrackById(targetTrackId)
                    if (track != null) {
                        val queuedTrack = QueuedTrack(
                            trackId = track.id,
                            title = track.title,
                            artistName = track.artistName,
                            albumTitle = track.albumTitle,
                            contentUri = track.contentUri,
                            durationMs = track.durationMs,
                            coverUri = track.coverUri,
                            source = TrackSource.CONTEXT
                        )
                        queueManager.setContext(
                            type = "single_track",
                            id = targetTrackId,
                            tracks = listOf(queuedTrack),
                            startIndex = 0
                        )
                    }
                }

                // Build the sliding triplet [prev, current, next] around the active track
                val state = queueManager.state.value
                val currentTrack = state.currentTrack
                if (currentTrack != null) {
                    val desiredTracks = mutableListOf<QueuedTrack>()
                    val rawPrev = state.history.lastOrNull()
                    val rawNext = state.priorityQueue.firstOrNull() ?: queueManager.getUpcomingContextTracks().firstOrNull()

                    val prev = if (rawPrev?.trackId != currentTrack.trackId) rawPrev else null
                    val next = if (rawNext?.trackId != currentTrack.trackId) rawNext else null

                    if (prev != null) desiredTracks.add(prev)
                    desiredTracks.add(currentTrack)
                    if (next != null) desiredTracks.add(next)

                    for (t in desiredTracks) {
                        val resolvedItem = repository.getTrackById(t.trackId)?.toMediaItem(
                            this@PlaybackService,
                            controller.packageName,
                            connectedPackages
                        )
                        if (resolvedItem != null) {
                            resolvedItems.add(resolvedItem)
                        }
                    }
                }
            }

            if (resolvedItems.isEmpty()) {
                // Fallback resolution standard si non-contexte externe ou erreur
                for (item in mediaItems) {
                    val trackId = item.mediaId
                    val track = repository.getTrackById(trackId)
                    if (track != null) {
                        resolvedItems.add(track.toMediaItem(this@PlaybackService, controller.packageName, connectedPackages))
                    } else {
                        resolvedItems.add(item)
                    }
                }
            }

            return resolvedItems
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                try {
                    val resolved = resolveMediaItemsHelper(controller, mediaItems)
                    future.set(resolved)
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    val resolved = resolveMediaItemsHelper(controller, mediaItems)
                    
                    // Resolve correct starting index based on target track ID ONLY for external controllers.
                    // For the internal application controller, use the requested startIndex directly.
                    val resolvedIndex = if (controller.packageName != packageName && mediaItems.firstOrNull()?.mediaId != null) {
                        resolved.indexOfFirst { it.mediaId == mediaItems.firstOrNull()?.mediaId }
                    } else -1
                    
                    val targetStartIndex = if (resolvedIndex != -1) resolvedIndex else startIndex
                    
                    future.set(MediaSession.MediaItemsWithStartPosition(resolved, targetStartIndex, startPositionMs))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    private fun createFolderItem(
        id: String,
        title: String,
        folderType: Int,
        subtitle: String? = null,
        artworkUri: Uri? = null
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artworkUri)
                    .setFolderType(folderType)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .build()
            )
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val container = (application as? AuraApplication)?.container
        container?.applicationScope?.launch(Dispatchers.IO) {
            container.playbackStateStore.flushPendingPlaybackSnapshot()
        }
        val currentPlayer = mediaSession?.player
        if (currentPlayer == null || !currentPlayer.playWhenReady ||
            currentPlayer.mediaItemCount == 0 ||
            currentPlayer.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val container = (application as? AuraApplication)?.container
        container?.applicationScope?.launch(Dispatchers.IO) {
            container.playbackStateStore.flushPendingPlaybackSnapshot()
        }
        serviceScope.cancel() // Annulation propre pour eviter les fuites memoire
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }
}

// Mappers d'extension utilitaires pour la conversion propre vers MediaItem avec metadonnees completes
fun TrackListRow.toMediaItem(
    context: android.content.Context,
    clientPackageName: String? = null,
    connectedPackages: List<String> = emptyList()
): MediaItem {
    val artworkUri = coverUri?.let { uriStr ->
        if (uriStr.startsWith("/") || uriStr.startsWith("file://")) {
            val filePath = if (uriStr.startsWith("file://")) {
                uriStr.substring(7)
            } else {
                uriStr
            }
            val file = java.io.File(filePath)
            // Serve cover art via public ArtworkContentProvider (Zero-Jank async I/O)
            Uri.parse("content://com.aura.music.artwork/covers/${file.name}")
        } else {
            Uri.parse(uriStr)
        }
    }
    val mediaUri = if (!contentUri.isNullOrBlank()) {
        Uri.parse(contentUri)
    } else if (id.isNotBlank()) {
        val rawToken = com.aura.music.core.AuthSessionManager.getInstance(context).authToken.value
        val base = "${com.aura.music.data.network.BuildConfig.API_BASE_URL.trimEnd('/')}/me/sync/files/$id"
        val fullUrl = if (!rawToken.isNullOrBlank()) {
            val clean = if (rawToken.startsWith("Bearer ", ignoreCase = true)) rawToken.substring(7).trim() else rawToken.trim()
            "$base?token=$clean"
        } else {
            base
        }
        Uri.parse(fullUrl)
    } else null
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artistName)
        .setAlbumTitle(albumTitle)
        .setArtworkUri(artworkUri)
        .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(mediaUri)
        .setMediaMetadata(metadata)
        .build()
}

fun <T> List<T>.paginate(page: Int, pageSize: Int): List<T> {
    val fromIndex = page * pageSize
    if (fromIndex >= size || fromIndex < 0) return emptyList()
    val toIndex = ((page + 1) * pageSize).coerceAtMost(size)
    return subList(fromIndex, toIndex)
}
