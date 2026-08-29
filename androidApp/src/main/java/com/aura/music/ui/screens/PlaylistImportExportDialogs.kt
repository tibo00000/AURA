package com.aura.music.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.data.playlist.ExportReport
import com.aura.music.data.playlist.ImportReport
import com.aura.music.data.playlist.ImportStage
import com.aura.music.data.playlist.PlaylistImportExportManager
import com.aura.music.data.spotify.SpotifyAuthManager
import com.aura.music.ui.theme.AuraColors
import kotlinx.coroutines.launch

@Composable
fun PlaylistImportDialog(
    importManager: PlaylistImportExportManager,
    spotifyAuthManager: SpotifyAuthManager,
    onPlaylistCreated: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val isSpotifyConnected by spotifyAuthManager.isConnected.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var webUrlText by remember { mutableStateOf("") }
    var currentStage by remember { mutableStateOf<ImportStage?>(null) }
    var activeReport by remember { mutableStateOf<ImportReport?>(null) }
    var pendingImportUrl by remember { mutableStateOf<String?>(null) }

    // Auto-reprise si connexion Spotify réussie alors qu'un import était en attente
    LaunchedEffect(isSpotifyConnected) {
        val pending = pendingImportUrl
        if (isSpotifyConnected && !pending.isNullOrBlank()) {
            pendingImportUrl = null
            scope.launch {
                importManager.importFromWeb(pending).collect { stage ->
                    currentStage = stage
                    if (stage is ImportStage.ReadyToCreate) {
                        activeReport = stage.report
                    }
                }
            }
        }
    }

    // Sélecteur de fichier Android Document Provider
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                importManager.importFromFile(uri, context.contentResolver).collect { stage ->
                    currentStage = stage
                    if (stage is ImportStage.ReadyToCreate) {
                        activeReport = stage.report
                    }
                }
            }
        }
    }

    if (activeReport != null) {
        PlaylistImportSummaryDialog(
            report = activeReport!!,
            onConfirm = { customName ->
                scope.launch {
                    val plId = importManager.commitImport(activeReport!!, customName)
                    Toast.makeText(context, "Playlist « $customName » créée avec succès !", Toast.LENGTH_SHORT).show()
                    onPlaylistCreated(plId)
                    onDismiss()
                }
            },
            onDismiss = { activeReport = null }
        )
        return
    }

    AlertDialog(
        onDismissRequest = {
            if (currentStage !is ImportStage.FetchingMetadata && currentStage !is ImportStage.Reconciling) {
                onDismiss()
            }
        },
        containerColor = AuraColors.ElevatedGraphite,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AuraColors.BlazeOrange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlaylistAdd,
                        contentDescription = null,
                        tint = AuraColors.BlazeOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = "Importer une playlist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = AuraColors.TextPrimary
                    )
                    Text(
                        text = "Deezer, Spotify, M3U8, CSV ou TXT",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraColors.TextSecondary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = AuraColors.DarkGraphite,
                    contentColor = AuraColors.BlazeOrange,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = AuraColors.BlazeOrange
                        )
                    },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Lien Web", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Fichier", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                when (val stage = currentStage) {
                    is ImportStage.FetchingMetadata -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = AuraColors.BlazeOrange)
                            Text(
                                text = "Récupération des morceaux de la playlist...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AuraColors.TextSecondary
                            )
                        }
                    }
                    is ImportStage.Reconciling -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(AuraColors.DarkGraphite)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Analyse locale :",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AuraColors.TextPrimary
                                )
                                Text(
                                    text = "${stage.progress.processedCount} / ${stage.progress.totalCount}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AuraColors.BlazeOrange
                                )
                            }
                            LinearProgressIndicator(
                                progress = { stage.progress.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = AuraColors.BlazeOrange,
                                trackColor = AuraColors.ElevatedGraphite
                            )
                            Text(
                                text = stage.progress.currentTrackName,
                                style = MaterialTheme.typography.labelSmall,
                                color = AuraColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    is ImportStage.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(AuraColors.SemanticError.copy(alpha = 0.12f))
                                .border(1.dp, AuraColors.SemanticError.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = AuraColors.SemanticError)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Échec de l'import",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AuraColors.SemanticError
                                )
                            }
                            Text(
                                text = stage.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = AuraColors.TextPrimary
                            )
                            if (stage.requiresSpotifyAuth) {
                                Button(
                                    onClick = {
                                        pendingImportUrl = webUrlText
                                        spotifyAuthManager.startAuthFlow(context)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(42.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AuraColors.BlazeOrange, contentColor = AuraColors.DeepBlack)
                                ) {
                                    Text("Lier mon compte Spotify en 1 clic", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    else -> {
                        if (selectedTab == 0) {
                            // Onglet Lien Web
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = webUrlText,
                                    onValueChange = { webUrlText = it },
                                    label = { Text("Lien Deezer ou Spotify") },
                                    placeholder = { Text("https://open.spotify.com/... ou deezer.com/...") },
                                    singleLine = true,
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            val text = clipboardManager.getText()?.text
                                            if (!text.isNullOrBlank()) {
                                                webUrlText = text
                                            }
                                        }) {
                                            Icon(Icons.Rounded.ContentPaste, contentDescription = "Coller", tint = AuraColors.BlazeOrange)
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AuraColors.BlazeOrange,
                                        unfocusedBorderColor = AuraColors.HairlineDark,
                                        focusedContainerColor = AuraColors.DarkGraphite,
                                        unfocusedContainerColor = AuraColors.DarkGraphite,
                                        focusedTextColor = AuraColors.TextPrimary,
                                        unfocusedTextColor = AuraColors.TextPrimary
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Bannière Spotify Status
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(AuraColors.DarkGraphite)
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isSpotifyConnected) AuraColors.SemanticSuccess else AuraColors.TextMuted)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isSpotifyConnected) "Spotify lié" else "Spotify non lié",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AuraColors.TextPrimary
                                            )
                                        }
                                        if (isSpotifyConnected) {
                                            Text(
                                                text = "Déconnecter",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AuraColors.SemanticError,
                                                modifier = Modifier.clickable { spotifyAuthManager.disconnectSpotify() }
                                            )
                                        } else {
                                            Text(
                                                text = "Lier mon compte",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = AuraColors.BlazeOrange,
                                                modifier = Modifier.clickable {
                                                    spotifyAuthManager.startAuthFlow(context)
                                                }
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (webUrlText.isNotBlank()) {
                                            scope.launch {
                                                importManager.importFromWeb(webUrlText).collect { stage ->
                                                    currentStage = stage
                                                    if (stage is ImportStage.ReadyToCreate) {
                                                        activeReport = stage.report
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = webUrlText.isNotBlank(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AuraColors.BlazeOrange,
                                        contentColor = AuraColors.DeepBlack,
                                        disabledContainerColor = AuraColors.ElevatedGraphite,
                                        disabledContentColor = AuraColors.TextMuted
                                    )
                                ) {
                                    Text("Analyser et Importer", fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            // Onglet Fichier
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(AuraColors.DarkGraphite)
                                        .border(1.dp, AuraColors.HairlineDark, RoundedCornerShape(16.dp))
                                        .clickable {
                                            filePickerLauncher.launch(
                                                arrayOf(
                                                    "audio/x-mpegurl",
                                                    "application/vnd.apple.mpegurl",
                                                    "text/plain",
                                                    "text/csv",
                                                    "application/octet-stream",
                                                    "*/*"
                                                )
                                            )
                                        }
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Rounded.FolderOpen,
                                            contentDescription = null,
                                            tint = AuraColors.BlazeOrange,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Sélectionner un fichier .m3u8, .csv ou .txt",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = AuraColors.TextPrimary
                                        )
                                        Text(
                                            text = "Encodage UTF-8/BOM/Latin-1 auto-détecté",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AuraColors.TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = currentStage !is ImportStage.FetchingMetadata && currentStage !is ImportStage.Reconciling
            ) {
                Text("Fermer", color = AuraColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
fun PlaylistImportSummaryDialog(
    report: ImportReport,
    onConfirm: (customName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var playlistName by remember { mutableStateOf(report.playlistName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraColors.ElevatedGraphite,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Résumé de l'import",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AuraColors.TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Nom de la playlist") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AuraColors.BlazeOrange,
                        unfocusedBorderColor = AuraColors.HairlineDark,
                        focusedContainerColor = AuraColors.DarkGraphite,
                        unfocusedContainerColor = AuraColors.DarkGraphite,
                        focusedTextColor = AuraColors.TextPrimary,
                        unfocusedTextColor = AuraColors.TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AuraColors.DarkGraphite)
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total des titres :", color = AuraColors.TextSecondary)
                            Text("${report.totalTracks} morceaux", fontWeight = FontWeight.Bold, color = AuraColors.TextPrimary)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = AuraColors.SemanticSuccess, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Déjà sur votre téléphone :", color = AuraColors.TextSecondary)
                            }
                            Text("${report.matchedLocalCount} trouvés", fontWeight = FontWeight.Bold, color = AuraColors.SemanticSuccess)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = AuraColors.BlazeOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("À télécharger / Cloud :", color = AuraColors.TextSecondary)
                            }
                            Text("${report.missingCount} titres", fontWeight = FontWeight.Bold, color = AuraColors.BlazeOrange)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(playlistName.ifBlank { report.playlistName }) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuraColors.BlazeOrange,
                    contentColor = AuraColors.DeepBlack
                )
            ) {
                Text("Créer la playlist", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = AuraColors.TextSecondary)
            }
        }
    )
}

@Composable
fun PlaylistExportReportDialog(
    report: ExportReport,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraColors.ElevatedGraphite,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = AuraColors.SemanticSuccess, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Playlist exportée !", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AuraColors.TextPrimary)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Le fichier .m3u8 standard a été généré avec succès.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraColors.TextSecondary
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AuraColors.DarkGraphite)
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Fichier : ${report.outputFile.name}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = AuraColors.TextPrimary)
                        Text("• ${report.fullPathCount} morceaux avec chemin physique complet", style = MaterialTheme.typography.bodySmall, color = AuraColors.SemanticSuccess)
                        if (report.metadataOnlyCount > 0) {
                            Text("• ${report.metadataOnlyCount} morceaux avec métadonnées relatives", style = MaterialTheme.typography.bodySmall, color = AuraColors.TextMuted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/x-mpegurl"
                        putExtra(Intent.EXTRA_STREAM, report.shareableUri)
                        putExtra(Intent.EXTRA_SUBJECT, report.playlistName)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Partager la playlist .m3u8"))
                    onDismiss()
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AuraColors.BlazeOrange, contentColor = AuraColors.DeepBlack)
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Partager / Ouvrir", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Fermer", color = AuraColors.TextSecondary)
            }
        }
    )
}
