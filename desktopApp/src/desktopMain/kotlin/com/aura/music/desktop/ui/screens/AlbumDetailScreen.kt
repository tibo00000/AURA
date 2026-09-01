package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.AlbumDetailResponseData
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.components.DesktopHeroHeader
import com.aura.music.desktop.ui.components.DesktopTrackTable
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DeepBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun AlbumDetailScreen(
    albumId: String,
    allLocalTracks: List<TrackListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onToggleLike: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var albumData by remember { mutableStateOf<AlbumDetailResponseData?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val localAlbumTracks = remember(albumId, allLocalTracks) {
        allLocalTracks.filter { it.albumId == albumId || (it.displayAlbum != null && albumId.contains(it.displayAlbum!!, ignoreCase = true)) }
    }

    val albumTitle = remember(albumData, localAlbumTracks, albumId) {
        albumData?.title ?: localAlbumTracks.firstOrNull()?.displayAlbum ?: "Album"
    }

    val artistName = remember(albumData, localAlbumTracks) {
        albumData?.primaryArtistName ?: localAlbumTracks.firstOrNull()?.displayArtist ?: "Artiste inconnu"
    }

    val coverUri = remember(albumData, localAlbumTracks) {
        albumData?.coverUri ?: localAlbumTracks.firstOrNull()?.coverUri
    }

    LaunchedEffect(albumId) {
        if (albumId.startsWith("alb_")) {
            isLoading = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val resp = orchestrator.apiService.getAlbum(albumId)
                    if (resp.data != null) {
                        albumData = resp.data
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to load remote album: ${e.message}")
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val tracksToShow = remember(albumData, localAlbumTracks) {
        if (localAlbumTracks.isNotEmpty()) localAlbumTracks
        else {
            albumData?.tracks?.map { tt ->
                TrackListRow(
                    id = tt.id,
                    title = tt.title,
                    displayArtist = tt.displayArtistName,
                    displayAlbum = tt.displayAlbumTitle ?: albumTitle,
                    durationMs = tt.durationMs.toLong(),
                    coverUri = tt.coverUri ?: coverUri,
                    artistId = tt.artistId ?: "artist:${tt.displayArtistName}",
                    albumId = albumId,
                    isLiked = tt.isLiked,
                    isCloudOnly = true
                )
            } ?: emptyList()
        }
    }

    val totalDurationMs = remember(tracksToShow) { tracksToShow.sumOf { it.durationMs ?: 0L } }
    val uiState by orchestrator.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        DesktopHeroHeader(
            tag = "ALBUM",
            title = albumTitle,
            subtitle = "$artistName • ${tracksToShow.size} titres",
            extraMetadata = formatTotalDuration(totalDurationMs),
            coverUri = coverUri,
            onBack = { appState.navigateBack() },
            onPlayAll = {
                if (tracksToShow.isNotEmpty()) {
                    orchestrator.playTrack(
                        trackId = tracksToShow.first().id,
                        contextType = "album",
                        contextId = albumId,
                        contextTracks = tracksToShow.map { orchestrator.toQueuedTrack(it) },
                        startIndex = 0
                    )
                }
            },
            onShuffleAll = {
                if (tracksToShow.isNotEmpty()) {
                    orchestrator.playTrack(
                        trackId = tracksToShow.first().id,
                        contextType = "album",
                        contextId = albumId,
                        contextTracks = tracksToShow.map { orchestrator.toQueuedTrack(it) },
                        startIndex = 0
                    )
                    if (!orchestrator.queueManager.state.value.isShuffle) {
                        orchestrator.toggleShuffle()
                    }
                }
            }
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BlazeOrange)
            }
        } else {
            DesktopTrackTable(
                tracks = tracksToShow,
                activeTrackId = uiState.currentTrack?.trackId,
                isPlaying = uiState.playbackState == com.aura.music.domain.player.PlaybackState.Playing,
                onTrackClick = { track, index ->
                    orchestrator.playTrack(
                        trackId = track.id,
                        contextType = "album",
                        contextId = albumId,
                        contextTracks = tracksToShow.map { orchestrator.toQueuedTrack(it) },
                        startIndex = index
                    )
                },
                onToggleLike = onToggleLike,
                onOpenArtist = { appState.openArtist(it) },
                showAlbumColumn = false
            )
        }
    }
}

private fun formatTotalDuration(ms: Long): String {
    val totalMinutes = ms / 1000 / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}
