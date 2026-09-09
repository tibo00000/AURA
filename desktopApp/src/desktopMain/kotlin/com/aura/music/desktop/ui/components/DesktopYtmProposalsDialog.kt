package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aura.music.data.network.YtmCandidateDto
import com.aura.music.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DesktopYtmProposalsDialog(
    trackTitle: String?,
    candidates: List<YtmCandidateDto>,
    onSelectCandidate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(520.dp)
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = OffBlack)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Choisir la version audio",
                    color = PureWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                if (!trackTitle.isNullOrBlank()) {
                    Text(
                        text = trackTitle,
                        color = BlazeOrange,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Text(
                    text = "Plusieurs correspondances YouTube Music ont été trouvées. Sélectionnez celle qui correspond à ce morceau :",
                    color = PureWhite.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )

                if (candidates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Aucune proposition trouvée.",
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(candidates) { candidate ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectCandidate(candidate.videoId) },
                                color = DarkGraphite,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    DesktopArtworkCover(
                                        coverUri = candidate.coverUri,
                                        size = 48.dp,
                                        shapeRadius = 6.dp
                                    )

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = candidate.title,
                                            color = PureWhite,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            if (!candidate.artist.isNullOrBlank()) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Person,
                                                        contentDescription = null,
                                                        tint = PureWhite.copy(alpha = 0.5f),
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Text(
                                                        text = candidate.artist,
                                                        color = PureWhite.copy(alpha = 0.7f),
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            if (!candidate.duration.isNullOrBlank()) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.AccessTime,
                                                        contentDescription = null,
                                                        tint = PureWhite.copy(alpha = 0.5f),
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Text(
                                                        text = candidate.duration,
                                                        color = PureWhite.copy(alpha = 0.5f),
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Annuler", color = PureWhite.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopChangeAudioModals(
    appState: com.aura.music.desktop.state.DesktopAppState,
    orchestrator: com.aura.music.desktop.DesktopPlaybackOrchestrator
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    if (appState.isChangeAudioLoading) {
        Dialog(onDismissRequest = {}) {
            Card(
                modifier = Modifier
                    .width(360.dp)
                    .clip(RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        color = BlazeOrange,
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = "Recherche des versions audio...",
                        color = PureWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    val errorMessage = appState.changeAudioErrorMessage
    if (errorMessage != null) {
        Dialog(onDismissRequest = { appState.changeAudioErrorMessage = null }) {
            Card(
                modifier = Modifier
                    .width(400.dp)
                    .clip(RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "Changement de version audio",
                        color = PureWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = PureWhite.copy(alpha = 0.75f),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { appState.changeAudioErrorMessage = null },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                        ) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }

    val jobId = appState.changeAudioJobId
    if (jobId != null) {
        DesktopYtmProposalsDialog(
            trackTitle = appState.changeAudioTrackTitle,
            candidates = appState.changeAudioCandidates,
            onSelectCandidate = { videoId ->
                coroutineScope.launch {
                    orchestrator.resolveDownload(jobId, videoId)
                }
                appState.clearChangeAudio()
            },
            onDismiss = {
                coroutineScope.launch {
                    orchestrator.cancelResolutionJob(jobId)
                }
                appState.clearChangeAudio()
            }
        )
    }
}
