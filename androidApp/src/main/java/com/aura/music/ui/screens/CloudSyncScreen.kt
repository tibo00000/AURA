package com.aura.music.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.SyncedFileResponseData
import com.aura.music.data.repository.CloudFileRepository
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun CloudSyncScreen(
    cloudFileRepository: CloudFileRepository,
    onNavigateBack: () -> Unit,
    playerViewModel: com.aura.music.ui.player.PlayerViewModel? = null
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var refreshTick by remember { mutableIntStateOf(0) }

    var isLoading by remember { mutableStateOf(false) }
    var cloudFiles by remember { mutableStateOf<List<SyncedFileResponseData>>(emptyList()) }
    var localTracks by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }

    // Track operation progress: Map of trackId to "Uploading..." or "Downloading..." or "Deleting..."
    val activeOperations = remember { mutableStateMapOf<String, String>() }

    // Fetch cloud files and local tracks
    LaunchedEffect(refreshTick) {
        isLoading = true
        try {
            cloudFileRepository.listCloudFiles().collect { result ->
                result.onSuccess { items ->
                    // Sort by upload time desc (if present) or just keep list order
                    cloudFiles = items.sortedByDescending { it.uploadedAt ?: "" }
                }.onFailure { err ->
                    scope.launch {
                        snackbarHostState.showSnackbar("Erreur de chargement: ${err.message}")
                    }
                }
            }

            // Fetch local tracks metadata from the db
            localTracks = cloudFileRepository.getLocalTracks()
        } catch (e: Exception) {
            Log.e("CloudSyncScreen", "Failed to refresh cloud files", e)
        } finally {
            isLoading = false
        }
    }

    // Let's calculate:
    // 1. Recent Synced Files (shown to user, say limit to 10 to avoid accumulation)
    // 2. Cloud only files (in cloudFiles but not in localTracks, or contentUri is null/empty)
    val syncedTrackIds = remember(cloudFiles) { cloudFiles.map { it.trackId }.toSet() }
    
    // We can filter local tracks that are local scanned files and NOT synced yet
    val pendingUploadTracks = remember(localTracks, syncedTrackIds) {
        localTracks.filter { track ->
            val isLocal = track.contentUri?.startsWith("content://") == true
            isLocal && !syncedTrackIds.contains(track.id)
        }
    }

    // We can filter cloud files that are missing locally
    // Wait, we need to map cloudFiles back to track metadata if we have it in localTracks
    val cloudOnlyFiles = remember(cloudFiles, localTracks) {
        cloudFiles.filter { cloudFile ->
            val localTrack = localTracks.find { it.id == cloudFile.trackId }
            localTrack == null || localTrack.contentUri.isNullOrBlank()
        }
    }

    // Limit display of synced files to avoid clutter
    val recentSyncedFiles = remember(cloudFiles) {
        cloudFiles.take(10)
    }

    RouteScaffold(
        title = "Gestion des fichiers Cloud",
        onNavigateBack = onNavigateBack
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepBlack)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Hero Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .background(Brush.linearGradient(listOf(Color(0xFF232323), Color(0xFF0F0F0F))))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Fichiers Cloud AURA",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                "Stocke tes fichiers audio personnels dans le cloud pour les synchroniser et les récupérer à tout moment.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Total dans le Cloud : ${cloudFiles.size} fichier(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BlazeOrange,
                                    fontWeight = FontWeight.Bold
                                )
                                Button(
                                    onClick = { refreshTick++ },
                                    shape = RoundedCornerShape(50.dp)
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Rafraîchir")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Rafraîchir")
                                }
                            }
                        }
                    }
                }

                if (isLoading && cloudFiles.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = BlazeOrange)
                        }
                    }
                } else {
                    // 1. Operations in Progress / Active Operations
                    if (activeOperations.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                Text(
                                    "Opérations en cours",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BlazeOrange,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                activeOperations.forEach { (trackId, status) ->
                                    val cloudItem = cloudFiles.find { it.trackId == trackId }
                                    val trackName = localTracks.find { it.id == trackId }?.title
                                        ?: cloudItem?.title
                                        ?: "Piste #${trackId.substringAfterLast(":")}"
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .background(ElevatedGraphite)
                                                .padding(12.dp)
                                                .fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = BlazeOrange
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    trackName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                status,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = BlazeOrange,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Cloud-only tracks available for download (Manual Recovery)
                    if (cloudOnlyFiles.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                Text(
                                    "Disponibles pour récupération (${cloudOnlyFiles.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Text(
                                    "Fichiers présents dans le cloud mais absents sur cet appareil.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                        }

                        items(cloudOnlyFiles, key = { "cloud-only-${it.trackId}" }) { cloudItem ->
                            val trackMeta = localTracks.find { it.id == cloudItem.trackId }
                            val title = trackMeta?.title ?: cloudItem.title ?: "Piste #${cloudItem.trackId.substringAfterLast(":")}"
                            val subtitle = trackMeta?.artistName ?: cloudItem.artistName ?: "Artiste inconnu"
                            val sizeMb = String.format("%.2f MB", cloudItem.sizeBytes.toDouble() / (1024 * 1024))
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .background(DarkGraphite)
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "$subtitle • $sizeMb",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            activeOperations[cloudItem.trackId] = "Téléchargement..."
                                            scope.launch {
                                                cloudFileRepository.downloadTrack(
                                                    trackId = cloudItem.trackId,
                                                    title = trackMeta?.title ?: cloudItem.title,
                                                    artistName = trackMeta?.artistName ?: cloudItem.artistName,
                                                    albumTitle = trackMeta?.albumTitle ?: cloudItem.albumTitle,
                                                    durationMs = trackMeta?.durationMs ?: cloudItem.durationMs
                                                ).collect { res ->
                                                    activeOperations.remove(cloudItem.trackId)
                                                    res.onSuccess {
                                                        snackbarHostState.showSnackbar("Téléchargement réussi : $title")
                                                        refreshTick++
                                                    }.onFailure { err ->
                                                        snackbarHostState.showSnackbar("Échec de téléchargement : ${err.message}")
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !activeOperations.containsKey(cloudItem.trackId)
                                    ) {
                                        Icon(
                                            Icons.Rounded.CloudDownload,
                                            contentDescription = "Récupérer",
                                            tint = BlazeOrange
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Recently Uploaded Files
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "Fichiers récents dans le cloud",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                "Affichage des 10 fichiers les plus récents.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }

                    if (recentSyncedFiles.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .background(DarkGraphite)
                                        .padding(16.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Info, contentDescription = null, tint = TextSecondary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        "Aucun fichier dans le cloud pour le moment.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        items(recentSyncedFiles, key = { "recent-${it.trackId}" }) { cloudItem ->
                            val trackMeta = localTracks.find { it.id == cloudItem.trackId }
                            val title = trackMeta?.title ?: cloudItem.title ?: "Piste #${cloudItem.trackId.substringAfterLast(":")}"
                            val subtitle = trackMeta?.artistName ?: cloudItem.artistName ?: "Artiste inconnu"
                            val sizeMb = String.format("%.2f MB", cloudItem.sizeBytes.toDouble() / (1024 * 1024))
                            val isLocal = trackMeta?.contentUri?.isNotBlank() == true

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .background(DarkGraphite)
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "$subtitle • $sizeMb ${if (isLocal) "(Local)" else "(Cloud uniquement)"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    
                                    // Let user delete the cloud file
                                    IconButton(
                                        onClick = {
                                            activeOperations[cloudItem.trackId] = "Suppression..."
                                            scope.launch {
                                                try {
                                                    cloudFileRepository.deleteSyncFile(cloudItem.trackId).collect { res ->
                                                        activeOperations.remove(cloudItem.trackId)
                                                        res.onSuccess {
                                                            snackbarHostState.showSnackbar("Fichier supprimé du cloud")
                                                            refreshTick++
                                                        }.onFailure { err ->
                                                            snackbarHostState.showSnackbar("Erreur de suppression : ${err.message}")
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    activeOperations.remove(cloudItem.trackId)
                                                    snackbarHostState.showSnackbar("Erreur : ${e.message}")
                                                }
                                            }
                                        },
                                        enabled = !activeOperations.containsKey(cloudItem.trackId)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Supprimer du cloud",
                                            tint = Color.Red.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Snackbar Host for action feedback
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = ElevatedGraphite,
                    contentColor = TextPrimary,
                    actionColor = BlazeOrange
                )
            }
        }
    }
}
