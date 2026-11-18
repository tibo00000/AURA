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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.collectAsState
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
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.ui.screens.SelectPlaylistDialog
import com.aura.music.ui.screens.DuplicateTrackInPlaylistDialog
import com.aura.music.data.repository.AddToPlaylistResult
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.PlaylistDetail
import com.aura.music.data.playlist.PlaylistSourceReference
import com.aura.music.data.playlist.PlaylistSourceReadException
import com.aura.music.data.network.PlaylistRefreshResult
import com.aura.music.data.playlist.metadataMatchKey
import com.aura.music.data.playlist.suggestedPlaylistLinks
import com.aura.music.data.playlist.suggestedLibraryTracks
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import com.aura.music.ui.screens.PlaylistMosaicCover
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

@Composable
fun PlaylistDetailScreenNew(
    repository: LocalLibraryRepository,
    playerViewModel: PlayerViewModel,
    playlistId: String,
    refreshToken: Int,
    onNavigateBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val detailState = produceState<PlaylistDetail?>(initialValue = null, repository, playlistId) {
        repository.observePlaylistDetail(playlistId).collect { value = it }
    }
    val scope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenuOpen by remember { mutableStateOf(false) }
    var exportReport by remember { mutableStateOf<com.aura.music.data.playlist.ExportReport?>(null) }
    val detail = detailState.value
 
    var activeTrackForPlaylist by remember { mutableStateOf<PlaylistTrackRow?>(null) }
    var pendingDuplicatePrompt by remember { mutableStateOf<AddToPlaylistResult.AlreadyExists?>(null) }
    var trackToEditMetadata by remember { mutableStateOf<com.aura.music.data.local.TrackListRow?>(null) }
    val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository, refreshTick, refreshToken) {
        value = repository.getPlaylists()
    }
    val playlists = playlistsState.value

    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = remember(context) { context.getSharedPreferences("aura_prefs", android.content.Context.MODE_PRIVATE) }
    var isAutoDownloadPlaylist by remember(playlistId) { mutableStateOf(sharedPrefs.getBoolean("auto_download_playlist_$playlistId", false)) }
    val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
    val auraScope by appContainer.authSessionManager.sessionScope.collectAsState()
    val sourceRepository = appContainer.playlistSourceRepository
    val observedSourceState by remember(sourceRepository, playlistId) { sourceRepository.observe(playlistId) }
        .collectAsState(initial = null)
    val sourceState = observedSourceState?.takeIf { it.userId == auraScope?.userId }
    val sourceProvider = sourceState?.sourceProvider
    val sourceName = when (sourceProvider) {
        "spotify" -> "Spotify"
        "deezer" -> "Deezer"
        else -> null
    }
    var sourceEditRevision by remember(playlistId, auraScope) { mutableStateOf<Long?>(null) }
    var showSyncOptions by remember(playlistId, auraScope) { mutableStateOf(false) }
    var isSavingSource by remember(playlistId, auraScope) { mutableStateOf(false) }
    var isRefreshingSource by remember(playlistId, auraScope) { mutableStateOf(false) }
    var showUncertainTracks by remember(playlistId, auraScope) { mutableStateOf(false) }
    var trackToLink by remember(playlistId, auraScope) { mutableStateOf<com.aura.music.data.network.PlaylistTrackMetadata?>(null) }
    val lastRefresh = remember(sourceState?.lastRefreshJson) {
        sourceState?.lastRefreshJson?.let { runCatching { Json.decodeFromString<PlaylistRefreshResult>(it) }.getOrNull() }
    }
    androidx.compose.runtime.LaunchedEffect(playlistId, auraScope, lastRefresh?.requestId) {
        if (lastRefresh?.uncertain?.isNotEmpty() == true) showUncertainTracks = true
    }
    val cloudFileRepository = appContainer.cloudFileRepository
    val playlistPlayback = appContainer.playlistPlayback
    val acquisitionStatusMap by playlistPlayback.statuses.collectAsState()
    val verificationFailures by playlistPlayback.verificationFailures.collectAsState()
    val librarySyncPending by playlistPlayback.librarySyncPending.collectAsState()
    val acquisitionReport = acquisitionStatusMap[playlistId]
    val acquisitionStatuses = acquisitionReport.orEmpty()
    val acquisitionsByTrack = remember(acquisitionStatuses) { acquisitionStatuses.associateBy { it.trackId } }
    val inProgress = remember(acquisitionStatuses) { acquisitionStatuses.count { it.isAcquisitionInProgress() } }
    val needsCloudCheck = inProgress > 0 || playlistId in librarySyncPending
    var showAcquisitionReport by remember(playlistId, auraScope) { mutableStateOf(false) }
    var requestingCloud by remember(playlistId, auraScope) { mutableStateOf(false) }
    var choosingVersion by remember(playlistId, auraScope) { mutableStateOf<Pair<String, List<com.aura.music.data.network.YtmCandidateDto>>?>(null) }
    val downloadJobs by appContainer.downloadRepository.getAllJobs().collectAsState(initial = emptyList())
    val jobsByTrack = remember(downloadJobs) { downloadJobs.associateBy { it.trackId } }
    val isOnline by appContainer.connectivityObserver.isOnline.collectAsState(initial = true)
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val confirmedPlaylistCloudIds = remember(syncedCloudTrackIds, acquisitionStatuses) {
        syncedCloudTrackIds + acquisitionStatuses.filter { it.availability == "available" }.map { it.trackId }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var knownLocalUris by remember(playlistId, auraScope, detail?.tracks) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var libraryCandidates by remember(playlistId, auraScope, lastRefresh?.requestId) {
        mutableStateOf<List<com.aura.music.data.local.TrackListRow>>(emptyList())
    }
    var inPlaylistSuggestions by remember(playlistId, auraScope, lastRefresh?.requestId, detail?.tracks) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var inLibrarySuggestions by remember(playlistId, auraScope, lastRefresh?.requestId, detail?.tracks, libraryCandidates) {
        mutableStateOf<Map<String, com.aura.music.data.local.TrackListRow>>(emptyMap())
    }
    androidx.compose.runtime.LaunchedEffect(playlistId, auraScope, lastRefresh?.requestId, detail?.tracks, libraryCandidates) {
        val owner = auraScope ?: return@LaunchedEffect
        val (playlistLinks, libraryLinks) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val uncertain = lastRefresh?.uncertain.orEmpty()
            val rows = detail?.tracks.orEmpty()
            suggestedPlaylistLinks(uncertain, rows) to suggestedLibraryTracks(uncertain, libraryCandidates, rows)
        }
        if (appContainer.authSessionManager.isCurrent(owner)) {
            inPlaylistSuggestions = playlistLinks
            inLibrarySuggestions = libraryLinks
        }
    }
    androidx.compose.runtime.LaunchedEffect(playlistId, auraScope, detail?.tracks) {
        val owner = auraScope ?: return@LaunchedEffect
        val resolved = sourceRepository.resolveKnownLocalAudio(detail?.tracks.orEmpty())
        if (appContainer.authSessionManager.isCurrent(owner)) {
            knownLocalUris = resolved.mapNotNull { row -> row.contentUri?.let { row.trackId to it } }.toMap()
        }
    }
    androidx.compose.runtime.LaunchedEffect(playlistId, auraScope, lastRefresh?.requestId) {
        val owner = auraScope ?: return@LaunchedEffect
        if (lastRefresh?.uncertain.isNullOrEmpty()) return@LaunchedEffect
        val candidates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val keys = lastRefresh?.uncertain.orEmpty().mapNotNull { metadataMatchKey(it.title, it.artist) }.toSet()
            repository.getAllTracks().filter { metadataMatchKey(it.title, it.artistName) in keys }
        }
        if (appContainer.authSessionManager.isCurrent(owner)) libraryCandidates = candidates
    }
    androidx.compose.runtime.LaunchedEffect(
        playlistId, auraScope, sourceState?.userId, lastRefresh?.requestId,
        showAcquisitionReport, isOnline, needsCloudCheck,
    ) {
        if (!isOnline) return@LaunchedEffect
        if (showAcquisitionReport || needsCloudCheck) {
            while (true) {
                playlistPlayback.verifyLater(playlistId)
                kotlinx.coroutines.delay(10_000)
            }
        } else if (sourceState != null) playlistPlayback.verifyLater(playlistId)
    }
    fun currentDeviceId(): String = android.provider.Settings.Secure.getString(
        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
    ) ?: "android_pixel_device"

    fun refreshSource(
        confirmed: List<String> = emptyList(), links: Map<String, String> = emptyMap(),
        libraryLinks: Map<String, com.aura.music.data.local.TrackListRow> = emptyMap(),
    ) {
        if (isRefreshingSource) return
        val owner = auraScope ?: return
        if (!appContainer.authSessionManager.isCurrent(owner)) return
        isRefreshingSource = true
        scope.launch {
            try {
                val confirmedLinks = links.toMutableMap()
                for ((sourceId, candidate) in libraryLinks) {
                    if (!appContainer.authSessionManager.isCurrent(owner)) return@launch
                    val added = repository.addTrackToPlaylist(playlistId, candidate.id, contextType = "playlist_source_link", isCurrent = { appContainer.authSessionManager.isCurrent(owner) })
                    if (added is AddToPlaylistResult.Error) throw PlaylistSourceReadException(added.message)
                    if (!appContainer.authSessionManager.isCurrent(owner)) return@launch
                    val occurrence = repository.getPlaylistDetail(playlistId)?.tracks?.lastOrNull { it.trackId == candidate.id }
                        ?: throw PlaylistSourceReadException("Le titre n’a pas pu être retrouvé dans la playlist.")
                    confirmedLinks[sourceId] = occurrence.playlistItemId
                }
                if (!appContainer.authSessionManager.isCurrent(owner)) return@launch
                val result = sourceRepository.refresh(playlistId, currentDeviceId(), confirmed, confirmedLinks)
                if (!appContainer.authSessionManager.isCurrent(owner)) return@launch
                if (result.uncertain.isEmpty()) snackbarHostState.showSnackbar("Playlist actualisée")
                else showUncertainTracks = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbarHostState.showSnackbar(
                    (failure as? PlaylistSourceReadException)?.message ?: "L’actualisation n’a pas pu être confirmée.",
                )
            } finally {
                isRefreshingSource = false
            }
        }
    }
    fun saveSource(reference: PlaylistSourceReference?, revision: Long) {
        if (isSavingSource) return
        isSavingSource = true
        scope.launch {
            try {
                val error = sourceRepository.set(playlistId, currentDeviceId(), reference, revision)
                snackbarHostState.showSnackbar(error ?: "Source de la playlist mise à jour.")
            } finally {
                isSavingSource = false
            }
        }
    }

    var playRequest by remember(playlistId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun playEntries(rows: List<PlaylistTrackRow>, shuffle: Boolean, occurrence: String? = null) {
        playRequest?.cancel()
        playRequest = scope.launch {
            val eligible = playlistPlayback.prepare(playlistId, rows)
            playlistPlayback.verifyLater(playlistId)
            val ordered = if (shuffle) eligible.shuffled() else eligible
            val index = if (occurrence == null) 0 else ordered.indexOfFirst { it.internalId == occurrence }
            val selected = ordered.getOrNull(index)
            if (selected == null) {
                snackbarHostState.showSnackbar("Aucune source de lecture confirmée pour ce choix.")
                return@launch
            }
            playerViewModel.onEvent(com.aura.music.domain.player.PlayerEvent.PlayTrack(
                selected.trackId, "playlist", playlistId, ordered, index,
            ))
        }
    }
    fun requestCloud(trackId: String? = null) {
        if (requestingCloud) return
        requestingCloud = true
        scope.launch {
            try {
                val auth = appContainer.authSessionManager
                val owner = auraScope ?: run {
                    snackbarHostState.showSnackbar("Connecte-toi à Aura pour demander la disponibilité cloud.")
                    return@launch
                }
                if (!auth.isCurrent(owner)) return@launch
                playlistPlayback.request(playlistId, currentDeviceId(), trackId, owner)
                if (!auth.isCurrent(owner)) return@launch
                snackbarHostState.showSnackbar("Demande cloud enregistrée")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbarHostState.showSnackbar(
                    (failure as? PlaylistSourceReadException)?.message ?: "La demande cloud n’a pas pu être confirmée. Réessaie.",
                )
            } finally {
                requestingCloud = false
            }
        }
    }

    RouteScaffold(title = detail?.summary?.name ?: "Playlist", onNavigateBack = onNavigateBack, snackbarHostState = snackbarHostState) {
        if (detail == null) {
            EmptyStateSurface("Playlist introuvable", "Cette playlist n'existe plus localement.")
        } else {
            com.aura.music.ui.components.AuraLazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    // 1. Hero Cover (Mosaïque 2x2 ou Cover unique)
                    val previewCovers = remember(detail.tracks) {
                        detail.tracks.mapNotNull { it.coverUri }.filter { it.isNotBlank() }.distinct().take(4)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        PlaylistMosaicCover(
                            modifier = Modifier.size(220.dp),
                            coverUri = detail.summary.coverUri,
                            previewCovers = previewCovers,
                            shape = RoundedCornerShape(20.dp),
                            iconSize = 64.dp,
                        )
                    }

                    // 2. Metadata (Titre, Pistes, Durée totale)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            detail.summary.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                        val totalDurationMs = remember(detail.tracks) { detail.tracks.sumOf { it.durationMs ?: 0L } }
                        val durationMinutes = totalDurationMs / 60000L
                        val durationText = if (durationMinutes > 0) " • ${durationMinutes} min" else ""
                        Text(
                            "${detail.summary.itemCount} piste(s)$durationText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        if (sourceName != null) {
                            Text("Source · $sourceName", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        if (isRefreshingSource) Text("Actualisation en cours…", style = MaterialTheme.typography.bodySmall)

                    }
                }

                item {
                    // 3. Action Bar (Boutons ronds Play & Shuffle à gauche, Switch & MoreVert à droite)
                    val notDownloadedTracks = remember(detail.tracks) {
                        detail.tracks.filter { it.contentUri.isNullOrBlank() }
                    }
                    val isAllDownloaded = remember(detail.tracks, notDownloadedTracks) {
                        detail.tracks.isNotEmpty() && notDownloadedTracks.isEmpty()
                    }
                    var isBatchDownloading by remember { mutableStateOf(false) }
                    val batchDownloadJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

                    // Auto-téléchargement persistant dès que de nouvelles pistes manquent et que l'option est active
                    androidx.compose.runtime.LaunchedEffect(isAutoDownloadPlaylist, detail.tracks, isOnline) {
                        if (isAutoDownloadPlaylist && isOnline && !isBatchDownloading && detail.tracks.isNotEmpty()) {
                            val toDownload = detail.tracks.filter { it.contentUri.isNullOrBlank() }
                            if (toDownload.isNotEmpty()) {
                                isBatchDownloading = true
                                val job = launch {
                                    try {
                                        val semaphore = kotlinx.coroutines.sync.Semaphore(2)
                                        kotlinx.coroutines.coroutineScope {
                                            toDownload.forEach { track ->
                                                launch {
                                                    semaphore.withPermit {
                                                        cloudFileRepository.downloadTrack(
                                                            trackId = track.trackId,
                                                            title = track.title,
                                                            artistName = track.artistName,
                                                            albumTitle = track.albumTitle,
                                                            durationMs = track.durationMs,
                                                            artistId = track.artistId,
                                                            albumId = track.albumId,
                                                            coverUri = track.coverUri,
                                                        ).collect { }
                                                    }
                                                }
                                            }
                                        }
                                    } finally {
                                        isBatchDownloading = false
                                        refreshTick++
                                    }
                                }
                                batchDownloadJob.value = job
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Play & Shuffle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            // Grand bouton Play principal
                            IconButton(
                                onClick = {
                                    playEntries(detail.tracks, false)
                                },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(BlazeOrange, CircleShape),
                                enabled = detail.tracks.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Tout lire",
                                    tint = DeepBlack,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // Bouton Shuffle secondaire
                            IconButton(
                                onClick = {
                                    playEntries(detail.tracks, true)
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(DarkGraphite, CircleShape),
                                enabled = detail.tracks.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = "Lecture aléatoire",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Switch Téléchargement & Menu 3-points
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(
                                checked = isAutoDownloadPlaylist,
                                enabled = detail.tracks.isNotEmpty(),
                                thumbContent = if (isBatchDownloading) {
                                    {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.5.dp,
                                            color = BlazeOrange
                                        )
                                    }
                                } else if (isAutoDownloadPlaylist && isAllDownloaded) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.DownloadDone,
                                            contentDescription = "Disponible hors-ligne",
                                            tint = BlazeOrange,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                } else {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = "Télécharger la playlist",
                                            tint = if (isAutoDownloadPlaylist) BlazeOrange else TextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                },
                                onCheckedChange = { checked ->
                                    isAutoDownloadPlaylist = checked
                                    sharedPrefs.edit().putBoolean("auto_download_playlist_$playlistId", checked).apply()
                                    if (checked) {
                                        if (notDownloadedTracks.isNotEmpty()) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Téléchargement de ${notDownloadedTracks.size} morceau(x) pour l'écoute hors-ligne...")
                                            }
                                        }
                                    } else {
                                        batchDownloadJob.value?.cancel()
                                        batchDownloadJob.value = null
                                        isBatchDownloading = false
                                        scope.launch {
                                            val downloadedTracks = detail.tracks.filter { !it.contentUri.isNullOrBlank() }
                                            downloadedTracks.forEach { track ->
                                                cloudFileRepository.removeLocalFile(track.trackId)
                                            }
                                            refreshTick++
                                            snackbarHostState.showSnackbar("Fichiers locaux supprimés. Titres toujours disponibles sur le Cloud.")
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = BlazeOrange,
                                    uncheckedThumbColor = DarkGraphite,
                                    uncheckedTrackColor = ElevatedGraphite,
                                    uncheckedBorderColor = Color.Transparent
                                )
                            )

                            Box {
                                IconButton(
                                    onClick = { showSyncOptions = false; showMenuOpen = true },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = "Options de la playlist",
                                        tint = TextSecondary
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMenuOpen,
                                    onDismissRequest = { showMenuOpen = false; showSyncOptions = false },
                                ) {
                                    if (showSyncOptions) {
                                        DropdownMenuItem(
                                            text = { Text("Retour") },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null) },
                                            onClick = { showSyncOptions = false },
                                        )
                                        if (sourceName != null) {
                                            DropdownMenuItem(
                                                text = { Text("Changer la source") },
                                                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                                                enabled = !isSavingSource,
                                                onClick = { sourceEditRevision = sourceState?.sourceRevision ?: 0; showMenuOpen = false },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Retirer la source") },
                                                leadingIcon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
                                                enabled = !isSavingSource,
                                                onClick = { showMenuOpen = false; sourceState?.let { saveSource(null, it.sourceRevision) } },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Demander les fichiers cloud") },
                                            leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                                            enabled = !requestingCloud,
                                            onClick = { showMenuOpen = false; requestCloud() },
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text(if (sourceName == null) "Associer une source" else "Actualiser depuis $sourceName") },
                                            leadingIcon = { Icon(if (sourceName == null) Icons.Rounded.Link else Icons.Rounded.Refresh, contentDescription = null) },
                                            enabled = !isSavingSource && !isRefreshingSource,
                                            onClick = {
                                                showMenuOpen = false
                                                if (sourceName == null) sourceEditRevision = sourceState?.sourceRevision ?: 0 else refreshSource()
                                            },
                                        )
                                        if (lastRefresh?.uncertain?.isNotEmpty() == true) {
                                            DropdownMenuItem(
                                                text = { Text("Vérifier les titres") },
                                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null) },
                                                enabled = !isRefreshingSource,
                                                onClick = { showMenuOpen = false; showUncertainTracks = true },
                                            )
                                        }
                                        if (sourceState != null) {
                                            DropdownMenuItem(
                                                text = { Text("Bilan de synchronisation") },
                                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null) },
                                                onClick = { showMenuOpen = false; showAcquisitionReport = true },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Options de synchronisation") },
                                            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                            onClick = { showSyncOptions = true },
                                        )
                                        androidx.compose.material3.HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Renommer") },
                                            onClick = {
                                                showRenameDialog = true
                                                showMenuOpen = false
                                            },
                                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Supprimer les doublons") },
                                            onClick = {
                                                showMenuOpen = false
                                                scope.launch {
                                                    try {
                                                        val removed = repository.deduplicatePlaylist(playlistId)
                                                        refreshTick++
                                                        if (removed > 0) {
                                                            snackbarHostState.showSnackbar("$removed doublon(s) supprimé(s)")
                                                        } else {
                                                            snackbarHostState.showSnackbar("Aucun doublon trouvé")
                                                        }
                                                    } catch (e: Exception) {
                                                        snackbarHostState.showSnackbar("Erreur : ${e.message}")
                                                    }
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = BlazeOrange) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Exporter (.m3u8)") },
                                            onClick = {
                                                showMenuOpen = false
                                                scope.launch {
                                                    try {
                                                        val report = appContainer.playlistImportExportManager.exportPlaylistToM3U8(playlistId)
                                                        exportReport = report
                                                    } catch (e: Exception) {
                                                        android.widget.Toast.makeText(context, "Erreur export : ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null, tint = BlazeOrange) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Supprimer la playlist", color = Color.Red) },
                                            onClick = {
                                                showDeleteDialog = true
                                                showMenuOpen = false
                                            },
                                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color.Red) },
                                        )
                                    }
                                }
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
                    itemsIndexed(
                        items = detail.tracks,
                        key = { _, track -> track.playlistItemId },
                        contentType = { _, _ -> "track_row" }
                    ) { index, track ->
                        val onPlayTrackClick = remember(track.playlistItemId, detail.tracks) {
                            { playEntries(detail.tracks, false, track.playlistItemId) }
                        }
                        val onRefreshClick = remember { { refreshTick++; Unit } }
                        val onAddToPlaylistClick = remember { { t: PlaylistTrackRow -> activeTrackForPlaylist = t } }
                        val onAddToQueueClick = remember(track, playlistId) {
                            {
                                scope.launch {
                                    val eligible = playlistPlayback.prepare(playlistId, listOf(track)).singleOrNull()
                                    if (eligible != null) {
                                        playerViewModel.onEvent(com.aura.music.domain.player.PlayerEvent.AddToQueue(
                                            eligible.copy(internalId = java.util.UUID.randomUUID().toString()),
                                        ))
                                    } else {
                                        playlistPlayback.verifyLater(playlistId)
                                        snackbarHostState.showSnackbar("Aucune source de lecture confirmée pour ce titre.")
                                    }
                                }
                                Unit
                            }
                        }
                        PlaylistTrackRowItem(
                            track = track.copy(contentUri = track.contentUri ?: knownLocalUris[track.trackId]),
                            playlistId = detail.summary.id,
                            repository = repository,
                            onPlayTrack = onPlayTrackClick,
                            onRefresh = onRefreshClick,
                            onAddToPlaylist = onAddToPlaylistClick,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                            onAddToQueue = onAddToQueueClick,
                            verifiedCloud = acquisitionsByTrack[track.trackId]?.availability?.let { it == "available" }
                                ?: (track.trackId in syncedCloudTrackIds),
                            canMoveUp = index > 0,
                            canMoveDown = index < detail.tracks.lastIndex,
                            cloudFileRepository = cloudFileRepository,
                            job = jobsByTrack[track.trackId],
                            acquisition = acquisitionsByTrack[track.trackId],
                            snackbarHostState = snackbarHostState,
                            onEditMetadata = { t -> trackToEditMetadata = t }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showAcquisitionReport) {
        val titles = remember(detail?.tracks) { detail?.tracks.orEmpty().associate { it.trackId to it.title } }
        AlertDialog(
            onDismissRequest = { showAcquisitionReport = false },
            title = { Text("Bilan de synchronisation") },
            text = {
                LazyColumn(Modifier.height(260.dp)) {
                    lastRefresh?.let { result ->
                        item {
                            Text("Dernière actualisation", style = MaterialTheme.typography.titleSmall)
                            Text("${result.addedCount} ajouté(s) · ${result.presentCount} déjà présent(s) · ${result.excludedCount} exclu(s)",
                                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            if (result.uncertain.isNotEmpty()) TextButton(onClick = {
                                showAcquisitionReport = false; showUncertainTracks = true
                            }) { Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null); Text("Vérifier les titres") }
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    when {
                        !isOnline -> item {
                            Text(if (acquisitionReport == null) "Hors connexion" else "Hors connexion · dernier bilan connu")
                        }
                        playlistId in verificationFailures -> item {
                            Text("Bilan temporairement indisponible.")
                            if (acquisitionReport != null) Text("Dernier bilan connu")
                            TextButton(onClick = { playlistPlayback.verifyLater(playlistId) }) {
                                Text("Réessayer la lecture")
                            }
                        }
                        acquisitionReport == null -> item { Text("Bilan en attente de confirmation.") }
                        acquisitionReport.isEmpty() -> item { Text("Aucun titre dans ce bilan.") }
                    }
                    itemsIndexed(acquisitionStatuses, key = { _, row -> row.trackId }) { _, row ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(titles[row.trackId] ?: "Titre de la playlist")
                            val label = when {
                                row.availability == "available" -> "Disponible dans le cloud"
                                row.errorCode == "source_identity_ambiguous" -> "Plusieurs versions sont associées à ce titre. Vérifie les correspondances de la playlist."
                                row.status == "source_not_authorized" -> "Source audio indisponible · aucun téléchargement lancé"
                                row.errorCode == "cloud_file_missing" -> "Fichier cloud introuvable · téléchargement à relancer"
                                row.errorCode == "acquisition_temporarily_unavailable" -> "Serveur temporairement indisponible · reprise automatique"
                                row.errorCode == "retry_requested" -> "Relance demandée"
                                row.status == "failed" || row.jobStatus == "failed" -> "Échec du téléchargement"
                                row.jobStatus == "requires_resolution" -> "Choix de version nécessaire"
                                row.status == "not_requested" -> "Aucune demande"
                                row.status == "cancelled" -> "Demande annulée"
                                row.jobStatus == "running" -> "Téléchargement en cours"
                                row.jobStatus == "queued" -> "Dans la file des téléchargements"
                                else -> "En attente de prise en charge"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(when {
                                    row.availability == "available" -> Icons.Rounded.CloudDone
                                    row.status == "failed" || row.status == "source_not_authorized" -> Icons.Rounded.ErrorOutline
                                    row.jobStatus == "running" -> Icons.Rounded.CloudDownload
                                    else -> Icons.Rounded.Schedule
                                }, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                                Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            if (row.availability != "available" && row.errorCode != "source_identity_ambiguous" &&
                                row.status in listOf("source_not_authorized", "failed", "cancelled")
                            ) TextButton(enabled = !requestingCloud, onClick = { requestCloud(row.trackId) }) { Text("Réessayer") }
                            if (row.availability != "available" && row.jobStatus == "requires_resolution") {
                                row.jobId?.let { jobId ->
                                    TextButton(onClick = {
                                        scope.launch {
                                            val auth = appContainer.authSessionManager
                                            val session = runCatching { auth.captureScope() }.getOrNull() ?: return@launch
                                            val candidates = appContainer.downloadRepository.getCandidatesForJob(jobId, auth.getBearerHeader())
                                            if (!auth.isCurrent(session)) return@launch
                                            if (candidates.isNullOrEmpty()) snackbarHostState.showSnackbar("Aucune version disponible.")
                                            else choosingVersion = jobId to candidates
                                        }
                                    }) { Text("Choisir la version") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAcquisitionReport = false }) { Text("Fermer") } },
        )
    }
    choosingVersion?.let { (jobId, candidates) ->
        AlertDialog(
            onDismissRequest = { choosingVersion = null },
            title = { Text("Choisir la version") },
            text = {
                LazyColumn(Modifier.height(220.dp)) {
                    itemsIndexed(candidates, key = { _, candidate -> candidate.videoId }) { _, candidate ->
                        TextButton(onClick = {
                            choosingVersion = null
                            scope.launch {
                                val auth = appContainer.authSessionManager
                                val owner = auraScope ?: return@launch
                                if (!auth.isCurrent(owner)) return@launch
                                val accepted = appContainer.downloadRepository.resolveJob(jobId, candidate.videoId, auth.getBearerHeader())
                                if (!auth.isCurrent(owner)) return@launch
                                if (!accepted) snackbarHostState.showSnackbar("Le choix de version n’a pas pu être confirmé. Réessaie.")
                                playlistPlayback.verifyLater(playlistId)
                            }
                        }) { Text("${candidate.title} · ${candidate.artist}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingVersion = null }) { Text("Annuler") } },
        )
    }

    if (showUncertainTracks && lastRefresh != null) {
        AlertDialog(
            onDismissRequest = { showUncertainTracks = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null) },
            title = { Text("Copies possibles") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item {
                        Text("Même titre et artiste : les copies uniques sont proposées ensemble. Choisis seulement les versions ambiguës.", style = MaterialTheme.typography.bodySmall)
                        val suggestions = inPlaylistSuggestions
                        val librarySuggestions = inLibrarySuggestions
                        if (suggestions.isNotEmpty() || librarySuggestions.isNotEmpty()) Button(onClick = {
                            showUncertainTracks = false
                            refreshSource(links = suggestions, libraryLinks = librarySuggestions)
                        }, enabled = !isRefreshingSource) {
                            Icon(Icons.Rounded.Link, contentDescription = null)
                            Text("Valider les ${suggestions.size + librarySuggestions.size} propositions")
                        }
                    }
                    itemsIndexed(lastRefresh.uncertain, key = { index, track -> track.trackId ?: index }) { _, track ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val key = metadataMatchKey(track.title, track.artist)
                            val proposed = detail?.tracks.orEmpty().filter { key != null && metadataMatchKey(it.title, it.artistName) == key }
                            val fromLibrary = inLibrarySuggestions[track.trackId]
                            val playlistCopy = proposed.singleOrNull()
                            PlaylistMatchTrackRow(track.title, track.artist, fromLibrary?.albumTitle ?: playlistCopy?.albumTitle ?: track.album,
                                fromLibrary?.coverUri ?: playlistCopy?.coverUri ?: track.coverUri,
                                hasLocalAudio = !fromLibrary?.contentUri.isNullOrBlank() || !playlistCopy?.contentUri.isNullOrBlank() || knownLocalUris[playlistCopy?.trackId] != null,
                                cloudAvailable = fromLibrary?.id in confirmedPlaylistCloudIds || playlistCopy?.trackId in confirmedPlaylistCloudIds,
                                showAvailability = fromLibrary != null || playlistCopy != null,
                            )
                            Text(if (inPlaylistSuggestions.containsKey(track.trackId) || fromLibrary != null)
                                "Copie proposée · validation groupée possible" else "Choisis la copie à réutiliser ou garde ce titre séparé",
                                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    showUncertainTracks = false
                                    val id = track.trackId ?: return@TextButton
                                    val occurrence = inPlaylistSuggestions[id]
                                    when {
                                        occurrence != null -> refreshSource(links = mapOf(id to occurrence))
                                        fromLibrary != null -> refreshSource(libraryLinks = mapOf(id to fromLibrary))
                                        else -> trackToLink = track
                                    }
                                }, enabled = track.trackId != null && !isRefreshingSource) {
                                    Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(if (inPlaylistSuggestions.containsKey(track.trackId) || fromLibrary != null) "Réutiliser" else "Choisir")
                                }
                                TextButton(onClick = {
                                    showUncertainTracks = false
                                    track.trackId?.let { refreshSource(listOf(it)) }
                                }, enabled = track.trackId != null && !isRefreshingSource) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("Garder séparé")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showUncertainTracks = false }) { Text("Fermer") } },
        )
    }

    val linkCandidate = trackToLink
    if (linkCandidate != null && detail != null) {
        PlaylistMatchDialog(
            source = linkCandidate,
            rows = detail.tracks,
            library = libraryCandidates,
            cloudIds = confirmedPlaylistCloudIds,
            localAudioIds = knownLocalUris.keys,
            onDismiss = { trackToLink = null },
            onPlaylistChoice = { track ->
                trackToLink = null
                linkCandidate.trackId?.let { refreshSource(links = mapOf(it to track.playlistItemId)) }
            },
            onLibraryChoice = { track ->
                trackToLink = null
                linkCandidate.trackId?.let { refreshSource(libraryLinks = mapOf(it to track)) }
            },
        )
    }

    sourceEditRevision?.let { revision ->
        PlaylistSourceDialog(
            importManager = appContainer.playlistImportExportManager,
            spotifyAuthManager = appContainer.spotifyAuthManager,
            onDismiss = { sourceEditRevision = null },
            onConfirm = { reference ->
                sourceEditRevision = null
                saveSource(reference, revision)
            },
        )
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

    if (exportReport != null) {
        PlaylistExportReportDialog(
            report = exportReport!!,
            onDismiss = { exportReport = null }
        )
    }

    if (activeTrackForPlaylist != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { activeTrackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                val track = activeTrackForPlaylist!!
                activeTrackForPlaylist = null
                scope.launch {
                    val result = repository.addTrackToPlaylist(
                        playlistId = playlist.id,
                        trackId = track.trackId,
                        contextType = "playlist",
                        title = track.title,
                        artistName = track.artistName,
                        albumTitle = track.albumTitle,
                        durationMs = track.durationMs,
                        coverUri = track.coverUri
                    )
                    if (result is AddToPlaylistResult.AlreadyExists) {
                        pendingDuplicatePrompt = result
                    } else {
                        refreshTick++
                    }
                }
            }
        )
    }

    pendingDuplicatePrompt?.let { prompt ->
        DuplicateTrackInPlaylistDialog(
            playlistName = prompt.playlistName,
            onConfirm = {
                scope.launch {
                    repository.addTrackToPlaylist(
                        playlistId = prompt.playlistId,
                        trackId = prompt.trackId,
                        contextType = "playlist",
                        allowDuplicate = true
                    )
                    pendingDuplicatePrompt = null
                    refreshTick++
                }
            },
            onDismiss = { pendingDuplicatePrompt = null }
        )
    }

    if (trackToEditMetadata != null) {
        EditTrackMetadataBottomSheet(
            track = trackToEditMetadata!!,
            apiService = appContainer.auraApiService,
            localLibraryRepository = repository,
            onDismiss = { trackToEditMetadata = null },
            onTrackUpdated = {
                trackToEditMetadata = null
                refreshTick++
            }
        )
    }
}

internal fun com.aura.music.data.network.PlaylistAcquisitionStatus.isAcquisitionInProgress(): Boolean =
    availability != "available" && (status == "pending" ||
        (status == "admitted" && (jobStatus == null || jobStatus == "queued" || jobStatus == "running" ||
            errorCode == "retry_requested" || errorCode == "acquisition_temporarily_unavailable")))

@Composable
private fun PlaylistTrackRowItem(
    track: PlaylistTrackRow,
    playlistId: String,
    repository: LocalLibraryRepository,
    onPlayTrack: () -> Unit,
    onRefresh: () -> Unit,
    onAddToPlaylist: (PlaylistTrackRow) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onAddToQueue: () -> Unit,
    verifiedCloud: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    cloudFileRepository: com.aura.music.data.repository.CloudFileRepository,
    job: com.aura.music.data.local.DownloadJobEntity?,
    acquisition: com.aura.music.data.network.PlaylistAcquisitionStatus?,
    snackbarHostState: SnackbarHostState,
    onEditMetadata: ((com.aura.music.data.local.TrackListRow) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    val onRemoveClick = remember(track.playlistItemId, playlistId) {
        {
            scope.launch {
                repository.removeTrackFromPlaylist(playlistId, track.playlistItemId)
                onRefresh()
            }
            Unit
        }
    }
    val onAddToPlaylistClick = remember(track.trackId) { { onAddToPlaylist(track) } }
    val onLikeClick = remember(track.trackId, playlistId) {
        {
            scope.launch {
                repository.toggleLike(track.trackId, currentlyLiked = false, contextType = "playlist", contextId = playlistId)
                onRefresh()
            }
            Unit
        }
    }
    val onUnlikeClick = remember(track.trackId, playlistId) {
        {
            scope.launch {
                repository.toggleLike(track.trackId, currentlyLiked = true, contextType = "playlist", contextId = playlistId)
                onRefresh()
            }
            Unit
        }
    }
    val onViewArtistClick = remember(track.artistId) {
        track.artistId?.let { artistId -> { onOpenArtist(artistId) } }
    }
    val onViewAlbumClick = remember(track.albumId) {
        track.albumId?.let { albumId -> { onOpenAlbum(albumId) } }
    }

    val isLocalScanned = track.contentUri?.startsWith("content://") == true || track.contentUri?.startsWith("file://") == true || track.contentUri?.startsWith("/") == true
    val isAlreadySynced = verifiedCloud
    val onUploadToCloudLambda = remember(track.trackId, isLocalScanned, isAlreadySynced) {
        if (isLocalScanned && !isAlreadySynced) {
            {
                scope.launch {
                    snackbarHostState.showSnackbar("Upload lancé pour : ${track.title}")
                    cloudFileRepository.uploadTrack(track.trackId).collect { res ->
                        res.onSuccess {
                            snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                            cloudFileRepository.refreshSyncedTrackIds()
                        }.onFailure { err ->
                            snackbarHostState.showSnackbar("Échec de l'upload : ${err.message}")
                        }
                    }
                }
                Unit
            }
        } else null
    }

    val isDownloadedLocally = !track.contentUri.isNullOrBlank()
    val isCloudOnly = !isDownloadedLocally && verifiedCloud
    val downloadStatus = playlistTrackDownloadStatus(isDownloadedLocally, verifiedCloud, acquisition, job)

    val onTrackRowClick = onPlayTrack

    val onDownloadFromCloudLambda = remember(track.trackId, isCloudOnly) {
        if (isCloudOnly) {
            {
                scope.launch {
                    snackbarHostState.showSnackbar("Téléchargement sur l'appareil lancé pour : ${track.title}")
                    cloudFileRepository.downloadTrack(
                        trackId = track.trackId,
                        title = track.title,
                        artistName = track.artistName,
                        albumTitle = track.albumTitle,
                        durationMs = track.durationMs,
                        artistId = track.artistId,
                        albumId = track.albumId,
                        coverUri = track.coverUri
                    ).collect { res ->
                        res.onSuccess {
                            snackbarHostState.showSnackbar("Téléchargement réussi : ${track.title}")
                            repository.refreshLocalMediaIndex()
                            onRefresh()
                        }.onFailure { err ->
                            snackbarHostState.showSnackbar("Échec du téléchargement : ${err.message}")
                        }
                    }
                }
                Unit
            }
        } else null
    }

    val reassignAudioCallback = com.aura.music.ui.components.LocalReassignAudio.current
    val onChangeAudioVersionLambda = remember(track.trackId, reassignAudioCallback != null) {
        if (reassignAudioCallback != null) {
            {
                reassignAudioCallback(
                    com.aura.music.ui.components.ReassignAudioTarget(
                        trackId = track.trackId,
                        title = track.title,
                        artist = track.artistName ?: "Artiste inconnu",
                        album = track.albumTitle,
                        coverUri = track.coverUri
                    )
                )
            }
        } else null
    }

    SharedTrackRowItem(
        title = track.title,
        subtitle = track.artistName ?: "Artiste inconnu",
        onClick = onTrackRowClick,
        coverUri = track.coverUri,
        contextType = "playlist",
        isLiked = track.isLiked,
        downloadStatus = downloadStatus,
        isCloudOnly = isCloudOnly,
        isCloudPending = playlistCloudPending(isDownloadedLocally, verifiedCloud, acquisition),
        cloudUnavailableReason = playlistCloudUnavailableReason(isDownloadedLocally, verifiedCloud, acquisition, job),
        onRemoveFromPlaylist = onRemoveClick,
        onAddToQueue = onAddToQueue,
        onAddToPlaylist = onAddToPlaylistClick,
        onLike = onLikeClick,
        onUnlike = onUnlikeClick,
        onViewArtist = onViewArtistClick,
        onViewAlbum = onViewAlbumClick,
        onUploadToCloud = onUploadToCloudLambda,
        onDownloadFromCloud = onDownloadFromCloudLambda,
        onEditMetadata = if (onEditMetadata != null) { { onEditMetadata(track.toTrackListRow()) } } else null,
        onChangeAudioVersion = onChangeAudioVersionLambda,
        onMovePlaylistItemUp = if (canMoveUp) {
            {
                scope.launch {
                    repository.movePlaylistItem(playlistId, track.playlistItemId, -1)
                    onRefresh()
                }
            }
        } else null,
        onMovePlaylistItemDown = if (canMoveDown) {
            {
                scope.launch {
                    repository.movePlaylistItem(playlistId, track.playlistItemId, 1)
                    onRefresh()
                }
            }
        } else null,
    )
}
