package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.PlaylistDetail
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import com.aura.music.ui.toQueuedTrack
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreenNew(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    playlistId: String,
    onNavigateBack: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val detailState = produceState<PlaylistDetail?>(initialValue = null, repository, playlistId, refreshTick) {
        value = repository.getPlaylistDetail(playlistId)
    }
    val scope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenuOpen by remember { mutableStateOf(false) }
    val detail = detailState.value

    RouteScaffold(title = detail?.summary?.name ?: "Playlist", onNavigateBack = onNavigateBack) {
        if (detail == null) {
            EmptyStateSurface("Playlist introuvable", "Cette playlist n'existe plus localement.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    // Hero Cover
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(280.dp)
                                .background(
                                    Brush.linearGradient(listOf(BlazeOrange, DeepBlack)),
                                    RoundedCornerShape(24.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            // optional : large icon placeholder
                        }
                    }

                    // Metadata
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            detail.summary.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                        Text(
                            "${detail.summary.itemCount} piste(s)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }

                item {
                    // Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                val tracks = detail.tracks.map { it.toTrackListRow() }
                                if (tracks.isNotEmpty()) {
                                    playPlaylist(playerViewModel, tracks, false, detail.summary.id)
                                }
                            },
                            enabled = detail.tracks.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Play")
                        }

                        Button(
                            onClick = {
                                val tracks = detail.tracks.map { it.toTrackListRow() }.shuffled()
                                if (tracks.isNotEmpty()) {
                                    playPlaylist(playerViewModel, tracks, true, detail.summary.id)
                                }
                            },
                            enabled = detail.tracks.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Shuffle")
                        }

                        Box {
                            IconButton(onClick = { showMenuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Menu actions supplémentaires")
                            }

                            DropdownMenu(
                                expanded = showMenuOpen,
                                onDismissRequest = { showMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Renommer") },
                                    onClick = {
                                        showRenameDialog = true
                                        showMenuOpen = false
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Supprimer") },
                                    onClick = {
                                        showDeleteDialog = true
                                        showMenuOpen = false
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                )
                            }
                        }
                    }
                }

                if (detail.tracks.isEmpty()) {
                    item {
                        EmptyStateSurface(
                            title = "Aucune piste pour l'instant",
                            message = "Accede a Recherche ou Bibliotheque pour ajouter des pistes a cette playlist.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    items(detail.tracks, key = { it.playlistItemId }) { track ->
                        PlaylistTrackRowItem(
                            track = track,
                            onPlayTrack = {
                                val tracks = detail.tracks.map { it.toTrackListRow() }
                                playPlaylist(playerViewModel, tracks, false, detail.summary.id, track.trackId)
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
            title = "Renommer la playlist",
            confirmLabel = "Sauvegarder",
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
            title = "Supprimer cette playlist ?",
            message = "Cette action est irréversible.",
            confirmLabel = "Supprimer",
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
}

@Composable
private fun PlaylistTrackRowItem(
    track: PlaylistTrackRow,
    onPlayTrack: () -> Unit,
) {
    SharedTrackRowItem(
        title = track.title,
        subtitle = track.artistName ?: "Artiste inconnu",
        onClick = onPlayTrack,
        coverUri = track.coverUri,
        trailingIcon = {
            IconButton(onClick = { /* context menu v2 */ }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Menu", tint = TextSecondary)
            }
        },
    )
}

private fun playPlaylist(
    playerViewModel: com.aura.music.ui.player.PlayerViewModel,
    tracks: List<com.aura.music.data.local.TrackListRow>,
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
        com.aura.music.domain.player.PlayerEvent.PlayTrack(
            trackId = orderedTracks.getOrNull(startIndex)?.id ?: return,
            contextType = "playlist",
            contextId = playlistId,
            contextTracks = orderedTracks.map { it.toQueuedTrack() },
            startIndex = startIndex,
        ),
    )
}
