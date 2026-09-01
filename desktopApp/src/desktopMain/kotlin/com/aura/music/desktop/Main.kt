package com.aura.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
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
import com.aura.music.desktop.domain.DesktopCloudSyncManager
import com.aura.music.desktop.domain.DesktopDownloadManager
import com.aura.music.desktop.domain.DesktopPlaylistManager
import com.aura.music.desktop.security.DesktopSecureStorage
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.components.*
import com.aura.music.desktop.ui.screens.*
import com.aura.music.desktop.ui.theme.*
import com.aura.music.domain.player.DesktopAudioPlayer
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
    val coroutineScope = rememberCoroutineScope()

    val cloudSyncManager = remember { DesktopCloudSyncManager(database, apiService, coroutineScope) }
    val downloadManager = remember { DesktopDownloadManager(database, apiService, coroutineScope) }
    val playlistManager = remember { DesktopPlaylistManager(database, cloudSyncManager) }

    val orchestrator = remember {
        DesktopPlaybackOrchestrator(
            database = database,
            audioPlayer = audioPlayer,
            queueManager = queueManager,
            apiService = apiService,
            playlistManager = playlistManager,
            cloudSyncManager = cloudSyncManager,
            downloadManager = downloadManager
        ).apply {
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

    // =======================================================================
    // FLUX RÉACTIFS ROOM (ZÉRO RELOAD MANUEL)
    // =======================================================================
    val allTracks by remember(database) {
        database.trackDao().getAllTracksFlow()
    }.collectAsState(initial = emptyList())

    val likedTracks by remember(database) {
        database.trackDao().getLikedTracksFlow()
    }.collectAsState(initial = emptyList())

    val playlists by remember(database) {
        database.playlistDao().getPlaylistsFlow()
    }.collectAsState(initial = emptyList())

    val localAlbums by remember(database) {
        database.albumDao().getAllBrowseAlbumsFlow()
    }.collectAsState(initial = emptyList())

    val localArtists by remember(database) {
        database.artistDao().getAllBrowseArtistsFlow()
    }.collectAsState(initial = emptyList())

    val downloadJobs by remember(database) {
        database.downloadJobDao().getAllJobsWithTrackFlow()
    }.collectAsState(initial = emptyList())

    var history by remember { mutableStateOf<List<HistoryItemResponse>>(emptyList()) }

    fun refreshHistory() {
        orchestrator.apiToken?.let { token ->
            coroutineScope.launch(Dispatchers.IO) {
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
            refreshHistory()
        }
        refreshHistory()
    }

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
            Item("Afficher AURA", onClick = {
                isVisible = true
                orchestrator.isWindowVisible = true
            })
            Separator()
            Item(if (orchestrator.audioPlayer.isPlaying()) "Mettre en pause" else "Lecture", onClick = {
                orchestrator.togglePlayPause()
            })
            Item("Piste suivante", onClick = { orchestrator.next() })
            Item("Piste précédente", onClick = { orchestrator.previous() })
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
                isVisible = false
                orchestrator.isWindowVisible = false
            },
            state = windowState,
            title = "AURA",
            undecorated = false,
            onKeyEvent = { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val isCtrl = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                    val isAlt = keyEvent.isAltPressed
                    val isShift = keyEvent.isShiftPressed

                    // Tous les raccourcis globaux sont strictement désactivés si la saisie a le focus
                    if (!appState.isInputFocused) {
                        when {
                            // Espace : Play / Pause
                            keyEvent.key == Key.Spacebar && !isCtrl && !isAlt -> {
                                orchestrator.togglePlayPause()
                                true
                            }
                            // Ctrl + Flèche Droite : Piste suivante
                            keyEvent.key == Key.DirectionRight && isCtrl -> {
                                orchestrator.next()
                                true
                            }
                            // Ctrl + Flèche Gauche : Piste précédente
                            keyEvent.key == Key.DirectionLeft && isCtrl -> {
                                orchestrator.previous()
                                true
                            }
                            // Flèche Droite seule : Avance rapide de 5 secondes
                            keyEvent.key == Key.DirectionRight && !isCtrl && !isAlt -> {
                                val currentPos = orchestrator.audioPlayer.getCurrentPosition()
                                val duration = orchestrator.audioPlayer.getDuration()
                                orchestrator.seekTo((currentPos + 5000L).coerceAtMost(duration))
                                true
                            }
                            // Flèche Gauche seule : Recul rapide de 5 secondes
                            keyEvent.key == Key.DirectionLeft && !isCtrl && !isAlt -> {
                                val currentPos = orchestrator.audioPlayer.getCurrentPosition()
                                orchestrator.seekTo((currentPos - 5000L).coerceAtLeast(0L))
                                true
                            }
                            // Ctrl + Flèche Haut : Volume +5%
                            keyEvent.key == Key.DirectionUp && isCtrl -> {
                                orchestrator.setVolume((orchestrator.audioPlayer.getVolume() + 0.05f).coerceAtMost(1f))
                                true
                            }
                            // Ctrl + Flèche Bas : Volume -5%
                            keyEvent.key == Key.DirectionDown && isCtrl -> {
                                orchestrator.setVolume((orchestrator.audioPlayer.getVolume() - 0.05f).coerceAtLeast(0f))
                                true
                            }
                            // L : Toggle Like du titre en cours
                            keyEvent.key == Key.L && !isCtrl && !isAlt -> {
                                orchestrator.uiState.value.currentTrack?.let { current ->
                                    orchestrator.toggleLike(current.trackId)
                                }
                                true
                            }
                            // Alt + Flèche Gauche : Navigation retour
                            keyEvent.key == Key.DirectionLeft && isAlt -> {
                                appState.navigateBack()
                                true
                            }
                            // Ctrl + F : Recherche
                            keyEvent.key == Key.F && isCtrl -> {
                                appState.navigateTo("search")
                                true
                            }
                            // Ctrl + Shift + Q : Tiroir de file d'attente
                            keyEvent.key == Key.Q && isCtrl && isShift -> {
                                appState.toggleQueue()
                                true
                            }
                            // Ctrl + Q : Masquer dans le Tray
                            keyEvent.key == Key.Q && isCtrl && !isShift -> {
                                isVisible = false
                                orchestrator.isWindowVisible = false
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        ) {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = BlazeOrange,
                    background = DeepBlack,
                    surface = CardDark,
                    onPrimary = PureWhite,
                    onBackground = PureWhite,
                    onSurface = PureWhite
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepBlack
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 1. Zone Supérieure (3 volets widescreen)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            // Volet Gauche : Navigation & Playlists
                            DesktopSidebar(
                                appState = appState,
                                playlists = playlists,
                                onPlaylistSelected = { plId ->
                                    appState.navigateTo("playlist_detail")
                                    appState.selectedPlaylistId = plId
                                }
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
                                        onToggleLike = { orchestrator.toggleLike(it) }
                                    )
                                    "library" -> LibraryScreen(
                                        allTracks = allTracks,
                                        allAlbums = localAlbums,
                                        allArtists = localArtists,
                                        playlists = playlists,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onToggleLike = { orchestrator.toggleLike(it) }
                                    )
                                    "favorites" -> FavoritesScreen(
                                        likedTracks = likedTracks,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onToggleLike = { orchestrator.toggleLike(it) }
                                    )
                                    "playlist_detail" -> {
                                        val plId = appState.selectedPlaylistId ?: ""
                                        val playlistDetailTracks by remember(database, plId) {
                                            database.playlistDao().getPlaylistTracksFlow(plId)
                                        }.collectAsState(initial = emptyList())

                                        val currentPlaylist = playlists.firstOrNull { it.id == plId }

                                        PlaylistDetailScreen(
                                            playlistId = plId,
                                            playlistName = currentPlaylist?.name ?: "Playlist",
                                            playlistTracks = playlistDetailTracks,
                                            orchestrator = orchestrator,
                                            appState = appState,
                                            onToggleLike = { orchestrator.toggleLike(it) },
                                            onReloadData = { }
                                        )
                                    }
                                    "artist_detail" -> ArtistDetailScreen(
                                        artistId = appState.selectedArtistId ?: "",
                                        allLocalTracks = allTracks,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onToggleLike = { orchestrator.toggleLike(it) }
                                    )
                                    "album_detail" -> AlbumDetailScreen(
                                        albumId = appState.selectedAlbumId ?: "",
                                        allLocalTracks = allTracks,
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        onToggleLike = { orchestrator.toggleLike(it) }
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
                                        onReloadData = { }
                                    )
                                    "settings" -> SettingsScreen(
                                        orchestrator = orchestrator,
                                        appState = appState,
                                        secureStorage = secureStorage,
                                        onReloadData = { }
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
                            onToggleLike = { orchestrator.toggleLike(it) }
                        )
                    }

                    // Dialogues Modaux
                    DesktopCreatePlaylistDialog(
                        appState = appState,
                        orchestrator = orchestrator,
                        onPlaylistCreated = { }
                    )

                    DesktopRenamePlaylistDialog(
                        appState = appState,
                        orchestrator = orchestrator,
                        onPlaylistRenamed = { }
                    )

                    DesktopAddToPlaylistDialog(
                        appState = appState,
                        playlists = playlists,
                        orchestrator = orchestrator,
                        onTrackAdded = { }
                    )

                    DesktopImportPlaylistDialog(
                        appState = appState,
                        allTracks = allTracks,
                        playlistManager = playlistManager,
                        onPlaylistImported = { }
                    )
                }
            }
        }
    }
}
