package com.aura.music.desktop.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.player.QueueState
import com.aura.music.domain.player.PlayerUiState
import com.aura.music.domain.player.QueuedTrack

/**
 * Constantes et extensions d'adaptation pour l'UI Desktop AURA.
 */

// Extensions pour TrackListRow
val TrackListRow.displayArtist: String get() = artistName
val TrackListRow.displayAlbum: String? get() = albumTitle
val TrackListRow.isCloudOnly: Boolean get() = contentUri.isNullOrBlank()

// Extensions pour PlaylistListRow
val PlaylistListRow.trackCount: Int get() = itemCount

// Extensions pour PlaylistTrackRow
val PlaylistTrackRow.displayArtist: String get() = artistName
val PlaylistTrackRow.displayAlbum: String? get() = albumTitle
val PlaylistTrackRow.isCloudOnly: Boolean get() = contentUri.isNullOrBlank()

// Extensions pour QueuedTrack
val QueuedTrack.displayArtist: String get() = artistName
val QueuedTrack.displayAlbum: String? get() = albumTitle
val QueuedTrack.isCloudOnly: Boolean get() = contentUri.isNullOrBlank()

// Extensions pour les états de lecture
val QueueState.isShuffle: Boolean get() = shuffleEnabled
val PlayerUiState.isShuffle: Boolean get() = shuffleEnabled

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * Applique le curseur main (Hand) au survol d'un élément cliquable / interactif sous Desktop.
 */
fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    if (enabled) this.pointerHoverIcon(PointerIcon.Hand) else this


/**
 * Rend un composant cliquable avec curseur Main et SANS le fond surligné / ripple par défaut de Compose.
 * Supprime le rectangle gris de survol indésirable tout en permettant la capture d'événements.
 */
fun Modifier.handClickable(
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    this
        .handCursor(enabled)
        .clickable(
            interactionSource = source,
            indication = null as Indication?,
            enabled = enabled,
            onClick = onClick
        )
}


