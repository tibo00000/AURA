package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.aura.music.data.local.TrackListRow
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CloudSyncScreen(
    allTracks: List<TrackListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onReloadData: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedFilter by remember { mutableStateOf(0) } // 0 = À récupérer, 1 = À sauvegarder, 2 = Tout
    val cloudOnlyTracks = remember(allTracks) { allTracks.filter { it.isCloudOnly } }
    val localOnlyTracks = remember(allTracks) { allTracks.filter { !it.isCloudOnly } }

    val displayedTracks = when (selectedFilter) {
        0 -> cloudOnlyTracks
        1 -> localOnlyTracks
        else -> allTracks
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // COLONNE GAUCHE (Jauge & Réglages Cloud)
        Column(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cloud AURA",
                    color = PureWhite,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = {
                        orchestrator.apiToken?.let { token ->
                            coroutineScope.launch(Dispatchers.IO) {
                                orchestrator.syncCloudData(token) {
                                    onReloadData()
                                }
                            }
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Rounded.Sync, contentDescription = "Synchroniser", tint = BlazeOrange)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "ESPACE DE STOCKAGE VPS",
                        color = PureWhite.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val usedMb = (allTracks.size * 8).coerceAtLeast(120) // estimation ~8Mo par titre
                    val totalMb = 5120 // 5 Go

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = "${"%.1f".format(usedMb / 1024f)} Go",
                            color = PureWhite,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "sur 5 Go",
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LinearProgressIndicator(
                        progress = { (usedMb.toFloat() / totalMb).coerceIn(0f, 1f) },
                        color = BlazeOrange,
                        trackColor = DarkGraphite,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Titres sur le Cloud", color = PureWhite.copy(alpha = 0.6f), fontSize = 13.sp)
                        Text(text = "${cloudOnlyTracks.size}", color = BlazeOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Titres locaux (PC)", color = PureWhite.copy(alpha = 0.6f), fontSize = 13.sp)
                        Text(text = "${localOnlyTracks.size}", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // Paramètre Auto-Sync
            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Sync automatique", color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = "Rapatrie automatiquement les morceaux ajoutés depuis votre mobile", color = PureWhite.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                    Switch(
                        checked = orchestrator.autoSyncEnabled,
                        onCheckedChange = { orchestrator.autoSyncEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureWhite,
                            checkedTrackColor = BlazeOrange,
                            uncheckedTrackColor = DarkGraphite
                        )
                    )
                }
            }

            // Boutons d'action globale
            Button(
                onClick = { orchestrator.triggerCloudDownloadAll(cloudOnlyTracks) },
                colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Rounded.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Tout récupérer sur le PC", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { orchestrator.triggerCloudUploadAll(localOnlyTracks) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(HairlineDark)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Rounded.CloudUpload, contentDescription = null, tint = BlazeOrange)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Tout sauvegarder sur le Cloud")
            }
        }

        // COLONNE DROITE (Liste des fichiers avec filtres)
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight()
        ) {
            // Filtres
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == 0,
                    onClick = { selectedFilter = 0 },
                    label = { Text("À récupérer (${cloudOnlyTracks.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BlazeOrange.copy(alpha = 0.2f),
                        selectedLabelColor = BlazeOrange,
                        labelColor = PureWhite.copy(alpha = 0.6f)
                    )
                )
                FilterChip(
                    selected = selectedFilter == 1,
                    onClick = { selectedFilter = 1 },
                    label = { Text("À sauvegarder (${localOnlyTracks.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BlazeOrange.copy(alpha = 0.2f),
                        selectedLabelColor = BlazeOrange,
                        labelColor = PureWhite.copy(alpha = 0.6f)
                    )
                )
                FilterChip(
                    selected = selectedFilter == 2,
                    onClick = { selectedFilter = 2 },
                    label = { Text("Tous les fichiers (${allTracks.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BlazeOrange.copy(alpha = 0.2f),
                        selectedLabelColor = BlazeOrange,
                        labelColor = PureWhite.copy(alpha = 0.6f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(displayedTracks, key = { it.id }) { track ->
                    CloudSyncTrackRow(
                        track = track,
                        onDownload = { orchestrator.triggerSingleFileDownload(track) },
                        onUpload = { orchestrator.triggerSingleFileUpload(track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudSyncTrackRow(
    track: TrackListRow,
    onDownload: () -> Unit,
    onUpload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(OffBlack)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DesktopArtworkCover(coverUri = track.coverUri, size = 40.dp, shapeRadius = 4.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = track.title, color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = track.displayArtist, color = PureWhite.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (track.isCloudOnly) {
            IconButton(onClick = onDownload) {
                Icon(imageVector = Icons.Rounded.CloudDownload, contentDescription = "Télécharger sur PC", tint = BlazeOrange)
            }
        } else {
            IconButton(onClick = onUpload) {
                Icon(imageVector = Icons.Rounded.CloudUpload, contentDescription = "Uploader sur Cloud", tint = PureWhite.copy(alpha = 0.6f))
            }
        }
    }
}
