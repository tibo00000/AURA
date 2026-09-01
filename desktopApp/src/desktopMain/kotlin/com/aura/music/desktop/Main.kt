package com.aura.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.aura.music.data.local.*
import com.aura.music.data.network.HistoryItemResponse
import com.aura.music.data.network.KtorAuraApiService
import com.aura.music.data.player.QueueManager
import com.aura.music.desktop.security.DesktopSecureStorage
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.desktop.ui.components.*
import com.aura.music.desktop.ui.screens.*
import com.aura.music.domain.player.DesktopAudioPlayer
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun main() = application {
    var isVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(1360.dp, 840.dp)
    )

    val secureStorage = remember { DesktopSecureStorage.createDefault() }
    val database = remember { AuraDatabase.getInstance(null) }
    val audioPlayer = remember { DesktopAudioPlayer() }
    val queueManager = remember { QueueManager() }
    val apiService = remember { KtorAuraApiService.createDefault() }

    val orchestrator = remember {
        DesktopPlaybackOrchestrator(database, audioPlayer, queueManager, apiService).apply {
            val savedToken = secureStorage.getSecret("supabase_token")
            apiToken = if (savedToken != null && !savedToken.contains("supabase_token_")) {
                savedToken
            } else {
                val defaultToken = "Bearer 12345678-1234-1234-1234-1234567890ab"
                secureStorage.saveSecret("supabase_token", defaultToken)
                defaultToken
            }
        }
    }

    val appState = remember { DesktopAppState() }
    val coroutineScope = rememberCoroutineScope()

    // Données réactives de la base Room
    var allTracks by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }
    var likedTracks by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistListRow>>(emptyList()) }
    var playlistTracks by remember { mutableStateOf<List<PlaylistTrackRow>>(emptyList()) }
    var currentPlaylistName by remember { mutableStateOf("") }
    var localAlbums by remember { mutableStateOf<List<AlbumBrowseRow>>(emptyList()) }
    var localArtists by remember { mutableStateOf<List<ArtistBrowseRow>>(emptyList()) }
    var history by remember { mutableStateOf<List<HistoryItemResponse>>(emptyList()) }

    val downloadJobs by remember(database) {
        database.downloadJobDao().getAllJobsWithTrackFlow()
    }.collectAsState(initial = emptyList())

    // Méthode de rechargement des données locales
    fun reloadDbData() {
        coroutineScope.launch(Dispatchers.IO) {
            allTracks = database.trackDao().getAllTracks()
            likedTracks = database.trackDao().getLikedTracks()
            playlists = database.playlistDao().getPlaylists()
            localAlbums = database.albumDao().getAllBrowseAlbums()
            localArtists = database.artistDao().getAllBrowseArtists()

            appState.selectedPlaylistId?.let { id ->
                val pl = database.playlistDao().getPlaylistDetail(id)
                if (pl != null) {
                    currentPlaylistName = pl.name
                    playlistTracks = database.playlistDao().getPlaylistTracks(id)
                }
            }

            orchestrator.apiToken?.let { token ->
                try {
                    val resp = apiService.getHistory(token)
                    if (resp.data?.items != null) {
                        history = resp.data!!.items.take(20)
                    }
                } catch (e: Exception) {
                    // Ignore transient history errors
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        orchestrator.connect {
            reloadDbData()
        }
        reloadDbData()
    }

    // Gestion de la visibilité pour l'orchestrateur (pause du rendu/tickers en arrière-plan)
    LaunchedEffect(isVisible) {
        orchestrator.isWindowVisible = isVisible
    }

    // Intégration System Tray
    val trayIcon = rememberVectorPainter(Icons.Rounded.MusicNote)
    Tray(
        icon = trayIcon,
        tooltip = "AURA Music Player",
        onAction = {
            isVisible = true
            orchestrator.isWindowVisible = true
        },
        menu = {
            Item("Ouvrir AURA", onClick = {
                isVisible = true
                orchestrator.isWindowVisible = true
            })
            Item("Lecture / Pause", onClick = { orchestrator.togglePlayPause() })
            Item("Suivant", onClick = { orchestrator.next() })
            Item("Précédent", onClick = { orchestrator.previous() })
            Separator()
            Item("Quitter", onClick = {
                orchestrator.disconnect()
                exitApplication()
            })
        }
    )

    if (isVisible) {
        Window(
            onCloseRequest = {
                // Réduction dans le System Tray (pause rendu Skia et maintien de la musique)
                isVisible = false
                orchestrator.isWindowVisible = false
            },
            state = windowState,
            title = "AURA Music Player",
            onKeyEvent = { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && !appState.isInputFocused) {
                    when {
                        // Play / Pause sur Espace (si aucun champ texte focalisé)
                        keyEvent.key == Key.Spacebar -> {
                            orchestrator.togglePlayPause()
                            true
                        }
                        // Suivant : Ctrl + Flèche Droite
                        keyEvent.isCtrlPressed && keyEvent.key == Key.DirectionRight -> {
                            orchestrator.next()
                            true
                        }
                        // Précédent : Ctrl + Flèche Gauche
                        keyEvent.isCtrlPressed && keyEvent.key == Key.DirectionLeft -> {
                            orchestrator.previous()
                            true
                        }
                        // Focus Recherche : Ctrl + F
                        keyEvent.isCtrlPressed && keyEvent.key == Key.F -> {
                            appState.navigateToRoot("search")
                            true
                        }
                        // Ouvrir/Fermer Queue : Ctrl + Shift + Q (pas de conflit avec Ctrl+Q)
                        keyEvent.isCtrlPressed && keyEvent.isShiftPressed && keyEvent.key == Key.Q -> {
                            appState.toggleQueue()
                            true
                        }
                        // Quitter : Ctrl + Q
                        keyEvent.isCtrlPressed && !keyEvent.isShiftPressed && keyEvent.key == Key.Q -> {
                            orchestrator.disconnect()
                            exitApplication()
                            true
                        }
                        else -> false
                    }
                } else false
            }
        ) {
            AuraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepBlack
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 1. Structure 3 Volets
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // Volet Gauche : Sidebar
                            DesktopSidebar(
                                appState = appState,
                                playlists = playlists,
                                activeDownloadsCount = downloadJobs.count { it.status == "running" || it.status == "queued" }
                            )

                            // Volet Central : Écrans
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(DeepBlack)
                            ) {
                                when (appState.currentScreen) {
                                    "home" -> HomeScreen(
                                        allTracks = allTracks,
                                        likedTracks = likedTracks,
                                        playlists = playlists,
                                        history = history,
                                        orchestrator = orchestrator,
                                        appState = appState
                                    )
                                    "search" -> SearchScreen(
                                        allTracks = allTracks,
                                        allAlbums = localAlbums,
                                        allArtists = localArtists,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onToggleLike = {
                                            orchestrator.toggleLike(it) { reloadDbData() }
                                        }
                                    )
                                    "library" -> LibraryScreen(
                                        allTracks = allTracks,
                                        allAlbums = localAlbums,
                                        allArtists = localArtists,
                                        playlists = playlists,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onToggleLike = {
                                            orchestrator.toggleLike(it) { reloadDbData() }
                                        }
                                    )
                                    "favorites" -> FavoritesScreen(
                                        likedTracks = likedTracks,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onToggleLike = {
                                            orchestrator.toggleLike(it) { reloadDbData() }
                                        }
                                    )
                                    "playlist_detail" -> {
                                        val plId = appState.selectedPlaylistId ?: ""
                                        LaunchedEffect(plId) {
                                            reloadDbData()
                                        }
                                        PlaylistDetailScreen(
                                            playlistId = plId,
                                            playlistName = currentPlaylistName,
                                            playlistTracks = playlistTracks,
                                            orchestrator = orchestrator,
                                            appState = appState,
                                            onToggleLike = {
                                                orchestrator.toggleLike(it) { reloadDbData() }
                                            },
                                            onReloadData = { reloadDbData() }
                                        )
                                    }
                                    "artist_detail" -> ArtistDetailScreen(
                                        artistId = appState.selectedArtistId ?: "",
                                        allLocalTracks = allTracks,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onToggleLike = {
                                            orchestrator.toggleLike(it) { reloadDbData() }
                                        }
                                    )
                                    "album_detail" -> AlbumDetailScreen(
                                        albumId = appState.selectedAlbumId ?: "",
                                        allLocalTracks = allTracks,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onToggleLike = {
                                            orchestrator.toggleLike(it) { reloadDbData() }
                                        }
                                    )
                                    "downloads" -> DownloadsScreen(
                                        downloadJobs = downloadJobs,
                                        orchestrator = orchestrator,
                                        appState = appState
                                    )
                                    "cloud_sync" -> CloudSyncScreen(
                                        allTracks = allTracks,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onReloadData = { reloadDbData() }
                                    )
                                    "settings" -> SettingsScreen(
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        secureStorage = secureStorage,
                                        onReloadData = { reloadDbData() }
                                    )
                                }
                            }

                            // Volet Droit : Queue Panel rétractable
                            if (appState.isQueueOpen) {
                                DesktopQueuePanel(
                                    orchestrator = orchestrator,
                                    appState = appState
                                )
                            }
                        }

                        // 2. Barre de lecture inférieure fixe
                        DesktopPlayerBar(
                            orchestrator = orchestrator,
                            appState = appState,
                            onToggleLike = {
                                orchestrator.toggleLike(it) { reloadDbData() }
                            }
                        )
                    }

                    // Dialogues Modaux
                    DesktopCreatePlaylistDialog(
                        appState = appState,
                        orchestrator = orchestrator,
                        onPlaylistCreated = { reloadDbData() }
                    )

                    DesktopRenamePlaylistDialog(
                        appState = appState,
                        orchestrator = orchestrator,
                        onPlaylistRenamed = { reloadDbData() }
                    )

                    DesktopAddToPlaylistDialog(
                        appState = appState,
                        playlists = playlists,
                        orchestrator = orchestrator,
                        onTrackAdded = { reloadDbData() }
                    )
                }
            }
        }
    }
}
