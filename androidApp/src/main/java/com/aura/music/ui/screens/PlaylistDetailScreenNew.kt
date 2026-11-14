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
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import com.aura.music.ui.screens.PlaylistMosaicCover
import com.aura.music.ui.toQueuedTrack
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
    val sourceRepository = appContainer.playlistSourceRepository
    val sourceState by remember(sourceRepository, playlistId) { sourceRepository.observe(playlistId) }
        .collectAsState(initial = null)
    val sourceProvider = sourceState?.sourceProvider
    val sourceName = when (sourceProvider) {
        "spotify" -> "Spotify"
        "deezer" -> "Deezer"
        else -> null
    }
    var sourceEditRevision by remember(playlistId) { mutableStateOf<Long?>(null) }
    var isSavingSource by remember(playlistId) { mutableStateOf(false) }
    var isRefreshingSource by remember(playlistId) { mutableStateOf(false) }
    var showUncertainTracks by remember(playlistId) { mutableStateOf(false) }
    var trackToLink by remember(playlistId) { mutableStateOf<com.aura.music.data.network.PlaylistTrackMetadata?>(null) }
    val lastRefresh = remember(sourceState?.lastRefreshJson) {
        sourceState?.lastRefreshJson?.let { runCatching { Json.decodeFromString<PlaylistRefreshResult>(it) }.getOrNull() }
    }
    val cloudFileRepository = appContainer.cloudFileRepository
    val downloadJobs by appContainer.downloadRepository.getAllJobs().collectAsState(initial = emptyList())
    val isOnline by appContainer.connectivityObserver.isOnline.collectAsState(initial = true)
    val syncedCloudTrackIds by cloudFileRepository.syncedTrackIds.collectAsState(initial = emptySet())
    val cloudFilesState = produceState(initialValue = emptyList<com.aura.music.data.network.SyncedFileResponseData>(), cloudFileRepository, refreshTick, refreshToken) {
        cloudFileRepository.listCloudFiles().collect { res ->
            res.onSuccess { value = it }
        }
    }
    val cloudFiles = cloudFilesState.value
    val snackbarHostState = remember { SnackbarHostState() }
    fun currentDeviceId(): String = android.provider.Settings.Secure.getString(
        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
    ) ?: "android_pixel_device"

    fun refreshSource(confirmed: List<String> = emptyList(), links: Map<String, String> = emptyMap()) {
        if (isRefreshingSource) return
        isRefreshingSource = true
        scope.launch {
            try {
                val result = sourceRepository.refresh(playlistId, currentDeviceId(), confirmed, links)
                snackbarHostState.showSnackbar("${result.addedCount} titre(s) ajouté(s) · ${result.uncertain.size} à vérifier")
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
                        if (lastRefresh != null) {
                            val completed = remember(lastRefresh.completedAt) {
                                runCatching {
                                    java.time.Instant.parse(lastRefresh.completedAt)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT))
                                }.getOrNull()
                            }
                            if (completed != null) {
                                Text("Dernière actualisation · $completed", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            Text("${lastRefresh.addedCount} ajouté(s) · ${lastRefresh.excludedCount} exclu(s)", style = MaterialTheme.typography.bodySmall)
                            if (lastRefresh.uncertain.isNotEmpty()) {
                                TextButton(onClick = { showUncertainTracks = true }, enabled = !isRefreshingSource) {
                                    Text("Vérifier ${lastRefresh.uncertain.size} correspondance(s)")
                                }
                            }
                        }
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
                                    val tracks = detail.tracks.map { it.toTrackListRow() }
                                    if (tracks.isNotEmpty()) {
                                        playPlaylist(playerViewModel, tracks, false, detail.summary.id)
                                    }
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
                                    val tracks = detail.tracks.map { it.toTrackListRow() }.shuffled()
                                    if (tracks.isNotEmpty()) {
                                        playPlaylist(playerViewModel, tracks, true, detail.summary.id)
                                    }
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
                                            val playlistTrackIds = detail.tracks.map { it.trackId }.toSet()
                                            val userToken = appContainer.authSessionManager.getBearerHeader()
                                            downloadJobs.filter { it.trackId in playlistTrackIds && it.status in setOf("queued", "running", "requires_resolution") }
                                                .forEach { job ->
                                                    appContainer.downloadRepository.deleteJob(job.id, userToken)
                                                }

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
                                    onClick = { showMenuOpen = true },
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
                                    onDismissRequest = { showMenuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(if (sourceName == null) "Associer une source" else "Changer la source") },
                                        enabled = !isSavingSource,
                                        onClick = {
                                            sourceEditRevision = sourceState?.sourceRevision ?: 0
                                            showMenuOpen = false
                                        },
                                    )
                                    if (sourceName != null) {
                                        DropdownMenuItem(
                                            text = { Text("Actualiser depuis $sourceName") },
                                            enabled = !isSavingSource && !isRefreshingSource,
                                            onClick = {
                                                showMenuOpen = false
                                                refreshSource()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Retirer la source") },
                                            enabled = !isSavingSource,
                                            onClick = {
                                                showMenuOpen = false
                                                sourceState?.let { saveSource(null, it.sourceRevision) }
                                            },
                                        )
                                    }
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
                        val onPlayTrackClick = remember(track.trackId, detail.tracks) {
                            {
                                val tracks = detail.tracks.map { it.toTrackListRow() }
                                playPlaylist(playerViewModel, tracks, false, detail.summary.id, track.trackId)
                            }
                        }
                        val onRefreshClick = remember { { refreshTick++; Unit } }
                        val onAddToPlaylistClick = remember { { t: PlaylistTrackRow -> activeTrackForPlaylist = t } }
                        val onAddToQueueClick = remember(track.trackId) {
                            {
                                playerViewModel.onEvent(com.aura.music.domain.player.PlayerEvent.AddToQueue(track.toTrackListRow().toQueuedTrack()))
                            }
                        }
                        PlaylistTrackRowItem(
                            track = track,
                            playlistId = detail.summary.id,
                            repository = repository,
                            onPlayTrack = onPlayTrackClick,
                            onRefresh = onRefreshClick,
                            onAddToPlaylist = onAddToPlaylistClick,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                            onAddToQueue = onAddToQueueClick,
                            canMoveUp = index > 0,
                            canMoveDown = index < detail.tracks.lastIndex,
                            cloudFileRepository = cloudFileRepository,
                            syncedCloudTrackIds = syncedCloudTrackIds,
                            cloudFiles = cloudFiles,
                            downloadJobs = downloadJobs,
                            snackbarHostState = snackbarHostState,
                            onEditMetadata = { t -> trackToEditMetadata = t }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showUncertainTracks && lastRefresh != null) {
        AlertDialog(
            onDismissRequest = { showUncertainTracks = false },
            title = { Text("Correspondances à vérifier") },
            text = {
                LazyColumn(Modifier.height(220.dp)) {
                    itemsIndexed(lastRefresh.uncertain, key = { index, track -> track.trackId ?: index }) { _, track ->
                        Column {
                            Text("${track.title} · ${track.artist}")
                            TextButton(onClick = {
                                showUncertainTracks = false
                                track.trackId?.let { refreshSource(listOf(it)) }
                            }, enabled = track.trackId != null) { Text("Ajouter comme titre distinct") }
                            TextButton(onClick = {
                                showUncertainTracks = false
                                trackToLink = track
                            }, enabled = track.trackId != null) { Text("Associer à une occurrence") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showUncertainTracks = false }) { Text("Fermer") } },
        )
    }

    val linkCandidate = trackToLink
    if (linkCandidate != null && detail != null) {
        AlertDialog(
            onDismissRequest = { trackToLink = null },
            title = { Text("Occurrence pour ${linkCandidate.title}") },
            text = {
                LazyColumn(Modifier.height(260.dp)) {
                    itemsIndexed(detail.tracks, key = { _, track -> track.playlistItemId }) { index, track ->
                        TextButton(onClick = {
                            trackToLink = null
                            linkCandidate.trackId?.let { refreshSource(links = mapOf(it to track.playlistItemId)) }
                        }) { Text("${index + 1}. ${track.title} · ${track.artistName.orEmpty()}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { trackToLink = null }) { Text("Annuler") } },
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
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    cloudFileRepository: com.aura.music.data.repository.CloudFileRepository,
    syncedCloudTrackIds: Set<String>,
    cloudFiles: List<com.aura.music.data.network.SyncedFileResponseData> = emptyList(),
    downloadJobs: List<com.aura.music.data.local.DownloadJobEntity> = emptyList(),
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
    val isPresentInCloud = syncedCloudTrackIds.contains(track.trackId) ||
        syncedCloudTrackIds.any { isDeezerTrackMatch(it, track.trackId) } ||
        cloudFiles.any { cloud ->
            isDeezerTrackMatch(cloud.trackId, track.trackId) ||
            (cloud.title?.trim().equals(track.title.trim(), ignoreCase = true) &&
             (cloud.artistName?.trim().equals(track.artistName?.trim(), ignoreCase = true) || track.artistName.isNullOrBlank() || cloud.artistName.isNullOrBlank()) &&
             (cloud.albumTitle?.trim().equals(track.albumTitle?.trim(), ignoreCase = true) || track.albumTitle.isNullOrBlank() || cloud.albumTitle.isNullOrBlank()))
        }
    val isAlreadySynced = isPresentInCloud
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
    val isCloudOnly = !isDownloadedLocally
    val job = remember(downloadJobs, track.trackId) {
        downloadJobs.find { it.trackId == track.trackId }
    }

    val downloadStatus = when {
        job != null && job.status == "queued" -> TrackDownloadStatus.Queued
        job != null && job.status == "running" -> TrackDownloadStatus.Downloading(job.progressPercent ?: 0f)
        job != null && job.status == "failed" -> TrackDownloadStatus.Failed(job.errorMessage ?: "Échec du téléchargement")
        isDownloadedLocally -> TrackDownloadStatus.Downloaded
        else -> TrackDownloadStatus.NotDownloaded
    }

    val onTrackRowClick: () -> Unit = {
        if (downloadStatus is TrackDownloadStatus.Queued || downloadStatus is TrackDownloadStatus.Downloading) {
            scope.launch {
                snackbarHostState.showSnackbar("Téléchargement Cloud en cours pour : ${track.title}")
            }
        } else {
            onPlayTrack()
        }
    }

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
