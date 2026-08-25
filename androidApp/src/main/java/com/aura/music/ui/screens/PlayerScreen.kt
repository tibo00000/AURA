package com.aura.music.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.PlayerUiState
import com.aura.music.domain.player.RepeatMode
import com.aura.music.ui.components.rememberAuraFlingBehavior
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.utils.FastTimeFormatter
import com.aura.music.ui.theme.*
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
    var showEditMetadataBottomSheet by remember { mutableStateOf(false) }
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
    val totalQueueCount by remember { derivedStateOf { visiblePriorityQueue.size + uiState.mainQueueTracks.size } }
    val queueLabel by remember { derivedStateOf { if (totalQueueCount > 0) "File ($totalQueueCount)" else "File" } }

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
                    .background(DeepBlack)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Fermer le lecteur",
                        modifier = Modifier.size(32.dp),
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                
                // Segmented control (Lecteur / File d'attente)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(DarkGraphite)
                        .border(1.dp, HairlineDark, RoundedCornerShape(999.dp))
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (!showQueue) BlazeOrange else Color.Transparent)
                            .clickable { showQueue = false }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Lecteur",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (!showQueue) DeepBlack else TextSecondary,
                            fontWeight = if (!showQueue) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (showQueue) BlazeOrange else Color.Transparent)
                            .clickable { showQueue = true }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = queueLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (showQueue) DeepBlack else TextSecondary,
                            fontWeight = if (showQueue) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Options supplémentaires",
                            tint = TextPrimary
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier
                            .background(ElevatedGraphite)
                            .border(1.dp, HairlineDark, RoundedCornerShape(12.dp))
                    ) {
                        // Ajouter/Retirer aux favoris
                        DropdownMenuItem(
                            text = { Text(if (uiState.isCurrentTrackLiked) "Retirer des favoris" else "Ajouter aux favoris", color = TextPrimary) },
                            onClick = {
                                playerViewModel.onEvent(PlayerEvent.ToggleLike)
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (uiState.isCurrentTrackLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (uiState.isCurrentTrackLiked) BlazeOrange else TextSecondary
                                )
                            }
                        )
                        // Ajouter à une playlist
                        DropdownMenuItem(
                            text = { Text("Ajouter à une playlist", color = TextPrimary) },
                            onClick = {
                                showSelectPlaylistDialog = true
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, tint = BlazeOrange)
                            }
                        )
                        // Voir l'artiste
                        DropdownMenuItem(
                            text = { Text("Voir l'artiste", color = if (artistId != null) TextPrimary else TextMuted) },
                            onClick = {
                                artistId?.let { onOpenArtist(it) }
                                menuExpanded = false
                            },
                            enabled = artistId != null,
                            leadingIcon = {
                                Icon(Icons.Rounded.Person, contentDescription = null, tint = if (artistId != null) TextPrimary else TextMuted)
                            }
                        )
                        // Voir l'album
                        DropdownMenuItem(
                            text = { Text("Voir l'album", color = if (albumId != null) TextPrimary else TextMuted) },
                            onClick = {
                                albumId?.let { onOpenAlbum(it) }
                                menuExpanded = false
                            },
                            enabled = albumId != null,
                            leadingIcon = {
                                Icon(Icons.Rounded.Album, contentDescription = null, tint = if (albumId != null) TextPrimary else TextMuted)
                            }
                        )
                        // Modifier les informations
                        if (trackDetails != null) {
                            DropdownMenuItem(
                                text = { Text("Modifier les informations", color = TextPrimary) },
                                onClick = {
                                    menuExpanded = false
                                    showEditMetadataBottomSheet = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Edit, contentDescription = null, tint = TextPrimary)
                                }
                            )
                        }
                    }
                }
            }
        },
        containerColor = DeepBlack
    ) { innerPadding ->
        if (track == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aucune lecture active",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (!showQueue) {
                    // =========================================================
                    // VUE LECTEUR PLEIN ÉCRAN
                    // =========================================================
                    // Large Artwork
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 8.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(DarkGraphite)
                            .border(1.dp, HairlineDark, RoundedCornerShape(20.dp)),
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
                                tint = TextMuted,
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
                            color = TextPrimary
                        )
                        Text(
                            text = track.artistName,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val albumTitle = track.albumTitle
                        if (!albumTitle.isNullOrEmpty()) {
                            Text(
                                text = albumTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
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
                                tint = if (uiState.shuffleEnabled) BlazeOrange else TextSecondary,
                            )
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Previous) }) {
                            Icon(
                                Icons.Rounded.SkipPrevious,
                                contentDescription = "Piste précédente",
                                modifier = Modifier.size(44.dp),
                                tint = TextPrimary
                            )
                        }
                        IconButton(
                            onClick = { playerViewModel.onEvent(PlayerEvent.TogglePlayPause) },
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(BlazeOrange)
                        ) {
                            Icon(
                                imageVector = if (uiState.playbackState == PlaybackState.Playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Lecture ou pause",
                                modifier = Modifier.size(38.dp),
                                tint = DeepBlack
                            )
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.Next) }) {
                            Icon(
                                Icons.Rounded.SkipNext,
                                contentDescription = "Piste suivante",
                                modifier = Modifier.size(44.dp),
                                tint = TextPrimary
                            )
                        }
                        IconButton(onClick = { playerViewModel.onEvent(PlayerEvent.CycleRepeatMode) }) {
                            Icon(
                                imageVector = if (uiState.repeatMode == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                contentDescription = "Mode répétition",
                                tint = if (uiState.repeatMode == RepeatMode.Off) TextSecondary else BlazeOrange,
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
                                tint = if (uiState.isCurrentTrackLiked) BlazeOrange else TextSecondary,
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
                                tint = TextSecondary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Single unified Queue access card with context & track count
                    PlayerQueueAccessCard(
                        uiState = uiState,
                        totalQueueCount = totalQueueCount,
                        onClick = { showQueue = true },
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                } else {
                    // =========================================================
                    // VUE FILE D'ATTENTE MODERNE (Scrollable, full height)
                    // =========================================================
                    val queueFlingBehavior = rememberAuraFlingBehavior(
                        maxVelocity = 3500f,
                        frictionMultiplier = 0.85f
                    )

                    LazyColumn(
                        state = reorderState.listState,
                        flingBehavior = queueFlingBehavior,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DeepBlack)
                            .reorderable(reorderState),
                        contentPadding = PaddingValues(bottom = 36.dp, top = 4.dp)
                    ) {
                        // 1. CARTE EN COURS DE LECTURE
                        item(key = "now_playing_card", contentType = "header") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(ElevatedGraphite)
                                    .border(1.dp, HairlineDark, RoundedCornerShape(16.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(BlazeOrange)
                                    )
                                    Text(
                                        text = "EN COURS DE LECTURE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = BlazeOrange,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val cover = track.coverUri
                                    if (cover != null) {
                                        AsyncImage(
                                            model = cover,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(DarkGraphite),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.MusicNote,
                                                contentDescription = null,
                                                tint = TextMuted,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = track.artistName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = { playerViewModel.onEvent(PlayerEvent.TogglePlayPause) },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(BlazeOrange)
                                    ) {
                                        Icon(
                                            imageVector = if (uiState.playbackState == PlaybackState.Playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = "Lecture / Pause",
                                            tint = DeepBlack,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 2. FILE D'ATTENTE PRIORITAIRE (Ajouts manuels)
                        item(key = "pq_header", contentType = "header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "File d'attente prioritaire",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    if (visiblePriorityQueue.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(BlazeOrange.copy(alpha = 0.2f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "${visiblePriorityQueue.size}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = BlazeOrange
                                            )
                                        }
                                    }
                                }

                                if (visiblePriorityQueue.isNotEmpty()) {
                                    Text(
                                        text = "Tout effacer",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMuted,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                playerViewModel.onEvent(PlayerEvent.ClearPriorityQueue)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            if (visiblePriorityQueue.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(DarkGraphite.copy(alpha = 0.5f))
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = "Aucun titre ajouté manuellement.\nUtilisez « Lire ensuite » dans le menu d'un morceau.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
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

                        // 3. MAIN UPCOMING QUEUE HEADER (File contextuelle / Album / Playlist)
                        if (uiState.mainQueueTracks.isNotEmpty()) {
                            item(key = "mq_header", contentType = "header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "À suivre",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(ElevatedGraphite)
                                                    .border(1.dp, HairlineDark, RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "${uiState.mainQueueTracks.size}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextSecondary
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Depuis : ${describeContext(uiState)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                    }
                                }
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
                                    color = TextMuted,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
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

    if (showEditMetadataBottomSheet && trackDetails != null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val appContainer = remember(context) { (context.applicationContext as com.aura.music.AuraApplication).container }
        EditTrackMetadataBottomSheet(
            track = trackDetails,
            apiService = appContainer.auraApiService,
            localLibraryRepository = appContainer.localLibraryRepository,
            onDismiss = { showEditMetadataBottomSheet = false },
            onTrackUpdated = {
                showEditMetadataBottomSheet = false
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
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 12.dp else 0.dp,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "pq_elevation"
    )
    val itemShape = RoundedCornerShape(14.dp)
    val bgColor = if (isDragging) ElevatedGraphite else Color.Transparent
    val borderStroke = if (isDragging) BlazeOrange else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .graphicsLayer {
                shadowElevation = elevation.toPx()
                shape = itemShape
                clip = false
            }
            .clip(itemShape)
            .background(bgColor)
            .border(if (isDragging) 1.dp else 0.dp, borderStroke, itemShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val cover = queuedTrack.coverUri
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkGraphite),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = queuedTrack.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = queuedTrack.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Retirer",
                modifier = Modifier.size(18.dp),
                tint = TextMuted
            )
        }

        Box(
            modifier = dragModifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "Réorganiser",
                tint = if (isDragging) BlazeOrange else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
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
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 12.dp else 0.dp,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "mq_elevation"
    )
    val itemShape = RoundedCornerShape(14.dp)
    val bgColor = if (isDragging) ElevatedGraphite else Color.Transparent
    val borderStroke = if (isDragging) BlazeOrange else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .graphicsLayer {
                shadowElevation = elevation.toPx()
                shape = itemShape
                clip = false
            }
            .clip(itemShape)
            .background(bgColor)
            .border(if (isDragging) 1.dp else 0.dp, borderStroke, itemShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val cover = queuedTrack.coverUri
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkGraphite),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = queuedTrack.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = queuedTrack.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Retirer de la suite",
                modifier = Modifier.size(18.dp),
                tint = TextMuted
            )
        }

        Box(
            modifier = dragModifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "Réorganiser",
                tint = if (isDragging) BlazeOrange else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PlayerQueueAccessCard(
    uiState: PlayerUiState,
    totalQueueCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ElevatedGraphite)
            .border(1.dp, HairlineDark, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.QueueMusic,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = BlazeOrange
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "File d'attente ($totalQueueCount)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Depuis : ${describeContext(uiState)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = "Afficher la file d'attente",
            tint = TextMuted,
            modifier = Modifier.size(20.dp)
        )
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
        SleekSlider(
            value = sliderValue.coerceIn(0f, uiState.durationMs.toFloat().coerceAtLeast(0f)),
            onValueChange = { seekDraft = it },
            onValueChangeFinished = {
                val finalValue = seekDraft?.toLong() ?: uiState.positionMs
                playerViewModel.onEvent(PlayerEvent.SeekTo(finalValue))
                seekDraft = null
            },
            valueRange = 0f..uiState.durationMs.toFloat().coerceAtLeast(1f),
            activeColor = BlazeOrange,
            inactiveColor = HairlineDark
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = FastTimeFormatter.formatDuration((seekDraft ?: uiState.positionMs.toFloat()).toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = FastTimeFormatter.formatDuration(uiState.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
    }
}
