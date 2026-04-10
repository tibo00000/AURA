package com.aura.music.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.PlaylistDetail
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.RepeatMode
import com.aura.music.ui.DashboardSummaryCard
import com.aura.music.ui.PlaylistPreviewList
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.toQueuedTrack
import kotlinx.coroutines.launch

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
    var refreshTick by remember { mutableIntStateOf(0) }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshTick) {
        value = repository.getPlaylists()
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    RouteScaffold(title = "Playlists", onNavigateBack = onNavigateBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text("Create playlist", modifier = Modifier.padding(start = 8.dp))
            }

            PlaylistPreviewList(
                playlists = playlistsState.value,
                onOpenPlaylist = onOpenPlaylist,
            )
        }
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = "Create playlist",
            confirmLabel = "Create",
            initialValue = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                scope.launch {
                    repository.createPlaylist(name)
                    refreshTick++
                }
                showCreateDialog = false
            },
        )
    }
}

@Composable
fun PlaylistDetailScreen(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    playlistId: String,
    onNavigateBack: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val detailState = produceState<PlaylistDetail?>(initialValue = null, repository, playlistId, refreshTick) {
        value = repository.getPlaylistDetail(playlistId)
    }
    val candidateTracksState = produceState(initialValue = emptyList<TrackListRow>(), repository, playlistId, refreshTick) {
        value = repository.getPlaylistCandidateTracks()
    }
    val scope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddTrackDialog by remember { mutableStateOf(false) }

    val detail = detailState.value

    RouteScaffold(
        title = detail?.summary?.name ?: "Playlist",
        onNavigateBack = onNavigateBack,
    ) {
        if (detail == null) {
            PlaceholderDetailScreen(
                title = "Playlist detail",
                subtitle = "Playlist not found.",
                onNavigateBack = onNavigateBack,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = detail.summary.name,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                text = "${detail.summary.itemCount} track(s)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                playPlaylist(
                                    playerViewModel = playerViewModel,
                                    tracks = detail.tracks.map { it.toTrackListRow() },
                                    shuffle = false,
                                    playlistId = detail.summary.id,
                                )
                            },
                        ) {
                            Text("Play")
                        }
                        Button(
                            onClick = {
                                playPlaylist(
                                    playerViewModel = playerViewModel,
                                    tracks = detail.tracks.map { it.toTrackListRow() },
                                    shuffle = true,
                                    playlistId = detail.summary.id,
                                )
                            },
                            enabled = detail.tracks.isNotEmpty(),
                        ) {
                            Text("Shuffle")
                        }
                        Button(onClick = { showAddTrackDialog = true }) {
                            Text("Add track")
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                            Text("Rename", modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = null)
                            Text("Delete", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (detail.tracks.isEmpty()) {
                    item {
                        Text(
                            text = "This playlist is empty. Add a local track to make it playable.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(detail.tracks, key = { it.playlistItemId }) { track ->
                        PlaylistTrackItem(
                            track = track,
                            canMoveUp = track.position > 0,
                            canMoveDown = track.position < detail.tracks.lastIndex,
                            onPlay = {
                                playPlaylist(
                                    playerViewModel = playerViewModel,
                                    tracks = detail.tracks.map { row -> row.toTrackListRow() },
                                    shuffle = false,
                                    playlistId = detail.summary.id,
                                    startTrackId = track.trackId,
                                )
                            },
                            onMoveUp = {
                                scope.launch {
                                    repository.movePlaylistItem(detail.summary.id, track.playlistItemId, -1)
                                    refreshTick++
                                }
                            },
                            onMoveDown = {
                                scope.launch {
                                    repository.movePlaylistItem(detail.summary.id, track.playlistItemId, 1)
                                    refreshTick++
                                }
                            },
                            onRemove = {
                                scope.launch {
                                    repository.removeTrackFromPlaylist(detail.summary.id, track.playlistItemId)
                                    refreshTick++
                                }
                            },
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showRenameDialog && detail != null) {
        PlaylistNameDialog(
            title = "Rename playlist",
            confirmLabel = "Save",
            initialValue = detail.summary.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                scope.launch {
                    repository.renamePlaylist(detail.summary.id, name)
                    refreshTick++
                }
                showRenameDialog = false
            },
        )
    }

    if (showDeleteDialog && detail != null) {
        ConfirmDialog(
            title = "Delete playlist",
            message = "Delete ${detail.summary.name}? This removes the playlist and its local ordering only.",
            confirmLabel = "Delete",
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                scope.launch {
                    repository.deletePlaylist(detail.summary.id)
                }
                showDeleteDialog = false
                onNavigateBack()
            },
        )
    }

    if (showAddTrackDialog && detail != null) {
        AddTrackToPlaylistDialog(
            tracks = candidateTracksState.value,
            onDismiss = { showAddTrackDialog = false },
            onSelectTrack = { track ->
                scope.launch {
                    repository.addTrackToPlaylist(detail.summary.id, track.id)
                    refreshTick++
                }
                showAddTrackDialog = false
            },
        )
    }
}

@Composable
private fun PlaylistTrackItem(
    track: PlaylistTrackRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = listOfNotNull(track.artistName, track.albumTitle).joinToString(" • "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${track.title}")
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Remove from playlist")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onPlay),
    )
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AddTrackToPlaylistDialog(
    tracks: List<TrackListRow>,
    onDismiss: () -> Unit,
    onSelectTrack: (TrackListRow) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visibleTracks = remember(tracks, query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            tracks.take(20)
        } else {
            tracks.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                    it.artistName.contains(trimmed, ignoreCase = true) ||
                    (it.albumTitle?.contains(trimmed, ignoreCase = true) == true)
            }.take(20)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a local track") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Filter tracks") },
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(visibleTracks, key = { it.id }) { track ->
                        ListItem(
                            headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    text = listOfNotNull(track.artistName, track.albumTitle).joinToString(" • "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.clickable { onSelectTrack(track) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

private fun playPlaylist(
    playerViewModel: PlayerViewModel,
    tracks: List<TrackListRow>,
    shuffle: Boolean,
    playlistId: String,
    startTrackId: String? = null,
) {
    if (tracks.isEmpty()) return
    val orderedTracks = if (shuffle) tracks.shuffled() else tracks
    val startIndex = startTrackId?.let { trackId ->
        orderedTracks.indexOfFirst { it.id == trackId }.takeIf { it >= 0 }
    } ?: 0

    playerViewModel.onEvent(
        PlayerEvent.PlayTrack(
            trackId = orderedTracks[startIndex].id,
            contextType = "playlist",
            contextId = playlistId,
            contextTracks = orderedTracks.map { it.toQueuedTrack() },
            startIndex = startIndex,
        ),
    )
}

private fun PlaylistTrackRow.toTrackListRow(): TrackListRow = TrackListRow(
    id = trackId,
    title = title,
    artistName = artistName,
    albumTitle = albumTitle,
    contentUri = contentUri,
    durationMs = durationMs,
)

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
                    text = "No active track. Start a local track from Home, Search, or Playlists.",
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

                    IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Previous) }) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(36.dp),
                        )
                    }

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

                    IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Next) }) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(36.dp),
                        )
                    }

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
