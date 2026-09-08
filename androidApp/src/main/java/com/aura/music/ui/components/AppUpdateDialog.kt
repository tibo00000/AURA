package com.aura.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.aura.music.core.AppUpdateManager
import com.aura.music.core.UpdateState

@Composable
fun AppUpdateDialog(
    state: UpdateState,
    updateManager: AppUpdateManager,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is UpdateState.UpdateAvailable -> {
            val isMandatory = state.isMandatory
            AlertDialog(
                onDismissRequest = {
                    if (!isMandatory) updateManager.dismiss()
                },
                properties = DialogProperties(
                    dismissOnBackPress = !isMandatory,
                    dismissOnClickOutside = !isMandatory,
                ),
                icon = {
                    UpdateHeaderIcon(
                        icon = Icons.Rounded.CloudDownload,
                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Mise à jour disponible",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        VersionBadge(
                            versionName = state.updateInfo.versionName,
                            isMandatory = isMandatory,
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (isMandatory) {
                            Text(
                                text = "Cette mise à jour contient des correctifs critiques nécessaires pour continuer à utiliser AURA.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }

                        if (!state.updateInfo.releaseNotes.isNullOrBlank()) {
                            Text(
                                text = "Nouveautés :",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = state.updateInfo.releaseNotes,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { updateManager.startDownload(state.updateInfo) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Mettre à jour")
                    }
                },
                dismissButton = if (!isMandatory) {
                    {
                        TextButton(onClick = { updateManager.dismiss() }) {
                            Text("Plus tard")
                        }
                    }
                } else null,
                modifier = modifier,
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {}, // Téléchargement non interruptible via tap externe
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
                icon = {
                    UpdateHeaderIcon(
                        icon = Icons.Rounded.CloudDownload,
                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                title = {
                    Text(
                        text = "Téléchargement en cours...",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (state.totalBytes > 0) {
                                val currentMb = state.bytesDownloaded.toDouble() / (1024 * 1024)
                                val totalMb = state.totalBytes.toDouble() / (1024 * 1024)
                                Text(
                                    text = "%.1f / %.1f Mo".format(currentMb, totalMb),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Validation de l'empreinte SHA-256 en fin de transfert...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {},
                modifier = modifier,
            )
        }

        is UpdateState.PermissionRequired -> {
            AlertDialog(
                onDismissRequest = { updateManager.dismiss() },
                icon = {
                    UpdateHeaderIcon(
                        icon = Icons.Rounded.Security,
                        backgroundColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                title = {
                    Text(
                        text = "Autorisation d'installation",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        text = "Pour appliquer la mise à jour, Android exige que vous autorisiez AURA à installer des applications inconnues.\n\nCliquez sur 'Paramètres' pour activer l'autorisation, puis revenez pour installer.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    Button(onClick = { updateManager.openInstallPermissionSettings() }) {
                        Text("Paramètres")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        if (updateManager.canInstallUnknownApps()) {
                            updateManager.launchInstaller(state.apkFile)
                        } else {
                            updateManager.dismiss()
                        }
                    }) {
                        Text(if (updateManager.canInstallUnknownApps()) "Installer" else "Annuler")
                    }
                },
                modifier = modifier,
            )
        }

        is UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { updateManager.dismiss() },
                icon = {
                    UpdateHeaderIcon(
                        icon = Icons.Rounded.CheckCircle,
                        backgroundColor = Color(0xFF4CAF50).copy(alpha = 0.15f),
                        tint = Color(0xFF4CAF50),
                    )
                },
                title = {
                    Text(
                        text = "Package prêt à installer",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        text = "Le fichier APK a été téléchargé et son intégrité SHA-256 a été validée avec succès.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    Button(onClick = { updateManager.launchInstaller(state.apkFile) }) {
                        Text("Installer")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateManager.dismiss() }) {
                        Text("Fermer")
                    }
                },
                modifier = modifier,
            )
        }

        is UpdateState.Error -> {
            AlertDialog(
                onDismissRequest = { updateManager.dismiss() },
                icon = {
                    UpdateHeaderIcon(
                        icon = Icons.Rounded.ErrorOutline,
                        backgroundColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                title = {
                    Text(
                        text = "Mise à jour impossible",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    if (state.canRetry && state.updateInfo != null) {
                        Button(onClick = { updateManager.startDownload(state.updateInfo) }) {
                            Text("Réessayer")
                        }
                    } else {
                        Button(onClick = { updateManager.dismiss() }) {
                            Text("Compris")
                        }
                    }
                },
                dismissButton = if (state.canRetry && state.updateInfo != null) {
                    {
                        TextButton(onClick = { updateManager.dismiss() }) {
                            Text("Fermer")
                        }
                    }
                } else null,
                modifier = modifier,
            )
        }

        UpdateState.Idle,
        UpdateState.Checking,
        UpdateState.UpToDate -> {
            // Aucun dialogue à afficher
        }
    }
}

@Composable
private fun UpdateHeaderIcon(
    icon: ImageVector,
    backgroundColor: Color,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun VersionBadge(
    versionName: String,
    isMandatory: Boolean,
) {
    val bgColor = if (isMandatory) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    }
    val textColor = if (isMandatory) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            text = if (isMandatory) "v$versionName • Requise" else "v$versionName",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
