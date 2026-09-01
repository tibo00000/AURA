package com.aura.music.desktop.ui.components

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

@Composable
fun DesktopCreatePlaylistDialog(
    appState: DesktopAppState,
    orchestrator: DesktopPlaybackOrchestrator,
    onPlaylistCreated: () -> Unit
) {
    if (!appState.showCreatePlaylistDialog) return
    var name by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Dialog(onDismissRequest = { appState.showCreatePlaylistDialog = false }) {
        Card(
            modifier = Modifier.width(400.dp).clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = OffBlack)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = "Créer une playlist", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Nom de la playlist...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkGraphite,
                        unfocusedContainerColor = DarkGraphite,
                        focusedBorderColor = BlazeOrange,
                        unfocusedBorderColor = HairlineDark,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { appState.isInputFocused = it.isFocused }
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { appState.showCreatePlaylistDialog = false }) {
                        Text("Annuler", color = PureWhite.copy(alpha = 0.6f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    orchestrator.createPlaylist(name.trim())
                                    onPlaylistCreated()
                                    appState.showCreatePlaylistDialog = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                        enabled = name.isNotBlank()
                    ) {
                        Text("Créer")
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopRenamePlaylistDialog(
    appState: DesktopAppState,
    orchestrator: DesktopPlaybackOrchestrator,
    onPlaylistRenamed: () -> Unit
) {
    val target = appState.playlistToRename ?: return
    var newName by remember(target) { mutableStateOf(target.second) }
    val coroutineScope = rememberCoroutineScope()

    Dialog(onDismissRequest = { appState.playlistToRename = null }) {
        Card(
            modifier = Modifier.width(400.dp).clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = OffBlack)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = "Renommer la playlist", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkGraphite,
                        unfocusedContainerColor = DarkGraphite,
                        focusedBorderColor = BlazeOrange,
                        unfocusedBorderColor = HairlineDark,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { appState.isInputFocused = it.isFocused }
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { appState.playlistToRename = null }) {
                        Text("Annuler", color = PureWhite.copy(alpha = 0.6f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    orchestrator.renamePlaylist(target.first, newName.trim())
                                    onPlaylistRenamed()
                                    appState.playlistToRename = null
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                        enabled = newName.isNotBlank()
                    ) {
                        Text("Enregistrer")
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopAddToPlaylistDialog(
    appState: DesktopAppState,
    playlists: List<PlaylistListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    onTrackAdded: () -> Unit
) {
    val track = appState.trackForPlaylistPicker ?: return
    val coroutineScope = rememberCoroutineScope()

    Dialog(onDismissRequest = { appState.trackForPlaylistPicker = null }) {
        Card(
            modifier = Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = OffBlack)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = "Ajouter à une playlist", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${track.title} • ${track.displayArtist}", color = PureWhite.copy(alpha = 0.6f), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))

                if (playlists.isEmpty()) {
                    Text(text = "Aucune playlist créée.", color = PureWhite.copy(alpha = 0.5f), fontSize = 13.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(playlists, key = { it.id }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkGraphite)
                                    .clickable {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            orchestrator.addTrackToPlaylist(pl.id, track.id)
                                            onTrackAdded()
                                            appState.trackForPlaylistPicker = null
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Rounded.QueueMusic, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(text = pl.name, color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(text = "${pl.trackCount} titres", color = PureWhite.copy(alpha = 0.5f), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { appState.trackForPlaylistPicker = null }) {
                        Text("Fermer", color = PureWhite.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}
