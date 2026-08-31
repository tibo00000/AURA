package com.aura.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.repository.DownloadRepository
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.RouteScaffold
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
fun SettingsScreen(
    repository: LocalLibraryRepository,
    downloadRepository: DownloadRepository,
    syncRepository: com.aura.music.data.repository.SyncRepository,
    onNavigateBack: () -> Unit = {},
    onNavigateToSandbox: () -> Unit,
    onNavigateToCloudSync: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var cookiesText by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf<Boolean?>(null) }
    var showYouTubeLogin by remember { mutableStateOf(false) }
    var showManualCookies by remember { mutableStateOf(false) }

    var isSyncing by remember { mutableStateOf(false) }
    var syncResultStatus by remember { mutableStateOf<String?>(null) }
    var isSyncSuccess by remember { mutableStateOf<Boolean?>(null) }

    var isIndexing by remember { mutableStateOf(false) }
    var indexResult by remember { mutableStateOf<String?>(null) }
    var showAuthDialog by remember { mutableStateOf(false) }

    val authManager = remember { com.aura.music.core.AuthSessionManager.getInstance(ctx) }
    val currentUserEmail by authManager.userEmail.collectAsState()

    val settingsState = produceState<UserSettingsEntity?>(initialValue = null, repository, refreshTick) {
        repository.ensureDefaults()
        value = repository.getSettings()
    }
    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshTick) {
        value = repository.getLibraryDashboardSummary()
    }
    val settings = settingsState.value

    if (showAuthDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showAuthDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            com.aura.music.ui.auth.AuthScreen(
                onNavigateBack = { showAuthDialog = false }
            )
        }
    }

    // Pas de fleche de retour car c'est un onglet principal du menu inferieur
    RouteScaffold(title = "Paramètres") {
        if (settings == null) {
            EmptyStateSurface("Settings indisponibles", "Le profil local n'est pas encore initialisé.")
            return@RouteScaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepBlack),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // =================================================================
            // 1. SYNCHRONISATION & CLOUD
            // =================================================================
            item {
                SettingsCard(
                    title = "Synchronisation & Cloud",
                    icon = Icons.Rounded.Cloud
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAuthDialog = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Compte & Authentification",
                                style = MaterialTheme.typography.bodyMedium,
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = currentUserEmail ?: "Non connecté",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = "Gérer",
                            color = BlazeOrange,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    HorizontalDivider(color = HairlineDark, modifier = Modifier.padding(vertical = 4.dp))

                    SettingToggleRow(
                        title = "Synchronisation Cloud",
                        subtitle = "Sauvegardez vos titres et playlists sur votre serveur privé.",
                        checked = settings.syncEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setSyncEnabled(enabled)
                                refreshTick++
                            }
                        },
                    )

                    if (settings.syncEnabled) {
                        HorizontalDivider(color = HairlineDark, modifier = Modifier.padding(vertical = 4.dp))

                        val formattedDate = remember(settings.lastSyncAt) {
                            settings.lastSyncAt?.let { timestamp ->
                                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy à HH:mm", java.util.Locale.getDefault())
                                sdf.format(java.util.Date(timestamp))
                            } ?: "Aucune synchronisation récente"
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (settings.lastSyncAt != null) SemanticSuccess else BlazeOrange)
                            )
                            Text(
                                text = "Dernière synchro : $formattedDate",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }

                        if (syncResultStatus != null) {
                            Text(
                                text = syncResultStatus!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSyncSuccess == true) SemanticSuccess else SemanticError,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Bouton Synchroniser
                            Button(
                                onClick = {
                                    isSyncing = true
                                    syncResultStatus = "Synchronisation en cours..."
                                    isSyncSuccess = null
                                    scope.launch {
                                        try {
                                            val deviceId = android.provider.Settings.Secure.getString(
                                                ctx.contentResolver,
                                                android.provider.Settings.Secure.ANDROID_ID
                                            ) ?: "android_pixel_device"
                                            val success = syncRepository.performSync(deviceId, force = true)
                                            if (success) {
                                                syncResultStatus = "Synchronisation réussie !"
                                                isSyncSuccess = true
                                                refreshTick++
                                            } else {
                                                syncResultStatus = "Aucune mise à jour requise."
                                                isSyncSuccess = true
                                            }
                                        } catch (e: Exception) {
                                            syncResultStatus = "Erreur : ${e.localizedMessage}"
                                            isSyncSuccess = false
                                        } finally {
                                            isSyncing = false
                                        }
                                    }
                                },
                                enabled = !isSyncing,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BlazeOrange,
                                    contentColor = DeepBlack
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = DeepBlack,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Synchroniser", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // Bouton Fichiers Cloud
                            Button(
                                onClick = onNavigateToCloudSync,
                                modifier = Modifier
                                    .weight(1f)
                                    .border(1.dp, HairlineDark, RoundedCornerShape(12.dp)),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DarkGraphite,
                                    contentColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp), tint = BlazeOrange)
                                    Text("Fichiers Cloud", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        HorizontalDivider(color = HairlineDark, modifier = Modifier.padding(vertical = 4.dp))

                        PolicyPillSelector(
                            label = "Autoriser la synchronisation sur :",
                            selected = settings.statsSyncNetworkPolicy,
                            onSelect = { policy ->
                                scope.launch {
                                    repository.setStatsSyncNetworkPolicy(policy)
                                    refreshTick++
                                }
                            }
                        )
                    }
                }
            }

            // =================================================================
            // 2. RECHERCHE & SERVICES EN LIGNE
            // =================================================================
            item {
                SettingsCard(
                    title = "Recherche & Services en ligne",
                    icon = Icons.Rounded.Search
                ) {
                    SettingToggleRow(
                        title = "Catalogue musical étendu",
                        subtitle = "Recherchez et écoutez des millions de titres en ligne via Deezer et YouTube.",
                        checked = settings.onlineSearchEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setOnlineSearchEnabled(enabled)
                                refreshTick++
                            }
                        },
                    )

                    if (settings.onlineSearchEnabled) {
                        HorizontalDivider(color = HairlineDark, modifier = Modifier.padding(vertical = 4.dp))

                        PolicyPillSelector(
                            label = "Autoriser la recherche en ligne sur :",
                            selected = settings.onlineSearchNetworkPolicy,
                            onSelect = { policy ->
                                scope.launch {
                                    repository.setOnlineSearchNetworkPolicy(policy)
                                    refreshTick++
                                }
                            }
                        )
                    }
                }
            }

            // =================================================================
            // 3. CONTOURNEMENT YOUTUBE & SESSIONS
            // =================================================================
            item {
                SettingsCard(
                    title = "Accès & Flux YouTube",
                    icon = Icons.Rounded.PlayCircle
                ) {
                    Text(
                        text = "Connectez-vous pour contourner les restrictions régionales et assurer la lecture fluide de tous les flux audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Button(
                        onClick = { showYouTubeLogin = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE50914),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text("Connexion YouTube (WebView sécurisée)", fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showManualCookies = !showManualCookies }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Saisie manuelle des cookies Netscape",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted
                        )
                        Icon(
                            imageVector = if (showManualCookies) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = TextMuted
                        )
                    }

                    AnimatedVisibility(
                        visible = showManualCookies,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = cookiesText,
                                onValueChange = { cookiesText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp),
                                placeholder = {
                                    Text(
                                        "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\t...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                },
                                maxLines = 8,
                                singleLine = false,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = DarkGraphite,
                                    unfocusedContainerColor = DarkGraphite,
                                    focusedBorderColor = BlazeOrange,
                                    unfocusedBorderColor = HairlineDark,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )

                            if (uploadStatus != null) {
                                Text(
                                    text = uploadStatus!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSuccess == true) SemanticSuccess else SemanticError,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Button(
                                onClick = {
                                    if (cookiesText.isBlank()) {
                                        uploadStatus = "Veuillez coller des cookies valides."
                                        isSuccess = false
                                        return@Button
                                    }
                                    isUploading = true
                                    uploadStatus = "Envoi en cours..."
                                    isSuccess = null
                                    scope.launch {
                                        downloadRepository.uploadCookies(cookiesText, com.aura.music.core.AuthSessionManager.getInstance(ctx).getBearerHeader())
                                            .collect { result ->
                                                isUploading = false
                                                result.fold(
                                                    onSuccess = {
                                                        uploadStatus = "Cookies mis à jour avec succès sur le serveur."
                                                        isSuccess = true
                                                        cookiesText = ""
                                                    },
                                                    onFailure = { error ->
                                                        uploadStatus = "Erreur : ${error.localizedMessage}"
                                                        isSuccess = false
                                                    }
                                                )
                                            }
                                    }
                                },
                                enabled = !isUploading && cookiesText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BlazeOrange,
                                    contentColor = DeepBlack
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(if (isUploading) "Téléversement..." else "Envoyer les cookies", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (showYouTubeLogin) {
                    YouTubeLoginDialog(
                        onCookiesExtracted = { netscapeCookies ->
                            showYouTubeLogin = false
                            isUploading = true
                            uploadStatus = "Envoi des cookies WebView..."
                            isSuccess = null
                            scope.launch {
                                downloadRepository.uploadCookies(netscapeCookies, com.aura.music.core.AuthSessionManager.getInstance(ctx).getBearerHeader())
                                    .collect { result ->
                                        isUploading = false
                                        result.fold(
                                            onSuccess = {
                                                uploadStatus = "Cookies YouTube mis à jour avec succès !"
                                                isSuccess = true
                                            },
                                            onFailure = { error ->
                                                uploadStatus = "Erreur WebView : ${error.localizedMessage}"
                                                isSuccess = false
                                            }
                                        )
                                    }
                            }
                        },
                        onDismiss = { showYouTubeLogin = false }
                    )
                }
            }

            // =================================================================
            // 4. STOCKAGE & BIBLIOTHÈQUE LOCALE
            // =================================================================
            item {
                SettingsCard(
                    title = "Bibliothèque locale & Données",
                    icon = Icons.Rounded.Storage
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricBadge(
                            label = "Pistes Room",
                            value = "${summaryState.value?.roomTrackCount ?: 0}",
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            label = "Pistes MediaStore",
                            value = "${summaryState.value?.mediaStoreTrackCount ?: 0}",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (indexResult != null) {
                        Text(
                            text = indexResult!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticSuccess,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            isIndexing = true
                            indexResult = "Scan du stockage en cours..."
                            scope.launch {
                                try {
                                    val count = repository.refreshLocalMediaIndex()
                                    indexResult = "Index rafraîchi : $count piste(s) synchronisée(s)"
                                    refreshTick++
                                } catch (e: Exception) {
                                    indexResult = "Erreur : ${e.localizedMessage}"
                                } finally {
                                    isIndexing = false
                                }
                            }
                        },
                        enabled = !isIndexing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, HairlineDark, RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkGraphite,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isIndexing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = BlazeOrange,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(18.dp))
                                Text("Scanner & rafraîchir l'index local", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 5. OUTILS AVANCÉS
            // =================================================================
            item {
                SettingsCard(
                    title = "Outils avancés",
                    icon = Icons.Rounded.Tune
                ) {
                    Text(
                        text = "Outil de diagnostic pour tester la fluidité et les performances des listes réorganisables.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Button(
                        onClick = onNavigateToSandbox,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, HairlineDark, RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkGraphite,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(18.dp))
                            Text("Ouvrir le bac à sable de performance", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Footer Version
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "AURA Music Player",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                    Text(
                        text = "Version 1.0.0 • Architecture Hybride MVVM",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

// =============================================================================
// Composants de style pour les Paramètres
// =============================================================================

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ElevatedGraphite)
            .border(1.dp, HairlineDark, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkGraphite)
                        .border(1.dp, HairlineDark, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = BlazeOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
        content()
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DeepBlack,
                checkedTrackColor = BlazeOrange,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = DarkGraphite,
                uncheckedBorderColor = HairlineDark
            )
        )
    }
}

@Composable
private fun PolicyPillSelector(
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val options = listOf(
                "wifi_only" to ("Wi-Fi uniquement" to Icons.Rounded.Wifi),
                "any_network" to ("Wi-Fi & Réseau mobile" to Icons.Rounded.Language)
            )
            options.forEach { (key, pair) ->
                val (text, icon) = pair
                val isSelected = selected == key
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) BlazeOrange.copy(alpha = 0.15f) else DarkGraphite)
                        .border(1.dp, if (isSelected) BlazeOrange else HairlineDark, RoundedCornerShape(12.dp))
                        .clickable { onSelect(key) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) BlazeOrange else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) BlazeOrange else TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBadge(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkGraphite)
            .border(1.dp, HairlineDark, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BlazeOrange
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun YouTubeLoginDialog(
    onCookiesExtracted: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Connexion YouTube",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Fermer",
                            tint = Color.White
                        )
                    }
                }

                Text(
                    text = "Connectez-vous à votre compte YouTube. Les cookies de session seront extraits automatiquement et envoyés de manière sécurisée au serveur pour contourner les restrictions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )

                // WebView Container
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context ->
                        android.webkit.WebView(context).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            }
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val cookieManager = android.webkit.CookieManager.getInstance()
                                    val cookiesString = cookieManager.getCookie("https://www.youtube.com")
                                    if (cookiesString != null) {
                                        if (cookiesString.contains("SID=") && cookiesString.contains("HSID=")) {
                                            val netscapeCookies = convertToNetscape(cookiesString)
                                            onCookiesExtracted(netscapeCookies)
                                        }
                                    }
                                }
                            }
                            loadUrl("https://www.youtube.com")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

fun convertToNetscape(cookiesString: String): String {
    val builder = java.lang.StringBuilder()
    builder.append("# Netscape HTTP Cookie File\n")
    builder.append("# This file is generated by AURA Music Player WebView\n\n")
    
    val cookies = cookiesString.split(";")
    for (cookie in cookies) {
        val trimmed = cookie.trim()
        if (trimmed.isEmpty()) continue
        
        val parts = trimmed.split("=", limit = 2)
        if (parts.size == 2) {
            val name = parts[0].trim()
            val value = parts[1].trim()
            builder.append(".youtube.com\tTRUE\t/\tTRUE\t2147483647\t$name\t$value\n")
        }
    }
    return builder.toString()
}
