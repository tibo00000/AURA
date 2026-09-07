package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

@Composable
fun SettingsScreen(
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    secureStorage: DesktopSecureStorage,
    authSessionManager: DesktopAuthSessionManager? = null,
    onReloadData: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val authManager = authSessionManager ?: remember { DesktopAuthSessionManager(secureStorage, coroutineScope) }
    val authState by authManager.authState.collectAsState()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isAuthLoading by remember { mutableStateOf(false) }
    var authMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    var scanStatus by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 860.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Titre
            item {
                Column {
                    Text(
                        text = "Paramètres",
                        color = PureWhite,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configuration du compte, synchronisation et préférences",
                        color = PureWhite.copy(alpha = 0.45f),
                        fontSize = 12.sp
                    )
                }
            }

            // 1. Compte & Synchronisation Supabase / AURA
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = OffBlack),
                    border = BorderStroke(1.dp, HairlineDark)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(imageVector = Icons.Rounded.AccountCircle, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(22.dp))
                                Text(text = "Compte & Synchronisation Cloud", color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        when (val currentAuth = authState) {
                            is DesktopAuthState.Authenticated -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DarkGraphite, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(BlazeOrange.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Person,
                                                contentDescription = null,
                                                tint = BlazeOrange,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = currentAuth.email,
                                                color = PureWhite,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Session active et synchronisée",
                                                color = PureWhite.copy(alpha = 0.5f),
                                                fontSize = 12.sp
                                            )
                                        }
                                    }

                                    Surface(
                                        color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF4CAF50))
                                            )
                                            Text(
                                                text = "En ligne",
                                                color = Color(0xFF4CAF50),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
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
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.handCursor(),
                                        enabled = !isAuthLoading
                                    ) {
                                        if (isAuthLoading) {
                                            CircularProgressIndicator(color = PureWhite, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Synchronisation en cours...", fontSize = 13.sp)
                                        } else {
                                            Icon(imageVector = Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Synchroniser maintenant", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            authManager.logout {
                                                orchestrator.clearStreamCache()
                                            }
                                            orchestrator.apiToken = null
                                            authMessage = "Déconnexion effectuée."
                                            isError = false
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.handCursor()
                                    ) {
                                        Text("Se déconnecter", fontSize = 13.sp)
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

                                Column(
                                    modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
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

                                    Spacer(modifier = Modifier.height(4.dp))

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
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.handCursor(),
                                        enabled = !isAuthLoading && email.isNotBlank() && password.isNotBlank()
                                    ) {
                                        if (isAuthLoading) {
                                            CircularProgressIndicator(color = PureWhite, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text("Se connecter", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        }
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

            // 2. Gestionnaire Cloud & Fichiers Distants
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .handClickable { appState.navigateTo("cloud_sync") },
                    colors = CardDefaults.cardColors(containerColor = OffBlack),
                    border = BorderStroke(1.dp, HairlineDark)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BlazeOrange.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudSync,
                                    contentDescription = null,
                                    tint = BlazeOrange,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Gestionnaire Cloud",
                                    color = PureWhite,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Gestion de l'espace VPS, transferts distants et sauvegardes",
                                    color = PureWhite.copy(alpha = 0.5f),
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Surface(
                            color = DarkGraphite,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, HairlineDark)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Gérer", color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = BlazeOrange,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Indexation de la Bibliothèque Locale
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = OffBlack),
                    border = BorderStroke(1.dp, HairlineDark)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(imageVector = Icons.Rounded.FolderOpen, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(22.dp))
                            Text(text = "Dossiers de Musique Locaux", color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Indexez vos dossiers locaux (MP3, FLAC, AAC, WAV) pour enrichir votre bibliothèque en tâche de fond.",
                            color = PureWhite.copy(alpha = 0.55f),
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
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, HairlineDark),
                                modifier = Modifier.handCursor(),
                                enabled = !isScanning
                            ) {
                                Icon(imageVector = Icons.Rounded.Add, contentDescription = null, tint = PureWhite, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Ajouter un dossier musical", color = PureWhite, fontSize = 13.sp)
                            }
                        }

                        if (scanStatus != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isScanning) {
                                    CircularProgressIndicator(color = BlazeOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(text = scanStatus!!, color = if (isScanning) BlazeOrange else Color(0xFF4CAF50), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // 4. Raccourcis Clavier
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = OffBlack),
                    border = BorderStroke(1.dp, HairlineDark)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(imageVector = Icons.Rounded.Keyboard, contentDescription = null, tint = BlazeOrange, modifier = Modifier.size(22.dp))
                            Text(text = "Raccourcis Clavier", color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val shortcutsLeft = listOf(
                            "Espace" to "Lecture / Pause",
                            "Ctrl + Flèche Droite" to "Piste suivante",
                            "Ctrl + Flèche Gauche" to "Piste précédente",
                            "Flèche Droite / Gauche" to "Avance / Retour (5s)",
                            "Ctrl + Flèche Haut / Bas" to "Volume (+/- 5%)"
                        )
                        val shortcutsRight = listOf(
                            "L" to "Ajouter aux Favoris",
                            "Ctrl + F" to "Recherche rapide",
                            "Ctrl + Shift + Q" to "File d'attente",
                            "Ctrl + Q" to "Réduire dans le Tray"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                shortcutsLeft.forEach { (key, action) ->
                                    ShortcutItem(key = key, action = action)
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                shortcutsRight.forEach { (key, action) ->
                                    ShortcutItem(key = key, action = action)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutItem(key: String, action: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = DarkGraphite,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, HairlineDark)
        ) {
            Text(
                text = key,
                color = BlazeOrange,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Text(
            text = action,
            color = PureWhite.copy(alpha = 0.8f),
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}
