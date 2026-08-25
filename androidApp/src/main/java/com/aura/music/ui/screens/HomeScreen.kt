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
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.theme.*
import com.aura.music.AuraApplication
import coil3.compose.AsyncImage
import androidx.compose.material.icons.rounded.Favorite
import com.aura.music.ui.components.ShimmerTrackList
import com.aura.music.ui.components.ShimmerCard
import com.aura.music.ui.components.rememberShimmerBrush

import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface ResumeItem {
    object Favorites : ResumeItem
    data class Playlist(val playlist: PlaylistListRow) : ResumeItem
    data class Album(val album: AlbumBrowseRow) : ResumeItem
    data class Artist(val artist: ArtistBrowseRow) : ResumeItem
}

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
    onOpenCloudSync: () -> Unit,
    onOpenFavorites: () -> Unit,
) {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as AuraApplication
    val cloudFileRepository = application.container.cloudFileRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    var isCloudBannerDismissed by remember { mutableStateOf(false) }

    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshToken) {
        value = repository.getLibraryDashboardSummary()
    }
    val recentDownloadedTracksState = produceState<List<TrackListRow>?>(initialValue = null, repository, refreshToken) {
        value = repository.getDownloadedTracks().take(4)
    }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshToken) {
        value = repository.getPlaylists().take(8)
    }
    val albumsState = produceState(initialValue = emptyList<AlbumBrowseRow>(), repository, refreshToken) {
        value = repository.getBrowseAlbums(limit = 8)
    }
    val artistsState = produceState(initialValue = emptyList<ArtistBrowseRow>(), repository, refreshToken) {
        value = repository.getBrowseArtists(limit = 8)
    }
    val likedCountState = produceState(initialValue = 0, repository, refreshToken) {
        value = repository.getLikedTracks().size
    }
    val allTracksState = produceState(initialValue = emptyList<TrackListRow>(), repository, refreshToken) {
        value = repository.getAllTracks()
    }

    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository, refreshToken) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value

    val cloudOnlyCount = remember(allTracksState.value, syncedCloudTrackIds, cloudFiles) {
        allTracksState.value.count { track ->
            val isPresentInCloud = syncedCloudTrackIds.contains(track.id) ||
                syncedCloudTrackIds.any { isDeezerTrackMatch(it, track.id) } ||
                cloudFiles.any { cloud ->
                    isDeezerTrackMatch(cloud.trackId, track.id) ||
                    (cloud.title?.trim().equals(track.title.trim(), ignoreCase = true) &&
                     (cloud.artistName?.trim().equals(track.artistName?.trim(), ignoreCase = true) || track.artistName.isNullOrBlank() || cloud.artistName.isNullOrBlank()) &&
                     (cloud.albumTitle?.trim().equals(track.albumTitle?.trim(), ignoreCase = true) || track.albumTitle.isNullOrBlank() || cloud.albumTitle.isNullOrBlank()))
                }
            track.contentUri.isNullOrBlank() && isPresentInCloud
        }
    }

    val resumeItems = remember(playlistsState.value, albumsState.value, artistsState.value) {
        val items = mutableListOf<ResumeItem>()
        items.add(ResumeItem.Favorites)
        playlistsState.value.forEach { items.add(ResumeItem.Playlist(it)) }
        albumsState.value.forEach { items.add(ResumeItem.Album(it)) }
        artistsState.value.forEach { items.add(ResumeItem.Artist(it)) }
        items
    }

    RouteScaffold(title = "Accueil", style = MaterialTheme.typography.headlineLarge) {
        com.aura.music.ui.components.AuraLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepBlack),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                HomeHeader(summaryState.value, onRequestAudioPermission)
            }
            
            if (cloudOnlyCount > 5 && !isCloudBannerDismissed) {
                item {
                    CloudRecoveryBanner(
                        count = cloudOnlyCount,
                        onOpenCloudSync = onOpenCloudSync,
                        onDismiss = { isCloudBannerDismissed = true },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            
            item {
                ResumeRail(
                    items = resumeItems,
                    likedCount = likedCountState.value,
                    isLoading = summaryState.value == null,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onOpenFavorites = onOpenFavorites
                )
            }
            
            item {
                RecentTracksSection(
                    tracks = recentDownloadedTracksState.value, 
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
private fun CloudRecoveryBanner(
    count: Int,
    onOpenCloudSync: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ElevatedGraphite)
            .border(1.dp, BlazeOrange.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BlazeOrange.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDownload,
                    contentDescription = null,
                    tint = BlazeOrange,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Musique sur le Cloud",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$count titres disponibles sur votre serveur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onOpenCloudSync,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BlazeOrange,
                        contentColor = DeepBlack
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Gérer",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Masquer le rappel",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
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
private fun ResumeRail(
    items: List<ResumeItem>,
    likedCount: Int,
    isLoading: Boolean,
    onOpenPlaylist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenFavorites: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Reprendre",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        if (isLoading) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(4) {
                    ShimmerCard(width = 140.dp, height = 180.dp)
                }
            }
            return
        }
        
        if (items.isEmpty()) {
            Text(
                text = "Votre activité récente apparaîtra ici.",
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
            items(items, key = { item ->
                when (item) {
                    is ResumeItem.Favorites -> "favorites"
                    is ResumeItem.Playlist -> "playlist_${item.playlist.id}"
                    is ResumeItem.Album -> "album_${item.album.id}"
                    is ResumeItem.Artist -> "artist_${item.artist.id}"
                }
            }) { item ->
                when (item) {
                    is ResumeItem.Favorites -> {
                        Column(
                            modifier = Modifier
                                .width(140.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(DarkGraphite)
                                .clickable { onOpenFavorites() }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PlaceholderCover(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                                icon = Icons.Rounded.Favorite,
                                gradient = Brush.linearGradient(listOf(RoseSignal, DeepViolet))
                            )
                            Column {
                                Text(
                                    text = "Coups de coeur",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Favoris • $likedCount titres",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    is ResumeItem.Playlist -> {
                        val playlist = item.playlist
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
                    is ResumeItem.Album -> {
                        val album = item.album
                        Column(
                            modifier = Modifier
                                .width(140.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(DarkGraphite)
                                .clickable { onOpenAlbum(album.id) }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!album.coverUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = album.coverUri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                PlaceholderCover(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp)),
                                    icon = Icons.Rounded.Album,
                                    gradient = Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF111111)))
                                )
                            }
                            Column {
                                Text(
                                    text = album.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Album • ${album.artistName ?: "Inconnu"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    is ResumeItem.Artist -> {
                        val artist = item.artist
                        Column(
                            modifier = Modifier
                                .width(140.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(DarkGraphite)
                                .clickable { onOpenArtist(artist.id) }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!artist.pictureUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = artist.pictureUri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                PlaceholderCover(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp)),
                                    icon = Icons.Rounded.MusicNote,
                                    gradient = Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF111111)))
                                )
                            }
                            Column {
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Artiste",
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
    }
}

@Composable
private fun RecentTracksSection(tracks: List<TrackListRow>?, onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Téléchargés récemment",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        if (tracks == null) {
            ShimmerTrackList(count = 3, modifier = Modifier.padding(horizontal = 16.dp))
        } else if (tracks.isEmpty()) {
            Text(
                text = "Vos téléchargements récents apparaîtront ici.",
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
