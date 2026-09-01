package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.aura.music.desktop.ui.*
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.RepeatMode
import com.aura.music.ui.screens.SleekSlider
import com.aura.music.ui.theme.*

@Composable
fun DesktopPlayerBar(
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onToggleLike: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by orchestrator.uiState.collectAsState()
    val queueState by orchestrator.queueManager.state.collectAsState()
    val currentTrack = uiState.currentTrack

    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableStateOf(0f) }
    var volume by remember { mutableStateOf(1f) }
    var isMuted by remember { mutableStateOf(false) }
    var previousVolume by remember { mutableStateOf(1f) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp),
        color = OffBlack
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalDivider(color = HairlineDark, thickness = 1.dp)

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. BLOC GAUCHE : Métadonnées de la piste
                Row(
                    modifier = Modifier.width(280.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DesktopArtworkCover(
                        coverUri = currentTrack?.coverUri,
                        size = 52.dp,
                        shapeRadius = 8.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = currentTrack?.title ?: "Aucune lecture",
                            color = PureWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentTrack?.artistName ?: "AURA Player",
                            color = PureWhite.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                currentTrack?.artistName?.let { appState.openArtist("artist:$it") }
                            }
                        )
                    }
                    if (currentTrack != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { onToggleLike(currentTrack.trackId) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (uiState.isCurrentTrackLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (uiState.isCurrentTrackLiked) "Retirer des favoris" else "Ajouter aux favoris",
                                tint = if (uiState.isCurrentTrackLiked) BlazeOrange else PureWhite.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // 2. BLOC CENTRAL : Contrôles & Timeline
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Contrôles principaux
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle
                        IconButton(
                            onClick = { orchestrator.toggleShuffle() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = "Lecture aléatoire",
                                tint = if (queueState.shuffleEnabled) BlazeOrange else PureWhite.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Previous
                        IconButton(
                            onClick = { orchestrator.previous() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Précédent",
                                tint = PureWhite,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Play/Pause
                        val isPlaying = uiState.playbackState == PlaybackState.Playing
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(BlazeOrange)
                                .clickable { orchestrator.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Lecture",
                                tint = PureWhite,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Next
                        IconButton(
                            onClick = { orchestrator.next() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Suivant",
                                tint = PureWhite,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Repeat
                        IconButton(
                            onClick = { orchestrator.toggleRepeat() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            val repeatIcon = if (queueState.repeatMode == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat
                            val repeatTint = if (queueState.repeatMode != RepeatMode.Off) BlazeOrange else PureWhite.copy(alpha = 0.5f)
                            Icon(
                                imageVector = repeatIcon,
                                contentDescription = "Mode de répétition",
                                tint = repeatTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Timeline & Durée
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val currentMs = if (isSeeking) (seekPosition * uiState.durationMs).toLong() else uiState.positionMs
                        Text(
                            text = formatTime(currentMs),
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            modifier = Modifier.width(36.dp)
                        )

                        val sliderValue = if (uiState.durationMs > 0) {
                            if (isSeeking) seekPosition else (uiState.positionMs.toFloat() / uiState.durationMs).coerceIn(0f, 1f)
                        } else 0f

                        SleekSlider(
                            value = sliderValue,
                            onValueChange = {
                                isSeeking = true
                                seekPosition = it
                            },
                            onValueChangeFinished = {
                                isSeeking = false
                                val targetMs = (seekPosition * uiState.durationMs).toLong()
                                orchestrator.seekTo(targetMs)
                            },
                            modifier = Modifier.weight(1f),
                            activeColor = BlazeOrange,
                            inactiveColor = DarkGraphite
                        )

                        Text(
                            text = formatTime(uiState.durationMs),
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            modifier = Modifier.width(36.dp)
                        )
                    }
                }

                // 3. BLOC DROIT : Outils & Volume
                Row(
                    modifier = Modifier.width(240.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    // Bouton Queue Drawer
                    IconButton(
                        onClick = { appState.toggleQueue() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QueueMusic,
                            contentDescription = "File d'attente",
                            tint = if (appState.isQueueOpen) BlazeOrange else PureWhite.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Contrôle du volume
                    IconButton(
                        onClick = {
                            if (isMuted) {
                                isMuted = false
                                volume = previousVolume
                                orchestrator.setVolume(previousVolume)
                            } else {
                                previousVolume = volume
                                isMuted = true
                                volume = 0f
                                orchestrator.setVolume(0f)
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        val volIcon = when {
                            volume == 0f || isMuted -> Icons.Rounded.VolumeOff
                            volume < 0.5f -> Icons.Rounded.VolumeDown
                            else -> Icons.Rounded.VolumeUp
                        }
                        Icon(
                            imageVector = volIcon,
                            contentDescription = "Volume",
                            tint = PureWhite.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    SleekSlider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            isMuted = it == 0f
                            orchestrator.setVolume(it)
                        },
                        modifier = Modifier.width(90.dp),
                        activeColor = PureWhite.copy(alpha = 0.8f),
                        inactiveColor = DarkGraphite
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
