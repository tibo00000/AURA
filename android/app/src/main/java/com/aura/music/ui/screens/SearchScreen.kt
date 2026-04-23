package com.aura.music.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import coil.compose.AsyncImage
import com.aura.music.AuraApplication
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.ArtistSummary
import com.aura.music.data.network.AlbumSummary
import com.aura.music.data.network.TrackSummary
import com.aura.music.data.repository.BestMatchResult
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.SearchRepository
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.search.SearchViewModel
import com.aura.music.ui.search.SearchViewModelFactory
import com.aura.music.ui.screens.EmptyStateSurface
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.HairlineDark
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: LocalLibraryRepository,
    refreshToken: Int,
    onRequestAudioPermission: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as AuraApplication
    val auraApiService = application.container.auraApiService
    val searchRepository = SearchRepository(repository, auraApiService)
    
    val viewModel: SearchViewModel = viewModel(
        factory = SearchViewModelFactory(searchRepository)
    )
    
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) } // 0 = Bibliothèque, 1 = En ligne

    LaunchedEffect(refreshToken) {
        // Any permission refresh can be handled here if needed
    }

    RouteScaffold(title = "Recherche") {
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
                    onSubmit = viewModel::submitSearch,
                    onClear = viewModel::clearQuery,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Local suggestions dropdown (only during typing with 3+ chars)
            if (uiState.shouldShowSuggestions) {
                item {
                    LocalSuggestionsSection(
                        result = uiState.localSuggestions,
                        onSelectTrack = { track ->
                            viewModel.selectSuggestion(track.title)
                        },
                        onSelectArtist = { artist ->
                            viewModel.selectSuggestion(artist.name)
                        },
                        onSelectAlbum = { album ->
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
                        onSelectQuery = viewModel::selectRecentQuery,
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

                // Best match
                if (result.bestMatch != null) {
                    item {
                        BestMatchSection(
                            bestMatch = result.bestMatch,
                            onPlayTrack = { track ->
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
                                    "search_best_match"
                                )
                            },
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // Tab navigation
                item {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        androidx.compose.material3.Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Bibliothèque") }
                        )
                        androidx.compose.material3.Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("En ligne") }
                        )
                    }
                }

                // Local library tab content
                if (selectedTab == 0) {
                    if (result.localTracks.isNotEmpty() || result.localArtists.isNotEmpty() || result.localAlbums.isNotEmpty()) {
                        item {
                            LocalLibrarySearchTab(
                                tracks = result.localTracks,
                                artists = result.localArtists,
                                albums = result.localAlbums,
                                onPlayTrack = { track, allTracks ->
                                    onPlayTrackInList(track, allTracks, "search_local")
                                },
                                onLikeTrack = { trackId, isLiked ->
                                    viewModel.likeLocalTrack(trackId, isLiked)
                                },
                                onAddToPlaylist = { /* TODO: playlist dialog */ },
                                onOpenArtist = onOpenArtist,
                                onOpenAlbum = onOpenAlbum,
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
                if (selectedTab == 1) {
                    if (result.onlineTracks.isNotEmpty() || result.onlineArtists.isNotEmpty() || result.onlineAlbums.isNotEmpty()) {
                        item {
                            OnlineSearchTab(
                                tracks = result.onlineTracks,
                                artists = result.onlineArtists,
                                albums = result.onlineAlbums,
                                onPlayTrack = { track ->
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
                                onAddToPlaylist = { /* TODO: playlist dialog */ },
                                onOpenArtist = onOpenArtist,
                                onOpenAlbum = onOpenAlbum,
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
                    SharedTrackRowItem(
                        title = track.title,
                        subtitle = track.artistName,
                        coverUri = track.coverUri,
                        onClick = { onPlayTrack(track, tracks) },
                        showCover = true,
                        contextType = "standard",
                        onLike = { onLikeTrack(track.id, track.isLiked) },
                        onUnlike = { onLikeTrack(track.id, track.isLiked) },
                        onAddToPlaylist = { onAddToPlaylist(track) }
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
                    SharedTrackRowItem(
                        title = track.title,
                        subtitle = track.displayArtistName,
                        coverUri = track.coverUri,
                        onClick = { onPlayTrack(track) },
                        showCover = true,
                        contextType = "search_online",
                        onAddToPlaylist = { onAddToPlaylist(track) }
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
                        Card(
                            modifier = Modifier
                                .size(width = 168.dp, height = 210.dp)
                                .clickable { onOpenArtist(artist.id) },
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .background(
                                            Brush.linearGradient(listOf(Color(0xFF792BEE), HairlineDark)),
                                            CircleShape
                                        ),
                                )
                                Text(
                                    artist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
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
                        Card(
                            modifier = Modifier
                                .size(width = 172.dp, height = 220.dp)
                                .clickable { onOpenAlbum(album.id) },
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .background(
                                            Brush.linearGradient(listOf(Color(0xFFFF9E00), HairlineDark)),
                                            RoundedCornerShape(20.dp)
                                        ),
                                )
                                Text(
                                    album.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    album.primaryArtistName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
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
    }
}

/**
 * Best match hero card.
 */
@Composable
private fun BestMatchSection(
    bestMatch: BestMatchResult,
    onPlayTrack: (TrackSummary) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Meilleur résultat",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )

        when (bestMatch) {
            is BestMatchResult.OnlineTrack -> {
                HeroTrackCard(
                    track = bestMatch.track,
                    onPlay = { onPlayTrack(bestMatch.track) }
                )
            }
            is BestMatchResult.OnlineArtist -> {
                HeroArtistCard(
                    artist = bestMatch.artist,
                    onOpen = { onOpenArtist(bestMatch.artist.id) }
                )
            }
            is BestMatchResult.OnlineAlbum -> {
                HeroAlbumCard(
                    album = bestMatch.album,
                    onOpen = { onOpenAlbum(bestMatch.album.id) }
                )
            }
            is BestMatchResult.LocalTrack -> {
                HeroLocalTrackCard(
                    track = bestMatch.track,
                    onPlay = { }
                )
            }
            is BestMatchResult.LocalArtist -> {
                HeroLocalArtistCard(
                    artist = bestMatch.artist,
                    onOpen = { onOpenArtist(bestMatch.artist.id) }
                )
            }
            is BestMatchResult.LocalAlbum -> {
                HeroLocalAlbumCard(
                    album = bestMatch.album,
                    onOpen = { onOpenAlbum(bestMatch.album.id) }
                )
            }
        }
    }
}

@Composable
private fun HeroTrackCard(track: TrackSummary, onPlay: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(ElevatedGraphite, HairlineDark)))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (track.coverUri != null) {
                coil.compose.AsyncImage(
                    model = track.coverUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
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
                    track.displayArtistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.Search, contentDescription = "Lire", modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun HeroArtistCard(artist: ArtistSummary, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFF792BEE), HairlineDark)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                artist.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text("Artiste", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HeroAlbumCard(album: AlbumSummary, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFFFF9E00), HairlineDark)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                album.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                album.primaryArtistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (album.trackCount != null) {
                Text(
                    "${album.trackCount} piste(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Local library section with tabs.
 */
@Composable
private fun LocalLibrarySection(
    tracks: List<TrackListRow>,
    artists: List<com.aura.music.data.local.ArtistBrowseRow>,
    albums: List<com.aura.music.data.local.AlbumBrowseRow>,
    onPlayTrack: (TrackListRow, List<TrackListRow>) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            "Dans votre bibliothèque",
            "Résultats locaux"
        )

        if (tracks.isNotEmpty()) {
            Text(
                "Titres",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            tracks.take(5).forEach { track ->
                SharedTrackRowItem(
                    title = track.title,
                    subtitle = track.artistName,
                    coverUri = track.coverUri,
                    onClick = { onPlayTrack(track, tracks) }
                )
            }
        }

        if (artists.isNotEmpty()) {
            Text(
                "Artiste",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            BrowseArtistRail(artists, onOpenArtist)
        }

        if (albums.isNotEmpty()) {
            Text(
                "Album",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            BrowseAlbumRail(albums, onOpenAlbum)
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
                    if (track.coverUri != null) {
                        coil.compose.AsyncImage(
                            model = track.coverUri,
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
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .size(width = 168.dp, height = 210.dp)
                        .clickable { onOpenArtist(artist.id) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF792BEE), HairlineDark)),
                                    androidx.compose.foundation.shape.CircleShape
                                ),
                        )
                        Text(
                            artist.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .size(width = 172.dp, height = 220.dp)
                        .clickable { onOpenAlbum(album.id) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFFFF9E00), HairlineDark)),
                                    androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                                ),
                        )
                        Text(
                            album.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            album.primaryArtistName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.size(16.dp)) }
        }
    }
}

/**
 * Hero card for local tracks.
 */
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
            if (track.coverUri != null) {
                AsyncImage(
                    model = track.coverUri,
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
 * Hero card for local artists.
 */
@Composable
private fun HeroLocalArtistCard(
    artist: com.aura.music.data.local.ArtistBrowseRow,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFF792BEE), HairlineDark)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (artist.pictureUri != null) {
                AsyncImage(
                    model = artist.pictureUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No Image", color = Color.White)
                }
            }

            Text(
                artist.name,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/**
 * Hero card for local albums.
 */
@Composable
private fun HeroLocalAlbumCard(
    album: com.aura.music.data.local.AlbumBrowseRow,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFFFF9E00), HairlineDark)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (album.coverUri != null) {
                AsyncImage(
                    model = album.coverUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Text(
                album.title?: "Unknown",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Text(
                album.artistName?: "Unknown",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
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
