package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.AlbumDetailResponseData
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.components.*
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*
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
    var resolvedCoverUri by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val localAlbumTracks = remember(albumId, allLocalTracks) {
        allLocalTracks.filter { it.albumId == albumId || (it.albumTitle != null && albumId.contains(it.albumTitle!!, ignoreCase = true)) }
    }

    val albumTitle = remember(albumData, localAlbumTracks, albumId) {
        albumData?.title ?: localAlbumTracks.firstOrNull()?.albumTitle ?: "Album"
    }

    val artistName = remember(albumData, localAlbumTracks) {
        albumData?.primaryArtistName ?: localAlbumTracks.firstOrNull()?.artistName ?: "Artiste inconnu"
    }

    // Résolution hybride album et mise en cache Room (Point 3 de l'audit)
    LaunchedEffect(albumId) {
        if (albumId.startsWith("alb_")) {
            isLoading = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val resp = orchestrator.apiService.getAlbum(albumId)
                    if (resp.data != null) {
                        albumData = resp.data
                        resolvedCoverUri = resp.data?.coverUri
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to load remote album: ${e.message}")
                } finally {
                    isLoading = false
                }
            }
        } else {
            // Album local sans ID distant : tentative de résolution
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val sampleTrack = localAlbumTracks.firstOrNull()?.title
                    val resp = orchestrator.apiService.resolveAlbum(
                        title = albumTitle,
                        artistName = artistName,
                        trackTitle = sampleTrack
                    )
                    val resolved = resp.data?.album
                    if (resolved != null) {
                        resolvedCoverUri = resolved.coverUri
                        if (!resolved.coverUri.isNullOrBlank()) {
                            val now = System.currentTimeMillis()
                            orchestrator.database.albumDao().updateArtwork(
                                albumId = albumId,
                                coverUri = resolved.coverUri!!,
                                artworkOrigin = "backend_resolve",
                                resolvedAt = now,
                                updatedAt = now
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Ignore transient resolution errors
                }
            }
        }
    }

    val coverUri = remember(albumData, localAlbumTracks, resolvedCoverUri) {
        resolvedCoverUri ?: albumData?.coverUri ?: localAlbumTracks.firstOrNull()?.coverUri
    }

    val tracksToShow = remember(albumData, localAlbumTracks) {
        if (localAlbumTracks.isNotEmpty()) localAlbumTracks
        else {
            albumData?.tracks?.map { tt ->
                TrackListRow(
                    id = tt.id,
                    artistId = tt.artistId ?: "artist:${tt.displayArtistName}",
                    albumId = albumId,
                    title = tt.title,
                    artistName = tt.displayArtistName,
                    albumTitle = tt.displayAlbumTitle ?: albumTitle,
                    contentUri = null,
                    durationMs = tt.durationMs.toLong(),
                    coverUri = tt.coverUri ?: coverUri,
                    isLiked = tt.isLiked,
                    createdAt = 0L,
                    updatedAt = 0L
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
            subtitle = "$artistName • ${tracksToShow.size} titres • ${totalDurationMs / 60000} min",
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
                    orchestrator.toggleShuffle()
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 16.dp)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BlazeOrange)
                }
            } else {
                DesktopTrackTable(
                    tracks = tracksToShow,
                    currentPlayingTrackId = uiState.currentTrack?.trackId,
                    isPlaying = uiState.isPlaying,
                    orchestrator = orchestrator,
                    database = orchestrator.database,
                    appState = appState,
                    onTrackClick = { clickedTrack ->
                        orchestrator.playTrack(
                            trackId = clickedTrack.id,
                            contextType = "album",
                            contextId = albumId,
                            contextTracks = tracksToShow.map { orchestrator.toQueuedTrack(it) },
                            startIndex = tracksToShow.indexOf(clickedTrack).coerceAtLeast(0)
                        )
                    },
                    onToggleLike = onToggleLike
                )
            }
        }
    }
}
