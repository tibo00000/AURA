package com.aura.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.DashboardSummaryCard
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.TrackList

@Composable
fun HomeScreen(
    repository: LocalLibraryRepository,
    refreshToken: Int,
    onRequestAudioPermission: () -> Unit,
    onOpenPlayer: (TrackListRow) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshToken) {
        value = repository.getLibraryDashboardSummary()
    }
    val recentTracksState = produceState(initialValue = emptyList<TrackListRow>(), repository, refreshToken) {
        value = repository.getRecentTracks()
    }

    RouteScaffold(title = "Home") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "AURA now has a real local shell: navigation, Room, and MediaStore discovery.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                summaryState.value?.let {
                    DashboardSummaryCard(
                        summary = it,
                        onRequestAudioPermission = onRequestAudioPermission,
                    )
                }
            }
            item {
                Column {
                    Button(
                        onClick = { recentTracksState.value.firstOrNull()?.let(onOpenPlayer) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text("Resume local playback shell")
                    }
                }
            }
            item {
                TrackList(
                    title = "Recent local tracks",
                    tracks = recentTracksState.value,
                    onPlayTrack = onOpenPlayer,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
