package com.aura.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.DashboardSummaryCard
import com.aura.music.ui.PlayerPreview
import com.aura.music.ui.PlaylistPreviewList
import com.aura.music.ui.RouteScaffold

@Composable
fun LibraryScreen(
    repository: LocalLibraryRepository,
    refreshToken: Int,
    onRequestAudioPermission: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshToken) {
        value = repository.getLibraryDashboardSummary()
    }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshToken) {
        value = repository.getPlaylists()
    }

    RouteScaffold(title = "Library") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                summaryState.value?.let {
                    DashboardSummaryCard(
                        summary = it,
                        onRequestAudioPermission = onRequestAudioPermission,
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = onOpenPlaylists) { Text("Open playlists") }
                    Button(onClick = onOpenDownloads) { Text("Open downloads") }
                    Button(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
            item {
                PlaylistPreviewList(
                    playlists = playlistsState.value,
                    onOpenPlaylist = { },
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun PlaylistsScreen(
    repository: LocalLibraryRepository,
    onNavigateBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository) {
        value = repository.getPlaylists()
    }

    RouteScaffold(title = "Playlists", onNavigateBack = onNavigateBack) {
        Column(modifier = Modifier.padding(top = 16.dp)) {
            PlaylistPreviewList(
                playlists = playlistsState.value,
                onOpenPlaylist = onOpenPlaylist,
            )
        }
    }
}

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onNavigateBack: () -> Unit,
) {
    PlaceholderDetailScreen(
        title = "Playlist detail",
        subtitle = "Playlist id: $playlistId",
        onNavigateBack = onNavigateBack,
    )
}

@Composable
fun ArtistRouteScreen(
    artistId: String,
    onNavigateBack: () -> Unit,
) {
    PlaceholderDetailScreen(
        title = "Artist",
        subtitle = "Artist id: $artistId",
        onNavigateBack = onNavigateBack,
    )
}

@Composable
fun AlbumRouteScreen(
    albumId: String,
    onNavigateBack: () -> Unit,
) {
    PlaceholderDetailScreen(
        title = "Album",
        subtitle = "Album id: $albumId",
        onNavigateBack = onNavigateBack,
    )
}

@Composable
fun DownloadsScreen(onNavigateBack: () -> Unit) {
    PlaceholderDetailScreen(
        title = "Downloads",
        subtitle = "Jobs and retry flows will land in SRV-006 and AND-007.",
        onNavigateBack = onNavigateBack,
    )
}

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    PlaceholderDetailScreen(
        title = "Settings",
        subtitle = "User settings already persist locally in Room.",
        onNavigateBack = onNavigateBack,
    )
}

@Composable
fun PlayerScreen(
    preview: PlayerPreview?,
    onNavigateBack: () -> Unit,
) {
    PlaceholderDetailScreen(
        title = "Player",
        subtitle = preview?.let { "Previewing ${it.title} by ${it.subtitle}." }
            ?: "No active preview yet. Start a local track from Home or Search.",
        onNavigateBack = onNavigateBack,
    )
}

@Composable
private fun PlaceholderDetailScreen(
    title: String,
    subtitle: String,
    onNavigateBack: () -> Unit,
) {
    RouteScaffold(title = title, onNavigateBack = onNavigateBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = subtitle)
            Text("This destination now exists in the real navigation graph and already receives stable ids.")
        }
    }
}
