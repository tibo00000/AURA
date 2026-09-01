package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.playlist.PlaylistImportExportEngine
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    playlistName: String,
    playlistTracks: List<PlaylistTrackRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onToggleLike: (String) -> Unit,
    onReloadData: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by orchestrator.uiState.collectAsState()
    var showOptionsMenu by remember { mutableStateOf(false) }
    var notificationMessage by remember { mutableStateOf<String?>(null) }

    val convertedTracks = remember(playlistTracks) {
        playlistTracks.map { pt ->
            TrackListRow(
                id = pt.trackId,
                artistId = pt.artistId,
                albumId = pt.albumId,
                title = pt.title,
                artistName = pt.artistName,
                albumTitle = pt.albumTitle,
                contentUri = pt.contentUri,
                durationMs = pt.durationMs,
                coverUri = pt.coverUri,
                isLiked = pt.isLiked,
                createdAt = pt.addedAt,
                updatedAt = pt.addedAt
            )
        }
    }

    val mosaicCovers = remember(playlistTracks) {
        playlistTracks.mapNotNull { it.coverUri }.filter { it.isNotBlank() }.take(4)
    }

    val totalDurationMs = remember(playlistTracks) {
        playlistTracks.sumOf { it.durationMs ?: 0L }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        DesktopHeroHeader(
            tag = "PLAYLIST",
            title = playlistName,
            subtitle = "${playlistTracks.size} morceaux",
            extraMetadata = formatTotalDuration(totalDurationMs),
            coverUri = mosaicCovers.firstOrNull(),
            mosaicCovers = mosaicCovers,
            onBack = { appState.navigateBack() },
            onPlayAll = {
                if (convertedTracks.isNotEmpty()) {
                    orchestrator.playTrack(
                        trackId = convertedTracks.first().id,
                        contextType = "playlist",
                        contextId = playlistId,
                        contextTracks = convertedTracks.map { orchestrator.toQueuedTrack(it) },
                        startIndex = 0
                    )
                }
            },
            onShuffleAll = {
                if (convertedTracks.isNotEmpty()) {
                    orchestrator.playTrack(
                        trackId = convertedTracks.first().id,
                        contextType = "playlist",
                        contextId = playlistId,
                        contextTracks = convertedTracks.map { orchestrator.toQueuedTrack(it) },
                        startIndex = 0
                    )
                    if (!orchestrator.queueManager.state.value.shuffleEnabled) {
                        orchestrator.toggleShuffle()
                    }
                }
            },
            onMoreOptions = {
                showOptionsMenu = true
            }
        )

        Box(modifier = Modifier.padding(horizontal = 24.dp)) {
            DropdownMenu(
                expanded = showOptionsMenu,
                onDismissRequest = { showOptionsMenu = false },
                modifier = Modifier.background(DarkGraphite)
            ) {
                DropdownMenuItem(
                    text = { Text("Renommer la playlist", color = PureWhite, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, tint = PureWhite, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        showOptionsMenu = false
                        appState.playlistToRename = playlistId to playlistName
                    }
                )
                DropdownMenuItem(
                    text = { Text("Supprimer les doublons", color = PureWhite, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        showOptionsMenu = false
                        coroutineScope.launch(Dispatchers.IO) {
                            val removedCount = orchestrator.deduplicatePlaylist(playlistId)
                            withContext(Dispatchers.Main) {
                                onReloadData()
                                notificationMessage = if (removedCount > 0) {
                                    "$removedCount doublon(s) supprimé(s)"
                                } else {
                                    "Aucun doublon trouvé"
                                }
                            }
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Exporter en .m3u8", color = PureWhite, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null, tint = PureWhite, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        showOptionsMenu = false
                        val chooser = JFileChooser().apply {
                            dialogTitle = "Exporter la playlist en .m3u8"
                            fileFilter = FileNameExtensionFilter("Playlist M3U8 (*.m3u8)", "m3u8")
                            selectedFile = File("$playlistName.m3u8")
                        }
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val m3u8Content = PlaylistImportExportEngine.exportToM3u8(playlistName, convertedTracks)
                                    chooser.selectedFile.writeText(m3u8Content)
                                    withContext(Dispatchers.Main) {
                                        notificationMessage = "Playlist exportée avec succès en M3U8 !"
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        notificationMessage = "Erreur d'export : ${e.message}"
                                    }
                                }
                            }
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Supprimer la playlist", color = Color(0xFFFF453A), fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF453A), modifier = Modifier.size(18.dp)) },
                    onClick = {
                        showOptionsMenu = false
                        appState.playlistToDelete = playlistId to playlistName
                    }
                )
            }
        }

        if (notificationMessage != null) {
            LaunchedEffect(notificationMessage) {
                kotlinx.coroutines.delay(3500)
                notificationMessage = null
            }
            Surface(
                color = DarkGraphite,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = notificationMessage!!, color = PureWhite, fontSize = 13.sp)
                }
            }
        }

        DesktopTrackTable(
            tracks = convertedTracks,
            currentPlayingTrackId = uiState.currentTrack?.trackId,
            isPlaying = uiState.isPlaying,
            orchestrator = orchestrator,
            database = orchestrator.database,
            appState = appState,
            onTrackClick = { clickedTrack ->
                orchestrator.playTrack(
                    trackId = clickedTrack.id,
                    contextType = "playlist",
                    contextId = playlistId,
                    contextTracks = convertedTracks.map { orchestrator.toQueuedTrack(it) },
                    startIndex = convertedTracks.indexOf(clickedTrack).coerceAtLeast(0)
                )
            },
            onToggleLike = onToggleLike
        )
    }
}

private fun formatTotalDuration(ms: Long): String {
    val totalMinutes = ms / 1000 / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}
