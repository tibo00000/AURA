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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.AlbumSummary
import com.aura.music.data.network.ArtistDetailResponseData
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.desktop.ui.components.DesktopHeroHeader
import com.aura.music.desktop.ui.components.DesktopTrackTable
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ArtistDetailScreen(
    artistId: String,
    allLocalTracks: List<TrackListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onToggleLike: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var artistData by remember { mutableStateOf<ArtistDetailResponseData?>(null) }
    var resolvedCoverUri by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val localArtistTracks = remember(artistId, allLocalTracks) {
        allLocalTracks.filter { it.artistId == artistId || it.artistName.equals(artistId.removePrefix("artist:"), ignoreCase = true) }
    }

    val artistName = remember(artistData, localArtistTracks, artistId) {
        artistData?.name ?: localArtistTracks.firstOrNull()?.artistName ?: artistId.removePrefix("artist:")
    }

    // Résolution hybride automatique & mise en cache Room (Point 3 de l'audit)
    LaunchedEffect(artistId) {
        if (artistId.startsWith("art_")) {
            isLoading = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val resp = orchestrator.apiService.getArtist(artistId)
                    if (resp.data != null) {
                        artistData = resp.data
                        resolvedCoverUri = resp.data?.pictureUri
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to load remote artist: ${e.message}")
                } finally {
                    isLoading = false
                }
            }
        } else {
            // Artiste local sans ID backend : appel de résolution non-bloquant
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val sampleTrack = localArtistTracks.firstOrNull()?.title
                    val resp = orchestrator.apiService.resolveArtist(name = artistName, trackTitle = sampleTrack)
                    val resolved = resp.data
                    if (resolved != null) {
                        resolvedCoverUri = resolved.pictureUri
                        if (!resolved.pictureUri.isNullOrBlank()) {
                            val now = System.currentTimeMillis()
                            orchestrator.database.artistDao().updateArtwork(
                                artistId = artistId,
                                pictureUri = resolved.pictureUri!!,
                                artworkOrigin = "backend_resolve",
                                resolvedAt = now,
                                updatedAt = now
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Ignore transient resolution errors
                }
            }
        }
    }

    val uiState by orchestrator.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        DesktopHeroHeader(
            tag = "ARTISTE",
            title = artistName,
            subtitle = if (artistData != null) "${artistData!!.topTracks.size} titres populaires" else "${localArtistTracks.size} morceaux dans votre bibliothèque",
            coverUri = resolvedCoverUri ?: artistData?.pictureUri ?: localArtistTracks.firstOrNull()?.coverUri,
            onBack = { appState.navigateBack() },
            onPlayAll = {
                if (localArtistTracks.isNotEmpty()) {
                    orchestrator.playTrack(
                        trackId = localArtistTracks.first().id,
                        contextType = "artist",
                        contextId = artistId,
                        contextTracks = localArtistTracks.map { orchestrator.toQueuedTrack(it) },
                        startIndex = 0
                    )
                }
            },
            onShuffleAll = {
                if (localArtistTracks.isNotEmpty()) {
                    orchestrator.playTrack(
                        trackId = localArtistTracks.first().id,
                        contextType = "artist",
                        contextId = artistId,
                        contextTracks = localArtistTracks.map { orchestrator.toQueuedTrack(it) },
                        startIndex = 0
                    )
                    orchestrator.toggleShuffle()
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            // Discographie en ligne si disponible
            if (artistData?.albums?.isNotEmpty() == true) {
                item {
                    Text(
                        text = "Discographie",
                        color = PureWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(artistData!!.albums, key = { it.id }) { album ->
                            DesktopArtistAlbumCard(
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

            // Morceaux
            item {
                Text(
                    text = if (artistData != null) "Titres populaires" else "Morceaux locaux",
                    color = PureWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(14.dp))

                if (localArtistTracks.isNotEmpty()) {
                    DesktopTrackTable(
                        tracks = localArtistTracks,
                        currentPlayingTrackId = uiState.currentTrack?.trackId,
                        isPlaying = uiState.isPlaying,
                        orchestrator = orchestrator,
                        database = orchestrator.database,
                        appState = appState,
                        onTrackClick = { clickedTrack ->
                            orchestrator.playTrack(
                                trackId = clickedTrack.id,
                                contextType = "artist",
                                contextId = artistId,
                                contextTracks = localArtistTracks.map { orchestrator.toQueuedTrack(it) },
                                startIndex = localArtistTracks.indexOf(clickedTrack).coerceAtLeast(0)
                            )
                        },
                        onToggleLike = onToggleLike
                    )
                } else if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BlazeOrange)
                    }
                } else {
                    Text(
                        text = "Aucun morceau trouvé pour cet artiste.",
                        color = PureWhite.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopArtistAlbumCard(
    album: AlbumSummary,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        DesktopArtworkCover(
            coverUri = album.coverUri,
            size = 130.dp,
            cornerRadius = 6.dp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (album.releaseDate != null) {
            Text(
                text = album.releaseDate.take(4),
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
        }
    }
}
