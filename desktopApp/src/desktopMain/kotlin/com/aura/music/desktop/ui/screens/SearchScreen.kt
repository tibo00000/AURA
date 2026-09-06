package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.AlbumSummary
import com.aura.music.data.network.ArtistSummary
import com.aura.music.data.network.SearchResponseData
import com.aura.music.data.network.TrackSummary
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.desktop.ui.components.DesktopTrackTable
import com.aura.music.desktop.ui.formatDuration
import com.aura.music.desktop.utils.DesktopTrackMatcher
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.search.SearchNormalizer
import com.aura.music.ui.components.ShimmerTrackRow
import com.aura.music.ui.components.rememberShimmerBrush
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class SearchCategoryFilter(val label: String) {
    ALL("Tout"),
    TRACKS("Titres"),
    ARTISTS("Artistes"),
    ALBUMS("Albums")
}

@Composable
fun SearchScreen(
    allTracks: List<TrackListRow>,
    allAlbums: List<AlbumBrowseRow>,
    allArtists: List<ArtistBrowseRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onToggleLike: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var onlineResults by remember { mutableStateOf<SearchResponseData?>(null) }
    var isOnlineLoading by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf(SearchCategoryFilter.ALL) }

    fun performOnlineSearch(query: String) {
        if (query.trim().length < 2) return
        isOnlineLoading = true
        onlineError = null
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val resp = orchestrator.apiService.search(query.trim())
                if (resp.data != null) {
                    onlineResults = resp.data
                } else {
                    onlineError = resp.error?.message ?: "Aucun résultat trouvé"
                }
            } catch (e: Exception) {
                onlineError = "Erreur de connexion : ${e.message}"
            } finally {
                isOnlineLoading = false
            }
        }
    }

    // Filtrage local mémoïsé
    val filteredLocalTracks = remember(appState.searchQuery, allTracks) {
        if (appState.searchQuery.isBlank()) allTracks
        else {
            val q = SearchNormalizer.normalize(appState.searchQuery)
            allTracks.filter {
                SearchNormalizer.normalize(it.title).contains(q) ||
                SearchNormalizer.normalize(it.artistName).contains(q) ||
                (it.albumTitle != null && SearchNormalizer.normalize(it.albumTitle!!).contains(q))
            }
        }
    }

    val filteredLocalAlbums = remember(appState.searchQuery, allAlbums) {
        if (appState.searchQuery.isBlank()) allAlbums
        else {
            val q = SearchNormalizer.normalize(appState.searchQuery)
            allAlbums.filter {
                SearchNormalizer.normalize(it.title).contains(q) ||
                (it.artistName != null && SearchNormalizer.normalize(it.artistName!!).contains(q))
            }
        }
    }

    val filteredLocalArtists = remember(appState.searchQuery, allArtists) {
        if (appState.searchQuery.isBlank()) allArtists
        else {
            val q = SearchNormalizer.normalize(appState.searchQuery)
            allArtists.filter { SearchNormalizer.normalize(it.name).contains(q) }
        }
    }

    val uiState by orchestrator.uiState.collectAsState()
    val isBuffering = uiState.playbackState == PlaybackState.Buffering || uiState.playbackState == PlaybackState.Preparing
    val shimmerBrush = rememberShimmerBrush()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // 1. Barre de recherche moderne Stealth Graphite
        OutlinedTextField(
            value = appState.searchQuery,
            onValueChange = {
                appState.searchQuery = it
                if (appState.searchTab == 1 && it.trim().length >= 2) {
                    performOnlineSearch(it)
                }
            },
            placeholder = {
                Text(
                    "Rechercher un titre, un artiste, un album...",
                    color = PureWhite.copy(alpha = 0.4f),
                    fontSize = 14.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = BlazeOrange,
                    modifier = Modifier.size(24.dp)
                )
            },
            trailingIcon = {
                if (appState.searchQuery.isNotBlank()) {
                    IconButton(onClick = {
                        appState.searchQuery = ""
                        onlineResults = null
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Effacer",
                            tint = PureWhite.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = OffBlack,
                unfocusedContainerColor = OffBlack,
                focusedBorderColor = BlazeOrange,
                unfocusedBorderColor = HairlineDark,
                focusedTextColor = PureWhite,
                unfocusedTextColor = PureWhite,
                cursorColor = BlazeOrange
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { appState.isInputFocused = it.isFocused }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Onglets principaux : Bibliothèque Locale vs Catalogue Cloud
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabRow(
                selectedTabIndex = appState.searchTab,
                containerColor = Color.Transparent,
                contentColor = BlazeOrange,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[appState.searchTab]),
                        color = BlazeOrange,
                        height = 3.dp
                    )
                },
                divider = { HorizontalDivider(color = HairlineDark) },
                modifier = Modifier.weight(1f)
            ) {
                Tab(
                    selected = appState.searchTab == 0,
                    onClick = { appState.searchTab = 0 },
                    text = {
                        Text(
                            text = if (appState.searchQuery.isBlank()) "Ma Bibliothèque" else "Ma Bibliothèque (${filteredLocalTracks.size})",
                            color = if (appState.searchTab == 0) PureWhite else PureWhite.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            fontWeight = if (appState.searchTab == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = appState.searchTab == 1,
                    onClick = {
                        appState.searchTab = 1
                        if (onlineResults == null && appState.searchQuery.trim().length >= 2) {
                            performOnlineSearch(appState.searchQuery)
                        }
                    },
                    text = {
                        Text(
                            text = "Catalogue Cloud / En ligne",
                            color = if (appState.searchTab == 1) PureWhite else PureWhite.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            fontWeight = if (appState.searchTab == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. Chips de filtrage par catégorie (Tout / Titres / Artistes / Albums)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            SearchCategoryFilter.values().forEach { filter ->
                val isSelected = selectedCategory == filter
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        isSelected -> BlazeOrange
                        isHovered -> DarkGraphite
                        else -> OffBlack
                    },
                    border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, HairlineDark),
                    modifier = Modifier
                        .hoverable(interactionSource)
                        .clickable { selectedCategory = filter }
                ) {
                    Text(
                        text = filter.label,
                        color = if (isSelected) PureWhite else PureWhite.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 4. Contenu de la recherche
        if (appState.searchTab == 0) {
            // === VUE BIBLIOTHÈQUE LOCALE ===
            if (appState.searchQuery.isBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = BlazeOrange.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Rechercher dans votre musique",
                            color = PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tapez le nom d'un morceau, d'un artiste ou d'un album pour filtrer votre bibliothèque.",
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                when (selectedCategory) {
                    SearchCategoryFilter.ALL -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Artistes
                            if (filteredLocalArtists.isNotEmpty()) {
                                item {
                                    Text(text = "Artistes", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        items(filteredLocalArtists, key = { it.id }) { artist ->
                                            DesktopArtistItem(
                                                artist = artist,
                                                onClick = {
                                                    appState.selectedArtistId = artist.id
                                                    appState.navigateTo("artist_detail")
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Albums
                            if (filteredLocalAlbums.isNotEmpty()) {
                                item {
                                    Text(text = "Albums", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        items(filteredLocalAlbums, key = { it.id }) { album ->
                                            DesktopAlbumItem(
                                                album = album,
                                                onClick = {
                                                    appState.selectedAlbumId = album.id
                                                    appState.navigateTo("album_detail")
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Morceaux
                            item {
                                Text(
                                    text = "Morceaux (${filteredLocalTracks.size})",
                                    color = PureWhite,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                if (filteredLocalTracks.isNotEmpty()) {
                                    DesktopTrackTable(
                                        tracks = filteredLocalTracks,
                                        currentPlayingTrackId = uiState.currentTrack?.trackId,
                                        isPlaying = uiState.isPlaying,
                                        orchestrator = orchestrator,
                                        database = orchestrator.database,
                                        appState = appState,
                                        onTrackClick = { clickedTrack ->
                                            orchestrator.playTrack(
                                                trackId = clickedTrack.id,
                                                contextType = "search",
                                                contextId = appState.searchQuery,
                                                contextTracks = filteredLocalTracks.map { orchestrator.toQueuedTrack(it) },
                                                startIndex = filteredLocalTracks.indexOf(clickedTrack).coerceAtLeast(0)
                                            )
                                        },
                                        onToggleLike = onToggleLike
                                    )
                                } else {
                                    Text(
                                        text = "Aucun morceau correspondant à \"${appState.searchQuery}\".",
                                        color = PureWhite.copy(alpha = 0.5f),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }

                    SearchCategoryFilter.TRACKS -> {
                        if (filteredLocalTracks.isNotEmpty()) {
                            DesktopTrackTable(
                                tracks = filteredLocalTracks,
                                currentPlayingTrackId = uiState.currentTrack?.trackId,
                                isPlaying = uiState.isPlaying,
                                orchestrator = orchestrator,
                                database = orchestrator.database,
                                appState = appState,
                                onTrackClick = { clickedTrack ->
                                    orchestrator.playTrack(
                                        trackId = clickedTrack.id,
                                        contextType = "search",
                                        contextId = appState.searchQuery,
                                        contextTracks = filteredLocalTracks.map { orchestrator.toQueuedTrack(it) },
                                        startIndex = filteredLocalTracks.indexOf(clickedTrack).coerceAtLeast(0)
                                    )
                                },
                                onToggleLike = onToggleLike
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Aucun morceau trouvé.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                            }
                        }
                    }

                    SearchCategoryFilter.ARTISTS -> {
                        if (filteredLocalArtists.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(130.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredLocalArtists, key = { it.id }) { artist ->
                                    DesktopArtistItem(
                                        artist = artist,
                                        onClick = {
                                            appState.selectedArtistId = artist.id
                                            appState.navigateTo("artist_detail")
                                        }
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Aucun artiste trouvé.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                            }
                        }
                    }

                    SearchCategoryFilter.ALBUMS -> {
                        if (filteredLocalAlbums.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(140.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredLocalAlbums, key = { it.id }) { album ->
                                    DesktopAlbumItem(
                                        album = album,
                                        onClick = {
                                            appState.selectedAlbumId = album.id
                                            appState.navigateTo("album_detail")
                                        }
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Aucun album trouvé.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        } else {
            // === VUE CATALOGUE CLOUD / EN LIGNE ===
            if (isOnlineLoading) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) {
                        ShimmerTrackRow(brush = shimmerBrush)
                    }
                }
            } else if (onlineError != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = onlineError!!, color = PureWhite.copy(alpha = 0.7f), fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { performOnlineSearch(appState.searchQuery) },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                        ) {
                            Text("Réessayer")
                        }
                    }
                }
            } else if (onlineResults != null) {
                val results = onlineResults!!

                when (selectedCategory) {
                    SearchCategoryFilter.ALL -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Artistes distants
                            if (results.artists.isNotEmpty()) {
                                item {
                                    Text(text = "Artistes", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        items(results.artists, key = { it.id }) { artist ->
                                            DesktopOnlineArtistItem(
                                                artist = artist,
                                                onClick = {
                                                    appState.selectedArtistId = artist.id
                                                    appState.navigateTo("artist_detail")
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Albums distants
                            if (results.albums.isNotEmpty()) {
                                item {
                                    Text(text = "Albums", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        items(results.albums, key = { it.id }) { album ->
                                            DesktopOnlineAlbumItem(
                                                album = album,
                                                onClick = {
                                                    appState.selectedAlbumId = album.id
                                                    appState.navigateTo("album_detail")
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Titres distants en streaming & téléchargement
                            if (results.tracks.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Titres en streaming & téléchargement (${results.tracks.size})",
                                        color = PureWhite,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        results.tracks.forEach { track ->
                                            val isMatch = DesktopTrackMatcher.hasLocalMatch(track, allTracks)
                                            val isCurrentPlaying = uiState.currentTrack?.trackId == track.id
                                            DesktopOnlineTrackRow(
                                                track = track,
                                                isLocalMatch = isMatch,
                                                isCurrentPlaying = isCurrentPlaying,
                                                isPlaying = uiState.isPlaying && isCurrentPlaying,
                                                isBuffering = isBuffering && isCurrentPlaying,
                                                onPlay = {
                                                    orchestrator.playOnlineTrack(track, results.tracks)
                                                },
                                                onDownload = {
                                                    orchestrator.triggerTrackDownload(track)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SearchCategoryFilter.TRACKS -> {
                        if (results.tracks.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(results.tracks, key = { it.id }) { track ->
                                    val isMatch = DesktopTrackMatcher.hasLocalMatch(track, allTracks)
                                    val isCurrentPlaying = uiState.currentTrack?.trackId == track.id
                                    DesktopOnlineTrackRow(
                                        track = track,
                                        isLocalMatch = isMatch,
                                        isCurrentPlaying = isCurrentPlaying,
                                        isPlaying = uiState.isPlaying && isCurrentPlaying,
                                        isBuffering = isBuffering && isCurrentPlaying,
                                        onPlay = {
                                            orchestrator.playOnlineTrack(track, results.tracks)
                                        },
                                        onDownload = {
                                            orchestrator.triggerTrackDownload(track)
                                        }
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Aucun titre trouvé en ligne.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                            }
                        }
                    }

                    SearchCategoryFilter.ARTISTS -> {
                        if (results.artists.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(130.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(results.artists, key = { it.id }) { artist ->
                                    DesktopOnlineArtistItem(
                                        artist = artist,
                                        onClick = {
                                            appState.selectedArtistId = artist.id
                                            appState.navigateTo("artist_detail")
                                        }
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Aucun artiste trouvé en ligne.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                            }
                        }
                    }

                    SearchCategoryFilter.ALBUMS -> {
                        if (results.albums.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(140.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(results.albums, key = { it.id }) { album ->
                                    DesktopOnlineAlbumItem(
                                        album = album,
                                        onClick = {
                                            appState.selectedAlbumId = album.id
                                            appState.navigateTo("album_detail")
                                        }
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Aucun album trouvé en ligne.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.CloudQueue,
                            contentDescription = null,
                            tint = BlazeOrange.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Catalogue Cloud AURA & Deezer",
                            color = PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Entrez au moins 2 caractères pour rechercher parmi des millions de titres.",
                            color = PureWhite.copy(alpha = 0.4f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopArtistItem(artist: ArtistBrowseRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DesktopArtworkCover(coverUri = artist.pictureUri, size = 96.dp, shapeRadius = 48.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = artist.name,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Artiste",
            color = PureWhite.copy(alpha = 0.45f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun DesktopAlbumItem(album: AlbumBrowseRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(135.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        DesktopArtworkCover(coverUri = album.coverUri, size = 111.dp, shapeRadius = 8.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = album.title,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (album.artistName != null) {
            Text(
                text = album.artistName!!,
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DesktopOnlineArtistItem(artist: ArtistSummary, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DesktopArtworkCover(coverUri = artist.pictureUri, size = 96.dp, shapeRadius = 48.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = artist.name,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Artiste",
            color = PureWhite.copy(alpha = 0.45f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun DesktopOnlineAlbumItem(album: AlbumSummary, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(135.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        DesktopArtworkCover(coverUri = album.coverUri, size = 111.dp, shapeRadius = 8.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = album.title,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = album.primaryArtistName,
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DesktopOnlineTrackRow(
    track: TrackSummary,
    isLocalMatch: Boolean,
    isCurrentPlaying: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isCurrentPlaying -> BlazeOrange.copy(alpha = 0.12f)
                    isHovered -> DarkGraphite.copy(alpha = 0.6f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interactionSource)
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icône Play au survol ou Pochette avec overlay
        Box(
            modifier = Modifier.size(46.dp),
            contentAlignment = Alignment.Center
        ) {
            DesktopArtworkCover(coverUri = track.coverUri, size = 46.dp, shapeRadius = 6.dp)

            if (isCurrentPlaying || isHovered) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrentPlaying && isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = BlazeOrange,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isCurrentPlaying && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = BlazeOrange,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Titre + badge explicite
        Column(modifier = Modifier.weight(2f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.title,
                    color = if (isCurrentPlaying) BlazeOrange else PureWhite,
                    fontSize = 13.sp,
                    fontWeight = if (isCurrentPlaying) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.isExplicit) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = PureWhite.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(2.dp)
                    ) {
                        Text(
                            text = "E",
                            color = PureWhite.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Text(
                text = track.displayArtistName,
                color = PureWhite.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Album
        Text(
            text = track.displayAlbumTitle ?: "-",
            color = PureWhite.copy(alpha = 0.45f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.5f).padding(horizontal = 8.dp)
        )

        // Durée formatée
        Text(
            text = formatDuration(track.durationMs.toLong()),
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier.width(50.dp),
            textAlign = TextAlign.End
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Statut Bibliothèque ou Bouton Télécharger
        if (isLocalMatch) {
            Surface(
                color = DarkGraphite,
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "En bibliothèque",
                        color = Color(0xFF00E676),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "Télécharger sur votre compte",
                    tint = BlazeOrange,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

