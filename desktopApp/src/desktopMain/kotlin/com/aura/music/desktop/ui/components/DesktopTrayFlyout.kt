package com.aura.music.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.ui.formatDuration
import com.aura.music.desktop.ui.handCursor
import com.aura.music.domain.player.PlaybackState
import com.aura.music.ui.theme.*
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * Gestionnaire natif de l'icône de barre des tâches (System Tray) sans PopupMenu AWT blanc.
 * Clic simple (gauche ou droit) -> Bascule le Flyout Mini-Player AURA.
 * Double-clic -> Restaure directement l'application principale.
 */
@Composable
fun AuraTrayIcon(
    tooltip: String = "AURA Music Player",
    onToggleFlyout: () -> Unit,
    onOpenApp: () -> Unit
) {
    if (!SystemTray.isSupported()) return

    val currentToggleFlyout by rememberUpdatedState(onToggleFlyout)
    val currentOpenApp by rememberUpdatedState(onOpenApp)

    DisposableEffect(tooltip) {
        val systemTray = SystemTray.getSystemTray()
        val iconImage: java.awt.Image? = runCatching {
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("aura_logo.png")
            if (stream != null) javax.imageio.ImageIO.read(stream) else null
        }.getOrNull()

        val image = iconImage ?: java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val trayIcon = TrayIcon(image, tooltip).apply {
            isImageAutoSize = true
            addActionListener {
                currentOpenApp()
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON3 || e.isPopupTrigger) {
                        currentToggleFlyout()
                    } else if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                        currentToggleFlyout()
                    }
                }
            })
        }

        try {
            systemTray.add(trayIcon)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        onDispose {
            try {
                systemTray.remove(trayIcon)
            } catch (_: Exception) {}
        }
    }
}

/**
 * Mini-Player Flyout moderne en Compose Desktop ancré au-dessus de la barre des tâches.
 * Remplace avantageusement le menu blanc brut Windows 95 par un panneau sombre aux couleurs d'AURA.
 */
@Composable
fun DesktopTrayFlyout(
    orchestrator: DesktopPlaybackOrchestrator,
    onOpenApp: () -> Unit,
    onQuit: () -> Unit,
    onClose: () -> Unit
) {
    val uiState by orchestrator.uiState.collectAsState()
    val currentTrack = uiState.currentTrack
    val isPlaying = orchestrator.audioPlayer.isPlaying() || uiState.playbackState == PlaybackState.Playing

    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(
            width = 340.dp,
            height = 230.dp
        ),
        title = "AURA Mini",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        focusable = true
    ) {
        // Ancrage automatique intelligent au-dessus de la zone de notification
        LaunchedEffect(Unit) {
            val gc = window.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            val bounds = gc.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val flyoutW = window.width.takeIf { it > 0 } ?: 340
            val flyoutH = window.height.takeIf { it > 0 } ?: 230
            val mouseLoc = MouseInfo.getPointerInfo()?.location

            val posX = if (mouseLoc != null) {
                (mouseLoc.x - flyoutW / 2).coerceIn(
                    bounds.x + insets.left + 12,
                    bounds.x + bounds.width - insets.right - flyoutW - 12
                )
            } else {
                bounds.x + bounds.width - insets.right - flyoutW - 12
            }
            val posY = bounds.y + bounds.height - insets.bottom - flyoutH - 12

            window.setLocation(posX, posY)
        }

        // Fermeture automatique lors d'un clic en dehors du flyout
        DisposableEffect(window) {
            val focusListener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {}
                override fun windowLostFocus(e: WindowEvent?) {
                    onClose()
                }
            }
            window.addWindowFocusListener(focusListener)
            onDispose {
                window.removeWindowFocusListener(focusListener)
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = BlazeOrange,
                background = DeepBlack,
                surface = CardDark,
                onPrimary = PureWhite,
                onBackground = PureWhite,
                onSurface = PureWhite
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                color = OffBlack,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, HairlineDark),
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 1. En-tête : Badge & Fermeture
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(BlazeOrange)
                            )
                            Text(
                                text = "AURA MUSIC",
                                color = PureWhite.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }

                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(24.dp)
                                .handCursor()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Fermer",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // 2. Bloc Média : Pochette, Titre, Artiste & Timeline
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DesktopArtworkCover(
                            coverUri = currentTrack?.coverUri,
                            size = 56.dp,
                            shapeRadius = 10.dp
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTrack?.title ?: "Aucune lecture active",
                                color = PureWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentTrack?.artistName ?: "AURA Player",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Mini timeline
                            val progress = if (uiState.durationMs > 0) {
                                (uiState.positionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
                            } else 0f

                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape),
                                color = BlazeOrange,
                                trackColor = DarkGraphite
                            )

                            Spacer(modifier = Modifier.height(3.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatDuration(uiState.positionMs),
                                    color = TextMuted,
                                    fontSize = 9.sp
                                )
                                Text(
                                    text = formatDuration(uiState.durationMs),
                                    color = TextMuted,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }

                    // 3. Contrôles de lecture
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { orchestrator.previous() },
                            modifier = Modifier
                                .size(34.dp)
                                .handCursor()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Précédent",
                                tint = PureWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BlazeOrange)
                                .clickable { orchestrator.togglePlayPause() }
                                .handCursor(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Lecture",
                                tint = PureWhite,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        IconButton(
                            onClick = { orchestrator.next() },
                            modifier = Modifier
                                .size(34.dp)
                                .handCursor()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Suivant",
                                tint = PureWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 4. Barre d'actions inférieure : Afficher AURA & Quitter
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenApp,
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .handCursor(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DarkGraphite,
                                contentColor = PureWhite
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.OpenInFull,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Afficher AURA",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        OutlinedButton(
                            onClick = onQuit,
                            modifier = Modifier
                                .height(32.dp)
                                .handCursor(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SemanticError
                            ),
                            border = BorderStroke(1.dp, HairlineDark),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Quitter",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
