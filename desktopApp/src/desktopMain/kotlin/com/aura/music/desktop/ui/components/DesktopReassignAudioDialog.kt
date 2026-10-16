package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aura.music.data.network.YtmCandidateDto
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.handCursor
import com.aura.music.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

@Composable
fun DesktopReassignAudioDialog(
    appState: DesktopAppState,
    orchestrator: DesktopPlaybackOrchestrator,
    onAudioReassigned: (jobId: String) -> Unit = {}
) {
    val target = appState.reassignAudioTarget ?: return
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember(target) { mutableStateOf("${target.artist} ${target.title}".trim()) }
    var candidates by remember(target) { mutableStateOf<List<YtmCandidateDto>?>(null) }
    var isLoading by remember(target) { mutableStateOf(false) }
    var isSubmitting by remember(target) { mutableStateOf(false) }
    var selectedVideoId by remember(target) { mutableStateOf<String?>(null) }
    var errorMessage by remember(target) { mutableStateOf<String?>(null) }

    // Recherche automatique avec debounce de 400 ms
    LaunchedEffect(searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            candidates = emptyList()
            isLoading = false
            return@LaunchedEffect
        }

        delay(400)
        isLoading = true
        errorMessage = null

        val results = orchestrator.searchCandidates(
            artist = target.artist,
            title = target.title,
            query = query,
            limit = 10
        )
        candidates = results
        isLoading = false
    }

    Dialog(onDismissRequest = {
        if (!isSubmitting) {
            appState.closeReassignAudio()
            appState.isInputFocused = false
        }
    }) {
        Card(
            modifier = Modifier
                .width(620.dp)
                .height(680.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, HairlineDark, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = OffBlack)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // En-tête
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Changer la version audio",
                            color = PureWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Recherchez et choisissez une version alternative YouTube Music pour « ${target.title} ».",
                            color = PureWhite.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = {
                            if (!isSubmitting) {
                                appState.closeReassignAudio()
                                appState.isInputFocused = false
                            }
                        },
                        modifier = Modifier.size(32.dp).handCursor()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Fermer",
                            tint = PureWhite.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Champ de recherche avec focus tracker
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Rechercher sur YouTube Music...", color = PureWhite.copy(alpha = 0.4f), fontSize = 13.sp) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp).handCursor()) {
                                Icon(Icons.Rounded.Close, contentDescription = "Effacer", tint = PureWhite.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkGraphite,
                        unfocusedContainerColor = DarkGraphite,
                        focusedBorderColor = BlazeOrange,
                        unfocusedBorderColor = HairlineDark,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        cursorColor = BlazeOrange
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { appState.isInputFocused = it.isFocused },
                    shape = RoundedCornerShape(10.dp)
                )

                // Message d'erreur éventuel
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // Contenu : propositions ou état de chargement
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(color = BlazeOrange, strokeWidth = 2.5.dp, modifier = Modifier.size(36.dp))
                        }
                        candidates == null -> {
                            CircularProgressIndicator(color = BlazeOrange, strokeWidth = 2.5.dp, modifier = Modifier.size(36.dp))
                        }
                        candidates!!.isEmpty() -> {
                            Text(
                                text = "Aucune version trouvée pour cette recherche.",
                                color = PureWhite.copy(alpha = 0.5f),
                                fontSize = 14.sp
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(candidates!!, key = { it.videoId }) { candidate ->
                                    val isCurrentCandidateSubmitting = isSubmitting && selectedVideoId == candidate.videoId

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(containerColor = DarkGraphite)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            // Miniature
                                            DesktopArtworkCover(
                                                coverUri = candidate.coverUri,
                                                size = 48.dp,
                                                shapeRadius = 6.dp
                                            )

                                            // Infos
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = candidate.title,
                                                    color = PureWhite,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = candidate.artist + (candidate.album?.let { " • $it" } ?: ""),
                                                    color = PureWhite.copy(alpha = 0.6f),
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val candidateDuration = candidate.duration
                                                if (!candidateDuration.isNullOrBlank()) {
                                                    Text(
                                                        text = candidateDuration,
                                                        color = PureWhite.copy(alpha = 0.4f),
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }

                                            // Bouton d'aperçu dans le navigateur externe
                                            IconButton(
                                                onClick = {
                                                    try {
                                                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                                            Desktop.getDesktop().browse(URI("https://www.youtube.com/watch?v=${candidate.videoId}"))
                                                        }
                                                    } catch (e: Exception) {
                                                        System.err.println("Failed to open browser: ${e.message}")
                                                    }
                                                },
                                                modifier = Modifier.size(36.dp).handCursor()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.OpenInNew,
                                                    contentDescription = "Écouter sur YouTube",
                                                    tint = PureWhite.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            // Bouton Choisir
                                            Button(
                                                onClick = {
                                                    if (!isSubmitting) {
                                                        isSubmitting = true
                                                        selectedVideoId = candidate.videoId
                                                        coroutineScope.launch {
                                                            val result = orchestrator.reassignAudio(
                                                                trackId = target.trackId,
                                                                videoId = candidate.videoId,
                                                                title = target.title,
                                                                artistName = target.artist,
                                                                albumTitle = target.album,
                                                                coverUri = candidate.coverUri ?: target.coverUri
                                                            )
                                                            if (result.isSuccess) {
                                                                val newJobId = result.getOrThrow()
                                                                onAudioReassigned(newJobId)
                                                                appState.closeReassignAudio()
                                                                appState.isInputFocused = false
                                                            } else {
                                                                errorMessage = result.exceptionOrNull()?.message
                                                                    ?: "Erreur lors de la réassignation audio"
                                                                isSubmitting = false
                                                                selectedVideoId = null
                                                            }
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = BlazeOrange,
                                                    contentColor = Color.White
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                enabled = !isSubmitting,
                                                modifier = Modifier.height(36.dp).handCursor()
                                            ) {
                                                if (isCurrentCandidateSubmitting) {
                                                    CircularProgressIndicator(
                                                        color = Color.White,
                                                        strokeWidth = 2.dp,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                } else {
                                                    Text("Choisir", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Pied de dialogue avec bouton Annuler
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (!isSubmitting) {
                                appState.closeReassignAudio()
                                appState.isInputFocused = false
                            }
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier.handCursor()
                    ) {
                        Text("Annuler", color = PureWhite.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
