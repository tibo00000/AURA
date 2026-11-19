package com.aura.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.music.AuraApplication
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.ElevatedGraphite

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistRefreshLayout(
    enabled: Boolean, isRefreshing: Boolean, onRefresh: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(modifier.fillMaxSize().pullToRefresh(
        isRefreshing = isRefreshing, state = state, enabled = enabled, onRefresh = onRefresh,
    )) {
        content()
        if (enabled) PullToRefreshDefaults.Indicator(
            state = state, isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter), containerColor = ElevatedGraphite, color = BlazeOrange,
        )
    }
}

internal fun playlistOriginLabel(provider: String?, pinned: Boolean = false): String {
    val origin = when (provider) {
        "spotify" -> "Playlist Spotify"
        "deezer" -> "Playlist Deezer"
        else -> "Playlist Aura"
    }
    return if (pinned) "$origin · Épinglée" else origin
}

/** Local, user-scoped read only; never initialize the sync repository for a list label. */
@Composable
internal fun rememberPlaylistSourceProviders(repository: LocalLibraryRepository): Map<String, String> {
    val context = LocalContext.current
    val auth = remember(context) { (context.applicationContext as AuraApplication).container.authSessionManager }
    val owner by auth.sessionScope.collectAsState()
    return key(owner) {
        val account = owner
        if (account == null) emptyMap() else {
            val providers by remember(repository, account) {
                repository.observePlaylistSourceProviders(account.userId)
            }.collectAsState(initial = emptyMap())
            if (auth.isCurrent(account)) providers else emptyMap()
        }
    }
}

internal enum class PlaylistSyncAction { DetachSource, RequestCloud }

@Composable
internal fun PlaylistCloudFeedback(
    summary: String, onDownloads: () -> Unit, onReport: () -> Unit, onDismiss: () -> Unit,
) {
    Surface(color = ElevatedGraphite, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = BlazeOrange)
                Spacer(Modifier.size(8.dp))
                Text("Fichiers cloud", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Masquer le suivi cloud") }
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDownloads, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Voir les téléchargements")
            }
            TextButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Voir le bilan et identifier les versions")
            }
        }
    }
}

internal data class PlaylistSyncConfirmation(
    val action: PlaylistSyncAction,
    val sourceName: String? = null,
    val retryTrackId: String? = null,
    val trackTitle: String? = null,
    val missingCount: Int? = null,
)

@Composable
internal fun PlaylistSyncConfirmationDialog(
    confirmation: PlaylistSyncConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val detach = confirmation.action == PlaylistSyncAction.DetachSource
    val retry = confirmation.retryTrackId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (detach) Icons.Rounded.LinkOff else Icons.Rounded.CloudDownload, null) },
        title = { Text(when {
            detach -> "Retirer la source ${confirmation.sourceName.orEmpty()} ?"
            retry -> "Réessayer le téléchargement cloud ?"
            else -> when (val count = confirmation.missingCount) {
                null -> "Demander les fichiers cloud ?"
                0 -> "Vérifier les fichiers cloud ?"
                1 -> "Demander 1 fichier cloud ?"
                else -> "Demander $count fichiers cloud ?"
            }
        }) },
        text = { Text(when {
            detach -> "Les prochaines actualisations ne liront plus cette source. La playlist et ses titres restent dans Aura, synchronisés entre tes appareils. Les téléchargements déjà lancés continuent."
            retry -> "Aura cherchera à nouveau un fichier audio pour « ${confirmation.trackTitle ?: "ce titre"} » dans ton cloud. Cela ne lance pas de téléchargement sur ton téléphone."
            else -> "Aura recherche les fichiers manquants et réessaie les anciennes demandes en échec. Les copies disponibles et les téléchargements en cours sont conservés. Les versions incertaines restent à choisir dans le bilan. Aucun fichier n’est copié sur le téléphone."
        }) },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(when { detach -> "Retirer la source"; retry -> "Réessayer"; else -> "Demander les fichiers" })
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
