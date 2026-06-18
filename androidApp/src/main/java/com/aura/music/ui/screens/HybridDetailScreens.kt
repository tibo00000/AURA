package com.aura.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.ArtistDetailResponseData
import com.aura.music.data.network.AlbumDetailResponseData
import com.aura.music.data.network.TrackSummary
import com.aura.music.data.network.AlbumSummary
import com.aura.music.data.repository.ArtistDetail
import com.aura.music.data.repository.AlbumDetail
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.trackList
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.domain.player.PlayerEvent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

// =============================================================================
// HybridArtistScreen (AND-010)
// =============================================================================

/**
 * Écran artiste hybride selon le layout canonique docs/android/screens/artist-layout.md.
 *
 * - Ouverture instantanée depuis Room (localData)
 * - Enrichissement asynchrone non bloquant (onlineData + picture_uri)
 * - Si onlineData arrive, les top_tracks backend remplacent la tracklist locale vide
 */
@Composable
fun HybridArtistScreen(
    viewModel: ArtistDetailViewModel,
    playlists: List<PlaylistListRow>,
    onNavigateBack: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onLikeTrack: (TrackListRow) -> Unit,
    onAddTrackToPlaylist: (PlaylistListRow, TrackListRow) -> Unit,
    onDeleteTrack: (TrackListRow) -> Unit,
    onAddToQueue: (TrackListRow) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val artist = state.localData
    val onlineData = state.onlineData

    // Title from local if available, else from online, else placeholder
    val showAllOnlineTracks = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null) }
    var trackToDelete by remember { mutableStateOf<TrackListRow?>(null) }

    val context = LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    RouteScaffold(
        title = null,
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState
    ) {
        if (state.isLocalLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BlazeOrange)
            }
            return@RouteScaffold
        }

        if (artist == null && onlineData == null) {
            if (state.isEnrichmentLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BlazeOrange)
                }
                return@RouteScaffold
            } else {
                EmptyStateSurface(
                    title = "Artiste introuvable",
                    message = "Cet artiste n'existe pas ou n'est plus accessible.",
                )
                return@RouteScaffold
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(DeepBlack),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ---- HERO section ----
            item(key = "hero") {
                ArtistHeroSection(
                    name = artist?.summary?.name ?: onlineData?.name ?: "",
                    pictureUri = artist?.summary?.pictureUri ?: onlineData?.pictureUri,
                    summary = artist?.summary?.summary ?: onlineData?.summary,
                    trackCount = artist?.topTracks?.size ?: onlineData?.topTracks?.size ?: 0,
                    albumCount = artist?.albums?.size ?: onlineData?.albums?.size ?: 0,
                    isEnrichmentLoading = state.isEnrichmentLoading,
                )
            }

            // ---- TRACKLIST ----
            // Prefer local tracks; fall back to online top_tracks summary if local is empty
            val localTracks = artist?.topTracks ?: emptyList()
            if (localTracks.isNotEmpty()) {
                val onUploadToCloudLambda = { track: TrackListRow ->
                    val isLocalScanned = track.contentUri?.startsWith("content://") == true
                    val isAlreadySynced = syncedCloudTrackIds.contains(track.id)
                    if (isLocalScanned && !isAlreadySynced) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                            cloudFileRepository.uploadTrack(track.id).collect { res ->
                                res.onSuccess {
                                    snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                                    cloudFileRepository.refreshSyncedTrackIds()
                                    viewModel.refreshLocal()
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                                }
                            }
                        }
                    }
                }
                val onDownloadFromCloudLambda = { track: TrackListRow ->
                    val isCloudOnly = track.contentUri.isNullOrBlank()
                    val isPresentInCloud = syncedCloudTrackIds.contains(track.id)
                    if (isCloudOnly && isPresentInCloud) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Téléchargement cloud lancé pour : ${track.title}")
                            cloudFileRepository.downloadTrack(
                                trackId = track.id,
                                title = track.title,
                                artistName = track.artistName,
                                albumTitle = track.albumTitle,
                                durationMs = track.durationMs
                            ).collect { res ->
                                res.onSuccess {
                                    snackbarHostState.showSnackbar("Téléchargement cloud réussi : ${track.title}")
                                    appContainer.localLibraryRepository.refreshLocalMediaIndex()
                                    viewModel.refreshLocal()
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                                }
                            }
                        }
                    }
                }

                trackList(
                    title = "Titres populaires",
                    tracks = localTracks,
                    contextType = "artist",
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = { },
                    onOpenAlbum = onOpenAlbum,
                    onPlayNow = { track -> onPlayTrackInList(track, localTracks, "artist") },
                    onAddToQueue = onAddToQueue,
                    onAddTrackToPlaylist = { track -> activeTrackForPlaylist = track },
                    onLikeTrack = onLikeTrack,
                    onDeleteDownload = { track -> trackToDelete = track },
                    onUploadToCloud = onUploadToCloudLambda,
                    onDownloadFromCloud = onDownloadFromCloudLambda
                )
            } else if (onlineData != null && onlineData.topTracks.isNotEmpty()) {
                item(key = "online_tracklist_header") {
                    Text(
                        "Top tracks (enrichissement)",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                val displayedTracks = if (showAllOnlineTracks.value) onlineData.topTracks else onlineData.topTracks.take(5)
                items(displayedTracks, key = { "online_track_${it.id}" }) { track ->
                    OnlineTrackRow(track = track, showCover = true)
                }
                if (!showAllOnlineTracks.value && onlineData.topTracks.size > 5) {
                    item(key = "show_more_tracks") {
                        androidx.compose.material3.TextButton(
                            onClick = { showAllOnlineTracks.value = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Afficher tous les titres", color = BlazeOrange)
                        }
                    }
                }
            }

            // ---- ALBUMS ----
            val localAlbums = artist?.albums ?: emptyList()
            val onlineAlbums = onlineData?.albums
                ?.sortedByDescending { it.releaseDate.orEmpty() }
                ?: emptyList()

            if (localAlbums.isNotEmpty()) {
                item(key = "albums_section") {
                    SectionTitle("Albums", "Discographie disponible localement.")
                    Spacer(Modifier.height(8.dp))
                    BrowseAlbumRail(albums = localAlbums, onOpenAlbum = onOpenAlbum)
                }
            } else if (onlineAlbums.isNotEmpty()) {
                val singles = onlineAlbums.filter { it.isSingleRelease() }
                val albums = onlineAlbums.filterNot { it.isSingleRelease() }

                if (albums.isNotEmpty()) {
                    item(key = "online_albums_section") {
                        OnlineAlbumRailSection(
                            title = "Albums",
                            albums = albums,
                            onOpenAlbum = onOpenAlbum,
                        )
                    }
                }
                if (singles.isNotEmpty()) {
                    item(key = "online_singles_section") {
                        OnlineAlbumRailSection(
                            title = "Singles",
                            albums = singles,
                            onOpenAlbum = onOpenAlbum,
                        )
                    }
                }
            }

            // ---- Enrichment error banner (non-blocking) ----
            if (state.enrichmentError != null) {
                item(key = "enrichment_error") {
                    Text(
                        text = "⚠ ${state.enrichmentError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                onAddTrackToPlaylist(playlist, activeTrackForPlaylist!!)
                activeTrackForPlaylist = null
            }
        )
    }

    if (trackToDelete != null) {
        ConfirmDialog(
            title = "Supprimer de l'appareil ?",
            message = "Voulez-vous vraiment supprimer ce titre de votre appareil ? Cette action supprimera définitivement le fichier physique.",
            confirmLabel = "Supprimer",
            onDismiss = { trackToDelete = null },
            onConfirm = {
                onDeleteTrack(trackToDelete!!)
                trackToDelete = null
            }
        )
    }
}

// =============================================================================
// HybridAlbumScreen (AND-010)
// =============================================================================

/**
 * Écran album hybride selon le layout canonique docs/android/screens/album-layout.md.
 */
@Composable
fun HybridAlbumScreen(
    viewModel: AlbumDetailViewModel,
    playlists: List<PlaylistListRow>,
    onNavigateBack: () -> Unit,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onLikeTrack: (TrackListRow) -> Unit,
    onAddTrackToPlaylist: (PlaylistListRow, TrackListRow) -> Unit,
    onDeleteTrack: (TrackListRow) -> Unit,
    onAddToQueue: (TrackListRow) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val album = state.localData
    val onlineData = state.onlineData

    val screenTitle = album?.summary?.title ?: onlineData?.title ?: "Album"
    var activeTrackForPlaylist by remember { mutableStateOf<TrackListRow?>(null) }
    var trackToDelete by remember { mutableStateOf<TrackListRow?>(null) }

    val context = LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val cloudFileRepository = appContainer.cloudFileRepository
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    RouteScaffold(
        title = screenTitle,
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState
    ) {
        if (state.isLocalLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BlazeOrange)
            }
            return@RouteScaffold
        }

        if (album == null && onlineData == null) {
            if (state.isEnrichmentLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BlazeOrange)
                }
                return@RouteScaffold
            } else {
                EmptyStateSurface(
                    title = "Album introuvable",
                    message = "Cet album n'existe pas ou n'est plus accessible.",
                )
                return@RouteScaffold
            }
        }

        // Resolve displayed metadata: local wins, online enriches
        val coverUri = album?.summary?.coverUri ?: onlineData?.coverUri
        val title = album?.summary?.title ?: onlineData?.title ?: ""
        val artistId = album?.summary?.artistId
        val artistName = album?.summary?.artistName ?: onlineData?.primaryArtistName ?: "Artiste inconnu"
        val releaseDate = album?.summary?.releaseDate ?: onlineData?.releaseDate
        val trackCount = album?.summary?.trackCount ?: onlineData?.trackCount
        val localTracks = album?.tracks ?: emptyList()

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(DeepBlack),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ---- HERO cover ----
            item(key = "hero") {
                AlbumHeroSection(
                    title = title,
                    artistId = artistId,
                    artistName = artistName,
                    coverUri = coverUri,
                    releaseDate = releaseDate,
                    trackCount = trackCount,
                    localTrackCount = localTracks.size,
                    isEnrichmentLoading = state.isEnrichmentLoading,
                    onOpenArtist = onOpenArtist,
                )
            }

            // ---- ACTION bar ----
            item(key = "actions") {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (localTracks.isNotEmpty())
                                onPlayTrackInList(localTracks.first(), localTracks, "album")
                        },
                        enabled = localTracks.isNotEmpty(),
                    ) { Text("Play") }
                    Button(
                        onClick = {
                            val shuffled = localTracks.shuffled()
                            if (shuffled.isNotEmpty())
                                onPlayTrackInList(shuffled.first(), shuffled, "album")
                        },
                        enabled = localTracks.isNotEmpty(),
                    ) { Text("Aléatoire") }
                }
            }

            // ---- TRACKLIST (local only) ----
            if (localTracks.isNotEmpty()) {
                val onUploadToCloudLambda = { track: TrackListRow ->
                    val isLocalScanned = track.contentUri?.startsWith("content://") == true
                    val isAlreadySynced = syncedCloudTrackIds.contains(track.id)
                    if (isLocalScanned && !isAlreadySynced) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                            cloudFileRepository.uploadTrack(track.id).collect { res ->
                                res.onSuccess {
                                    snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                                    cloudFileRepository.refreshSyncedTrackIds()
                                    viewModel.refreshLocal()
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                                }
                            }
                        }
                    }
                }
                val onDownloadFromCloudLambda = { track: TrackListRow ->
                    val isCloudOnly = track.contentUri.isNullOrBlank()
                    val isPresentInCloud = syncedCloudTrackIds.contains(track.id)
                    if (isCloudOnly && isPresentInCloud) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Téléchargement cloud lancé pour : ${track.title}")
                            cloudFileRepository.downloadTrack(
                                trackId = track.id,
                                title = track.title,
                                artistName = track.artistName,
                                albumTitle = track.albumTitle,
                                durationMs = track.durationMs
                            ).collect { res ->
                                res.onSuccess {
                                    snackbarHostState.showSnackbar("Téléchargement cloud réussi : ${track.title}")
                                    appContainer.localLibraryRepository.refreshLocalMediaIndex()
                                    viewModel.refreshLocal()
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                                }
                            }
                        }
                    }
                }

                trackList(
                    title = "",
                    tracks = localTracks,
                    contextType = "album",
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = { },
                    showCover = false,
                    onPlayNow = { track -> onPlayTrackInList(track, localTracks, "album") },
                    onAddToQueue = onAddToQueue,
                    onAddTrackToPlaylist = { track -> activeTrackForPlaylist = track },
                    onLikeTrack = onLikeTrack,
                    onDeleteDownload = { track -> trackToDelete = track },
                    onUploadToCloud = onUploadToCloudLambda,
                    onDownloadFromCloud = onDownloadFromCloudLambda
                )
            } else if (onlineData != null && onlineData.tracks.isNotEmpty()) {
                items(onlineData.tracks, key = { "online_album_track_${it.id}" }) { track ->
                    OnlineTrackRow(track = track, showCover = false)
                }
            } else {
                item(key = "empty_tracks") {
                    EmptyStateSurface(
                        title = "Aucun titre disponible",
                        message = "Les pistes de cet album ne sont pas disponibles.",
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // ---- Enrichment error banner ----
            if (state.enrichmentError != null) {
                item(key = "enrichment_error") {
                    Text(
                        text = "⚠ ${state.enrichmentError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                onAddTrackToPlaylist(playlist, activeTrackForPlaylist!!)
                activeTrackForPlaylist = null
            }
        )
    }

    if (trackToDelete != null) {
        ConfirmDialog(
            title = "Supprimer de l'appareil ?",
            message = "Voulez-vous vraiment supprimer ce titre de votre appareil ? Cette action supprimera définitivement le fichier physique.",
            confirmLabel = "Supprimer",
            onDismiss = { trackToDelete = null },
            onConfirm = {
                onDeleteTrack(trackToDelete!!)
                trackToDelete = null
            }
        )
    }
}

// =============================================================================
// Sub-composables
// =============================================================================

@Composable
private fun ArtistHeroSection(
    name: String,
    pictureUri: String?,
    summary: String?,
    trackCount: Int,
    albumCount: Int,
    isEnrichmentLoading: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepBlack)
            .padding(top = 24.dp, bottom = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Artist picture (enriched) or placeholder
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (pictureUri != null) {
                    AsyncImage(
                        model = pictureUri,
                        contentDescription = "Photo de $name",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(
                                Color(0xFF232323),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
                // Enrichment spinner overlay
                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isEnrichmentLoading,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = BlazeOrange,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    "$trackCount piste(s) • $albumCount album(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistActionBar(
    hasLocalTracks: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    enrichmentBlocked: Boolean,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (enrichmentBlocked) {
            Icon(
                Icons.Rounded.Wifi,
                contentDescription = "Enrichissement désactivé",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
            )
        }
    }
}

@Composable
private fun AlbumHeroSection(
    title: String,
    artistId: String?,
    artistName: String,
    coverUri: String?,
    releaseDate: String?,
    trackCount: Int?,
    localTrackCount: Int,
    isEnrichmentLoading: Boolean,
    onOpenArtist: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Cover art
        Box(
            modifier = Modifier.size(280.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUri != null) {
                AsyncImage(
                    model = coverUri,
                    contentDescription = "Cover de $title",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(24.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF00E0FF), Color(0xFF101010))),
                            RoundedCornerShape(24.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
            // Enrichment spinner
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isEnrichmentLoading,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = BlazeOrange,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        // Metadata
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (artistId != null) {
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onOpenArtist(artistId) }
                        .padding(4.dp),
                )
            } else {
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Text(
                text = listOfNotNull(
                    trackCount?.let { "$it piste(s)" }
                        ?: if (localTrackCount > 0) "$localTrackCount piste(s) locale(s)" else null,
                    releaseDate,
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

/** Affichage simplifié d'un titre online (non local) — lecture non disponible */
@Composable
private fun OnlineAlbumRailSection(
    title: String,
    albums: List<AlbumSummary>,
    onOpenAlbum: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title, "Du plus recent au plus ancien.")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                SharedRailCard(
                    title = album.title,
                    subtitle = album.onlineAlbumMetadata(),
                    imageUri = album.coverUri,
                    gradientStartColor = Color(0xFFFF9E00),
                    imageShape = RoundedCornerShape(20.dp),
                    onClick = { onOpenAlbum(album.id) },
                )
            }
        }
    }
}

@Composable
private fun OnlineTrackRow(track: TrackSummary, showCover: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showCover) {
            val cover = track.coverUri
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                PlaceholderCover(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
            )
            Text(
                track.displayArtistName,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
            )
        }
    }
}

/** Convertit un AlbumSummary online en AlbumBrowseRow pour réutiliser BrowseAlbumRail */
private fun com.aura.music.data.network.AlbumSummary.toBrowseRow(): AlbumBrowseRow = AlbumBrowseRow(
    id = id,
    title = title,
    artistId = null,
    artistName = primaryArtistName,
    coverUri = coverUri,
    trackCount = trackCount,
)

private fun AlbumSummary.onlineAlbumMetadata(): String =
    listOfNotNull(
        releaseDate?.take(4),
        trackCount?.let { "$it piste(s)" },
    ).joinToString(" | ").ifBlank { "En ligne" }

private fun AlbumSummary.isSingleRelease(): Boolean =
    when (releaseType?.lowercase()) {
        "single" -> true
        "album", "ep", "compilation" -> false
        else -> trackCount == 1
    }
