package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import com.aura.music.data.local.PlaylistListRow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.HairlineDark
import com.aura.music.ui.theme.TextMuted

@Composable
fun PlaceholderCover(modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.MusicNote, gradient: Brush? = null) {
    Box(
        modifier = modifier
            .background(gradient ?: Brush.linearGradient(listOf(ElevatedGraphite, HairlineDark))),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun BrowseArtistRail(
    artists: List<ArtistBrowseRow>,
    onOpenArtist: (String) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyStateSurface(
            "Pas encore d'artiste local",
            "AURA affichera ici les artistes issus de la bibliotheque indexee.",
            Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = 16.dp)) {
        items(artists, key = { it.id }) { artist ->
            SharedRailCard(
                title = artist.name,
                subtitle = "${artist.trackCount} piste(s) | ${artist.albumCount} album(s)",
                imageUri = artist.pictureUri,
                gradientStartColor = Color(0xFF792BEE),
                imageShape = CircleShape,
                onClick = { onOpenArtist(artist.id) }
            )
        }
        item { Spacer(modifier = Modifier.size(16.dp)) }
    }
}

@Composable
fun BrowseAlbumRail(
    albums: List<AlbumBrowseRow>,
    onOpenAlbum: (String) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyStateSurface("Pas d'album local", "Les albums locaux indexes seront proposes ici.", Modifier.padding(horizontal = 16.dp))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = 16.dp)) {
        items(albums, key = { it.id }) { album ->
            SharedRailCard(
                title = album.title,
                subtitle = album.artistName ?: "Artiste inconnu",
                imageUri = album.coverUri,
                gradientStartColor = Color(0xFFFF9E00),
                imageShape = RoundedCornerShape(20.dp),
                onClick = { onOpenAlbum(album.id) }
            )
        }
        item { Spacer(modifier = Modifier.size(16.dp)) }
    }
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SharedRailCard(
    title: String,
    subtitle: String,
    imageUri: String?,
    gradientStartColor: Color,
    imageShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(20.dp),
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .size(width = 168.dp, height = 236.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (imageUri != null) {
                coil.compose.AsyncImage(
                    model = imageUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(imageShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(
                            Brush.linearGradient(listOf(gradientStartColor, com.aura.music.ui.theme.HairlineDark)),
                            imageShape
                        )
                )
            }
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun HeroIdentityCard(
    title: String,
    subtitle: String,
    gradient: Brush,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(modifier = Modifier.background(gradient).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DownloadStateCard(
    icon: ImageVector,
    title: String,
    message: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(modifier = Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun FilterRow(
    values: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp)) {
        items(values, key = { it }) { value ->
            Card(modifier = Modifier.clickable { onSelect(value) }, shape = RoundedCornerShape(999.dp)) {
                Text(
                    text = value,
                    modifier = Modifier
                        .background(if (value == selected) Color(0xFFFF6B00) else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = if (value == selected) Color(0xFF160A00) else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item { Spacer(modifier = Modifier.size(16.dp)) }
    }
}

@Composable
fun EmptyStateSurface(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Ligne de piste partagee entre FavoritesScreen et PlaylistDetailScreenNew.
 * Card DarkGraphite + titre + sous-titre + slot leading + slot trailing + menu contextuel.
 * 
 * Le menu contextuel varie selon le contextType :
 * - "album" : Ajouter à playlist, Ajouter aux favoris
 * - "playlist" : Retirer de playlist, Ajouter à une autre playlist
 * - "favorites" : Retirer des favoris, Ajouter à une playlist
 * - "standard" : Ajouter à playlist, Ajouter aux favoris (défaut)
 */
private data class ContextMenuItem(
    val text: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

/**
 * Ligne de piste partagee entre FavoritesScreen et PlaylistDetailScreenNew.
 * Card DarkGraphite + titre + sous-titre + slot leading + slot trailing + menu contextuel.
 * 
 * Le menu contextuel varie selon le contextType :
 * - "album" : standard
 * - "playlist" : Retirer de playlist, Ajouter à une autre playlist, Voir l'artiste, Voir l'album
 * - "favorites" : Retirer des favoris, Ajouter à une playlist, Voir l'artiste, Voir l'album
 * - "search_online" : Ajouter à une playlist, Télécharger, Voir l'artiste, Voir l'album
 * - "standard" : Lire maintenant, Ajouter à la file, Ajouter à playlist, Ajouter aux favoris, Voir artiste/album, Télécharger, Supprimer
 */
@Composable
fun SharedTrackRowItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverUri: String? = null,
    showCover: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
    contextType: String = "standard",  // "album", "playlist", "favorites", "search_online", "standard", "artist"
    isLiked: Boolean = false,
    onPlayNow: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onLike: (() -> Unit)? = null,
    onUnlike: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onViewArtist: (() -> Unit)? = null,
    onViewAlbum: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val menuItems = remember(
        contextType,
        isLiked,
        onPlayNow,
        onAddToQueue,
        onAddToPlaylist,
        onLike,
        onUnlike,
        onRemoveFromPlaylist,
        onViewArtist,
        onViewAlbum,
        onDownload,
        onDeleteDownload
    ) {
        val items = mutableListOf<ContextMenuItem>()
        when (contextType) {
            "playlist" -> {
                if (onRemoveFromPlaylist != null) {
                    items.add(ContextMenuItem("Retirer de cette playlist", Icons.Rounded.Delete, onRemoveFromPlaylist))
                }
                if (onAddToPlaylist != null) {
                    items.add(ContextMenuItem("Ajouter à une autre playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                }
                if (isLiked) {
                    if (onUnlike != null) {
                        items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike))
                    }
                } else {
                    if (onLike != null) {
                        items.add(ContextMenuItem("Ajouter aux favoris", Icons.Rounded.FavoriteBorder, onLike))
                    }
                }
                if (onViewArtist != null) {
                    items.add(ContextMenuItem("Voir l'artiste", Icons.Rounded.Person, onViewArtist))
                }
                if (onViewAlbum != null) {
                    items.add(ContextMenuItem("Voir l'album", Icons.Rounded.Album, onViewAlbum))
                }
            }
            "favorites" -> {
                if (isLiked) {
                    if (onUnlike != null) {
                        items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike))
                    }
                } else {
                    if (onLike != null) {
                        items.add(ContextMenuItem("Ajouter aux favoris", Icons.Rounded.FavoriteBorder, onLike))
                    }
                }
                if (onAddToPlaylist != null) {
                    items.add(ContextMenuItem("Ajouter à une playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                }
                if (onViewArtist != null) {
                    items.add(ContextMenuItem("Voir l'artiste", Icons.Rounded.Person, onViewArtist))
                }
                if (onViewAlbum != null) {
                    items.add(ContextMenuItem("Voir l'album", Icons.Rounded.Album, onViewAlbum))
                }
            }
            "search_online" -> {
                if (onDownload != null) {
                    items.add(ContextMenuItem("Télécharger", Icons.Rounded.ArrowDownward, onDownload))
                }
                if (onViewArtist != null) {
                    items.add(ContextMenuItem("Voir l'artiste", Icons.Rounded.Person, onViewArtist))
                }
                if (onViewAlbum != null) {
                    items.add(ContextMenuItem("Voir l'album", Icons.Rounded.Album, onViewAlbum))
                }
            }
            "artist" -> {
                if (onPlayNow != null) {
                    items.add(ContextMenuItem("Lire maintenant", Icons.Rounded.PlayArrow, onPlayNow))
                }
                if (onAddToQueue != null) {
                    items.add(ContextMenuItem("Ajouter à la file d'attente", Icons.Rounded.QueueMusic, onAddToQueue))
                }
                if (onAddToPlaylist != null) {
                    items.add(ContextMenuItem("Ajouter à une playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                }
                if (isLiked) {
                    if (onUnlike != null) {
                        items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike))
                    }
                } else {
                    if (onLike != null) {
                        items.add(ContextMenuItem("Ajouter aux favoris", Icons.Rounded.FavoriteBorder, onLike))
                    }
                }
                if (onViewAlbum != null) {
                    items.add(ContextMenuItem("Voir l'album", Icons.Rounded.Album, onViewAlbum))
                }
                if (onDeleteDownload != null) {
                    items.add(ContextMenuItem("Supprimer", Icons.Rounded.Delete, onDeleteDownload))
                }
            }
            "album" -> {
                if (onPlayNow != null) {
                    items.add(ContextMenuItem("Lire maintenant", Icons.Rounded.PlayArrow, onPlayNow))
                }
                if (onAddToQueue != null) {
                    items.add(ContextMenuItem("Ajouter à la file d'attente", Icons.Rounded.QueueMusic, onAddToQueue))
                }
                if (onAddToPlaylist != null) {
                    items.add(ContextMenuItem("Ajouter à une playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                }
                if (isLiked) {
                    if (onUnlike != null) {
                        items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike))
                    }
                } else {
                    if (onLike != null) {
                        items.add(ContextMenuItem("Ajouter aux favoris", Icons.Rounded.FavoriteBorder, onLike))
                    }
                }
                if (onViewArtist != null) {
                    items.add(ContextMenuItem("Voir l'artiste", Icons.Rounded.Person, onViewArtist))
                }
                if (onDeleteDownload != null) {
                    items.add(ContextMenuItem("Supprimer", Icons.Rounded.Delete, onDeleteDownload))
                }
            }
            else -> { // standard, search_local, library_tracks, etc.
                if (onPlayNow != null) {
                    items.add(ContextMenuItem("Lire maintenant", Icons.Rounded.PlayArrow, onPlayNow))
                }
                if (onAddToQueue != null) {
                    items.add(ContextMenuItem("Ajouter à la file d'attente", Icons.Rounded.QueueMusic, onAddToQueue))
                }
                if (onAddToPlaylist != null) {
                    items.add(ContextMenuItem("Ajouter à une playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                }
                if (isLiked) {
                    if (onUnlike != null) {
                        items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike))
                    }
                } else {
                    if (onLike != null) {
                        items.add(ContextMenuItem("Ajouter aux favoris", Icons.Rounded.FavoriteBorder, onLike))
                    }
                }
                if (onViewArtist != null) {
                    items.add(ContextMenuItem("Voir l'artiste", Icons.Rounded.Person, onViewArtist))
                }
                if (onViewAlbum != null) {
                    items.add(ContextMenuItem("Voir l'album", Icons.Rounded.Album, onViewAlbum))
                }
                if (onDownload != null) {
                    items.add(ContextMenuItem("Télécharger", Icons.Rounded.ArrowDownward, onDownload))
                }
                if (onDeleteDownload != null) {
                    items.add(ContextMenuItem("Supprimer", Icons.Rounded.Delete, onDeleteDownload))
                }
            }
        }
        items
    }

    androidx.compose.material3.Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFF1E1E1E))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showCover) {
                if (coverUri != null) {
                    AsyncImage(
                        model = coverUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    PlaceholderCover(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (trailingIcon != null) {
                trailingIcon()
            } else if (menuItems.isNotEmpty()) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Menu contextuel",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        menuItems.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.text) },
                                onClick = {
                                    item.onClick()
                                    menuExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialogue de selection de playlist (UI pure conforme MVVM).
 */
@Composable
fun SelectPlaylistDialog(
    playlists: List<PlaylistListRow>,
    onDismiss: () -> Unit,
    onPlaylistSelected: (PlaylistListRow) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter à une playlist") },
        text = {
            if (playlists.isEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                ) {
                    Text("Aucune playlist locale", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        ListItem(
                            headlineContent = { Text(playlist.name) },
                            supportingContent = { Text("${playlist.itemCount} piste(s)") },
                            modifier = Modifier.clickable { onPlaylistSelected(playlist) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
