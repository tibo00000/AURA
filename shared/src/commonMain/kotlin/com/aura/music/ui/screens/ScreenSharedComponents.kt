package com.aura.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.HairlineDark
import com.aura.music.ui.theme.RoseSignal
import com.aura.music.ui.theme.TextMuted

/**
 * Représente l'état de téléchargement local d'un titre pour les affichages en ligne.
 */
@Immutable
sealed interface TrackDownloadStatus {
    object Idle : TrackDownloadStatus
    object NotDownloaded : TrackDownloadStatus
    object Queued : TrackDownloadStatus
    data class Downloading(val progressPercent: Float = 0f) : TrackDownloadStatus
    object Downloaded : TrackDownloadStatus
    data class Failed(val errorCode: String? = null, val message: String? = null) : TrackDownloadStatus
}

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
fun SleekSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color = Color(0xFFFF6B00), // BlazeOrange
    inactiveColor: Color = Color(0xFF1A1A1A), // DarkGraphite
) {
    val density = LocalDensity.current
    var width by remember { mutableStateOf(1) }
    val rangeLength = valueRange.endInclusive - valueRange.start
    val fraction = ((value - valueRange.start) / if (rangeLength > 0f) rangeLength else 1f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(valueRange, rangeLength) {
                detectTapGestures(
                    onPress = { offset ->
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + frac * rangeLength)
                        if (onValueChangeFinished != null) {
                            tryAwaitRelease()
                            onValueChangeFinished()
                        }
                    }
                )
            }
            .pointerInput(valueRange, rangeLength) {
                detectHorizontalDragGestures(
                    onDragEnd = { onValueChangeFinished?.invoke() },
                    onHorizontalDrag = { change, _ ->
                        val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + frac * rangeLength)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Track Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(inactiveColor, shape = RoundedCornerShape(1.5.dp))
        )
        // Active Track
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(activeColor, shape = RoundedCornerShape(1.5.dp))
        )
        
        // Thumb (small dot)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    val thumbSizePx = with(density) { 8.dp.toPx() }
                    val maxOffset = width - thumbSizePx
                    translationX = (maxOffset * fraction).coerceAtLeast(0f)
                }
                .size(8.dp)
                .background(activeColor, shape = CircleShape)
        )
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
                AsyncImage(
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
    contextType: String = "standard",
    isLiked: Boolean = false,
    downloadStatus: TrackDownloadStatus = TrackDownloadStatus.NotDownloaded,
    isCloudOnly: Boolean = false,
    isOfflineDisabled: Boolean = false,
    onOfflineBlocked: (() -> Unit)? = null,
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
    onUploadToCloud: (() -> Unit)? = null,
    onDownloadFromCloud: (() -> Unit)? = null,
    onDeleteFromCloud: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val hasMenu = onPlayNow != null || onAddToQueue != null || onAddToPlaylist != null ||
            onLike != null || onUnlike != null || onRemoveFromPlaylist != null ||
            onViewArtist != null || onViewAlbum != null || onDownload != null ||
            onDeleteDownload != null || onUploadToCloud != null ||
            onDownloadFromCloud != null || onDeleteFromCloud != null || onEditMetadata != null

    val effectiveAlpha = if (isOfflineDisabled) 0.38f else 1.0f
    val onCardClick: () -> Unit = if (isOfflineDisabled) {
        { onOfflineBlocked?.invoke() }
    } else {
        onClick
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(effectiveAlpha),
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
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                PlaceholderCover(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (onLike != null || onUnlike != null) {
                IconButton(
                        onClick = { if (isLiked) onUnlike?.invoke() else onLike?.invoke() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isLiked) "Retirer des favoris" else "Ajouter aux favoris",
                            tint = if (isLiked) BlazeOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (isCloudOnly && (downloadStatus is TrackDownloadStatus.Idle || downloadStatus is TrackDownloadStatus.NotDownloaded)) {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Cloud,
                            contentDescription = "Sur le Cloud",
                            tint = BlazeOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    when (downloadStatus) {
                        is TrackDownloadStatus.Idle, is TrackDownloadStatus.NotDownloaded -> {
                            if (onUploadToCloud != null) {
                                IconButton(onClick = onUploadToCloud, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.CloudUpload, contentDescription = "Ajouter au Cloud", tint = BlazeOrange, modifier = Modifier.size(20.dp))
                                }
                            } else if (onDownload != null) {
                                IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        imageVector = if (contextType == "search_online") Icons.Rounded.CloudDownload else Icons.Rounded.Download,
                                        contentDescription = if (contextType == "search_online") "Ajouter au Cloud" else "Télécharger sur l'appareil",
                                        tint = if (contextType == "search_online") BlazeOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        is TrackDownloadStatus.Queued -> {
                            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Schedule, contentDescription = "En attente", tint = BlazeOrange, modifier = Modifier.size(18.dp))
                            }
                        }
                        is TrackDownloadStatus.Downloading -> {
                            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                val progress = (downloadStatus.progressPercent / 100f).coerceIn(0f, 1f)
                                CircularProgressIndicator(progress = { progress }, color = BlazeOrange, strokeWidth = 2.5.dp, modifier = Modifier.size(18.dp))
                            }
                        }
                        is TrackDownloadStatus.Downloaded -> {
                            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = "Téléchargé", tint = Color(0xFF00E676), modifier = Modifier.size(18.dp))
                            }
                        }
                        is TrackDownloadStatus.Failed -> {
                            IconButton(onClick = { onDownload?.invoke() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.ErrorOutline, contentDescription = "Échec", tint = Color.Red, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                if (trailingIcon != null) {
                    trailingIcon()
                } else if (hasMenu) {
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
                        if (menuExpanded) {
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
                                onDeleteDownload,
                                onUploadToCloud,
                                onDownloadFromCloud,
                                onDeleteFromCloud,
                                onEditMetadata
                            ) {
                                val items = mutableListOf<ContextMenuItem>()
                                when (contextType) {
                                    "playlist" -> {
                                        if (onRemoveFromPlaylist != null) items.add(ContextMenuItem("Retirer de cette playlist", Icons.Rounded.Delete, onRemoveFromPlaylist))
                                        if (onAddToQueue != null) items.add(ContextMenuItem("Ajouter à la file d'attente", Icons.Rounded.QueueMusic, onAddToQueue))
                                        if (isLiked) { if (onUnlike != null) items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike)) }
                                        else { if (onLike != null) items.add(ContextMenuItem("Ajouter aux favoris", Icons.Rounded.FavoriteBorder, onLike)) }
                                        if (onViewArtist != null) items.add(ContextMenuItem("Voir l'artiste", Icons.Rounded.Person, onViewArtist))
                                        if (onViewAlbum != null) items.add(ContextMenuItem("Voir l'album", Icons.Rounded.Album, onViewAlbum))
                                        if (onDownload != null) items.add(ContextMenuItem("Télécharger sur l'appareil", Icons.Rounded.Download, onDownload))
                                        if (onDeleteDownload != null) items.add(ContextMenuItem("Supprimer du téléphone", Icons.Rounded.Delete, onDeleteDownload))
                                    }
                                    "album" -> {
                                        if (onPlayNow != null) items.add(ContextMenuItem("Écouter maintenant", Icons.Rounded.PlayArrow, onPlayNow))
                                        if (onAddToQueue != null) items.add(ContextMenuItem("Ajouter à la file d'attente", Icons.Rounded.QueueMusic, onAddToQueue))
                                        if (onAddToPlaylist != null) items.add(ContextMenuItem("Ajouter à une playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                                        if (isLiked) { if (onUnlike != null) items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike)) }
                                        else { if (onLike != null) items.add(ContextMenuItem("Ajouter aux favoris", Icons.Rounded.FavoriteBorder, onLike)) }
                                        if (onViewArtist != null) items.add(ContextMenuItem("Voir l'artiste", Icons.Rounded.Person, onViewArtist))
                                        if (onDownload != null) items.add(ContextMenuItem("Télécharger sur l'appareil", Icons.Rounded.Download, onDownload))
                                        if (onDeleteDownload != null) items.add(ContextMenuItem("Supprimer du téléphone", Icons.Rounded.Delete, onDeleteDownload))
                                    }
                                    "artist" -> {
                                        if (onPlayNow != null) items.add(ContextMenuItem("Écouter maintenant", Icons.Rounded.PlayArrow, onPlayNow))
                                        if (onAddToQueue != null) items.add(ContextMenuItem("Ajouter à la file d'attente", Icons.Rounded.QueueMusic, onAddToQueue))
                                        if (onAddToPlaylist != null) items.add(ContextMenuItem("Ajouter à une playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                                        if (isLiked) { if (onUnlike != null) items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike)) }
                                        else { if (onLike != null) items.add(ContextMenuItem("Ajouter aux favoris", Icons.Rounded.FavoriteBorder, onLike)) }
                                        if (onViewAlbum != null) items.add(ContextMenuItem("Voir l'album", Icons.Rounded.Album, onViewAlbum))
                                        if (onDownload != null) items.add(ContextMenuItem("Télécharger sur l'appareil", Icons.Rounded.Download, onDownload))
                                        if (onDeleteDownload != null) items.add(ContextMenuItem("Supprimer du téléphone", Icons.Rounded.Delete, onDeleteDownload))
                                    }
                                    "favorites" -> {
                                        if (onPlayNow != null) items.add(ContextMenuItem("Écouter maintenant", Icons.Rounded.PlayArrow, onPlayNow))
                                        if (onAddToQueue != null) items.add(ContextMenuItem("Ajouter à la file d'attente", Icons.Rounded.QueueMusic, onAddToQueue))
                                        if (onAddToPlaylist != null) items.add(ContextMenuItem("Ajouter à une playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                                        if (onUnlike != null) items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike))
                                        if (onViewArtist != null) items.add(ContextMenuItem("Voir l'artiste", Icons.Rounded.Person, onViewArtist))
                                        if (onViewAlbum != null) items.add(ContextMenuItem("Voir l'album", Icons.Rounded.Album, onViewAlbum))
                                        if (onDownload != null) items.add(ContextMenuItem("Télécharger sur l'appareil", Icons.Rounded.Download, onDownload))
                                        if (onDeleteDownload != null) items.add(ContextMenuItem("Supprimer du téléphone", Icons.Rounded.Delete, onDeleteDownload))
                                    }
                                    "search_online" -> {
                                        if (onPlayNow != null) items.add(ContextMenuItem("Écouter", Icons.Rounded.PlayArrow, onPlayNow))
                                        if (onAddToQueue != null) items.add(ContextMenuItem("Ajouter à la file d'attente", Icons.Rounded.QueueMusic, onAddToQueue))
                                        if (onAddToPlaylist != null) items.add(ContextMenuItem("Ajouter à une playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                                        if (onDownload != null) items.add(ContextMenuItem("Ajouter au Cloud personnel", Icons.Rounded.CloudDownload, onDownload))
                                        if (onUploadToCloud != null) items.add(ContextMenuItem("Sauvegarder le fichier local sur le Cloud", Icons.Rounded.CloudUpload, onUploadToCloud))
                                    }
                                    else -> {
                                        if (onPlayNow != null) items.add(ContextMenuItem("Écouter maintenant", Icons.Rounded.PlayArrow, onPlayNow))
                                        if (onAddToQueue != null) items.add(ContextMenuItem("Ajouter à la file d'attente", Icons.Rounded.QueueMusic, onAddToQueue))
                                        if (onAddToPlaylist != null) items.add(ContextMenuItem("Ajouter à une playlist", Icons.Rounded.PlaylistAdd, onAddToPlaylist))
                                        if (isLiked) { if (onUnlike != null) items.add(ContextMenuItem("Retirer des favoris", Icons.Rounded.Favorite, onUnlike)) }
                                        else { if (onLike != null) items.add(ContextMenuItem("Ajouter aux favoris", Icons.Rounded.FavoriteBorder, onLike)) }
                                        if (onViewArtist != null) items.add(ContextMenuItem("Voir l'artiste", Icons.Rounded.Person, onViewArtist))
                                        if (onViewAlbum != null) items.add(ContextMenuItem("Voir l'album", Icons.Rounded.Album, onViewAlbum))
                                        if (onDownload != null) items.add(ContextMenuItem("Télécharger sur l'appareil", Icons.Rounded.Download, onDownload))
                                        if (onDeleteDownload != null) items.add(ContextMenuItem("Supprimer du téléphone", Icons.Rounded.Delete, onDeleteDownload))
                                    }
                                }
                                if (onUploadToCloud != null && contextType != "search_online") items.add(ContextMenuItem("Ajouter au Cloud", Icons.Rounded.CloudUpload, onUploadToCloud))
                                if (onDownloadFromCloud != null) items.add(ContextMenuItem("Récupérer depuis le Cloud", Icons.Rounded.CloudDownload, onDownloadFromCloud))
                                if (onDeleteFromCloud != null) items.add(ContextMenuItem("Supprimer du Cloud", Icons.Rounded.Delete, onDeleteFromCloud))
                                if (onEditMetadata != null) items.add(ContextMenuItem("Modifier les informations", Icons.Rounded.Edit, onEditMetadata))
                                items
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
