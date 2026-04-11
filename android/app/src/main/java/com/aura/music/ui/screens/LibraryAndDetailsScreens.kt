package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.foundation.layout.PaddingValues
import com.aura.music.ui.theme.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.local.UserSettingsEntity
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
import com.aura.music.ui.TrackList
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.toQueuedTrack
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    repository: LocalLibraryRepository,
    refreshToken: Int,
    onRequestAudioPermission: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshToken) {
        value = repository.getLibraryDashboardSummary()
    }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshToken) {
        value = repository.getPlaylists()
    }
    val artistsState = produceState(initialValue = emptyList<ArtistBrowseRow>(), repository, refreshToken) {
        value = repository.getBrowseArtists(8)
    }
    val albumsState = produceState(initialValue = emptyList<AlbumBrowseRow>(), repository, refreshToken) {
        value = repository.getBrowseAlbums(8)
    }
    var query by remember { mutableStateOf("") }
    val searchResults = remember { mutableStateListOf<TrackListRow>() }
    val searchArtists = remember { mutableStateListOf<ArtistBrowseRow>() }
    val searchAlbums = remember { mutableStateListOf<AlbumBrowseRow>() }

    LaunchedEffect(query, repository, refreshToken) {
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

    RouteScaffold(
        title = "Bibliothèque",
        actions = {
            IconButton(onClick = onOpenDownloads) {
                Icon(Icons.Rounded.DownloadDone, contentDescription = "Téléchargements", tint = TextPrimary)
            }
        }
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
                    placeholder = { Text("Rechercher dans vos musiques locales...", color = TextSecondary) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextSecondary)
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
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LibraryGridItem("Titres", "${summaryState.value?.roomTrackCount ?: 0} éléments", Icons.Rounded.MusicNote, { }, Modifier.weight(1f))
                            LibraryGridItem("Albums", "Parcourir", Icons.Rounded.Album, { }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LibraryGridItem("Artistes", "Parcourir", Icons.Rounded.Mic, { }, Modifier.weight(1f))
                            LibraryGridItem("Playlists", "${playlistsState.value.size} éléments", Icons.Rounded.QueueMusic, onOpenPlaylists, Modifier.weight(1f))
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
                    item {
                        TrackList(
                            title = "Titres correspondants",
                            tracks = searchResults.toList(),
                            contextType = "library_search",
                            onPlayTrackInList = onPlayTrackInList,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                        )
                    }
                }
                if (searchAlbums.isNotEmpty()) {
                    item { Text("Albums correspondants", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.padding(horizontal = 16.dp)) }
                    item { BrowseAlbumRail(albums = searchAlbums.toList(), onOpenAlbum = onOpenAlbum) }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
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
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshTick) {
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
                        Text("Create playlist", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            PlaylistPreviewList(playlists = playlistsState.value, onOpenPlaylist = onOpenPlaylist)
        }
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = "Create playlist",
            confirmLabel = "Create",
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
                        ) { Text("Play") }
                        Button(
                            onClick = {
                                playPlaylist(playerViewModel, detail.tracks.map { it.toTrackListRow() }, true, detail.summary.id)
                            },
                            enabled = detail.tracks.isNotEmpty(),
                        ) { Text("Shuffle") }
                        Button(onClick = { showAddTrackDialog = true }) { Text("Add track") }
                    }
                }
                item {
                    Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                            Text("Rename", modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = null)
                            Text("Delete", modifier = Modifier.padding(start = 8.dp))
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
            title = "Rename playlist",
            confirmLabel = "Save",
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
            title = "Delete playlist",
            message = "Delete ${detail.summary.name}? This removes the playlist and its local ordering only.",
            confirmLabel = "Delete",
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
    artistId: String,
    onNavigateBack: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val artistState = produceState<ArtistDetail?>(initialValue = null, repository, artistId) {
        value = repository.getArtistDetail(artistId)
    }
    val artist = artistState.value

    RouteScaffold(title = artist?.summary?.name ?: "Artist", onNavigateBack = onNavigateBack) {
        if (artist == null) {
            EmptyStateSurface(
                title = "Artiste introuvable",
                message = "Cette surface attend soit un artiste local, soit un enrichissement online plus tard.",
            )
            return@RouteScaffold
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
            item {
                TrackList(
                    title = "Titres populaires",
                    tracks = artist.topTracks,
                    contextType = "artist",
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = { },
                    onOpenAlbum = onOpenAlbum,
                )
            }
            item { SectionTitle("Albums", "Navigation album depuis la bibliotheque locale.") }
            item { BrowseAlbumRail(albums = artist.albums, onOpenAlbum = onOpenAlbum) }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun AlbumRouteScreen(
    repository: LocalLibraryRepository,
    albumId: String,
    onNavigateBack: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val albumState = produceState<AlbumDetail?>(initialValue = null, repository, albumId) {
        value = repository.getAlbumDetail(albumId)
    }
    val album = albumState.value

    RouteScaffold(title = album?.summary?.title ?: "Album", onNavigateBack = onNavigateBack) {
        if (album == null) {
            EmptyStateSurface(
                title = "Album introuvable",
                message = "Cette surface recevra plus tard aussi les enrichissements online quand Search sera branche.",
            )
            return@RouteScaffold
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                HeroIdentityCard(
                    title = album.summary.title,
                    subtitle = listOfNotNull(
                        album.summary.artistName,
                        album.summary.trackCount?.let { "$it piste(s)" },
                        album.summary.releaseDate,
                    ).joinToString(" | "),
                    gradient = Brush.linearGradient(listOf(Color(0xFF00E0FF), Color(0xFF101010))),
                )
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
                    if (album.summary.artistId != null) {
                        Button(onClick = { onOpenArtist(album.summary.artistId) }) { Text("Artist") }
                    }
                }
            }
            item {
                TrackList(
                    title = "Tracklist",
                    tracks = album.tracks,
                    contextType = "album",
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = { },
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun DownloadsScreen(onNavigateBack: () -> Unit) {
    val filters = listOf("En attente", "En cours", "Termines", "Erreurs")
    var selectedFilter by remember { mutableStateOf(filters.first()) }

    RouteScaffold(title = "Downloads", onNavigateBack = onNavigateBack) {
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                HeroIdentityCard(
                    title = "Downloads",
                    subtitle = "Le backend jobs arrive ensuite; cette surface est deja prete pour ses etats.",
                    gradient = Brush.linearGradient(listOf(Color(0xFFFF6B00), Color(0xFF101010))),
                )
            }
            item { FilterRow(values = filters, selected = selectedFilter, onSelect = { selectedFilter = it }) }
            item {
                when (selectedFilter) {
                    "En attente" -> DownloadStateCard(Icons.Rounded.Schedule, "Aucun job en attente", "Les demandes de disponibilite locale apparaitront ici avant execution.")
                    "En cours" -> DownloadStateCard(Icons.Rounded.Sync, "Pas de progression active", "Les barres de progression temps reel arriveront avec l'API jobs et downloads.")
                    "Termines" -> DownloadStateCard(Icons.Rounded.DownloadDone, "Aucun download finalise", "Quand un titre sera disponible localement, tu pourras l'ouvrir ou le lire depuis ici.")
                    else -> DownloadStateCard(Icons.Rounded.ErrorOutline, "Pas d'erreur de job", "Les details d'erreur et les boutons Retry seront branches quand SRV-006 sera actif.")
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun SettingsScreen(
    repository: LocalLibraryRepository,
    onNavigateBack: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val settingsState = produceState<UserSettingsEntity?>(initialValue = null, repository, refreshTick) {
        repository.ensureDefaults()
        value = repository.getSettings()
    }
    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshTick) {
        value = repository.getLibraryDashboardSummary()
    }
    val settings = settingsState.value

    RouteScaffold(title = "Settings", onNavigateBack = onNavigateBack) {
        if (settings == null) {
            EmptyStateSurface("Settings indisponibles", "Le profil local n'est pas encore initialise.")
            return@RouteScaffold
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                HeroIdentityCard(
                    title = "Preferences AURA",
                    subtitle = "Recherche online, sync future et diagnostic local.",
                    gradient = Brush.linearGradient(listOf(Color(0xFF232323), Color(0xFF050505))),
                )
            }
            item {
                SettingsCard(title = "Compte et sync") {
                    SettingToggleRow(
                        title = "Sync cloud",
                        subtitle = "Optionnelle. L'app reste utile sans backend cloud.",
                        checked = settings.syncEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setSyncEnabled(enabled)
                                refreshTick++
                            }
                        },
                    )
                    Divider()
                    PolicyRow(
                        title = "Sync stats",
                        selected = settings.statsSyncNetworkPolicy,
                        options = listOf("wifi_only", "any_network"),
                        onSelect = { policy ->
                            scope.launch {
                                repository.setStatsSyncNetworkPolicy(policy)
                                refreshTick++
                            }
                        },
                    )
                }
            }
            item {
                SettingsCard(title = "Recherche") {
                    SettingToggleRow(
                        title = "Recherche online",
                        subtitle = "Active la partie backend-only pour la recherche enrichie.",
                        checked = settings.onlineSearchEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setOnlineSearchEnabled(enabled)
                                refreshTick++
                            }
                        },
                    )
                    Divider()
                    PolicyRow(
                        title = "Politique reseau",
                        selected = settings.onlineSearchNetworkPolicy,
                        options = listOf("wifi_only", "any_network"),
                        onSelect = { policy ->
                            scope.launch {
                                repository.setOnlineSearchNetworkPolicy(policy)
                                refreshTick++
                            }
                        },
                    )
                }
            }
            item {
                SettingsCard(title = "Diagnostics") {
                    Text("Pistes indexees: ${summaryState.value?.roomTrackCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text("MediaStore detecte: ${summaryState.value?.mediaStoreTrackCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Snapshot actif: ${if (summaryState.value?.activeSnapshot != null) "oui" else "non"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun PlayerScreen(
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by playerViewModel.uiState.collectAsState()
    val track = uiState.currentTrack
    var seekDraft by remember(track?.trackId, uiState.durationMs) { mutableStateOf<Float?>(null) }
    val sliderValue = seekDraft ?: uiState.positionMs.toFloat()

    RouteScaffold(title = "Player", onNavigateBack = onNavigateBack) {
        if (track == null) {
            EmptyStateSurface(
                title = "Aucune lecture active",
                message = "Lance une piste depuis Home, Library, Search ou Playlists pour ouvrir le full player.",
            )
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
                                .background(Brush.verticalGradient(listOf(Color(0xFF1A1A1A), Color(0xFF050505))))
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .aspectRatio(1f)
                                    .background(
                                        Brush.linearGradient(listOf(Color(0xFFFF6B00), Color(0xFF232323))),
                                        RoundedCornerShape(28.dp),
                                    ),
                            )
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Text(track.artistName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = track.albumTitle ?: describeContext(uiState),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Slider(
                            value = sliderValue.coerceIn(0f, uiState.durationMs.toFloat().coerceAtLeast(0f)),
                            onValueChange = { seekDraft = it },
                            onValueChangeFinished = {
                                val finalValue = seekDraft?.toLong() ?: uiState.positionMs
                                playerViewModel.onEvent(PlayerEvent.SeekTo(finalValue))
                                seekDraft = null
                            },
                            valueRange = 0f..uiState.durationMs.toFloat().coerceAtLeast(1f),
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration((seekDraft ?: uiState.positionMs.toFloat()).toLong()), style = MaterialTheme.typography.labelMedium)
                            Text(formatDuration(uiState.durationMs), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.ToggleShuffle) }) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (uiState.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Previous) }) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.TogglePlayPause) }, modifier = Modifier.size(72.dp)) {
                            Icon(
                                imageVector = if (uiState.playbackState == PlaybackState.Playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Toggle play/pause",
                                modifier = Modifier.size(54.dp),
                            )
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Next) }) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.CycleRepeatMode) }) {
                            Icon(
                                imageVector = if (uiState.repeatMode == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                contentDescription = "Repeat",
                                tint = if (uiState.repeatMode == RepeatMode.Off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                item { SourceContextCard(uiState) }
                item {
                    QueueSection(
                        queue = uiState.priorityQueue,
                        onMoveUp = { index -> playerViewModel.onEvent(PlayerEvent.ReorderQueue(index, index - 1)) },
                        onMoveDown = { index -> playerViewModel.onEvent(PlayerEvent.ReorderQueue(index, index + 1)) },
                        onRemove = { index -> playerViewModel.onEvent(PlayerEvent.RemoveFromQueue(index)) },
                    )
                }
                val errorMsg = uiState.errorMessage
                if (uiState.playbackState == PlaybackState.Error && errorMsg != null) {
                    item { DownloadStateCard(Icons.Rounded.ErrorOutline, "Erreur player", errorMsg) }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
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
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${track.title}")
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up") }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down") }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, contentDescription = "Remove from playlist") }
            }
        },
        modifier = Modifier.clickable(onClick = onPlay),
    )
}

@Composable
private fun PlaylistNameDialog(
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
private fun ConfirmDialog(
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

@Composable
private fun BrowseArtistRail(
    artists: List<ArtistBrowseRow>,
    onOpenArtist: (String) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyStateSurface("Pas encore d'artiste local", "AURA affichera ici les artistes issus de la bibliotheque indexee.", Modifier.padding(horizontal = 16.dp))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = 16.dp)) {
        items(artists, key = { it.id }) { artist ->
            Card(
                modifier = Modifier
                    .size(width = 168.dp, height = 210.dp)
                    .clickable { onOpenArtist(artist.id) },
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Brush.linearGradient(listOf(Color(0xFF792BEE), Color(0xFF232323))), CircleShape),
                    )
                    Text(artist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${artist.trackCount} piste(s) | ${artist.albumCount} album(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun BrowseAlbumRail(
    albums: List<AlbumBrowseRow>,
    onOpenAlbum: (String) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyStateSurface("Pas d'album local", "Les albums locaux indexes seront proposes ici.", Modifier.padding(horizontal = 16.dp))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = 16.dp)) {
        items(albums, key = { it.id }) { album ->
            Card(
                modifier = Modifier
                    .size(width = 172.dp, height = 220.dp)
                    .clickable { onOpenAlbum(album.id) },
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Brush.linearGradient(listOf(Color(0xFFFF9E00), Color(0xFF1A1A1A))), RoundedCornerShape(20.dp)),
                    )
                    Text(album.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artistName ?: "Artiste inconnu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { Spacer(modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeroIdentityCard(
    title: String,
    subtitle: String,
    gradient: Brush,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(modifier = Modifier.background(gradient).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DownloadStateCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(modifier = Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FilterRow(
    values: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp)) {
        items(values, key = { it }) { value ->
            Card(modifier = Modifier.clickable { onSelect(value) }, shape = RoundedCornerShape(999.dp)) {
                Text(
                    text = value,
                    modifier = Modifier
                        .background(if (value == selected) Color(0xFFFF6B00) else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = if (value == selected) Color(0xFF160A00) else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item { Spacer(modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PolicyRow(
    title: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val icon = if (option == "wifi_only") Icons.Rounded.Wifi else Icons.Rounded.Sync
                Card(modifier = Modifier.clickable { onSelect(option) }, shape = RoundedCornerShape(999.dp)) {
                    Row(
                        modifier = Modifier
                            .background(if (selected == option) Color(0xFFFF6B00) else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = if (selected == option) Color(0xFF160A00) else MaterialTheme.colorScheme.onSurface)
                        Text(option.replace('_', ' '), color = if (selected == option) Color(0xFF160A00) else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceContextCard(uiState: PlayerUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Contexte source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(describeContext(uiState), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Next privilegie toujours la priority queue avant de reprendre ce contexte.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueueSection(
    queue: List<QueuedTrack>,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Priority queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (queue.isEmpty()) {
                Text(
                    "Aucune piste en attente. Les ajouts manuels apparaitront ici avant le contexte source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                queue.forEachIndexed { index, queuedTrack ->
                    ListItem(
                        headlineContent = { Text(queuedTrack.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(queuedTrack.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("${index + 1}")
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onMoveUp(index) }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up") }
                                IconButton(onClick = { onMoveDown(index) }, enabled = index < queue.lastIndex) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down") }
                                IconButton(onClick = { onRemove(index) }) { Icon(Icons.Rounded.Delete, contentDescription = "Remove from queue") }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateSurface(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun PlaylistTrackRow.toTrackListRow(): TrackListRow = TrackListRow(
    id = trackId,
    artistId = null,
    albumId = null,
    title = title,
    artistName = artistName,
    albumTitle = albumTitle,
    contentUri = contentUri,
    durationMs = durationMs,
    isLiked = false,
)

private fun describeContext(uiState: PlayerUiState): String = when (uiState.contextType) {
    "playlist" -> "Depuis une playlist locale"
    "album" -> "Depuis un album"
    "artist" -> "Depuis un artiste"
    "search_results" -> "Depuis les resultats de recherche"
    "recent_tracks" -> "Depuis les pistes recentes"
    else -> "Lecture directe"
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
