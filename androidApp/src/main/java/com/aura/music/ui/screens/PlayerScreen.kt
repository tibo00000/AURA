package com.aura.music.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.PlayerUiState
import com.aura.music.domain.player.RepeatMode
import com.aura.music.ui.player.PlayerViewModel
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@Composable
fun PlayerScreen(
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val uiState by playerViewModel.staticUiState.collectAsState()
    val track = uiState.currentTrack

    // State for context menu and playlists
    var menuExpanded by remember { mutableStateOf(false) }
    var showSelectPlaylistDialog by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    val trackDetailsState = produceState<com.aura.music.data.local.TrackListRow?>(initialValue = null, track?.trackId) {
        value = track?.trackId?.let { playerViewModel.getTrackById(it) }
    }
    val trackDetails = trackDetailsState.value
    val artistId = trackDetails?.artistId
    val albumId = trackDetails?.albumId

    val playlistsState = produceState<List<com.aura.music.data.local.PlaylistListRow>>(initialValue = emptyList(), playerViewModel) {
        value = playerViewModel.getPlaylists()
    }
    val playlists = playlistsState.value

    // Local state for smooth Reordering
    val localPriorityQueue = remember { mutableStateOf(uiState.priorityQueue) }
    LaunchedEffect(uiState.priorityQueue) {
        localPriorityQueue.value = uiState.priorityQueue
    }
    val localMainQueue = remember { mutableStateOf(uiState.mainQueueTracks) }
    LaunchedEffect(uiState.mainQueueTracks) {
        localMainQueue.value = uiState.mainQueueTracks
    }

    val visiblePriorityQueue by remember { derivedStateOf { localPriorityQueue.value } }
    val visibleMainQueue by remember { derivedStateOf { localMainQueue.value.take(30) } }

    val onRemoveFromQueue = remember(playerViewModel) {
        { index: Int ->
            playerViewModel.onEvent(PlayerEvent.RemoveFromQueue(index))
        }
    }
    val onRemoveFromMainQueue = remember(playerViewModel) {
        { trackId: String ->
            playerViewModel.onEvent(PlayerEvent.RemoveFromMainQueue(trackId))
        }
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            if (from.key.toString().startsWith("pq_") && to.key.toString().startsWith("pq_")) {
                val list = localPriorityQueue.value.toMutableList()
                val fromIdx = list.indexOfFirst { "pq_${it.internalId}" == from.key }
                val toIdx = list.indexOfFirst { "pq_${it.internalId}" == to.key }
                if (fromIdx != -1 && toIdx != -1) {
                    list.add(toIdx, list.removeAt(fromIdx))
                    localPriorityQueue.value = list
                    playerViewModel.onEvent(PlayerEvent.ReorderQueue(fromIdx, toIdx))
                }
            } else if (from.key.toString().startsWith("mq_") && to.key.toString().startsWith("mq_")) {
                val list = localMainQueue.value.toMutableList()
                val fromIdx = list.indexOfFirst { "mq_${it.internalId}" == from.key }
                val toIdx = list.indexOfFirst { "mq_${it.internalId}" == to.key }
                if (fromIdx != -1 && toIdx != -1) {
                    list.add(toIdx, list.removeAt(fromIdx))
                    localMainQueue.value = list
                    val fromId = from.key.toString().removePrefix("mq_")
                    val toId = to.key.toString().removePrefix("mq_")
                    playerViewModel.onEvent(PlayerEvent.ReorderMainQueue(fromId, toId))
                }
            }
        },
        canDragOver = { draggedOver, dragging ->
            if (dragging.key?.toString()?.startsWith("pq_") == true) {
                draggedOver.key?.toString()?.startsWith("pq_") == true
            } else if (dragging.key?.toString()?.startsWith("mq_") == true) {
                draggedOver.key?.toString()?.startsWith("mq_") == true
            } else false
        }
    )

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Fermer le lecteur",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                
                // Segmented control (Lecteur / File d'attente)
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (showQueue) Color.Transparent else MaterialTheme.colorScheme.primary)
                            .clickable { showQueue = false }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Lecteur",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (showQueue) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (showQueue) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { showQueue = true }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "File",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (showQueue) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options supplémentaires")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        // Ajouter/Retirer aux favoris
                        DropdownMenuItem(
                            text = { Text(if (uiState.isCurrentTrackLiked) "Retirer des favoris" else "Ajouter aux favoris") },
                            onClick = {
                                playerViewModel.onEvent(PlayerEvent.ToggleLike)
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (uiState.isCurrentTrackLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = null
                                )
                            }
                        )
                        // Ajouter à une playlist
                        DropdownMenuItem(
                            text = { Text("Ajouter à une playlist") },
                            onClick = {
                                showSelectPlaylistDialog = true
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.PlaylistAdd, contentDescription = null)
                            }
                        )
                        // Voir l'artiste
                        DropdownMenuItem(
                            text = { Text("Voir l'artiste") },
                            onClick = {
                                artistId?.let { onOpenArtist(it) }
                                menuExpanded = false
                            },
                            enabled = artistId != null,
                            leadingIcon = {
                                Icon(Icons.Rounded.Person, contentDescription = null)
                            }
                        )
                        // Voir l'album
                        DropdownMenuItem(
                            text = { Text("Voir l'album") },
                            onClick = {
                                albumId?.let { onOpenAlbum(it) }
                                menuExpanded = false
                            },
                            enabled = albumId != null,
                            leadingIcon = {
                                Icon(Icons.Rounded.Album, contentDescription = null)
                            }
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (track == null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(
                    text = "Aucune lecture active",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (!showQueue) {
                    // Large Artwork
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 8.dp)
                            .aspectRatio(1f) // Square
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF1A1A1A)))),
                        contentAlignment = Alignment.Center
                    ) {
                        val cover = track.coverUri
                        if (cover != null) {
                            AsyncImage(
                                model = cover,
                                contentDescription = "Pochette de ${track.title}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(80.dp)
                            )
                        }
                    }

                    // Track Meta
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = track.artistName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val albumTitle = track.albumTitle
                        if (!albumTitle.isNullOrEmpty()) {
                            Text(
                                text = albumTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Progress Block
                    PlaybackProgressBlock(
                        playerViewModel = playerViewModel,
                        trackId = track.trackId
                    )

                    // Transport Controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.ToggleShuffle) }) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = "Lecture aléatoire",
                                tint = if (uiState.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Previous) }) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Piste précédente", modifier = Modifier.size(48.dp))
                        }
                        IconButton(
                            onClick = { playerViewModel.onEvent(PlayerEvent.TogglePlayPause) },
                            modifier = Modifier
                                .size(72.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (uiState.playbackState == PlaybackState.Playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Lecture ou pause",
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Next) }) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Piste suivante", modifier = Modifier.size(48.dp))
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.CycleRepeatMode) }) {
                            Icon(
                                imageVector = if (uiState.repeatMode == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                contentDescription = "Mode répétition",
                                tint = if (uiState.repeatMode == RepeatMode.Off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    // Secondary Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.ToggleLike) }) {
                            Icon(
                                imageVector = if (uiState.isCurrentTrackLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (uiState.isCurrentTrackLiked) "Retirer des favoris" else "Ajouter aux favoris",
                                tint = if (uiState.isCurrentTrackLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        // Ajouter à une playlist
                        IconButton(
                            onClick = { showSelectPlaylistDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistAdd,
                                contentDescription = "Ajouter à une playlist",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    // Context
                    SourceContextCard(uiState)

                    Spacer(modifier = Modifier.weight(1f))

                    // Button to open the Queue directly
                    Button(
                        onClick = { showQueue = true },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Voir la file d'attente (${visiblePriorityQueue.size + uiState.mainQueueTracks.size} titres)",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else {
                    // Dynamic Queue (Scrollable, full height)
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .reorderable(reorderState),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        // PRIORITY QUEUE HEADER
                        item(key = "pq_header", contentType = "header") {
                            Text(
                                text = "File d'attente prioritaire (${visiblePriorityQueue.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                            if (visiblePriorityQueue.isEmpty()) {
                                Text(
                                    text = "Aucune piste ajoutée manuellement.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                )
                            }
                        }

                        // PRIORITY QUEUE ITEMS (Reorderable)
                        itemsIndexed(
                            items = visiblePriorityQueue,
                            key = { _, it -> "pq_${it.internalId}" },
                            contentType = { _, _ -> "priority_item" }
                        ) { index, queuedTrack ->
                            ReorderableItem(reorderState, key = "pq_${queuedTrack.internalId}") { isDragging ->
                                PriorityQueueItemRow(
                                    queuedTrack = queuedTrack,
                                    isDragging = isDragging,
                                    onRemove = { onRemoveFromQueue(index) },
                                    dragModifier = Modifier.detectReorderAfterLongPress(reorderState)
                                )
                            }
                        }

                        // MAIN UPCOMING QUEUE HEADER
                        if (uiState.mainQueueTracks.isNotEmpty()) {
                            item(key = "mq_header", contentType = "header") {
                                Text(
                                    text = "À suivre (${uiState.mainQueueTracks.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                )
                            }
                        }

                        // MAIN UPCOMING QUEUE ITEMS (Reorderable natively)
                        itemsIndexed(
                            items = visibleMainQueue,
                            key = { _, it -> "mq_${it.internalId}" },
                            contentType = { _, _ -> "main_item" }
                        ) { index, queuedTrack ->
                            ReorderableItem(reorderState, key = "mq_${queuedTrack.internalId}") { isDragging ->
                                MainQueueItemRow(
                                    queuedTrack = queuedTrack,
                                    isDragging = isDragging,
                                    onRemove = { onRemoveFromMainQueue(queuedTrack.internalId) },
                                    dragModifier = Modifier.detectReorderAfterLongPress(reorderState)
                                )
                            }
                        }

                        if (uiState.mainQueueTracks.size > 30) {
                            item(key = "mq_footer", contentType = "footer") {
                                Text(
                                    text = "Et ${uiState.mainQueueTracks.size - 30} autres pistes...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSelectPlaylistDialog && track != null) {
        SelectPlaylistDialog(
            playlists = playlists,
            onDismiss = { showSelectPlaylistDialog = false },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTrackToPlaylist(playlist.id, track.trackId)
                showSelectPlaylistDialog = false
            }
        )
    }
}

@Composable
private fun PriorityQueueItemRow(
    queuedTrack: com.aura.music.domain.player.QueuedTrack,
    isDragging: Boolean,
    onRemove: () -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier
) {
    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
    val bgColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation)
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Retirer",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val cover = queuedTrack.coverUri
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = queuedTrack.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = queuedTrack.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = "Réorganiser",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = dragModifier
                .size(40.dp)
                .padding(8.dp)
        )
    }
}

@Composable
private fun MainQueueItemRow(
    queuedTrack: com.aura.music.domain.player.QueuedTrack,
    isDragging: Boolean,
    onRemove: () -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier
) {
    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
    val bgColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation)
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Retirer de la suite",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val cover = queuedTrack.coverUri
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = queuedTrack.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = queuedTrack.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = "Réorganiser",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = dragModifier
                .size(40.dp)
                .padding(8.dp)
        )
    }
}

@Composable
private fun SourceContextCard(uiState: PlayerUiState) {
    if (uiState.contextType != "recent_tracks") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Depuis : ${describeContext(uiState)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun describeContext(uiState: PlayerUiState): String = when (uiState.contextType) {
    "playlist" -> "Une playlist locale"
    "album" -> "Un album"
    "artist" -> "Un artiste"
    "search_results" -> "Les résultats de recherche"
    "recent_tracks" -> "Les pistes récentes"
    else -> "Lecture directe"
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun PlaybackProgressBlock(
    playerViewModel: PlayerViewModel,
    trackId: String?,
) {
    val uiState by playerViewModel.uiState.collectAsState()
    var seekDraft by remember(trackId, uiState.durationMs) { mutableStateOf<Float?>(null) }
    val sliderValue = seekDraft ?: uiState.positionMs.toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Slider(
            value = sliderValue.coerceIn(0f, uiState.durationMs.toFloat().coerceAtLeast(0f)),
            onValueChange = { seekDraft = it },
            onValueChangeFinished = {
                val finalValue = seekDraft?.toLong() ?: uiState.positionMs
                playerViewModel.onEvent(PlayerEvent.SeekTo(finalValue))
                seekDraft = null
            },
            valueRange = 0f..uiState.durationMs.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration((seekDraft ?: uiState.positionMs.toFloat()).toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDuration(uiState.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
