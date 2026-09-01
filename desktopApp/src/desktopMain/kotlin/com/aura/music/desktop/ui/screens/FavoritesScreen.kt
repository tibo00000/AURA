package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.aura.music.data.local.TrackListRow
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.desktop.ui.components.DesktopHeroHeader
import com.aura.music.desktop.ui.components.DesktopTrackTable
import com.aura.music.ui.theme.*

@Composable
fun FavoritesScreen(
    likedTracks: List<TrackListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onToggleLike: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by orchestrator.uiState.collectAsState()
    val totalDurationMs = remember(likedTracks) { likedTracks.sumOf { it.durationMs ?: 0L } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        DesktopHeroHeader(
            tag = "FAVORIS",
            title = "Titres Likés",
            subtitle = "${likedTracks.size} titres enregistrés",
            extraMetadata = formatTotalDuration(totalDurationMs),
            coverUri = likedTracks.firstOrNull()?.coverUri,
            mosaicCovers = likedTracks.mapNotNull { it.coverUri }.take(4),
            isLiked = true,
            onPlayAll = {
                if (likedTracks.isNotEmpty()) {
                    orchestrator.playTrack(
                        trackId = likedTracks.first().id,
                        contextType = "favorites",
                        contextId = "favorites",
                        contextTracks = likedTracks.map { orchestrator.toQueuedTrack(it) },
                        startIndex = 0
                    )
                }
            },
            onShuffleAll = {
                if (likedTracks.isNotEmpty()) {
                    orchestrator.playTrack(
                        trackId = likedTracks.first().id,
                        contextType = "favorites",
                        contextId = "favorites",
                        contextTracks = likedTracks.map { orchestrator.toQueuedTrack(it) },
                        startIndex = 0
                    )
                    if (!orchestrator.queueManager.state.value.isShuffle) {
                        orchestrator.toggleShuffle()
                    }
                }
            }
        )

        DesktopTrackTable(
            tracks = likedTracks,
            activeTrackId = uiState.currentTrack?.trackId,
            isPlaying = uiState.playbackState == com.aura.music.domain.player.PlaybackState.Playing,
            onTrackClick = { track, index ->
                orchestrator.playTrack(
                    trackId = track.id,
                    contextType = "favorites",
                    contextId = "favorites",
                    contextTracks = likedTracks.map { orchestrator.toQueuedTrack(it) },
                    startIndex = index
                )
            },
            onToggleLike = onToggleLike,
            onOpenArtist = { appState.openArtist(it) },
            onOpenAlbum = { appState.openAlbum(it) },
            showDateAddedColumn = true
        )
    }
}

private fun formatTotalDuration(ms: Long): String {
    val totalMinutes = ms / 1000 / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}
