package com.aura.music.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.repository.LibraryDashboardSummary
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.DownloadRepository
import com.aura.music.ui.RouteScaffold
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.TextPrimary
import androidx.compose.material.icons.rounded.CloudDownload
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.collect
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun SettingsScreen(
    repository: LocalLibraryRepository,
    downloadRepository: DownloadRepository,
    syncRepository: com.aura.music.data.repository.SyncRepository,
    onNavigateBack: () -> Unit,
    onNavigateToSandbox: () -> Unit,
    onNavigateToCloudSync: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    var cookiesText by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf<Boolean?>(null) }
    var showYouTubeLogin by remember { mutableStateOf(false) }

    var isSyncing by remember { mutableStateOf(false) }
    var syncResultStatus by remember { mutableStateOf<String?>(null) }
    var isSyncSuccess by remember { mutableStateOf<Boolean?>(null) }

    val settingsState = produceState<UserSettingsEntity?>(initialValue = null, repository, refreshTick) {
        repository.ensureDefaults()
        value = repository.getSettings()
    }
    val summaryState = produceState<LibraryDashboardSummary?>(initialValue = null, repository, refreshTick) {
        value = repository.getLibraryDashboardSummary()
    }
    val settings = settingsState.value

    RouteScaffold(title = "Paramètres", onNavigateBack = onNavigateBack) {
        if (settings == null) {
            EmptyStateSurface("Settings indisponibles", "Le profil local n'est pas encore initialise.")
            return@RouteScaffold
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                HeroIdentityCard(
                    title = "Preferences AURA",
                    subtitle = "Recherche online, sync future et diagnostic local.",
                    gradient = Brush.linearGradient(listOf(Color(0xFF232323), Color(0xFF050505))),
                )
            }
            item {
                val ctx = LocalContext.current
                SettingsCard(title = "Compte et sync") {
                    SettingToggleRow(
                        title = "Sync cloud",
                        subtitle = "Optionnelle. L'app reste utile sans backend cloud.",
                        checked = settings.syncEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setSyncEnabled(enabled)
                                refreshTick++
                            }
                        },
                    )

                    if (settings.syncEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(12.dp))

                        val formattedDate = remember(settings.lastSyncAt) {
                            settings.lastSyncAt?.let { timestamp ->
                                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                                sdf.format(java.util.Date(timestamp))
                            } ?: "Jamais synchronisé"
                        }

                        Text(
                            text = "Dernière synchronisation : $formattedDate",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )

                        if (syncResultStatus != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = syncResultStatus!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSyncSuccess == true) Color(0xFF4CAF50) else Color(0xFFF44336),
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                isSyncing = true
                                syncResultStatus = "Synchronisation réseau démarrée..."
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
                                            syncResultStatus = "Échec ou aucun changement à synchroniser."
                                            isSyncSuccess = false
                                        }
                                    } catch (e: Exception) {
                                        syncResultStatus = "Erreur: ${e.localizedMessage}"
                                        isSyncSuccess = false
                                    } finally {
                                        isSyncing = false
                                    }
                                }
                            },
                            enabled = !isSyncing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF6B00),
                                contentColor = Color(0xFF160A00)
                            ),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = "Synchroniser"
                                )
                                Text(if (isSyncing) "Synchronisation..." else "Synchroniser maintenant")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onNavigateToCloudSync,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElevatedGraphite,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = "Fichiers Cloud"
                            )
                            Text("Gestion des fichiers Cloud")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))
                    PolicyRow(
                        title = "Sync stats",
                        selected = settings.statsSyncNetworkPolicy,
                        options = listOf("wifi_only", "any_network"),
                        onSelect = { policy ->
                            scope.launch {
                                repository.setStatsSyncNetworkPolicy(policy)
                                refreshTick++
                            }
                        },
                    )
                }
            }
            item {
                SettingsCard(title = "Recherche") {
                    SettingToggleRow(
                        title = "Recherche online",
                        subtitle = "Active la partie backend-only pour la recherche enrichie.",
                        checked = settings.onlineSearchEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setOnlineSearchEnabled(enabled)
                                refreshTick++
                            }
                        },
                    )
                    Divider()
                    PolicyRow(
                        title = "Politique reseau",
                        selected = settings.onlineSearchNetworkPolicy,
                        options = listOf("wifi_only", "any_network"),
                        onSelect = { policy ->
                            scope.launch {
                                repository.setOnlineSearchNetworkPolicy(policy)
                                refreshTick++
                            }
                        },
                    )
                }
            }
            item {
                SettingsCard(title = "Contournement YouTube (Cookies)") {
                    Text(
                        "Pour contourner les blocages YouTube, connectez-vous directement via la WebView sécurisée de l'application ou collez vos cookies Netscape manuellement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { showYouTubeLogin = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE50914), // YouTube Red
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("Se connecter à YouTube (WebView)")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Ou coller manuellement les cookies Netscape :",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = cookiesText,
                        onValueChange = { cookiesText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = { Text("# Netscape HTTP Cookie File\n...", style = MaterialTheme.typography.bodySmall) },
                        maxLines = 10,
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors()
                    )
                    
                    if (uploadStatus != null) {
                        Text(
                            text = uploadStatus!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSuccess == true) Color(0xFF4CAF50) else Color(0xFFF44336),
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
                                downloadRepository.uploadCookies(cookiesText, com.aura.music.data.repository.SyncRepository.AUTH_TOKEN)
                                    .collect { result ->
                                        isUploading = false
                                        result.fold(
                                            onSuccess = {
                                                uploadStatus = "Cookies mis à jour avec succès sur le serveur."
                                                isSuccess = true
                                                cookiesText = ""
                                            },
                                            onFailure = { error ->
                                                uploadStatus = "Erreur: ${error.localizedMessage}"
                                                isSuccess = false
                                            }
                                        )
                                    }
                            }
                        },
                        enabled = !isUploading && cookiesText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6B00),
                            contentColor = Color(0xFF160A00)
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(if (isUploading) "Téléversement..." else "Mettre à jour les cookies")
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
                                downloadRepository.uploadCookies(netscapeCookies, com.aura.music.data.repository.SyncRepository.AUTH_TOKEN)
                                    .collect { result ->
                                        isUploading = false
                                        result.fold(
                                            onSuccess = {
                                                uploadStatus = "Cookies WebView mis à jour avec succès !"
                                                isSuccess = true
                                            },
                                            onFailure = { error ->
                                                uploadStatus = "Erreur WebView: ${error.localizedMessage}"
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
            item {
                var isIndexing by remember { mutableStateOf(false) }
                var indexResult by remember { mutableStateOf<String?>(null) }

                SettingsCard(title = "Diagnostics") {
                    Text("Pistes indexees: ${summaryState.value?.roomTrackCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text("MediaStore detecte: ${summaryState.value?.mediaStoreTrackCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Snapshot actif: ${if (summaryState.value?.activeSnapshot != null) "oui" else "non"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (indexResult != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = indexResult!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF00E0FF),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            isIndexing = true
                            indexResult = "Indexation locale en cours..."
                            scope.launch {
                                try {
                                    val count = repository.refreshLocalMediaIndex()
                                    indexResult = "Indexation terminée : $count piste(s) synchronisée(s) !"
                                    refreshTick++
                                } catch (e: java.lang.Exception) {
                                    indexResult = "Erreur : ${e.localizedMessage}"
                                } finally {
                                    isIndexing = false
                                }
                            }
                        },
                        enabled = !isIndexing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E0FF),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(if (isIndexing) "Indexation..." else "Rafraîchir l'index local")
                    }
                }
            }
            item {
                SettingsCard(title = "Sandbox Performance") {
                    Text(
                        "Outil de diagnostic pour tester la fluidité des listes réorganisables sous différentes configurations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onNavigateToSandbox,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("Ouvrir la Sandbox")
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PolicyRow(
    title: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val icon = if (option == "wifi_only") Icons.Rounded.Wifi else Icons.Rounded.Sync
                Card(modifier = Modifier.clickable { onSelect(option) }, shape = RoundedCornerShape(999.dp)) {
                    Row(
                        modifier = Modifier
                            .background(if (selected == option) Color(0xFFFF6B00) else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = if (selected == option) Color(0xFF160A00) else MaterialTheme.colorScheme.onSurface)
                        Text(option.replace('_', ' '), color = if (selected == option) Color(0xFF160A00) else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
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
