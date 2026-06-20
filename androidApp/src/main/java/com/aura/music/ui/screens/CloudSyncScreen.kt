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
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
    var syncEnabled by remember { mutableStateOf(false) }

    // Navigation and list filters
    var selectedFilter by remember { mutableStateOf("cloud_only") } // options: "cloud_only", "pending_upload", "all_cloud"
    var sortByFileSize by remember { mutableStateOf(false) }

    // Bulk actions in progress flags
    var isBulkUploading by remember { mutableStateOf(false) }
    var isBulkDownloading by remember { mutableStateOf(false) }

    // Track operation progress: Map of trackId to "Uploading...", "Downloading...", "Deleting..."
    val activeOperations = remember { mutableStateMapOf<String, String>() }

    // Fetch cloud files, settings, and local tracks
    LaunchedEffect(refreshTick) {
        isLoading = true
        try {
            cloudFileRepository.listCloudFiles().collect { result ->
                result.onSuccess { items ->
                    cloudFiles = items
                }.onFailure { err ->
                    scope.launch {
                        snackbarHostState.showSnackbar("Erreur de chargement cloud: ${err.message}")
                    }
                }
            }

            localTracks = cloudFileRepository.getLocalTracks()
            syncEnabled = cloudFileRepository.getSettings()?.syncEnabled == true
        } catch (e: Exception) {
            Log.e("CloudSyncScreen", "Failed to refresh cloud state", e)
        } finally {
            isLoading = false
        }
    }

    // Calculations
    val syncedTrackIds = remember(cloudFiles) { cloudFiles.map { it.trackId }.toSet() }

    // 1. Local tracks not yet synced to cloud (Pending upload)
    val pendingUploadTracks = remember(localTracks, cloudFiles) {
        localTracks.filter { track ->
            val isLocal = track.contentUri?.startsWith("content://") == true || track.contentUri?.startsWith("file://") == true
            if (!isLocal) return@filter false
            
            // Check if synced by ID
            val isSyncedById = cloudFiles.any { it.trackId == track.id }
            if (isSyncedById) return@filter false
            
            // Check if synced by metadata (Title + Artist)
            val normTitle = track.title.lowercase().trim()
            val normArtist = track.artistName.lowercase().trim()
            val isSyncedByMetadata = cloudFiles.any { cloud ->
                val cTitle = cloud.title?.lowercase()?.trim() ?: ""
                val cArtist = cloud.artistName?.lowercase()?.trim() ?: ""
                cTitle == normTitle && cArtist == normArtist
            }
            !isSyncedByMetadata
        }
    }

    // 2. Cloud files missing locally (Available for download)
    val cloudOnlyFiles = remember(cloudFiles, localTracks) {
        cloudFiles.filter { cloudFile ->
            // Check if local track exists by ID and has file
            val localTrack = localTracks.find { it.id == cloudFile.trackId }
            val hasLocalFileById = localTrack != null && !localTrack.contentUri.isNullOrBlank()
            if (hasLocalFileById) return@filter false
            
            // Check if local track exists by metadata (Title + Artist) and has file
            val normTitle = cloudFile.title?.lowercase()?.trim() ?: ""
            val normArtist = cloudFile.artistName?.lowercase()?.trim() ?: ""
            val hasLocalByMetadata = localTracks.any { local ->
                val lTitle = local.title.lowercase().trim()
                val lArtist = local.artistName.lowercase().trim()
                lTitle == normTitle && lArtist == normArtist && !local.contentUri.isNullOrBlank()
            }
            !hasLocalByMetadata
        }
    }

    // 3. VPS Storage calculation
    val totalSizeBytes = remember(cloudFiles) { cloudFiles.sumOf { it.sizeBytes } }
    val totalSizeMb = remember(totalSizeBytes) { String.format("%.2f MB", totalSizeBytes.toDouble() / (1024 * 1024)) }
    val maxVpsLimitBytes = 5L * 1024L * 1024L * 1024L // 5 GB limit representation
    val storageFraction = remember(totalSizeBytes) { (totalSizeBytes.toFloat() / maxVpsLimitBytes.toFloat()).coerceIn(0f, 1f) }

    // 4. Sorted cloud inventory
    val displayCloudFiles = remember(cloudFiles, sortByFileSize) {
        if (sortByFileSize) {
            cloudFiles.sortedByDescending { it.sizeBytes }
        } else {
            cloudFiles.sortedByDescending { it.uploadedAt ?: "" }
        }
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
                contentPadding = PaddingValues(bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: VPS Storage Gauge
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.Storage,
                                        contentDescription = null,
                                        tint = BlazeOrange,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Stockage VPS AURA",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                IconButton(onClick = { refreshTick++ }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Rafraîchir", tint = TextSecondary)
                                }
                            }

                            LinearProgressIndicator(
                                progress = storageFraction,
                                modifier = Modifier.fillMaxWidth(),
                                color = BlazeOrange,
                                trackColor = DarkGraphite
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "$totalSizeMb occupés",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Limite : 5.0 GB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }

                            Text(
                                "Total : ${cloudFiles.size} morceau(x) sauvegardé(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = BlazeOrange,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Section 2: Auto-Sync Settings
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .background(DarkGraphite)
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Sauvegarde automatique",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Sauvegarde directement chaque son téléchargé dans le cloud.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = syncEnabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        cloudFileRepository.updateSyncEnabled(checked)
                                        syncEnabled = checked
                                        snackbarHostState.showSnackbar(
                                            if (checked) "Sauvegarde automatique activée" else "Sauvegarde automatique désactivée"
                                        )
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = BlazeOrange,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = DarkGraphite
                                )
                            )
                        }
                    }
                }

                // Section 3: Active operations or bulk actions
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

                    // Section 4: Filters Chips / Tab Selector
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val filters = listOf(
                                    "cloud_only" to "À récupérer (${cloudOnlyFiles.size})",
                                    "pending_upload" to "À uploader (${pendingUploadTracks.size})",
                                    "all_cloud" to "Tout le Cloud (${cloudFiles.size})"
                                )
                                filters.forEach { (type, label) ->
                                    val selected = selectedFilter == type
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(
                                                if (selected) BlazeOrange else DarkGraphite,
                                                shape = RoundedCornerShape(50.dp)
                                            )
                                            .clickable { selectedFilter = type }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) Color.Black else TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // Bulk Actions Row based on the selected filter
                            if (selectedFilter == "cloud_only" && cloudOnlyFiles.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isBulkDownloading = true
                                            var successCount = 0
                                            var failCount = 0
                                            cloudOnlyFiles.forEach { cloudItem ->
                                                val trackMeta = localTracks.find { it.id == cloudItem.trackId }
                                                activeOperations[cloudItem.trackId] = "Téléchargement..."
                                                cloudFileRepository.downloadTrack(
                                                    trackId = cloudItem.trackId,
                                                    title = trackMeta?.title ?: cloudItem.title,
                                                    artistName = trackMeta?.artistName ?: cloudItem.artistName,
                                                    albumTitle = trackMeta?.albumTitle ?: cloudItem.albumTitle,
                                                    durationMs = trackMeta?.durationMs ?: cloudItem.durationMs,
                                                    artistId = trackMeta?.artistId ?: cloudItem.artistId,
                                                    albumId = trackMeta?.albumId ?: cloudItem.albumId,
                                                    coverUri = trackMeta?.coverUri ?: cloudItem.coverUri
                                                ).collect { res ->
                                                    activeOperations.remove(cloudItem.trackId)
                                                    res.onSuccess { successCount++ }.onFailure { failCount++ }
                                                }
                                            }
                                            isBulkDownloading = false
                                            snackbarHostState.showSnackbar("Récupération terminée: $successCount réussi(s), $failCount échoué(s)")
                                            refreshTick++
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                    enabled = !isBulkDownloading && activeOperations.isEmpty(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Color.Black)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Tout récupérer (${cloudOnlyFiles.size} fichiers)", color = Color.Black, fontWeight = FontWeight.Bold)
                                    if (isBulkDownloading) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                                    }
                                }
                            } else if (selectedFilter == "pending_upload" && pendingUploadTracks.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isBulkUploading = true
                                            var successCount = 0
                                            var failCount = 0
                                            pendingUploadTracks.forEach { track ->
                                                activeOperations[track.id] = "Sauvegarde..."
                                                cloudFileRepository.uploadTrack(track.id).collect { res ->
                                                    activeOperations.remove(track.id)
                                                    res.onSuccess { successCount++ }.onFailure { failCount++ }
                                                }
                                            }
                                            isBulkUploading = false
                                            snackbarHostState.showSnackbar("Sauvegarde terminée: $successCount réussi(s), $failCount échoué(s)")
                                            refreshTick++
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                    enabled = !isBulkUploading && activeOperations.isEmpty(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Rounded.CloudUpload, contentDescription = null, tint = Color.Black)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Tout sauvegarder (${pendingUploadTracks.size} fichiers)", color = Color.Black, fontWeight = FontWeight.Bold)
                                    if (isBulkUploading) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                                    }
                                }
                            } else if (selectedFilter == "all_cloud" && cloudFiles.isNotEmpty()) {
                                // Sorting control for VPS storage cleanup
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .clickable { sortByFileSize = !sortByFileSize }
                                            .background(DarkGraphite, shape = RoundedCornerShape(8.dp))
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Sort, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            if (sortByFileSize) "Trié par taille" else "Trié par date d'envoi",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section 5: List Rendering based on selected filter
                    when (selectedFilter) {
                        "cloud_only" -> {
                            if (cloudOnlyFiles.isEmpty()) {
                                item {
                                    EmptyFilterState("Aucun titre à récupérer. Votre appareil est à jour.")
                                }
                            } else {
                                items(cloudOnlyFiles, key = { "cloud-only-${it.trackId}" }) { cloudItem ->
                                    val trackMeta = localTracks.find { it.id == cloudItem.trackId }
                                    val title = trackMeta?.title ?: cloudItem.title ?: "Piste #${cloudItem.trackId.substringAfterLast(":")}"
                                    val subtitle = trackMeta?.artistName ?: cloudItem.artistName ?: "Artiste inconnu"
                                    val sizeMb = String.format("%.2f MB", cloudItem.sizeBytes.toDouble() / (1024 * 1024))
                                    
                                    TrackItemRow(
                                        title = title,
                                        subtitle = subtitle,
                                        sizeMb = sizeMb,
                                        actionIcon = Icons.Rounded.CloudDownload,
                                        actionColor = BlazeOrange,
                                        onActionClick = {
                                            activeOperations[cloudItem.trackId] = "Téléchargement..."
                                            scope.launch {
                                                cloudFileRepository.downloadTrack(
                                                    trackId = cloudItem.trackId,
                                                    title = trackMeta?.title ?: cloudItem.title,
                                                    artistName = trackMeta?.artistName ?: cloudItem.artistName,
                                                    albumTitle = trackMeta?.albumTitle ?: cloudItem.albumTitle,
                                                    durationMs = trackMeta?.durationMs ?: cloudItem.durationMs,
                                                    artistId = trackMeta?.artistId ?: cloudItem.artistId,
                                                    albumId = trackMeta?.albumId ?: cloudItem.albumId,
                                                    coverUri = trackMeta?.coverUri ?: cloudItem.coverUri
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
                                    )
                                }
                            }
                        }
                        "pending_upload" -> {
                            if (pendingUploadTracks.isEmpty()) {
                                item {
                                    EmptyFilterState("Aucun titre à sauvegarder. Tout est dans le cloud.")
                                }
                            } else {
                                items(pendingUploadTracks, key = { "pending-${it.id}" }) { track ->
                                    TrackItemRow(
                                        title = track.title,
                                        subtitle = track.artistName,
                                        sizeMb = null,
                                        actionIcon = Icons.Rounded.CloudUpload,
                                        actionColor = BlazeOrange,
                                        onActionClick = {
                                            activeOperations[track.id] = "Sauvegarde..."
                                            scope.launch {
                                                cloudFileRepository.uploadTrack(track.id).collect { res ->
                                                    activeOperations.remove(track.id)
                                                    res.onSuccess {
                                                        snackbarHostState.showSnackbar("Sauvegarde réussie : ${track.title}")
                                                        refreshTick++
                                                    }.onFailure { err ->
                                                        snackbarHostState.showSnackbar("Échec de la sauvegarde : ${err.message}")
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !activeOperations.containsKey(track.id)
                                    )
                                }
                            }
                        }
                        "all_cloud" -> {
                            if (displayCloudFiles.isEmpty()) {
                                item {
                                    EmptyFilterState("Aucun fichier stocké dans le cloud.")
                                }
                            } else {
                                items(displayCloudFiles, key = { "all-cloud-${it.trackId}" }) { cloudItem ->
                                    val trackMeta = localTracks.find { it.id == cloudItem.trackId }
                                    val title = trackMeta?.title ?: cloudItem.title ?: "Piste #${cloudItem.trackId.substringAfterLast(":")}"
                                    val subtitle = trackMeta?.artistName ?: cloudItem.artistName ?: "Artiste inconnu"
                                    val sizeMb = String.format("%.2f MB", cloudItem.sizeBytes.toDouble() / (1024 * 1024))
                                    val isLocal = trackMeta?.contentUri?.isNotBlank() == true

                                    TrackItemRow(
                                        title = title,
                                        subtitle = "$subtitle • ${if (isLocal) "Sur l'appareil" else "Cloud uniquement"}",
                                        sizeMb = sizeMb,
                                        actionIcon = Icons.Rounded.Delete,
                                        actionColor = Color.Red.copy(alpha = 0.8f),
                                        onActionClick = {
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
                                    )
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

@Composable
fun TrackItemRow(
    title: String,
    subtitle: String,
    sizeMb: String?,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionColor: Color,
    onActionClick: () -> Unit,
    enabled: Boolean
) {
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
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (sizeMb != null) "$subtitle • $sizeMb" else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onActionClick,
                enabled = enabled
            ) {
                Icon(
                    actionIcon,
                    contentDescription = null,
                    tint = if (enabled) actionColor else TextSecondary
                )
            }
        }
    }
}

@Composable
fun EmptyFilterState(message: String) {
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
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}
