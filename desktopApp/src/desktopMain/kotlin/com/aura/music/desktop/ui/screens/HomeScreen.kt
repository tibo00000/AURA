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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.HistoryItemResponse
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.ui.theme.*
import java.time.LocalTime

@Composable
fun HomeScreen(
    allTracks: List<TrackListRow>,
    likedTracks: List<TrackListRow>,
    playlists: List<PlaylistListRow>,
    history: List<HistoryItemResponse>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // 1. En-tête Salutation
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

        // 2. Cartes de statistiques
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatCard(
                    icon = Icons.Rounded.LibraryMusic,
                    label = "Titres",
                    value = "${allTracks.size}",
                    color = BlazeOrange,
                    onClick = { appState.navigateToRoot("library") },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Rounded.Favorite,
                    label = "Favoris",
                    value = "${likedTracks.size}",
                    color = BlazeOrange,
                    onClick = { appState.navigateToRoot("favorites") },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Rounded.QueueMusic,
                    label = "Playlists",
                    value = "${playlists.size}",
                    color = PureWhite,
                    onClick = { appState.navigateToRoot("library") },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Rounded.CloudSync,
                    label = "Stockage Cloud",
                    value = "${allTracks.count { it.isCloudOnly }} en ligne",
                    color = BlazeOrange,
                    onClick = { appState.navigateToRoot("cloud_sync") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 3. Récemment Écoutés
        if (history.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RÉCEMMENT ÉCOUTÉS",
                            color = PureWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val historyTracks = history.mapNotNull { h -> allTracks.firstOrNull { it.id == h.trackId } }.distinctBy { it.id }.take(10)

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(historyTracks, key = { it.id }) { track ->
                            RecentTrackCard(
                                track = track,
                                onPlay = {
                                    orchestrator.playTrack(
                                        trackId = track.id,
                                        contextType = "all",
                                        contextId = "all",
                                        contextTracks = allTracks.map { orchestrator.toQueuedTrack(it) },
                                        startIndex = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                                    )
                                },
                                onOpenArtist = { appState.openArtist(track.artistId) }
                            )
                        }
                    }
                }
            }
        }

        // 4. Vos Playlists Rapides
        if (playlists.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "VOS PLAYLISTS",
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
                        items(playlists.take(8), key = { it.id }) { pl ->
                            PlaylistQuickCard(
                                playlist = pl,
                                onClick = { appState.openPlaylist(pl.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .hoverable(interactionSource)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isHovered) DarkGraphite else OffBlack
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = value,
                    color = PureWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = label,
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun RecentTrackCard(
    track: TrackListRow,
    onPlay: () -> Unit,
    onOpenArtist: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onPlay)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(126.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            DesktopArtworkCover(
                coverUri = track.coverUri,
                size = 126.dp,
                shapeRadius = 8.dp
            )
            if (isHovered) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(BlazeOrange),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Lire",
                        tint = PureWhite,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = track.title,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.displayArtist,
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onOpenArtist() }
        )
    }
}

@Composable
private fun PlaylistQuickCard(
    playlist: PlaylistListRow,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DarkGraphite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = BlazeOrange,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = playlist.name,
                color = PureWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${playlist.trackCount} titres",
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
        }
    }
}
