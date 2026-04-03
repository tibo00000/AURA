package com.aura.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.RepeatMode
import com.aura.music.ui.DashboardSummaryCard
import com.aura.music.ui.PlaylistPreviewList
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.player.PlayerViewModel

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

/**
 * Ecran Player fonctionnel avec controles play/pause/next/prev, progression,
 * et toggles shuffle/repeat.
 *
 * Ecran minimal pour AND-004 : les controles complets (seek bar interactive,
 * vue queue, layout avance) sont attendus dans AND-007.
 */
@Composable
fun PlayerScreen(
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by playerViewModel.uiState.collectAsState()
    val track = uiState.currentTrack

    RouteScaffold(title = "Player", onNavigateBack = onNavigateBack) {
        if (track == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No active track. Start a local track from Home or Search.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = track.artistName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                track.albumTitle?.let { album ->
                    Text(
                        text = album,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Barre de progression
                val progress = if (uiState.durationMs > 0) {
                    (uiState.positionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(uiState.positionMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = formatDuration(uiState.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Controles principaux
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shuffle
                    IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.ToggleShuffle) }) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (uiState.shuffleEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    // Previous
                    IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Previous) }) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Play/Pause
                    IconButton(
                        onClick = { playerViewModel.onEvent(PlayerEvent.TogglePlayPause) },
                        modifier = Modifier.size(64.dp),
                    ) {
                        val icon = if (uiState.playbackState == PlaybackState.Playing) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        }
                        Icon(
                            icon,
                            contentDescription = "Toggle play/pause",
                            modifier = Modifier.size(48.dp),
                        )
                    }

                    // Next
                    IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Next) }) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Repeat
                    IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.CycleRepeatMode) }) {
                        val repeatIcon = when (uiState.repeatMode) {
                            RepeatMode.One -> Icons.Rounded.RepeatOne
                            else -> Icons.Rounded.Repeat
                        }
                        val repeatTint = when (uiState.repeatMode) {
                            RepeatMode.Off -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Icon(
                            repeatIcon,
                            contentDescription = "Repeat mode",
                            tint = repeatTint,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Etat et info queue
                if (uiState.playbackState == PlaybackState.Error && uiState.errorMessage != null) {
                    Text(
                        text = "Error: ${uiState.errorMessage}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (uiState.priorityQueue.isNotEmpty()) {
                    Text(
                        text = "${uiState.priorityQueue.size} track(s) in priority queue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
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

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
