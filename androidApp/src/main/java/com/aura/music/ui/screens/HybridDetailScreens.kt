package com.aura.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.ArtistDetailResponseData
import com.aura.music.data.network.AlbumDetailResponseData
import com.aura.music.data.network.TrackSummary
import com.aura.music.data.network.AlbumSummary
import com.aura.music.data.repository.ArtistDetail
import com.aura.music.data.repository.AlbumDetail
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.trackList
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.domain.player.PlayerEvent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

private fun TrackSummary.toTrackListRow(artistId: String? = null, albumId: String? = null): TrackListRow = TrackListRow(
    id = id,
    artistId = artistId,
    albumId = albumId,
    title = title,
    artistName = displayArtistName,
    albumTitle = displayAlbumTitle,
    contentUri = null,
    durationMs = durationMs.toLong(),
    coverUri = coverUri,
    isLiked = isLiked,
    createdAt = 0L,
    updatedAt = 0L
)

// =============================================================================
// HybridArtistScreen (AND-010)
// =============================================================================

/**
 * Écran artiste hybride selon le layout canonique docs/android/screens/artist-layout.md.
 *
 * - Ouverture instantanée depuis Room (localData)
 * - Enrichissement asynchrone non bloquant (onlineData + picture_uri)
 * - Si onlineData arrive, les top_tracks backend remplacent la tracklist locale vide
 */
@Composable
fun HybridArtistScreen(
    viewModel: ArtistDetailViewModel,
    playlists: List<PlaylistListRow>,
    onNavigateBack: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onLikeTrack: (TrackListRow) -> Unit,
    onAddTrackToPlaylist: (PlaylistListRow, TrackListRow) -> Unit,
    onDeleteTrack: (TrackListRow) -> Unit,
    onAddToQueue: (TrackListRow) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val artist = state.localData
    val onlineData = state.onlineData

    // Title from local if available, else from online, else placeholder
    val showAllOnlineTracks = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null) }
    var trackToDelete by remember { mutableStateOf<TrackListRow?>(null) }

    val context = LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val localLibraryRepository = appContainer.localLibraryRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value
    val allLibraryTracksState = produceState(initialValue = emptyList<TrackListRow>(), localLibraryRepository) {
        value = localLibraryRepository.getAllTracks()
    }
    val allLibraryTracks = allLibraryTracksState.value
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    RouteScaffold(
        title = null,
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState
    ) {
        if (state.isLocalLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BlazeOrange)
            }
            return@RouteScaffold
        }

        if (artist == null && onlineData == null) {
            if (state.isEnrichmentLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BlazeOrange)
                }
                return@RouteScaffold
            } else {
                EmptyStateSurface(
                    title = "Artiste introuvable",
                    message = "Cet artiste n'existe pas ou n'est plus accessible.",
                )
                return@RouteScaffold
            }
        }

        val downloadRepository = appContainer.downloadRepository
        val allDownloadJobs by downloadRepository.getAllJobsWithTrack().collectAsState(initial = emptyList())
        val trackDownloadStatusMap = remember(allDownloadJobs) {
            allDownloadJobs.associate { job ->
                val status: TrackDownloadStatus = when (job.status) {
                    "succeeded" -> TrackDownloadStatus.Downloaded
                    "running" -> TrackDownloadStatus.Downloading((job.progressPercent ?: 0f) / 100f)
                    "queued", "requires_resolution" -> TrackDownloadStatus.Queued
                    "failed" -> TrackDownloadStatus.Failed(job.errorCode, job.errorMessage)
                    else -> TrackDownloadStatus.Idle
                }
                job.trackId to status
            }
        }

        val allOnlineMapped = remember(onlineData?.topTracks, artist?.summary?.id, onlineData?.id) {
            onlineData?.topTracks?.map { it.toTrackListRow(artistId = artist?.summary?.id ?: onlineData.id) } ?: emptyList()
        }

        com.aura.music.ui.components.AuraLazyColumn(
            modifier = Modifier.fillMaxSize().background(DeepBlack),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ---- HERO section ----
            item(key = "hero") {
                ArtistHeroSection(
                    name = artist?.summary?.name ?: onlineData?.name ?: "",
                    pictureUri = artist?.summary?.pictureUri ?: onlineData?.pictureUri,
                    summary = artist?.summary?.summary ?: onlineData?.summary,
                    trackCount = artist?.topTracks?.size ?: onlineData?.topTracks?.size ?: 0,
                    albumCount = artist?.albums?.size ?: onlineData?.albums?.size ?: 0,
                    isEnrichmentLoading = state.isEnrichmentLoading,
                )
            }

            // ---- TRACKLIST ----
            // Prefer local tracks; fall back to online top_tracks summary if local is empty
            val localTracks = artist?.topTracks ?: emptyList()
            if (localTracks.isNotEmpty()) {
                val onUploadToCloudLambda = { track: TrackListRow ->
                    val isLocalScanned = track.contentUri?.startsWith("content://") == true
                    val isAlreadySynced = syncedCloudTrackIds.contains(track.id)
                    if (isLocalScanned && !isAlreadySynced) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                            cloudFileRepository.uploadTrack(track.id).collect { res ->
                                res.onSuccess {
                                    snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                                    cloudFileRepository.refreshSyncedTrackIds()
                                    viewModel.refreshLocal()
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                                }
                            }
                        }
                    }
                }
                val onDownloadFromCloudLambda = { track: TrackListRow ->
                    val isCloudOnly = track.contentUri.isNullOrBlank()
                    val isPresentInCloud = syncedCloudTrackIds.contains(track.id)
                    if (isCloudOnly && isPresentInCloud) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Téléchargement cloud lancé pour : ${track.title}")
                            cloudFileRepository.downloadTrack(
                                trackId = track.id,
                                title = track.title,
                                artistName = track.artistName,
                                albumTitle = track.albumTitle,
                                durationMs = track.durationMs,
                                artistId = track.artistId,
                                albumId = track.albumId,
                                coverUri = track.coverUri
                            ).collect { res ->
                                res.onSuccess {
                                    snackbarHostState.showSnackbar("Téléchargement cloud réussi : ${track.title}")
                                    appContainer.localLibraryRepository.refreshLocalMediaIndex()
                                    viewModel.refreshLocal()
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                                }
                            }
                        }
                    }
                }

                trackList(
                    title = "Titres populaires",
                    tracks = localTracks,
                    contextType = "artist",
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = { },
                    onOpenAlbum = onOpenAlbum,
                    onPlayNow = { track -> onPlayTrackInList(track, localTracks, "artist") },
                    onAddToQueue = onAddToQueue,
                    onAddTrackToPlaylist = { track -> activeTrackForPlaylist = track },
                    onLikeTrack = onLikeTrack,
                    onDeleteDownload = { track -> trackToDelete = track },
                    onUploadToCloud = onUploadToCloudLambda,
                    onDownloadFromCloud = onDownloadFromCloudLambda
                )
            } else if (onlineData != null && onlineData.topTracks.isNotEmpty()) {
                item(key = "online_tracklist_header") {
                    Text(
                        "Titres populaires",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                val displayedTracks = if (showAllOnlineTracks.value) onlineData.topTracks else onlineData.topTracks.take(5)
                items(displayedTracks, key = { it.id }) { track ->
                    val normTitle = track.title.lowercase().trim()
                    val normArtist = track.displayArtistName.lowercase().trim()

                    val matchedLocal = allLibraryTracks.firstOrNull {
                        (it.id == track.id || (it.title.lowercase().trim() == normTitle && it.artistName.lowercase().trim() == normArtist)) &&
                        !it.contentUri.isNullOrBlank()
                    }
                    val isDownloaded = matchedLocal != null

                    val isSynced = syncedCloudTrackIds.contains(track.id) || cloudFiles.any {
                        it.trackId == track.id ||
                        ((it.title?.lowercase()?.trim() ?: "") == normTitle && (it.artistName?.lowercase()?.trim() ?: "") == normArtist)
                    } || allLibraryTracks.any {
                        it.id == track.id || (it.title.lowercase().trim() == normTitle && it.artistName.lowercase().trim() == normArtist)
                    }

                    val dlStatus = trackDownloadStatusMap[track.id] ?: TrackDownloadStatus.Idle
                    val trackRow = track.toTrackListRow(artistId = artist?.summary?.id ?: onlineData.id)
                    val trackToPlay = matchedLocal ?: trackRow

                    InteractiveOnlineTrackRow(
                        track = track,
                        showCover = true,
                        isDownloaded = isDownloaded,
                        isSyncedToCloud = isSynced && !isDownloaded,
                        downloadStatus = dlStatus,
                        onPlay = {
                            if (isSynced || isDownloaded) {
                                onPlayTrackInList(trackToPlay, allOnlineMapped, "artist_online")
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Ajout au Cloud en cours pour : ${track.title}")
                                    downloadRepository.triggerDownload(
                                        trackId = track.id,
                                        title = track.title,
                                        artistName = track.displayArtistName,
                                        albumTitle = track.displayAlbumTitle,
                                        coverUri = track.coverUri,
                                        userToken = com.aura.music.data.repository.SyncRepository.AUTH_TOKEN
                                    ).collect { }
                                }
                            }
                        },
                        onDownloadCloud = {
                            scope.launch {
                                snackbarHostState.showSnackbar("Ajout au Cloud lancé pour : ${track.title}")
                                downloadRepository.triggerDownload(
                                    trackId = track.id,
                                    title = track.title,
                                    artistName = track.displayArtistName,
                                    albumTitle = track.displayAlbumTitle,
                                    coverUri = track.coverUri,
                                    userToken = com.aura.music.data.repository.SyncRepository.AUTH_TOKEN
                                ).collect { }
                            }
                        },
                        onAddToQueue = { onAddToQueue(trackRow) },
                        onAddToPlaylist = { activeTrackForPlaylist = trackRow },
                        onLike = { onLikeTrack(trackRow) }
                    )
                }
                if (!showAllOnlineTracks.value && onlineData.topTracks.size > 5) {
                    item(key = "show_more_tracks") {
                        androidx.compose.material3.TextButton(
                            onClick = { showAllOnlineTracks.value = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Afficher tous les titres", color = BlazeOrange)
                        }
                    }
                }
            }

            // ---- ALBUMS ----
            val localAlbums = artist?.albums ?: emptyList()
            val onlineAlbums = onlineData?.albums
                ?.sortedByDescending { it.releaseDate.orEmpty() }
                ?: emptyList()

            if (localAlbums.isNotEmpty()) {
                item(key = "albums_section") {
                    SectionTitle("Albums", "Discographie disponible localement.")
                    Spacer(Modifier.height(8.dp))
                    BrowseAlbumRail(albums = localAlbums, onOpenAlbum = onOpenAlbum)
                }
            } else if (onlineAlbums.isNotEmpty()) {
                val singles = onlineAlbums.filter { it.isSingleRelease() }
                val albums = onlineAlbums.filterNot { it.isSingleRelease() }

                if (albums.isNotEmpty()) {
                    item(key = "online_albums_section") {
                        OnlineAlbumRailSection(
                            title = "Albums",
                            albums = albums,
                            onOpenAlbum = onOpenAlbum,
                        )
                    }
                }
                if (singles.isNotEmpty()) {
                    item(key = "online_singles_section") {
                        OnlineAlbumRailSection(
                            title = "Singles",
                            albums = singles,
                            onOpenAlbum = onOpenAlbum,
                        )
                    }
                }
            }

            // ---- Enrichment error banner (non-blocking) ----
            if (state.enrichmentError != null) {
                item(key = "enrichment_error") {
                    Text(
                        text = "⚠ ${state.enrichmentError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                onAddTrackToPlaylist(playlist, activeTrackForPlaylist!!)
                activeTrackForPlaylist = null
            }
        )
    }

    if (trackToDelete != null) {
        ConfirmDialog(
            title = "Supprimer de l'appareil ?",
            message = "Voulez-vous vraiment supprimer ce titre de votre appareil ? Cette action supprimera définitivement le fichier physique.",
            confirmLabel = "Supprimer",
            onDismiss = { trackToDelete = null },
            onConfirm = {
                onDeleteTrack(trackToDelete!!)
                trackToDelete = null
            }
        )
    }
}

// =============================================================================
// HybridAlbumScreen (AND-010)
// =============================================================================

/**
 * Écran album hybride selon le layout canonique docs/android/screens/album-layout.md.
 */
@Composable
fun HybridAlbumScreen(
    viewModel: AlbumDetailViewModel,
    playlists: List<PlaylistListRow>,
    onNavigateBack: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onLikeTrack: (TrackListRow) -> Unit,
    onAddTrackToPlaylist: (PlaylistListRow, TrackListRow) -> Unit,
    onDeleteTrack: (TrackListRow) -> Unit,
    onAddToQueue: (TrackListRow) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val album = state.localData
    val onlineData = state.onlineData

    val screenTitle = album?.summary?.title ?: onlineData?.title ?: "Album"
    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null) }
    var trackToDelete by remember { mutableStateOf<TrackListRow?>(null) }

    val context = LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val localLibraryRepository = appContainer.localLibraryRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value
    val allLibraryTracksState = produceState(initialValue = emptyList<TrackListRow>(), localLibraryRepository) {
        value = localLibraryRepository.getAllTracks()
    }
    val allLibraryTracks = allLibraryTracksState.value
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    RouteScaffold(
        title = screenTitle,
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState
    ) {
        if (state.isLocalLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BlazeOrange)
            }
            return@RouteScaffold
        }

        if (album == null && onlineData == null) {
            if (state.isEnrichmentLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BlazeOrange)
                }
                return@RouteScaffold
            } else {
                EmptyStateSurface(
                    title = "Album introuvable",
                    message = "Cet album n'existe pas ou n'est plus accessible.",
                )
                return@RouteScaffold
            }
        }

        // Resolve displayed metadata: local wins, online enriches
        val coverUri = album?.summary?.coverUri ?: onlineData?.coverUri
        val title = album?.summary?.title ?: onlineData?.title ?: ""
        val artistId = album?.summary?.artistId
        val artistName = album?.summary?.artistName ?: onlineData?.primaryArtistName ?: "Artiste inconnu"
        val releaseDate = album?.summary?.releaseDate ?: onlineData?.releaseDate
        val trackCount = album?.summary?.trackCount ?: onlineData?.trackCount
        val localTracks = album?.tracks ?: emptyList()

        val downloadRepository = appContainer.downloadRepository
        val allDownloadJobs by downloadRepository.getAllJobsWithTrack().collectAsState(initial = emptyList())
        val trackDownloadStatusMap = remember(allDownloadJobs) {
            allDownloadJobs.associate { job ->
                val status: TrackDownloadStatus = when (job.status) {
                    "succeeded" -> TrackDownloadStatus.Downloaded
                    "running" -> TrackDownloadStatus.Downloading((job.progressPercent ?: 0f) / 100f)
                    "queued", "requires_resolution" -> TrackDownloadStatus.Queued
                    "failed" -> TrackDownloadStatus.Failed(job.errorCode, job.errorMessage)
                    else -> TrackDownloadStatus.Idle
                }
                job.trackId to status
            }
        }

        var isAlbumBatchDownloading by remember { mutableStateOf(false) }

        val allOnlineAlbumMapped = remember(onlineData?.tracks, artistId, album?.summary?.id, onlineData?.id) {
            onlineData?.tracks?.map { it.toTrackListRow(artistId = artistId, albumId = album?.summary?.id ?: onlineData.id) } ?: emptyList()
        }

        com.aura.music.ui.components.AuraLazyColumn(
            modifier = Modifier.fillMaxSize().background(DeepBlack),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ---- HERO cover ----
            item(key = "hero") {
                AlbumHeroSection(
                    title = title,
                    artistId = artistId,
                    artistName = artistName,
                    coverUri = coverUri,
                    releaseDate = releaseDate,
                    trackCount = trackCount,
                    localTrackCount = localTracks.size,
                    isEnrichmentLoading = state.isEnrichmentLoading,
                    onOpenArtist = onOpenArtist,
                )
            }

            // ---- ACTION bar ----
            item(key = "actions") {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (localTracks.isNotEmpty()) {
                        Button(
                            onClick = {
                                onPlayTrackInList(localTracks.first(), localTracks, "album")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Play")
                        }
                        Button(
                            onClick = {
                                val shuffled = localTracks.shuffled()
                                if (shuffled.isNotEmpty())
                                    onPlayTrackInList(shuffled.first(), shuffled, "album")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite)
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Aléatoire")
                        }
                    } else if (onlineData != null && onlineData.tracks.isNotEmpty()) {
                        Button(
                            onClick = {
                                val firstSynced = onlineData.tracks.firstOrNull { syncedCloudTrackIds.contains(it.id) } ?: onlineData.tracks.first()
                                onPlayTrackInList(firstSynced.toTrackListRow(artistId = artistId, albumId = album?.summary?.id ?: onlineData.id), allOnlineAlbumMapped, "album_online")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Play")
                        }

                        Button(
                            onClick = {
                                if (!isAlbumBatchDownloading) {
                                    scope.launch {
                                        isAlbumBatchDownloading = true
                                        val total = onlineData.tracks.size
                                        snackbarHostState.showSnackbar("Ajout des $total pistes de l'album sur le Cloud...")
                                        val semaphore = kotlinx.coroutines.sync.Semaphore(2)
                                        kotlinx.coroutines.coroutineScope {
                                            onlineData.tracks.forEach { trk ->
                                                launch {
                                                    semaphore.withPermit {
                                                        downloadRepository.triggerDownload(
                                                            trackId = trk.id,
                                                            title = trk.title,
                                                            artistName = trk.displayArtistName,
                                                            albumTitle = trk.displayAlbumTitle,
                                                            coverUri = trk.coverUri,
                                                            userToken = com.aura.music.data.repository.SyncRepository.AUTH_TOKEN
                                                        ).collect { }
                                                    }
                                                }
                                            }
                                        }
                                        isAlbumBatchDownloading = false
                                        cloudFileRepository.refreshSyncedTrackIds()
                                        snackbarHostState.showSnackbar("Album ajouté à votre Cloud personnel !")
                                    }
                                }
                            },
                            enabled = !isAlbumBatchDownloading,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite)
                        ) {
                            if (isAlbumBatchDownloading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text("Télécharger l'album")
                            }
                        }
                    }
                }
            }

            // ---- TRACKLIST (local only) ----
            if (localTracks.isNotEmpty()) {
                val onUploadToCloudLambda = { track: TrackListRow ->
                    val isLocalScanned = track.contentUri?.startsWith("content://") == true
                    val isAlreadySynced = syncedCloudTrackIds.contains(track.id)
                    if (isLocalScanned && !isAlreadySynced) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                            cloudFileRepository.uploadTrack(track.id).collect { res ->
                                res.onSuccess {
                                    snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                                    cloudFileRepository.refreshSyncedTrackIds()
                                    viewModel.refreshLocal()
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                                }
                            }
                        }
                    }
                }
                val onDownloadFromCloudLambda = { track: TrackListRow ->
                    val isCloudOnly = track.contentUri.isNullOrBlank()
                    val isPresentInCloud = syncedCloudTrackIds.contains(track.id)
                    if (isCloudOnly && isPresentInCloud) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Téléchargement cloud lancé pour : ${track.title}")
                            cloudFileRepository.downloadTrack(
                                trackId = track.id,
                                title = track.title,
                                artistName = track.artistName,
                                albumTitle = track.albumTitle,
                                durationMs = track.durationMs,
                                artistId = track.artistId,
                                albumId = track.albumId,
                                coverUri = track.coverUri
                            ).collect { res ->
                                res.onSuccess {
                                    snackbarHostState.showSnackbar("Téléchargement cloud réussi : ${track.title}")
                                    appContainer.localLibraryRepository.refreshLocalMediaIndex()
                                    viewModel.refreshLocal()
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                                }
                            }
                        }
                    }
                }

                trackList(
                    title = "",
                    tracks = localTracks,
                    contextType = "album",
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = { },
                    showCover = false,
                    onPlayNow = { track -> onPlayTrackInList(track, localTracks, "album") },
                    onAddToQueue = onAddToQueue,
                    onAddTrackToPlaylist = { track -> activeTrackForPlaylist = track },
                    onLikeTrack = onLikeTrack,
                    onDeleteDownload = { track -> trackToDelete = track },
                    onUploadToCloud = onUploadToCloudLambda,
                    onDownloadFromCloud = onDownloadFromCloudLambda
                )
            } else if (onlineData != null && onlineData.tracks.isNotEmpty()) {
                itemsIndexed(onlineData.tracks, key = { _, track -> track.id }) { index, track ->
                    val normTitle = track.title.lowercase().trim()
                    val normArtist = track.displayArtistName.lowercase().trim()

                    val matchedLocal = allLibraryTracks.firstOrNull {
                        (it.id == track.id || (it.title.lowercase().trim() == normTitle && it.artistName.lowercase().trim() == normArtist)) &&
                        !it.contentUri.isNullOrBlank()
                    }
                    val isDownloaded = matchedLocal != null

                    val isSynced = syncedCloudTrackIds.contains(track.id) || cloudFiles.any {
                        it.trackId == track.id ||
                        ((it.title?.lowercase()?.trim() ?: "") == normTitle && (it.artistName?.lowercase()?.trim() ?: "") == normArtist)
                    } || allLibraryTracks.any {
                        it.id == track.id || (it.title.lowercase().trim() == normTitle && it.artistName.lowercase().trim() == normArtist)
                    }

                    val dlStatus = trackDownloadStatusMap[track.id] ?: TrackDownloadStatus.Idle
                    val trackRow = track.toTrackListRow(artistId = artistId, albumId = album?.summary?.id ?: onlineData.id)
                    val trackToPlay = matchedLocal ?: trackRow

                    InteractiveOnlineTrackRow(
                        track = track,
                        index = index + 1,
                        showCover = false,
                        isDownloaded = isDownloaded,
                        isSyncedToCloud = isSynced && !isDownloaded,
                        downloadStatus = dlStatus,
                        onPlay = {
                            if (isSynced || isDownloaded) {
                                onPlayTrackInList(trackToPlay, allOnlineAlbumMapped, "album_online")
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Ajout au Cloud en cours pour : ${track.title}")
                                    downloadRepository.triggerDownload(
                                        trackId = track.id,
                                        title = track.title,
                                        artistName = track.displayArtistName,
                                        albumTitle = track.displayAlbumTitle,
                                        coverUri = track.coverUri,
                                        userToken = com.aura.music.data.repository.SyncRepository.AUTH_TOKEN
                                    ).collect { }
                                }
                            }
                        },
                        onDownloadCloud = {
                            scope.launch {
                                snackbarHostState.showSnackbar("Ajout au Cloud lancé pour : ${track.title}")
                                downloadRepository.triggerDownload(
                                    trackId = track.id,
                                    title = track.title,
                                    artistName = track.displayArtistName,
                                    albumTitle = track.displayAlbumTitle,
                                    coverUri = track.coverUri,
                                    userToken = com.aura.music.data.repository.SyncRepository.AUTH_TOKEN
                                ).collect { }
                            }
                        },
                        onAddToQueue = { onAddToQueue(trackRow) },
                        onAddToPlaylist = { activeTrackForPlaylist = trackRow },
                        onLike = { onLikeTrack(trackRow) }
                    )
                }
            } else {
                item(key = "empty_tracks") {
                    EmptyStateSurface(
                        title = "Aucun titre disponible",
                        message = "Les pistes de cet album ne sont pas disponibles.",
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // ---- Enrichment error banner ----
            if (state.enrichmentError != null) {
                item(key = "enrichment_error") {
                    Text(
                        text = "⚠ ${state.enrichmentError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                onAddTrackToPlaylist(playlist, activeTrackForPlaylist!!)
                activeTrackForPlaylist = null
            }
        )
    }

    if (trackToDelete != null) {
        ConfirmDialog(
            title = "Supprimer de l'appareil ?",
            message = "Voulez-vous vraiment supprimer ce titre de votre appareil ? Cette action supprimera définitivement le fichier physique.",
            confirmLabel = "Supprimer",
            onDismiss = { trackToDelete = null },
            onConfirm = {
                onDeleteTrack(trackToDelete!!)
                trackToDelete = null
            }
        )
    }
}

// =============================================================================
// Sub-composables
// =============================================================================

@Composable
private fun ArtistHeroSection(
    name: String,
    pictureUri: String?,
    summary: String?,
    trackCount: Int,
    albumCount: Int,
    isEnrichmentLoading: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepBlack)
            .padding(top = 24.dp, bottom = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Artist picture (enriched) or placeholder
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (pictureUri != null) {
                    AsyncImage(
                        model = pictureUri,
                        contentDescription = "Photo de $name",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(
                                Color(0xFF232323),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
                // Enrichment spinner overlay
                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isEnrichmentLoading,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = BlazeOrange,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    "$trackCount piste(s) • $albumCount album(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistActionBar(
    hasLocalTracks: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    enrichmentBlocked: Boolean,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (enrichmentBlocked) {
            Icon(
                Icons.Rounded.Wifi,
                contentDescription = "Enrichissement désactivé",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
            )
        }
    }
}

@Composable
private fun AlbumHeroSection(
    title: String,
    artistId: String?,
    artistName: String,
    coverUri: String?,
    releaseDate: String?,
    trackCount: Int?,
    localTrackCount: Int,
    isEnrichmentLoading: Boolean,
    onOpenArtist: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Cover art
        Box(
            modifier = Modifier.size(280.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUri != null) {
                AsyncImage(
                    model = coverUri,
                    contentDescription = "Cover de $title",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(24.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF00E0FF), Color(0xFF101010))),
                            RoundedCornerShape(24.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
            // Enrichment spinner
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isEnrichmentLoading,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = BlazeOrange,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        // Metadata
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (artistId != null) {
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onOpenArtist(artistId) }
                        .padding(4.dp),
                )
            } else {
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Text(
                text = listOfNotNull(
                    trackCount?.let { "$it piste(s)" }
                        ?: if (localTrackCount > 0) "$localTrackCount piste(s) locale(s)" else null,
                    releaseDate,
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

/** Affichage simplifié d'un titre online (non local) — lecture non disponible */
@Composable
private fun OnlineAlbumRailSection(
    title: String,
    albums: List<AlbumSummary>,
    onOpenAlbum: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title, "Du plus recent au plus ancien.")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                SharedRailCard(
                    title = album.title,
                    subtitle = album.onlineAlbumMetadata(),
                    imageUri = album.coverUri,
                    gradientStartColor = Color(0xFFFF9E00),
                    imageShape = RoundedCornerShape(20.dp),
                    onClick = { onOpenAlbum(album.id) },
                )
            }
        }
    }
}

@Composable
fun InteractiveOnlineTrackRow(
    track: TrackSummary,
    index: Int? = null,
    showCover: Boolean = true,
    isDownloaded: Boolean = false,
    isSyncedToCloud: Boolean = false,
    downloadStatus: TrackDownloadStatus = TrackDownloadStatus.Idle,
    onPlay: () -> Unit,
    onDownloadCloud: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (index != null) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Center
            )
        }

        if (showCover) {
            val cover = track.coverUri
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                PlaceholderCover(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val durationStr = if (track.durationMs > 0) {
                val totalSec = track.durationMs / 1000
                String.format("%d:%02d", totalSec / 60, totalSec % 60)
            } else null
            val subtext = listOfNotNull(track.displayArtistName, durationStr).joinToString(" • ")
            Text(
                subtext,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Action / Status Icon
        when {
            downloadStatus is TrackDownloadStatus.Downloading -> {
                CircularProgressIndicator(
                    progress = { (downloadStatus as TrackDownloadStatus.Downloading).progressPercent },
                    modifier = Modifier.size(24.dp),
                    color = BlazeOrange,
                    strokeWidth = 2.dp
                )
            }
            downloadStatus is TrackDownloadStatus.Queued -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = BlazeOrange,
                    strokeWidth = 2.dp
                )
            }
            isDownloaded -> {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Sur l'appareil",
                    tint = Color(0xFF00E676),
                    modifier = Modifier.size(20.dp)
                )
            }
            isSyncedToCloud -> {
                // Épuré : déjà sur le Cloud
            }
            else -> {
                IconButton(
                    onClick = onDownloadCloud,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.CloudDownload,
                        contentDescription = "Ajouter au Cloud",
                        tint = BlazeOrange,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Options", tint = TextSecondary)
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Écouter") },
                    onClick = {
                        showMenu = false
                        onPlay()
                    },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Ajouter à la file d'attente") },
                    onClick = {
                        showMenu = false
                        onAddToQueue()
                    },
                    leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Ajouter à une playlist") },
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
                    },
                    leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Ajouter aux favoris") },
                    onClick = {
                        showMenu = false
                        onLike()
                    },
                    leadingIcon = { Icon(Icons.Rounded.Favorite, contentDescription = null) }
                )
                if (!isSyncedToCloud && !isDownloaded) {
                    DropdownMenuItem(
                        text = { Text("Ajouter au Cloud personnel") },
                        onClick = {
                            showMenu = false
                            onDownloadCloud()
                        },
                        leadingIcon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) }
                    )
                }
            }
        }
    }
}

/** Convertit un AlbumSummary online en AlbumBrowseRow pour réutiliser BrowseAlbumRail */
private fun com.aura.music.data.network.AlbumSummary.toBrowseRow(): AlbumBrowseRow = AlbumBrowseRow(
    id = id,
    title = title,
    artistId = null,
    artistName = primaryArtistName,
    coverUri = coverUri,
    trackCount = trackCount,
)

private fun AlbumSummary.onlineAlbumMetadata(): String =
    listOfNotNull(
        releaseDate?.take(4),
        trackCount?.let { "$it piste(s)" },
    ).joinToString(" | ").ifBlank { "En ligne" }

private fun AlbumSummary.isSingleRelease(): Boolean =
    when (releaseType?.lowercase()) {
        "single" -> true
        "album", "ep", "compilation" -> false
        else -> trackCount == 1
    }
