package com.aura.music.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.aura.music.data.local.AuraDatabase
import com.aura.music.domain.player.WasmAudioPlayer
import com.aura.music.ui.theme.AuraTheme
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "webApp") {
        val database = remember {
            AuraDatabase.getInstance(null)
        }

        val audioPlayer = remember { WasmAudioPlayer() }

        AuraTheme {
            val apiService = remember { com.aura.music.data.network.KtorAuraApiService.createDefault() }
            var pingStatus by remember { mutableStateOf("En attente de connexion...") }
            LaunchedEffect(Unit) {
                pingStatus = try {
                    val response = apiService.search("test", limitTracks = 1)
                    if (response.data != null) {
                        "Connecté (FastAPI: OK)"
                    } else {
                        "Connecté (Erreur API: ${response.error?.message})"
                    }
                } catch (e: Exception) {
                    "Erreur de connexion: ${e.message}"
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "AURA Client Web",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Exécution Kotlin/Wasm avec rendu Skia/Skiko dans le navigateur.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("État de l'application Web", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Base de données Room: Connectée (OPFS SQLite)")
                            Text("Moteur Audio: HTML5 Audio DOM Bindings")
                            Text("Serveur API: $pingStatus")
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("Interface Web active.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
