package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
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
import com.aura.music.desktop.ui.*
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.desktop.ui.components.DesktopTrackTable
import com.aura.music.desktop.utils.DesktopTrackMatcher
import com.aura.music.domain.search.SearchNormalizer
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    // Recherche en ligne déclenchée
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
                SearchNormalizer.normalize(it.artistName ?: "").contains(q)
            }
        }
    }

    val filteredLocalArtists = remember(appState.searchQuery, allArtists) {
        if (appState.searchQuery.isBlank()) allArtists
        else {
            val q = SearchNormalizer.normalize(appState.searchQuery)
            allArtists.filter {
                SearchNormalizer.normalize(it.name).contains(q)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // 1. Barre de recherche
        OutlinedTextField(
            value = appState.searchQuery,
            onValueChange = {
                appState.searchQuery = it
                if (appState.selectedSearchTab == 1 && it.length >= 3) {
                    performOnlineSearch(it)
                }
            },
            placeholder = { Text("Rechercher un titre, un artiste, un album...", color = PureWhite.copy(alpha = 0.4f)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = if (appState.searchQuery.isNotBlank()) BlazeOrange else PureWhite.copy(alpha = 0.5f)
                )
            },
            trailingIcon = {
                if (appState.searchQuery.isNotBlank()) {
                    IconButton(onClick = { appState.searchQuery = "" }) {
                        Icon(imageVector = Icons.Rounded.Close, contentDescription = "Effacer", tint = PureWhite.copy(alpha = 0.6f))
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkGraphite,
                unfocusedContainerColor = OffBlack,
                focusedBorderColor = BlazeOrange,
                unfocusedBorderColor = HairlineDark,
                focusedTextColor = PureWhite,
                unfocusedTextColor = PureWhite
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { appState.isInputFocused = it.isFocused }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Onglets Bibliothèque / En ligne
        val tabs = listOf("Bibliothèque (${filteredLocalTracks.size})", "En ligne")
        TabRow(
            selectedTabIndex = appState.selectedSearchTab,
            containerColor = Color.Transparent,
            contentColor = PureWhite,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[appState.selectedSearchTab]),
                    color = BlazeOrange
                )
            },
            divider = { HorizontalDivider(color = HairlineDark) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = appState.selectedSearchTab == index,
                    onClick = {
                        appState.selectedSearchTab = index
                        if (index == 1 && appState.searchQuery.length >= 3 && onlineResults == null) {
                            performOnlineSearch(appState.searchQuery)
                        }
                    },
                    text = {
                        Text(
                            text = title,
                            color = if (appState.selectedSearchTab == index) BlazeOrange else PureWhite.copy(alpha = 0.7f),
                            fontWeight = if (appState.selectedSearchTab == index) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 3. Contenu de la recherche
        if (appState.selectedSearchTab == 0) {
            // RECHERCHE LOCALE
            if (appState.searchQuery.isNotBlank() && filteredLocalArtists.isNotEmpty()) {
                Text(
                    text = "ARTISTES CORRESPONDANTS",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(filteredLocalArtists, key = { it.id }) { artist ->
                        ArtistSearchCard(artist = artist, onClick = { appState.openArtist(artist.id) })
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            val uiState by orchestrator.uiState.collectAsState()

            DesktopTrackTable(
                tracks = filteredLocalTracks,
                activeTrackId = uiState.currentTrack?.trackId,
                isPlaying = uiState.playbackState == com.aura.music.domain.player.PlaybackState.Playing,
                onTrackClick = { track, index ->
                    orchestrator.playTrack(
                        trackId = track.id,
                        contextType = "search",
                        contextId = appState.searchQuery,
                        contextTracks = filteredLocalTracks.map { orchestrator.toQueuedTrack(it) },
                        startIndex = index
                    )
                },
                onToggleLike = onToggleLike,
                onOpenArtist = { appState.openArtist(it) },
                onOpenAlbum = { appState.openAlbum(it) }
            )
        } else {
            // RECHERCHE EN LIGNE
            when {
                isOnlineLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BlazeOrange)
                    }
                }
                onlineError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = onlineError!!, color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                    }
                }
                onlineResults != null -> {
                    OnlineSearchResultsView(
                        results = onlineResults!!,
                        allTracks = allTracks,
                        orchestrator = orchestrator,
                        appState = appState
                    )
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Tapez au moins 3 lettres pour chercher sur les services en ligne",
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
private fun OnlineSearchResultsView(
    results: SearchResponseData,
    allTracks: List<TrackListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Artistes en ligne
        if (results.artists.isNotEmpty()) {
            item {
                Text(
                    text = "ARTISTES",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(results.artists, key = { it.id }) { artist ->
                        OnlineArtistCard(artist = artist, onClick = { appState.openArtist(artist.id) })
                    }
                }
            }
        }

        // Albums en ligne
        if (results.albums.isNotEmpty()) {
            item {
                Text(
                    text = "ALBUMS",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(results.albums, key = { it.id }) { album ->
                        OnlineAlbumCard(album = album, onClick = { appState.openAlbum(album.id) })
                    }
                }
            }
        }

        // Titres en ligne
        if (results.tracks.isNotEmpty()) {
            item {
                Text(
                    text = "TITRES EN LIGNE",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            itemsIndexed(results.tracks, key = { index, track -> "${track.id}_$index" }) { _, track ->
                val matchedLocal = DesktopTrackMatcher.findMatchingLocalTrack(allTracks, track)
                val isInLibrary = matchedLocal != null
                OnlineTrackRow(
                    track = track,
                    isInLibrary = isInLibrary,
                    onPlay = {
                        if (matchedLocal != null) {
                            orchestrator.playTrack(
                                trackId = matchedLocal.id,
                                contextType = "all",
                                contextId = "all",
                                contextTracks = allTracks.map { orchestrator.toQueuedTrack(it) },
                                startIndex = allTracks.indexOfFirst { it.id == matchedLocal.id }.coerceAtLeast(0)
                            )
                        } else {
                            orchestrator.playOnlineTrack(track, results.tracks)
                        }
                    },
                    onDownload = {
                        orchestrator.triggerTrackDownload(track)
                    },
                    onOpenArtist = {
                        val artistId = track.artistId ?: results.artists.firstOrNull { it.name.equals(track.displayArtistName, ignoreCase = true) }?.id ?: "artist:${track.displayArtistName}"
                        appState.openArtist(artistId)
                    },
                    onOpenAlbum = {
                        val albumId = track.albumId ?: results.albums.firstOrNull { it.title.equals(track.displayAlbumTitle, ignoreCase = true) }?.id ?: "album:${track.displayArtistName}:${track.displayAlbumTitle}"
                        appState.openAlbum(albumId)
                    }
                )
            }
        }
    }
}

@Composable
private fun ArtistSearchCard(artist: ArtistBrowseRow, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        DesktopArtworkCover(coverUri = artist.pictureUri, size = 80.dp, shapeRadius = 40.dp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = artist.name, color = PureWhite, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun OnlineArtistCard(artist: ArtistSummary, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        DesktopArtworkCover(coverUri = artist.pictureUri, size = 90.dp, shapeRadius = 45.dp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = artist.name, color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun OnlineAlbumCard(album: AlbumSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(130.dp).clickable(onClick = onClick)
    ) {
        DesktopArtworkCover(coverUri = album.coverUri, size = 130.dp, shapeRadius = 8.dp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = album.title, color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = album.primaryArtistName, color = PureWhite.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OnlineTrackRow(
    track: TrackSummary,
    isInLibrary: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered) DarkGraphite else DarkGraphite.copy(alpha = 0.5f))
            .hoverable(interactionSource)
            .clickable(onClick = onPlay)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            DesktopArtworkCover(coverUri = track.coverUri, size = 44.dp, shapeRadius = 6.dp)
            if (isHovered) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Écouter",
                        tint = PureWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(2f)) {
            Text(text = track.title, color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(text = track.displayArtistName, color = PureWhite.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, modifier = Modifier.clickable(onClick = onOpenArtist))
        }
        Text(text = track.displayAlbumTitle ?: "-", color = PureWhite.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.weight(1.5f).clickable(onClick = onOpenAlbum), maxLines = 1)

        if (isInLibrary) {
            IconButton(
                onClick = onPlay,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDone,
                    contentDescription = "Dans votre bibliothèque",
                    tint = PureWhite.copy(alpha = 0.7f)
                )
            }
        } else {
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDownload,
                    contentDescription = "Télécharger sur le cloud",
                    tint = BlazeOrange
                )
            }
        }
    }
}
