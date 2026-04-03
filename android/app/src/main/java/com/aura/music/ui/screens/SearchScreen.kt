package com.aura.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.SummaryList
import com.aura.music.ui.TrackList
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: LocalLibraryRepository,
    refreshToken: Int,
    onRequestAudioPermission: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val recentQueries = remember { mutableStateListOf<String>() }
    val localResults = remember { mutableStateListOf<TrackListRow>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repository, refreshToken) {
        recentQueries.clear()
        recentQueries += repository.getRecentQueries()
    }

    LaunchedEffect(query, repository) {
        localResults.clear()
        if (query.trim().length >= 3) {
            localResults += repository.searchLocalTracks(query)
        }
    }

    RouteScaffold(title = "Search") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    label = { Text("Search locally now, online later") },
                    supportingText = {
                        Text("Type at least 3 characters for local suggestions. Full hybrid search will come with AND-005.")
                    },
                    singleLine = true,
                )
            }
            item {
                Button(
                    onClick = {
                        if (query.isNotBlank()) {
                            recentQueries.remove(query.trim())
                            recentQueries.add(0, query.trim())
                            scope.launch {
                                repository.saveRecentSearch(query)
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Validate local search")
                }
            }
            item {
                Button(
                    onClick = onRequestAudioPermission,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Refresh MediaStore access")
                }
            }
            item {
                if (query.trim().length >= 3) {
                    TrackList(
                        title = "Best local matches",
                        tracks = localResults.toList(),
                        contextType = "search_results",
                        onPlayTrackInList = { track, allTracks, contextType ->
                            scope.launch {
                                repository.saveRecentSearch(query)
                            }
                            onPlayTrackInList(track, allTracks, contextType)
                        },
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                    )
                } else {
                    SummaryList(
                        title = "Recent searches",
                        items = recentQueries,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
