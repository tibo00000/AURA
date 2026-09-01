package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aura.music.data.local.DownloadJobRowModel
import com.aura.music.desktop.ui.theme.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun DesktopDownloadErrorDetailDialog(
    job: DownloadJobRowModel?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    if (job == null) return

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(560.dp)
                .wrapContentHeight()
                .clip(RoundedCornerShape(16.dp)),
            color = OffBlack
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Détail de l'échec de téléchargement",
                        color = PureWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "${job.title} — ${job.artistName}",
                    color = PureWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Zone de logs techniques
                Surface(
                    color = DeepBlack,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Code : ${job.errorCode ?: "ERR_UNKNOWN"}",
                                color = BlazeOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Moteur : yt-dlp / deezer",
                                color = PureWhite.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = job.errorMessage ?: "Aucun détail d'erreur retourné par le serveur.",
                            color = PureWhite.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Conseil : Vérifiez votre connexion Internet ou réessayez ultérieurement. Si le flux distant a expiré, une nouvelle tentative régénérera les cookies.",
                    color = PureWhite.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val logContent = "Titre: ${job.title}\nArtiste: ${job.artistName}\nCode: ${job.errorCode}\nErreur: ${job.errorMessage}"
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(logContent), null)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite)
                    ) {
                        Icon(imageVector = Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copier le rapport")
                    }

                    Row {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = PureWhite.copy(alpha = 0.7f))
                        ) {
                            Text("Fermer")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                onRetry()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                        ) {
                            Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Réessayer")
                        }
                    }
                }
            }
        }
    }
}
