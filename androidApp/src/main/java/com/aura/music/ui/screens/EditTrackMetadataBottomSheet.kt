package com.aura.music.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.TrackSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTrackMetadataBottomSheet(
    track: TrackListRow,
    apiService: AuraApiService,
    localLibraryRepository: LocalLibraryRepository,
    onDismiss: () -> Unit,
    onTrackUpdated: (TrackListRow) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember(track.id) { mutableStateOf(track.title) }
    var artistName by remember(track.id) { mutableStateOf(track.artistName ?: "") }
    var albumTitle by remember(track.id) { mutableStateOf(track.albumTitle ?: "") }
    var trackNumber by remember(track.id) { mutableStateOf("") }
    var year by remember(track.id) { mutableStateOf("") }

    // Pochette sélectionnée : soit une URI locale (galerie), soit une URL Deezer, soit celle d'origine
    var selectedCoverUriOrUrl by remember(track.id) { mutableStateOf(track.coverUri) }

    // État de recherche de suggestions Deezer
    var isSearchingSuggestions by remember { mutableStateOf(true) }
    var suggestedTracks by remember { mutableStateOf<List<TrackSummary>>(emptyList()) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // PhotoPicker pour choisir une image de la galerie
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedCoverUriOrUrl = uri.toString()
        }
    }

    // Recherche automatique des suggestions Deezer au montage
    LaunchedEffect(track.id) {
        withContext(Dispatchers.IO) {
            try {
                val query = "${track.title} ${track.artistName ?: ""}".trim()
                if (query.length >= 2) {
                    val result = apiService.search(query, limitTracks = 6, limitArtists = 1, limitAlbums = 1)
                    val tracks = result.data?.tracks ?: emptyList()
                    suggestedTracks = tracks
                }
            } catch (e: Exception) {
                // Ignore silent network failure for suggestions
            } finally {
                isSearchingSuggestions = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DeepBlack,
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(2.dp),
                color = HairlineDark
            ) {}
        }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // En-tête
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Modifier les métadonnées",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fermer", tint = TextMuted)
                    }
                }
            }

            // Bannière de permission de stockage permanent si nécessaire (Android 11+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !com.aura.music.core.StoragePermissionHelper.hasFullStorageAccess(context)) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = ElevatedGraphite)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = BlazeOrange,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Écriture permanente dans le MP3",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    "Autorise AURA à modifier directement les tags du fichier pour que vos modifications soient permanentes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    com.aura.music.core.StoragePermissionHelper.requestFullStorageAccess(context)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Activer", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // Aperçu de la pochette (140x140dp) avec bouton pour changer
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(DarkGraphite)
                            .clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!selectedCoverUriOrUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = selectedCoverUriOrUrl,
                                contentDescription = "Pochette",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                        }

                        // Badge overlay "Changer"
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(BlazeOrange),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Changer la pochette",
                                tint = TextOnAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp), tint = BlazeOrange)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Choisir une image de la galerie", color = BlazeOrange, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Section 1 : Suggestions Deezer (Cas 1)
            if (suggestedTracks.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Suggestions officielles (Deezer)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = "Cliquez sur une version pour auto-compléter les métadonnées et la pochette.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                items(suggestedTracks, key = { it.id }) { suggestion ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                title = suggestion.title
                                artistName = suggestion.displayArtistName
                                albumTitle = suggestion.displayAlbumTitle ?: ""
                                if (!suggestion.coverUri.isNullOrBlank()) {
                                    selectedCoverUriOrUrl = suggestion.coverUri
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkGraphite)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!suggestion.coverUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = suggestion.coverUri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ElevatedGraphite),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TextMuted)
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = suggestion.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${suggestion.displayArtistName} • ${suggestion.displayAlbumTitle ?: "Single"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            TextButton(
                                onClick = {
                                    title = suggestion.title
                                    artistName = suggestion.displayArtistName
                                    albumTitle = suggestion.displayAlbumTitle ?: ""
                                    if (!suggestion.coverUri.isNullOrBlank()) {
                                        selectedCoverUriOrUrl = suggestion.coverUri
                                    }
                                }
                            ) {
                                Text("Appliquer", color = BlazeOrange, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Section 2 : Formulaire d'édition manuelle (Cas 2)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Informations du morceau",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Titre") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BlazeOrange,
                            unfocusedBorderColor = HairlineDark,
                            focusedLabelColor = BlazeOrange,
                            unfocusedLabelColor = TextMuted,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = artistName,
                        onValueChange = { artistName = it },
                        label = { Text("Artiste") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BlazeOrange,
                            unfocusedBorderColor = HairlineDark,
                            focusedLabelColor = BlazeOrange,
                            unfocusedLabelColor = TextMuted,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = albumTitle,
                        onValueChange = { albumTitle = it },
                        label = { Text("Album") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BlazeOrange,
                            unfocusedBorderColor = HairlineDark,
                            focusedLabelColor = BlazeOrange,
                            unfocusedLabelColor = TextMuted,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = trackNumber,
                            onValueChange = { trackNumber = it },
                            label = { Text("N° Piste") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BlazeOrange,
                                unfocusedBorderColor = HairlineDark,
                                focusedLabelColor = BlazeOrange,
                                unfocusedLabelColor = TextMuted,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = year,
                            onValueChange = { year = it },
                            label = { Text("Année") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BlazeOrange,
                                unfocusedBorderColor = HairlineDark,
                                focusedLabelColor = BlazeOrange,
                                unfocusedLabelColor = TextMuted,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            if (errorMessage != null) {
                item {
                    Text(
                        text = errorMessage ?: "",
                        color = Color.Red,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Boutons d'action : Enregistrer / Annuler
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, HairlineDark),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text("Annuler")
                    }

                    Button(
                        onClick = {
                            if (title.isBlank()) {
                                errorMessage = "Le titre ne peut pas être vide"
                                return@Button
                            }
                            isSaving = true
                            errorMessage = null
                            scope.launch {
                                val result = localLibraryRepository.updateTrackMetadata(
                                    trackId = track.id,
                                    newTitle = title,
                                    newArtistName = artistName,
                                    newAlbumTitle = albumTitle.ifBlank { null },
                                    coverSourceUriOrUrl = selectedCoverUriOrUrl,
                                    trackNumber = trackNumber.ifBlank { null },
                                    year = year.ifBlank { null }
                                )
                                isSaving = false
                                result.onSuccess { updatedTrack ->
                                    onTrackUpdated(updatedTrack)
                                    onDismiss()
                                }.onFailure { err ->
                                    errorMessage = "Erreur lors de la sauvegarde : ${err.message}"
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1.5f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BlazeOrange,
                            contentColor = TextOnAccent
                        ),
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = TextOnAccent,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enregistrer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
