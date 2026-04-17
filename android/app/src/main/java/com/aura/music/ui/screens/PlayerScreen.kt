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
import coil.compose.AsyncImage
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
) {
    val uiState by playerViewModel.uiState.collectAsState()
    val track = uiState.currentTrack
    var seekDraft by remember(track?.trackId, uiState.durationMs) { mutableStateOf<Float?>(null) }
    val sliderValue = seekDraft ?: uiState.positionMs.toFloat()

    // Local state for smooth Reordering
    val localPriorityQueue = remember { mutableStateOf(uiState.priorityQueue) }
    LaunchedEffect(uiState.priorityQueue) {
        localPriorityQueue.value = uiState.priorityQueue
    }
    val localMainQueue = remember { mutableStateOf(uiState.mainQueueTracks) }
    LaunchedEffect(uiState.mainQueueTracks) {
        localMainQueue.value = uiState.mainQueueTracks
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
                IconButton(onClick = { /* Optional Context Menu */ }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Options supplémentaires")
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
            LazyColumn(
                state = reorderState.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .reorderable(reorderState),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Large Artwork
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 12.dp)
                            .aspectRatio(1f) // Square
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF1A1A1A)))),
                        contentAlignment = Alignment.Center
                    ) {
                        if (track.coverUri != null) {
                            AsyncImage(
                                model = track.coverUri,
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
                }

                // Track Meta
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
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
                        if (!track.albumTitle.isNullOrEmpty()) {
                            Text(
                                text = track.albumTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Progress Block
                item {
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

                // Transport Controls
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                }

                // Secondary Actions
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 12.dp),
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
                        // Bouton de test pour ajouter a la file d'attente utilisateur !
                        IconButton(
                            onClick = { playerViewModel.onEvent(PlayerEvent.AddToQueue(track)) }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistAdd,
                                contentDescription = "Ajouter à la Queue (Test)",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }

                // Context
                item {
                    Box(modifier = Modifier.padding(vertical = 12.dp)) {
                        SourceContextCard(uiState)
                    }
                }

                // PRIORITY QUEUE HEADER
                item {
                    Text(
                        text = "File d'attente prioritaire (${localPriorityQueue.value.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    if (localPriorityQueue.value.isEmpty()) {
                        Text(
                            text = "Aucune piste ajoutée manuellement.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }

                // PRIORITY QUEUE ITEMS (Reorderable)
                itemsIndexed(localPriorityQueue.value, key = { _, it -> "pq_${it.internalId}" }) { index, queuedTrack ->
                    ReorderableItem(reorderState, key = "pq_${queuedTrack.internalId}") { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                        val bgColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation)
                                .background(bgColor)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { playerViewModel.onEvent(PlayerEvent.RemoveFromQueue(index)) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Retirer", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(queuedTrack.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(queuedTrack.artistName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = "Réorganiser",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(40.dp)
                                    .detectReorderAfterLongPress(reorderState)
                                    .padding(8.dp)
                            )
                        }
                    }
                }

                // MAIN UPCOMING QUEUE HEADER
                if (uiState.mainQueueTracks.isNotEmpty()) {
                    item {
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
                itemsIndexed(localMainQueue.value.take(30), key = { _, it -> "mq_${it.internalId}" }) { index, queuedTrack ->
                    ReorderableItem(reorderState, key = "mq_${queuedTrack.internalId}") { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                        val bgColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation)
                                .background(bgColor)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { playerViewModel.onEvent(PlayerEvent.RemoveFromMainQueue(queuedTrack.internalId)) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Retirer de la suite", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            if (queuedTrack.coverUri != null) {
                                AsyncImage(
                                    model = queuedTrack.coverUri,
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
                                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(queuedTrack.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(queuedTrack.artistName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = "Réorganiser",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .size(40.dp)
                                    .detectReorderAfterLongPress(reorderState)
                                    .padding(8.dp)
                            )
                        }
                    }
                }
                
                if (uiState.mainQueueTracks.size > 30) {
                    item {
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
