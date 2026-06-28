package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Downloading
import com.aura.music.ui.theme.TextPrimary
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.ui.screens.SelectPlaylistDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import com.aura.music.ui.theme.BlazeOrange
import coil3.compose.AsyncImage
import com.aura.music.AuraApplication
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.ArtistSummary
import com.aura.music.data.network.AlbumSummary
import com.aura.music.data.network.TrackSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.search.SearchViewModel
import com.aura.music.ui.search.SearchViewModelFactory
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.HairlineDark
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.ui.toQueuedTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: LocalLibraryRepository,
    refreshToken: Int,
    onRequestAudioPermission: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    playerViewModel: PlayerViewModel,
) {
    val focusManager = LocalFocusManager.current
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as AuraApplication
    val searchRepository = application.container.searchRepository
    val enrichmentRepository = application.container.enrichmentRepository
    val downloadRepository = application.container.downloadRepository
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val cloudFileRepository = application.container.cloudFileRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    
    val viewModel: SearchViewModel = viewModel(
        factory = SearchViewModelFactory(searchRepository, enrichmentRepository, application)
    )
    
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null) }
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
                    viewModel.refreshDisplayedLocalResults()
                }
            }
        } else {
            pendingDeleteTrackId = null
        }
    }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshToken) {
        value = repository.getPlaylists()
    }
    val playlists = playlistsState.value

    LaunchedEffect(refreshToken) {
        viewModel.refreshDisplayedLocalResults()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDisplayedLocalResults()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    RouteScaffold(
        title = "Recherche",
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = onOpenDownloads) {
                Icon(
                    imageVector = Icons.Rounded.Downloading,
                    contentDescription = "Téléchargements",
                    tint = TextPrimary
                )
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Search bar
            item {
                Spacer(modifier = Modifier.height(12.dp))
                SearchBarInput(
                    query = uiState.query,
                    onQueryChange = viewModel::updateQuery,
                    onSubmit = {
                        focusManager.clearFocus()
                        viewModel.submitSearch()
                    },
                    onClear = viewModel::clearQuery,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Local and Online suggestions dropdown (only during typing with 3+ chars)
            if (uiState.shouldShowSuggestions) {
                item {
                    LocalSuggestionsSection(
                        result = uiState.localSuggestions,
                        onSelectTrack = { track ->
                            focusManager.clearFocus()
                            viewModel.selectSuggestion(track.title)
                        },
                        onSelectArtist = { artist ->
                            focusManager.clearFocus()
                            viewModel.selectSuggestion(artist.name)
                        },
                        onSelectAlbum = { album ->
                            focusManager.clearFocus()
                            viewModel.selectSuggestion(album.title)
                        },
                        onSelectOnlineTrack = { track ->
                            focusManager.clearFocus()
                            viewModel.selectTab(1)
                            viewModel.selectSuggestion(track.title)
                        },
                        onSelectOnlineArtist = { artist ->
                            focusManager.clearFocus()
                            viewModel.selectTab(1)
                            viewModel.selectSuggestion(artist.name)
                        },
                        onSelectOnlineAlbum = { album ->
                            focusManager.clearFocus()
                            viewModel.selectTab(1)
                            viewModel.selectSuggestion(album.title)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Recent searches (shown when no search is active and no suggestions)
            if (!uiState.isSearchComplete && !uiState.shouldShowSuggestions && uiState.recentQueries.isNotEmpty()) {
                item {
                    RecentSearchesSection(
                        queries = uiState.recentQueries,
                        onSelectQuery = { query ->
                            focusManager.clearFocus()
                            viewModel.selectRecentQuery(query)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Loading indicator
            if (uiState.isLoadingFullSearch) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // Error banner
            if (uiState.errorMessage != null) {
                item {
                    ErrorBanner(
                        message = uiState.errorMessage!!,
                        onDismiss = viewModel::dismissError,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Search results with tab navigation
            if (uiState.isSearchComplete && uiState.displayResult != null) {
                val result = uiState.displayResult!!

                // Tab navigation
                item {
                    TabRow(
                        selectedTabIndex = uiState.selectedTab,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        androidx.compose.material3.Tab(
                            selected = uiState.selectedTab == 0,
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.selectTab(0)
                            },
                            text = { Text("Bibliothèque") }
                        )
                        androidx.compose.material3.Tab(
                            selected = uiState.selectedTab == 1,
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.selectTab(1)
                            },
                            text = { Text("En ligne") }
                        )
                    }
                }

                // Local library tab content
                if (uiState.selectedTab == 0) {
                    if (result.localTracks.isNotEmpty() || result.localArtists.isNotEmpty() || result.localAlbums.isNotEmpty()) {
                        item {
                            LocalLibrarySearchTab(
                                tracks = result.localTracks,
                                artists = result.localArtists,
                                albums = result.localAlbums,
                                onPlayTrack = { track, allTracks ->
                                    focusManager.clearFocus()
                                    onPlayTrackInList(track, allTracks, "search_local")
                                },
                                onLikeTrack = { trackId, isLiked ->
                                    viewModel.likeLocalTrack(trackId, isLiked)
                                },
                                onAddToPlaylist = { track -> activeTrackForPlaylist = track },
                                onOpenArtist = { artistId ->
                                    focusManager.clearFocus()
                                    onOpenArtist(artistId)
                                },
                                onOpenAlbum = { albumId ->
                                    focusManager.clearFocus()
                                    onOpenAlbum(albumId)
                                },
                                onDeleteTrack = { track ->
                                    trackToDelete = track
                                },
                                onAddToQueue = { track ->
                                    playerViewModel.onEvent(PlayerEvent.AddToQueue(track.toQueuedTrack()))
                                },
                                cloudFileRepository = cloudFileRepository,
                                syncedCloudTrackIds = syncedCloudTrackIds,
                                snackbarHostState = snackbarHostState,
                                scope = scope,
                                localLibraryRepository = repository,
                                onRefresh = { viewModel.refreshDisplayedLocalResults() },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    } else {
                        item {
                            EmptyStateSurface(
                                title = "Aucun résultat local",
                                message = "Essayez une autre recherche",
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                // Online tab content
                if (uiState.selectedTab == 1) {
                    if (result.onlineTracks.isNotEmpty() || result.onlineArtists.isNotEmpty() || result.onlineAlbums.isNotEmpty()) {
                        item {
                            OnlineSearchTab(
                                tracks = result.onlineTracks,
                                artists = result.onlineArtists,
                                albums = result.onlineAlbums,
                                onPlayTrack = { track ->
                                    focusManager.clearFocus()
                                    onPlayTrackInList(
                                        TrackListRow(
                                            id = track.id,
                                            artistId = null,
                                            albumId = null,
                                            title = track.title,
                                            artistName = track.displayArtistName,
                                            albumTitle = track.displayAlbumTitle,
                                            contentUri = null,
                                            durationMs = track.durationMs.toLong(),
                                            coverUri = track.coverUri,
                                            isLiked = track.isLiked
                                        ),
                                        emptyList(),
                                        "search_online_tracks"
                                    )
                                },
                                onDownloadTrack = { track ->
                                    scope.launch {
                                        downloadRepository.triggerDownload(
                                            trackId = track.id,
                                            title = track.title,
                                            artistName = track.displayArtistName,
                                            albumTitle = track.displayAlbumTitle,
                                            coverUri = track.coverUri,
                                            userToken = com.aura.music.data.repository.SyncRepository.AUTH_TOKEN
                                        ).collect { result ->
                                            if (result.isSuccess) {
                                                scope.launch {
                                                    val snackbarResult = snackbarHostState.showSnackbar(
                                                        message = "Téléchargement lancé : ${track.title}",
                                                        actionLabel = "Voir",
                                                        duration = androidx.compose.material3.SnackbarDuration.Short
                                                    )
                                                    if (snackbarResult == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                                        onOpenDownloads()
                                                    }
                                                }
                                                scope.launch {
                                                    downloadRepository.startPolling(com.aura.music.data.repository.SyncRepository.AUTH_TOKEN)
                                                }
                                            }
                                        }
                                    }
                                },
                                onAddToPlaylist = { track ->
                                    activeTrackForPlaylist = TrackListRow(
                                        id = track.id,
                                        artistId = null,
                                        albumId = null,
                                        title = track.title,
                                        artistName = track.displayArtistName,
                                        albumTitle = track.displayAlbumTitle,
                                        contentUri = null,
                                        durationMs = track.durationMs.toLong(),
                                        coverUri = track.coverUri,
                                        isLiked = track.isLiked
                                    )
                                },
                                onOpenArtist = { artistId ->
                                    focusManager.clearFocus()
                                    onOpenArtist(artistId)
                                },
                                onOpenAlbum = { albumId ->
                                    focusManager.clearFocus()
                                    onOpenAlbum(albumId)
                                },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    } else {
                        item {
                            EmptyStateSurface(
                                title = "Aucun résultat en ligne",
                                message = "Vérifiez votre connexion",
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                scope.launch {
                    repository.addTrackToPlaylist(playlist.id, activeTrackForPlaylist!!.id, contextType = "search")
                    activeTrackForPlaylist = null
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
                            android.util.Log.e("SearchScreen", "Failed to launch intent sender for delete", e)
                            pendingDeleteTrackId = null
                        }
                    } else {
                        pendingDeleteTrackId = null
                        viewModel.refreshDisplayedLocalResults()
                    }
                    trackToDelete = null
                }
            }
        )
    }
}

/**
 * Local library search results tab content.
 */
@Composable
private fun LocalLibrarySearchTab(
    tracks: List<TrackListRow>,
    artists: List<com.aura.music.data.local.ArtistBrowseRow>,
    albums: List<com.aura.music.data.local.AlbumBrowseRow>,
    onPlayTrack: (TrackListRow, List<TrackListRow>) -> Unit,
    onLikeTrack: (String, Boolean) -> Unit,
    onAddToPlaylist: (TrackListRow) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onDeleteTrack: (TrackListRow) -> Unit,
    onAddToQueue: (TrackListRow) -> Unit,
    modifier: Modifier = Modifier,
    cloudFileRepository: com.aura.music.data.repository.CloudFileRepository,
    syncedCloudTrackIds: Set<String>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    localLibraryRepository: com.aura.music.data.repository.LocalLibraryRepository,
    onRefresh: () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (tracks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Titres",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                tracks.take(5).forEach { track ->
                    SearchTrackRowItem(
                        track = track,
                        tracks = tracks,
                        onPlayTrack = onPlayTrack,
                        onAddToQueue = onAddToQueue,
                        onLikeTrack = onLikeTrack,
                        onAddToPlaylist = onAddToPlaylist,
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                        onDeleteTrack = onDeleteTrack,
                        cloudFileRepository = cloudFileRepository,
                        syncedCloudTrackIds = syncedCloudTrackIds,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        localLibraryRepository = localLibraryRepository,
                        onRefresh = onRefresh
                    )
                }
            }
        }

        if (artists.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Artistes",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                BrowseArtistRail(artists, onOpenArtist)
            }
        }

        if (albums.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Albums",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                BrowseAlbumRail(albums, onOpenAlbum)
            }
        }
    }
}

/**
 * Online search results tab content.
 */
@Composable
private fun OnlineSearchTab(
    tracks: List<TrackSummary>,
    artists: List<ArtistSummary>,
    albums: List<AlbumSummary>,
    onPlayTrack: (TrackSummary) -> Unit,
    onDownloadTrack: (TrackSummary) -> Unit,
    onAddToPlaylist: (TrackSummary) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (tracks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Titres",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                tracks.take(5).forEach { track ->
                    SearchOnlineTrackRowItem(
                        track = track,
                        artists = artists,
                        albums = albums,
                        onPlayTrack = onPlayTrack,
                        onDownloadTrack = onDownloadTrack,
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum
                    )
                }
            }
        }

        if (artists.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Artistes",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    items(artists.take(6), key = { it.id }) { artist ->
                        SharedRailCard(
                            title = artist.name,
                            subtitle = "Artiste en ligne",
                            imageUri = artist.pictureUri,
                            gradientStartColor = Color(0xFF792BEE),
                            imageShape = CircleShape,
                            onClick = { onOpenArtist(artist.id) },
                        )
                    }
                }
            }
        }

        if (albums.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Albums",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    items(albums.take(6), key = { it.id }) { album ->
                        SharedRailCard(
                            title = album.title,
                            subtitle = album.primaryArtistName,
                            imageUri = album.coverUri,
                            gradientStartColor = Color(0xFFFF9E00),
                            imageShape = RoundedCornerShape(20.dp),
                            onClick = { onOpenAlbum(album.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Search input bar with clear button and submit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBarInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var textFieldValue by remember(query) {
        mutableStateOf(TextFieldValue(text = query, selection = androidx.compose.ui.text.TextRange(query.length)))
    }

    OutlinedTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
            onQueryChange(newValue.text)
        },
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        placeholder = { Text("Rechercher...") },
        leadingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = "Rechercher",
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Effacer",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        colors = OutlinedTextFieldDefaults.colors(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search
        ),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSubmit()
                keyboardController?.hide()
            }
        )
    )
}


/**
 * Local suggestions dropdown (shown during typing with 3+ chars).
 */
@Composable
private fun LocalSuggestionsSection(
    result: com.aura.music.data.repository.HybridSearchResult?,
    onSelectTrack: (TrackListRow) -> Unit,
    onSelectArtist: (com.aura.music.data.local.ArtistBrowseRow) -> Unit,
    onSelectAlbum: (com.aura.music.data.local.AlbumBrowseRow) -> Unit,
    onSelectOnlineTrack: (TrackSummary) -> Unit,
    onSelectOnlineArtist: (ArtistSummary) -> Unit,
    onSelectOnlineAlbum: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    if (result == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        // Tracks
        if (result.localTracks.isNotEmpty()) {
            result.localTracks.take(3).forEach { track ->
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            onSelectTrack(track)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                track.artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Online Tracks (Suggestions)
        if (result.onlineTracks.isNotEmpty()) {
            result.onlineTracks.take(3).forEach { track ->
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            onSelectOnlineTrack(track)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BlazeOrange
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Titre • ${track.displayArtistName} (En ligne)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Artists
        if (result.localArtists.isNotEmpty()) {
            result.localArtists.take(2).forEach { artist ->
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            onSelectArtist(artist)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            artist.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Online Artists (Suggestions)
        if (result.onlineArtists.isNotEmpty()) {
            result.onlineArtists.take(2).forEach { artist ->
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            onSelectOnlineArtist(artist)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BlazeOrange
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                artist.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Artiste en ligne",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Albums
        if (result.localAlbums.isNotEmpty()) {
            result.localAlbums.take(2).forEach { album ->
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            onSelectAlbum(album)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                album.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                album.artistName ?: "Artiste inconnu",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Online Albums (Suggestions)
        if (result.onlineAlbums.isNotEmpty()) {
            result.onlineAlbums.take(2).forEach { album ->
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            onSelectOnlineAlbum(album)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BlazeOrange
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                album.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Album • ${album.primaryArtistName} (En ligne)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}




/**
 * Online tracks section.
 */
@Composable
private fun OnlineTracksSection(
    tracks: List<TrackSummary>,
    onPlayTrack: (TrackSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            "En ligne - Titres",
            "Résultats du backend AURA"
        )

        tracks.take(5).forEach { track ->
            androidx.compose.material3.Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clickable { onPlayTrack(track) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val cover = track.coverUri
                    if (cover != null) {
                        AsyncImage(
                            model = cover,
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        PlaceholderCover(modifier = Modifier.size(40.dp))
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            track.displayArtistName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Online artists section.
 */
@Composable
private fun OnlineArtistsSection(
    artists: List<ArtistSummary>,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            "En ligne - Artistes",
            "Résultats du backend AURA"
        )

        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 0.dp)
        ) {
            items(artists.take(6), key = { it.id }) { artist ->
                com.aura.music.ui.screens.SharedRailCard(
                    title = artist.name,
                    subtitle = "Artiste",
                    imageUri = artist.pictureUri,
                    gradientStartColor = Color(0xFF792BEE),
                    imageShape = CircleShape,
                    onClick = { onOpenArtist(artist.id) }
                )
            }
            item { Spacer(modifier = Modifier.size(16.dp)) }
        }
    }
}

/**
 * Online albums section.
 */
@Composable
private fun OnlineAlbumsSection(
    albums: List<AlbumSummary>,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            "En ligne - Albums",
            "Résultats du backend AURA"
        )

        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 0.dp)
        ) {
            items(albums.take(6), key = { it.id }) { album ->
                com.aura.music.ui.screens.SharedRailCard(
                    title = album.title,
                    subtitle = album.primaryArtistName,
                    imageUri = album.coverUri,
                    gradientStartColor = Color(0xFFFF9E00),
                    imageShape = RoundedCornerShape(20.dp),
                    onClick = { onOpenAlbum(album.id) }
                )
            }
            item { Spacer(modifier = Modifier.size(16.dp)) }
        }
    }
}


@Composable
private fun HeroLocalTrackCard(track: TrackListRow, onPlay: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(ElevatedGraphite, HairlineDark)))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val cover = track.coverUri
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                PlaceholderCover(modifier = Modifier.size(80.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}



/**
 * Recent searches section.
 */
@Composable
private fun RecentSearchesSection(
    queries: List<String>,
    onSelectQuery: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Recherches récentes",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )

        queries.forEach { query ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectQuery(query) }
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(query, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Error banner.
 */
@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Card(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
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
                    "Erreur de recherche",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Fermer",
                    modifier = Modifier.size(18.dp),
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * Placeholder cover for missing images.
 */
@Composable
private fun PlaceholderCover(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Gray)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White)
    }
}

/**
 * Section title.
 */
@Composable
private fun SectionTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

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

@Composable
private fun SearchTrackRowItem(
    track: TrackListRow,
    tracks: List<TrackListRow>,
    onPlayTrack: (TrackListRow, List<TrackListRow>) -> Unit,
    onAddToQueue: (TrackListRow) -> Unit,
    onLikeTrack: ((String, Boolean) -> Unit)? = null,
    onAddToPlaylist: ((TrackListRow) -> Unit)? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
    onDeleteTrack: ((TrackListRow) -> Unit)? = null,
    cloudFileRepository: com.aura.music.data.repository.CloudFileRepository,
    syncedCloudTrackIds: Set<String>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    localLibraryRepository: com.aura.music.data.repository.LocalLibraryRepository,
    onRefresh: () -> Unit,
) {
    val currentOnPlay = rememberUpdatedState(onPlayTrack)
    val currentOnQueue = rememberUpdatedState(onAddToQueue)
    val currentOnLike = rememberUpdatedState(onLikeTrack)
    val currentOnPlaylist = rememberUpdatedState(onAddToPlaylist)
    val currentOnArtist = rememberUpdatedState(onOpenArtist)
    val currentOnAlbum = rememberUpdatedState(onOpenAlbum)
    val currentOnDelete = rememberUpdatedState(onDeleteTrack)

    val onClick = remember(track.id, tracks) { { currentOnPlay.value(track, tracks) } }
    val onAddToQueueClick = remember(track.id) { { currentOnQueue.value(track) } }
    
    val onLikeClick = remember(track.id, track.isLiked, currentOnLike.value != null) {
        val cb = currentOnLike.value
        if (cb != null) {
            { cb(track.id, track.isLiked) }
        } else null
    }
    
    val onAddToPlaylistClick = remember(track.id, currentOnPlaylist.value != null) {
        val cb = currentOnPlaylist.value
        if (cb != null) {
            { cb(track) }
        } else null
    }

    val artistId = track.artistId
    val hasArtist = !artistId.isNullOrBlank() && currentOnArtist.value != null
    val onViewArtistClick = remember(track.id, artistId, hasArtist) {
        val cb = currentOnArtist.value
        if (hasArtist && cb != null) {
            { cb(artistId!!) }
        } else null
    }

    val albumId = track.albumId
    val hasAlbum = !albumId.isNullOrBlank() && currentOnAlbum.value != null
    val onViewAlbumClick = remember(track.id, albumId, hasAlbum) {
        val cb = currentOnAlbum.value
        if (hasAlbum && cb != null) {
            { cb(albumId!!) }
        } else null
    }

    val onDeleteClick = remember(track.id, currentOnDelete.value != null) {
        val cb = currentOnDelete.value
        if (cb != null) {
            { cb(track) }
        } else null
    }

    val isLocalScanned = track.contentUri?.startsWith("content://") == true
    val isAlreadySynced = syncedCloudTrackIds.contains(track.id)
    val onUploadToCloudLambda = remember(track.id, isLocalScanned, isAlreadySynced) {
        if (isLocalScanned && !isAlreadySynced) {
            {
                scope.launch {
                    snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                    cloudFileRepository.uploadTrack(track.id).collect { res ->
                        res.onSuccess {
                            snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                            cloudFileRepository.refreshSyncedTrackIds()
                            onRefresh()
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
    val isPresentInCloud = syncedCloudTrackIds.contains(track.id)
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
                            localLibraryRepository.refreshLocalMediaIndex()
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
        subtitle = track.artistName ?: "",
        coverUri = track.coverUri,
        onClick = onClick,
        showCover = true,
        contextType = "standard",
        isLiked = track.isLiked,
        onLike = onLikeClick,
        onUnlike = onLikeClick,
        onAddToQueue = onAddToQueueClick,
        onAddToPlaylist = onAddToPlaylistClick,
        onViewArtist = onViewArtistClick,
        onViewAlbum = onViewAlbumClick,
        onDeleteDownload = onDeleteClick,
        onUploadToCloud = onUploadToCloudLambda,
        onDownloadFromCloud = onDownloadFromCloudLambda
    )
}

@Composable
private fun SearchOnlineTrackRowItem(
    track: TrackSummary,
    artists: List<ArtistSummary>,
    albums: List<AlbumSummary>,
    onPlayTrack: (TrackSummary) -> Unit,
    onDownloadTrack: (TrackSummary) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val currentOnPlay = rememberUpdatedState(onPlayTrack)
    val currentOnDownload = rememberUpdatedState(onDownloadTrack)
    val currentOnArtist = rememberUpdatedState(onOpenArtist)
    val currentOnAlbum = rememberUpdatedState(onOpenAlbum)

    val onClick = remember(track.id) { { currentOnPlay.value(track) } }
    val onDownload = remember(track.id) { { currentOnDownload.value(track) } }
    val onViewArtist = remember(track.displayArtistName, artists) {
        {
            val matchedArtist = artists.firstOrNull { it.name.trim().lowercase() == track.displayArtistName.trim().lowercase() }
            val artistIdToOpen = matchedArtist?.id ?: "artist:${normalize(track.displayArtistName)}"
            currentOnArtist.value(artistIdToOpen)
        }
    }
    val onViewAlbum = remember(track.displayArtistName, track.displayAlbumTitle, albums) {
        track.displayAlbumTitle?.let { albumTitle ->
            {
                val matchedAlbum = albums.firstOrNull { it.title.trim().lowercase() == albumTitle.trim().lowercase() }
                val albumIdToOpen = matchedAlbum?.id ?: "album:${normalize(track.displayArtistName)}:${normalize(albumTitle)}"
                currentOnAlbum.value(albumIdToOpen)
            }
        }
    }

    SharedTrackRowItem(
        title = track.title,
        subtitle = track.displayArtistName,
        coverUri = track.coverUri,
        onClick = onClick,
        showCover = true,
        contextType = "search_online",
        onDownload = onDownload,
        onViewArtist = onViewArtist,
        onViewAlbum = onViewAlbum
    )
}
