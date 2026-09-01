package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.desktop.ui.components.DesktopTrackTable
import com.aura.music.desktop.utils.DesktopTrackMatcher
import com.aura.music.domain.search.SearchNormalizer
import com.aura.music.ui.components.ShimmerTrackRow
import com.aura.music.ui.components.rememberShimmerBrush
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
    val shimmerBrush = rememberShimmerBrush()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // 1. Barre de recherche stylisée
        OutlinedTextField(
            value = appState.searchQuery,
            onValueChange = {
                appState.searchQuery = it
                if (appState.searchTab == 1 && it.length >= 2) {
                    performOnlineSearch(it)
                }
            },
            placeholder = { Text("Rechercher des titres, artistes, albums...", color = PureWhite.copy(alpha = 0.4f)) },
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
                    IconButton(onClick = { appState.searchQuery = "" }) {
                        Icon(imageVector = Icons.Rounded.Close, contentDescription = "Effacer", tint = PureWhite.copy(alpha = 0.6f))
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

        // 2. Onglets Bibliothèque / En ligne
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
            divider = { HorizontalDivider(color = HairlineDark) }
        ) {
            Tab(
                selected = appState.searchTab == 0,
                onClick = { appState.searchTab = 0 },
                text = {
                    Text(
                        text = "Bibliothèque Locale (${filteredLocalTracks.size})",
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
                    if (onlineResults == null && appState.searchQuery.length >= 2) {
                        performOnlineSearch(appState.searchQuery)
                    }
                },
                text = {
                    Text(
                        text = "Recherche en Ligne",
                        color = if (appState.searchTab == 1) PureWhite else PureWhite.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        fontWeight = if (appState.searchTab == 1) FontWeight.Bold else FontWeight.Medium
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 3. Contenu de la recherche
        if (appState.searchTab == 0) {
            // Vue Locale
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Section Artistes Locaux
                if (filteredLocalArtists.isNotEmpty()) {
                    item {
                        Text(text = "Artistes", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(filteredLocalArtists, key = { it.id }) { artist ->
                                DesktopArtistItem(
                                    artist = artist,
                                    onClick = {
                                        appState.navigateTo("artist_detail")
                                        appState.selectedArtistId = artist.id
                                    }
                                )
                            }
                        }
                    }
                }

                // Section Albums Locaux
                if (filteredLocalAlbums.isNotEmpty()) {
                    item {
                        Text(text = "Albums", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(filteredLocalAlbums, key = { it.id }) { album ->
                                DesktopAlbumItem(
                                    album = album,
                                    onClick = {
                                        appState.navigateTo("album_detail")
                                        appState.selectedAlbumId = album.id
                                    }
                                )
                            }
                        }
                    }
                }

                // Section Titres Locaux
                item {
                    Text(text = "Morceaux", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                        Text(text = "Aucun morceau trouvé dans votre bibliothèque locale.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                    }
                }
            }
        } else {
            // Vue En Ligne (avec Shimmers unifiés)
            if (isOnlineLoading) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) {
                        ShimmerTrackRow(brush = shimmerBrush)
                    }
                }
            } else if (onlineError != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Rounded.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Artistes Distants
                    if (results.artists.isNotEmpty()) {
                        item {
                            Text(text = "Artistes", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(results.artists, key = { it.id }) { artist ->
                                    DesktopOnlineArtistItem(
                                        artist = artist,
                                        onClick = {
                                            appState.navigateTo("artist_detail")
                                            appState.selectedArtistId = artist.id
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Albums Distants
                    if (results.albums.isNotEmpty()) {
                        item {
                            Text(text = "Albums", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(results.albums, key = { it.id }) { album ->
                                    DesktopOnlineAlbumItem(
                                        album = album,
                                        onClick = {
                                            appState.navigateTo("album_detail")
                                            appState.selectedAlbumId = album.id
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Titres Distants
                    if (results.tracks.isNotEmpty()) {
                        item {
                            Text(text = "Titres en streaming & téléchargement", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "Entrez au moins 2 caractères pour rechercher sur le catalogue AURA / Deezer.", color = PureWhite.copy(alpha = 0.4f), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun DesktopArtistItem(artist: ArtistBrowseRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(110.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DesktopArtworkCover(coverUri = artist.pictureUri, size = 90.dp, shapeRadius = 45.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = artist.name, color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DesktopAlbumItem(album: AlbumBrowseRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(120.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        DesktopArtworkCover(coverUri = album.coverUri, size = 120.dp, shapeRadius = 8.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = album.title, color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (album.artistName != null) {
            Text(text = album.artistName!!, color = PureWhite.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DesktopOnlineArtistItem(artist: ArtistSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(110.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DesktopArtworkCover(coverUri = artist.pictureUri, size = 90.dp, shapeRadius = 45.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = artist.name, color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DesktopOnlineAlbumItem(album: AlbumSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(120.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        DesktopArtworkCover(coverUri = album.coverUri, size = 120.dp, shapeRadius = 8.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = album.title, color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = album.displayArtistName, color = PureWhite.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DesktopOnlineTrackRow(
    track: TrackSummary,
    isLocalMatch: Boolean,
    isCurrentPlaying: Boolean,
    isPlaying: Boolean,
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
        DesktopArtworkCover(coverUri = track.coverUri, size = 44.dp, shapeRadius = 4.dp)
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isCurrentPlaying) BlazeOrange else PureWhite,
                fontSize = 13.sp,
                fontWeight = if (isCurrentPlaying) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${track.displayArtistName} • ${track.displayAlbumTitle ?: ""}",
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isLocalMatch) {
            Surface(
                color = DarkGraphite,
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Dans la bibliothèque", color = Color(0xFF4CAF50), fontSize = 11.sp)
                }
            }
        } else {
            IconButton(onClick = onDownload) {
                Icon(imageVector = Icons.Rounded.Download, contentDescription = "Télécharger", tint = BlazeOrange)
            }
        }
    }
}
