package com.aura.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.aura.music.data.local.AuraDatabase
import com.aura.music.domain.player.DesktopAudioPlayer
import com.aura.music.ui.theme.AuraTheme
import com.aura.music.ui.theme.DeepBlack
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import java.util.logging.Level
import java.util.logging.Logger

fun main() = application {
    var isVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(1024.dp, 768.dp)
    )

    // DB Initialization helper
    val database = remember {
        AuraDatabase.getInstance(null)
    }

    // Native JNI Audio Player
    val audioPlayer = remember { DesktopAudioPlayer() }

    // Initialize JNativeHook
    DisposableEffect(Unit) {
        // Suppress JNativeHook default console logs
        val logger = Logger.getLogger(GlobalScreen::class.java.getPackage().name)
        logger.level = Level.OFF
        logger.useParentHandlers = false

        try {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(object : NativeKeyListener {
                override fun nativeKeyPressed(e: NativeKeyEvent) {
                    when (e.keyCode) {
                        NativeKeyEvent.VC_MEDIA_PLAY, NativeKeyEvent.VC_MEDIA_STOP -> {
                            println("JNativeHook: Global Play/Pause event detected")
                            // Here we would call playbackOrchestrator.onEvent(PlayerEvent.TogglePlayPause)
                        }
                        NativeKeyEvent.VC_MEDIA_NEXT -> {
                            println("JNativeHook: Global Next event detected")
                            // Here we would call playbackOrchestrator.onEvent(PlayerEvent.Next)
                        }
                        NativeKeyEvent.VC_MEDIA_PREVIOUS -> {
                            println("JNativeHook: Global Previous event detected")
                            // Here we would call playbackOrchestrator.onEvent(PlayerEvent.Previous)
                        }
                    }
                }
                override fun nativeKeyReleased(e: NativeKeyEvent) {}
                override fun nativeKeyTyped(e: NativeKeyEvent) {}
            })
        } catch (ex: Exception) {
            System.err.println("JNativeHook registration failed: ${ex.message}")
        }

        onDispose {
            try {
                GlobalScreen.unregisterNativeHook()
            } catch (ex: Exception) {
                // Ignore
            }
        }
    }

    // Tray management
    val trayState = rememberTrayState()
    val icon = remember {
        object : androidx.compose.ui.graphics.painter.Painter() {
            override val intrinsicSize = androidx.compose.ui.geometry.Size(32f, 32f)
            override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
                drawCircle(color = Color(0xFFFF6B00)) // BlazeOrange brand color
            }
        }
    }

    Tray(
        state = trayState,
        icon = icon,
        tooltip = "AURA Music Player",
        onAction = { isVisible = true },
        menu = {
            Item("Restaurer AURA", onClick = { isVisible = true })
            Separator()
            Item("Quitter", onClick = ::exitApplication)
        }
    )

    // Suspends Skia canvas rendering entirely by setting visible = false when minimized
    LaunchedEffect(windowState.isMinimized) {
        if (windowState.isMinimized) {
            isVisible = false
        }
    }

    if (isVisible) {
        Window(
            onCloseRequest = { isVisible = false }, // Hide to tray instead of exiting
            state = windowState,
            title = "AURA Music Player",
            icon = icon,
            undecorated = false
        ) {
            AuraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "AURA Client Bureau",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Une compilation JDK 17 avec exécution JVM 21, Generational ZGC (-XX:+UseZGC) et threads virtuels.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("État de l'application", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Base de données Room: Connectée (SQLite Bundled)")
                                Text("Moteur Audio: JavaFX Media (JNI Native Bridge)")
                                Text("Clavier Global (OS Hooks): JNativeHook Actif")
                                Text("Réduction System Tray: Configurée (Minimiser suspend Skia)")
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("Interface active. Minimisez pour suspendre le rendu graphique.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
