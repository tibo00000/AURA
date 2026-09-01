package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.desktop.ui.components.DesktopTrackTable
import com.aura.music.ui.theme.*

@Composable
fun LibraryScreen(
    allTracks: List<TrackListRow>,
    allAlbums: List<AlbumBrowseRow>,
    allArtists: List<ArtistBrowseRow>,
    playlists: List<PlaylistListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onToggleLike: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        "Titres (${allTracks.size})",
        "Albums (${allAlbums.size})",
        "Artistes (${allArtists.size})",
        "Playlists (${playlists.size})"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // En-tête Bibliothèque
        Text(
            text = "Bibliothèque",
            color = PureWhite,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = PureWhite,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = BlazeOrange
                )
            },
            divider = { HorizontalDivider(color = HairlineDark) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            color = if (selectedTab == index) BlazeOrange else PureWhite.copy(alpha = 0.7f),
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        when (selectedTab) {
            0 -> {
                val uiState by orchestrator.uiState.collectAsState()
                DesktopTrackTable(
                    tracks = allTracks,
                    activeTrackId = uiState.currentTrack?.trackId,
                    isPlaying = uiState.playbackState == com.aura.music.domain.player.PlaybackState.Playing,
                    onTrackClick = { track, index ->
                        orchestrator.playTrack(
                            trackId = track.id,
                            contextType = "all",
                            contextId = "all",
                            contextTracks = allTracks.map { orchestrator.toQueuedTrack(it) },
                            startIndex = index
                        )
                    },
                    onToggleLike = onToggleLike,
                    onOpenArtist = { appState.openArtist(it) },
                    onOpenAlbum = { appState.openAlbum(it) }
                )
            }
            1 -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(allAlbums, key = { it.id }) { album ->
                        AlbumGridCard(
                            album = album,
                            onClick = { appState.openAlbum(album.id) }
                        )
                    }
                }
            }
            2 -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(allArtists, key = { it.id }) { artist ->
                        ArtistGridCard(
                            artist = artist,
                            onClick = { appState.openArtist(artist.id) }
                        )
                    }
                }
            }
            3 -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        PlaylistGridCard(
                            playlist = pl,
                            onClick = { appState.openPlaylist(pl.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGridCard(album: AlbumBrowseRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        DesktopArtworkCover(
            coverUri = album.coverUri,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shapeRadius = 8.dp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = album.title,
            color = PureWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = album.artistName,
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ArtistGridCard(artist: ArtistBrowseRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        DesktopArtworkCover(
            coverUri = artist.pictureUri,
            size = 110.dp,
            shapeRadius = 55.dp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = artist.name,
            color = PureWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlaylistGridCard(playlist: PlaylistListRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkGraphite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = BlazeOrange,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = playlist.name,
            color = PureWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${playlist.trackCount} titres",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}
