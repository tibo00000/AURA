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
    var isLoading by remember { mutableStateOf(false) }

    val localArtistTracks = remember(artistId, allLocalTracks) {
        allLocalTracks.filter { it.artistId == artistId || it.displayArtist.equals(artistId.removePrefix("artist:"), ignoreCase = true) }
    }

    val artistName = remember(artistData, localArtistTracks, artistId) {
        artistData?.name ?: localArtistTracks.firstOrNull()?.displayArtist ?: artistId.removePrefix("artist:")
    }

    LaunchedEffect(artistId) {
        if (artistId.startsWith("art_")) {
            isLoading = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val resp = orchestrator.apiService.getArtist(artistId)
                    if (resp.data != null) {
                        artistData = resp.data
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to load remote artist: ${e.message}")
                } finally {
                    isLoading = false
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
            coverUri = artistData?.pictureUri ?: localArtistTracks.firstOrNull()?.coverUri,
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
                    if (!orchestrator.queueManager.state.value.isShuffle) {
                        orchestrator.toggleShuffle()
                    }
                }
            }
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BlazeOrange)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Section Discographie (si artiste en ligne)
                if (artistData?.albums?.isNotEmpty() == true) {
                    item {
                        Text(
                            text = "DISCOGRAPHIE",
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(artistData!!.albums, key = { it.id }) { album ->
                                ArtistAlbumCard(album = album, onClick = { appState.openAlbum(album.id) })
                            }
                        }
                    }
                }

                // Section Morceaux
                item {
                    Text(
                        text = "TITRES",
                        color = PureWhite.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val tracksToShow = if (localArtistTracks.isNotEmpty()) localArtistTracks else {
                        artistData?.topTracks?.map { tt ->
                            TrackListRow(
                                id = tt.id,
                                title = tt.title,
                                displayArtist = tt.displayArtistName,
                                displayAlbum = tt.displayAlbumTitle,
                                durationMs = tt.durationMs.toLong(),
                                coverUri = tt.coverUri,
                                artistId = tt.artistId ?: artistId,
                                albumId = tt.albumId,
                                isLiked = tt.isLiked,
                                isCloudOnly = true
                            )
                        } ?: emptyList()
                    }

                    DesktopTrackTable(
                        tracks = tracksToShow,
                        activeTrackId = uiState.currentTrack?.trackId,
                        isPlaying = uiState.playbackState == com.aura.music.domain.player.PlaybackState.Playing,
                        onTrackClick = { track, index ->
                            orchestrator.playTrack(
                                trackId = track.id,
                                contextType = "artist",
                                contextId = artistId,
                                contextTracks = tracksToShow.map { orchestrator.toQueuedTrack(it) },
                                startIndex = index
                            )
                        },
                        onToggleLike = onToggleLike,
                        onOpenAlbum = { appState.openAlbum(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistAlbumCard(album: AlbumSummary, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        DesktopArtworkCover(coverUri = album.coverUri, size = 120.dp, shapeRadius = 6.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = album.releaseDate?.take(4) ?: "Album",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 11.sp
        )
    }
}
