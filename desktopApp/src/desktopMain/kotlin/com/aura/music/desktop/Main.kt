package com.aura.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.aura.music.data.local.*
import com.aura.music.data.player.QueueManager
import com.aura.music.domain.player.*
import com.aura.music.ui.theme.*
import com.aura.music.data.network.KtorAuraApiService
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.DownloadRequestDto
import com.aura.music.data.network.HistoryItemResponse
import com.aura.music.data.network.SearchResponseData
import com.aura.music.data.network.AlbumDetailResponseData
import com.aura.music.data.network.ArtistDetailResponseData
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.JFileChooser
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector

fun main() = application {
    var isVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(1280.dp, 800.dp)
    )

    // DB Initialization helper
    val database = remember {
        AuraDatabase.getInstance(null)
    }

    // Native JVM Audio Player
    val audioPlayer = remember { DesktopAudioPlayer() }

    // Queue Manager (in memory queue engine)
    val queueManager = remember { QueueManager() }

    // Ktor Api Service Client
    val apiService = remember { KtorAuraApiService.createDefault() }

    // Playback Orchestrator
    val playbackOrchestrator = remember {
        DesktopPlaybackOrchestrator(database, audioPlayer, queueManager, apiService)
    }

    val coroutineScope = rememberCoroutineScope()

    // Screen navigation stack
    var screenStack by remember { mutableStateOf(listOf("home")) }
    val currentScreen = screenStack.last()

    // Details navigation IDs
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedArtistId by remember { mutableStateOf<String?>(null) }

    // Auth & settings token states
    var apiToken by remember { mutableStateOf("Bearer 12345678-1234-1234-1234-1234567890ab") }
    var downloadsToken by remember { mutableStateOf("Bearer test_user_token") }
    var userEmail by remember { mutableStateOf("") }
    var userPassword by remember { mutableStateOf("") }
    var loginStatus by remember { mutableStateOf("Connecté (Profil Test)") }
    var autoSyncEnabled by remember { mutableStateOf(true) }

    // Sync tokens and auto sync to orchestrator
    LaunchedEffect(apiToken) {
        playbackOrchestrator.apiToken = apiToken
    }
    LaunchedEffect(autoSyncEnabled) {
        playbackOrchestrator.autoSyncEnabled = autoSyncEnabled
    }

    // Navigation helper functions
    fun navigateTo(screen: String) {
        screenStack = screenStack + screen
    }

    fun navigateBack() {
        if (screenStack.size > 1) {
            screenStack = screenStack.dropLast(1)
        }
    }

    fun navigateToRoot(screen: String) {
        screenStack = listOf(screen)
    }

    // Database reactive UI state lists
    var allTracks by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }
    var likedTracks by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistListRow>>(emptyList()) }
    var playlistTracks by remember { mutableStateOf<List<PlaylistTrackRow>>(emptyList()) }
    var currentPlaylistName by remember { mutableStateOf("") }

    // Browse items for local tabs
    var localAlbums by remember { mutableStateOf<List<AlbumBrowseRow>>(emptyList()) }
    var localArtists by remember { mutableStateOf<List<ArtistBrowseRow>>(emptyList()) }

    val downloadJobs by remember(database) {
        database.downloadJobDao().getAllJobsWithTrackFlow()
    }.collectAsState(initial = emptyList())

    // Recently played items (listen history)
    var listenHistory by remember { mutableStateOf<List<HistoryItemResponse>>(emptyList()) }

    // Reusable data reloader
    fun reloadDbData() {
        coroutineScope.launch(Dispatchers.IO) {
            allTracks = database.trackDao().getAllTracks()
            likedTracks = database.trackDao().getLikedTracks()
            playlists = database.playlistDao().getPlaylists()
            localAlbums = database.albumDao().getAllBrowseAlbums()
            localArtists = database.artistDao().getAllBrowseArtists()
            
            selectedPlaylistId?.let { id ->
                val pl = database.playlistDao().getPlaylistDetail(id)
                if (pl != null) {
                    currentPlaylistName = pl.name
                    playlistTracks = database.playlistDao().getPlaylistTracks(id)
                }
            }

            // Sync history from remote
            if (apiToken.isNotBlank()) {
                try {
                    val response = apiService.getHistory(apiToken)
                    if (response.data != null) {
                        listenHistory = response.data!!.items
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to fetch history on reload: ${e.message}")
                }
            }
        }
    }

    // Initialize playback orchestrator and JNativeHook listeners
    LaunchedEffect(Unit) {
        playbackOrchestrator.connect()
        reloadDbData()
    }

    // Periodic history reloader
    LaunchedEffect(apiToken) {
        if (apiToken.isNotBlank()) {
            try {
                val response = apiService.getHistory(apiToken)
                if (response.data != null) {
                    listenHistory = response.data!!.items
                }
            } catch (e: Exception) {
                System.err.println("Failed to fetch history: ${e.message}")
            }
        }
    }

    // Observe local playback to append to listenHistory reactively
    LaunchedEffect(playbackOrchestrator) {
        playbackOrchestrator.uiState.collect { state ->
            val current = state.currentTrack
            if (current != null) {
                val alreadyExists = listenHistory.firstOrNull()?.trackId == current.trackId
                if (!alreadyExists) {
                    val newItem = HistoryItemResponse(
                        id = "local_hist_${System.currentTimeMillis()}",
                        trackId = current.trackId,
                        playedAt = java.time.Instant.now().toString(),
                        wasSkipped = false,
                        sourceContextType = state.contextType,
                        sourceContextId = state.contextId
                    )
                    listenHistory = (listOf(newItem) + listenHistory).take(20)
                }
            }
        }
    }

    // Initialize JNativeHook
    DisposableEffect(Unit) {
        val logger = Logger.getLogger(GlobalScreen::class.java.getPackage().name)
        logger.level = Level.OFF
        logger.useParentHandlers = false

        try {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(object : NativeKeyListener {
                override fun nativeKeyPressed(e: NativeKeyEvent) {
                    when (e.keyCode) {
                        NativeKeyEvent.VC_MEDIA_PLAY, NativeKeyEvent.VC_MEDIA_STOP -> {
                            println("JNativeHook: Global Play/Pause event detected")
                            javafx.application.Platform.runLater {
                                playbackOrchestrator.togglePlayPause()
                                reloadDbData()
                            }
                        }
                        NativeKeyEvent.VC_MEDIA_NEXT -> {
                            println("JNativeHook: Global Next event detected")
                            javafx.application.Platform.runLater {
                                playbackOrchestrator.next()
                                reloadDbData()
                            }
                        }
                        NativeKeyEvent.VC_MEDIA_PREVIOUS -> {
                            println("JNativeHook: Global Previous event detected")
                            javafx.application.Platform.runLater {
                                playbackOrchestrator.previous()
                                reloadDbData()
                            }
                        }
                    }
                }
                override fun nativeKeyReleased(e: NativeKeyEvent) {}
                override fun nativeKeyTyped(e: NativeKeyEvent) {}
            })
        } catch (ex: Exception) {
            System.err.println("JNativeHook registration failed: ${ex.message}")
        }

        onDispose {
            try {
                GlobalScreen.unregisterNativeHook()
                playbackOrchestrator.disconnect()
            } catch (ex: Exception) {
                // Ignore
            }
        }
    }

    // Tray management
    val trayState = rememberTrayState()
    val icon = remember {
        object : androidx.compose.ui.graphics.painter.Painter() {
            override val intrinsicSize = androidx.compose.ui.geometry.Size(32f, 32f)
            override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
                drawCircle(color = Color(0xFFFF6B00)) // BlazeOrange brand color
            }
        }
    }

    Tray(
        state = trayState,
        icon = icon,
        tooltip = "AURA Music Player",
        onAction = {
            isVisible = true
            windowState.isMinimized = false
        },
        menu = {
            Item("Restaurer AURA", onClick = {
                isVisible = true
                windowState.isMinimized = false
            })
            Separator()
            Item("Quitter", onClick = ::exitApplication)
        }
    )

    if (isVisible) {
        Window(
            onCloseRequest = { isVisible = false }, // Hide to tray instead of exiting
            state = windowState,
            title = "AURA Music Player",
            icon = icon,
            undecorated = false
        ) {
            AuraTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .components {
                            add(KtorNetworkFetcherFactory())
                        }
                        .logger(coil3.util.DebugLogger())
                        .build()
                }
                val playerState by playbackOrchestrator.uiState.collectAsState()
                
                // Dialogs and temp states
                var showCreatePlaylistDialog by remember { mutableStateOf(false) }
                var trackToDelete by remember { mutableStateOf<TrackListRow?>(null) }
                var showDeleteConfirmDialog by remember { mutableStateOf(false) }
                var playlistNameInput by remember { mutableStateOf("") }
                var showAddPlaylistMenuForTrack by remember { mutableStateOf<String?>(null) }
                var searchInput by remember { mutableStateOf("") }
                var localSearchResults by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }
                var onlineSearchResults by remember { mutableStateOf<SearchResponseData?>(null) }
                var isSearching by remember { mutableStateOf(false) }
                var searchError by remember { mutableStateOf<String?>(null) }
                var scanProgressMsg by remember { mutableStateOf("Prêt pour le scan.") }
                var libraryTab by remember { mutableStateOf("tracks") } // "tracks", "albums", "artists", "playlists"

                // Hybrid Search handler
                LaunchedEffect(searchInput) {
                    val query = searchInput.trim()
                    if (query.length >= 3) {
                        isSearching = true
                        searchError = null
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                localSearchResults = database.trackDao().searchTracks(query, 50)
                                val response = apiService.search(query)
                                if (response.error != null) {
                                    searchError = response.error?.message
                                } else {
                                    onlineSearchResults = response.data
                                }
                            } catch (e: Exception) {
                                searchError = e.message
                                System.err.println("Hybrid search failed: ${e.message}")
                            } finally {
                                isSearching = false
                            }
                        }
                    } else {
                        localSearchResults = emptyList()
                        onlineSearchResults = null
                        searchError = null
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepBlack
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Main Layout (Sidebar + Content + Queue)
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // 1. Sidebar Panel
                            Column(
                                modifier = Modifier
                                    .width(260.dp)
                                    .fillMaxHeight()
                                    .background(OffBlack)
                                    .padding(vertical = 24.dp)
                            ) {
                                // Brand Title
                                Row(
                                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.size(16.dp).clip(CircleShape).background(BlazeOrange)
                                    )
                                    Text(
                                        text = "AURA",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }

                                Spacer(modifier = Modifier.height(32.dp))

                                // Navigation Items
                                val navItems = listOf(
                                    Triple("home", "Accueil", Icons.Rounded.Home),
                                    Triple("search", "Recherche", Icons.Rounded.Search),
                                    Triple("library", "Bibliothèque", Icons.Rounded.List),
                                    Triple("favorites", "Favoris", Icons.Rounded.Favorite),
                                    Triple("downloads", "Téléchargements", Icons.Rounded.Download),
                                    Triple("settings", "Paramètres", Icons.Rounded.Settings)
                                )

                                navItems.forEach { (route, label, icon) ->
                                    val isSelected = currentScreen == route || 
                                        (route == "library" && (currentScreen == "playlist_detail" || currentScreen == "album_detail" || currentScreen == "artist_detail"))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                navigateToRoot(route)
                                                selectedPlaylistId = null
                                                reloadDbData()
                                            }
                                            .background(if (isSelected) DarkGraphite else Color.Transparent)
                                            .padding(horizontal = 24.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isSelected) BlazeOrange else TextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) TextPrimary else TextSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))
                                Divider(color = HairlineDark, modifier = Modifier.padding(horizontal = 24.dp))
                                Spacer(modifier = Modifier.height(16.dp))

                                // Playlists section header
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "MES PLAYLISTS",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted
                                    )
                                    IconButton(
                                        onClick = { showCreatePlaylistDialog = true },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null, tint = BlazeOrange)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Playlist list
                                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    items(playlists, key = { it.id }) { pl ->
                                        val isSelected = currentScreen == "playlist_detail" && selectedPlaylistId == pl.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedPlaylistId = pl.id
                                                    navigateTo("playlist_detail")
                                                    reloadDbData()
                                                }
                                                .background(if (isSelected) DarkGraphite else Color.Transparent)
                                                .padding(horizontal = 24.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(Icons.Rounded.List, contentDescription = null, tint = TextMuted)
                                            Text(
                                                text = pl.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isSelected) TextPrimary else TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            // 2. Main Content area
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(DeepBlack)
                                    .padding(horizontal = 32.dp, vertical = 24.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // Stack Navigation Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        if (screenStack.size > 1) {
                                            IconButton(
                                                onClick = { navigateBack() },
                                                modifier = Modifier.size(36.dp).clip(CircleShape).background(DarkGraphite)
                                            ) {
                                                Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = "Retour", tint = TextPrimary)
                                            }
                                        }
                                        Text(
                                            text = when (currentScreen) {
                                                "home" -> "Accueil"
                                                "search" -> "Recherche hybride"
                                                "library" -> "Bibliothèque"
                                                "settings" -> "Paramètres & Scanner"
                                                "album_detail" -> "Album"
                                                "artist_detail" -> "Artiste"
                                                "playlist_detail" -> "Détails de la Playlist"
                                                "favorites" -> "Favoris"
                                                "downloads" -> "Téléchargements"
                                                else -> ""
                                            },
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    }

                                    // Render active screen
                                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                        when (currentScreen) {
                                            "favorites" -> {
                                                FavoritesScreen(
                                                    likedTracks = likedTracks,
                                                    orchestrator = playbackOrchestrator,
                                                    onAddToPlaylist = { showAddPlaylistMenuForTrack = it },
                                                    onReload = { reloadDbData() },
                                                    onDeleteLocal = { track ->
                                                        trackToDelete = track
                                                        showDeleteConfirmDialog = true
                                                    }
                                                )
                                            }
                                            "downloads" -> {
                                                DownloadsScreen(
                                                    downloadJobs = downloadJobs,
                                                    orchestrator = playbackOrchestrator,
                                                    onReload = { reloadDbData() }
                                                )
                                            }
                                            "home" -> {
                                                HomeScreen(
                                                    allTracksCount = allTracks.size,
                                                    likedTracksCount = likedTracks.size,
                                                    playlistsCount = playlists.size,
                                                    history = listenHistory,
                                                    allTracks = allTracks,
                                                    orchestrator = playbackOrchestrator
                                                )
                                            }
                                            "search" -> {
                                                SearchScreen(
                                                    searchInput = searchInput,
                                                    onSearchInputChange = { searchInput = it },
                                                    localTracks = localSearchResults,
                                                    onlineResults = onlineSearchResults,
                                                    isSearching = isSearching,
                                                    error = searchError,
                                                    orchestrator = playbackOrchestrator,
                                                    apiService = apiService,
                                                    token = apiToken,
                                                    onNavigateToAlbum = { id ->
                                                        selectedAlbumId = id
                                                        navigateTo("album_detail")
                                                    },
                                                    onNavigateToArtist = { id ->
                                                        selectedArtistId = id
                                                        navigateTo("artist_detail")
                                                    },
                                                    onAddToPlaylist = { showAddPlaylistMenuForTrack = it },
                                                    onReload = { reloadDbData() },
                                                    database = database,
                                                    likedTracks = likedTracks,
                                                    onDeleteLocal = { track ->
                                                        trackToDelete = track
                                                        showDeleteConfirmDialog = true
                                                    }
                                                )
                                            }
                                            "library" -> {
                                                LibraryScreen(
                                                    activeTab = libraryTab,
                                                    onTabChange = { libraryTab = it },
                                                    allTracks = allTracks,
                                                    albums = localAlbums,
                                                    artists = localArtists,
                                                    playlists = playlists,
                                                    orchestrator = playbackOrchestrator,
                                                    onNavigateToAlbum = { id ->
                                                        selectedAlbumId = id
                                                        navigateTo("album_detail")
                                                    },
                                                    onNavigateToArtist = { id ->
                                                        selectedArtistId = id
                                                        navigateTo("artist_detail")
                                                    },
                                                    onNavigateToPlaylist = { id ->
                                                        selectedPlaylistId = id
                                                        navigateTo("playlist_detail")
                                                    },
                                                    onAddToPlaylist = { showAddPlaylistMenuForTrack = it },
                                                    onReload = { reloadDbData() },
                                                    likedTracks = likedTracks,
                                                    onDeleteLocal = { track ->
                                                        trackToDelete = track
                                                        showDeleteConfirmDialog = true
                                                    }
                                                )
                                            }
                                            "settings" -> {
                                                SettingsScreen(
                                                    email = userEmail,
                                                    onEmailChange = { userEmail = it },
                                                    password = userPassword,
                                                    onPasswordChange = { userPassword = it },
                                                    status = loginStatus,
                                                    apiToken = apiToken,
                                                    onApiTokenChange = { apiToken = it },
                                                    downloadsToken = downloadsToken,
                                                    onDownloadsTokenChange = { downloadsToken = it },
                                                    onLogin = {
                                                        if (userEmail.isNotBlank()) {
                                                            apiToken = "Bearer ${userEmail.trim()}"
                                                            loginStatus = "Connecté en tant que ${userEmail.trim()}"
                                                            reloadDbData()
                                                        }
                                                    },
                                                    autoSync = autoSyncEnabled,
                                                    onAutoSyncChange = { autoSyncEnabled = it },
                                                    onNavigateToCloudSync = { navigateTo("cloud_sync") },
                                                    scanProgress = scanProgressMsg,
                                                    onStartScan = {
                                                        val chooser = JFileChooser().apply {
                                                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                                            dialogTitle = "Choisir le dossier de musique"
                                                        }
                                                        val result = chooser.showOpenDialog(null)
                                                        if (result == JFileChooser.APPROVE_OPTION) {
                                                            val folder = chooser.selectedFile
                                                            scanProgressMsg = "Lancement de l'indexation Loom..."
                                                            playbackOrchestrator.scanLocalFolder(
                                                                folder = folder,
                                                                onProgress = { msg -> scanProgressMsg = msg },
                                                                onComplete = { count ->
                                                                    scanProgressMsg = "Indexation terminée. $count titres importés."
                                                                    reloadDbData()
                                                                }
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                            "cloud_sync" -> {
                                                CloudSyncScreen(
                                                    orchestrator = playbackOrchestrator,
                                                    onNavigateBack = { navigateBack() }
                                                )
                                            }
                                            "album_detail" -> {
                                                AlbumDetailScreen(
                                                    albumId = selectedAlbumId,
                                                    apiService = apiService,
                                                    database = database,
                                                    orchestrator = playbackOrchestrator,
                                                    onAddToPlaylist = { showAddPlaylistMenuForTrack = it },
                                                    onReload = { reloadDbData() },
                                                    likedTracks = likedTracks,
                                                    onDeleteLocal = { track ->
                                                        trackToDelete = track
                                                        showDeleteConfirmDialog = true
                                                    }
                                                )
                                            }
                                            "artist_detail" -> {
                                                ArtistDetailScreen(
                                                    artistId = selectedArtistId,
                                                    apiService = apiService,
                                                    database = database,
                                                    orchestrator = playbackOrchestrator,
                                                    onNavigateToAlbum = { id ->
                                                        selectedAlbumId = id
                                                        navigateTo("album_detail")
                                                    },
                                                    onAddToPlaylist = { showAddPlaylistMenuForTrack = it },
                                                    onReload = { reloadDbData() },
                                                    likedTracks = likedTracks,
                                                    onDeleteLocal = { track ->
                                                        trackToDelete = track
                                                        showDeleteConfirmDialog = true
                                                    }
                                                )
                                            }
                                            "playlist_detail" -> {
                                                PlaylistDetailScreen(
                                                    playlistId = selectedPlaylistId,
                                                    playlistName = currentPlaylistName,
                                                    tracks = playlistTracks,
                                                    allTracks = allTracks,
                                                    database = database,
                                                    orchestrator = playbackOrchestrator,
                                                    onDeletePlaylist = {
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            selectedPlaylistId?.let { id ->
                                                                database.playlistDao().deletePlaylist(id)
                                                                selectedPlaylistId = null
                                                                navigateBack()
                                                                reloadDbData()
                                                            }
                                                        }
                                                    },
                                                    onReload = { reloadDbData() },
                                                    likedTracks = likedTracks,
                                                    onDeleteLocal = { track ->
                                                        trackToDelete = track
                                                        showDeleteConfirmDialog = true
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Separator
                            Divider(color = HairlineDark, modifier = Modifier.width(1.dp).fillMaxHeight())

                            // 3. Right Queue Panel
                            Column(
                                modifier = Modifier
                                    .width(300.dp)
                                    .fillMaxHeight()
                                    .background(OffBlack)
                                    .padding(vertical = 24.dp, horizontal = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("File d'attente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        val totalQueue = playerState.mainQueueTracks.size + playerState.priorityQueue.size
                                        Text("$totalQueue titre(s) restant(s)", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                    }
                                    TextButton(
                                        onClick = {
                                            // Empty queue logic
                                            queueManager.clearQueue()
                                            playbackOrchestrator.next() // stops playback or advances
                                            reloadDbData()
                                        }
                                    ) {
                                        Text("Vider", color = BlazeOrange, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))

                                val upcoming = playerState.mainQueueTracks
                                val priority = playerState.priorityQueue

                                if (upcoming.isEmpty() && priority.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("File vide", color = TextMuted)
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (priority.isNotEmpty()) {
                                            item {
                                                Text("FILE PRIORITAIRE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = BlazeOrange)
                                            }
                                            itemsIndexed(priority) { idx, t ->
                                                QueueItemRow(
                                                    track = t,
                                                    onRemove = { playbackOrchestrator.removeFromQueue(idx) }
                                                )
                                            }
                                        }
                                        if (upcoming.isNotEmpty()) {
                                            item {
                                                Text("SUITE DU CONTEXTE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = TextMuted)
                                            }
                                            items(upcoming) { t ->
                                                QueueItemRow(
                                                    track = t,
                                                    onRemove = { playbackOrchestrator.removeFromMainQueue(t.internalId) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom Player Bar
                        PlayerBottomBar(
                            state = playerState,
                            orchestrator = playbackOrchestrator,
                            onLikeToggle = { trackId ->
                                playbackOrchestrator.toggleLike(trackId)
                                reloadDbData()
                            }
                        )
                    }

                    // Dialog: Create Playlist
                    if (showCreatePlaylistDialog) {
                        AlertDialog(
                            onDismissRequest = { showCreatePlaylistDialog = false },
                            title = { Text("Nouvelle Playlist", color = TextPrimary) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Entrez le nom de la playlist :", color = TextSecondary)
                                    OutlinedTextField(
                                        value = playlistNameInput,
                                        onValueChange = { playlistNameInput = it },
                                        singleLine = true,
                                        colors = TextFieldDefaults.colors(
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary,
                                            focusedContainerColor = OffBlack,
                                            unfocusedContainerColor = OffBlack,
                                            focusedIndicatorColor = BlazeOrange,
                                            cursorColor = BlazeOrange
                                        )
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (playlistNameInput.isNotBlank()) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val now = System.currentTimeMillis()
                                                val id = "playlist_${UUID.randomUUID().toString().take(8)}"
                                                database.playlistDao().insertPlaylist(
                                                    PlaylistEntity(
                                                        id = id,
                                                        name = playlistNameInput.trim(),
                                                        coverUri = null,
                                                        isPinned = false,
                                                        createdAt = now,
                                                        updatedAt = now
                                                    )
                                                )
                                                
                                                // Sync playlist to backend REST
                                                if (apiToken.isNotBlank()) {
                                                    try {
                                                        apiService.createPlaylist(
                                                            apiToken,
                                                            com.aura.music.data.network.PlaylistCreate(
                                                                id = id,
                                                                name = playlistNameInput.trim(),
                                                                coverUri = null,
                                                                isPinned = false
                                                            )
                                                        )
                                                    } catch (e: Exception) {
                                                        System.err.println("Failed to sync new playlist: ${e.message}")
                                                    }
                                                }

                                                playlistNameInput = ""
                                                showCreatePlaylistDialog = false
                                                reloadDbData()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                                ) {
                                    Text("Créer")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                                    Text("Annuler", color = TextSecondary)
                                }
                            },
                            containerColor = OffBlack
                        )
                    }

                    // Dialog: Add to Playlist selection
                    showAddPlaylistMenuForTrack?.let { trackId ->
                        AlertDialog(
                            onDismissRequest = { showAddPlaylistMenuForTrack = null },
                            title = { Text("Ajouter à la playlist", color = TextPrimary) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (playlists.isEmpty()) {
                                        Text("Aucune playlist créée.", color = TextMuted)
                                    } else {
                                        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                            items(playlists) { pl ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            coroutineScope.launch(Dispatchers.IO) {
                                                                val pos = database.playlistDao().getNextPlaylistPosition(pl.id)
                                                                val itemId = "playlist-item:${UUID.randomUUID()}"
                                                                database.playlistDao().insertPlaylistItem(
                                                                    PlaylistItemEntity(
                                                                        id = itemId,
                                                                        playlistId = pl.id,
                                                                        trackId = trackId,
                                                                        position = pos,
                                                                        addedAt = System.currentTimeMillis()
                                                                    )
                                                                )

                                                                // Sync to backend REST
                                                                if (apiToken.isNotBlank()) {
                                                                    try {
                                                                        apiService.appendTrackToPlaylist(
                                                                            apiToken,
                                                                            pl.id,
                                                                            com.aura.music.data.network.PlaylistItemCreate(
                                                                                id = itemId,
                                                                                trackId = trackId,
                                                                                position = pos
                                                                            )
                                                                        )
                                                                    } catch (e: Exception) {
                                                                        System.err.println("Failed to sync playlist item append: ${e.message}")
                                                                    }
                                                                }

                                                                showAddPlaylistMenuForTrack = null
                                                                reloadDbData()
                                                            }
                                                        }
                                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Rounded.List, contentDescription = null, tint = TextMuted)
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Text(pl.name, color = TextPrimary)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showAddPlaylistMenuForTrack = null }) {
                                    Text("Fermer", color = TextSecondary)
                                }
                            },
                            containerColor = OffBlack
                        )
                    }

                    // Dialog: Delete Track Confirmation
                    if (showDeleteConfirmDialog && trackToDelete != null) {
                        val track = trackToDelete!!
                        var isDownloaded by remember { mutableStateOf(false) }
                        LaunchedEffect(track.id) {
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                val raw = database.trackDao().getRawTrackById(track.id)
                                if (raw != null) {
                                    isDownloaded = raw.isDownloadedByAura || raw.canonicalAudioSourceType == "downloaded"
                                }
                            }
                        }
                        
                        val titleStr = if (isDownloaded) "Supprimer le téléchargement" else "Supprimer de l'ordinateur"
                        val fileUrl = track.contentUri ?: ""
                        val filePath = if (fileUrl.startsWith("file:")) {
                            try {
                                File(java.net.URI(fileUrl)).absolutePath
                            } catch (e: Exception) {
                                fileUrl
                            }
                        } else {
                            fileUrl
                        }
                        
                        val messageStr = if (isDownloaded) {
                            "Voulez-vous supprimer le fichier téléchargé pour \"${track.title}\" ?\nLe fichier sera supprimé et ce titre ne sera plus disponible hors-ligne."
                        } else {
                            "Attention : Vous allez supprimer définitivement le fichier physique de votre ordinateur.\n\nFichier : $filePath\n\nSouhaitez-vous continuer ?"
                        }

                        AlertDialog(
                            onDismissRequest = {
                                showDeleteConfirmDialog = false
                                trackToDelete = null
                            },
                            title = { Text(titleStr, color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = {
                                Text(messageStr, color = TextSecondary)
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                // Delete physical file
                                                val contentUri = track.contentUri
                                                if (contentUri != null && contentUri.startsWith("file:")) {
                                                    try {
                                                        val file = File(java.net.URI(contentUri))
                                                        if (file.exists()) {
                                                            file.delete()
                                                        }
                                                    } catch (e: Exception) {
                                                        System.err.println("Error deleting physical file: ${e.message}")
                                                    }
                                                }
                                                
                                                // Delete cover if downloaded
                                                val appDir = File(System.getProperty("user.home"), ".aura")
                                                val coverFile = File(appDir, "covers/${track.id.replace(':', ';')}.jpg")
                                                if (coverFile.exists()) {
                                                    coverFile.delete()
                                                }
                                                
                                                // Delete from DB
                                                database.trackDao().deleteTracksByIds(listOf(track.id))
                                                
                                                reloadDbData()
                                            } catch (e: Exception) {
                                                System.err.println("Failed to delete track: ${e.message}")
                                            } finally {
                                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                    showDeleteConfirmDialog = false
                                                    trackToDelete = null
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError)
                                ) {
                                    Text("Supprimer", color = Color.White)
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        showDeleteConfirmDialog = false
                                        trackToDelete = null
                                    }
                                ) {
                                    Text("Annuler", color = TextSecondary)
                                }
                            },
                            containerColor = OffBlack
                        )
                    }
                }
            }
        }
    }
}

// ---------------- COMPOSE SCREEN COMPONENTS ----------------

@Composable
fun HomeScreen(
    allTracksCount: Int,
    likedTracksCount: Int,
    playlistsCount: Int,
    history: List<HistoryItemResponse>,
    allTracks: List<TrackListRow>,
    orchestrator: DesktopPlaybackOrchestrator
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Welcoming card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Bonjour, AURA vous propose une expérience audio unique.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Profitez de votre bibliothèque locale synchronisée avec le cloud, et recherchez en ligne de nouvelles musiques.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        // Stats boxes row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                StatBox(label = "Titres locaux", value = allTracksCount.toString())
                StatBox(label = "Favoris", value = likedTracksCount.toString())
                StatBox(label = "Playlists", value = playlistsCount.toString())
            }
        }

        // Listen History section
        item {
            Text(
                text = "Écoutés récemment",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        if (history.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Aucun historique disponible pour le moment.", color = TextMuted)
                }
            }
        } else {
            items(history) { item ->
                // Look up track in local db to get title and artist
                val matchedLocal = allTracks.firstOrNull { it.id == item.trackId }
                val title = matchedLocal?.title ?: "Titre Inconnu"
                val artist = matchedLocal?.artistName ?: "Artiste Inconnu"
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(OffBlack)
                        .clickable {
                            if (matchedLocal != null) {
                                orchestrator.playTrack(matchedLocal.id, "history", "all", allTracks.map { t: TrackListRow -> t.toQueued() }, allTracks.indexOf(matchedLocal))
                            }
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(DarkGraphite),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = BlazeOrange)
                        }
                        Column {
                            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(artist, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (item.wasSkipped) "Passé" else "Écouté",
                            color = if (item.wasSkipped) SemanticError else SemanticSuccess,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        item.playedAt.take(16).replace("T", " ").let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    localTracks: List<TrackListRow>,
    onlineResults: SearchResponseData?,
    isSearching: Boolean,
    error: String?,
    orchestrator: DesktopPlaybackOrchestrator,
    apiService: AuraApiService,
    token: String,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onReload: () -> Unit,
    database: AuraDatabase,
    likedTracks: List<TrackListRow>,
    onDeleteLocal: (TrackListRow) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = searchInput,
            onValueChange = onSearchInputChange,
            placeholder = { Text("Rechercher un titre, artiste ou album...", color = TextMuted) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = OffBlack,
                unfocusedContainerColor = OffBlack,
                cursorColor = BlazeOrange,
                focusedIndicatorColor = BlazeOrange
            )
        )

        if (isSearching) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BlazeOrange)
            }
        } else if (error != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp).background(SemanticError.copy(alpha = 0.1f)).clip(RoundedCornerShape(8.dp))) {
                Text("Erreur recherche : $error", modifier = Modifier.padding(16.dp), color = SemanticError)
            }
        } else if (searchInput.trim().length < 3) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Entrez au moins 3 caractères pour rechercher.", color = TextMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 1. Best Match (Meilleur résultat)
                val best = onlineResults?.bestMatch
                if (best != null && best.item != null) {
                    item {
                        Text("Meilleur résultat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(OffBlack)
                                .clickable {
                                    when (best.kind) {
                                        "album" -> (best.item as? com.aura.music.data.network.AlbumSummary)?.id?.let { onNavigateToAlbum(it) }
                                        "artist" -> (best.item as? com.aura.music.data.network.ArtistSummary)?.id?.let { onNavigateToArtist(it) }
                                    }
                                }
                                .padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            val imageUrl = when (val item = best.item) {
                                is com.aura.music.data.network.TrackSummary -> item.coverUri
                                is com.aura.music.data.network.ArtistSummary -> item.pictureUri
                                is com.aura.music.data.network.AlbumSummary -> item.coverUri
                                else -> null
                            }
                            CoverImage(
                                url = imageUrl,
                                fallbackIcon = when (best.kind) {
                                    "artist" -> Icons.Rounded.Person
                                    "album" -> Icons.Rounded.List
                                    else -> Icons.Rounded.PlayArrow
                                },
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(if (best.kind == "artist") CircleShape else RoundedCornerShape(8.dp))
                            )
                            Column {
                                val titleText = when (val item = best.item) {
                                    is com.aura.music.data.network.TrackSummary -> item.title
                                    is com.aura.music.data.network.ArtistSummary -> item.name
                                    is com.aura.music.data.network.AlbumSummary -> item.title
                                    else -> ""
                                }
                                val subtitleText = when (val item = best.item) {
                                    is com.aura.music.data.network.TrackSummary -> item.displayArtistName
                                    is com.aura.music.data.network.AlbumSummary -> item.primaryArtistName
                                    else -> ""
                                }
                                Text(titleText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                                if (subtitleText.isNotEmpty()) {
                                    Text(subtitleText, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(DarkGraphite)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = when (best.kind) {
                                            "artist" -> "ARTISTE"
                                            "album" -> "ALBUM"
                                            else -> "TITRE"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Local tracks matches
                if (localTracks.isNotEmpty()) {
                    item {
                        Text("Titres de votre Bibliothèque", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    itemsIndexed(localTracks) { index, track ->
                        val isPlaying = orchestrator.uiState.collectAsState().value.currentTrack?.trackId == track.id
                        TrackListItem(
                            index = index,
                            track = track,
                            isLiked = likedTracks.any { it.id == track.id },
                            isPlaying = isPlaying,
                            onPlay = {
                                orchestrator.playTrack(track.id, "search_local", searchInput, localTracks.map { t: TrackListRow -> t.toQueued() }, index)
                            },
                            onLike = { orchestrator.toggleLike(track.id, onReload) },
                            onAddToQueue = { orchestrator.addToQueue(track.toQueued()) },
                            onAddToPlaylist = { onAddToPlaylist(track.id) },
                            onDeleteLocal = {
                                onDeleteLocal(track)
                            }
                        )
                    }
                }

                // 3. Online tracks matches
                val onlineTracks = onlineResults?.tracks ?: emptyList()
                if (onlineTracks.isNotEmpty()) {
                    item {
                        Text("Résultats en ligne", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    itemsIndexed(onlineTracks) { index, track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(OffBlack)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text((index + 1).toString(), color = TextMuted, modifier = Modifier.width(30.dp))
                                CoverImage(
                                    url = track.coverUri,
                                    fallbackIcon = Icons.Rounded.MusicNote,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Column {
                                    Text(track.title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                    Text(track.displayArtistName + (track.displayAlbumTitle?.let { " • $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (track.isLocalAvailable) {
                                    IconButton(
                                        onClick = {
                                            // Play as local track
                                            coroutineScope.launch {
                                                val localRow = database.trackDao().getTrackById(track.id)
                                                if (localRow != null) {
                                                    orchestrator.playTrack(localRow.id, "search_online", track.id, listOf(localRow.toQueued()), 0)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire", tint = BlazeOrange)
                                    }
                                } else {
                                    val isThisDownloading = isDownloading == track.id
                                    IconButton(
                                        onClick = {
                                            if (token.isNotBlank()) {
                                                isDownloading = track.id
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    try {
                                                        val now = System.currentTimeMillis()
                                                        val artistId = "artist_${track.displayArtistName.hashCode()}"
                                                        val albumId = track.displayAlbumTitle?.let { "album_${(track.displayArtistName + "_" + it).hashCode()}" }
                                                        
                                                        database.artistDao().upsertArtists(
                                                            listOf(
                                                                ArtistEntity(
                                                                    id = artistId,
                                                                    name = track.displayArtistName,
                                                                    normalizedName = track.displayArtistName.lowercase(),
                                                                    pictureUri = null,
                                                                    createdAt = now,
                                                                    updatedAt = now
                                                                )
                                                            )
                                                        )
                                                        
                                                        val albumTitle = track.displayAlbumTitle
                                                        if (albumId != null && albumTitle != null) {
                                                            database.albumDao().upsertAlbums(
                                                                listOf(
                                                                    AlbumEntity(
                                                                        id = albumId,
                                                                        primaryArtistId = artistId,
                                                                        title = albumTitle,
                                                                        normalizedTitle = albumTitle.lowercase(),
                                                                        coverUri = track.coverUri,
                                                                        createdAt = now,
                                                                        updatedAt = now
                                                                    )
                                                                )
                                                            )
                                                        }
                                                        
                                                        database.trackDao().upsertTracks(
                                                            listOf(
                                                                TrackEntity(
                                                                    id = track.id,
                                                                    primaryArtistId = artistId,
                                                                    albumId = albumId,
                                                                    title = track.title,
                                                                    normalizedTitle = track.title.lowercase(),
                                                                    displayArtistName = track.displayArtistName,
                                                                    displayAlbumTitle = track.displayAlbumTitle,
                                                                    durationMs = track.durationMs.toLong(),
                                                                    coverUri = track.coverUri,
                                                                    canonicalAudioSourceType = "cloud_only",
                                                                    isLiked = false,
                                                                    isDownloadedByAura = false,
                                                                    createdAt = now,
                                                                    updatedAt = now
                                                                )
                                                            )
                                                        )

                                                        apiService.createDownload(token, DownloadRequestDto(trackId = track.id))
                                                    } catch (e: Exception) {
                                                        System.err.println("Download trigger failed: ${e.message}")
                                                    } finally {
                                                        isDownloading = null
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isThisDownloading
                                    ) {
                                        if (isThisDownloading) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BlazeOrange)
                                        } else {
                                            Icon(Icons.Rounded.GetApp, contentDescription = "Télécharger", tint = TextMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Online albums matches
                val onlineAlbums = onlineResults?.albums ?: emptyList()
                if (onlineAlbums.isNotEmpty()) {
                    item {
                        Text("Albums en ligne", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(onlineAlbums) { album ->
                                Column(
                                    modifier = Modifier
                                        .width(140.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onNavigateToAlbum(album.id) }
                                        .background(OffBlack)
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CoverImage(
                                        url = album.coverUri,
                                        fallbackIcon = Icons.Rounded.List,
                                        modifier = Modifier
                                            .size(116.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                    Text(album.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(album.primaryArtistName, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // 5. Online artists matches
                val onlineArtists = onlineResults?.artists ?: emptyList()
                if (onlineArtists.isNotEmpty()) {
                    item {
                        Text("Artistes en ligne", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(onlineArtists) { artist ->
                                Column(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .clip(CircleShape)
                                        .clickable { onNavigateToArtist(artist.id) }
                                        .background(OffBlack)
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CoverImage(
                                        url = artist.pictureUri,
                                        fallbackIcon = Icons.Rounded.Person,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                    )
                                    Text(artist.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    activeTab: String,
    onTabChange: (String) -> Unit,
    allTracks: List<TrackListRow>,
    albums: List<AlbumBrowseRow>,
    artists: List<ArtistBrowseRow>,
    playlists: List<PlaylistListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onReload: () -> Unit,
    likedTracks: List<TrackListRow>,
    onDeleteLocal: (TrackListRow) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // TabRow headers
        TabRow(
            selectedTabIndex = when (activeTab) {
                "tracks" -> 0
                "albums" -> 1
                "artists" -> 2
                else -> 3
            },
            containerColor = Color.Transparent,
            contentColor = BlazeOrange,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[when (activeTab) {
                        "tracks" -> 0
                        "albums" -> 1
                        "artists" -> 2
                        else -> 3
                    }]),
                    color = BlazeOrange
                )
            }
        ) {
            Tab(selected = activeTab == "tracks", onClick = { onTabChange("tracks") }, text = { Text("Titres") })
            Tab(selected = activeTab == "albums", onClick = { onTabChange("albums") }, text = { Text("Albums") })
            Tab(selected = activeTab == "artists", onClick = { onTabChange("artists") }, text = { Text("Artistes") })
            Tab(selected = activeTab == "playlists", onClick = { onTabChange("playlists") }, text = { Text("Playlists") })
        }

        when (activeTab) {
            "tracks" -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = {
                                if (allTracks.isNotEmpty()) {
                                    val queued = allTracks.map { t: TrackListRow -> t.toQueued() }
                                    orchestrator.playTrack(queued[0].trackId, "library_tracks", "all", queued, 0)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Tout lire", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    TrackListHeader()

                    if (allTracks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Aucune piste. Utilisez 'Paramètres' pour importer des dossiers.", color = TextMuted)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            itemsIndexed(allTracks, key = { _, t -> t.id }) { index, track ->
                                val isPlaying = orchestrator.uiState.collectAsState().value.currentTrack?.trackId == track.id
                                TrackListItem(
                                    index = index,
                                    track = track,
                                    isLiked = likedTracks.any { it.id == track.id },
                                    isPlaying = isPlaying,
                                    onPlay = {
                                        val queued = allTracks.map { t: TrackListRow -> t.toQueued() }
                                        orchestrator.playTrack(track.id, "library_tracks", "all", queued, index)
                                    },
                                    onLike = { orchestrator.toggleLike(track.id, onReload) },
                                    onAddToQueue = { orchestrator.addToQueue(track.toQueued()) },
                                    onAddToPlaylist = { onAddToPlaylist(track.id) },
                                    onDeleteLocal = {
                                        onDeleteLocal(track)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            "albums" -> {
                if (albums.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucun album local.", color = TextMuted)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(albums) { album ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToAlbum(album.id) },
                                colors = CardDefaults.cardColors(containerColor = OffBlack)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CoverImage(
                                        url = album.coverUri,
                                        fallbackIcon = Icons.Rounded.List,
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Text(album.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(album.artistName ?: "Artiste inconnu", color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            "artists" -> {
                if (artists.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucun artiste local.", color = TextMuted)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(artists) { artist ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToArtist(artist.id) },
                                colors = CardDefaults.cardColors(containerColor = OffBlack)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CoverImage(
                                        url = artist.pictureUri,
                                        fallbackIcon = Icons.Rounded.Person,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                    )
                                    Text(artist.name, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${artist.trackCount} titre(s)", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            "playlists" -> {
                if (playlists.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucune playlist. Utilisez le bouton '+' à gauche pour en créer une.", color = TextMuted)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(playlists) { pl ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToPlaylist(pl.id) },
                                colors = CardDefaults.cardColors(containerColor = OffBlack)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DarkGraphite),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.List, contentDescription = null, tint = TextMuted, modifier = Modifier.size(32.dp))
                                    }
                                    Text(pl.name, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Playlist AURA", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    status: String,
    apiToken: String,
    onApiTokenChange: (String) -> Unit,
    downloadsToken: String,
    onDownloadsTokenChange: (String) -> Unit,
    onLogin: () -> Unit,
    autoSync: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    onNavigateToCloudSync: () -> Unit,
    scanProgress: String,
    onStartScan: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Auth Simulation Box
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Compte AURA & Connexion", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Statut: $status", color = BlazeOrange, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = onEmailChange,
                            label = { Text("E-mail") },
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = DarkGraphite,
                                unfocusedContainerColor = DarkGraphite,
                                cursorColor = BlazeOrange,
                                focusedIndicatorColor = BlazeOrange
                            )
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = onPasswordChange,
                            label = { Text("Mot de passe") },
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = DarkGraphite,
                                unfocusedContainerColor = DarkGraphite,
                                cursorColor = BlazeOrange,
                                focusedIndicatorColor = BlazeOrange
                            )
                        )
                    }

                    Button(
                        onClick = onLogin,
                        colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                    ) {
                        Text("Se connecter")
                    }
                }
            }
        }

        // Credentials overview
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Configuration des Tokens REST", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                    
                    OutlinedTextField(
                        value = apiToken,
                        onValueChange = onApiTokenChange,
                        label = { Text("Bearer Token Sync") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = DarkGraphite,
                            unfocusedContainerColor = DarkGraphite,
                            cursorColor = BlazeOrange,
                            focusedIndicatorColor = BlazeOrange
                        )
                    )

                    OutlinedTextField(
                        value = downloadsToken,
                        onValueChange = onDownloadsTokenChange,
                        label = { Text("Bearer Token Téléchargements") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = DarkGraphite,
                            unfocusedContainerColor = DarkGraphite,
                            cursorColor = BlazeOrange,
                            focusedIndicatorColor = BlazeOrange
                        )
                    )
                }
            }
        }

        // Auto sync options
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Row(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Synchronisation automatique", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Synchronise vos snapshots de lecture et vos likes en arrière-plan.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Switch(
                        checked = autoSync,
                        onCheckedChange = onAutoSyncChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = BlazeOrange
                        )
                    )
                }
            }
        }

        // Navigation to Cloud Sync screen
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToCloudSync() },
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Row(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Gestion des fichiers Cloud", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Gérer l'espace disque, uploader vos musiques locales et télécharger vos fichiers cloud.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = BlazeOrange
                    )
                }
            }
        }

        // Project Loom local scanner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Indexeur de fichiers locaux (Project Loom)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Sélectionnez un dossier de musique local pour l'indexation. La tâche d'importation s'exécute de manière asynchrone sur des threads virtuels Loom.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    
                    Button(
                        onClick = onStartScan,
                        colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sélectionner dossier...")
                    }

                    Text(scanProgress, color = BlazeOrange, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun AlbumDetailScreen(
    albumId: String?,
    apiService: AuraApiService,
    database: AuraDatabase,
    orchestrator: DesktopPlaybackOrchestrator,
    onAddToPlaylist: (String) -> Unit,
    onReload: () -> Unit,
    likedTracks: List<TrackListRow>,
    onDeleteLocal: (TrackListRow) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var onlineAlbum by remember { mutableStateOf<AlbumDetailResponseData?>(null) }
    var localTracks by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }
    var albumTitle by remember { mutableStateOf("Album") }
    var artistName by remember { mutableStateOf("Artiste") }
    var tracksCount by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(albumId) {
        albumId?.let { id ->
            loading = true
            try {
                if (id.startsWith("album_")) {
                    // Local album details
                    localTracks = database.trackDao().getTracksForAlbum(id)
                    val first = localTracks.firstOrNull()
                    albumTitle = first?.albumTitle ?: "Album local"
                    artistName = first?.artistName ?: "Artiste local"
                    tracksCount = localTracks.size
                    onlineAlbum = null
                } else {
                    // Online album details
                    val response = apiService.getAlbum(id)
                    if (response.data != null) {
                        onlineAlbum = response.data
                        albumTitle = response.data!!.title
                        artistName = response.data!!.primaryArtistName
                        tracksCount = response.data!!.tracks.size
                    }
                }
            } catch (e: Exception) {
                System.err.println("Failed to load album details: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BlazeOrange)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // Header Hero
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val albumCoverUrl = onlineAlbum?.coverUri ?: localTracks.firstOrNull()?.coverUri
                    CoverImage(
                        url = albumCoverUrl,
                        fallbackIcon = Icons.Rounded.List,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Column {
                        Text(albumTitle, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(artistName, style = MaterialTheme.typography.titleLarge, color = TextSecondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("$tracksCount titre(s)", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            if (albumId != null) {
                                if (albumId.startsWith("album_")) {
                                    if (localTracks.isNotEmpty()) {
                                        orchestrator.playTrack(localTracks[0].id, "album", albumId, localTracks.map { t: TrackListRow -> t.toQueued() }, 0)
                                    }
                                } else {
                                    // Play online tracks if available
                                    onlineAlbum?.tracks?.let { tracks ->
                                        if (tracks.isNotEmpty()) {
                                            // Trigger play of first track (if local mapping matches)
                                            coroutineScope.launch {
                                                val localRow = database.trackDao().getTrackById(tracks[0].id)
                                                if (localRow != null) {
                                                    orchestrator.playTrack(localRow.id, "album", albumId, listOf(localRow.toQueued()), 0)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tout lire", fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                TrackListHeader()
            }

            if (albumId != null && albumId.startsWith("album_")) {
                itemsIndexed(localTracks) { index, track ->
                    val isPlaying = orchestrator.uiState.collectAsState().value.currentTrack?.trackId == track.id
                    TrackListItem(
                        index = index,
                        track = track,
                        isLiked = likedTracks.any { it.id == track.id },
                        isPlaying = isPlaying,
                        onPlay = {
                            orchestrator.playTrack(track.id, "album", albumId, localTracks.map { t: TrackListRow -> t.toQueued() }, index)
                        },
                        onLike = { orchestrator.toggleLike(track.id, onReload) },
                        onAddToQueue = { orchestrator.addToQueue(track.toQueued()) },
                        onAddToPlaylist = { onAddToPlaylist(track.id) },
                        onDeleteLocal = if (track.contentUri?.startsWith("file:") == true) {
                            {
                                onDeleteLocal(track)
                            }
                        } else null
                    )
                }
            } else {
                val tracks = onlineAlbum?.tracks ?: emptyList()
                itemsIndexed(tracks) { index, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(OffBlack)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text((index + 1).toString(), color = TextMuted, modifier = Modifier.width(30.dp))
                            Column {
                                Text(track.title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(track.displayArtistName, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            }
                        }
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    val localRow = database.trackDao().getTrackById(track.id)
                                    if (localRow != null) {
                                        orchestrator.playTrack(localRow.id, "album", albumId ?: "", listOf(localRow.toQueued()), 0)
                                    }
                                }
                            },
                            enabled = track.isLocalAvailable
                        ) {
                            Icon(
                                imageVector = if (track.isLocalAvailable) Icons.Rounded.PlayArrow else Icons.Rounded.List,
                                contentDescription = null,
                                tint = if (track.isLocalAvailable) BlazeOrange else TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    artistId: String?,
    apiService: AuraApiService,
    database: AuraDatabase,
    orchestrator: DesktopPlaybackOrchestrator,
    onNavigateToAlbum: (String) -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onReload: () -> Unit,
    likedTracks: List<TrackListRow>,
    onDeleteLocal: (TrackListRow) -> Unit
) {
    var onlineArtist by remember { mutableStateOf<ArtistDetailResponseData?>(null) }
    var localTracks by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }
    var artistName by remember { mutableStateOf("Artiste") }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(artistId) {
        artistId?.let { id ->
            loading = true
            try {
                if (id.startsWith("artist_")) {
                    localTracks = database.trackDao().getTracksForArtist(id, 50)
                    artistName = localTracks.firstOrNull()?.artistName ?: "Artiste local"
                    onlineArtist = null
                } else {
                    val response = apiService.getArtist(id)
                    if (response.data != null) {
                        onlineArtist = response.data
                        artistName = response.data!!.name
                    }
                }
            } catch (e: Exception) {
                System.err.println("Failed to load artist details: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BlazeOrange)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val artistPictureUrl = onlineArtist?.pictureUri ?: localTracks.firstOrNull()?.coverUri
                    CoverImage(
                        url = artistPictureUrl,
                        fallbackIcon = Icons.Rounded.Person,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                    )
                    Column {
                        Text(artistName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Artiste AURA", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
            }

            item {
                Text("Meilleures Pistes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
            }

            if (artistId != null && artistId.startsWith("artist_")) {
                itemsIndexed(localTracks) { index, track ->
                    val isPlaying = orchestrator.uiState.collectAsState().value.currentTrack?.trackId == track.id
                    TrackListItem(
                        index = index,
                        track = track,
                        isLiked = likedTracks.any { it.id == track.id },
                        isPlaying = isPlaying,
                        onPlay = {
                            orchestrator.playTrack(track.id, "artist", artistId, localTracks.map { t: TrackListRow -> t.toQueued() }, index)
                        },
                        onLike = { orchestrator.toggleLike(track.id, onReload) },
                        onAddToQueue = { orchestrator.addToQueue(track.toQueued()) },
                        onAddToPlaylist = { onAddToPlaylist(track.id) },
                        onDeleteLocal = if (track.contentUri?.startsWith("file:") == true) {
                            {
                                onDeleteLocal(track)
                            }
                        } else null
                    )
                }
            } else {
                val tracks = onlineArtist?.topTracks ?: emptyList()
                itemsIndexed(tracks) { index, track ->
                    val isPlaying = orchestrator.uiState.collectAsState().value.currentTrack?.trackId == track.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(OffBlack)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text((index + 1).toString(), color = if (isPlaying) BlazeOrange else TextMuted, modifier = Modifier.width(30.dp))
                            Column {
                                Text(track.title, style = MaterialTheme.typography.bodyLarge, color = if (isPlaying) BlazeOrange else TextPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        IconButton(
                            onClick = {
                                // play if local mapping matches
                            },
                            enabled = track.isLocalAvailable
                        ) {
                            Icon(
                                imageVector = if (track.isLocalAvailable) Icons.Rounded.PlayArrow else Icons.Rounded.List,
                                contentDescription = null,
                                tint = if (track.isLocalAvailable) BlazeOrange else TextMuted
                            )
                        }
                    }
                }
            }

            // Albums section
            val albums = onlineArtist?.albums ?: emptyList()
            if (albums.isNotEmpty()) {
                item {
                    Text("Albums", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(albums) { album ->
                            Column(
                                modifier = Modifier
                                    .width(130.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(OffBlack)
                                    .clickable { onNavigateToAlbum(album.id) }
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(106.dp).clip(RoundedCornerShape(4.dp)).background(DarkGraphite),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.List, contentDescription = null, tint = TextMuted)
                                }
                                Text(album.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(album.releaseDate?.take(4) ?: "", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailScreen(
    playlistId: String?,
    playlistName: String,
    tracks: List<PlaylistTrackRow>,
    allTracks: List<TrackListRow>,
    database: AuraDatabase,
    orchestrator: DesktopPlaybackOrchestrator,
    onDeletePlaylist: () -> Unit,
    onReload: () -> Unit,
    likedTracks: List<TrackListRow>,
    onDeleteLocal: (TrackListRow) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(playlistName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("${tracks.size} titre(s) dans la playlist.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            IconButton(
                onClick = onDeletePlaylist
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Supprimer la playlist", tint = SemanticError)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (tracks.isNotEmpty()) {
                Button(
                    onClick = {
                        val queued = tracks.map { t: PlaylistTrackRow -> t.toQueued() }
                        orchestrator.playTrack(queued[0].trackId, "playlist", playlistId ?: "", queued, 0)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tout lire", fontWeight = FontWeight.Bold)
                }
            }
        }

        Divider(color = HairlineDark)
        TrackListHeader()

        if (tracks.isEmpty()) {
            Box(modifier = Modifier.weight(0.5f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Cette playlist est vide. Ajoutez des pistes locales ci-dessous.", color = TextMuted)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(0.5f)) {
                itemsIndexed(tracks, key = { _, t -> t.playlistItemId }) { index, track ->
                    val isPlaying = orchestrator.uiState.collectAsState().value.currentTrack?.trackId == track.trackId
                    val trackRow = track.toTrackListRow()
                    TrackListItem(
                        index = index,
                        track = trackRow,
                        isLiked = likedTracks.any { it.id == trackRow.id },
                        isPlaying = isPlaying,
                        onPlay = {
                            val queued = tracks.map { t: PlaylistTrackRow -> t.toQueued() }
                            orchestrator.playTrack(track.trackId, "playlist", playlistId ?: "", queued, index)
                        },
                        onLike = { orchestrator.toggleLike(track.trackId, onReload) },
                        onAddToQueue = { orchestrator.addToQueue(track.toQueued()) },
                        onRemove = {
                            coroutineScope.launch(Dispatchers.IO) {
                                database.playlistDao().deletePlaylistItem(track.playlistItemId)
                                onReload()
                            }
                        },
                        onDeleteLocal = if (trackRow.contentUri?.startsWith("file:") == true) {
                            {
                                onDeleteLocal(trackRow)
                            }
                        } else null
                    )
                }
            }
        }

        // Section to add local tracks to playlist
        Divider(color = HairlineDark)
        Text("Ajouter des titres de la bibliothèque", style = MaterialTheme.typography.titleLarge, color = TextPrimary)

        val candidates = allTracks.filter { t -> tracks.none { pt -> pt.trackId == t.id } }
        if (candidates.isEmpty()) {
            Text("Tous les titres de votre bibliothèque sont dans la playlist.", color = TextMuted, modifier = Modifier.padding(vertical = 12.dp))
        } else {
            LazyColumn(modifier = Modifier.weight(0.4f)) {
                items(candidates) { candidate ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(candidate.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(candidate.artistName, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                        Button(
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    playlistId?.let { pid ->
                                        val pos = database.playlistDao().getNextPlaylistPosition(pid)
                                        database.playlistDao().insertPlaylistItem(
                                            PlaylistItemEntity(
                                                id = "playlist-item:${UUID.randomUUID()}",
                                                playlistId = pid,
                                                trackId = candidate.id,
                                                position = pos,
                                                addedAt = System.currentTimeMillis()
                                            )
                                        )
                                        onReload()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite, contentColor = TextPrimary)
                        ) {
                            Text("Ajouter")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackListHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("#", modifier = Modifier.width(30.dp), color = TextMuted, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text("Titre", modifier = Modifier.weight(1f), color = TextMuted, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text("Album", modifier = Modifier.weight(1f), color = TextMuted, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(120.dp))
    }
}

@Composable
fun TrackListItem(
    index: Int,
    track: TrackListRow,
    isLiked: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: ((String) -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onDeleteLocal: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPlaying) DarkGraphite else Color.Transparent)
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Index / Play Icon
        Text(
            text = (index + 1).toString(),
            modifier = Modifier.width(30.dp),
            color = if (isPlaying) BlazeOrange else TextMuted,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        // Cover Image
        CoverImage(
            url = track.coverUri,
            fallbackIcon = Icons.Rounded.MusicNote,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(16.dp))

        // Title & Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isPlaying) BlazeOrange else TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artistName,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Album Title
        Text(
            text = track.albumTitle ?: "Sans Album",
            modifier = Modifier.weight(1f),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Actions Row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Like Button
            IconButton(onClick = onLike) {
                Icon(
                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Aimer",
                    tint = if (isLiked) BlazeOrange else TextMuted
                )
            }

            var showMenu by remember { mutableStateOf(false) }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Options",
                        tint = TextMuted
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Ajouter à la file d'attente") },
                        onClick = {
                            showMenu = false
                            onAddToQueue()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Queue,
                                contentDescription = null,
                                tint = TextPrimary
                            )
                        }
                    )
                    
                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Ajouter à une playlist") },
                            onClick = {
                                showMenu = false
                                onAddToPlaylist(track.id)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.PlaylistAdd,
                                    contentDescription = null,
                                    tint = TextPrimary
                                )
                            }
                        )
                    }
                    
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text("Retirer", color = SemanticError) },
                            onClick = {
                                showMenu = false
                                onRemove()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = SemanticError
                                )
                            }
                        )
                    }

                    if (onDeleteLocal != null) {
                        DropdownMenuItem(
                            text = { Text("Supprimer le fichier", color = SemanticError) },
                            onClick = {
                                showMenu = false
                                onDeleteLocal()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = SemanticError
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QueueItemRow(
    track: QueuedTrack,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkGraphite)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(
                url = track.coverUri,
                fallbackIcon = Icons.Rounded.PlayArrow,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artistName,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Retirer de la file",
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun StatBox(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkGraphite)
            .padding(16.dp)
            .width(120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            color = BlazeOrange,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun PlayerBottomBar(
    state: PlayerUiState,
    orchestrator: DesktopPlaybackOrchestrator,
    onLikeToggle: (String) -> Unit
) {
    var volume by remember { mutableStateOf(0.8f) }
    
    fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(OffBlack)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left Section (Metadata & Like)
        Row(
            modifier = Modifier.width(260.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val track = state.currentTrack
            if (track != null) {
                CoverImage(
                    url = track.coverUri,
                    fallbackIcon = Icons.Rounded.PlayArrow,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artistName,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = { onLikeToggle(track.trackId) }) {
                    Icon(
                        imageVector = if (state.isCurrentTrackLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Aimer",
                        tint = if (state.isCurrentTrackLiked) BlazeOrange else TextMuted
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(56.dp))
                Text("Aucun titre", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Center Section (Playback Controls & Timeline)
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { orchestrator.toggleShuffle() }) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Aléatoire",
                        tint = if (state.shuffleEnabled) BlazeOrange else TextMuted
                    )
                }

                IconButton(onClick = { orchestrator.previous() }) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Précédent",
                        tint = TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                val isPlaying = state.playbackState == PlaybackState.Playing
                Button(
                    onClick = { orchestrator.togglePlayPause() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BlazeOrange,
                        contentColor = TextPrimary
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Lecture",
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = { orchestrator.next() }) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Suivant",
                        tint = TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                val isRepeatOne = state.repeatMode == RepeatMode.One
                val isRepeatAll = state.repeatMode == RepeatMode.All
                IconButton(onClick = { orchestrator.cycleRepeatMode() }) {
                    Icon(
                        imageVector = if (isRepeatOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Répéter",
                        tint = if (isRepeatOne || isRepeatAll) BlazeOrange else TextMuted
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(state.positionMs),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                
                Slider(
                    value = if (state.durationMs > 0) state.positionMs.toFloat() else 0f,
                    onValueChange = { pos -> orchestrator.seekTo(pos.toLong()) },
                    valueRange = 0f..(if (state.durationMs > 0) state.durationMs.toFloat() else 1f),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        activeTrackColor = BlazeOrange,
                        inactiveTrackColor = DarkGraphite,
                        thumbColor = BlazeOrange
                    )
                )

                Text(
                    text = formatTime(state.durationMs),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Right Section (Volume)
        Row(
            modifier = Modifier.width(200.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.VolumeUp,
                contentDescription = "Volume",
                tint = TextMuted
            )
            
            Slider(
                value = volume,
                onValueChange = { vol ->
                    volume = vol
                    orchestrator.setVolume(vol)
                },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    activeTrackColor = BlazeOrange,
                    inactiveTrackColor = DarkGraphite,
                    thumbColor = BlazeOrange
                )
            )
        }
    }
}

fun TrackListRow.toQueued(): QueuedTrack = QueuedTrack(
    trackId = id,
    title = title,
    artistName = artistName,
    albumTitle = albumTitle,
    contentUri = contentUri,
    durationMs = durationMs ?: 0L,
    coverUri = coverUri,
    source = TrackSource.CONTEXT
)

fun PlaylistTrackRow.toQueued(): QueuedTrack = QueuedTrack(
    trackId = trackId,
    title = title,
    artistName = artistName,
    albumTitle = albumTitle,
    contentUri = contentUri,
    durationMs = durationMs ?: 0L,
    coverUri = coverUri,
    source = TrackSource.CONTEXT
)

fun PlaylistTrackRow.toTrackListRow(): TrackListRow = TrackListRow(
    id = trackId,
    artistId = artistId,
    albumId = albumId,
    title = title,
    artistName = artistName,
    albumTitle = albumTitle,
    contentUri = contentUri,
    durationMs = durationMs,
    coverUri = coverUri,
    isLiked = isLiked,
    createdAt = addedAt,
    updatedAt = addedAt
)

@Composable
fun CoverImage(
    url: String?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    if (!url.isNullOrBlank()) {
        val model: Any = if (url.startsWith("file:")) {
            try {
                File(java.net.URI(url))
            } catch (e: Exception) {
                url
            }
        } else {
            url
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(DarkGraphite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = TextMuted
            )
        }
    }
}

@Composable
fun FavoritesScreen(
    likedTracks: List<TrackListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    onAddToPlaylist: (String) -> Unit,
    onReload: () -> Unit,
    onDeleteLocal: (TrackListRow) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Titres favoris", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("${likedTracks.size} titre(s) aimé(s).", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (likedTracks.isNotEmpty()) {
                Button(
                    onClick = {
                        val queued = likedTracks.map { it.toQueued() }
                        orchestrator.playTrack(queued[0].trackId, "favorites", "favorites", queued, 0)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tout lire", fontWeight = FontWeight.Bold)
                }
            }
        }

        Divider(color = HairlineDark)
        TrackListHeader()

        if (likedTracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun titre favori. Aimez des titres pour les voir apparaître ici.", color = TextMuted)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(likedTracks, key = { _, t -> t.id }) { index, track ->
                    val isPlaying = orchestrator.uiState.collectAsState().value.currentTrack?.trackId == track.id
                    TrackListItem(
                        index = index,
                        track = track,
                        isLiked = likedTracks.any { it.id == track.id },
                        isPlaying = isPlaying,
                        onPlay = {
                            val queued = likedTracks.map { it.toQueued() }
                            orchestrator.playTrack(track.id, "favorites", "favorites", queued, index)
                        },
                        onLike = { orchestrator.toggleLike(track.id, onReload) },
                        onAddToQueue = { orchestrator.addToQueue(track.toQueued()) },
                        onAddToPlaylist = onAddToPlaylist,
                        onDeleteLocal = if (track.contentUri?.startsWith("file:") == true) {
                            {
                                onDeleteLocal(track)
                            }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(
    downloadJobs: List<DownloadJobRowModel>,
    orchestrator: DesktopPlaybackOrchestrator,
    onReload: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Téléchargements", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("${downloadJobs.size} tâche(s) de téléchargement.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
        }

        Divider(color = HairlineDark)

        if (downloadJobs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun téléchargement en cours ou complété.", color = TextMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(downloadJobs, key = { it.jobId }) { job ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(OffBlack)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverImage(
                            url = job.coverUri,
                            fallbackIcon = Icons.Rounded.Download,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(job.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(job.artistName, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            
                            if (!job.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = "Erreur: ${job.errorMessage}",
                                    color = SemanticError,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(horizontalAlignment = Alignment.End) {
                            val statusLabel = when (job.status) {
                                "pending", "queued" -> "En attente"
                                "running", "downloading" -> "Téléchargement..."
                                "succeeded", "completed" -> "Terminé"
                                "failed" -> "Échoué"
                                "cancelled" -> "Annulé"
                                else -> job.status.replaceFirstChar { it.uppercase() }
                            }
                            
                            val statusColor = when (job.status) {
                                "succeeded", "completed" -> Color.Green
                                "failed" -> SemanticError
                                "running", "downloading" -> BlazeOrange
                                else -> TextMuted
                            }
                            
                            Text(statusLabel, color = statusColor, fontWeight = FontWeight.SemiBold)
                            
                            val progress = job.progressPercent
                            if ((job.status == "running" || job.status == "downloading") && progress != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${progress.toInt()}%",
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CloudSyncScreen(
    orchestrator: DesktopPlaybackOrchestrator,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var cloudFiles by remember { mutableStateOf<List<com.aura.music.data.network.SyncedFileResponseData>>(emptyList()) }
    var localTracks by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf("cloud_only") } // options: "cloud_only", "pending_upload", "all_cloud"
    var sortByFileSize by remember { mutableStateOf(false) }

    // Active operations progress indicators
    val activeOperations = remember { mutableStateMapOf<String, String>() }

    // Fetch cloud files, settings, and local tracks
    LaunchedEffect(refreshTick, orchestrator.apiToken) {
        val token = orchestrator.apiToken
        if (!token.isNullOrBlank()) {
            isLoading = true
            try {
                val response = orchestrator.apiService.listSyncFiles(token)
                if (response.data != null) {
                    cloudFiles = response.data!!.items
                }
                localTracks = orchestrator.database.trackDao().getAllTracks()
            } catch (e: Exception) {
                System.err.println("Failed to fetch cloud sync status: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    val syncedTrackIds = remember(cloudFiles) { cloudFiles.map { it.trackId }.toSet() }

    // Filter tracks
    // 1. À uploader (local files not on cloud)
    val pendingUploadTracks = remember(localTracks, syncedTrackIds) {
        localTracks.filter { track ->
            val isLocal = track.contentUri?.startsWith("file://") == true && 
                !track.contentUri.contains(".aura/downloads") && 
                !track.contentUri.contains(".aura\\downloads")
            isLocal && !syncedTrackIds.contains(track.id)
        }
    }

    // 2. À récupérer (cloud files missing locally or not downloaded)
    val cloudOnlyFiles = remember(cloudFiles, localTracks) {
        cloudFiles.filter { cloudFile ->
            val localTrack = localTracks.find { it.id == cloudFile.trackId }
            val appDir = File(System.getProperty("user.home"), ".aura")
            val downloadsDir = File(appDir, "downloads")
            val targetFile = File(downloadsDir, "${cloudFile.trackId.replace(':', ';')}.mp3")
            
            localTrack == null || localTrack.contentUri.isNullOrBlank() || !targetFile.exists() || targetFile.length() == 0L
        }
    }

    // VPS Storage calculation
    val totalSizeBytes = remember(cloudFiles) { cloudFiles.sumOf { it.sizeBytes } }
    val totalSizeMb = remember(totalSizeBytes) { String.format("%.2f MB", totalSizeBytes.toDouble() / (1024 * 1024)) }
    val maxVpsLimitBytes = 5L * 1024L * 1024L * 1024L // 5 GB limit representation
    val storageFraction = remember(totalSizeBytes) { (totalSizeBytes.toFloat() / maxVpsLimitBytes.toFloat()).coerceIn(0f, 1f) }

    // Bulk upload/download actions
    var isBulkUploading by remember { mutableStateOf(false) }
    var isBulkDownloading by remember { mutableStateOf(false) }

    fun triggerRefresh() {
        refreshTick++
    }

    fun handleUpload(trackId: String) {
        val token = orchestrator.apiToken ?: return
        activeOperations[trackId] = "Envoi..."
        coroutineScope.launch {
            try {
                orchestrator.uploadCloudTrack(token, trackId)
            } catch (e: Exception) {
                System.err.println("Upload failed: ${e.message}")
            } finally {
                activeOperations.remove(trackId)
                triggerRefresh()
            }
        }
    }

    fun handleDownload(cloudFile: com.aura.music.data.network.SyncedFileResponseData) {
        val token = orchestrator.apiToken ?: return
        val trackId = cloudFile.trackId
        activeOperations[trackId] = "Téléchargement..."
        coroutineScope.launch {
            try {
                orchestrator.downloadCloudTrack(
                    token = token,
                    trackId = trackId,
                    title = cloudFile.title ?: "Titre inconnu",
                    artistName = cloudFile.artistName ?: "Artiste inconnu",
                    albumTitle = cloudFile.albumTitle,
                    durationMs = cloudFile.durationMs ?: 0L,
                    coverUri = cloudFile.coverUri
                )
            } catch (e: Exception) {
                System.err.println("Download failed: ${e.message}")
            } finally {
                activeOperations.remove(trackId)
                triggerRefresh()
            }
        }
    }

    fun handleDelete(trackId: String) {
        val token = orchestrator.apiToken ?: return
        activeOperations[trackId] = "Suppression..."
        coroutineScope.launch {
            try {
                orchestrator.deleteCloudTrack(token, trackId)
            } catch (e: Exception) {
                System.err.println("Delete failed: ${e.message}")
            } finally {
                activeOperations.remove(trackId)
                triggerRefresh()
            }
        }
    }

    fun handleBulkUpload() {
        val token = orchestrator.apiToken ?: return
        isBulkUploading = true
        coroutineScope.launch {
            try {
                pendingUploadTracks.forEach { track ->
                    activeOperations[track.id] = "Envoi..."
                    try {
                        orchestrator.uploadCloudTrack(token, track.id)
                    } catch (e: Exception) {
                        System.err.println("Bulk upload failed for ${track.id}: ${e.message}")
                    } finally {
                        activeOperations.remove(track.id)
                    }
                }
            } finally {
                isBulkUploading = false
                triggerRefresh()
            }
        }
    }

    fun handleBulkDownload() {
        val token = orchestrator.apiToken ?: return
        isBulkDownloading = true
        coroutineScope.launch {
            try {
                cloudOnlyFiles.forEach { cloudFile ->
                    activeOperations[cloudFile.trackId] = "Téléchargement..."
                    try {
                        orchestrator.downloadCloudTrack(
                            token = token,
                            trackId = cloudFile.trackId,
                            title = cloudFile.title ?: "Titre inconnu",
                            artistName = cloudFile.artistName ?: "Artiste inconnu",
                            albumTitle = cloudFile.albumTitle,
                            durationMs = cloudFile.durationMs ?: 0L,
                            coverUri = cloudFile.coverUri
                        )
                    } catch (e: Exception) {
                        System.err.println("Bulk download failed for ${cloudFile.trackId}: ${e.message}")
                    } finally {
                        activeOperations.remove(cloudFile.trackId)
                    }
                }
            } finally {
                isBulkDownloading = false
                triggerRefresh()
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // --- COLONNE GAUCHE (Stockage & Options) ---
        Column(
            modifier = Modifier.width(320.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Retour
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onNavigateBack() }
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = BlazeOrange)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retour aux paramètres", color = BlazeOrange, fontWeight = FontWeight.Bold)
            }

            Text("Stockage Cloud", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = TextPrimary)

            // VPS Space Gauge Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Storage, contentDescription = null, tint = BlazeOrange)
                        Text("VPS personnel AURA", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }

                    LinearProgressIndicator(
                        progress = storageFraction,
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = BlazeOrange,
                        trackColor = DarkGraphite
                    )

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(totalSizeMb, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text("de 5.00 Go", color = TextMuted)
                    }
                }
            }

            // Sync Options Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync automatique", color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text("Envoie et télécharge en arrière-plan.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                        Switch(
                            checked = orchestrator.autoSyncEnabled,
                            onCheckedChange = { orchestrator.autoSyncEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextPrimary,
                                checkedTrackColor = BlazeOrange
                            )
                        )
                    }

                    Button(
                        onClick = { triggerRefresh() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Actualiser l'index cloud", color = TextPrimary)
                    }
                }
            }
        }

        // --- COLONNE DROITE (Filtres & Liste des fichiers) ---
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Navigation chips row
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filters = listOf(
                    "cloud_only" to "À récupérer (${cloudOnlyFiles.size})",
                    "pending_upload" to "À uploader (${pendingUploadTracks.size})",
                    "all_cloud" to "Tout le Cloud (${cloudFiles.size})"
                )
                filters.forEach { (key, label) ->
                    val selected = selectedFilter == key
                    Box(
                        modifier = Modifier
                            .background(
                                if (selected) BlazeOrange else DarkGraphite,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable { selectedFilter = key }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.White else TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Bulk actions & Sort Bar
            Row(
                modifier = Modifier.fillMaxWidth().background(OffBlack, RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.clickable { sortByFileSize = !sortByFileSize },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Sort, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (sortByFileSize) "Trié par Taille" else "Trié par Nom",
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Bulk actions buttons
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selectedFilter == "cloud_only" && cloudOnlyFiles.isNotEmpty()) {
                        Button(
                            onClick = { handleBulkDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                            enabled = !isBulkDownloading
                        ) {
                            if (isBulkDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                            } else {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Tout récupérer")
                            }
                        }
                    } else if (selectedFilter == "pending_upload" && pendingUploadTracks.isNotEmpty()) {
                        Button(
                            onClick = { handleBulkUpload() },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                            enabled = !isBulkUploading
                        ) {
                            if (isBulkUploading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                            } else {
                                Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Tout sauvegarder")
                            }
                        }
                    }
                }
            }

            // Lists rendering
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BlazeOrange)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedFilter == "cloud_only") {
                        if (cloudOnlyFiles.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                    Text("Aucun fichier à récupérer.", color = TextMuted)
                                }
                            }
                        } else {
                            val sortedList = if (sortByFileSize) {
                                cloudOnlyFiles.sortedByDescending { it.sizeBytes }
                            } else {
                                cloudOnlyFiles.sortedBy { it.title ?: "" }
                            }

                            items(sortedList) { cloudFile ->
                                CloudTrackRow(
                                    title = cloudFile.title ?: "Titre inconnu",
                                    artist = cloudFile.artistName ?: "Artiste inconnu",
                                    sizeStr = String.format("%.2f MB", cloudFile.sizeBytes.toDouble() / (1024 * 1024)),
                                    opStatus = activeOperations[cloudFile.trackId],
                                    actionLabel = "Télécharger",
                                    onAction = { handleDownload(cloudFile) },
                                    onDelete = { handleDelete(cloudFile.trackId) }
                                )
                            }
                        }
                    } else if (selectedFilter == "pending_upload") {
                        if (pendingUploadTracks.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                    Text("Tout est synchronisé sur le cloud !", color = TextMuted)
                                }
                            }
                        } else {
                            val sortedList = if (sortByFileSize) {
                                pendingUploadTracks.sortedByDescending { it.durationMs ?: 0L }
                            } else {
                                pendingUploadTracks.sortedBy { it.title }
                            }

                            items(sortedList) { track ->
                                val fileBytes = try {
                                    val uriStr = track.contentUri ?: ""
                                    val f = if (uriStr.startsWith("file:/")) File(java.net.URI(uriStr)) else File(uriStr)
                                    if (f.exists()) f.length() else 0L
                                } catch (e: Exception) {
                                    0L
                                }
                                val sizeStr = String.format("%.2f MB", fileBytes.toDouble() / (1024 * 1024))

                                CloudTrackRow(
                                    title = track.title,
                                    artist = track.artistName,
                                    sizeStr = sizeStr,
                                    opStatus = activeOperations[track.id],
                                    actionLabel = "Envoyer",
                                    onAction = { handleUpload(track.id) },
                                    onDelete = null
                                )
                            }
                        }
                    } else { // "all_cloud"
                        if (cloudFiles.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                    Text("Le cloud est vide.", color = TextMuted)
                                }
                            }
                        } else {
                            val sortedList = if (sortByFileSize) {
                                cloudFiles.sortedByDescending { it.sizeBytes }
                            } else {
                                cloudFiles.sortedBy { it.title ?: "" }
                            }

                            items(sortedList) { cloudFile ->
                                CloudTrackRow(
                                    title = cloudFile.title ?: "Titre inconnu",
                                    artist = cloudFile.artistName ?: "Artiste inconnu",
                                    sizeStr = String.format("%.2f MB", cloudFile.sizeBytes.toDouble() / (1024 * 1024)),
                                    opStatus = activeOperations[cloudFile.trackId],
                                    actionLabel = null,
                                    onAction = {},
                                    onDelete = { handleDelete(cloudFile.trackId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CloudTrackRow(
    title: String,
    artist: String,
    sizeStr: String,
    opStatus: String?,
    actionLabel: String?,
    onAction: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(OffBlack, RoundedCornerShape(8.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$artist • $sizeStr", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (opStatus != null) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BlazeOrange, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(opStatus, color = BlazeOrange, style = MaterialTheme.typography.bodySmall)
            } else {
                if (actionLabel != null) {
                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite)
                    ) {
                        Text(actionLabel, color = TextPrimary)
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = TextMuted)
                    }
                }
            }
        }
    }
}
