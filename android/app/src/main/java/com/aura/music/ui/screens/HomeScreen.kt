package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.theme.*

@Composable
fun HomeScreen(
    repository: LocalLibraryRepository,
    refreshToken: Int,
    onRequestAudioPermission: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshToken) {
        value = repository.getLibraryDashboardSummary()
    }
    val recentTracksState = produceState(initialValue = emptyList<TrackListRow>(), repository, refreshToken) {
        value = repository.getRecentTracks()
    }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshToken) {
        value = repository.getPlaylists().take(8)
    }

    RouteScaffold(title = "Accueil", style = MaterialTheme.typography.headlineLarge) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepBlack),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                HomeHeader(summaryState.value, onRequestAudioPermission)
            }
            
            item {
                ResumeRail(playlists = playlistsState.value, onOpenPlaylist = onOpenPlaylist)
            }
            
            item {
                DownloadsSection(
                    tracks = recentTracksState.value, 
                    onPlayTrackInList = onPlayTrackInList
                )
            }
            
            item {
                DiscoveryMixCard()
            }
            
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HomeHeader(summary: LibraryDashboardSummary?, onRequestAudioPermission: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (summary != null && !summary.hasAudioPermission) {
            Button(
                onClick = onRequestAudioPermission, 
                colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange, contentColor = TextOnAccent)
            ) {
                Text("Autoriser l'audio")
            }
        }
    }
}

@Composable
private fun ResumeRail(playlists: List<PlaylistListRow>, onOpenPlaylist: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Reprendre",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        if (playlists.isEmpty()) {
            Text(
                text = "Cree une playlist pour commencer.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            return
        }
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(playlists, key = { it.id }) { playlist ->
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkGraphite)
                        .clickable { onOpenPlaylist(playlist.id) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PlaceholderCover(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                        icon = Icons.Rounded.QueueMusic,
                        gradient = Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF111111)))
                    )
                    Column {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Playlist • ${playlist.itemCount} titres",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsSection(tracks: List<TrackListRow>, onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Derniers téléchargements",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        if (tracks.isEmpty()) {
            Text(
                text = "Vos pistes locales téléchargées apparaitront ici.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                tracks.take(4).forEach { track ->
                    DenseTrackRow(track = track, onClick = { onPlayTrackInList(track, tracks.take(4), "recent_downloads") })
                }
            }
        }
    }
}

@Composable
private fun DenseTrackRow(track: TrackListRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PlaceholderCover(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            icon = Icons.Rounded.MusicNote,
            gradient = Brush.linearGradient(listOf(ElevatedGraphite, DeepBlack))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artistName ?: "Inconnu", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = TextPrimary)
    }
}

@Composable
private fun DiscoveryMixCard() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Mix Découvertes",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(
                modifier = Modifier
                    .background(Brush.linearGradient(listOf(DeepViolet, ElectricCyan)))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "AURA Mix",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Généré à partir de votre bibliothèque locale.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary.copy(alpha = 0.9f)
                )
                Button(
                    onClick = { /* TODO */ },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepBlack, contentColor = TextPrimary),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text("JOUER LE MIX", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
