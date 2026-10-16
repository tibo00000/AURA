package com.aura.music.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.network.BuildConfig
import com.aura.music.data.network.KtorAuraApiService
import com.aura.music.data.network.TrackSummary
import com.aura.music.data.network.WebAuthSessionManager
import com.aura.music.domain.player.WasmAudioPlayer
import com.aura.music.ui.theme.AuraTheme
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface ServerStatus {
    object Checking : ServerStatus
    object Available : ServerStatus
    data class Unavailable(val message: String) : ServerStatus
}

private fun hideWasmLoader() {
    try {
        val loader = document.getElementById("wasm-loader")
        loader?.let {
            it.setAttribute("style", "opacity: 0; pointer-events: none;")
            window.setTimeout({
                it.setAttribute("style", "display: none;")
            }, 400)
        }
    } catch (_: Throwable) {
        // Ignorer si non disponible
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${if (seconds < 10) "0$seconds" else "$seconds"}"
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "webApp") {
        AuraTheme {
            WebAuraApp()
        }
    }
}

@Composable
fun WebAuraApp() {
    val coroutineScope = rememberCoroutineScope()
    val sessionManager = remember { WebAuthSessionManager.getInstance() }
    val apiService = remember { KtorAuraApiService.createDefault() }
    val database = remember { AuraDatabase.getInstance(null) }
    val audioPlayer = remember { WasmAudioPlayer() }

    val isLoggedIn by sessionManager.isLoggedIn.collectAsState()
    val userEmail by sessionManager.userEmail.collectAsState()
    val authToken by sessionManager.authToken.collectAsState()

    var serverStatus by remember { mutableStateOf<ServerStatus>(ServerStatus.Checking) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<TrackSummary>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var currentTrack by remember { mutableStateOf<TrackSummary?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var audioErrorMessage by remember { mutableStateOf<String?>(null) }

    var showLoginDialog by remember { mutableStateOf(false) }

    // Masquage du loader HTML dès le premier frame Compose
    LaunchedEffect(Unit) {
        hideWasmLoader()
    }

    // Diagnostic de connectivité initiale
    LaunchedEffect(Unit) {
        serverStatus = ServerStatus.Checking
        try {
            val response = apiService.search("aura", limitTracks = 1)
            serverStatus = if (response.data != null) {
                ServerStatus.Available
            } else {
                ServerStatus.Unavailable(response.error?.message ?: "Erreur serveur inconnue")
            }
        } catch (e: Exception) {
            serverStatus = ServerStatus.Unavailable(e.message ?: "Impossible de joindre le serveur FastAPI")
        }
    }

    // Écouteurs du lecteur audio
    DisposableEffect(audioPlayer) {
        audioPlayer.onCompletionListener = {
            isPlaying = false
            currentPositionMs = 0L
        }
        audioPlayer.onErrorListener = { err ->
            isPlaying = false
            audioErrorMessage = "Erreur lecture audio: $err"
        }
        onDispose {
            audioPlayer.stop()
        }
    }

    // Boucle de mise à jour de la position de lecture
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPositionMs = audioPlayer.getCurrentPosition()
            val total = audioPlayer.getDuration()
            if (total > 0L) {
                durationMs = total
            }
            delay(250)
        }
    }

    val playTrack: (TrackSummary) -> Unit = { track ->
        currentTrack = track
        audioErrorMessage = null
        durationMs = track.durationMs.toLong()
        currentPositionMs = 0L

        val cleanBaseUrl = BuildConfig.API_BASE_URL.removeSuffix("/")
        val token = authToken
        val streamUrl = if (!token.isNullOrBlank()) {
            "$cleanBaseUrl/me/sync/files/${track.id}?token=$token"
        } else {
            "$cleanBaseUrl/me/sync/files/${track.id}"
        }

        audioPlayer.play(streamUrl)
        isPlaying = true
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Barre d'en-tête (Header)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "AURA",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "WEB",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Statut Serveur
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = when (serverStatus) {
                                is ServerStatus.Available -> Color(0xFF4CAF50)
                                is ServerStatus.Checking -> Color(0xFFFFC107)
                                is ServerStatus.Unavailable -> Color(0xFFF44336)
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val statusLabel = when (serverStatus) {
                                is ServerStatus.Available -> "Serveur en ligne"
                                is ServerStatus.Checking -> "Vérification..."
                                is ServerStatus.Unavailable -> "Serveur indisponible"
                            }
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Authentification
                        if (isLoggedIn) {
                            Text(
                                text = userEmail ?: "Connecté",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedButton(
                                onClick = { sessionManager.logout() },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Déconnexion", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Button(
                                onClick = { showLoginDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                            ) {
                                Text("Connexion", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Corps principal (Recherche & Résultats)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                // Zone de recherche
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Rechercher un titre, un artiste, un album...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                coroutineScope.launch {
                                    isSearching = true
                                    searchError = null
                                    try {
                                        val res = apiService.search(searchQuery.trim(), limitTracks = 25)
                                        if (res.data != null) {
                                            searchResults = res.data!!.tracks
                                        } else {
                                            searchError = res.error?.message ?: "Aucun résultat"
                                        }
                                    } catch (e: Exception) {
                                        searchError = "Erreur de recherche: ${e.message}"
                                    } finally {
                                        isSearching = false
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text(if (isSearching) "..." else "Rechercher")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Alertes / Statuts
                searchError?.let { err ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                audioErrorMessage?.let { err ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Liste des morceaux
                if (searchResults.isEmpty() && !isSearching) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "AURA Web Player",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Moteur Audio HTML5 natif · Cache Session SQLite · Fast Streaming HTTP 206",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchResults, key = { it.id }) { track ->
                            val isSelected = currentTrack?.id == track.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { playTrack(track) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${track.displayArtistName} ${track.displayAlbumTitle?.let { "• $it" } ?: ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = formatDuration(track.durationMs.toLong()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        FilledTonalButton(
                                            onClick = {
                                                if (isSelected && isPlaying) {
                                                    audioPlayer.pause()
                                                    isPlaying = false
                                                } else {
                                                    playTrack(track)
                                                }
                                            },
                                            shape = CircleShape,
                                            contentPadding = PaddingValues(8.dp),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Text(
                                                text = if (isSelected && isPlaying) "⏸" else "▶",
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Mini Player Bar (en bas)
            currentTrack?.let { track ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        // Slider de progression
                        val sliderPosition = if (durationMs > 0) {
                            (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        Slider(
                            value = sliderPosition,
                            onValueChange = { frac ->
                                val targetMs = (frac * durationMs).toLong()
                                currentPositionMs = targetMs
                                audioPlayer.seekTo(targetMs)
                            },
                            modifier = Modifier.fillMaxWidth().height(16.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.displayArtistName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "${formatDuration(currentPositionMs)} / ${formatDuration(durationMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Button(
                                    onClick = {
                                        if (isPlaying) {
                                            audioPlayer.pause()
                                            isPlaying = false
                                        } else {
                                            audioPlayer.play(
                                                audioPlayer.let {
                                                    val token = authToken
                                                    val cleanBaseUrl = BuildConfig.API_BASE_URL.removeSuffix("/")
                                                    if (!token.isNullOrBlank()) {
                                                        "$cleanBaseUrl/me/sync/files/${track.id}?token=$token"
                                                    } else {
                                                        "$cleanBaseUrl/me/sync/files/${track.id}"
                                                    }
                                                }
                                            )
                                            isPlaying = true
                                        }
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier.size(44.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = if (isPlaying) "⏸" else "▶",
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal / Dialogue de connexion Supabase
    if (showLoginDialog) {
        var emailInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var loginError by remember { mutableStateOf<String?>(null) }
        var isLoggingIn by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isLoggingIn) showLoginDialog = false },
            title = { Text("Connexion à votre compte AURA") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Authentification Supabase directe (Zero-Leak). Vos morceaux et playlists synchronisés seront accessibles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Mot de passe") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    loginError?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isLoggingIn = true
                            loginError = null
                            val res = sessionManager.login(emailInput, passwordInput)
                            res.fold(
                                onSuccess = {
                                    showLoginDialog = false
                                },
                                onFailure = { ex ->
                                    loginError = ex.message ?: "Identifiants invalides"
                                }
                            )
                            isLoggingIn = false
                        }
                    },
                    enabled = emailInput.isNotBlank() && passwordInput.isNotBlank() && !isLoggingIn
                ) {
                    Text(if (isLoggingIn) "Connexion..." else "Se connecter")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLoginDialog = false },
                    enabled = !isLoggingIn
                ) {
                    Text("Annuler")
                }
            }
        )
    }
}

