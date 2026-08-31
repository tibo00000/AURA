package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.ui.screens.SelectPlaylistDialog
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.PlaylistDetail
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import com.aura.music.ui.screens.PlaylistMosaicCover
import com.aura.music.ui.toQueuedTrack
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreenNew(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    playlistId: String,
    refreshToken: Int,
    onNavigateBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val detailState = produceState<PlaylistDetail?>(initialValue = null, repository, playlistId, refreshTick, refreshToken) {
        value = repository.getPlaylistDetail(playlistId)
    }
    val scope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenuOpen by remember { mutableStateOf(false) }
    var exportReport by remember { mutableStateOf<com.aura.music.data.playlist.ExportReport?>(null) }
    val detail = detailState.value
 
    var activeTrackForPlaylist by remember { mutableStateOf<PlaylistTrackRow?>(null) }
    var trackToEditMetadata by remember { mutableStateOf<com.aura.music.data.local.TrackListRow?>(null) }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshTick, refreshToken) {
        value = repository.getPlaylists()
    }
    val playlists = playlistsState.value

    val context = androidx.compose.ui.platform.LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val downloadJobs by appContainer.downloadRepository.getAllJobs().collectAsState(initial = emptyList())
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository, refreshTick, refreshToken) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value
    val snackbarHostState = remember { SnackbarHostState() }

    RouteScaffold(title = detail?.summary?.name ?: "Playlist", onNavigateBack = onNavigateBack, snackbarHostState = snackbarHostState) {
        if (detail == null) {
            EmptyStateSurface("Playlist introuvable", "Cette playlist n'existe plus localement.")
        } else {
            com.aura.music.ui.components.AuraLazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    // 1. Hero Cover (Mosaïque 2x2 ou Cover unique)
                    val previewCovers = remember(detail.tracks) {
                        detail.tracks.mapNotNull { it.coverUri }.filter { it.isNotBlank() }.distinct().take(4)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        PlaylistMosaicCover(
                            modifier = Modifier.size(220.dp),
                            coverUri = detail.summary.coverUri,
                            previewCovers = previewCovers,
                            shape = RoundedCornerShape(20.dp),
                            iconSize = 64.dp,
                        )
                    }

                    // 2. Metadata (Titre, Pistes, Durée totale)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            detail.summary.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                        val totalDurationMs = remember(detail.tracks) { detail.tracks.sumOf { it.durationMs ?: 0L } }
                        val durationMinutes = totalDurationMs / 60000L
                        val durationText = if (durationMinutes > 0) " • ${durationMinutes} min" else ""
                        Text(
                            "${detail.summary.itemCount} piste(s)$durationText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }

                item {
                    // 3. Action Bar (Boutons ronds Play & Shuffle à gauche, Switch & MoreVert à droite)
                    val notDownloadedTracks = remember(detail.tracks) {
                        detail.tracks.filter { it.contentUri.isNullOrBlank() }
                    }
                    val isAllDownloaded = remember(detail.tracks, notDownloadedTracks) {
                        detail.tracks.isNotEmpty() && notDownloadedTracks.isEmpty()
                    }
                    var isBatchDownloading by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Play & Shuffle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            // Grand bouton Play principal
                            IconButton(
                                onClick = {
                                    val tracks = detail.tracks.map { it.toTrackListRow() }
                                    if (tracks.isNotEmpty()) {
                                        playPlaylist(playerViewModel, tracks, false, detail.summary.id)
                                    }
                                },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(BlazeOrange, CircleShape),
                                enabled = detail.tracks.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Tout lire",
                                    tint = DeepBlack,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // Bouton Shuffle secondaire
                            IconButton(
                                onClick = {
                                    val tracks = detail.tracks.map { it.toTrackListRow() }.shuffled()
                                    if (tracks.isNotEmpty()) {
                                        playPlaylist(playerViewModel, tracks, true, detail.summary.id)
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(DarkGraphite, CircleShape),
                                enabled = detail.tracks.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = "Lecture aléatoire",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Switch Téléchargement & Menu 3-points
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(
                                checked = isAllDownloaded,
                                enabled = detail.tracks.isNotEmpty() && !isBatchDownloading,
                                thumbContent = if (isBatchDownloading) {
                                    {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.5.dp,
                                            color = BlazeOrange
                                        )
                                    }
                                } else if (isAllDownloaded) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.DownloadDone,
                                            contentDescription = "Disponible hors-ligne",
                                            tint = BlazeOrange,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                } else {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = "Télécharger la playlist",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                },
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (notDownloadedTracks.isNotEmpty()) {
                                            scope.launch {
                                                isBatchDownloading = true
                                                val total = notDownloadedTracks.size
                                                snackbarHostState.showSnackbar("Téléchargement de $total morceau(x) pour l'écoute hors-ligne...")
                                                val semaphore = kotlinx.coroutines.sync.Semaphore(2)
                                                kotlinx.coroutines.coroutineScope {
                                                    notDownloadedTracks.forEach { track ->
                                                        launch {
                                                            semaphore.withPermit {
                                                                cloudFileRepository.downloadTrack(
                                                                    trackId = track.trackId,
                                                                    title = track.title,
                                                                    artistName = track.artistName,
                                                                    albumTitle = track.albumTitle,
                                                                    durationMs = track.durationMs,
                                                                    artistId = track.artistId,
                                                                    albumId = track.albumId,
                                                                    coverUri = track.coverUri,
                                                                ).collect { }
                                                            }
                                                        }
                                                    }
                                                }
                                                isBatchDownloading = false
                                                refreshTick++
                                                snackbarHostState.showSnackbar("Playlist disponible hors-ligne !")
                                            }
                                        }
                                    } else {
                                        scope.launch {
                                            val downloadedTracks = detail.tracks.filter { !it.contentUri.isNullOrBlank() }
                                            downloadedTracks.forEach { track ->
                                                cloudFileRepository.removeLocalFile(track.trackId)
                                            }
                                            refreshTick++
                                            snackbarHostState.showSnackbar("Fichiers locaux supprimés. Titres toujours disponibles sur le Cloud.")
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = BlazeOrange,
                                    uncheckedThumbColor = DarkGraphite,
                                    uncheckedTrackColor = ElevatedGraphite,
                                    uncheckedBorderColor = Color.Transparent
                                )
                            )

                            Box {
                                IconButton(
                                    onClick = { showMenuOpen = true },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = "Options de la playlist",
                                        tint = TextSecondary
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMenuOpen,
                                    onDismissRequest = { showMenuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Renommer") },
                                        onClick = {
                                            showRenameDialog = true
                                            showMenuOpen = false
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Exporter (.m3u8)") },
                                        onClick = {
                                            showMenuOpen = false
                                            scope.launch {
                                                try {
                                                    val report = appContainer.playlistImportExportManager.exportPlaylistToM3U8(playlistId)
                                                    exportReport = report
                                                } catch (e: Exception) {
                                                    android.widget.Toast.makeText(context, "Erreur export : ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null, tint = BlazeOrange) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Supprimer la playlist", color = Color.Red) },
                                        onClick = {
                                            showDeleteDialog = true
                                            showMenuOpen = false
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color.Red) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (detail.tracks.isEmpty()) {
                    item {
                        EmptyStateSurface(
                            title = "Aucune piste pour l'instant",
                            message = "Accede a Recherche ou Bibliotheque pour ajouter des pistes a cette playlist.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    items(
                        items = detail.tracks,
                        key = { it.playlistItemId },
                        contentType = { "track_row" }
                    ) { track ->
                        val onPlayTrackClick = remember(track.trackId, detail.tracks) {
                            {
                                val tracks = detail.tracks.map { it.toTrackListRow() }
                                playPlaylist(playerViewModel, tracks, false, detail.summary.id, track.trackId)
                            }
                        }
                        val onRefreshClick = remember { { refreshTick++; Unit } }
                        val onAddToPlaylistClick = remember { { t: PlaylistTrackRow -> activeTrackForPlaylist = t } }
                        val onAddToQueueClick = remember(track.trackId) {
                            {
                                playerViewModel.onEvent(com.aura.music.domain.player.PlayerEvent.AddToQueue(track.toTrackListRow().toQueuedTrack()))
                            }
                        }
                        PlaylistTrackRowItem(
                            track = track,
                            playlistId = detail.summary.id,
                            repository = repository,
                            onPlayTrack = onPlayTrackClick,
                            onRefresh = onRefreshClick,
                            onAddToPlaylist = onAddToPlaylistClick,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                            onAddToQueue = onAddToQueueClick,
                            cloudFileRepository = cloudFileRepository,
                            syncedCloudTrackIds = syncedCloudTrackIds,
                            cloudFiles = cloudFiles,
                            downloadJobs = downloadJobs,
                            snackbarHostState = snackbarHostState,
                            onEditMetadata = { t -> trackToEditMetadata = t }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showRenameDialog && detail != null) {
        PlaylistNameDialog(
            title = "Renommer la playlist",
            confirmLabel = "Sauvegarder",
            initialValue = detail.summary.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                scope.launch {
                    repository.renamePlaylist(detail.summary.id, name)
                    refreshTick++
                }
                showRenameDialog = false
            },
        )
    }

    if (showDeleteDialog && detail != null) {
        ConfirmDialog(
            title = "Supprimer cette playlist ?",
            message = "Cette action est irréversible.",
            confirmLabel = "Supprimer",
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                scope.launch {
                    repository.deletePlaylist(detail.summary.id)
                }
                showDeleteDialog = false
                onNavigateBack()
            },
        )
    }

    if (exportReport != null) {
        PlaylistExportReportDialog(
            report = exportReport!!,
            onDismiss = { exportReport = null }
        )
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                scope.launch {
                    repository.addTrackToPlaylist(playlist.id, activeTrackForPlaylist!!.trackId, contextType = "playlist")
                    activeTrackForPlaylist = null
                    refreshTick++
                }
            }
        )
    }

    if (trackToEditMetadata != null) {
        EditTrackMetadataBottomSheet(
            track = trackToEditMetadata!!,
            apiService = appContainer.auraApiService,
            localLibraryRepository = repository,
            onDismiss = { trackToEditMetadata = null },
            onTrackUpdated = {
                trackToEditMetadata = null
                refreshTick++
            }
        )
    }
}

@Composable
private fun PlaylistTrackRowItem(
    track: PlaylistTrackRow,
    playlistId: String,
    repository: LocalLibraryRepository,
    onPlayTrack: () -> Unit,
    onRefresh: () -> Unit,
    onAddToPlaylist: (PlaylistTrackRow) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onAddToQueue: () -> Unit,
    cloudFileRepository: com.aura.music.data.repository.CloudFileRepository,
    syncedCloudTrackIds: Set<String>,
    cloudFiles: List<com.aura.music.data.network.SyncedFileResponseData> = emptyList(),
    downloadJobs: List<com.aura.music.data.local.DownloadJobEntity> = emptyList(),
    snackbarHostState: SnackbarHostState,
    onEditMetadata: ((com.aura.music.data.local.TrackListRow) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    val onRemoveClick = remember(track.playlistItemId, playlistId) {
        {
            scope.launch {
                repository.removeTrackFromPlaylist(playlistId, track.playlistItemId)
                onRefresh()
            }
            Unit
        }
    }
    val onAddToPlaylistClick = remember(track.trackId) { { onAddToPlaylist(track) } }
    val onLikeClick = remember(track.trackId, playlistId) {
        {
            scope.launch {
                repository.toggleLike(track.trackId, currentlyLiked = false, contextType = "playlist", contextId = playlistId)
                onRefresh()
            }
            Unit
        }
    }
    val onUnlikeClick = remember(track.trackId, playlistId) {
        {
            scope.launch {
                repository.toggleLike(track.trackId, currentlyLiked = true, contextType = "playlist", contextId = playlistId)
                onRefresh()
            }
            Unit
        }
    }
    val onViewArtistClick = remember(track.artistId) {
        track.artistId?.let { artistId -> { onOpenArtist(artistId) } }
    }
    val onViewAlbumClick = remember(track.albumId) {
        track.albumId?.let { albumId -> { onOpenAlbum(albumId) } }
    }

    val isLocalScanned = track.contentUri?.startsWith("content://") == true || track.contentUri?.startsWith("file://") == true || track.contentUri?.startsWith("/") == true
    val isPresentInCloud = syncedCloudTrackIds.contains(track.trackId) ||
        syncedCloudTrackIds.any { isDeezerTrackMatch(it, track.trackId) } ||
        cloudFiles.any { cloud ->
            isDeezerTrackMatch(cloud.trackId, track.trackId) ||
            (cloud.title?.trim().equals(track.title.trim(), ignoreCase = true) &&
             (cloud.artistName?.trim().equals(track.artistName?.trim(), ignoreCase = true) || track.artistName.isNullOrBlank() || cloud.artistName.isNullOrBlank()) &&
             (cloud.albumTitle?.trim().equals(track.albumTitle?.trim(), ignoreCase = true) || track.albumTitle.isNullOrBlank() || cloud.albumTitle.isNullOrBlank()))
        }
    val isAlreadySynced = isPresentInCloud
    val onUploadToCloudLambda = remember(track.trackId, isLocalScanned, isAlreadySynced) {
        if (isLocalScanned && !isAlreadySynced) {
            {
                scope.launch {
                    snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                    cloudFileRepository.uploadTrack(track.trackId).collect { res ->
                        res.onSuccess {
                            snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                            cloudFileRepository.refreshSyncedTrackIds()
                        }.onFailure { err ->
                            snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                        }
                    }
                }
                Unit
            }
        } else null
    }

    val isDownloadedLocally = !track.contentUri.isNullOrBlank()
    val isCloudOnly = !isDownloadedLocally
    val job = remember(downloadJobs, track.trackId) {
        downloadJobs.find { it.trackId == track.trackId }
    }

    val downloadStatus = when {
        job != null && job.status == "queued" -> TrackDownloadStatus.Queued
        job != null && job.status == "running" -> TrackDownloadStatus.Downloading(job.progressPercent ?: 0f)
        job != null && job.status == "failed" -> TrackDownloadStatus.Failed(job.errorMessage ?: "Échec du téléchargement")
        isDownloadedLocally -> TrackDownloadStatus.Downloaded
        else -> TrackDownloadStatus.NotDownloaded
    }

    val onTrackRowClick: () -> Unit = {
        if (downloadStatus is TrackDownloadStatus.Queued || downloadStatus is TrackDownloadStatus.Downloading) {
            scope.launch {
                snackbarHostState.showSnackbar("Téléchargement Cloud en cours pour : ${track.title}")
            }
        } else {
            onPlayTrack()
        }
    }

    val onDownloadFromCloudLambda = remember(track.trackId, isCloudOnly) {
        if (isCloudOnly) {
            {
                scope.launch {
                    snackbarHostState.showSnackbar("Téléchargement sur l'appareil lancé pour : ${track.title}")
                    cloudFileRepository.downloadTrack(
                        trackId = track.trackId,
                        title = track.title,
                        artistName = track.artistName,
                        albumTitle = track.albumTitle,
                        durationMs = track.durationMs,
                        artistId = track.artistId,
                        albumId = track.albumId,
                        coverUri = track.coverUri
                    ).collect { res ->
                        res.onSuccess {
                            snackbarHostState.showSnackbar("Téléchargement réussi : ${track.title}")
                            repository.refreshLocalMediaIndex()
                            onRefresh()
                        }.onFailure { err ->
                            snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                        }
                    }
                }
                Unit
            }
        } else null
    }

    SharedTrackRowItem(
        title = track.title,
        subtitle = track.artistName ?: "Artiste inconnu",
        onClick = onTrackRowClick,
        coverUri = track.coverUri,
        contextType = "playlist",
        isLiked = track.isLiked,
        downloadStatus = downloadStatus,
        isCloudOnly = isCloudOnly,
        onRemoveFromPlaylist = onRemoveClick,
        onAddToQueue = onAddToQueue,
        onAddToPlaylist = onAddToPlaylistClick,
        onLike = onLikeClick,
        onUnlike = onUnlikeClick,
        onViewArtist = onViewArtistClick,
        onViewAlbum = onViewAlbumClick,
        onUploadToCloud = onUploadToCloudLambda,
        onDownloadFromCloud = onDownloadFromCloudLambda,
        onEditMetadata = if (onEditMetadata != null) { { onEditMetadata(track.toTrackListRow()) } } else null
    )
}

private fun playPlaylist(
    playerViewModel: com.aura.music.ui.player.PlayerViewModel,
    tracks: List<com.aura.music.data.local.TrackListRow>,
    shuffle: Boolean,
    playlistId: String,
    startTrackId: String? = null,
) {
    if (tracks.isEmpty()) return
    val orderedTracks = if (shuffle) tracks.shuffled() else tracks
    val startIndex = startTrackId?.let { trackId ->
        orderedTracks.indexOfFirst { it.id == trackId }.takeIf { it >= 0 }
    } ?: 0
    
    playerViewModel.onEvent(
        com.aura.music.domain.player.PlayerEvent.PlayTrack(
            trackId = orderedTracks.getOrNull(startIndex)?.id ?: return,
            contextType = "playlist",
            contextId = playlistId,
            contextTracks = orderedTracks.map { it.toQueuedTrack() },
            startIndex = startIndex,
        ),
    )
}
