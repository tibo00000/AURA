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
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.aura.music.AuraApplication
import com.aura.music.data.local.TrackListRow
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.TrackSource
import com.aura.music.domain.player.occurrenceId
import com.aura.music.domain.player.withOccurrence
import com.aura.music.domain.player.findPlaylistResumeIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
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
    private val controllerTrust by lazy { ControllerTrustPolicy(this) }
    private val privatePlaybackReady = PlaybackSessionReadiness()

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

        val sessionActivityIntent = Intent(this, com.aura.music.MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaLibrarySession.Builder(this, exoPlayer, librarySessionCallback)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
        player = exoPlayer
        serviceScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            // Secure preferences/Keystore may initialize on a cold Auto/service start.
            lateinit var auth: com.aura.music.core.AuthSessionManager
            lateinit var privatePlayback: PrivatePlaybackGuard
            if (!privatePlaybackReady.initialize {
                auth = withContext(Dispatchers.IO) { (application as AuraApplication).container.authSessionManager }
                privatePlayback = PrivatePlaybackGuard(exoPlayer, auth.sessionScope.value)
            }) {
                android.util.Log.e("PlaybackService", "Initialisation de session audio indisponible")
                return@launch
            }
            auth.sessionScope.collect(privatePlayback::onScopeChanged)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession.takeIf { controllerTrust.allows(controllerInfo) }

    internal val librarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controllerTrust.allows(controller)) MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            else MediaSession.ConnectionResult.reject()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Filtrage des clients de confiance (système, Android Auto, Assistant, package local)
            if (!controllerTrust.allows(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
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
            if (!controllerTrust.allows(browser)) return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
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
                                val container = (application as AuraApplication).container
                                val rows = repository.getPlaylistDetail(playlistId)?.tracks.orEmpty()
                                val eligible = container.playlistPlayback.prepare(playlistId, rows)
                                container.playlistPlayback.verifyLater(playlistId)
                                eligible.map { it.toPlaylistMediaItem(this@PlaybackService, playlistId) }
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
            if (!controllerTrust.allows(browser)) return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
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
                            if (mediaId.startsWith("playlist-entry:")) {
                                decodePlaylistBrowseId(mediaId)?.let { (playlistId, occurrenceId) ->
                                    val rows = repository.getPlaylistDetail(playlistId)?.tracks.orEmpty()
                                    val eligible = container.playlistPlayback.prepare(playlistId, rows)
                                    container.playlistPlayback.verifyLater(playlistId)
                                    eligible.firstOrNull { it.internalId == occurrenceId }
                                        ?.toPlaylistMediaItem(this@PlaybackService, playlistId)
                                }
                            } else if (mediaId.startsWith("playlist:")) {
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
                        future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
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
            if (!controllerTrust.allows(controller)) throw SecurityException("Contrôleur non autorisé")
            privatePlaybackReady.await() // Admit no source until the non-blocking session observer is ready.
            val container = (application as AuraApplication).container
            val repository = container.localLibraryRepository
            val playlistRequest = mediaItems.singleOrNull()?.mediaId?.takeIf { it.startsWith("playlist-entry:") }
            val browsedPlaylist = lastBrowsedFolderId?.takeIf { it.startsWith("playlist:") }
            val requestsBrowsedTrack = browsedPlaylist != null && mediaItems.singleOrNull()?.mediaId?.isNotBlank() == true
            if (!controllerTrust.isInternal(controller) && (playlistRequest != null || requestsBrowsedTrack)) {
                val sessionScope = container.authSessionManager.sessionScope.value
                val selectedReference = playlistRequest?.let(::decodePlaylistBrowseId)
                if (playlistRequest != null && selectedReference == null) return emptyList()
                val playlistId = selectedReference?.first ?: browsedPlaylist?.removePrefix("playlist:") ?: return emptyList()
                val rows = repository.getPlaylistDetail(playlistId)?.tracks.orEmpty()
                val eligible = container.playlistPlayback.prepare(playlistId, rows)
                container.playlistPlayback.verifyLater(playlistId)
                val index = if (selectedReference != null) eligible.indexOfFirst { it.internalId == selectedReference.second }
                else eligible.indexOfFirst { it.trackId == mediaItems.single().mediaId }
                if (index < 0 || container.authSessionManager.sessionScope.value != sessionScope) return emptyList()
                container.queueManager.setContext("playlist", playlistId, eligible, index)
                return listOfNotNull(eligible[index], container.queueManager.peekNext())
                    .distinctBy { it.internalId }.map { it.toPlaylistMediaItem(this@PlaybackService) }
            }
            val admittedItems = ControllerMediaResolver(this@PlaybackService, controllerTrust,
                com.aura.music.core.PrivateStreamPolicy(java.net.URI(com.aura.music.data.network.BuildConfig.API_BASE_URL)),
                repository::getTrackById, { repository.searchLocalTracks(it, limit = 20) }
            ).resolve(controller, mediaItems)
            val queueManager = container.queueManager
            val connectedPackages = mediaSession?.connectedControllers?.map { it.packageName } ?: emptyList()

            val resolvedItems = mutableListOf<MediaItem>()

            // Resolution de la file d'attente (reprise du contexte de navigation)
            if (admittedItems.size == 1 && !controllerTrust.isInternal(controller)) {
                val targetTrackId = admittedItems[0].mediaId
                var parentFolderId = lastBrowsedFolderId

                // Helper function to load context tracks dynamically
                suspend fun getTracksForFolder(folderId: String): List<TrackListRow> {
                    return when (folderId) {
                        "favorites" -> repository.getLikedTracks()
                        "downloads" -> repository.getDownloadedTracks()
                        "tracks" -> repository.getAllTracks()
                        else -> if (folderId.startsWith("album:")) {
                            repository.getTracksForAlbum(folderId.substringAfter("album:"))
                        } else emptyList()
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
                resolvedItems.addAll(admittedItems)
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
                    val resolvedIndex = if (!controllerTrust.isInternal(controller) && mediaItems.size == 1) {
                        val requestedOccurrence = decodePlaylistBrowseId(mediaItems.first().mediaId)?.second
                        val requestedId = mediaItems.first().mediaId.takeIf { it.isNotBlank() }
                            ?: (application as AuraApplication).container.queueManager.state.value.currentTrack?.trackId
                        resolved.indexOfFirst { if (requestedOccurrence != null) it.occurrenceId() == requestedOccurrence else it.mediaId == requestedId }
                    } else -1
                    
                    val targetStartIndex = if (resolvedIndex != -1) resolvedIndex else if (resolved.isEmpty()) C.INDEX_UNSET
                        else if (startIndex == C.INDEX_UNSET) startIndex else startIndex.coerceIn(0, resolved.lastIndex)
                    
                    future.set(MediaSession.MediaItemsWithStartPosition(resolved, targetStartIndex, startPositionMs))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    if (!controllerTrust.allows(controller)) throw SecurityException("Contrôleur non autorisé")
                    privatePlaybackReady.await()
                    val container = (application as AuraApplication).container
                    val sessionScope = container.authSessionManager.sessionScope.value
                    val snapshot = container.playbackStateStore.restoreLocalPlaylist()
                    val contextId = snapshot?.contextId
                    val detail = contextId?.let {
                        container.localLibraryRepository.getPlaylistDetail(it)
                            ?: container.localLibraryRepository.getPlaylistDetail(it.removePrefix("playlist:"))
                    }
                    if (snapshot == null || detail == null || snapshot.currentTrackId == null) {
                        future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET))
                        return@launch
                    }
                    val playlistId = detail.summary.id
                    val eligible = container.playlistPlayback.prepare(playlistId, detail.tracks)
                    container.playlistPlayback.verifyLater(playlistId)
                    if (eligible.isEmpty() || container.authSessionManager.sessionScope.value != sessionScope) {
                        future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET))
                        return@launch
                    }
                    val index = findPlaylistResumeIndex(eligible, snapshot.currentTrackId, snapshot.playlistOccurrenceId)
                    val start = index.coerceAtLeast(0)
                    container.queueManager.restoreModes(snapshot.shuffleEnabled, snapshot.repeatMode)
                    container.queueManager.setContext("playlist", playlistId, eligible, start)
                    future.set(MediaSession.MediaItemsWithStartPosition(
                        listOfNotNull(eligible[start], container.queueManager.peekNext())
                            .distinctBy { it.internalId }.map { it.toPlaylistMediaItem(this@PlaybackService) },
                        0,
                        if (index < 0) C.TIME_UNSET else snapshot.positionMs,
                    ))
                } catch (failure: Exception) {
                    future.setException(failure)
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
        privatePlaybackReady.close()
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
        // Stream direct depuis le Cloud personnel AURA (le Bearer token est injecté via MediaCacheManager)
        val fullUrl = "${com.aura.music.data.network.BuildConfig.API_BASE_URL.trimEnd('/')}/me/sync/files/$id"
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

private fun QueuedTrack.toPlaylistMediaItem(context: android.content.Context, browsePlaylistId: String? = null): MediaItem {
    val row = TrackListRow(trackId, null, null, title, artistName, albumTitle, contentUri, durationMs, coverUri, false)
    val item = row.toMediaItem(context)
    require(com.aura.music.core.PrivateStreamPolicy.validTrackId(trackId))
    val builder = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().withOccurrence(internalId).build())
    if (contentUri == null && browsePlaylistId == null) {
        val origin = java.net.URI(com.aura.music.data.network.BuildConfig.API_BASE_URL)
        builder.setUri(com.aura.music.core.PrivateStreamPolicy(origin).uriFor(trackId))
    }
    if (browsePlaylistId != null) {
        builder.setMediaId("playlist-entry:${Uri.encode(browsePlaylistId)}:${Uri.encode(internalId)}")
        builder.setUri(null as Uri?)
    }
    return builder.build()
}

private fun decodePlaylistBrowseId(mediaId: String): Pair<String, String>? {
    if (!mediaId.startsWith("playlist-entry:") || mediaId.length > 1400) return null
    val parts = mediaId.removePrefix("playlist-entry:").split(':', limit = 2)
    if (parts.size != 2) return null
    val playlistId = Uri.decode(parts[0])
    val occurrenceId = Uri.decode(parts[1])
    return if (playlistId.isNotBlank() && occurrenceId.isNotBlank()) playlistId to occurrenceId else null
}

fun <T> List<T>.paginate(page: Int, pageSize: Int): List<T> {
    val fromIndex = page * pageSize
    if (fromIndex >= size || fromIndex < 0) return emptyList()
    val toIndex = ((page + 1) * pageSize).coerceAtMost(size)
    return subList(fromIndex, toIndex)
}
