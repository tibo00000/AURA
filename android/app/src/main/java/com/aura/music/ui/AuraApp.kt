package com.aura.music.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import com.aura.music.ui.screens.DownloadsScreen
import com.aura.music.ui.screens.HomeScreen
import com.aura.music.ui.screens.LibraryScreen
import com.aura.music.ui.screens.PlayerScreen
import com.aura.music.ui.screens.PlaylistDetailScreen
import com.aura.music.ui.screens.PlaylistsScreen
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
    val playerUiState by playerViewModel.uiState.collectAsState()

    val topDestinations = remember {
        listOf(
            TopLevelDestination(AuraRoute.Home, "Home", Icons.Rounded.Home),
            TopLevelDestination(AuraRoute.Search, "Search", Icons.Rounded.Search),
            TopLevelDestination(AuraRoute.Library, "Library", Icons.Rounded.LibraryMusic),
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

    val onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit = { track, allTracks, contextType ->
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

    AuraTheme {
        AuraAppScaffold(
            navController = navController,
            topDestinations = topDestinations,
            playerUiState = playerUiState,
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
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) },
                )
            }
            composable(AuraRoute.Search) {
                SearchScreen(
                    repository = repository,
                    refreshToken = permissionRefreshTick,
                    onRequestAudioPermission = requestAudioPermission,
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) },
                )
            }
            composable(AuraRoute.Library) {
                LibraryScreen(
                    repository = repository,
                    refreshToken = permissionRefreshTick,
                    onRequestAudioPermission = requestAudioPermission,
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenPlaylist = { playlistId -> navController.navigate(AuraRoute.playlistDetail(playlistId)) },
                    onOpenPlaylists = { navController.navigate(AuraRoute.Playlists) },
                    onOpenDownloads = { navController.navigate(AuraRoute.Downloads) },
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) },
                )
            }
            composable(AuraRoute.Playlists) {
                PlaylistsScreen(
                    repository = repository,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenPlaylist = { playlistId -> navController.navigate(AuraRoute.playlistDetail(playlistId)) },
                )
            }
            composable(AuraRoute.PlaylistDetailPattern) { backStackEntry ->
                PlaylistDetailScreen(
                    repository = repository,
                    playerViewModel = playerViewModel,
                    playlistId = backStackEntry.arguments?.getString(AuraRoute.PlaylistIdArg).orEmpty(),
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(AuraRoute.ArtistPattern) { backStackEntry ->
                ArtistRouteScreen(
                    repository = repository,
                    artistId = backStackEntry.arguments?.getString(AuraRoute.ArtistIdArg).orEmpty(),
                    onNavigateBack = { navController.popBackStack() },
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) },
                )
            }
            composable(AuraRoute.AlbumPattern) { backStackEntry ->
                AlbumRouteScreen(
                    repository = repository,
                    albumId = backStackEntry.arguments?.getString(AuraRoute.AlbumIdArg).orEmpty(),
                    onNavigateBack = { navController.popBackStack() },
                    onPlayTrackInList = onPlayTrackInList,
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) },
                )
            }
            composable(AuraRoute.Downloads) {
                DownloadsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(AuraRoute.Settings) {
                SettingsScreen(
                    repository = repository,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(AuraRoute.Player) {
                PlayerScreen(
                    playerViewModel = playerViewModel,
                    onNavigateBack = { navController.popBackStack() },
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
    playerUiState: PlayerUiState,
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
    val showMiniPlayer = playerUiState.currentTrack != null && currentRoute != AuraRoute.Player

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    if (showMiniPlayer) {
                        MiniPlayerCard(
                            playerUiState = playerUiState,
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
                                    selected = currentRoute == destination.route,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
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
    playerUiState: PlayerUiState,
    onClick: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val track = playerUiState.currentTrack ?: return

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
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(onClick = onTogglePlayPause) {
                val icon = if (playerUiState.playbackState == PlaybackState.Playing) {
                    Icons.Rounded.Pause
                } else {
                    Icons.Rounded.PlayArrow
                }
                Icon(icon, contentDescription = "Toggle play/pause")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
            }
        }
    }
}

object AuraRoute {
    const val Home = "home"
    const val Search = "search"
    const val Library = "library"
    const val Playlists = "playlists"
    const val Downloads = "downloads"
    const val Settings = "settings"
    const val Player = "player"

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
                            Text("Back", color = TextPrimary)
                        }
                    }
                },
                actions = actions
            )
        },
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

@Composable
fun TrackList(
    title: String,
    tracks: List<TrackListRow>,
    contextType: String,
    onPlayTrackInList: (TrackListRow, List<TrackListRow>, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (tracks.isEmpty()) {
            Text(
                text = "No local tracks found yet.",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            tracks.forEach { track ->
                ListItem(
                    headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            text = listOfNotNull(track.artistName, track.albumTitle).joinToString(" | "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${track.title}")
                    },
                    modifier = Modifier.clickable { onPlayTrackInList(track, tracks, contextType) },
                )
            }
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
    coverUri = null,
    source = TrackSource.CONTEXT,
)
