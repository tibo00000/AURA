package com.aura.music.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.aura.music.data.network.YtmCandidateDto
import com.aura.music.data.repository.DownloadRepository
import com.aura.music.ui.screens.PlaceholderCover
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.OffBlack
import com.aura.music.ui.theme.SemanticError
import com.aura.music.ui.theme.TextMuted
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ReassignAudioTarget(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverUri: String? = null
)

val LocalReassignAudio = androidx.compose.runtime.staticCompositionLocalOf<((ReassignAudioTarget) -> Unit)?> { null }

/**
 * Dialogue permettant à l'utilisateur de rechercher et de choisir une version audio alternative
 * sur YouTube Music pour un morceau donné.
 */
@Composable
fun ReassignAudioVersionDialog(
    trackId: String,
    initialTitle: String,
    initialArtist: String,
    initialAlbum: String? = null,
    initialCoverUri: String? = null,
    downloadRepository: DownloadRepository,
    userToken: String,
    onDismiss: () -> Unit,
    onAudioReassigned: (jobId: String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("$initialArtist $initialTitle".trim()) }
    var candidates by remember { mutableStateOf<List<YtmCandidateDto>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var selectedVideoId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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

        val results = downloadRepository.searchCandidates(
            userToken = userToken,
            artist = initialArtist,
            title = initialTitle,
            query = query,
            limit = 10
        )
        candidates = results
        isLoading = false
    }

    Dialog(onDismissRequest = { if (!isSubmitting) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = OffBlack
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Changer la version audio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Recherchez et choisissez une version alternative YouTube Music pour « $initialTitle ».",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Champ de recherche
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Rechercher sur YouTube Music...", color = TextMuted) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Rechercher",
                            tint = BlazeOrange
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Effacer",
                                    tint = TextSecondary
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlazeOrange,
                        unfocusedBorderColor = ElevatedGraphite,
                        cursorColor = BlazeOrange,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                // Message d'erreur éventuel
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = SemanticError,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // Contenu des candidats
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(color = BlazeOrange, strokeWidth = 2.5.dp)
                        }
                        candidates == null -> {
                            CircularProgressIndicator(color = BlazeOrange, strokeWidth = 2.5.dp)
                        }
                        candidates!!.isEmpty() -> {
                            Text(
                                text = "Aucune proposition trouvée pour cette recherche.",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodyMedium
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
                                        colors = CardDefaults.cardColors(
                                            containerColor = ElevatedGraphite
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            // Miniature
                                            if (candidate.coverUri != null) {
                                                AsyncImage(
                                                    model = candidate.coverUri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                )
                                            } else {
                                                PlaceholderCover(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                )
                                            }

                                            // Détails
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = candidate.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = candidate.artist + (candidate.album?.let { " • $it" } ?: ""),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val candidateDuration = candidate.duration
                                                if (candidateDuration != null) {
                                                    Text(
                                                        text = candidateDuration,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = TextMuted
                                                    )
                                                }
                                            }

                                            // Bouton d'écoute / aperçu sur YouTube externe
                                            IconButton(
                                                onClick = {
                                                    try {
                                                        val intent = Intent(
                                                            Intent.ACTION_VIEW,
                                                            Uri.parse("https://www.youtube.com/watch?v=${candidate.videoId}")
                                                        )
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {
                                                        // Ignore intent failure
                                                    }
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.OpenInNew,
                                                    contentDescription = "Écouter sur YouTube",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            // Bouton de choix
                                            Button(
                                                onClick = {
                                                    if (!isSubmitting) {
                                                        isSubmitting = true
                                                        selectedVideoId = candidate.videoId
                                                        coroutineScope.launch {
                                                            val result = downloadRepository.reassignAudio(
                                                                userToken = userToken,
                                                                trackId = trackId,
                                                                videoId = candidate.videoId,
                                                                title = initialTitle,
                                                                artistName = initialArtist,
                                                                albumTitle = initialAlbum,
                                                                coverUri = candidate.coverUri ?: initialCoverUri
                                                            )
                                                            if (result.isSuccess) {
                                                                val newJobId = result.getOrThrow()
                                                                onAudioReassigned(newJobId)
                                                                onDismiss()
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
                                                modifier = Modifier.height(36.dp)
                                            ) {
                                                if (isCurrentCandidateSubmitting) {
                                                    CircularProgressIndicator(
                                                        color = Color.White,
                                                        strokeWidth = 2.dp,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                } else {
                                                    Text("Choisir", style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Footer avec bouton Annuler
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting
                    ) {
                        Text(text = "Annuler", color = BlazeOrange, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
