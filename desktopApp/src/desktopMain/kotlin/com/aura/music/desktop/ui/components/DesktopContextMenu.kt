package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.desktop.ui.*
import com.aura.music.ui.theme.*

@Composable
fun DesktopTrackContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    track: TrackListRow,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onToggleLike: () -> Unit,
    onEditMetadata: (() -> Unit)? = null,
    onDownloadCloud: (() -> Unit)? = null,
    onUploadCloud: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    offset: DpOffset = DpOffset(0.dp, 0.dp)
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        modifier = Modifier
            .width(230.dp)
            .background(DarkGraphite)
    ) {
        ContextMenuItem(
            icon = Icons.Rounded.PlayArrow,
            label = "Lire ensuite",
            onClick = {
                onPlayNext()
                onDismissRequest()
            }
        )
        ContextMenuItem(
            icon = Icons.Rounded.QueueMusic,
            label = "Ajouter à la file",
            onClick = {
                onAddToQueue()
                onDismissRequest()
            }
        )
        ContextMenuItem(
            icon = Icons.Rounded.PlaylistAdd,
            label = "Ajouter à une playlist",
            onClick = {
                onAddToPlaylist()
                onDismissRequest()
            }
        )

        HorizontalDivider(color = HairlineDark, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

        ContextMenuItem(
            icon = Icons.Rounded.Person,
            label = "Voir l'artiste",
            onClick = {
                onOpenArtist()
                onDismissRequest()
            }
        )
        if (!track.albumId.isNullOrBlank()) {
            ContextMenuItem(
                icon = Icons.Rounded.Album,
                label = "Voir l'album",
                onClick = {
                    onOpenAlbum()
                    onDismissRequest()
                }
            )
        }

        if (onEditMetadata != null && !track.contentUri.isNullOrBlank()) {
            ContextMenuItem(
                icon = Icons.Rounded.Edit,
                label = "Modifier les métadonnées",
                onClick = {
                    onEditMetadata()
                    onDismissRequest()
                }
            )
        }

        HorizontalDivider(color = HairlineDark, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

        ContextMenuItem(
            icon = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            label = if (track.isLiked) "Retirer des favoris" else "Ajouter aux favoris",
            iconTint = if (track.isLiked) BlazeOrange else PureWhite,
            onClick = {
                onToggleLike()
                onDismissRequest()
            }
        )

        if (onDownloadCloud != null && track.isCloudOnly) {
            ContextMenuItem(
                icon = Icons.Rounded.CloudDownload,
                label = "Télécharger sur le PC",
                iconTint = BlazeOrange,
                onClick = {
                    onDownloadCloud()
                    onDismissRequest()
                }
            )
        }

        if (onUploadCloud != null && !track.isCloudOnly) {
            ContextMenuItem(
                icon = Icons.Rounded.CloudUpload,
                label = "Sauvegarder sur le Cloud",
                iconTint = BlazeOrange,
                onClick = {
                    onUploadCloud()
                    onDismissRequest()
                }
            )
        }

        if (onRemoveFromPlaylist != null) {
            HorizontalDivider(color = HairlineDark, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
            ContextMenuItem(
                icon = Icons.Rounded.Delete,
                label = "Retirer de la playlist",
                iconTint = MaterialTheme.colorScheme.error,
                onClick = {
                    onRemoveFromPlaylist()
                    onDismissRequest()
                }
            )
        }
    }
}

@Composable
private fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    iconTint: androidx.compose.ui.graphics.Color = PureWhite
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = PureWhite,
                fontSize = 13.sp
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    )
}
