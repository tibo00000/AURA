package com.aura.music.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.aura.music.data.playlist.PlaylistSourceReference
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.data.playlist.ExportReport
import com.aura.music.data.playlist.ImportReport
import com.aura.music.data.playlist.ImportStage
import com.aura.music.data.playlist.PlaylistImportExportManager
import com.aura.music.data.playlist.SpotifyPlaylistSummary
import com.aura.music.data.spotify.SpotifyAuthManager
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.HairlineDark
import com.aura.music.ui.theme.SemanticError
import com.aura.music.ui.theme.SemanticSuccess
import com.aura.music.ui.theme.TextMuted
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun PlaylistSourceDialog(
    importManager: PlaylistImportExportManager,
    spotifyAuthManager: SpotifyAuthManager,
    onDismiss: () -> Unit,
    onConfirm: (PlaylistSourceReference) -> Unit,
) {
    var link by remember { mutableStateOf("") }
    var showSpotify by remember { mutableStateOf(false) }
    val connected by spotifyAuthManager.isConnected.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val spotifyPlaylists by produceState<List<com.aura.music.data.playlist.SpotifyPlaylistSummary>?>(
        null, showSpotify, connected,
    ) {
        value = if (showSpotify && connected) importManager.getUserSpotifyPlaylists().filterNot { it.isLikedSongs }
        else null
    }
    val reference = remember(link) { PlaylistSourceReference.parse(link) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Source de la playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Lien Spotify ou Deezer") },
                    singleLine = true,
                    isError = link.isNotBlank() && reference == null,
                    supportingText = {
                        if (link.isNotBlank() && reference == null) {
                            Text("Utilise le lien complet d’une playlist.")
                        }
                    },
                )
                TextButton(onClick = {
                    showSpotify = true
                    if (!connected) spotifyAuthManager.startAuthFlow(context)
                }) {
                    Text(if (connected) "Choisir une playlist Spotify" else "Connecter Spotify")
                }
                if (showSpotify && connected) {
                    val playlists = spotifyPlaylists
                    when {
                        playlists == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        playlists.isEmpty() -> Text("Aucune playlist Spotify accessible.")
                        else -> LazyColumn(Modifier.heightIn(max = 200.dp)) {
                            items(playlists, key = { it.id }) { playlist ->
                                TextButton(onClick = { link = "spotify:playlist:${playlist.id}" }) {
                                    Text(playlist.name)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = reference != null, onClick = { reference?.let(onConfirm) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

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
    var showCustomUrlField by remember { mutableStateOf(false) }

    // Chargement dynamique des playlists Spotify du compte
    var refreshSpotifyPlaylistsTick by remember { mutableIntStateOf(0) }
    val userSpotifyPlaylistsState = produceState<List<SpotifyPlaylistSummary>?>(
        initialValue = null,
        isSpotifyConnected,
        refreshSpotifyPlaylistsTick
    ) {
        if (isSpotifyConnected) {
            value = importManager.getUserSpotifyPlaylists()
        } else {
            value = emptyList()
        }
    }

    // Auto-reprise si connexion Spotify réussie alors qu'un import était en attente
    LaunchedEffect(isSpotifyConnected) {
        val pending = pendingImportUrl
        if (isSpotifyConnected && !pending.isNullOrBlank()) {
            pendingImportUrl = null
            scope.launch {
                try {
                    importManager.importFromWeb(pending).collect { stage ->
                        currentStage = stage
                        if (stage is ImportStage.ReadyToCreate) {
                            activeReport = stage.report
                        }
                    }
                } catch (e: Exception) {
                    currentStage = ImportStage.Error(e.message ?: "Erreur inattendue")
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
                try {
                    importManager.importFromFile(uri, context.contentResolver).collect { stage ->
                        currentStage = stage
                        if (stage is ImportStage.ReadyToCreate) {
                            activeReport = stage.report
                        }
                    }
                } catch (e: Exception) {
                    currentStage = ImportStage.Error(e.message ?: "Erreur inattendue lors de la lecture du fichier")
                }
            }
        }
    }

    if (activeReport != null) {
        var isCommitting by remember { mutableStateOf(false) }
        var committingProgress by remember { mutableStateOf<Pair<Int, String>?>(null) }

        PlaylistImportSummaryDialog(
            report = activeReport!!,
            isSubmitting = isCommitting,
            submitProgress = committingProgress,
            onConfirm = { customName ->
                isCommitting = true
                committingProgress = Pair(0, "Initialisation...")
                scope.launch {
                    try {
                        val plId = importManager.commitImport(activeReport!!, customName) { current, _, name ->
                            committingProgress = Pair(current, name)
                        }
                        Toast.makeText(context, "Playlist « $customName » créée avec succès !", Toast.LENGTH_SHORT).show()
                        onPlaylistCreated(plId)
                        onDismiss()
                    } catch (e: com.aura.music.data.playlist.PlaylistImportCreatedException) {
                        Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                        onPlaylistCreated(e.playlistId)
                        onDismiss()
                    } catch (e: Exception) {
                        if (e !is kotlinx.coroutines.CancellationException) {
                            Toast.makeText(context, "Erreur création playlist : ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        isCommitting = false
                        committingProgress = null
                    }
                }
            },
            onDismiss = {
                if (!isCommitting) {
                    activeReport = null
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = {
            if (currentStage !is ImportStage.FetchingMetadata && currentStage !is ImportStage.Reconciling) {
                onDismiss()
            }
        },
        containerColor = ElevatedGraphite,
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
                        .background(BlazeOrange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        tint = BlazeOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = "Importer une playlist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Spotify, Deezer, M3U8, CSV ou TXT",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DarkGraphite,
                    contentColor = BlazeOrange,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = BlazeOrange
                        )
                    },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Spotify & Liens", fontWeight = FontWeight.Bold) },
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
                                .padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = BlazeOrange)
                            Text(
                                text = "Récupération des morceaux...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }
                    is ImportStage.Reconciling -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(DarkGraphite)
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
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${stage.progress.processedCount} / ${stage.progress.totalCount}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BlazeOrange
                                )
                            }
                            LinearProgressIndicator(
                                progress = { stage.progress.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = BlazeOrange,
                                trackColor = ElevatedGraphite
                            )
                            Text(
                                text = stage.progress.currentTrackName,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
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
                                .background(SemanticError.copy(alpha = 0.12f))
                                .border(1.dp, SemanticError.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = SemanticError)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Échec de l'import",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SemanticError
                                )
                            }
                            Text(
                                text = stage.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                            if (stage.requiresSpotifyAuth) {
                                Button(
                                    onClick = {
                                        pendingImportUrl = webUrlText
                                        spotifyAuthManager.startAuthFlow(context)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(42.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange, contentColor = DeepBlack)
                                ) {
                                    Text("Lier mon compte Spotify en 1 clic", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { currentStage = null },
                                    modifier = Modifier.fillMaxWidth().height(38.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, HairlineDark),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                                ) {
                                    Text("Réessayer", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    else -> {
                        if (selectedTab == 0) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Bannière Statut Spotify
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(DarkGraphite)
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
                                                    .background(if (isSpotifyConnected) SemanticSuccess else TextMuted)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isSpotifyConnected) "Compte Spotify lié" else "Spotify non connecté",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                        }
                                        if (isSpotifyConnected) {
                                            Text(
                                                text = "Déconnecter",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SemanticError,
                                                modifier = Modifier.clickable {
                                                    spotifyAuthManager.disconnectSpotify()
                                                    refreshSpotifyPlaylistsTick++
                                                }
                                            )
                                        } else {
                                            Text(
                                                text = "Connecter",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = BlazeOrange,
                                                modifier = Modifier.clickable {
                                                    spotifyAuthManager.startAuthFlow(context)
                                                }
                                            )
                                        }
                                    }
                                }

                                if (isSpotifyConnected) {
                                    // Liste visuelle des Playlists Spotify du compte
                                    val userPlaylists = userSpotifyPlaylistsState.value
                                    if (userPlaylists == null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(140.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                color = BlazeOrange,
                                                strokeWidth = 3.dp
                                            )
                                        }
                                    } else if (userPlaylists.isNotEmpty()) {
                                        Text(
                                            text = "Vos playlists Spotify :",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextSecondary
                                        )

                                        LazyColumn(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 240.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(DarkGraphite)
                                                .padding(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            items(userPlaylists, key = { it.id }) { item ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(ElevatedGraphite)
                                                        .clickable {
                                                            scope.launch {
                                                                try {
                                                                    importManager.importFromSpotifySelection(item).collect { stage ->
                                                                        currentStage = stage
                                                                        if (stage is ImportStage.ReadyToCreate) {
                                                                            activeReport = stage.report
                                                                        }
                                                                    }
                                                                } catch (e: Exception) {
                                                                    currentStage = ImportStage.Error(e.message ?: "Erreur inattendue")
                                                                }
                                                            }
                                                        }
                                                        .padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    if (item.isLikedSongs) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(44.dp)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(BlazeOrange.copy(alpha = 0.2f)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Favorite,
                                                                contentDescription = null,
                                                                tint = BlazeOrange,
                                                                modifier = Modifier.size(24.dp)
                                                            )
                                                        }
                                                    } else if (!item.coverUrl.isNullOrBlank()) {
                                                        AsyncImage(
                                                            model = item.coverUrl,
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
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(DarkGraphite),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.MusicNote,
                                                                contentDescription = null,
                                                                tint = TextMuted,
                                                                modifier = Modifier.size(22.dp)
                                                            )
                                                        }
                                                    }

                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = item.name,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = TextPrimary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = "${item.trackCount} morceau(x)",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = TextSecondary
                                                        )
                                                    }

                                                    Icon(
                                                        imageVector = Icons.Rounded.CloudDownload,
                                                        contentDescription = "Importer",
                                                        tint = BlazeOrange,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Accordéon pour coller un lien personnalisé
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { showCustomUrlField = !showCustomUrlField }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (showCustomUrlField) "Masquer le champ de lien" else "Ou coller un lien (Deezer / Spotify)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BlazeOrange
                                    )
                                    Icon(
                                        imageVector = if (showCustomUrlField) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = BlazeOrange,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                AnimatedVisibility(visible = showCustomUrlField || !isSpotifyConnected) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Coller", tint = BlazeOrange)
                                                }
                                            },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = BlazeOrange,
                                                unfocusedBorderColor = HairlineDark,
                                                focusedContainerColor = DarkGraphite,
                                                unfocusedContainerColor = DarkGraphite,
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Button(
                                            onClick = {
                                                if (webUrlText.isNotBlank()) {
                                                    scope.launch {
                                                        try {
                                                            importManager.importFromWeb(webUrlText).collect { stage ->
                                                                currentStage = stage
                                                                if (stage is ImportStage.ReadyToCreate) {
                                                                    activeReport = stage.report
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            currentStage = ImportStage.Error(e.message ?: "Erreur inattendue")
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
                                                containerColor = BlazeOrange,
                                                contentColor = DeepBlack,
                                                disabledContainerColor = ElevatedGraphite,
                                                disabledContentColor = TextMuted
                                            )
                                        ) {
                                            Text("Analyser et Importer", fontWeight = FontWeight.Bold)
                                        }
                                    }
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
                                        .background(DarkGraphite)
                                        .border(1.dp, HairlineDark, RoundedCornerShape(16.dp))
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
                                            tint = BlazeOrange,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Sélectionner un fichier .m3u8, .csv ou .txt",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "Encodage UTF-8/BOM/Latin-1 auto-détecté",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
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
                Text("Fermer", color = BlazeOrange, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun PlaylistImportSummaryDialog(
    report: ImportReport,
    isSubmitting: Boolean = false,
    submitProgress: Pair<Int, String>? = null,
    onConfirm: (customName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var playlistName by remember { mutableStateOf(report.playlistName) }

    if (isSubmitting) {
        val current = submitProgress?.first ?: 0
        val total = report.totalTracks
        val trackName = submitProgress?.second ?: "Initialisation..."
        val fraction = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f

        AlertDialog(
            onDismissRequest = { /* Non annulable pendant la création */ },
            containerColor = ElevatedGraphite,
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
                            .background(BlazeOrange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = BlazeOrange,
                            strokeWidth = 3.dp
                        )
                    }
                    Column {
                        Text(
                            text = "Création de la playlist...",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Ajout des morceaux à votre bibliothèque",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(DarkGraphite)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Progression :",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "$current / $total",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = BlazeOrange
                            )
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = BlazeOrange,
                            trackColor = ElevatedGraphite
                        )
                        Text(
                            text = trackName,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {}
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ElevatedGraphite,
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Rounded.MusicNote, null, tint = BlazeOrange) },
        title = {
            Text(
                text = "Résumé de l'import",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Nom de la playlist") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlazeOrange,
                        unfocusedBorderColor = HairlineDark,
                        focusedContainerColor = DarkGraphite,
                        unfocusedContainerColor = DarkGraphite,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("${report.totalTracks} titres · ${report.sourceDescription}",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImportSummaryCountRow("Références reconnues", report.matchedLocalCount,
                        Icons.Rounded.CheckCircle, SemanticSuccess)
                    if (report.possibleMatchCount > 0) ImportSummaryCountRow("Copies possibles",
                        report.possibleMatchCount, Icons.Rounded.Link, BlazeOrange)
                    if (report.unmatchedCount > 0) ImportSummaryCountRow("Sans correspondance",
                        report.unmatchedCount, Icons.Rounded.MusicNote, TextSecondary)
                }

                if (report.missingCount > 0) {
                    Text(
                        text = if (report.sourceReference != null)
                            "La playlist restera liée à ${report.sourceDescription}. Tu pourras valider les copies proposées ensemble. Les fichiers manquants dépendent d'une source audio disponible."
                        else "Les titres manquants seront ajoutés à la playlist et leur téléchargement cloud sera demandé.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        text = "Tous les morceaux de cette playlist sont déjà connus dans AURA.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticSuccess
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(playlistName.ifBlank { report.playlistName }) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BlazeOrange,
                    contentColor = DeepBlack
                )
            ) {
                Text("Créer la playlist", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = BlazeOrange, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun ImportSummaryCountRow(label: String, count: Int, icon: ImageVector, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(count.toString(), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = color, softWrap = false)
    }
}

@Composable
fun PlaylistExportReportDialog(
    report: ExportReport,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ElevatedGraphite,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = SemanticSuccess, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Playlist exportée !", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
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
                    color = TextSecondary
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkGraphite)
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Fichier : ${report.outputFile.name}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("• ${report.fullPathCount} morceaux avec chemin physique complet", style = MaterialTheme.typography.bodySmall, color = SemanticSuccess)
                        if (report.metadataOnlyCount > 0) {
                            Text("• ${report.metadataOnlyCount} morceaux avec métadonnées relatives", style = MaterialTheme.typography.bodySmall, color = TextMuted)
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
                colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange, contentColor = DeepBlack)
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Partager / Ouvrir", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Fermer", color = BlazeOrange, fontWeight = FontWeight.Bold)
            }
        }
    )
}
