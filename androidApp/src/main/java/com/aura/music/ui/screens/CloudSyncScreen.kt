package com.aura.music.ui.screens

import com.aura.music.ui.utils.TrackLookupIndex
import com.aura.music.ui.utils.FastTimeFormatter

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.SyncedFileResponseData
import com.aura.music.data.repository.CloudFileRepository
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.components.AuraLazyColumn
import com.aura.music.ui.player.PlayerViewModel
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CloudFilterType {
    ALL,
    CLOUD_ONLY,
    DOWNLOADED,
    HEAVY,
    RECENT
}

@Composable
fun CloudSyncScreen(
    cloudFileRepository: CloudFileRepository,
    onNavigateBack: () -> Unit,
    playerViewModel: PlayerViewModel? = null
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var refreshTick by remember { mutableIntStateOf(0) }

    // Multi-page Tab selector: 0: Dashboard / 1: Explorateur Cloud / 2: Stockage Local
    var selectedTab by remember { mutableIntStateOf(0) }

    var isLoading by remember { mutableStateOf(false) }
    var cloudFiles by remember { mutableStateOf<List<SyncedFileResponseData>>(emptyList()) }
    var localTracks by remember { mutableStateOf<List<TrackListRow>>(emptyList()) }

    // Tab 0: Show pending uploads list toggle
    var showPendingUploads by remember { mutableStateOf(false) }

    // Tab 1: Filters & Search
    var cloudSearchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(CloudFilterType.ALL) }
    var sortByFileSize by remember { mutableStateOf(false) }

    // Dialog state
    var trackToDeleteFromCloud by remember { mutableStateOf<SyncedFileResponseData?>(null) }
    var showClearLocalCacheDialog by remember { mutableStateOf(false) }
    var trackToDeleteLocalOnly by remember { mutableStateOf<TrackListRow?>(null) }

    // Progress operations tracking (set of trackIds currently operating)
    var activeOperations by remember { mutableStateOf(setOf<String>()) }
    var isRepairingMetadata by remember { mutableStateOf(false) }

    // Load data asynchronously on IO
    LaunchedEffect(refreshTick) {
        isLoading = true
        try {
            withContext(Dispatchers.IO) {
                // 1. ALWAYS load local tracks first so they are immediately visible even if network fails
                val tracks = cloudFileRepository.getLocalTracks()
                localTracks = tracks

                // 2. Fetch cloud files if network is reachable
                try {
                    cloudFileRepository.listCloudFiles().collect { result ->
                        result.onSuccess { items ->
                            cloudFiles = items
                        }.onFailure { err ->
                            Log.w("CloudSyncScreen", "Cloud list failed: ${err.message}")
                            scope.launch {
                                snackbarHostState.showSnackbar("Serveur Cloud inaccessible : ${err.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("CloudSyncScreen", "Failed to contact cloud server", e)
                }
            }
        } catch (e: Exception) {
            Log.e("CloudSyncScreen", "Failed to refresh state", e)
        } finally {
            isLoading = false
        }
    }

    // Calculations
    val totalCloudSizeBytes = remember(cloudFiles) { cloudFiles.sumOf { it.sizeBytes } }
    val totalCloudSizeMb = remember(totalCloudSizeBytes) {
        String.format("%.2f MB", totalCloudSizeBytes.toDouble() / (1024 * 1024))
    }
    val maxVpsLimitBytes = 20L * 1024L * 1024L * 1024L // 20 GB VPS reference
    val storageFraction = remember(totalCloudSizeBytes) {
        (totalCloudSizeBytes.toFloat() / maxVpsLimitBytes.toFloat()).coerceIn(0f, 1f)
    }

    val locallyStoredTracks = remember(localTracks) {
        localTracks.filter { !it.contentUri.isNullOrBlank() }
    }

    val localLookupIndex = remember(locallyStoredTracks) {
        TrackLookupIndex.build(locallyStoredTracks, emptyList(), emptySet())
    }

    val cloudLookupIndex = remember(cloudFiles) {
        TrackLookupIndex.build(emptyList(), cloudFiles, emptySet())
    }

    val pendingUploadTracks = remember(localTracks, cloudLookupIndex) {
        localTracks.filter { track ->
            val isLocal = track.contentUri?.startsWith("content://") == true || track.contentUri?.startsWith("file://") == true
            isLocal && !cloudLookupIndex.isCloudSynced(track.id, track.title, track.artistName, track.albumTitle)
        }
    }

    // Safe to clear = local tracks that are securely backed up on the Cloud
    val safeToClearTracks = remember(locallyStoredTracks, cloudLookupIndex) {
        locallyStoredTracks.filter { local ->
            cloudLookupIndex.isCloudSynced(local.id, local.title, local.artistName, local.albumTitle)
        }
    }
    val localOnlyTracks = remember(locallyStoredTracks, safeToClearTracks) {
        locallyStoredTracks.filter { it !in safeToClearTracks }
    }

    // Filtered Cloud Files for Tab 1
    val filteredCloudFiles = remember(cloudFiles, cloudSearchQuery, activeFilter, sortByFileSize, localLookupIndex) {
        val query = cloudSearchQuery.trim().lowercase()
        var list = if (query.isEmpty()) {
            cloudFiles
        } else {
            cloudFiles.filter { file ->
                (file.title?.lowercase()?.contains(query) == true) ||
                (file.artistName?.lowercase()?.contains(query) == true) ||
                (file.albumTitle?.lowercase()?.contains(query) == true)
            }
        }

        // Apply selected Filter Chip
        list = when (activeFilter) {
            CloudFilterType.ALL -> list
            CloudFilterType.CLOUD_ONLY -> list.filter { file ->
                localLookupIndex.findLocalMatch(file.trackId, file.title ?: "", file.artistName, file.albumTitle) == null
            }
            CloudFilterType.DOWNLOADED -> list.filter { file ->
                localLookupIndex.findLocalMatch(file.trackId, file.title ?: "", file.artistName, file.albumTitle) != null
            }
            CloudFilterType.HEAVY -> list.filter { it.sizeBytes >= 10L * 1024L * 1024L } // >= 10 MB
            CloudFilterType.RECENT -> list.sortedByDescending { it.uploadedAt ?: "" }.take(20)
        }

        if (sortByFileSize) {
            list.sortedByDescending { it.sizeBytes }
        } else if (activeFilter != CloudFilterType.RECENT) {
            list.sortedByDescending { it.uploadedAt ?: "" }
        } else {
            list
        }
    }

    RouteScaffold(
        title = "Cloud & Stockage",
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = { refreshTick++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Actualiser", tint = BlazeOrange)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepBlack)
        ) {
            // Tab Selector Row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkGraphite,
                contentColor = BlazeOrange,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = BlazeOrange
                        )
                    }
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "Vue d'ensemble",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 0) BlazeOrange else TextSecondary
                        )
                    },
                    icon = { Icon(Icons.Rounded.Storage, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "Explorateur Cloud (${cloudFiles.size})",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 1) BlazeOrange else TextSecondary
                        )
                    },
                    icon = { Icon(Icons.Rounded.Cloud, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            "Téléphone (${locallyStoredTracks.size})",
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 2) BlazeOrange else TextSecondary
                        )
                    },
                    icon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            if (isLoading && cloudFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BlazeOrange)
                }
            } else {
                when (selectedTab) {
                    // TAB 0: DASHBOARD / OVERVIEW
                    0 -> {
                        AuraLazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Storage Overview Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = ElevatedGraphite)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(Icons.Rounded.Cloud, contentDescription = null, tint = BlazeOrange)
                                                Text(
                                                    "Stockage Serveur Personnel",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                            }
                                            Text(
                                                totalCloudSizeMb,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = BlazeOrange
                                            )
                                        }

                                        LinearProgressIndicator(
                                            progress = { storageFraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp)),
                                            color = BlazeOrange,
                                            trackColor = DarkGraphite,
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "${cloudFiles.size} morceaux sur le serveur",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                            Text(
                                                "Limite standard: 20 GB",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }

                            // Quick Metadata Repair Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkGraphite)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(18.dp))
                                                Text("Réparer les métadonnées Cloud", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                                            }
                                            Text(
                                                "Synchronise les vrais titres, artistes et pochettes du téléphone vers le serveur pour réparer les 'Titres inconnus'.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isRepairingMetadata = true
                                                    cloudFileRepository.repairAndSyncAllCloudMetadata().collect { res ->
                                                        res.onSuccess { count ->
                                                            snackbarHostState.showSnackbar("Succès : $count métadonnées réparées sur le Cloud !")
                                                            refreshTick++
                                                        }.onFailure { err ->
                                                            snackbarHostState.showSnackbar("Erreur : ${err.message}")
                                                        }
                                                        isRepairingMetadata = false
                                                    }
                                                }
                                            },
                                            enabled = !isRepairingMetadata,
                                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange, contentColor = DeepBlack),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            if (isRepairingMetadata) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = DeepBlack, strokeWidth = 2.dp)
                                            } else {
                                                Text("Réparer", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            // Quick Stats Grid
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { selectedTab = 2 },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = DarkGraphite)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(20.dp))
                                            Text("${locallyStoredTracks.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                                            Text("Sur le téléphone (Hors-ligne)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                    }
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { showPendingUploads = !showPendingUploads },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (showPendingUploads) ElevatedGraphite else DarkGraphite
                                        ),
                                        border = if (showPendingUploads) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BlazeOrange)) else null
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Rounded.CloudUpload, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(20.dp))
                                                Icon(
                                                    if (showPendingUploads) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                    contentDescription = null,
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Text("${pendingUploadTracks.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                                            Text(
                                                if (pendingUploadTracks.isEmpty()) "À jour avec le Cloud" else "En attente d'upload (cliquer)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (pendingUploadTracks.isEmpty()) Color(0xFF00E676) else BlazeOrange
                                            )
                                        }
                                    }
                                }
                            }

                            // Expandable Pending Uploads Section
                            if (showPendingUploads) {
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = ElevatedGraphite)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "Titres non encore synchronisés (${pendingUploadTracks.size})",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                                if (pendingUploadTracks.isNotEmpty()) {
                                                    Button(
                                                        onClick = {
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar("Synchronisation de ${pendingUploadTracks.size} morceaux lancée...")
                                                                pendingUploadTracks.forEach { track ->
                                                                    cloudFileRepository.uploadTrack(track.id).collect { res ->
                                                                        res.onFailure {
                                                                            Log.e("CloudSync", "Failed to upload track ${track.id}: ${it.message}")
                                                                        }
                                                                    }
                                                                }
                                                                cloudFileRepository.refreshSyncedTrackIds()
                                                                refreshTick++
                                                                snackbarHostState.showSnackbar("Synchronisation terminée.")
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                                        shape = RoundedCornerShape(8.dp),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                    ) {
                                                        Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Tout uploader", style = MaterialTheme.typography.labelMedium)
                                                    }
                                                }
                                            }

                                            if (pendingUploadTracks.isEmpty()) {
                                                Text(
                                                    "Tous vos morceaux locaux sont déjà sauvegardés sur votre Cloud personnel !",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = Color(0xFF00E676)
                                                )
                                            } else {
                                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    pendingUploadTracks.forEach { track ->
                                                        val isUploading = activeOperations.contains(track.id)
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(DarkGraphite, RoundedCornerShape(10.dp))
                                                                .padding(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                        ) {
                                                            if (!track.coverUri.isNullOrBlank()) {
                                                                AsyncImage(
                                                                    model = track.coverUri,
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier
                                                                        .size(38.dp)
                                                                        .clip(RoundedCornerShape(6.dp))
                                                                )
                                                            } else {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .size(38.dp)
                                                                        .background(ElevatedGraphite, RoundedCornerShape(6.dp)),
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                                                                }
                                                            }

                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                Text(track.artistName ?: "Artiste inconnu", style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            }

                                                            if (isUploading) {
                                                                CircularProgressIndicator(color = BlazeOrange, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                                            } else {
                                                                IconButton(
                                                                    onClick = {
                                                                        scope.launch {
                                                                            activeOperations = activeOperations + track.id
                                                                            cloudFileRepository.uploadTrack(track.id).collect { res ->
                                                                                res.onSuccess {
                                                                                    snackbarHostState.showSnackbar("Upload réussi : ${track.title}")
                                                                                    cloudFileRepository.refreshSyncedTrackIds()
                                                                                    refreshTick++
                                                                                }.onFailure { err ->
                                                                                    snackbarHostState.showSnackbar("Échec : ${err.message}")
                                                                                }
                                                                                activeOperations = activeOperations - track.id
                                                                            }
                                                                        }
                                                                    },
                                                                    modifier = Modifier.size(32.dp)
                                                                ) {
                                                                    Icon(Icons.Rounded.CloudUpload, contentDescription = "Uploader", tint = BlazeOrange)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // TAB 1: EXPLORATEUR & NETTOYEUR DU CLOUD
                    1 -> {
                        AuraLazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Search bar & Sort
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = cloudSearchQuery,
                                        onValueChange = { cloudSearchQuery = it },
                                        placeholder = { Text("Rechercher un son sur le Cloud...", style = MaterialTheme.typography.bodyMedium) },
                                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary) },
                                        trailingIcon = {
                                            if (cloudSearchQuery.isNotEmpty()) {
                                                IconButton(onClick = { cloudSearchQuery = "" }) {
                                                    Icon(Icons.Rounded.Close, contentDescription = "Effacer")
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = BlazeOrange,
                                            unfocusedBorderColor = DarkGraphite,
                                            focusedContainerColor = ElevatedGraphite,
                                            unfocusedContainerColor = ElevatedGraphite
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )

                                    IconButton(
                                        onClick = { sortByFileSize = !sortByFileSize },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(if (sortByFileSize) BlazeOrange else DarkGraphite, RoundedCornerShape(12.dp))
                                    ) {
                                        Icon(
                                            Icons.Rounded.Sort,
                                            contentDescription = "Trier par taille",
                                            tint = if (sortByFileSize) Color.White else TextPrimary
                                        )
                                    }
                                }
                            }

                            // Filter Chips Row
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = activeFilter == CloudFilterType.ALL,
                                        onClick = { activeFilter = CloudFilterType.ALL },
                                        label = { Text("Tous (${cloudFiles.size})") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BlazeOrange,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                    FilterChip(
                                        selected = activeFilter == CloudFilterType.CLOUD_ONLY,
                                        onClick = { activeFilter = CloudFilterType.CLOUD_ONLY },
                                        label = { Text("Non téléchargés (Cloud seul)") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BlazeOrange,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                    FilterChip(
                                        selected = activeFilter == CloudFilterType.DOWNLOADED,
                                        onClick = { activeFilter = CloudFilterType.DOWNLOADED },
                                        label = { Text("Sur le téléphone") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BlazeOrange,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                    FilterChip(
                                        selected = activeFilter == CloudFilterType.HEAVY,
                                        onClick = { activeFilter = CloudFilterType.HEAVY },
                                        label = { Text("Fichiers lourds (> 10 Mo)") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BlazeOrange,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                    FilterChip(
                                        selected = activeFilter == CloudFilterType.RECENT,
                                        onClick = { activeFilter = CloudFilterType.RECENT },
                                        label = { Text("Récents") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BlazeOrange,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            // Items count indicator
                            item {
                                Text(
                                    "${filteredCloudFiles.size} fichier(s) correspondant(s)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }

                            if (filteredCloudFiles.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            if (cloudSearchQuery.isEmpty()) "Aucun fichier pour ce filtre" else "Aucun résultat trouvé sur le Cloud",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            } else {
                                items(
                                    items = filteredCloudFiles,
                                    key = { it.trackId }
                                ) { file ->
                                    val sizeMbStr = FastTimeFormatter.formatFileSize(file.sizeBytes)
                                    val isDownloaded = localLookupIndex.findLocalMatch(file.trackId, file.title ?: "", file.artistName, file.albumTitle) != null
                                    val isOperating = activeOperations.contains(file.trackId)

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
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
                                            if (!file.coverUri.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = file.coverUri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .background(DarkGraphite, RoundedCornerShape(8.dp)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TextSecondary)
                                                }
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    file.title ?: "Titre inconnu",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    listOfNotNull(file.artistName, file.albumTitle).joinToString(" • ").ifBlank { "Artiste inconnu" },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    "$sizeMbStr • ${if (isDownloaded) "Téléchargé sur le téléphone" else "Streamable (Cloud)"}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isDownloaded) Color(0xFF00E676) else BlazeOrange
                                                )
                                            }

                                            if (isOperating) {
                                                CircularProgressIndicator(color = BlazeOrange, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                            } else {
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    // Stream play button
                                                    IconButton(
                                                        onClick = {
                                                            if (playerViewModel != null) {
                                                                val queued = com.aura.music.domain.player.QueuedTrack(
                                                                    trackId = file.trackId,
                                                                    title = file.title ?: "Titre inconnu",
                                                                    artistName = file.artistName ?: "Artiste inconnu",
                                                                    albumTitle = file.albumTitle,
                                                                    contentUri = null,
                                                                    durationMs = file.durationMs?.toLong() ?: 0L,
                                                                    coverUri = file.coverUri,
                                                                    source = com.aura.music.domain.player.TrackSource.CONTEXT
                                                                )
                                                                playerViewModel.onEvent(
                                                                    PlayerEvent.PlayTrack(
                                                                        trackId = file.trackId,
                                                                        contextType = "cloud_explorer",
                                                                        contextId = "cloud",
                                                                        contextTracks = listOf(queued),
                                                                        startIndex = 0
                                                                    )
                                                                )
                                                            }
                                                        },
                                                        modifier = Modifier.size(36.dp)
                                                    ) {
                                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Écouter", tint = BlazeOrange)
                                                    }

                                                    // Download offline button (if not already downloaded)
                                                    if (!isDownloaded) {
                                                        IconButton(
                                                            onClick = {
                                                                scope.launch {
                                                                    activeOperations = activeOperations + file.trackId
                                                                    cloudFileRepository.downloadTrack(
                                                                        trackId = file.trackId,
                                                                        title = file.title ?: "",
                                                                        artistName = file.artistName,
                                                                        albumTitle = file.albumTitle,
                                                                        durationMs = file.durationMs,
                                                                        artistId = file.artistId,
                                                                        albumId = file.albumId,
                                                                        coverUri = file.coverUri
                                                                    ).collect { res ->
                                                                        res.onSuccess {
                                                                            snackbarHostState.showSnackbar("Téléchargé : ${file.title}")
                                                                            refreshTick++
                                                                        }.onFailure { err ->
                                                                            snackbarHostState.showSnackbar("Échec : ${err.message}")
                                                                        }
                                                                        activeOperations = activeOperations - file.trackId
                                                                    }
                                                                }
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(Icons.Rounded.Download, contentDescription = "Télécharger sur le téléphone", tint = TextPrimary)
                                                        }
                                                    }

                                                    // Delete from Cloud button
                                                    IconButton(
                                                        onClick = { trackToDeleteFromCloud = file },
                                                        modifier = Modifier.size(36.dp)
                                                    ) {
                                                        Icon(Icons.Rounded.Delete, contentDescription = "Supprimer du Cloud", tint = Color.Red.copy(alpha = 0.8f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // TAB 2: GESTIONNAIRE DE STOCKAGE LOCAL
                    2 -> {
                        AuraLazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = ElevatedGraphite)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text("Mémoire Téléphone (Mode Hors-ligne)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Text(
                                            "${locallyStoredTracks.size} piste(s) sont stockées physiquement sur votre smartphone.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary
                                        )
                                        Text(
                                            "• ${safeToClearTracks.size} piste(s) sont sécurisées sur le Cloud et peuvent être libérées sans risque.\n" +
                                            (if (localOnlyTracks.isNotEmpty()) "• ${localOnlyTracks.size} piste(s) sont uniquement sur ce téléphone et seront préservées." else "• Toutes vos pistes sont déjà sauvegardées sur le Cloud."),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (safeToClearTracks.isNotEmpty()) Color(0xFF00E676) else TextSecondary
                                        )

                                        if (safeToClearTracks.isNotEmpty()) {
                                            Button(
                                                onClick = { showClearLocalCacheDialog = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.85f)),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Libérer la mémoire (${safeToClearTracks.size} sons sur le Cloud)", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Text(
                                    "Pistes sur le téléphone (${locallyStoredTracks.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                                )
                            }

                            items(
                                items = locallyStoredTracks,
                                key = { it.id }
                            ) { track ->
                                val isBackedUp = safeToClearTracks.contains(track)
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
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
                                        Icon(
                                            if (isBackedUp) Icons.Rounded.CheckCircle else Icons.Rounded.PhoneAndroid,
                                            contentDescription = null,
                                            tint = if (isBackedUp) Color(0xFF00E676) else BlazeOrange,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(track.artistName ?: "Artiste inconnu", style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                if (isBackedUp) "✓ Sauvegardé sur le Cloud (Suppression sûre)" else "⚠️ Local uniquement (Non synchronisé au Cloud)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isBackedUp) Color(0xFF00E676) else BlazeOrange
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                if (isBackedUp) {
                                                    scope.launch {
                                                        cloudFileRepository.removeLocalFile(track.id)
                                                        refreshTick++
                                                        snackbarHostState.showSnackbar("Fichier supprimé de l'appareil.")
                                                    }
                                                } else {
                                                    trackToDeleteLocalOnly = track
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Supprimer du téléphone", tint = TextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Delete from Cloud Dialog
        if (trackToDeleteFromCloud != null) {
            val target = trackToDeleteFromCloud!!
            AlertDialog(
                onDismissRequest = { trackToDeleteFromCloud = null },
                title = { Text("Supprimer du Cloud ?") },
                text = {
                    Text("Voulez-vous supprimer définitivement \"${target.title}\" du serveur personnel ? Ce morceau ne sera plus streamable.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val fileId = target.trackId
                            trackToDeleteFromCloud = null
                            scope.launch {
                                cloudFileRepository.deleteSyncFile(fileId).collect { res ->
                                    res.onSuccess {
                                        snackbarHostState.showSnackbar("Morceau supprimé du Cloud avec succès.")
                                        cloudFileRepository.refreshSyncedTrackIds()
                                        refreshTick++
                                    }.onFailure { err ->
                                        snackbarHostState.showSnackbar("Erreur de suppression : ${err.message}")
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("Supprimer du Cloud")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { trackToDeleteFromCloud = null }) {
                        Text("Annuler")
                    }
                }
            )
        }

        // Clear Local Cache Confirmation Dialog (Safe tracks only)
        if (showClearLocalCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearLocalCacheDialog = false },
                title = { Text("Vider la mémoire locale ?") },
                text = {
                    Text("Seuls les ${safeToClearTracks.size} morceaux déjà sauvegardés sur le Cloud seront supprimés de la mémoire locale du téléphone. Vos musiques restent intactes sur le Cloud et streamables en ligne.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showClearLocalCacheDialog = false
                            scope.launch {
                                safeToClearTracks.forEach { track ->
                                    cloudFileRepository.removeLocalFile(track.id)
                                }
                                refreshTick++
                                snackbarHostState.showSnackbar("${safeToClearTracks.size} fichiers locaux supprimés avec succès.")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("Confirmer le nettoyage")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearLocalCacheDialog = false }) {
                        Text("Annuler")
                    }
                }
            )
        }

        // Warning when deleting a local-only track
        if (trackToDeleteLocalOnly != null) {
            val target = trackToDeleteLocalOnly!!
            AlertDialog(
                onDismissRequest = { trackToDeleteLocalOnly = null },
                title = { Text("Attention : Morceau non sauvegardé sur le Cloud") },
                text = {
                    Text("Le morceau \"${target.title}\" n'a pas été envoyé sur votre serveur Cloud. Si vous le supprimez de l'appareil, il sera définitivement perdu.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            trackToDeleteLocalOnly = null
                            scope.launch {
                                cloudFileRepository.removeLocalFile(target.id)
                                refreshTick++
                                snackbarHostState.showSnackbar("Fichier supprimé de l'appareil.")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("Supprimer définitivement")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { trackToDeleteLocalOnly = null }) {
                        Text("Annuler")
                    }
                }
            )
        }
    }
}
