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
        val exoPlayer = ExoPlayer.Builder(this)
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
                            repository.getLikedTracks().map { it.toMediaItem() }
                        }
                        "downloads" -> {
                            repository.getDownloadedTracks().map { it.toMediaItem() }
                        }
                        "tracks" -> {
                            repository.getAllTracks().map { it.toMediaItem() }
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
                                repository.getPlaylistTrackQueue(playlistId).map { it.toMediaItem() }
                            } else if (parentId.startsWith("album:")) {
                                val albumId = parentId.substringAfter("album:")
                                repository.getTracksForAlbum(albumId).map { it.toMediaItem() }
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
                                    createFolderItem("playlist:${it.id}", it.name, MediaMetadata.FOLDER_TYPE_PLAYLISTS)
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
                                track?.toMediaItem()
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

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                try {
                    val container = (application as AuraApplication).container
                    val repository = container.localLibraryRepository
                    val queueManager = container.queueManager

                    val resolvedItems = mutableListOf<MediaItem>()
                    for (item in mediaItems) {
                        val trackId = item.mediaId
                        val track = repository.getTrackById(trackId)
                        if (track != null) {
                            resolvedItems.add(track.toMediaItem())
                        } else {
                            resolvedItems.add(item)
                        }
                    }

                    // Resolution de la file d'attente (reprise du contexte de navigation)
                    if (mediaItems.size == 1) {
                        val targetTrackId = mediaItems[0].mediaId
                        val parentFolderId = lastBrowsedFolderId

                        if (parentFolderId != null) {
                            val contextTracks = when (parentFolderId) {
                                "favorites" -> repository.getLikedTracks()
                                "downloads" -> repository.getDownloadedTracks()
                                "tracks" -> repository.getAllTracks()
                                else -> {
                                    if (parentFolderId.startsWith("playlist:")) {
                                        val playlistId = parentFolderId.substringAfter("playlist:")
                                        repository.getPlaylistTrackQueue(playlistId)
                                    } else if (parentFolderId.startsWith("album:")) {
                                        val albumId = parentFolderId.substringAfter("album:")
                                        repository.getTracksForAlbum(albumId)
                                    } else {
                                        emptyList()
                                    }
                                }
                            }

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
                            // Fallback single track context si pas de dossier de navigation precedent
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
                    }

                    future.set(resolvedItems)
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
        val currentPlayer = mediaSession?.player
        if (currentPlayer == null || !currentPlayer.playWhenReady ||
            currentPlayer.mediaItemCount == 0 ||
            currentPlayer.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
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
fun TrackListRow.toMediaItem(): MediaItem {
    val artworkUri = coverUri?.let { Uri.parse(it) }
    val mediaUri = contentUri?.let { Uri.parse(it) }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(mediaUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistName)
                .setAlbumTitle(albumTitle)
                .setArtworkUri(artworkUri)
                .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()
}

fun <T> List<T>.paginate(page: Int, pageSize: Int): List<T> {
    val fromIndex = page * pageSize
    if (fromIndex >= size || fromIndex < 0) return emptyList()
    val toIndex = ((page + 1) * pageSize).coerceAtMost(size)
    return subList(fromIndex, toIndex)
}
