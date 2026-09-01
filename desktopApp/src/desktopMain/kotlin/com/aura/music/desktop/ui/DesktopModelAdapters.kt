package com.aura.music.desktop.ui

import androidx.compose.ui.graphics.Color
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
