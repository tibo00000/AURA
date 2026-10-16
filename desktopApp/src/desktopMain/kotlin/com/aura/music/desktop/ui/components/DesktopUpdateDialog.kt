package com.aura.music.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aura.music.desktop.domain.DesktopUpdateManager
import com.aura.music.desktop.domain.DesktopUpdateState
import com.aura.music.desktop.utils.DesktopInstallMode
import com.aura.music.ui.theme.*
import java.awt.Desktop
import java.net.URI

@Composable
fun DesktopUpdateDialog(
    state: DesktopUpdateState,
    updateManager: DesktopUpdateManager,
    modifier: Modifier = Modifier
) {
    when (state) {
        is DesktopUpdateState.UpdateAvailable -> {
            val isMandatory = state.isMandatory
            Dialog(
                onDismissRequest = { if (!isMandatory) updateManager.dismiss() }
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = OffBlack,
                    border = BorderStroke(1.dp, HairlineDark),
                    modifier = modifier.widthIn(max = 520.dp).padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Icône
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(BlazeOrange.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                tint = BlazeOrange,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Titre & Badge version
                        Text(
                            text = "Mise à jour disponible",
                            color = PureWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = BlazeOrange.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, BlazeOrange.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "Version ${state.updateInfo.versionName}",
                                color = BlazeOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Notes de version
                        val notes = state.updateInfo.releaseNotes
                        if (!notes.isNullOrBlank()) {
                            Surface(
                                color = DarkGraphite,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, HairlineDark),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(14.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = "Nouveautés :",
                                        color = PureWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = notes,
                                        color = PureWhite.copy(alpha = 0.8f),
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Boutons d'action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                        ) {
                            if (!isMandatory) {
                                OutlinedButton(
                                    onClick = { updateManager.dismiss() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite.copy(alpha = 0.7f)),
                                    border = BorderStroke(1.dp, HairlineDark),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Plus tard", fontSize = 13.sp)
                                }
                            }

                            if (state.installMode == DesktopInstallMode.INSTALLED) {
                                Button(
                                    onClick = { updateManager.startDownload(state.updateInfo) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Mettre à jour maintenant", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        try {
                                            Desktop.getDesktop().browse(URI(state.updateInfo.downloadUrl))
                                        } catch (_: Exception) { }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Télécharger la version portable", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        is DesktopUpdateState.Downloading -> {
            Dialog(onDismissRequest = { /* Bloquant pendant le streaming */ }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = OffBlack,
                    border = BorderStroke(1.dp, HairlineDark),
                    modifier = modifier.widthIn(max = 460.dp).padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = BlazeOrange,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(44.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Téléchargement de la mise à jour...",
                            color = PureWhite,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Vérification cryptographique SHA-256 en cours",
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        LinearProgressIndicator(
                            progress = { state.progress },
                            color = BlazeOrange,
                            trackColor = DarkGraphite,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                color = PureWhite.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (state.totalBytes > 0) {
                                val mbDownloaded = state.bytesDownloaded / (1024f * 1024f)
                                val mbTotal = state.totalBytes / (1024f * 1024f)
                                Text(
                                    text = "%.1f Mo / %.1f Mo".format(mbDownloaded, mbTotal),
                                    color = PureWhite.copy(alpha = 0.5f),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "AURA se fermera brièvement dès que le téléchargement sera validé.",
                            color = PureWhite.copy(alpha = 0.4f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        is DesktopUpdateState.PostUpdateNotification -> {
            Dialog(onDismissRequest = { updateManager.dismiss() }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = OffBlack,
                    border = BorderStroke(1.dp, HairlineDark),
                    modifier = modifier.widthIn(max = 480.dp).padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val iconColor = if (state.success) Color(0xFF4CAF50) else Color(0xFFFF5252)
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(iconColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = if (state.success) "Mise à jour réussie" else "Échec de la mise à jour",
                            color = PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            color = PureWhite.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                        ) {
                            if (!state.success && state.logFile != null && state.logFile.exists()) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            Desktop.getDesktop().open(state.logFile)
                                        } catch (_: Exception) { }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite.copy(alpha = 0.8f)),
                                    border = BorderStroke(1.dp, HairlineDark),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Rounded.Article, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Consulter le journal", fontSize = 12.sp)
                                }
                            }

                            if (!state.success) {
                                Button(
                                    onClick = {
                                        updateManager.dismiss()
                                        updateManager.checkForUpdate(isManual = true)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Réessayer", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                Button(
                                    onClick = { updateManager.dismiss() },
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Parfait", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        is DesktopUpdateState.Error -> {
            Dialog(onDismissRequest = { updateManager.dismiss() }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = OffBlack,
                    border = BorderStroke(1.dp, HairlineDark),
                    modifier = modifier.widthIn(max = 460.dp).padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5252).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Erreur de mise à jour",
                            color = PureWhite,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            color = PureWhite.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                        ) {
                            OutlinedButton(
                                onClick = { updateManager.dismiss() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite.copy(alpha = 0.7f)),
                                border = BorderStroke(1.dp, HairlineDark),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Fermer", fontSize = 12.sp)
                            }

                            if (state.canRetry && state.updateInfo != null) {
                                Button(
                                    onClick = { updateManager.startDownload(state.updateInfo) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Réessayer", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        else -> { /* Idle, Checking, UpToDate : géré inline dans SettingsScreen */ }
    }
}
