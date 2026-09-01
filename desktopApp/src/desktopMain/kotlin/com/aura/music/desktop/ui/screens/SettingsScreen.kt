package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.security.DesktopAuthSessionManager
import com.aura.music.desktop.security.DesktopAuthState
import com.aura.music.desktop.security.DesktopSecureStorage
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

@Composable
fun SettingsScreen(
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    secureStorage: DesktopSecureStorage,
    onReloadData: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val authManager = remember { DesktopAuthSessionManager(secureStorage, coroutineScope) }
    val authState by authManager.authState.collectAsState()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isAuthLoading by remember { mutableStateOf(false) }
    var authMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    var scanStatus by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Titre
        item {
            Text(
                text = "Paramètres",
                color = PureWhite,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 1. Compte & Synchronisation Supabase / AURA
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Rounded.AccountCircle, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = "Compte & Synchronisation Cloud", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    when (val currentAuth = authState) {
                        is DesktopAuthState.Authenticated -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkGraphite, RoundedCornerShape(8.dp))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Connecté : ${currentAuth.email}",
                                        color = PureWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Session sécurisée active (Windows DPAPI / KeyStore)",
                                        color = PureWhite.copy(alpha = 0.5f),
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        isAuthLoading = true
                                        authMessage = null
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                orchestrator.syncCloudData(currentAuth.token) {
                                                    authMessage = "Synchronisation terminée avec succès !"
                                                    isError = false
                                                }
                                            } catch (e: Exception) {
                                                authMessage = "Erreur de synchronisation : ${e.message}"
                                                isError = true
                                            } finally {
                                                isAuthLoading = false
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                    enabled = !isAuthLoading
                                ) {
                                    if (isAuthLoading) {
                                        CircularProgressIndicator(color = PureWhite, modifier = Modifier.size(16.dp))
                                    } else {
                                        Icon(imageVector = Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Synchroniser maintenant")
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        authManager.logout()
                                        orchestrator.apiToken = null
                                        authMessage = "Déconnexion effectuée."
                                        isError = false
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Se déconnecter")
                                }
                            }
                        }
                        else -> {
                            Text(
                                text = "Connectez-vous pour synchroniser vos favoris, playlists et fichiers audio sur tous vos appareils.",
                                color = PureWhite.copy(alpha = 0.6f),
                                fontSize = 13.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Adresse email") },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(imageVector = Icons.Rounded.Mail, contentDescription = null, tint = BlazeOrange)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = DarkGraphite,
                                    unfocusedContainerColor = DarkGraphite,
                                    focusedBorderColor = BlazeOrange,
                                    unfocusedBorderColor = HairlineDark,
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { appState.isInputFocused = it.isFocused }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Mot de passe") },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(imageVector = Icons.Rounded.Lock, contentDescription = null, tint = BlazeOrange)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = null,
                                            tint = PureWhite.copy(alpha = 0.5f)
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = DarkGraphite,
                                    unfocusedContainerColor = DarkGraphite,
                                    focusedBorderColor = BlazeOrange,
                                    unfocusedBorderColor = HairlineDark,
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { appState.isInputFocused = it.isFocused }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    if (email.isNotBlank() && password.isNotBlank()) {
                                        isAuthLoading = true
                                        authMessage = null
                                        coroutineScope.launch {
                                            val result = authManager.loginWithPassword(email, password)
                                            result.onSuccess { token ->
                                                orchestrator.apiToken = token
                                                authMessage = "Connexion réussie !"
                                                isError = false
                                                orchestrator.syncCloudData(token)
                                            }.onFailure { err ->
                                                authMessage = "Échec : ${err.message}"
                                                isError = true
                                            }
                                            isAuthLoading = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                enabled = !isAuthLoading && email.isNotBlank() && password.isNotBlank()
                            ) {
                                if (isAuthLoading) {
                                    CircularProgressIndicator(color = PureWhite, modifier = Modifier.size(18.dp))
                                } else {
                                    Text("Se connecter")
                                }
                            }
                        }
                    }

                    if (authMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = authMessage!!,
                            color = if (isError) Color(0xFFFF5252) else Color(0xFF4CAF50),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // 2. Indexation de la Bibliothèque Locale
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Rounded.FolderOpen, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = "Dossiers de Musique Locaux", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Scannez vos dossiers locaux (MP3, FLAC, AAC, WAV) pour alimenter votre bibliothèque AURA sans blocage d'interface grâce aux Virtual Threads (Loom).",
                        color = PureWhite.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val chooser = JFileChooser().apply {
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    dialogTitle = "Sélectionner un dossier musical"
                                }
                                val result = chooser.showOpenDialog(null)
                                if (result == JFileChooser.APPROVE_OPTION) {
                                    val dir = chooser.selectedFile
                                    if (dir != null && dir.exists()) {
                                        isScanning = true
                                        scanStatus = "Scan en cours de ${dir.name}..."
                                        coroutineScope.launch(orchestrator.loomDispatcher) {
                                            try {
                                                orchestrator.scanDirectory(dir)
                                                scanStatus = "Indexation terminée !"
                                            } catch (e: Exception) {
                                                scanStatus = "Erreur pendant l'indexation : ${e.message}"
                                            } finally {
                                                isScanning = false
                                            }
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite),
                            enabled = !isScanning
                        ) {
                            Icon(imageVector = Icons.Rounded.Add, contentDescription = null, tint = PureWhite)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ajouter un dossier musical", color = PureWhite)
                        }
                    }

                    if (scanStatus != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isScanning) {
                                CircularProgressIndicator(color = BlazeOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(text = scanStatus!!, color = if (isScanning) BlazeOrange else Color(0xFF4CAF50), fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // 3. Raccourcis Clavier
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = OffBlack)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Rounded.Keyboard, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = "Raccourcis Clavier", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val shortcuts = listOf(
                        "Espace" to "Lecture / Pause",
                        "Ctrl + Flèche Droite" to "Piste suivante",
                        "Ctrl + Flèche Gauche" to "Piste précédente",
                        "Flèche Droite / Gauche" to "Avance / Retour rapide (5 sec)",
                        "Ctrl + Flèche Haut / Bas" to "Volume (+/- 5%)",
                        "L" to "Ajouter / Retirer des Favoris",
                        "Ctrl + F" to "Recherche rapide",
                        "Ctrl + Shift + Q" to "Ouvrir / Fermer la file d'attente",
                        "Ctrl + Q" to "Réduire dans la barre des tâches (Tray)"
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shortcuts.forEach { (key, action) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = DarkGraphite,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = key,
                                        color = BlazeOrange,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                                Text(
                                    text = action,
                                    color = PureWhite.copy(alpha = 0.8f),
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
