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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.TrackListRow
import com.aura.music.desktop.media.DesktopAudioTagWriter
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
fun DesktopEditMetadataDialog(
    track: TrackListRow?,
    database: AuraDatabase,
    appState: DesktopAppState,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    if (track == null) return

    val file = remember(track.contentUri) {
        val uri = track.contentUri ?: ""
        if (uri.startsWith("file:/")) {
            try { File(java.net.URI(uri)) } catch (e: Exception) { null }
        } else if (uri.isNotBlank()) {
            File(uri)
        } else null
    }

    val isMp3 = file?.extension?.lowercase() == "mp3"
    val coroutineScope = rememberCoroutineScope()

    var title by remember(track) { mutableStateOf(track.title) }
    var artist by remember(track) { mutableStateOf(track.artistName) }
    var album by remember(track) { mutableStateOf(track.albumTitle ?: "") }
    var year by remember(track) { mutableStateOf("") }
    var trackNumber by remember(track) { mutableStateOf("") }

    var selectedCoverFile by remember { mutableStateOf<File?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(520.dp)
                .wrapContentHeight()
                .clip(RoundedCornerShape(16.dp)),
            color = OffBlack
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = BlazeOrange,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Modifier les métadonnées",
                        color = PureWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (!isMp3) {
                    Surface(
                        color = DarkGraphite,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = BlazeOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Édition physique disponible pour les fichiers MP3 (format ${file?.extension?.uppercase() ?: "Inconnu"} en lecture seule).",
                                color = PureWhite.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titre du morceau") },
                    singleLine = true,
                    enabled = isMp3 && !isSaving,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkGraphite,
                        unfocusedContainerColor = DarkGraphite,
                        focusedBorderColor = BlazeOrange,
                        unfocusedBorderColor = HairlineDark,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { appState.isInputFocused = it.isFocused }
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artiste") },
                    singleLine = true,
                    enabled = isMp3 && !isSaving,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkGraphite,
                        unfocusedContainerColor = DarkGraphite,
                        focusedBorderColor = BlazeOrange,
                        unfocusedBorderColor = HairlineDark,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { appState.isInputFocused = it.isFocused }
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    enabled = isMp3 && !isSaving,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkGraphite,
                        unfocusedContainerColor = DarkGraphite,
                        focusedBorderColor = BlazeOrange,
                        unfocusedBorderColor = HairlineDark,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { appState.isInputFocused = it.isFocused }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = trackNumber,
                        onValueChange = { trackNumber = it.filter { c -> c.isDigit() } },
                        label = { Text("N° Piste") },
                        singleLine = true,
                        enabled = isMp3 && !isSaving,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkGraphite,
                            unfocusedContainerColor = DarkGraphite,
                            focusedBorderColor = BlazeOrange,
                            unfocusedBorderColor = HairlineDark,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { appState.isInputFocused = it.isFocused }
                    )

                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it.filter { c -> c.isDigit() } },
                        label = { Text("Année") },
                        singleLine = true,
                        enabled = isMp3 && !isSaving,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkGraphite,
                            unfocusedContainerColor = DarkGraphite,
                            focusedBorderColor = BlazeOrange,
                            unfocusedBorderColor = HairlineDark,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { appState.isInputFocused = it.isFocused }
                    )
                }

                if (isMp3) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val chooser = JFileChooser().apply {
                                fileFilter = FileNameExtensionFilter("Images (JPG, PNG)", "jpg", "jpeg", "png")
                                dialogTitle = "Choisir une pochette"
                            }
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                selectedCoverFile = chooser.selectedFile
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite)
                    ) {
                        Icon(imageVector = Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (selectedCoverFile != null) "Pochette : ${selectedCoverFile!!.name}" else "Remplacer la pochette...",
                            fontSize = 12.sp
                        )
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage!!, color = Color(0xFFFF5252), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = PureWhite.copy(alpha = 0.7f))
                    ) {
                        Text("Annuler")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (isMp3) {
                        Button(
                            onClick = {
                                if (file == null) return@Button
                                isSaving = true
                                errorMessage = null
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val coverBytes = selectedCoverFile?.readBytes()
                                        val mimeType = if (selectedCoverFile?.extension?.lowercase() == "png") "image/png" else "image/jpeg"

                                        val success = DesktopAudioTagWriter.writeMp3Tags(
                                            file = file,
                                            update = DesktopAudioTagWriter.TagUpdate(
                                                title = title.trim().ifBlank { file.nameWithoutExtension },
                                                artist = artist.trim().ifBlank { "Artiste Inconnu" },
                                                album = album.trim().ifBlank { null },
                                                trackNumber = trackNumber.toIntOrNull(),
                                                year = year.toIntOrNull(),
                                                newCoverBytes = coverBytes,
                                                coverMimeType = mimeType
                                            )
                                        )

                                        if (success) {
                                            val now = System.currentTimeMillis()
                                            val rawTrack = database.trackDao().getRawTrackById(track.id)
                                            if (rawTrack != null) {
                                                database.trackDao().upsertTracks(
                                                    listOf(
                                                        rawTrack.copy(
                                                            title = title.trim(),
                                                            normalizedTitle = title.trim().lowercase(),
                                                            displayArtistName = artist.trim(),
                                                            displayAlbumTitle = album.trim().ifBlank { null },
                                                            updatedAt = now
                                                        )
                                                    )
                                                )
                                            }
                                            withContext(Dispatchers.Main) {
                                                onSaved()
                                                onDismiss()
                                            }
                                        } else {
                                            errorMessage = "Échec de l'écriture des tags."
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "Erreur : ${e.message}"
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(color = PureWhite, modifier = Modifier.size(16.dp))
                            } else {
                                Text("Enregistrer")
                            }
                        }
                    }
                }
            }
        }
    }
}
