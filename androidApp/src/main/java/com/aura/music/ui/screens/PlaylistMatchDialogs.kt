package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.PlaylistTrackMetadata
import com.aura.music.data.playlist.PlaylistMatchCandidates
import com.aura.music.data.playlist.playlistMatchCandidates
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.SemanticSuccess
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The caller owns visibility and scroll, including while a child dialog is open. */
@Composable
internal fun PlaylistCopiesDialog(
    tracks: List<PlaylistTrackMetadata>, suggestionCount: Int, busy: Boolean, error: String?,
    listState: LazyListState, onConfirmSuggestions: () -> Unit,
    onReuse: (PlaylistTrackMetadata) -> Unit, onSeparate: (PlaylistTrackMetadata) -> Unit,
    onDismiss: () -> Unit, trackContent: @Composable (PlaylistTrackMetadata) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null) },
        title = { Text("Copies possibles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 440.dp)) {
                    item(key = "instructions") {
                        Text("Réutilise une copie ou garde une occurrence séparée. Les autres titres restent à vérifier.",
                            style = MaterialTheme.typography.bodySmall)
                        if (suggestionCount > 0) Button(onClick = onConfirmSuggestions, enabled = !busy) {
                            Icon(Icons.Rounded.Link, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(if (suggestionCount == 1) "Valider la proposition" else "Valider les $suggestionCount propositions")
                        }
                    }
                    itemsIndexed(tracks, key = { index, track -> track.trackId ?: "unidentified:$index" }) { _, track ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            trackContent(track)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onReuse(track) }, enabled = track.trackId != null && !busy) {
                                    Icon(Icons.Rounded.Link, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(4.dp)); Text("Réutiliser")
                                }
                                TextButton(onClick = { onSeparate(track) }, enabled = track.trackId != null && !busy) {
                                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(4.dp)); Text("Garder séparé")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** A compact native row, with asynchronous artwork and a separate availability cue. */
@Composable
internal fun PlaylistMatchTrackRow(
    title: String, artist: String, album: String?, coverUri: String?,
    hasLocalAudio: Boolean = false, cloudAvailable: Boolean = false,
    showAvailability: Boolean = true, onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 72.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(DarkGraphite),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
            if (!coverUri.isNullOrBlank()) AsyncImage(
                model = coverUri, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!album.isNullOrBlank()) Text(album, style = MaterialTheme.typography.bodySmall,
                color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showAvailability) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val color = if (hasLocalAudio) SemanticSuccess else TextSecondary
                Icon(when { hasLocalAudio -> Icons.Rounded.DownloadDone; cloudAvailable -> Icons.Rounded.Cloud
                    else -> Icons.Rounded.MusicNote }, null, tint = color, modifier = Modifier.size(14.dp))
                Text(when { hasLocalAudio -> "Sur le téléphone"; cloudAvailable -> "Dans le cloud"
                    else -> "Fichier à vérifier" }, style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
        if (onClick != null) Icon(Icons.Rounded.Link, null, tint = BlazeOrange, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun PlaylistMatchDialog(
    source: PlaylistTrackMetadata, rows: List<PlaylistTrackRow>, library: List<TrackListRow>,
    cloudIds: Set<String>, localAudioIds: Set<String>, onPlaylistChoice: (PlaylistTrackRow) -> Unit,
    onLibraryChoice: (TrackListRow) -> Unit, onDismiss: () -> Unit,
) = key(source.trackId, source.title, source.artist) {
    var showOthers by remember { mutableStateOf(false) }
    val candidates by produceState(PlaylistMatchCandidates(), source, rows, library) {
        value = withContext(Dispatchers.Default) { playlistMatchCandidates(source, rows, library) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Link, null) },
        title = { Text("Choisir une copie") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item {
                    PlaylistMatchTrackRow(source.title, source.artist, source.album, source.coverUri,
                        showAvailability = false)
                    Text("Réutiliser un titre existant", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    if (candidates.playlist.isEmpty() && candidates.library.isEmpty())
                        Text("Aucune copie avec le même titre et artiste.", style = MaterialTheme.typography.bodySmall)
                }
                if (candidates.playlist.isNotEmpty()) item {
                    Text("Dans cette playlist", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                }
                items(candidates.playlist, key = { "occurrence:${it.playlistItemId}" }) { row ->
                    PlaylistMatchTrackRow(row.title, row.artistName, row.albumTitle, row.coverUri,
                        !row.contentUri.isNullOrBlank() || row.trackId in localAudioIds, row.trackId in cloudIds,
                        onClick = { onPlaylistChoice(row) })
                }
                if (candidates.library.isNotEmpty()) item {
                    Text("Dans la bibliothèque", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                }
                items(candidates.library, key = { "library:${it.id}" }) { row ->
                    PlaylistMatchTrackRow(row.title, row.artistName, row.albumTitle, row.coverUri,
                        !row.contentUri.isNullOrBlank(), row.id in cloudIds,
                        onClick = { onLibraryChoice(row) })
                }
                item {
                    TextButton(onClick = { showOthers = !showOthers }) {
                        Text(if (showOthers) "Masquer les autres titres" else "Afficher les autres titres")
                    }
                }
                if (showOthers) items(rows, key = { "other:${it.playlistItemId}" }) { row ->
                    PlaylistMatchTrackRow(row.title, row.artistName, row.albumTitle, row.coverUri,
                        !row.contentUri.isNullOrBlank() || row.trackId in localAudioIds, row.trackId in cloudIds,
                        onClick = { onPlaylistChoice(row) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
