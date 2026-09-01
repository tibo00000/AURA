package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.playlist.ImportAnalysisResult
import com.aura.music.data.playlist.ParsedPlaylistItem
import com.aura.music.data.playlist.PlaylistImportExportEngine
import com.aura.music.desktop.domain.DesktopPlaylistManager
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun DesktopImportPlaylistDialog(
    appState: DesktopAppState,
    allTracks: List<TrackListRow>,
    playlistManager: DesktopPlaylistManager,
    onPlaylistImported: () -> Unit
) {
    if (!appState.showImportPlaylistDialog) return

    val coroutineScope = rememberCoroutineScope()
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var analysisResult by remember { mutableStateOf<ImportAnalysisResult?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { appState.showImportPlaylistDialog = false }) {
        Surface(
            modifier = Modifier
                .width(540.dp)
                .wrapContentHeight()
                .clip(RoundedCornerShape(16.dp)),
            color = OffBlack
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.FileUpload,
                        contentDescription = null,
                        tint = BlazeOrange,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Importer une Playlist",
                        color = PureWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedFile == null) {
                    Text(
                        text = "Sélectionnez un fichier de playlist (.m3u, .m3u8 ou .json) depuis votre ordinateur. Les morceaux seront automatiquement réconciliés avec votre bibliothèque locale.",
                        color = PureWhite.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val chooser = JFileChooser().apply {
                                fileFilter = FileNameExtensionFilter("Playlists (*.m3u, *.m3u8, *.json)", "m3u", "m3u8", "json")
                                dialogTitle = "Choisir un fichier de playlist"
                            }
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                val file = chooser.selectedFile
                                if (file != null && file.exists()) {
                                    selectedFile = file
                                    isAnalyzing = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            val content = file.readText()
                                            val name = file.nameWithoutExtension
                                            val parsed = if (file.extension.lowercase() == "json") {
                                                val json = PlaylistImportExportEngine.parseJson(content)
                                                json?.tracks?.map {
                                                    ParsedPlaylistItem(it.title, it.artist, null)
                                                } ?: emptyList()
                                            } else {
                                                PlaylistImportExportEngine.parseM3u(content, name)
                                            }

                                            val result = PlaylistImportExportEngine.reconcile(name, parsed, allTracks)
                                            withContext(Dispatchers.Main) {
                                                analysisResult = result
                                                isAnalyzing = false
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                errorMessage = "Erreur de lecture : ${e.message}"
                                                isAnalyzing = false
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Rounded.Folder, contentDescription = null, tint = PureWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Parcourir les fichiers...", color = PureWhite)
                    }
                } else if (isAnalyzing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = BlazeOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Analyse et réconciliation des morceaux...", color = PureWhite, fontSize = 14.sp)
                    }
                } else if (analysisResult != null) {
                    val res = analysisResult!!
                    Surface(
                        color = DarkGraphite,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Playlist : ${res.playlistName}",
                                color = PureWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(
                                    text = "✓ ${res.matchedTracks.size} reconnus",
                                    color = Color(0xFF4CAF50),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "⚠ ${res.unmatchedEntries.size} non trouvés",
                                    color = if (res.unmatchedEntries.isNotEmpty()) Color(0xFFFFB74D) else PureWhite.copy(alpha = 0.5f),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                selectedFile = null
                                analysisResult = null
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = PureWhite.copy(alpha = 0.7f))
                        ) {
                            Text("Changer de fichier")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                isImporting = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val plId = playlistManager.createPlaylist(res.playlistName)
                                        for (track in res.matchedTracks) {
                                            playlistManager.addTrackToPlaylist(plId, track.id)
                                        }
                                        withContext(Dispatchers.Main) {
                                            onPlaylistImported()
                                            appState.showImportPlaylistDialog = false
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            errorMessage = "Erreur d'import : ${e.message}"
                                        }
                                    } finally {
                                        isImporting = false
                                    }
                                }
                            },
                            enabled = !isImporting && res.matchedTracks.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(color = PureWhite, modifier = Modifier.size(16.dp))
                            } else {
                                Text("Créer la playlist (${res.matchedTracks.size} titres)")
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = errorMessage!!, color = Color(0xFFFF5252), fontSize = 12.sp)
                }
            }
        }
    }
}
