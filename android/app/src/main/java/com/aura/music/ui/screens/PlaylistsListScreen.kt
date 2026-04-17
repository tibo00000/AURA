package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun PlaylistsListScreen(
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepBlack),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Construis tes contextes d'ecoute",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                        Text(
                            "Les playlists locales pilotent la lecture, la reprise et bientot la sync cloud.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        Button(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Text("Créer une playlist", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            if (playlistsState.value.isEmpty()) {
                item {
                    EmptyStateSurface(
                        title = "Pas encore de playlist",
                        message = "Les playlists locales pilotent la lecture et bientot la sync cloud.\n\nCrée ta première pour commencer.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                items(playlistsState.value, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onClick = { onOpenPlaylist(playlist.id) },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = "Créer une playlist",
            confirmLabel = "Créer",
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
private fun PlaylistRow(
    playlist: PlaylistListRow,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onClick() }
            .height(80.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkGraphite)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Cover placeholder
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        Brush.linearGradient(listOf(BlazeOrange, DeepBlack)),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Playlist info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${playlist.itemCount} piste(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }

            // Navigation indicator
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = TextSecondary,
            )
        }
    }
}
