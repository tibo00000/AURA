package com.aura.music.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aura.music.AuraApplication
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.PlayerUiState
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.TrackSource
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.screens.AlbumRouteScreen
import com.aura.music.ui.screens.AlbumDetailViewModel
import com.aura.music.ui.screens.ArtistDetailViewModel
import com.aura.music.ui.screens.DownloadsScreen
import com.aura.music.ui.screens.HomeScreen
import com.aura.music.ui.screens.FavoritesScreen
import com.aura.music.ui.screens.HybridAlbumScreen
import com.aura.music.ui.screens.HybridArtistScreen
import com.aura.music.ui.screens.LibraryScreen
import com.aura.music.ui.screens.LibraryTracksScreen
import com.aura.music.ui.screens.LibraryArtistsScreen
import com.aura.music.ui.screens.PlayerScreen
import com.aura.music.ui.screens.PlaylistDetailScreenNew
import com.aura.music.ui.screens.PlaylistsListScreen
import com.aura.music.ui.screens.SearchScreen
import com.aura.music.ui.screens.SettingsScreen
import com.aura.music.ui.screens.ArtistRouteScreen
import com.aura.music.ui.theme.*

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun AuraApp() {
    val application = LocalContext.current.applicationContext as AuraApplication
    val repository = application.container.localLibraryRepository
    val navController = rememberNavController()
    val playerViewModel: PlayerViewModel = viewModel(
        factory = application.container.playerViewModelFactory,
    )
    val currentTrack by remember(playerViewModel) {
        playerViewModel.uiState.map { it.currentTrack }.distinctUntilChanged()
    }.collectAsState(initial = null)

    val playbackState by remember(playerViewModel) {
        playerViewModel.uiState.map { it.playbackState }.distinctUntilChanged()
    }.collectAsState(initial = PlaybackState.Idle)

    val topDestinations = remember {
        listOf(
            TopLevelDestination(AuraRoute.Home, "Accueil", Icons.Rounded.Home),
            TopLevelDestination(AuraRoute.Search, "Recherche", Icons.Rounded.Search),
            TopLevelDestination(AuraRoute.Library, "Bibliothèque", Icons.Rounded.LibraryMusic),
            TopLevelDestination(AuraRoute.Settings, "Paramètres", Icons.Rounded.Settings)
        )
    }
    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRefreshTick++
    }

    val requestAudioPermission = {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    LaunchedEffect(permissionRefreshTick) {
        repository.ensureDefaults()
        repository.refreshLocalMediaIndex()
    }

    val onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit = remember(playerViewModel, navController) {
        { track, allTracks, contextType ->
            val contextTracks = allTracks.map { it.toQueuedTrack() }
            val startIndex = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playerViewModel.onEvent(
                PlayerEvent.PlayTrack(
                    trackId = track.id,
                    contextType = contextType,
                    contextId = contextType,
                    contextTracks = contextTracks,
                    startIndex = startIndex,
                ),
            )
            navController.navigate(AuraRoute.Player)
        }
    }

    AuraTheme {
        AuraAppScaffold(
            navController = navController,
            topDestinations = topDestinations,
            currentTrack = currentTrack,
            playbackState = playbackState,
            onMiniPlayerClick = { navController.navigate(AuraRoute.Player) },
            onPrevious = { playerViewModel.onEvent(PlayerEvent.Previous) },
            onTogglePlayPause = { playerViewModel.onEvent(PlayerEvent.TogglePlayPause) },
            onNext = { playerViewModel.onEvent(PlayerEvent.Next) },
        ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AuraRoute.Home,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AuraRoute.Home) {
                HomeScreen(
                    repository = repository,
                    refreshToken = permissionRefreshTick,
                    onRequestAudioPermission = requestAudioPermission,
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenPlaylist = { playlistId -> navController.navigate(AuraRoute.playlistDetail(playlistId)) },
                    onOpenDownloads = { navController.navigate(AuraRoute.Downloads) },
                    onOpenPlayer = { navController.navigate(AuraRoute.Player) },
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) { launchSingleTop = true } },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) { launchSingleTop = true } },
                )
            }
            composable(AuraRoute.Search) {
                SearchScreen(
                    repository = repository,
                    refreshToken = permissionRefreshTick,
                    onRequestAudioPermission = requestAudioPermission,
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) { launchSingleTop = true } },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) { launchSingleTop = true } },
                    onOpenDownloads = { navController.navigate(AuraRoute.Downloads) },
                    playerViewModel = playerViewModel,
                )
            }
            composable(AuraRoute.Library) {
                LibraryScreen(
                    repository = repository,
                    playerViewModel = playerViewModel,
                    refreshToken = permissionRefreshTick,
                    onRequestAudioPermission = requestAudioPermission,
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenPlaylist = { playlistId -> navController.navigate(AuraRoute.playlistDetail(playlistId)) },
                    onOpenPlaylists = { navController.navigate(AuraRoute.Playlists) },
                    onOpenFavorites = { navController.navigate(AuraRoute.Favorites) },
                    onOpenTracks = { navController.navigate(AuraRoute.LibraryTracks) },
                    onOpenArtists = { navController.navigate(AuraRoute.LibraryArtists) },
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) { launchSingleTop = true } },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) { launchSingleTop = true } },
                )
            }
            composable(AuraRoute.LibraryTracks) {
                LibraryTracksScreen(
                    repository = repository,
                    playerViewModel = playerViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) { launchSingleTop = true } },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) { launchSingleTop = true } },
                )
            }
            composable(AuraRoute.LibraryArtists) {
                LibraryArtistsScreen(
                    repository = repository,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) { launchSingleTop = true } },
                )
            }
            composable(AuraRoute.Playlists) {
                PlaylistsListScreen(
                    repository = repository,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenPlaylist = { playlistId -> navController.navigate(AuraRoute.playlistDetail(playlistId)) },
                )
            }
            composable(AuraRoute.Favorites) {
                FavoritesScreen(
                    repository = repository,
                    playerViewModel = playerViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) { launchSingleTop = true } },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) { launchSingleTop = true } },
                )
            }
            composable(AuraRoute.PlaylistDetailPattern) { backStackEntry ->
                PlaylistDetailScreenNew(
                    repository = repository,
                    playerViewModel = playerViewModel,
                    playlistId = backStackEntry.arguments?.getString(AuraRoute.PlaylistIdArg).orEmpty(),
                    onNavigateBack = { navController.popBackStack() },
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) { launchSingleTop = true } },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) { launchSingleTop = true } },
                )
            }
            composable(AuraRoute.ArtistPattern) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getString(AuraRoute.ArtistIdArg).orEmpty()
                val ctx = LocalContext.current
                val enrichmentRepo = (ctx.applicationContext as com.aura.music.AuraApplication)
                    .container.enrichmentRepository
                val vm: ArtistDetailViewModel = viewModel(
                    key = "artist_$artistId",
                    factory = ArtistDetailViewModel.Factory(
                        artistId = artistId,
                        localRepo = repository,
                        apiService = (ctx.applicationContext as com.aura.music.AuraApplication).container.auraApiService,
                        enrichmentRepo = enrichmentRepo,
                        appContext = ctx.applicationContext,
                    )
                )
                val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository) {
                    value = repository.getPlaylists()
                }
                val scope = rememberCoroutineScope()
                var pendingDeleteTrackId by remember { mutableStateOf<String?>(null) }
                val intentSenderLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        pendingDeleteTrackId?.let { trackId ->
                            scope.launch {
                                repository.deleteTrack(trackId)
                                pendingDeleteTrackId = null
                                vm.refreshLocal()
                            }
                        }
                    } else {
                        pendingDeleteTrackId = null
                    }
                }
                HybridArtistScreen(
                    viewModel = vm,
                    playlists = playlistsState.value,
                    onNavigateBack = { navController.popBackStack() },
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) { launchSingleTop = true } },
                    onLikeTrack = { track ->
                        scope.launch {
                            repository.toggleLike(track.id, track.isLiked, "artist", artistId)
                            vm.refreshLocal()
                        }
                    },
                    onAddTrackToPlaylist = { playlist, track ->
                        scope.launch {
                            repository.addTrackToPlaylist(playlist.id, track.id, "artist")
                            vm.refreshLocal()
                        }
                    },
                    onDeleteTrack = { track ->
                        scope.launch {
                            val trackId = track.id
                            pendingDeleteTrackId = trackId
                            val pendingIntent = repository.deleteTrack(trackId)
                            if (pendingIntent != null) {
                                try {
                                    val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                    intentSenderLauncher.launch(intentSenderRequest)
                                } catch (e: Exception) {
                                    android.util.Log.e("AuraApp", "Failed to launch intent for deletion", e)
                                    pendingDeleteTrackId = null
                                }
                            } else {
                                pendingDeleteTrackId = null
                                vm.refreshLocal()
                            }
                        }
                    },
                    onAddToQueue = { track ->
                        playerViewModel.onEvent(com.aura.music.domain.player.PlayerEvent.AddToQueue(track.toQueuedTrack()))
                    }
                )
            }
            composable(AuraRoute.AlbumPattern) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString(AuraRoute.AlbumIdArg).orEmpty()
                val ctx = LocalContext.current
                val enrichmentRepo = (ctx.applicationContext as com.aura.music.AuraApplication)
                    .container.enrichmentRepository
                val vm: AlbumDetailViewModel = viewModel(
                    key = "album_$albumId",
                    factory = AlbumDetailViewModel.Factory(
                        albumId = albumId,
                        localRepo = repository,
                        apiService = (ctx.applicationContext as com.aura.music.AuraApplication).container.auraApiService,
                        enrichmentRepo = enrichmentRepo,
                        appContext = ctx.applicationContext,
                    )
                )
                val playlistsState = produceState(initialValue = emptyList<PlaylistListRow>(), repository) {
                    value = repository.getPlaylists()
                }
                val scope = rememberCoroutineScope()
                var pendingDeleteTrackId by remember { mutableStateOf<String?>(null) }
                val intentSenderLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        pendingDeleteTrackId?.let { trackId ->
                            scope.launch {
                                repository.deleteTrack(trackId)
                                pendingDeleteTrackId = null
                                vm.refreshLocal()
                            }
                        }
                    } else {
                        pendingDeleteTrackId = null
                    }
                }
                HybridAlbumScreen(
                    viewModel = vm,
                    playlists = playlistsState.value,
                    onNavigateBack = { navController.popBackStack() },
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) { launchSingleTop = true } },
                    onLikeTrack = { track ->
                        scope.launch {
                            repository.toggleLike(track.id, track.isLiked, "album", albumId)
                            vm.refreshLocal()
                        }
                    },
                    onAddTrackToPlaylist = { playlist, track ->
                        scope.launch {
                            repository.addTrackToPlaylist(playlist.id, track.id, "album")
                            vm.refreshLocal()
                        }
                    },
                    onDeleteTrack = { track ->
                        scope.launch {
                            val trackId = track.id
                            pendingDeleteTrackId = trackId
                            val pendingIntent = repository.deleteTrack(trackId)
                            if (pendingIntent != null) {
                                try {
                                    val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                    intentSenderLauncher.launch(intentSenderRequest)
                                } catch (e: Exception) {
                                    android.util.Log.e("AuraApp", "Failed to launch intent for deletion", e)
                                    pendingDeleteTrackId = null
                                }
                            } else {
                                pendingDeleteTrackId = null
                                vm.refreshLocal()
                            }
                        }
                    },
                    onAddToQueue = { track ->
                        playerViewModel.onEvent(com.aura.music.domain.player.PlayerEvent.AddToQueue(track.toQueuedTrack()))
                    }
                )
            }
            composable(AuraRoute.Downloads) {
                val ctx = LocalContext.current
                val appContainer = (ctx.applicationContext as com.aura.music.AuraApplication).container
                val downloadsViewModel: com.aura.music.ui.downloads.DownloadsViewModel = viewModel(
                    factory = appContainer.downloadsViewModelFactory
                )
                DownloadsScreen(
                    viewModel = downloadsViewModel,
                    playerViewModel = playerViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(AuraRoute.Settings) {
                val ctx = LocalContext.current
                val appContainer = (ctx.applicationContext as com.aura.music.AuraApplication).container
                SettingsScreen(
                    repository = repository,
                    downloadRepository = appContainer.downloadRepository,
                    syncRepository = appContainer.syncRepository,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSandbox = { navController.navigate(AuraRoute.Sandbox) }
                )
            }
            composable(AuraRoute.Sandbox) {
                com.aura.music.ui.screens.SandboxScreen(
                    repository = repository,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = AuraRoute.Player,
                enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, animationSpec = tween(300)) },
                exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, animationSpec = tween(300)) },
                popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, animationSpec = tween(300)) },
                popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, animationSpec = tween(300)) }
            ) {
                PlayerScreen(
                    playerViewModel = playerViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenArtist = { artistId ->
                        navController.navigate(AuraRoute.artist(artistId)) { launchSingleTop = true }
                    },
                    onOpenAlbum = { albumId ->
                        navController.navigate(AuraRoute.album(albumId)) { launchSingleTop = true }
                    }
                )
            }
        }
    }
    }
}

@Composable
private fun AuraAppScaffold(
    navController: NavHostController,
    topDestinations: List<TopLevelDestination>,
    currentTrack: QueuedTrack?,
    playbackState: PlaybackState,
    onMiniPlayerClick: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevelRoute = topDestinations.any { it.route == currentRoute }
    val showBottomBar = currentRoute != AuraRoute.Player
    val showMiniPlayer = currentTrack != null && currentRoute != AuraRoute.Player

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    if (showMiniPlayer) {
                        MiniPlayerCard(
                            currentTrack = currentTrack,
                            playbackState = playbackState,
                            onClick = onMiniPlayerClick,
                            onPrevious = onPrevious,
                            onTogglePlayPause = onTogglePlayPause,
                            onNext = onNext,
                        )
                    }
                    NavigationBar(
                        containerColor = OffBlack,
                        contentColor = TextSecondary
                    ) {
                        topDestinations.forEach { destination ->
                            NavigationBarItem(
                                selected = isRouteInTab(currentRoute, destination.route),
                                onClick = {
                                    if (isRouteInTab(currentRoute, destination.route)) {
                                        if (currentRoute != destination.route) {
                                            navController.popBackStack(destination.route, inclusive = false)
                                        } else {
                                            navController.navigate(destination.route) {
                                                popUpTo(destination.route) { inclusive = true }
                                            }
                                        }
                                    } else {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Icon(destination.icon, contentDescription = destination.label) },
                                label = { Text(destination.label) },
                                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    selectedIconColor = BlazeOrange,
                                    selectedTextColor = BlazeOrange,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        content(innerPadding)
    }
}

@Composable
private fun MiniPlayerCard(
    currentTrack: QueuedTrack?,
    playbackState: PlaybackState,
    onClick: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val track = currentTrack ?: return

    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = track.artistName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Piste precedente")
            }
            IconButton(onClick = onTogglePlayPause) {
                val icon = if (playbackState == PlaybackState.Playing) {
                    Icons.Rounded.Pause
                } else {
                    Icons.Rounded.PlayArrow
                }
                Icon(icon, contentDescription = "Lecture ou pause")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Piste suivante")
            }
        }
    }
}

object AuraRoute {
    const val Home = "home"
    const val Search = "search"
    const val Library = "library"
    const val Playlists = "playlists"
    const val Favorites = "favorites"
    const val Downloads = "downloads"
    const val Settings = "settings"
    const val Player = "player"
    const val LibraryTracks = "library_tracks"
    const val LibraryArtists = "library_artists"
    const val Sandbox = "sandbox"

    const val ArtistIdArg = "artistId"
    const val AlbumIdArg = "albumId"
    const val PlaylistIdArg = "playlistId"

    const val ArtistPattern = "artist/{$ArtistIdArg}"
    const val AlbumPattern = "album/{$AlbumIdArg}"
    const val PlaylistDetailPattern = "playlist/{$PlaylistIdArg}"

    fun artist(artistId: String): String = "artist/$artistId"
    fun album(albumId: String): String = "album/$albumId"
    fun playlistDetail(playlistId: String): String = "playlist/$playlistId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScaffold(
    title: String? = null,
    style: TextStyle? = null,
    onNavigateBack: (() -> Unit)? = null,
    snackbarHostState: androidx.compose.material3.SnackbarHostState? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (title != null) Text(title, style = style ?: MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DeepBlack,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary,
                    actionIconContentColor = TextPrimary
                ),
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Retour",
                                tint = TextPrimary,
                            )
                        }
                    }
                },
                actions = actions
            )
        },
        snackbarHost = {
            if (snackbarHostState != null) {
                androidx.compose.material3.SnackbarHost(hostState = snackbarHostState) { data ->
                    androidx.compose.material3.Snackbar(
                        snackbarData = data,
                        containerColor = ElevatedGraphite,
                        contentColor = TextPrimary,
                        actionColor = BlazeOrange
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            content()
        }
    }
}

@Composable
fun SummaryList(title: String, items: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (items.isEmpty()) {
            Text(
                text = "Nothing to show yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = item,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

fun LazyListScope.trackList(
    title: String,
    tracks: List<TrackListRow>,
    contextType: String,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    showCover: Boolean = true,
    onAddTrackToPlaylist: ((TrackListRow) -> Unit)? = null,
    onLikeTrack: ((TrackListRow) -> Unit)? = null,
    onPlayNow: ((TrackListRow) -> Unit)? = null,
    onAddToQueue: ((TrackListRow) -> Unit)? = null,
    onDeleteDownload: ((TrackListRow) -> Unit)? = null,
) {
    if (title.isNotBlank()) {
        item(key = "tracklist_title_${title}_${contextType}") {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    if (tracks.isEmpty()) {
        item(key = "tracklist_empty_${contextType}") {
            Text(
                text = "No local tracks found yet.",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(
            items = tracks,
            key = { track -> "track_${track.id}_${contextType}" },
            contentType = { "track_row" }
        ) { track ->
            val currentOnPlay = rememberUpdatedState(onPlayTrackInList)
            val currentOnAdd = rememberUpdatedState(onAddTrackToPlaylist)
            val currentOnLike = rememberUpdatedState(onLikeTrack)
            val currentOnPlayNow = rememberUpdatedState(onPlayNow)
            val currentOnAddToQueue = rememberUpdatedState(onAddToQueue)
            val currentOnOpenArtist = rememberUpdatedState(onOpenArtist)
            val currentOnOpenAlbum = rememberUpdatedState(onOpenAlbum)
            val currentOnDelete = rememberUpdatedState(onDeleteDownload)

            val currentOnClick = remember(track.id, tracks, contextType) {
                { currentOnPlay.value(track, tracks, contextType) }
            }
            val onAddToPlaylistLambda = remember(track.id, currentOnAdd.value != null) {
                val cb = currentOnAdd.value
                if (cb != null) { { cb(track) } } else null
            }
            val onLikeLambda = remember(track.id, currentOnLike.value != null) {
                val cb = currentOnLike.value
                if (cb != null) { { cb(track) } } else null
            }
            val onUnlikeLambda = remember(track.id, currentOnLike.value != null) {
                val cb = currentOnLike.value
                if (cb != null) { { cb(track) } } else null
            }
            val onPlayNowLambda = remember(track.id, currentOnPlayNow.value != null) {
                val cb = currentOnPlayNow.value
                if (cb != null) { { cb(track) } } else null
            }
            val onAddToQueueLambda = remember(track.id, currentOnAddToQueue.value != null) {
                val cb = currentOnAddToQueue.value
                if (cb != null) { { cb(track) } } else null
            }
            val artistId = track.artistId
            val hasArtist = !artistId.isNullOrBlank()
            val onViewArtistLambda = remember(track.id, artistId, hasArtist) {
                val cb = currentOnOpenArtist.value
                if (hasArtist) { { cb(artistId!!) } } else null
            }
            val albumId = track.albumId
            val hasAlbum = !albumId.isNullOrBlank()
            val onViewAlbumLambda = remember(track.id, albumId, hasAlbum) {
                val cb = currentOnOpenAlbum.value
                if (hasAlbum) { { cb(albumId!!) } } else null
            }
            val onDeleteDownloadLambda = remember(track.id, currentOnDelete.value != null) {
                val cb = currentOnDelete.value
                if (cb != null) { { cb(track) } } else null
            }

            com.aura.music.ui.screens.SharedTrackRowItem(
                title = track.title,
                subtitle = listOfNotNull(track.artistName, track.albumTitle).joinToString(" | "),
                coverUri = track.coverUri,
                showCover = showCover,
                onClick = currentOnClick,
                contextType = contextType,
                isLiked = track.isLiked,
                onAddToPlaylist = onAddToPlaylistLambda,
                onLike = onLikeLambda,
                onUnlike = onUnlikeLambda,
                onPlayNow = onPlayNowLambda,
                onAddToQueue = onAddToQueueLambda,
                onViewArtist = onViewArtistLambda,
                onViewAlbum = onViewAlbumLambda,
                onDeleteDownload = onDeleteDownloadLambda,
            )
        }
    }
}

@Composable
fun PlaylistPreviewList(
    playlists: List<PlaylistListRow>,
    onOpenPlaylist: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (playlists.isEmpty()) {
            Text(
                text = "No local playlist yet. Create one from Library to build reusable listening contexts.",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            playlists.forEach { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    supportingContent = {
                        val typeLabel = if (playlist.isPinned) "Pinned playlist" else "Local playlist"
                        Text("$typeLabel | ${playlist.itemCount} track(s)")
                    },
                    modifier = Modifier.clickable { onOpenPlaylist(playlist.id) },
                )
            }
        }
    }
}

@Composable
fun DashboardSummaryCard(
    summary: LibraryDashboardSummary,
    onRequestAudioPermission: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Local-first shell ready", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Room tracks: ${summary.roomTrackCount} | MediaStore: ${summary.mediaStoreTrackCount} | Playlists: ${summary.playlistCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Recent searches: ${summary.recentSearchCount} | Snapshot active: ${summary.activeSnapshot != null}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!summary.hasAudioPermission) {
                Button(onClick = onRequestAudioPermission) {
                    Text("Grant audio access")
                }
            }
        }
    }
}

/**
 * Extension pour convertir un TrackListRow en QueuedTrack.
 */
fun TrackListRow.toQueuedTrack(): QueuedTrack = QueuedTrack(
    trackId = id,
    title = title,
    artistName = artistName,
    albumTitle = albumTitle,
    contentUri = contentUri,
    durationMs = durationMs,
    coverUri = coverUri,
    source = TrackSource.CONTEXT,
)

private fun isRouteInTab(route: String?, tabRoute: String): Boolean {
    if (route == null) return false
    if (route == tabRoute) return true
    return when (tabRoute) {
        AuraRoute.Home -> route == AuraRoute.ArtistPattern || route == AuraRoute.AlbumPattern
        AuraRoute.Search -> route == AuraRoute.Downloads
        AuraRoute.Library -> route == AuraRoute.Playlists ||
                route == AuraRoute.Favorites ||
                route == AuraRoute.LibraryTracks ||
                route == AuraRoute.LibraryArtists ||
                route == AuraRoute.PlaylistDetailPattern
        AuraRoute.Settings -> route == AuraRoute.Sandbox
        else -> false
    }
}
