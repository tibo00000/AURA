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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.HistoryItemResponse
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.desktop.ui.handClickable
import com.aura.music.ui.components.rememberShimmerBrush
import com.aura.music.ui.components.shimmer
import com.aura.music.ui.theme.*
import java.time.LocalTime

sealed interface DesktopResumeItem {
    object Favorites : DesktopResumeItem
    data class Playlist(val playlist: PlaylistListRow) : DesktopResumeItem
    data class Album(val album: AlbumBrowseRow) : DesktopResumeItem
}

@Composable
fun HomeScreen(
    allTracks: List<TrackListRow>,
    likedTracks: List<TrackListRow>,
    playlists: List<PlaylistListRow>,
    allAlbums: List<AlbumBrowseRow> = emptyList(),
    history: List<HistoryItemResponse> = emptyList(),
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val greeting = remember {
        val hour = LocalTime.now().hour
        when (hour) {
            in 5..17 -> "Bonjour"
            in 18..22 -> "Bonsoir"
            else -> "Bonne nuit"
        }
    }

    // Reconstruction des conteneurs récemment lancés (Favoris, Playlists, Albums) comme sur mobile
    val resumeItems by remember {
        derivedStateOf {
            val items = mutableListOf<DesktopResumeItem>()
            if (likedTracks.isNotEmpty()) {
                items.add(DesktopResumeItem.Favorites)
            }
            playlists.forEach { items.add(DesktopResumeItem.Playlist(it)) }
            allAlbums.take(8).forEach { items.add(DesktopResumeItem.Album(it)) }
            items
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // 1. En-tête Salutation (épuré, sans les 4 cartes statistiques)
        item {
            Column {
                Text(
                    text = greeting,
                    color = PureWhite,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Bienvenue sur votre lecteur audio haute fidélité AURA",
                    color = PureWhite.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
            }
        }

        // 2. Récemment Écoutés / Reprendre (Conteneurs : Favoris, Playlists, Albums comme sur mobile)
        if (resumeItems.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "REPRENDRE L'ÉCOUTE",
                        color = PureWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(resumeItems) { item ->
                            when (item) {
                                is DesktopResumeItem.Favorites -> {
                                    ResumeFavoritesCard(
                                        trackCount = likedTracks.size,
                                        onClick = { appState.navigateToRoot("favorites") }
                                    )
                                }
                                is DesktopResumeItem.Playlist -> {
                                    ResumePlaylistCard(
                                        playlist = item.playlist,
                                        onClick = { appState.openPlaylist(item.playlist.id) }
                                    )
                                }
                                is DesktopResumeItem.Album -> {
                                    ResumeAlbumCard(
                                        album = item.album,
                                        onClick = { appState.openAlbum(item.album.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (isLoading) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "REPRENDRE L'ÉCOUTE",
                        color = PureWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    val shimmerBrush = rememberShimmerBrush()
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(6) {
                            ShimmerResumeCard(brush = shimmerBrush)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShimmerResumeCard(brush: Brush) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OffBlack)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .shimmer(brush, RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(14.dp)
                .shimmer(brush, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .height(11.dp)
                .shimmer(brush, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
private fun ResumeFavoritesCard(
    trackCount: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .handClickable(interactionSource = interactionSource, onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        listOf(BlazeOrange, Color(0xFFFF2D55))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(52.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Coups de Cœur",
            color = PureWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$trackCount titres",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ResumePlaylistCard(
    playlist: PlaylistListRow,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .handClickable(interactionSource = interactionSource, onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Playlist • ${playlist.itemCount} titres",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ResumeAlbumCard(
    album: AlbumBrowseRow,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .handClickable(interactionSource = interactionSource, onClick = onClick)
            .padding(12.dp)
    ) {
        DesktopArtworkCover(
            coverUri = album.coverUri,
            size = 136.dp,
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = album.artistName ?: "Album",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
