package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.ui.theme.*

@Composable
fun DesktopQueuePanel(
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val queueState by orchestrator.queueManager.state.collectAsState()
    val uiState by orchestrator.uiState.collectAsState()
    val currentTrack = uiState.currentTrack
    val upcomingContextTracks = orchestrator.queueManager.getUpcomingContextTracks()

    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragAccumulatedY by remember { mutableFloatStateOf(0f) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 50.dp.toPx() }

    Column(
        modifier = modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(OffBlack)
            .padding(16.dp)
    ) {
        // En-tête de la file d'attente
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "File d'attente",
                    color = PureWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                val totalCount = (if (currentTrack != null) 1 else 0) + queueState.priorityQueue.size + upcomingContextTracks.size
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "($totalCount)",
                    color = PureWhite.copy(alpha = 0.4f),
                    fontSize = 13.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (queueState.priorityQueue.isNotEmpty()) {
                    TextButton(
                        onClick = { orchestrator.queueManager.clearPriorityQueue() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.handCursor()
                    ) {
                        Text("Vider", color = BlazeOrange, fontSize = 12.sp)
                    }
                }
                IconButton(
                    onClick = { appState.isQueueOpen = false },
                    modifier = Modifier.size(28.dp).handCursor()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Fermer la file",
                        tint = PureWhite.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. EN COURS DE LECTURE
            if (currentTrack != null) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "EN COURS",
                            color = BlazeOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkGraphite)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DesktopArtworkCover(
                                coverUri = currentTrack.coverUri,
                                size = 48.dp,
                                shapeRadius = 6.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentTrack.title,
                                    color = PureWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = currentTrack.artistName,
                                    color = PureWhite.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = BlazeOrange,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // 2. FILE PRIORITAIRE
            if (queueState.priorityQueue.isNotEmpty()) {
                item {
                    Text(
                        text = "FILE PRIORITAIRE",
                        color = PureWhite.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                itemsIndexed(queueState.priorityQueue, key = { _, t -> "prio_${t.internalId}" }) { index, track ->
                    val itemKey = "prio_${track.internalId}"
                    val isDragging = draggingKey == itemKey
                    QueueTrackItem(
                        track = track,
                        itemKey = itemKey,
                        isDragging = isDragging,
                        dragOffsetY = if (isDragging) dragAccumulatedY else 0f,
                        onDragStart = {
                            draggingKey = itemKey
                            draggedIndex = index
                            dragAccumulatedY = 0f
                        },
                        onDragDelta = { deltaY ->
                            dragAccumulatedY += deltaY
                            val threshold = itemHeightPx * 0.5f
                            if (dragAccumulatedY > threshold && draggedIndex < queueState.priorityQueue.size - 1) {
                                val targetIndex = draggedIndex + 1
                                orchestrator.queueManager.reorderQueue(draggedIndex, targetIndex)
                                draggedIndex = targetIndex
                                dragAccumulatedY -= itemHeightPx
                            } else if (dragAccumulatedY < -threshold && draggedIndex > 0) {
                                val targetIndex = draggedIndex - 1
                                orchestrator.queueManager.reorderQueue(draggedIndex, targetIndex)
                                draggedIndex = targetIndex
                                dragAccumulatedY += itemHeightPx
                            }
                        },
                        onDragEnd = {
                            draggingKey = null
                            draggedIndex = -1
                            dragAccumulatedY = 0f
                        },
                        onPlayNow = { orchestrator.playTrackDirectly(track) },
                        onMoveUp = if (index > 0) { { orchestrator.queueManager.reorderQueue(index, index - 1) } } else null,
                        onMoveDown = if (index < queueState.priorityQueue.size - 1) { { orchestrator.queueManager.reorderQueue(index, index + 1) } } else null,
                        onRemove = { orchestrator.queueManager.removeFromQueue(index) }
                    )
                }
            }

            // 3. SUITE DE LECTURE (CONTEXTE)
            if (upcomingContextTracks.isNotEmpty()) {
                item {
                    Text(
                        text = "SUITE DE LA LECTURE",
                        color = PureWhite.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                itemsIndexed(upcomingContextTracks, key = { _, t -> "ctx_${t.internalId}" }) { index, track ->
                    val itemKey = "ctx_${track.internalId}"
                    val isDragging = draggingKey == itemKey
                    QueueTrackItem(
                        track = track,
                        itemKey = itemKey,
                        isDragging = isDragging,
                        dragOffsetY = if (isDragging) dragAccumulatedY else 0f,
                        onDragStart = {
                            draggingKey = itemKey
                            draggedIndex = index
                            dragAccumulatedY = 0f
                        },
                        onDragDelta = { deltaY ->
                            dragAccumulatedY += deltaY
                            val threshold = itemHeightPx * 0.5f
                            if (dragAccumulatedY > threshold && draggedIndex < upcomingContextTracks.size - 1) {
                                val currentT = upcomingContextTracks[draggedIndex]
                                val targetT = upcomingContextTracks[draggedIndex + 1]
                                orchestrator.queueManager.reorderUpcomingContextTrack(currentT.internalId, targetT.internalId)
                                draggedIndex = draggedIndex + 1
                                dragAccumulatedY -= itemHeightPx
                            } else if (dragAccumulatedY < -threshold && draggedIndex > 0) {
                                val currentT = upcomingContextTracks[draggedIndex]
                                val targetT = upcomingContextTracks[draggedIndex - 1]
                                orchestrator.queueManager.reorderUpcomingContextTrack(currentT.internalId, targetT.internalId)
                                draggedIndex = draggedIndex - 1
                                dragAccumulatedY += itemHeightPx
                            }
                        },
                        onDragEnd = {
                            draggingKey = null
                            draggedIndex = -1
                            dragAccumulatedY = 0f
                        },
                        onPlayNow = {
                            val ctx = queueState.context
                            if (ctx != null) {
                                val idx = ctx.tracks.indexOfFirst { it.internalId == track.internalId }.coerceAtLeast(0)
                                orchestrator.playTrack(
                                    trackId = track.trackId,
                                    contextType = ctx.type,
                                    contextId = ctx.id,
                                    contextTracks = ctx.tracks,
                                    startIndex = idx
                                )
                            }
                        },
                        onRemove = {
                            orchestrator.queueManager.removeUpcomingContextTrack(track.internalId)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTrackItem(
    track: QueuedTrack,
    itemKey: String,
    isDragging: Boolean = false,
    dragOffsetY: Float = 0f,
    onDragStart: () -> Unit = {},
    onDragDelta: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onPlayNow: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY
                shadowElevation = if (isDragging) 8f else 0f
            }
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isDragging) DarkGraphite
                else Color.Transparent
            )
            .then(
                if (isDragging) Modifier.border(1.dp, BlazeOrange.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                else Modifier
            )
            .hoverable(interactionSource)
            .handCursor()
            .clickable(onClick = onPlayNow)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Traits de drag (Drag Handle) interactif
        Box(
            modifier = Modifier
                .size(24.dp)
                .handCursor()
                .pointerInput(itemKey) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount.y)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "Glisser pour réordonner",
                tint = if (isDragging) BlazeOrange else if (isHovered) PureWhite.copy(alpha = 0.8f) else PureWhite.copy(alpha = 0.25f),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))

        DesktopArtworkCover(
            coverUri = track.coverUri,
            size = 36.dp,
            shapeRadius = 4.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = PureWhite,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artistName,
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Actions au survol (monter/descendre si prioritaire, supprimer)
        if (isHovered && !isDragging) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onMoveUp != null) {
                    IconButton(
                        onClick = onMoveUp,
                        modifier = Modifier.size(22.dp).handCursor()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = "Monter",
                            tint = PureWhite.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (onMoveDown != null) {
                    IconButton(
                        onClick = onMoveDown,
                        modifier = Modifier.size(22.dp).handCursor()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Descendre",
                            tint = PureWhite.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (onRemove != null) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(22.dp).handCursor()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Retirer",
                            tint = PureWhite.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
