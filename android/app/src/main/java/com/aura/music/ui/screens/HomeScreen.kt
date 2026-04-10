package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.DashboardSummaryCard
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.TrackList

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
    val albumsState = produceState(initialValue = emptyList<AlbumBrowseRow>(), repository, refreshToken) {
        value = repository.getBrowseAlbums(8)
    }
    val resumeTrackState = produceState<TrackListRow?>(initialValue = null, repository, refreshToken) {
        val snapshot = repository.getLibraryDashboardSummary().activeSnapshot
        value = snapshot?.currentTrackId?.let(repository::getTrackById)
    }

    RouteScaffold(title = "Home") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Accueil",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Reprends ta musique locale, tes playlists et les zones chaudes d'AURA.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                summaryState.value?.let {
                    DashboardSummaryCard(
                        summary = it,
                        onRequestAudioPermission = onRequestAudioPermission,
                    )
                }
            }
            item {
                HeroResumeCard(
                    resumeTrack = resumeTrackState.value,
                    playlistCount = summaryState.value?.playlistCount ?: 0,
                    onOpenPlayer = onOpenPlayer,
                    onRequestAudioPermission = onRequestAudioPermission,
                )
            }
            item {
                SectionHeader(
                    title = "Playlists recentes",
                    subtitle = "Retrouve vite tes contextes de lecture locaux.",
                )
            }
            item {
                HorizontalPlaylistRail(
                    playlists = playlistsState.value,
                    onOpenPlaylist = onOpenPlaylist,
                )
            }
            item {
                SectionHeader(
                    title = "Albums a relancer",
                    subtitle = "Des points d'entree directs vers les surfaces album.",
                )
            }
            item {
                HorizontalAlbumRail(
                    albums = albumsState.value,
                    onOpenAlbum = onOpenAlbum,
                )
            }
            item {
                QuickDownloadsCard(onOpenDownloads = onOpenDownloads)
            }
            item {
                TrackList(
                    title = "Dernieres pistes locales",
                    tracks = recentTracksState.value,
                    contextType = "recent_tracks",
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HeroResumeCard(
    resumeTrack: TrackListRow?,
    playlistCount: Int,
    onOpenPlayer: () -> Unit,
    onRequestAudioPermission: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFDB5800), Color(0xFF101010)),
                    ),
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (resumeTrack != null) "Reprendre l'ecoute" else "Bibliotheque locale",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (resumeTrack != null) {
                    "${resumeTrack.title} | ${resumeTrack.artistName}"
                } else {
                    "Indexe tes pistes puis reprends ici ta session la plus recente."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = if (resumeTrack != null) onOpenPlayer else onRequestAudioPermission) {
                    Text(if (resumeTrack != null) "Reprendre" else "Autoriser l'audio")
                }
                Text(
                    text = "$playlistCount playlist(s) locales",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun HorizontalPlaylistRail(
    playlists: List<PlaylistListRow>,
    onOpenPlaylist: (String) -> Unit,
) {
    if (playlists.isEmpty()) {
        EmptyCard(
            title = "Pas encore de playlists",
            message = "Cree une playlist dans Library pour retrouver ici tes contextes favoris.",
        )
        return
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(start = 16.dp),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            Card(
                modifier = Modifier
                    .width(188.dp)
                    .clickable { onOpenPlaylist(playlist.id) },
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFFF9E00), Color(0xFF1A1A1A)),
                                ),
                                RoundedCornerShape(20.dp),
                            ),
                    )
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${playlist.itemCount} piste(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.width(16.dp)) }
    }
}

@Composable
private fun HorizontalAlbumRail(
    albums: List<AlbumBrowseRow>,
    onOpenAlbum: (String) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyCard(
            title = "Pas d'album pret",
            message = "Les albums locaux apparaissent ici une fois indexes depuis MediaStore.",
        )
        return
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(start = 16.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            Card(
                modifier = Modifier
                    .width(172.dp)
                    .clickable { onOpenAlbum(album.id) },
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF792BEE), Color(0xFF00E0FF)),
                                ),
                                RoundedCornerShape(20.dp),
                            ),
                    )
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = album.artistName ?: "Artiste inconnu",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.width(16.dp)) }
    }
}

@Composable
private fun QuickDownloadsCard(
    onOpenDownloads: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onOpenDownloads),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFFFF6B00), CircleShape)
                    .padding(12.dp),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Rounded.CloudDownload,
                    contentDescription = null,
                    tint = Color(0xFF160A00),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Downloads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "Suivre les jobs, les erreurs et les contenus bientot disponibles localement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyCard(
    title: String,
    message: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
