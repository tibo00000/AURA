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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.aura.music.data.repository.LocalLibraryRepository
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
import kotlinx.coroutines.launch

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

data class PlayerPreview(
    val trackId: String,
    val title: String,
    val subtitle: String,
)

@Composable
fun AuraApp() {
    val application = LocalContext.current.applicationContext as AuraApplication
    val repository = application.container.localLibraryRepository
    val navController = rememberNavController()
    val topDestinations = remember {
        listOf(
            TopLevelDestination(AuraRoute.Home, "Home", Icons.Rounded.Home),
            TopLevelDestination(AuraRoute.Search, "Search", Icons.Rounded.Search),
            TopLevelDestination(AuraRoute.Library, "Library", Icons.Rounded.LibraryMusic),
        )
    }
    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    var currentPreview by remember { mutableStateOf<PlayerPreview?>(null) }
    val scope = rememberCoroutineScope()

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

    AuraAppScaffold(
        navController = navController,
        topDestinations = topDestinations,
        currentPreview = currentPreview,
        onMiniPlayerClick = { navController.navigate(AuraRoute.Player) },
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
                    onOpenPlayer = { track ->
                        currentPreview = PlayerPreview(track.id, track.title, track.artistName)
                        scope.launch {
                            repository.seedPlaybackPreview(track.id)
                        }
                        navController.navigate(AuraRoute.Player)
                    },
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) },
                )
            }
            composable(AuraRoute.Search) {
                SearchScreen(
                    repository = repository,
                    refreshToken = permissionRefreshTick,
                    onRequestAudioPermission = requestAudioPermission,
                    onPlayTrack = { track ->
                        currentPreview = PlayerPreview(track.id, track.title, track.artistName)
                        scope.launch {
                            repository.seedPlaybackPreview(track.id)
                        }
                        navController.navigate(AuraRoute.Player)
                    },
                    onOpenArtist = { artistId -> navController.navigate(AuraRoute.artist(artistId)) },
                    onOpenAlbum = { albumId -> navController.navigate(AuraRoute.album(albumId)) },
                )
            }
            composable(AuraRoute.Library) {
                LibraryScreen(
                    repository = repository,
                    refreshToken = permissionRefreshTick,
                    onRequestAudioPermission = requestAudioPermission,
                    onOpenPlaylists = { navController.navigate(AuraRoute.Playlists) },
                    onOpenDownloads = { navController.navigate(AuraRoute.Downloads) },
                    onOpenSettings = { navController.navigate(AuraRoute.Settings) },
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
                    playlistId = backStackEntry.arguments?.getString(AuraRoute.PlaylistIdArg).orEmpty(),
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(AuraRoute.ArtistPattern) { backStackEntry ->
                ArtistRouteScreen(
                    artistId = backStackEntry.arguments?.getString(AuraRoute.ArtistIdArg).orEmpty(),
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(AuraRoute.AlbumPattern) { backStackEntry ->
                AlbumRouteScreen(
                    albumId = backStackEntry.arguments?.getString(AuraRoute.AlbumIdArg).orEmpty(),
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(AuraRoute.Downloads) {
                DownloadsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(AuraRoute.Settings) {
                SettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(AuraRoute.Player) {
                PlayerScreen(
                    preview = currentPreview,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun AuraAppScaffold(
    navController: NavHostController,
    topDestinations: List<TopLevelDestination>,
    currentPreview: PlayerPreview?,
    onMiniPlayerClick: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevelRoute = topDestinations.any { it.route == currentRoute }
    val showBottomBar = currentRoute != AuraRoute.Player
    val showMiniPlayer = currentPreview != null && currentRoute != AuraRoute.Player

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    if (showMiniPlayer) {
                        MiniPlayerCard(
                            preview = currentPreview,
                            onClick = onMiniPlayerClick,
                        )
                    }
                    if (isTopLevelRoute) {
                        NavigationBar {
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
                                )
                            }
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
    preview: PlayerPreview?,
    onClick: () -> Unit,
) {
    if (preview == null) return

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
                    text = preview.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = preview.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
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
    title: String,
    onNavigateBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Text("Back")
                        }
                    }
                },
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
    onPlayTrack: (TrackListRow) -> Unit,
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
                            text = listOfNotNull(track.artistName, track.albumTitle).joinToString(" • "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        IconButton(onClick = { onPlayTrack(track) }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${track.title}")
                        }
                    },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { onOpenArtist("artist:${track.artistName.lowercase().replace(" ", "-")}") }) {
                                Text("Artist")
                            }
                            Button(
                                onClick = {
                                    onOpenAlbum(
                                        "album:${track.artistName.lowercase().replace(" ", "-")}:${track.albumTitle.orEmpty().lowercase().replace(" ", "-")}",
                                    )
                                },
                            ) {
                                Text("Album")
                            }
                        }
                    },
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
        Text(
            text = "Playlists",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (playlists.isEmpty()) {
            Text(
                text = "No playlist persisted yet. This is expected before AND-006.",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            playlists.forEach { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    supportingContent = { Text(if (playlist.isPinned) "Pinned playlist" else "Local playlist") },
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
                text = "Room tracks: ${summary.roomTrackCount} • MediaStore: ${summary.mediaStoreTrackCount} • Playlists: ${summary.playlistCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Recent searches: ${summary.recentSearchCount} • Snapshot active: ${summary.activeSnapshot != null}",
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
