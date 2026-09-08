package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.TrackListRow
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.domain.search.SearchNormalizer
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CloudSyncScreen(
    allTracks: List<TrackListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onReloadData: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedFilter by remember { mutableStateOf(0) } // 0 = À récupérer, 1 = À sauvegarder, 2 = Tout
    var searchQuery by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    val cloudFileIds by orchestrator.cloudFileIds.collectAsState()
    var trackToDeleteFromCloud by remember { mutableStateOf<TrackListRow?>(null) }

    val cloudOnlyTracks = remember(allTracks) { allTracks.filter { it.isCloudOnly } }
    val localOnlyTracks = remember(allTracks, cloudFileIds) { allTracks.filter { !it.isCloudOnly && !cloudFileIds.contains(it.id) } }

    val baseTracks = when (selectedFilter) {
        0 -> cloudOnlyTracks
        1 -> localOnlyTracks
        else -> allTracks
    }

    val filteredTracks = remember(baseTracks, searchQuery) {
        val q = SearchNormalizer.normalize(searchQuery).trim()
        if (q.isBlank()) {
            baseTracks
        } else {
            baseTracks.filter {
                SearchNormalizer.normalize(it.title).contains(q) ||
                SearchNormalizer.normalize(it.artistName).contains(q) ||
                (it.albumTitle != null && SearchNormalizer.normalize(it.albumTitle!!).contains(q))
            }
        }
    }

    val totalCloudCount = remember(allTracks, cloudFileIds) {
        allTracks.count { it.isCloudOnly || cloudFileIds.contains(it.id) }
    }
    val usedMb = remember(totalCloudCount) { (totalCloudCount * 8).coerceAtLeast(120) } // estimation ~8Mo par titre
    val totalMb = 20480 // 20 Go (aligné sur mobile)

    var showConfirmDownloadAllDialog by remember { mutableStateOf(false) }
    var showConfirmUploadAllDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // =====================================================================
        // 1. EN-TÊTE SUPÉRIEUR : Titre, navigation retour et bouton Sync
        // =====================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (appState.canNavigateBack) {
                    IconButton(
                        onClick = { appState.navigateBack() },
                        modifier = Modifier.size(36.dp).handCursor()
                    ) {
                        Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = "Retour", tint = PureWhite)
                    }
                }
                Column {
                    Text(
                        text = "Gestionnaire Cloud",
                        color = PureWhite,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Stockage VPS privé et synchronisation multi-appareils",
                        color = PureWhite.copy(alpha = 0.45f),
                        fontSize = 12.sp
                    )
                }
            }

            // Bouton de synchronisation immédiate avec info-bulle
            AuraTooltip(text = "Synchroniser les favoris, playlists et métadonnées avec le Cloud") {
                Button(
                    onClick = {
                        orchestrator.apiToken?.let { token ->
                            isSyncing = true
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    orchestrator.syncCloudData(token) {
                                        onReloadData()
                                    }
                                } finally {
                                    isSyncing = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, if (isSyncing) BlazeOrange else HairlineDark),
                    modifier = Modifier.height(36.dp).handCursor(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    enabled = !isSyncing
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(color = BlazeOrange, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Synchronisation...", color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    } else {
                        Icon(imageVector = Icons.Rounded.Sync, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Synchroniser", color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // =====================================================================
        // 2. BANDEAU HORIZONTAL DE SYNTHÈSE (2 Grandes Cartes Équilibrées)
        // =====================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Carte 1 : Stockage VPS (Limite 20 Go alignée sur mobile)
            Card(
                modifier = Modifier.weight(1f).height(128.dp).clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = OffBlack),
                border = BorderStroke(1.dp, HairlineDark)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(imageVector = Icons.Rounded.Cloud, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(16.dp))
                            Text("STOCKAGE CLOUD VPS", color = PureWhite.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        Text(
                            text = "${((usedMb.toFloat() / totalMb) * 100).toInt()}%",
                            color = BlazeOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${"%.1f".format(usedMb / 1024f)} Go",
                            color = PureWhite,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(text = "sur 20,0 Go alloués", color = PureWhite.copy(alpha = 0.45f), fontSize = 12.sp)
                    }

                    LinearProgressIndicator(
                        progress = { (usedMb.toFloat() / totalMb).coerceIn(0f, 1f) },
                        color = BlazeOrange,
                        trackColor = DarkGraphite,
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                    )

                    Text(
                        text = "${cloudOnlyTracks.size} sur le Cloud • ${localOnlyTracks.size} sur ce PC",
                        color = PureWhite.copy(alpha = 0.55f),
                        fontSize = 11.sp
                    )
                }
            }

            // Carte 2 : Actions Massives Sécurisées
            Card(
                modifier = Modifier.weight(1f).height(128.dp).clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = OffBlack),
                border = BorderStroke(1.dp, HairlineDark)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(imageVector = Icons.Rounded.Bolt, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(16.dp))
                            Text("ACTIONS MASSIVES", color = PureWhite.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        AuraTooltip(text = "Toutes les actions globales demandent une confirmation avant exécution") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .background(BlazeOrange.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Icon(imageVector = Icons.Rounded.Shield, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(12.dp))
                                Text("Sécurisé", color = BlazeOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AuraTooltip(
                            text = "Rapatrier l'intégralité des ${cloudOnlyTracks.size} titres distants sur ce PC",
                            modifier = Modifier.weight(1f)
                        ) {
                            Button(
                                onClick = { showConfirmDownloadAllDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp).handCursor(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                enabled = cloudOnlyTracks.isNotEmpty()
                            ) {
                                Icon(imageVector = Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Rapatrier tout (${cloudOnlyTracks.size})", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }

                        AuraTooltip(
                            text = "Sauvegarder l'intégralité des ${localOnlyTracks.size} titres locaux sur le Cloud VPS",
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedButton(
                                onClick = { showConfirmUploadAllDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite),
                                border = BorderStroke(1.dp, HairlineDark),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp).handCursor(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                enabled = localOnlyTracks.isNotEmpty()
                            ) {
                                Icon(imageVector = Icons.Rounded.CloudUpload, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sauvegarder tout (${localOnlyTracks.size})", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }

                    Text(
                        text = "${cloudOnlyTracks.size} distants • ${localOnlyTracks.size} locaux",
                        color = PureWhite.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // =====================================================================
        // 3. BARRE D'OUTILS FILTRES & RECHERCHE
        // =====================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Filtres sous forme de pastilles harmonisées avec la Bibliothèque
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = DarkGraphite.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, HairlineDark),
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(
                        "À récupérer (${cloudOnlyTracks.size})" to 0,
                        "À sauvegarder (${localOnlyTracks.size})" to 1,
                        "Tous les titres (${allTracks.size})" to 2
                    ).forEach { (label, filterIndex) ->
                        val isSelected = selectedFilter == filterIndex
                        val interactionSource = remember { MutableInteractionSource() }
                        val isHovered by interactionSource.collectIsHoveredAsState()

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(15.dp))
                                .background(
                                    when {
                                        isSelected -> BlazeOrange.copy(alpha = 0.20f)
                                        isHovered -> PureWhite.copy(alpha = 0.05f)
                                        else -> Color.Transparent
                                    }
                                )
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) BlazeOrange.copy(alpha = 0.70f) else Color.Transparent,
                                    shape = RoundedCornerShape(15.dp)
                                )
                                .hoverable(interactionSource)
                                .handClickable(interactionSource = interactionSource) {
                                    selectedFilter = filterIndex
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) BlazeOrange else PureWhite.copy(alpha = if (isHovered) 0.85f else 0.55f),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Champ de filtrage instantané par nom
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = DarkGraphite.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, if (searchQuery.isNotEmpty()) BlazeOrange.copy(alpha = 0.5f) else HairlineDark),
                modifier = Modifier.width(240.dp).height(36.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = if (searchQuery.isNotEmpty()) BlazeOrange else PureWhite.copy(alpha = 0.45f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text("Filtrer ces titres...", color = PureWhite.copy(alpha = 0.35f), fontSize = 12.sp)
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(color = PureWhite, fontSize = 12.sp),
                            cursorBrush = SolidColor(BlazeOrange),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(18.dp).handCursor()) {
                            Icon(imageVector = Icons.Rounded.Close, contentDescription = "Effacer", tint = PureWhite.copy(alpha = 0.5f), modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 4. TABLEAU STRUCTURÉ DES FICHIERS CLOUD (Pleine largeur)
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(OffBlack)
                .border(1.dp, HairlineDark, RoundedCornerShape(12.dp))
        ) {
            // En-tête du tableau
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(DarkGraphite.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#",
                    color = PureWhite.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "TITRE",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(2.5f).padding(end = 16.dp)
                )
                Text(
                    text = "ARTISTE",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1.8f)
                )
                Text(
                    text = "ALBUM",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1.8f)
                )
                Text(
                    text = "STATUT",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1.2f)
                )
                Box(modifier = Modifier.width(92.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = "ACTION",
                        color = PureWhite.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            HorizontalDivider(color = HairlineDark, thickness = 1.dp)

            // Corps du tableau
            if (filteredTracks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDone,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Aucun titre correspondant au filtre" else "Tous les morceaux sont synchronisés !",
                            color = PureWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Essayez une autre recherche" else "Votre bibliothèque locale et votre VPS sont à jour.",
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(filteredTracks, key = { index, track -> "${track.id}_$index" }) { index, track ->
                        val isOnCloud = track.isCloudOnly || cloudFileIds.contains(track.id)
                        CloudTrackTableRow(
                            index = index + 1,
                            track = track,
                            isOnCloud = isOnCloud,
                            onOpenArtist = { appState.openArtist(track.artistId ?: "artist:${track.artistName}") },
                            onOpenAlbum = { track.albumId?.let { appState.openAlbum(it) } },
                            onDownload = { orchestrator.triggerSingleFileDownload(track) },
                            onUpload = { orchestrator.triggerSingleFileUpload(track) },
                            onDeleteCloud = { trackToDeleteFromCloud = track }
                        )
                    }
                }
            }
        }

        if (showConfirmDownloadAllDialog) {
            CloudActionConfirmDialog(
                title = "Rapatrier tous les titres ?",
                message = "Vous êtes sur le point de télécharger ${cloudOnlyTracks.size} titre${if (cloudOnlyTracks.size > 1) "s" else ""} depuis votre Cloud VPS vers votre PC.",
                detailText = "Volume estimé : ~${(cloudOnlyTracks.size * 8)} Mo. Les fichiers seront stockés localement et lisibles sans connexion internet.",
                confirmText = "Confirmer le téléchargement",
                confirmIcon = Icons.Rounded.Download,
                onConfirm = {
                    showConfirmDownloadAllDialog = false
                    orchestrator.triggerCloudDownloadAll(cloudOnlyTracks)
                },
                onDismiss = { showConfirmDownloadAllDialog = false }
            )
        }

        if (showConfirmUploadAllDialog) {
            CloudActionConfirmDialog(
                title = "Sauvegarder tous les titres ?",
                message = "Vous êtes sur le point d'envoyer ${localOnlyTracks.size} titre${if (localOnlyTracks.size > 1) "s" else ""} locaux vers votre serveur Cloud VPS.",
                detailText = "Vos fichiers audio seront envoyés sur votre serveur privé AURA et deviendront immédiatement accessibles sur votre mobile et vos autres appareils.",
                confirmText = "Confirmer l'envoi",
                confirmIcon = Icons.Rounded.CloudUpload,
                onConfirm = {
                    showConfirmUploadAllDialog = false
                    orchestrator.triggerCloudUploadAll(localOnlyTracks)
                },
                onDismiss = { showConfirmUploadAllDialog = false }
            )
        }

        if (trackToDeleteFromCloud != null) {
            val track = trackToDeleteFromCloud!!
            CloudActionConfirmDialog(
                title = "Supprimer du Cloud VPS ?",
                message = "Êtes-vous sûr de vouloir supprimer « ${track.title} » de votre serveur Cloud ?",
                detailText = if (track.isCloudOnly) {
                    "Ce titre n'étant pas stocké sur votre PC, il sera définitivement retiré de votre bibliothèque."
                } else {
                    "Le fichier audio restera intact sur votre PC, mais il ne sera plus synchronisé ni accessible sur vos autres appareils."
                },
                confirmText = "Supprimer du Cloud",
                confirmIcon = Icons.Rounded.Delete,
                confirmColor = Color(0xFFE53935),
                onConfirm = {
                    val toDelete = track
                    trackToDeleteFromCloud = null
                    coroutineScope.launch(Dispatchers.IO) {
                        orchestrator.apiToken?.let { token ->
                            orchestrator.deleteCloudTrack(token, toDelete.id)
                            onReloadData()
                        }
                    }
                },
                onDismiss = { trackToDeleteFromCloud = null }
            )
        }
    }
}

@Composable
private fun CloudTrackTableRow(
    index: Int,
    track: TrackListRow,
    isOnCloud: Boolean,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onDownload: () -> Unit,
    onUpload: () -> Unit,
    onDeleteCloud: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val artistInteractionSource = remember { MutableInteractionSource() }
    val isArtistHovered by artistInteractionSource.collectIsHoveredAsState()

    val albumInteractionSource = remember { MutableInteractionSource() }
    val isAlbumHovered by albumInteractionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(if (isHovered) DarkGraphite.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // #
        Text(
            text = "$index",
            color = PureWhite.copy(alpha = 0.45f),
            fontSize = 12.sp,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Center
        )

        // Titre + Cover
        Row(
            modifier = Modifier.weight(2.5f).padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DesktopArtworkCover(coverUri = track.coverUri, size = 36.dp, shapeRadius = 4.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = track.title,
                color = if (isHovered) PureWhite else PureWhite.copy(alpha = 0.88f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (track.isCloudOnly) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Rounded.Cloud,
                    contentDescription = "Sur le Cloud",
                    tint = BlazeOrange,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // Artiste
        Box(modifier = Modifier.weight(1.8f), contentAlignment = Alignment.CenterStart) {
            Text(
                text = track.displayArtist,
                color = when {
                    isArtistHovered -> PureWhite
                    isHovered -> PureWhite.copy(alpha = 0.70f)
                    else -> PureWhite.copy(alpha = 0.55f)
                },
                textDecoration = if (isArtistHovered) TextDecoration.Underline else TextDecoration.None,
                fontSize = 13.sp,
                fontWeight = if (isArtistHovered) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.handClickable(interactionSource = artistInteractionSource, onClick = onOpenArtist)
            )
        }

        // Album
        Box(modifier = Modifier.weight(1.8f), contentAlignment = Alignment.CenterStart) {
            if (!track.displayAlbum.isNullOrBlank()) {
                Text(
                    text = track.displayAlbum!!,
                    color = when {
                        isAlbumHovered -> PureWhite
                        isHovered -> PureWhite.copy(alpha = 0.65f)
                        else -> PureWhite.copy(alpha = 0.45f)
                    },
                    textDecoration = if (isAlbumHovered) TextDecoration.Underline else TextDecoration.None,
                    fontSize = 13.sp,
                    fontWeight = if (isAlbumHovered) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.handClickable(interactionSource = albumInteractionSource, onClick = onOpenAlbum)
                )
            } else {
                Text(text = "-", color = PureWhite.copy(alpha = 0.35f), fontSize = 13.sp)
            }
        }

        // Statut
        Box(modifier = Modifier.weight(1.2f), contentAlignment = Alignment.CenterStart) {
            val isSyncedOnBoth = !track.isCloudOnly && isOnCloud
            val statusTooltip = when {
                track.isCloudOnly -> "Sur le Cloud : stocké sur votre VPS (non téléchargé en local)"
                isSyncedOnBoth -> "Synchronisé : présent sur ce PC et sauvegardé sur le Cloud VPS"
                else -> "Sur ce PC : stocké localement et disponible hors-ligne"
            }
            AuraTooltip(text = statusTooltip) {
                Surface(
                    color = when {
                        track.isCloudOnly -> BlazeOrange.copy(alpha = 0.15f)
                        isSyncedOnBoth -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                        else -> DarkGraphite
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        when {
                            track.isCloudOnly -> BlazeOrange.copy(alpha = 0.4f)
                            isSyncedOnBoth -> Color(0xFF4CAF50).copy(alpha = 0.4f)
                            else -> HairlineDark
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                track.isCloudOnly -> Icons.Rounded.Cloud
                                isSyncedOnBoth -> Icons.Rounded.CloudDone
                                else -> Icons.Rounded.CheckCircle
                            },
                            contentDescription = null,
                            tint = when {
                                track.isCloudOnly -> BlazeOrange
                                isSyncedOnBoth -> Color(0xFF4CAF50)
                                else -> Color(0xFF4CAF50)
                            },
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = when {
                                track.isCloudOnly -> "Cloud"
                                isSyncedOnBoth -> "Sync"
                                else -> "Local"
                            },
                            color = when {
                                track.isCloudOnly -> BlazeOrange
                                isSyncedOnBoth -> Color(0xFF4CAF50)
                                else -> PureWhite.copy(alpha = 0.75f)
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Action
        Box(modifier = Modifier.width(92.dp), contentAlignment = Alignment.CenterEnd) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterEnd)
            ) {
                if (track.isCloudOnly) {
                    AuraTooltip(text = "Rapatrier sur ce PC") {
                        Surface(
                            color = BlazeOrange,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(30.dp)
                                .handClickable(onClick = onDownload)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Rapatrier",
                                    tint = PureWhite,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    AuraTooltip(text = "Supprimer du Cloud VPS") {
                        Surface(
                            color = DarkGraphite,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, HairlineDark),
                            modifier = Modifier
                                .size(30.dp)
                                .handClickable(onClick = onDeleteCloud)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Supprimer du Cloud",
                                    tint = Color(0xFFE57373),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                } else if (isOnCloud) {
                    AuraTooltip(text = "Supprimer du Cloud VPS (conserver en local)") {
                        Surface(
                            color = DarkGraphite,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, HairlineDark),
                            modifier = Modifier
                                .size(30.dp)
                                .handClickable(onClick = onDeleteCloud)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Supprimer du Cloud",
                                    tint = Color(0xFFE57373),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                } else {
                    AuraTooltip(text = "Sauvegarder sur le Cloud VPS") {
                        Surface(
                            color = DarkGraphite,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, HairlineDark),
                            modifier = Modifier
                                .size(30.dp)
                                .handClickable(onClick = onUpload)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = "Sauvegarder",
                                    tint = BlazeOrange,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudActionConfirmDialog(
    title: String,
    message: String,
    detailText: String,
    confirmText: String,
    confirmIcon: androidx.compose.ui.graphics.vector.ImageVector,
    confirmColor: Color = BlazeOrange,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(confirmColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = confirmIcon,
                        contentDescription = null,
                        tint = confirmColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = title,
                    color = PureWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = message,
                    color = PureWhite.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Surface(
                    color = DarkGraphite,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, HairlineDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = detailText,
                        color = PureWhite.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = confirmColor),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.handCursor()
            ) {
                Icon(imageVector = confirmIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(confirmText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = PureWhite.copy(alpha = 0.7f)),
                modifier = Modifier.handCursor()
            ) {
                Text("Annuler", fontSize = 13.sp)
            }
        },
        containerColor = OffBlack,
        shape = RoundedCornerShape(16.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AuraTooltip(
    text: String,
    modifier: Modifier = Modifier,
    delayMillis: Int = 400,
    content: @Composable () -> Unit
) {
    TooltipArea(
        tooltip = {
            Surface(
                color = OffBlack,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, HairlineDark),
                shadowElevation = 8.dp
            ) {
                Text(
                    text = text,
                    color = PureWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        },
        delayMillis = delayMillis,
        tooltipPlacement = TooltipPlacement.CursorPoint(
            offset = DpOffset(0.dp, 16.dp)
        ),
        modifier = modifier
    ) {
        content()
    }
}
