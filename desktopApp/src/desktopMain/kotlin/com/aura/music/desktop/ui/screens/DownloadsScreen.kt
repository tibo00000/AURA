package com.aura.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.aura.music.data.local.DownloadJobRowModel
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*

@Composable
fun DownloadsScreen(
    downloadJobs: List<DownloadJobRowModel>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // En-tête
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Téléchargements",
                    color = PureWhite,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${downloadJobs.size} tâches au total",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }

            if (downloadJobs.isNotEmpty()) {
                Button(
                    onClick = { orchestrator.clearCompletedDownloadJobs() },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkGraphite),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.DeleteSweep, contentDescription = null, tint = PureWhite, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Nettoyer l'historique", color = PureWhite, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (downloadJobs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDone,
                        contentDescription = null,
                        tint = PureWhite.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Aucun téléchargement en cours",
                        color = PureWhite.copy(alpha = 0.5f),
                        fontSize = 16.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(downloadJobs, key = { it.jobId }) { jobItem ->
                    DownloadJobRowItem(
                        jobItem = jobItem,
                        onRetry = { orchestrator.retryDownloadJob(jobItem.jobId) },
                        onCancel = { orchestrator.cancelDownloadJob(jobItem.jobId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadJobRowItem(
    jobItem: DownloadJobRowModel,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = OffBlack)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Statut Icon
            val (icon, tint) = when (jobItem.status.lowercase()) {
                "running" -> Icons.Rounded.Download to BlazeOrange
                "queued" -> Icons.Rounded.HourglassEmpty to PureWhite.copy(alpha = 0.6f)
                "succeeded", "completed" -> Icons.Rounded.CheckCircle to Color(0xFF4CAF50)
                "failed" -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
                else -> Icons.Rounded.CloudDownload to BlazeOrange
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Titre & Artiste & Progression
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = jobItem.title,
                    color = PureWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = jobItem.artistName,
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (jobItem.status.equals("running", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (jobItem.progressPercent ?: 0f) / 100f },
                        color = BlazeOrange,
                        trackColor = DarkGraphite,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    )
                }

                if (!jobItem.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = jobItem.errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Statut texte
            Text(
                text = when (jobItem.status.lowercase()) {
                    "running" -> "${(jobItem.progressPercent ?: 0f).toInt()}%"
                    "queued" -> "En attente"
                    "succeeded", "completed" -> "Terminé"
                    "failed" -> "Échoué"
                    else -> jobItem.status
                },
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(90.dp)
            )

            // Actions
            if (jobItem.status.equals("failed", ignoreCase = true)) {
                IconButton(onClick = onRetry) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "Réessayer", tint = BlazeOrange)
                }
            }

            if (jobItem.status.equals("running", ignoreCase = true) || jobItem.status.equals("queued", ignoreCase = true)) {
                IconButton(onClick = onCancel) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Annuler", tint = PureWhite.copy(alpha = 0.5f))
                }
            }
        }
    }
}
