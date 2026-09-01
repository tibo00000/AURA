package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
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
                val totalCount = (if (currentTrack != null) 1 else 0) + queueState.priorityQueue.size + (queueState.contextTracks.size - queueState.currentIndex - 1).coerceAtLeast(0)
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Vider", color = BlazeOrange, fontSize = 12.sp)
                    }
                }
                IconButton(
                    onClick = { appState.isQueueOpen = false },
                    modifier = Modifier.size(28.dp)
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
                                    text = currentTrack.displayArtist,
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
                    QueueTrackItem(
                        track = track,
                        onPlayNow = { orchestrator.playTrackDirectly(track) },
                        onRemove = { orchestrator.queueManager.removeTrack(track.internalId) }
                    )
                }
            }

            // 3. SUITE DE LECTURE (CONTEXTE)
            val upcomingContextTracks = if (queueState.currentIndex + 1 < queueState.contextTracks.size) {
                queueState.contextTracks.subList(queueState.currentIndex + 1, queueState.contextTracks.size)
            } else emptyList()

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
                    QueueTrackItem(
                        track = track,
                        onPlayNow = {
                            orchestrator.playTrack(
                                trackId = track.trackId,
                                contextType = queueState.contextType ?: "all",
                                contextId = queueState.contextId ?: "all",
                                contextTracks = queueState.contextTracks,
                                startIndex = queueState.currentIndex + 1 + index
                            )
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
    onPlayNow: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHovered) DarkGraphite.copy(alpha = 0.7f) else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onPlayNow)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                text = track.displayArtist,
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onRemove != null && isHovered) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
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
