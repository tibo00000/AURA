package com.aura.music.ui.screens

import java.io.File
import com.aura.music.ui.utils.TrackLookupIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import kotlinx.coroutines.sync.withPermit
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import com.aura.music.ui.components.ShimmerTrackList
import com.aura.music.ui.components.ShimmerGrid
import androidx.compose.foundation.layout.PaddingValues
import com.aura.music.ui.theme.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.AlbumDetail
import com.aura.music.data.repository.ArtistDetail
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.PlaylistDetail
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.PlayerUiState
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.RepeatMode
import com.aura.music.ui.DashboardSummaryCard
import com.aura.music.ui.PlaylistPreviewList
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.trackList
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.toQueuedTrack
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest

@Composable
fun LibraryScreen(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    refreshToken: Int,
    onRequestAudioPermission: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null) }
    var trackToDelete by remember { mutableStateOf<TrackListRow?>(null) }
    var pendingDeleteTrackId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingDeleteTrackId?.let { trackId ->
                scope.launch {
                    repository.deleteTrack(trackId)
                    pendingDeleteTrackId = null
                    refreshTick++
                }
            }
        } else {
            pendingDeleteTrackId = null
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository, refreshToken, refreshTick) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value
    val snackbarHostState = remember { SnackbarHostState() }

    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshToken, refreshTick) {
        value = repository.getLibraryDashboardSummary()
    }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshToken, refreshTick) {
        value = repository.getPlaylists()
    }
    val artistsState = produceState(initialValue = emptyList<ArtistBrowseRow>(), repository, refreshToken, refreshTick) {
        value = repository.getBrowseArtists(8)
    }
    val favoritesCountState = produceState(initialValue = 0, repository, refreshToken, refreshTick) {
        value = repository.getLikedTracks().size
    }
    var query by remember { mutableStateOf("") }
    val searchResults = remember { mutableStateListOf<TrackListRow>() }
    val searchArtists = remember { mutableStateListOf<ArtistBrowseRow>() }
    val searchAlbums = remember { mutableStateListOf<AlbumBrowseRow>() }

    LaunchedEffect(query, repository, refreshToken, refreshTick) {
        searchResults.clear()
        searchArtists.clear()
        searchAlbums.clear()
        if (query.trim().length >= 2) {
            searchResults += repository.searchLocalTracks(query, limit = 24)
            searchArtists += repository.searchLocalArtists(query, limit = 8)
            searchAlbums += repository.searchLocalAlbums(query, limit = 8)
        }
    }

    val isSearchActive = query.trim().length >= 2
    val lookupIndex = remember(cloudFiles, syncedCloudTrackIds) {
        TrackLookupIndex.build(emptyList(), cloudFiles, syncedCloudTrackIds)
    }

    RouteScaffold(
        title = "Bibliothèque",
        snackbarHostState = snackbarHostState
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(DeepBlack),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text("Rechercher...", color = TextSecondary) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Effacer la recherche", tint = TextSecondary)
                            }
                        }
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ElevatedGraphite,
                        unfocusedContainerColor = ElevatedGraphite,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )
            }

            if (!isSearchActive) {
                item {
                    if (summaryState.value == null) {
                        ShimmerGrid(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            rowCount = 2,
                            colCount = 2,
                            cardHeight = 84.dp
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LibraryGridItem("Titres", "${summaryState.value?.roomTrackCount ?: 0} éléments", Icons.Rounded.MusicNote, onOpenTracks, Modifier.weight(1f))
                                LibraryGridItem("Favoris", "${favoritesCountState.value} éléments", Icons.Rounded.Favorite, onOpenFavorites, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LibraryGridItem("Artistes", "Parcourir", Icons.Rounded.Mic, onOpenArtists, Modifier.weight(1f))
                                LibraryGridItem("Playlists", "${playlistsState.value.size} éléments", Icons.Rounded.QueueMusic, onOpenPlaylists, Modifier.weight(1f))
                            }
                        }
                    }
                }
                
                if (playlistsState.value.isNotEmpty()) {
                    item { 
                        Text("Playlists récentes", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    item { PlaylistPreviewList(playlists = playlistsState.value.take(4), onOpenPlaylist = onOpenPlaylist) }
                }
                
                item { Spacer(modifier = Modifier.height(24.dp)) }

            } else {
                // Search State
                if (searchArtists.isNotEmpty()) {
                    item { Text("Artistes correspondants", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.padding(horizontal = 16.dp)) }
                    item { BrowseArtistRail(artists = searchArtists.toList(), onOpenArtist = onOpenArtist) } 
                }
                if (searchResults.isNotEmpty()) {
                    val onUploadToCloudLambda = { track: TrackListRow ->
                        val isLocalScanned = track.contentUri?.startsWith("content://") == true || track.contentUri?.startsWith("file://") == true || track.contentUri?.startsWith("/") == true
                        val isAlreadySynced = lookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)
                        if (isLocalScanned && !isAlreadySynced) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                                cloudFileRepository.uploadTrack(track.id).collect { res ->
                                    res.onSuccess {
                                        snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                                        cloudFileRepository.refreshSyncedTrackIds()
                                        refreshTick++
                                    }.onFailure { err ->
                                        snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                                    }
                                }
                            }
                        }
                    }
                    val onDownloadFromCloudLambda = { track: TrackListRow ->
                        val isCloudOnly = track.contentUri.isNullOrBlank()
                        val isPresentInCloud = lookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)
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
                                        repository.refreshLocalMediaIndex()
                                        refreshTick++
                                    }.onFailure { err ->
                                        snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                                    }
                                }
                            }
                        }
                    }

                    trackList(
                        title = "Titres correspondants",
                        tracks = searchResults.toList(),
                        contextType = "library_search",
                        onPlayTrackInList = onPlayTrackInList,
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                        onPlayNow = { track -> onPlayTrackInList(track, searchResults.toList(), "library_search") },
                        onAddToQueue = { track -> playerViewModel.onEvent(PlayerEvent.AddToQueue(track.toQueuedTrack())) },
                        onAddTrackToPlaylist = { track -> activeTrackForPlaylist = track },
                        onLikeTrack = { track ->
                            scope.launch {
                                repository.toggleLike(track.id, track.isLiked, "library_search")
                                refreshTick++
                            }
                        },
                        onDeleteDownload = { track -> trackToDelete = track },
                        onUploadToCloud = onUploadToCloudLambda,
                        onDownloadFromCloud = onDownloadFromCloudLambda
                    )
                }
                if (searchAlbums.isNotEmpty()) {
                    item { Text("Albums correspondants", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.padding(horizontal = 16.dp)) }
                    item { BrowseAlbumRail(albums = searchAlbums.toList(), onOpenAlbum = onOpenAlbum) }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlistsState.value,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                scope.launch {
                    repository.addTrackToPlaylist(playlist.id, activeTrackForPlaylist!!.id, contextType = "library_search")
                    activeTrackForPlaylist = null
                    refreshTick++
                }
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
                val trackId = trackToDelete!!.id
                pendingDeleteTrackId = trackId
                scope.launch {
                    val pendingIntent = repository.deleteTrack(trackId)
                    if (pendingIntent != null) {
                        try {
                            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            intentSenderLauncher.launch(intentSenderRequest)
                        } catch (e: Exception) {
                            android.util.Log.e("LibraryScreen", "Failed to launch intent sender for delete", e)
                            pendingDeleteTrackId = null
                        }
                    } else {
                        pendingDeleteTrackId = null
                        refreshTick++
                    }
                    trackToDelete = null
                }
            }
        )
    }
}

@Composable
fun FavoritesScreen(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    refreshToken: Int,
    onNavigateBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val tracksState = produceState<List<TrackListRow>?>(initialValue = null, repository, refreshTick, refreshToken) {
        value = repository.getLikedTracks()
    }
    val scope = rememberCoroutineScope()
    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null) }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshTick, refreshToken) {
        value = repository.getPlaylists()
    }
    val playlists = playlistsState.value

    val context = androidx.compose.ui.platform.LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository, refreshTick, refreshToken) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value
    val isOnline by appContainer.connectivityObserver.isOnline.collectAsState(initial = true)
    val snackbarHostState = remember { SnackbarHostState() }

    RouteScaffold(title = "Favoris", onNavigateBack = onNavigateBack, snackbarHostState = snackbarHostState) {
        val tracks = tracksState.value
        if (tracks == null) {
            ShimmerTrackList(count = 6)
        } else if (tracks.isEmpty()) {
            EmptyStateSurface(
                title = "Aucun favori",
                message = "Appuie sur le cœur dans le player pour retrouver tes pistes ici.",
            )
        } else {
            val contextTracks = remember(tracks) {
                tracks.map { it.toQueuedTrack() }
            }
            val notDownloadedTracks = remember(tracks) {
                tracks.filter { it.contentUri.isNullOrBlank() }
            }
            val isAllDownloaded = remember(tracks, notDownloadedTracks) {
                tracks.isNotEmpty() && notDownloadedTracks.isEmpty()
            }
            var isBatchDownloading by remember { mutableStateOf(false) }

            val lookupIndex = remember(tracks, cloudFiles, syncedCloudTrackIds) {
                TrackLookupIndex.build(tracks ?: emptyList(), cloudFiles, syncedCloudTrackIds)
            }

            com.aura.music.ui.components.AuraLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .background(
                                Brush.linearGradient(listOf(Color(0xFFFF6B00), Color(0xFF1A0A00))),
                                RoundedCornerShape(24.dp),
                            )
                            .padding(20.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = "Titres likés",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = "${tracks.size} titre(s) enregistré(s)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    if (tracks.isNotEmpty()) {
                                        playerViewModel.onEvent(
                                            PlayerEvent.PlayTrack(
                                                trackId = tracks.first().id,
                                                contextType = "favorites",
                                                contextId = "favorites",
                                                contextTracks = contextTracks,
                                                startIndex = 0,
                                            )
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                enabled = tracks.isNotEmpty(),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text("Play")
                            }
                            Button(
                                onClick = {
                                    val shuffled = tracks.shuffled()
                                    if (shuffled.isNotEmpty()) {
                                        val shuffledContext = shuffled.map { it.toQueuedTrack() }
                                        playerViewModel.onEvent(
                                            PlayerEvent.PlayTrack(
                                                trackId = shuffled.first().id,
                                                contextType = "favorites",
                                                contextId = "favorites",
                                                contextTracks = shuffledContext,
                                                startIndex = 0,
                                            )
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite),
                                enabled = tracks.isNotEmpty(),
                            ) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text("Aléatoire")
                            }
                        }

                        // Bouton d'action globale Cloud / Local
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (isAllDownloaded) "Hors-ligne" else "Télécharger tout",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAllDownloaded) Color(0xFF00E676) else TextSecondary
                            )
                            Switch(
                                checked = isAllDownloaded,
                                enabled = !isBatchDownloading && isOnline,
                                onCheckedChange = { shouldDownloadAll ->
                                    if (shouldDownloadAll) {
                                        scope.launch {
                                            isBatchDownloading = true
                                            val toDownload = tracks.filter { it.contentUri.isNullOrBlank() }
                                            snackbarHostState.showSnackbar("Téléchargement de ${toDownload.size} pistes favoris...")
                                            val semaphore = kotlinx.coroutines.sync.Semaphore(2)
                                            kotlinx.coroutines.coroutineScope {
                                                toDownload.forEach { track ->
                                                    launch {
                                                        semaphore.withPermit {
                                                            cloudFileRepository.downloadTrack(
                                                                trackId = track.id,
                                                                title = track.title,
                                                                artistName = track.artistName,
                                                                albumTitle = track.albumTitle,
                                                                durationMs = track.durationMs,
                                                                artistId = track.artistId,
                                                                albumId = track.albumId,
                                                                coverUri = track.coverUri
                                                            ).collect { }
                                                        }
                                                    }
                                                }
                                            }
                                            isBatchDownloading = false
                                            repository.refreshLocalMediaIndex()
                                            refreshTick++
                                            snackbarHostState.showSnackbar("Favoris synchronisés sur l'appareil !")
                                        }
                                    } else {
                                        scope.launch {
                                            val downloadedTracks = tracks.filter { !it.contentUri.isNullOrBlank() }
                                            downloadedTracks.forEach { track ->
                                                cloudFileRepository.removeLocalFile(track.id)
                                            }
                                            refreshTick++
                                            snackbarHostState.showSnackbar("Fichiers locaux supprimés. Favoris toujours disponibles sur le Cloud.")
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = BlazeOrange,
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = DarkGraphite
                                )
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = tracks,
                    key = { _, track -> track.id },
                    contentType = { _, _ -> "track_row" }
                ) { index, track ->
                    val playEvent = remember(track.id, index, contextTracks) {
                        PlayerEvent.PlayTrack(
                            trackId = track.id,
                            contextType = "favorites",
                            contextId = "favorites",
                            contextTracks = contextTracks,
                            startIndex = index,
                        )
                    }
                    val onPlayClick = remember(playEvent) { { playerViewModel.onEvent(playEvent) } }
                    val onAddToQueueClick = remember(track.id) { { playerViewModel.onEvent(PlayerEvent.AddToQueue(track.toQueuedTrack())) } }
                    val onUnlikeClick = remember(track.id) {
                        {
                            scope.launch {
                                repository.toggleLike(track.id, currentlyLiked = true, contextType = "favorites")
                                refreshTick++
                            }
                            Unit
                        }
                    }
                    val onAddToPlaylistClick = remember(track.id) { { activeTrackForPlaylist = track } }
                    val onViewArtistClick = remember(track.artistId) {
                        track.artistId?.let { artistId -> { onOpenArtist(artistId) } }
                    }
                    val onViewAlbumClick = remember(track.albumId) {
                        track.albumId?.let { albumId -> { onOpenAlbum(albumId) } }
                    }

                    val isLocalScanned = track.contentUri?.startsWith("content://") == true || track.contentUri?.startsWith("file://") == true || track.contentUri?.startsWith("/") == true
                    val isPresentInCloud = lookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)
                    val isAlreadySynced = isPresentInCloud
                    val onUploadToCloudLambda = remember(track.id, isLocalScanned, isAlreadySynced) {
                        if (isLocalScanned && !isAlreadySynced) {
                            {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                                    cloudFileRepository.uploadTrack(track.id).collect { res ->
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

                    val isCloudOnly = track.contentUri.isNullOrBlank()
                    val onDownloadFromCloudLambda = remember(track.id, isCloudOnly, isPresentInCloud) {
                        if (isCloudOnly && isPresentInCloud) {
                            {
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
                                            repository.refreshLocalMediaIndex()
                                            refreshTick++
                                        }.onFailure { err ->
                                            snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                                        }
                                    }
                                }
                                Unit
                            }
                        } else null
                    }

                    val isDownloadedLocally = !track.contentUri.isNullOrBlank()
                    val isCloudOnlyTrack = isCloudOnly && isPresentInCloud
                    val isOfflineBlocked = isCloudOnlyTrack && !isOnline

                    SharedTrackRowItem(
                        title = track.title,
                        subtitle = listOfNotNull(track.artistName, track.albumTitle).joinToString(" • "),
                        onClick = onPlayClick,
                        coverUri = track.coverUri,
                        contextType = "favorites",
                        isLiked = true,
                        downloadStatus = if (isDownloadedLocally) com.aura.music.ui.screens.TrackDownloadStatus.Downloaded else com.aura.music.ui.screens.TrackDownloadStatus.NotDownloaded,
                        isCloudOnly = isCloudOnlyTrack,
                        isOfflineDisabled = isOfflineBlocked,
                        onOfflineBlocked = {
                            scope.launch {
                                snackbarHostState.showSnackbar("Ce titre est sur le Cloud. Connexion Internet requise pour le streamer.")
                            }
                        },
                        onAddToQueue = onAddToQueueClick,
                        onUnlike = onUnlikeClick,
                        onAddToPlaylist = onAddToPlaylistClick,
                        onViewArtist = onViewArtistClick,
                        onViewAlbum = onViewAlbumClick,
                        onUploadToCloud = onUploadToCloudLambda,
                        onDownloadFromCloud = onDownloadFromCloudLambda,
                    )
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                scope.launch {
                    repository.addTrackToPlaylist(playlist.id, activeTrackForPlaylist!!.id, contextType = "favorites")
                    activeTrackForPlaylist = null
                }
            }
        )
    }
}

@Composable
fun LibraryGridItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = DarkGraphite)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(28.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}

@Composable
fun PlaylistsScreen(
    repository: LocalLibraryRepository,
    onNavigateBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val playlistsState = produceState<List<PlaylistListRow>?>(initialValue = null, repository, refreshTick) {
        value = repository.getPlaylists()
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    RouteScaffold(title="Playlists", onNavigateBack = onNavigateBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Construis tes contextes d'ecoute", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Les playlists locales pilotent la lecture, la reprise et bientot la sync cloud.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text("Créer une playlist", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            val playlists = playlistsState.value
            if (playlists == null) {
                ShimmerTrackList(count = 4)
            } else {
                PlaylistPreviewList(playlists = playlists, onOpenPlaylist = onOpenPlaylist)
            }
        }
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = "Créer une playlist",
            confirmLabel = "Créer",
            initialValue = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                scope.launch {
                    repository.createPlaylist(name)
                    refreshTick++
                }
                showCreateDialog = false
            },
        )
    }
}

@Composable
fun PlaylistDetailScreen(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    playlistId: String,
    onNavigateBack: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val detailState = produceState<PlaylistDetail?>(initialValue = null, repository, playlistId, refreshTick) {
        value = repository.getPlaylistDetail(playlistId)
    }
    val candidateTracksState = produceState(initialValue = emptyList<TrackListRow>(), repository, playlistId, refreshTick) {
        value = repository.getPlaylistCandidateTracks()
    }
    val scope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddTrackDialog by remember { mutableStateOf(false) }
    val detail = detailState.value

    RouteScaffold(title = detail?.summary?.name ?: "Playlist", onNavigateBack = onNavigateBack) {
        if (detail == null) {
            EmptyStateSurface("Playlist introuvable", "Cette playlist n'existe plus localement.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .background(Brush.linearGradient(listOf(Color(0xFFFF9E00), Color(0xFF101010))))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(detail.summary.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = "${detail.summary.itemCount} piste(s) | contexte playlist local",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                playPlaylist(playerViewModel, detail.tracks.map { it.toTrackListRow() }, false, detail.summary.id)
                            },
                            enabled = detail.tracks.isNotEmpty(),
                        ) { Text("Jouer") }
                        Button(
                            onClick = {
                                playPlaylist(playerViewModel, detail.tracks.map { it.toTrackListRow() }, true, detail.summary.id)
                            },
                            enabled = detail.tracks.isNotEmpty(),
                        ) { Text("Aléatoire") }
                        Button(onClick = { showAddTrackDialog = true }) { Text("Ajouter une piste") }
                    }
                }
                item {
                    Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                            Text("Renommer", modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = null)
                            Text("Supprimer", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (detail.tracks.isEmpty()) {
                    item {
                        EmptyStateSurface(
                            title = "Playlist vide",
                            message = "Ajoute un titre local depuis toute ta bibliotheque pour rendre ce contexte jouable.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    items(detail.tracks, key = { it.playlistItemId }) { track ->
                        PlaylistTrackItem(
                            track = track,
                            canMoveUp = track.position > 0,
                            canMoveDown = track.position < detail.tracks.lastIndex,
                            onPlay = {
                                playPlaylist(
                                    playerViewModel = playerViewModel,
                                    tracks = detail.tracks.map { row -> row.toTrackListRow() },
                                    shuffle = false,
                                    playlistId = detail.summary.id,
                                    startTrackId = track.trackId,
                                )
                            },
                            onMoveUp = {
                                scope.launch {
                                    repository.movePlaylistItem(detail.summary.id, track.playlistItemId, -1)
                                    refreshTick++
                                }
                            },
                            onMoveDown = {
                                scope.launch {
                                    repository.movePlaylistItem(detail.summary.id, track.playlistItemId, 1)
                                    refreshTick++
                                }
                            },
                            onRemove = {
                                scope.launch {
                                    repository.removeTrackFromPlaylist(detail.summary.id, track.playlistItemId)
                                    refreshTick++
                                }
                            },
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
            title = "Supprimer la playlist",
            message = "Supprimer ${detail.summary.name}? Cela supprime la playlist et son ordre local uniquement.",
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

    if (showAddTrackDialog && detail != null) {
        AddTrackToPlaylistDialog(
            tracks = candidateTracksState.value,
            onDismiss = { showAddTrackDialog = false },
            onSelectTrack = { track ->
                scope.launch {
                    repository.addTrackToPlaylist(detail.summary.id, track.id)
                    refreshTick++
                }
                showAddTrackDialog = false
            },
        )
    }
}
@Composable
fun ArtistRouteScreen(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    artistId: String,
    onNavigateBack: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val artistState = produceState<ArtistDetail?>(initialValue = null, repository, artistId, refreshTick) {
        value = repository.getArtistDetail(artistId)
    }
    val artist = artistState.value
    val scope = rememberCoroutineScope()
    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null) }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshTick) {
        value = repository.getPlaylists()
    }
    val playlists = playlistsState.value

    val context = androidx.compose.ui.platform.LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository, refreshTick) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value
    val snackbarHostState = remember { SnackbarHostState() }

    RouteScaffold(
        title = artist?.summary?.name ?: "Artist",
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState
    ) {
        if (artist == null) {
            EmptyStateSurface(
                title = "Artiste introuvable",
                message = "Cette surface attend soit un artiste local, soit un enrichissement online plus tard.",
            )
            return@RouteScaffold
        }

        val lookupIndex = remember(artist.topTracks, cloudFiles, syncedCloudTrackIds) {
            TrackLookupIndex.build(artist.topTracks, cloudFiles, syncedCloudTrackIds)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                HeroIdentityCard(
                    title = artist.summary.name,
                    subtitle = "${artist.topTracks.size} top track(s) | ${artist.albums.size} album(s)",
                    gradient = Brush.linearGradient(listOf(Color(0xFF792BEE), Color(0xFF101010))),
                )
            }
            item {
                Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val tracks = artist.topTracks
                            if (tracks.isNotEmpty()) onPlayTrackInList(tracks.first(), tracks, "artist")
                        },
                        enabled = artist.topTracks.isNotEmpty(),
                    ) { Text("Play") }
                    Button(
                        onClick = {
                            val tracks = artist.topTracks.shuffled()
                            if (tracks.isNotEmpty()) onPlayTrackInList(tracks.first(), tracks, "artist")
                        },
                        enabled = artist.topTracks.isNotEmpty(),
                    ) { Text("Mix local") }
                }
            }
            val onUploadToCloudLambda = { track: TrackListRow ->
                val isLocalScanned = track.contentUri?.startsWith("content://") == true || track.contentUri?.startsWith("file://") == true || track.contentUri?.startsWith("/") == true
                val isAlreadySynced = lookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)
                if (isLocalScanned && !isAlreadySynced) {
                    scope.launch {
                        snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                        cloudFileRepository.uploadTrack(track.id).collect { res ->
                            res.onSuccess {
                                snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                                cloudFileRepository.refreshSyncedTrackIds()
                                refreshTick++
                            }.onFailure { err ->
                                snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                            }
                        }
                    }
                }
            }
            val onDownloadFromCloudLambda = { track: TrackListRow ->
                val isCloudOnly = track.contentUri.isNullOrBlank()
                val isPresentInCloud = lookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)
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
                                repository.refreshLocalMediaIndex()
                                refreshTick++
                            }.onFailure { err ->
                                snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                            }
                        }
                    }
                }
            }

            trackList(
                title = "Titres populaires",
                tracks = artist.topTracks,
                contextType = "artist",
                onPlayTrackInList = onPlayTrackInList,
                onOpenArtist = { },
                onOpenAlbum = onOpenAlbum,
                onPlayNow = { track -> onPlayTrackInList(track, artist.topTracks, "artist") },
                onAddToQueue = { track -> playerViewModel.onEvent(PlayerEvent.AddToQueue(track.toQueuedTrack())) },
                onAddTrackToPlaylist = { track -> activeTrackForPlaylist = track },
                onLikeTrack = { track ->
                    scope.launch {
                        repository.toggleLike(track.id, track.isLiked, "artist", artistId)
                        refreshTick++
                    }
                },
                onDeleteDownload = { track ->
                    scope.launch {
                        repository.deleteTrack(track.id)
                        refreshTick++
                    }
                },
                onUploadToCloud = onUploadToCloudLambda,
                onDownloadFromCloud = onDownloadFromCloudLambda
            )
            item { SectionTitle("Albums", "Navigation album depuis la bibliotheque locale.") }
            item { BrowseAlbumRail(albums = artist.albums, onOpenAlbum = onOpenAlbum) }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                scope.launch {
                    repository.addTrackToPlaylist(playlist.id, activeTrackForPlaylist!!.id, contextType = "artist")
                    activeTrackForPlaylist = null
                    refreshTick++
                }
            }
        )
    }
}

@Composable
fun AlbumRouteScreen(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    albumId: String,
    onNavigateBack: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val albumState = produceState<AlbumDetail?>(initialValue = null, repository, albumId, refreshTick) {
        value = repository.getAlbumDetail(albumId)
    }
    val album = albumState.value
    val scope = rememberCoroutineScope()
    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null) }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshTick) {
        value = repository.getPlaylists()
    }
    val playlists = playlistsState.value

    val context = androidx.compose.ui.platform.LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository, refreshTick) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value
    val snackbarHostState = remember { SnackbarHostState() }

    RouteScaffold(
        title = album?.summary?.title ?: "Album",
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState
    ) {
        if (album == null) {
            EmptyStateSurface(
                title = "Album introuvable",
                message = "Cette surface recevra plus tard aussi les enrichissements online quand Search sera branche.",
            )
            return@RouteScaffold
        }

        val lookupIndex = remember(album.tracks, cloudFiles, syncedCloudTrackIds) {
            TrackLookupIndex.build(album.tracks, cloudFiles, syncedCloudTrackIds)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                // Hero Cover
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (album.summary.coverUri != null) {
                        AsyncImage(
                            model = album.summary.coverUri,
                            contentDescription = "Cover for ${album.summary.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(280.dp)
                                .clip(RoundedCornerShape(24.dp))
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
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White.copy(alpha = 0.5f))
                        }
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = album.summary.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        // Nom de l'artiste cliquable
                        val artistId = album.summary.artistId
                        val artistName = album.summary.artistName
                        if (artistId != null && artistName != null) {
                            Text(
                                text = artistName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onOpenArtist(artistId) }
                                    .padding(4.dp)
                            )
                        } else {
                            Text(
                                text = artistName ?: "Unknown Artist",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = listOfNotNull(
                                album.summary.trackCount?.let { "$it piste(s)" },
                                album.summary.releaseDate,
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val tracks = album.tracks
                            if (tracks.isNotEmpty()) onPlayTrackInList(tracks.first(), tracks, "album")
                        },
                        enabled = album.tracks.isNotEmpty(),
                    ) { Text("Play") }
                    Button(
                        onClick = {
                            val tracks = album.tracks.shuffled()
                            if (tracks.isNotEmpty()) onPlayTrackInList(tracks.first(), tracks, "album")
                        },
                        enabled = album.tracks.isNotEmpty(),
                    ) { Text("Shuffle") }
                }
            }
            val onUploadToCloudLambda = { track: TrackListRow ->
                val isLocalScanned = track.contentUri?.startsWith("content://") == true || track.contentUri?.startsWith("file://") == true || track.contentUri?.startsWith("/") == true
                val isAlreadySynced = lookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)
                if (isLocalScanned && !isAlreadySynced) {
                    scope.launch {
                        snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                        cloudFileRepository.uploadTrack(track.id).collect { res ->
                            res.onSuccess {
                                snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                                cloudFileRepository.refreshSyncedTrackIds()
                                refreshTick++
                            }.onFailure { err ->
                                snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                            }
                        }
                    }
                }
            }
            val onDownloadFromCloudLambda = { track: TrackListRow ->
                val isCloudOnly = track.contentUri.isNullOrBlank()
                val isPresentInCloud = lookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)
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
                                repository.refreshLocalMediaIndex()
                                refreshTick++
                            }.onFailure { err ->
                                snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                            }
                        }
                    }
                }
            }

            trackList(
                title = "",
                tracks = album.tracks,
                contextType = "album",
                onPlayTrackInList = onPlayTrackInList,
                onOpenArtist = onOpenArtist,
                onOpenAlbum = { },
                showCover = false,
                onPlayNow = { track -> onPlayTrackInList(track, album.tracks, "album") },
                onAddToQueue = { track -> playerViewModel.onEvent(PlayerEvent.AddToQueue(track.toQueuedTrack())) },
                onAddTrackToPlaylist = { track -> activeTrackForPlaylist = track },
                onLikeTrack = { track ->
                    scope.launch {
                        repository.toggleLike(track.id, track.isLiked, "album", albumId)
                        refreshTick++
                    }
                },
                onDeleteDownload = { track ->
                    scope.launch {
                        repository.deleteTrack(track.id)
                        refreshTick++
                    }
                },
                onUploadToCloud = onUploadToCloudLambda,
                onDownloadFromCloud = onDownloadFromCloudLambda
            )
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                scope.launch {
                    repository.addTrackToPlaylist(playlist.id, activeTrackForPlaylist!!.id, contextType = "album")
                    activeTrackForPlaylist = null
                    refreshTick++
                }
            }
        )
    }
}

@Composable
fun DownloadsScreen(
    viewModel: com.aura.music.ui.downloads.DownloadsViewModel,
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val selectedErrorJob by viewModel.selectedErrorJob.collectAsState()
    var activeResolveJobId by remember { mutableStateOf<String?>(null) }

    val filterLabels = listOf(
        "En cours (${uiState.queuedCount})",
        "Terminés (${uiState.succeededCount})",
        "Erreurs (${uiState.failedCount})"
    )
    val filterMapping = mapOf(
        "En cours (${uiState.queuedCount})" to "En cours",
        "Terminés (${uiState.succeededCount})" to "Terminés",
        "Erreurs (${uiState.failedCount})" to "Erreurs"
    )
    val activeLabel = filterLabels.firstOrNull { it.startsWith(uiState.selectedTab) } ?: filterLabels.first()

    RouteScaffold(
        title = "Téléchargements",
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = { viewModel.forceRefresh() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Rafraîchir", tint = TextPrimary)
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.errorMessage != null) {
                // Inline simple error banner
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF4A2A2A))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Erreur de synchronisation",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                uiState.errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        IconButton(onClick = { viewModel.dismissError() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Fermer", tint = Color.White)
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    HeroIdentityCard(
                        title = "Downloads",
                        subtitle = "Gère et écoute tes pistes hors-ligne en temps réel.",
                        gradient = Brush.linearGradient(listOf(Color(0xFFFF6B00), Color(0xFF101010))),
                    )
                }

                item {
                    FilterRow(
                        values = filterLabels,
                        selected = activeLabel,
                        onSelect = { label ->
                            val mappedTab = filterMapping[label] ?: "En cours"
                            viewModel.selectTab(mappedTab)
                        }
                    )
                }

                if (uiState.jobs.isEmpty()) {
                    item {
                        when (uiState.selectedTab) {
                            "En attente", "En cours" -> DownloadStateCard(
                                Icons.Rounded.Sync,
                                "Pas de progression active",
                                "Les barres de progression s'activent lorsque le téléchargement démarre."
                            )
                            "Terminés" -> DownloadStateCard(
                                Icons.Rounded.DownloadDone,
                                "Aucun download finalisé",
                                "Quand un titre sera disponible localement, tu pourras l'ouvrir ou le lire depuis ici."
                            )
                            else -> DownloadStateCard(
                                Icons.Rounded.ErrorOutline,
                                "Pas d'erreur de job",
                                "Les détails d'erreur et les boutons Retry seront branchés en cas d'échec."
                            )
                        }
                    }
                } else {
                    items(uiState.jobs, key = { it.jobId }) { job ->
                        DownloadJobRow(
                            job = job,
                            onRetry = { viewModel.retryDownload(job.jobId) },
                            onResolve = {
                                viewModel.loadCandidatesForJob(job.jobId)
                                activeResolveJobId = job.jobId
                            },
                            onInspectError = {
                                viewModel.inspectError(job)
                            },
                            onPlay = {
                                val downloadsDir = File(context.filesDir, "downloads")
                                val targetFile = File(downloadsDir, "${job.trackId.replace(':', ';')}.mp3")
                                val resolvedUri = if (targetFile.exists() && targetFile.length() > 0L) {
                                    android.net.Uri.fromFile(targetFile).toString()
                                } else {
                                    null
                                }

                                val trackRow = TrackListRow(
                                    id = job.trackId,
                                    artistId = null,
                                    albumId = null,
                                    title = job.title,
                                    artistName = job.artistName,
                                    albumTitle = null,
                                    contentUri = resolvedUri,
                                    durationMs = null,
                                    coverUri = job.coverUri,
                                    isLiked = false
                                )
                                playerViewModel.onEvent(
                                    PlayerEvent.PlayTrack(
                                        trackId = job.trackId,
                                        contextType = "downloads",
                                        contextId = "downloads",
                                        contextTracks = listOf(trackRow.toQueuedTrack()),
                                        startIndex = 0
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (activeResolveJobId != null) {
        YtmProposalsDialog(
            jobId = activeResolveJobId!!,
            viewModel = viewModel,
            onDismiss = { activeResolveJobId = null }
        )
    }

    if (selectedErrorJob != null) {
        DownloadErrorDetailDialog(
            job = selectedErrorJob!!,
            onDismiss = { viewModel.inspectError(null) },
            onRetry = {
                viewModel.retryDownload(selectedErrorJob!!.jobId)
                viewModel.inspectError(null)
            }
        )
    }
}

@Composable
fun DownloadErrorDetailDialog(
    job: com.aura.music.data.local.DownloadJobRowModel,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var showTechnicalDetails by remember { mutableStateOf(false) }

    val isCookieError = (job.errorCode?.contains("bot", ignoreCase = true) == true) ||
            (job.errorMessage?.contains("cookie", ignoreCase = true) == true) ||
            (job.errorMessage?.contains("anti-robot", ignoreCase = true) == true)

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1E1E1E)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with Error Icon & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Échec du téléchargement",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${job.title} • ${job.artistName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // User-friendly Error Explanation Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2020))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Diagnostic :",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = BlazeOrange
                        )
                        Text(
                            text = job.errorMessage ?: "Une erreur inattendue est survenue pendant le traitement.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }

                // Collapsible Technical Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTechnicalDetails = !showTechnicalDetails }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showTechnicalDetails) "Masquer les détails techniques" else "Afficher les détails techniques",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Icon(
                            imageVector = if (showTechnicalDetails) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (showTechnicalDetails) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = OffBlack)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Code erreur : ${job.errorCode ?: "UNKNOWN"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "Job ID : ${job.jobId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "Track ID : ${job.trackId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }

                // Copy report button
                OutlinedButton(
                    onClick = {
                        val report = buildString {
                            appendLine("--- Rapport d'erreur de téléchargement AURA ---")
                            appendLine("Piste : ${job.title} - ${job.artistName}")
                            appendLine("Job ID : ${job.jobId}")
                            appendLine("Track ID : ${job.trackId}")
                            appendLine("Code : ${job.errorCode ?: "UNKNOWN"}")
                            appendLine("Message : ${job.errorMessage ?: "Inconnu"}")
                            appendLine("Date : ${java.util.Date(job.createdAt)}")
                        }
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(report))
                        android.widget.Toast.makeText(context, "Rapport d'erreur copié !", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copier le rapport d'erreur")
                }

                // Action buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Fermer", color = TextSecondary)
                    }

                    if (isCookieError && onOpenSettings != null) {
                        Button(
                            onClick = {
                                onDismiss()
                                onOpenSettings()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange, contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Text("Paramètres", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                onDismiss()
                                onRetry()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange, contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Text("Réessayer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadJobRow(
    job: com.aura.music.data.local.DownloadJobRowModel,
    onRetry: () -> Unit,
    onResolve: () -> Unit,
    onInspectError: () -> Unit,
    onPlay: () -> Unit
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        onClick = {
            if (job.status == "failed" || job.status == "cancelled") {
                onInspectError()
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFF1E1E1E))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Cover Artwork or Placeholder
            if (job.coverUri != null) {
                AsyncImage(
                    model = job.coverUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                PlaceholderCover(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            }

            // 2. Info Block
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = TextPrimary
                )
                Text(
                    text = job.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Status indicators
                when (job.status) {
                    "running" -> {
                        val progress = job.progressPercent ?: 0f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = progress / 100f,
                                color = BlazeOrange,
                                trackColor = DarkGraphite,
                                modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                            )
                            Text(
                                text = "${progress.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = BlazeOrange,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    "queued" -> {
                        Text(
                            text = "Dans la file d'attente...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    "requires_resolution" -> {
                        Text(
                            text = "Choix de version requis",
                            style = MaterialTheme.typography.bodySmall,
                            color = BlazeOrange,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    "failed" -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = "Erreur : ${job.errorMessage ?: "Inconnue"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Red,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "Détails de l'erreur",
                                tint = Color.Red.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    "succeeded" -> {
                        Text(
                            text = "Téléchargé avec succès",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Green,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // 3. Trailing Actions
            when (job.status) {
                "failed", "cancelled" -> {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Réessayer",
                            tint = BlazeOrange
                        )
                    }
                }
                "requires_resolution" -> {
                    androidx.compose.material3.Button(
                        onClick = onResolve,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = BlazeOrange,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = "Choisir",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                "succeeded" -> {
                    IconButton(onClick = onPlay) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Lire",
                            tint = Color.Green
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun PlaylistTrackItem(
    track: PlaylistTrackRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(track.artistName, track.albumTitle).joinToString(" | "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire ${track.title}")
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Monter dans la playlist") }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Descendre dans la playlist") }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, contentDescription = "Retirer de la playlist") }
            }
        },
        modifier = Modifier.clickable(onClick = onPlay),
    )
}

@Composable
fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist name") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.trim().isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddTrackToPlaylistDialog(
    tracks: List<TrackListRow>,
    onDismiss: () -> Unit,
    onSelectTrack: (TrackListRow) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visibleTracks = remember(tracks, query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) tracks else tracks.filter {
            it.title.contains(trimmed, true) || it.artistName.contains(trimmed, true) || (it.albumTitle?.contains(trimmed, true) == true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a local track") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Toute la bibliotheque locale indexee est disponible ici.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Filter tracks") }, singleLine = true)
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(visibleTracks, key = { it.id }) { track ->
                        ListItem(
                            headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(listOfNotNull(track.artistName, track.albumTitle).joinToString(" | "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.clickable { onSelectTrack(track) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun CompactActionCard(
    label: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}






private fun playPlaylist(
    playerViewModel: PlayerViewModel,
    tracks: List<TrackListRow>,
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
        PlayerEvent.PlayTrack(
            trackId = orderedTracks[startIndex].id,
            contextType = "playlist",
            contextId = playlistId,
            contextTracks = orderedTracks.map { it.toQueuedTrack() },
            startIndex = startIndex,
        ),
    )
}

fun PlaylistTrackRow.toTrackListRow(): TrackListRow = TrackListRow(
    id = trackId,
    artistId = null,
    albumId = null,
    title = title,
    artistName = artistName,
    albumTitle = albumTitle,
    contentUri = contentUri,
    durationMs = durationMs,
    coverUri = coverUri,
    isLiked = isLiked,
)

@Composable
fun YtmProposalsDialog(
    jobId: String,
    viewModel: com.aura.music.ui.downloads.DownloadsViewModel,
    onDismiss: () -> Unit
) {
    val candidatesMap by viewModel.candidates.collectAsState()
    val candidates = candidatesMap[jobId]

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Text(
                    text = "Choisir la version",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Plusieurs correspondances ont été trouvées. Veuillez sélectionner la version correcte à télécharger.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                if (candidates == null) {
                    // Loading State
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(color = BlazeOrange)
                    }
                } else if (candidates.isEmpty()) {
                    // Empty State
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "Aucune proposition trouvée.", color = TextSecondary)
                    }
                } else {
                    // Candidates List
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(candidates) { candidate ->
                            androidx.compose.material3.Card(
                                onClick = {
                                    viewModel.resolveJob(jobId, candidate.videoId)
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = androidx.compose.ui.graphics.Color(0xFF2A2A2A)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Thumbnail cover
                                    if (candidate.coverUri != null) {
                                        AsyncImage(
                                            model = candidate.coverUri,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    } else {
                                        PlaceholderCover(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = candidate.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = candidate.artist + (candidate.album?.let { " • $it" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    val duration = candidate.duration
                                    if (duration != null) {
                                        Text(
                                            text = duration,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Action Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(text = "Annuler", color = BlazeOrange)
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryTracksScreen(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    refreshToken: Int,
    onNavigateBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val tracksState = produceState<List<TrackListRow>?>(initialValue = null, repository, refreshTick, refreshToken) {
        value = repository.getAllTracks()
    }
    val scope = rememberCoroutineScope()
    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null)}
    var trackToDelete by remember { mutableStateOf<TrackListRow?>(null) }
    var pendingDeleteTrackId by remember { mutableStateOf<String?>(null) }
    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingDeleteTrackId?.let { trackId ->
                scope.launch {
                    repository.deleteTrack(trackId)
                    pendingDeleteTrackId = null
                    refreshTick++
                }
            }
        } else {
            pendingDeleteTrackId = null
        }
    }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshTick, refreshToken) {
        value = repository.getPlaylists()
    }
    val playlists = playlistsState.value

    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = remember(context) { context.getSharedPreferences("aura_prefs", android.content.Context.MODE_PRIVATE) }
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository, refreshTick, refreshToken) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value
    val isOnline by appContainer.connectivityObserver.isOnline.collectAsState(initial = true)
    val snackbarHostState = remember { SnackbarHostState() }

    val sortingOptions = listOf("A-Z", "Récents")
    var selectedSort by remember { mutableStateOf(sharedPrefs.getString("library_tracks_sort", "A-Z") ?: "A-Z") }
    var selectedFilter by remember { mutableStateOf("Tous") }
    var showSortMenu by remember { mutableStateOf(false) }

    val lookupIndex = remember(tracksState.value, cloudFiles, syncedCloudTrackIds) {
        TrackLookupIndex.build(tracksState.value ?: emptyList(), cloudFiles, syncedCloudTrackIds)
    }

    val sortedTracks by remember(tracksState.value, selectedSort, selectedFilter, lookupIndex) {
        derivedStateOf {
            val list = tracksState.value ?: return@derivedStateOf null
            val filtered = when (selectedFilter) {
                "Sur l'appareil" -> list.filter { !it.contentUri.isNullOrBlank() }
                "Pas synchronisé" -> list.filter { track ->
                    !track.contentUri.isNullOrBlank() && !lookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)
                }
                else -> list
            }
            when (selectedSort) {
                "A-Z" -> filtered.sortedBy { it.title.lowercase() }
                "Récents" -> filtered.sortedByDescending { it.updatedAt ?: it.createdAt }
                else -> filtered
            }
        }
    }

    RouteScaffold(title = "Tous les titres", onNavigateBack = onNavigateBack, snackbarHostState = snackbarHostState) {
        val tracks = sortedTracks
        if (tracks == null) {
            ShimmerTrackList(count = 8)
        } else if (tracks.isEmpty()) {
            EmptyStateSurface(
                title = "Aucun titre",
                message = "Indexe tes musiques locales pour voir tes pistes ici.",
            )
        } else {
            val contextTracks = remember(tracks) {
                tracks.map { it.toQueuedTrack() }
            }
            com.aura.music.ui.components.AuraLazyColumn(
                modifier = Modifier.fillMaxSize().background(DeepBlack),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .background(
                                Brush.linearGradient(listOf(BlazeOrange, Color(0xFF101010))),
                                RoundedCornerShape(24.dp),
                            )
                            .padding(20.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                "Tous les titres",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                "${tracks.size} piste(s) locale(s)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                if (tracks.isNotEmpty()) {
                                    playerViewModel.onEvent(
                                        PlayerEvent.PlayTrack(
                                            trackId = tracks.first().id,
                                            contextType = "library_tracks",
                                            contextId = "library_tracks",
                                            contextTracks = tracks.map { it.toQueuedTrack() },
                                            startIndex = 0,
                                        ),
                                    )
                                }
                            },
                            enabled = tracks.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BlazeOrange,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                val shuffled = tracks.shuffled()
                                if (shuffled.isNotEmpty()) {
                                    playerViewModel.onEvent(
                                        PlayerEvent.PlayTrack(
                                            trackId = shuffled.first().id,
                                            contextType = "library_tracks",
                                            contextId = "library_tracks",
                                            contextTracks = shuffled.map { it.toQueuedTrack() },
                                            startIndex = 0,
                                        ),
                                    )
                                }
                            },
                            enabled = tracks.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DarkGraphite,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Shuffle", fontWeight = FontWeight.Bold)
                        }
                        Box {
                            Button(
                                onClick = { showSortMenu = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DarkGraphite,
                                    contentColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Sort,
                                    contentDescription = "Trier",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(selectedSort, style = MaterialTheme.typography.labelLarge)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                sortingOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            selectedSort = option
                                            sharedPrefs.edit().putString("library_tracks_sort", option).apply()
                                            showSortMenu = false
                                        },
                                        trailingIcon = {
                                            if (selectedSort == option) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = BlazeOrange,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("Tous", "Sur l'appareil", "Pas synchronisé").forEach { filter ->
                            val isSelected = selectedFilter == filter
                            androidx.compose.material3.FilterChip(
                                selected = isSelected,
                                onClick = { selectedFilter = filter },
                                label = { Text(filter) },
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BlazeOrange,
                                    selectedLabelColor = Color.White,
                                    containerColor = DarkGraphite,
                                    labelColor = TextPrimary
                                ),
                                border = null,
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = tracks,
                    key = { _, track -> track.id },
                    contentType = { _, _ -> "track_row" }
                ) { index, track ->
                    val playEvent = remember(track.id, index, contextTracks) {
                        PlayerEvent.PlayTrack(
                            trackId = track.id,
                            contextType = "library_tracks",
                            contextId = "library_tracks",
                            contextTracks = contextTracks,
                            startIndex = index,
                        )
                    }
                    val onPlayClick = remember(playEvent) { { playerViewModel.onEvent(playEvent) } }
                    val onAddToQueueClick = remember(track.id) { { playerViewModel.onEvent(PlayerEvent.AddToQueue(track.toQueuedTrack())) } }
                    val onLikeClick = remember(track.id) {
                        {
                            scope.launch {
                                repository.toggleLike(track.id, false, "library_tracks", "library_tracks")
                                refreshTick++
                            }
                            Unit
                        }
                    }
                    val onUnlikeClick = remember(track.id) {
                        {
                            scope.launch {
                                repository.toggleLike(track.id, true, "library_tracks", "library_tracks")
                                refreshTick++
                            }
                            Unit
                        }
                    }
                    val onAddToPlaylistClick = remember(track.id) { { activeTrackForPlaylist = track } }
                    val onViewArtistClick = remember(track.artistId) {
                        track.artistId?.let { artistId -> { onOpenArtist(artistId) } }
                    }
                    val onViewAlbumClick = remember(track.albumId) {
                        track.albumId?.let { albumId -> { onOpenAlbum(albumId) } }
                    }
                    val onDeleteDownloadClick = remember(track.id) { { trackToDelete = track } }

                    val isDownloadedLocally = !track.contentUri.isNullOrBlank()
                    val isPresentInCloud = lookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)

                    val onUploadToCloudLambda = remember(track.id, isDownloadedLocally, isPresentInCloud) {
                        if (isDownloadedLocally && !isPresentInCloud) {
                            {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                                    cloudFileRepository.uploadTrack(track.id).collect { res ->
                                        res.onSuccess {
                                            snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                                            cloudFileRepository.refreshSyncedTrackIds()
                                            refreshTick++
                                        }.onFailure { err ->
                                            snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                                        }
                                    }
                                }
                                Unit
                            }
                        } else null
                    }

                    val isCloudOnlyTrack = !isDownloadedLocally && isPresentInCloud
                    val onDownloadFromCloudLambda = remember(track.id, isCloudOnlyTrack, isPresentInCloud) {
                        if (isCloudOnlyTrack && isPresentInCloud) {
                            {
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
                                            repository.refreshLocalMediaIndex()
                                            refreshTick++
                                        }.onFailure { err ->
                                            snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                                        }
                                    }
                                }
                                Unit
                            }
                        } else null
                    }

                    val isOfflineBlocked = isCloudOnlyTrack && !isOnline

                    SharedTrackRowItem(
                        title = track.title,
                        subtitle = listOfNotNull(track.artistName, track.albumTitle).joinToString(" • "),
                        onClick = onPlayClick,
                        coverUri = track.coverUri,
                        contextType = "standard",
                        isLiked = track.isLiked,
                        downloadStatus = if (isDownloadedLocally) com.aura.music.ui.screens.TrackDownloadStatus.Downloaded else com.aura.music.ui.screens.TrackDownloadStatus.Idle,
                        isCloudOnly = isCloudOnlyTrack,
                        isOfflineDisabled = isOfflineBlocked,
                        onOfflineBlocked = {
                            scope.launch {
                                snackbarHostState.showSnackbar("Ce titre est sur le Cloud. Connexion Internet requise pour le streamer.")
                            }
                        },
                        onAddToQueue = onAddToQueueClick,
                        onLike = onLikeClick,
                        onUnlike = onUnlikeClick,
                        onAddToPlaylist = onAddToPlaylistClick,
                        onViewArtist = onViewArtistClick,
                        onViewAlbum = onViewAlbumClick,
                        onDeleteDownload = if (isDownloadedLocally) onDeleteDownloadClick else null,
                        onUploadToCloud = onUploadToCloudLambda,
                        onDownloadFromCloud = onDownloadFromCloudLambda,
                    )
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                scope.launch {
                    repository.addTrackToPlaylist(playlist.id, activeTrackForPlaylist!!.id, contextType = "library_tracks")
                    activeTrackForPlaylist = null
                    refreshTick++
                }
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
                val trackId = trackToDelete!!.id
                pendingDeleteTrackId = trackId
                scope.launch {
                    val pendingIntent = repository.deleteTrack(trackId)
                    if (pendingIntent != null) {
                        try {
                            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            intentSenderLauncher.launch(intentSenderRequest)
                        } catch (e: Exception) {
                            android.util.Log.e("LibraryTracksScreen", "Failed to launch intent sender for delete", e)
                            pendingDeleteTrackId = null
                        }
                    } else {
                        pendingDeleteTrackId = null
                        refreshTick++
                    }
                    trackToDelete = null
                }
            }
        )
    }
}

@Composable
fun LibraryArtistsScreen(
    repository: LocalLibraryRepository,
    refreshToken: Int,
    onNavigateBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val artistsState = produceState(initialValue = emptyList<ArtistBrowseRow>(), repository, refreshToken) {
        value = repository.getAllBrowseArtists()
    }

    val sortingOptions = listOf("A-Z", "Plus de titres", "Récents")
    var selectedSort by remember { mutableStateOf("A-Z") }
    var showSortMenu by remember { mutableStateOf(false) }

    val sortedArtists by remember(artistsState.value, selectedSort) {
        derivedStateOf {
            when (selectedSort) {
                "A-Z" -> artistsState.value.sortedBy { it.name.lowercase() }
                "Plus de titres" -> artistsState.value.sortedByDescending { it.trackCount }
                "Récents" -> artistsState.value.sortedByDescending { it.updatedAt }
                else -> artistsState.value
            }
        }
    }

    RouteScaffold(title = "Artistes", onNavigateBack = onNavigateBack) {
        if (artistsState.value.isEmpty()) {
            EmptyStateSurface(
                title = "Aucun artiste",
                message = "Indexe tes musiques locales pour voir tes artistes ici.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(DeepBlack),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${artistsState.value.size} artiste(s)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    Icons.Rounded.Sort,
                                    contentDescription = "Trier",
                                    tint = Color.White,
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                sortingOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            selectedSort = option
                                            showSortMenu = false
                                        },
                                        leadingIcon = if (selectedSort == option) {
                                            { Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(0xFF00E0FF)) }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                }
                items(sortedArtists, key = { it.id }) { artist ->
                    androidx.compose.material3.Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { onOpenArtist(artist.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(androidx.compose.ui.graphics.Color(0xFF1E1E1E))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (artist.pictureUri != null) {
                                AsyncImage(
                                    model = artist.pictureUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(
                                            Brush.linearGradient(listOf(Color(0xFF792BEE), Color(0xFF101010))),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Mic,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    artist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${artist.trackCount} piste(s) • ${artist.albumCount} album(s)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}


