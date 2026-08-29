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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.PlaylistListRow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.DeepBlack
import com.aura.music.ui.theme.ElevatedGraphite
import com.aura.music.ui.theme.HairlineDark
import com.aura.music.ui.theme.RoseSignal
import com.aura.music.ui.theme.TextMuted
import com.aura.music.ui.theme.TextPrimary
import com.aura.music.ui.theme.TextSecondary

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
    val rangeLength = valueRange.endInclusive - valueRange.start
    val fraction = ((value - valueRange.start) / if (rangeLength > 0f) rangeLength else 1f).coerceIn(0f, 1f)

    val currentRange by rememberUpdatedState(valueRange)
    val currentLength by rememberUpdatedState(rangeLength)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp)
            .semantics {
                contentDescription = "Position de lecture"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(currentRange.start, currentRange.endInclusive),
                    range = currentRange
                )
                setProgress { targetValue ->
                    currentOnChange(targetValue.coerceIn(currentRange.start, currentRange.endInclusive))
                    currentOnFinished?.invoke()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val length = currentLength
                        val rawFrac = (offset.x / size.width).coerceIn(0f, 1f)
                        val frac = if (isRtl) 1f - rawFrac else rawFrac
                        currentOnChange(currentRange.start + frac * length)
                        if (currentOnFinished != null) {
                            tryAwaitRelease()
                            currentOnFinished?.invoke()
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { currentOnFinished?.invoke() },
                    onHorizontalDrag = { change, _ ->
                        val length = currentLength
                        val rawFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                        val frac = if (isRtl) 1f - rawFrac else rawFrac
                        currentOnChange(currentRange.start + frac * length)
                    }
                )
            }
            .drawBehind {
                val trackHeightPx = 3.dp.toPx()
                val trackCornerRadiusPx = 1.5.dp.toPx()
                val thumbRadiusPx = 4.dp.toPx()
                val centerY = size.height / 2f
                val top = centerY - trackHeightPx / 2f

                // Inactive track (full width)
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, trackHeightPx),
                    cornerRadius = CornerRadius(trackCornerRadiusPx, trackCornerRadiusPx)
                )

                // Active track (proportional width, Draw phase only)
                val activeWidth = (size.width * fraction).coerceIn(0f, size.width)
                if (activeWidth > 0f) {
                    val activeTopLeftX = if (isRtl) size.width - activeWidth else 0f
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(activeTopLeftX, top),
                        size = Size(activeWidth, trackHeightPx),
                        cornerRadius = CornerRadius(trackCornerRadiusPx, trackCornerRadiusPx)
                    )
                }

                // Thumb dot (radius 4.dp = size 8.dp)
                val rawThumbCenterX = if (isRtl) size.width * (1f - fraction) else size.width * fraction
                val thumbCenterX = rawThumbCenterX.coerceIn(thumbRadiusPx, size.width - thumbRadiusPx)
                drawCircle(
                    color = activeColor,
                    radius = thumbRadiusPx,
                    center = Offset(thumbCenterX, centerY)
                )
            }
    )
}

@Composable
fun BrowseArtistRail(
    artists: List<ArtistBrowseRow>,
    onOpenArtist: (String) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyStateSurface(
            "Pas encore d'artiste local",
            "AURA affichera ici les artistes issus de la bibliothèque indexée.",
            Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(artists, key = { it.id }) { artist ->
            Column(
                modifier = Modifier
                    .width(104.dp)
                    .clickable { onOpenArtist(artist.id) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(DarkGraphite)
                        .border(1.5.dp, HairlineDark, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (artist.pictureUri != null) {
                        AsyncImage(
                            model = artist.pictureUri,
                            contentDescription = artist.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = BlazeOrange,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "${artist.trackCount} titre(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun BrowseAlbumRail(
    albums: List<AlbumBrowseRow>,
    onOpenAlbum: (String) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyStateSurface("Pas d'album local", "Les albums locaux indexés seront proposés ici.", Modifier.padding(horizontal = 16.dp))
        return
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(albums, key = { it.id }) { album ->
            val count = album.trackCount
            SharedRailCard(
                title = album.title,
                subtitle = if (count != null && count > 0) "$count titre(s)" else (album.artistName ?: "Album"),
                imageUri = album.coverUri,
                gradientStartColor = Color(0xFFFF9E00),
                imageShape = RoundedCornerShape(14.dp),
                onClick = { onOpenAlbum(album.id) }
            )
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}

@Composable
fun SharedRailCard(
    title: String,
    subtitle: String,
    imageUri: String?,
    gradientStartColor: Color,
    imageShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable { onClick() },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(imageShape)
                .background(DarkGraphite)
                .border(1.dp, HairlineDark, imageShape),
            contentAlignment = Alignment.Center
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(48.dp)
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
 * Bouton Cœur interactif avec micro-interaction (rebond d'échelle et transition de couleur)
 * et retour optimiste immédiat (0 ms).
 */
@Composable
fun FavoriteHeartButton(
    isLiked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp
) {
    var optimisticLiked by remember(isLiked) { mutableStateOf(isLiked) }
    var triggerBounce by remember { mutableStateOf(false) }

    LaunchedEffect(isLiked) {
        optimisticLiked = isLiked
    }

    val scale by animateFloatAsState(
        targetValue = if (triggerBounce) 1.25f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "heartScale"
    )

    LaunchedEffect(triggerBounce) {
        if (triggerBounce) {
            delay(120)
            triggerBounce = false
        }
    }

    val heartColor by animateColorAsState(
        targetValue = if (optimisticLiked) BlazeOrange else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        animationSpec = tween(durationMillis = 150),
        label = "heartColor"
    )

    IconButton(
        onClick = {
            optimisticLiked = !optimisticLiked
            triggerBounce = true
            onToggle()
        },
        modifier = modifier
            .size(size)
            .scale(scale)
    ) {
        Icon(
            imageVector = if (optimisticLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (optimisticLiked) "Retirer des favoris" else "Ajouter aux favoris",
            tint = heartColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Ligne de piste partagee entre FavoritesScreen, PlaylistDetailScreen, Library, Search.
 * Card DarkGraphite + titre + sous-titre + slot leading + slot trailing + menu contextuel hierarchique.
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
    var menuPage by remember { mutableStateOf(0) } // 0 = Principal, 1 = Avancé (Fichiers & Données)

    val hasAdvancedItems = onRemoveFromPlaylist != null || onDownload != null || onDeleteDownload != null ||
            onUploadToCloud != null || onDownloadFromCloud != null || onDeleteFromCloud != null || onEditMetadata != null

    val hasMenu = onAddToQueue != null || onAddToPlaylist != null ||
            onViewArtist != null || onViewAlbum != null || hasAdvancedItems

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
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (coverUri != null) {
                    AsyncImage(
                        model = coverUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PlaceholderCover(
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Overlay visuel du statut de téléchargement / chargement directement sur la pochette
                when (downloadStatus) {
                    is TrackDownloadStatus.Queued -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Schedule,
                                contentDescription = "En attente",
                                tint = BlazeOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    is TrackDownloadStatus.Downloading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val progress = (downloadStatus.progressPercent / 100f).coerceIn(0f, 1f)
                            if (progress > 0f) {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    color = BlazeOrange,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                CircularProgressIndicator(
                                    color = BlazeOrange,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    is TrackDownloadStatus.Failed -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = "Échec",
                                tint = Color.Red,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    else -> {
                        // Si le son est sur le Cloud ou téléchargé, aucun badge par-dessus la pochette
                    }
                }
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (isCloudOnly) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = "Disponible sur le Cloud",
                        tint = BlazeOrange,
                        modifier = Modifier.size(13.dp)
                    )
                } else if (downloadStatus is TrackDownloadStatus.Downloaded) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = "Téléchargé sur l'appareil",
                        tint = ElectricGreen,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (onLike != null || onUnlike != null) {
                FavoriteHeartButton(
                    isLiked = isLiked,
                    onToggle = { if (isLiked) onUnlike?.invoke() else onLike?.invoke() }
                )
            }

            if (contextType == "search_online" && onDownload != null && downloadStatus is TrackDownloadStatus.NotDownloaded) {
                IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = "Ajouter au Cloud",
                        tint = BlazeOrange,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (trailingIcon != null) {
                trailingIcon()
            } else if (hasMenu) {
                Box {
                    IconButton(
                        onClick = {
                            menuPage = 0
                            menuExpanded = true
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Menu contextuel",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (menuExpanded) {
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = {
                                menuExpanded = false
                                menuPage = 0
                            },
                        ) {
                            if (menuPage == 0) {
                                // ===== NIVEAU 1 : ACTIONS MUSICALES PRINCIPALES =====
                                if (onAddToQueue != null) {
                                    DropdownMenuItem(
                                        text = { Text("Ajouter à la file d'attente") },
                                        onClick = {
                                            menuExpanded = false
                                            onAddToQueue()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) }
                                    )
                                }
                                if (onAddToPlaylist != null) {
                                    DropdownMenuItem(
                                        text = { Text("Ajouter à une playlist") },
                                        onClick = {
                                            menuExpanded = false
                                            onAddToPlaylist()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) }
                                    )
                                }
                                if (onViewArtist != null) {
                                    DropdownMenuItem(
                                        text = { Text("Voir l'artiste") },
                                        onClick = {
                                            menuExpanded = false
                                            onViewArtist()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) }
                                    )
                                }
                                if (onViewAlbum != null) {
                                    DropdownMenuItem(
                                        text = { Text("Voir l'album") },
                                        onClick = {
                                            menuExpanded = false
                                            onViewAlbum()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Album, contentDescription = null) }
                                    )
                                }
                                if (hasAdvancedItems) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Plus d'options")
                                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        onClick = {
                                            menuPage = 1
                                        }
                                    )
                                }
                            } else {
                                // ===== NIVEAU 2 : FICHIERS & GESTION AVANCÉE =====
                                DropdownMenuItem(
                                    text = { Text("Retour", color = BlazeOrange, fontWeight = FontWeight.SemiBold) },
                                    onClick = { menuPage = 0 },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = BlazeOrange) }
                                )
                                if (onRemoveFromPlaylist != null) {
                                    DropdownMenuItem(
                                        text = { Text("Retirer de la playlist") },
                                        onClick = {
                                            menuExpanded = false
                                            menuPage = 0
                                            onRemoveFromPlaylist()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) }
                                    )
                                }
                                if (onDownload != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (contextType == "search_online") "Ajouter au Cloud personnel" else "Télécharger sur l'appareil") },
                                        onClick = {
                                            menuExpanded = false
                                            menuPage = 0
                                            onDownload()
                                        },
                                        leadingIcon = { Icon(if (contextType == "search_online") Icons.Rounded.CloudDownload else Icons.Rounded.Download, contentDescription = null) }
                                    )
                                }
                                if (onDeleteDownload != null) {
                                    DropdownMenuItem(
                                        text = { Text("Supprimer du téléphone") },
                                        onClick = {
                                            menuExpanded = false
                                            menuPage = 0
                                            onDeleteDownload()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) }
                                    )
                                }
                                if (onUploadToCloud != null) {
                                    DropdownMenuItem(
                                        text = { Text("Ajouter au Cloud") },
                                        onClick = {
                                            menuExpanded = false
                                            menuPage = 0
                                            onUploadToCloud()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) }
                                    )
                                }
                                if (onDownloadFromCloud != null) {
                                    DropdownMenuItem(
                                        text = { Text("Récupérer depuis le Cloud") },
                                        onClick = {
                                            menuExpanded = false
                                            menuPage = 0
                                            onDownloadFromCloud()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) }
                                    )
                                }
                                if (onDeleteFromCloud != null) {
                                    DropdownMenuItem(
                                        text = { Text("Supprimer du Cloud") },
                                        onClick = {
                                            menuExpanded = false
                                            menuPage = 0
                                            onDeleteFromCloud()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) }
                                    )
                                }
                                if (onEditMetadata != null) {
                                    DropdownMenuItem(
                                        text = { Text("Modifier les informations") },
                                        onClick = {
                                            menuExpanded = false
                                            menuPage = 0
                                            onEditMetadata()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) }
                                    )
                                }
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

/**
 * Pochette intelligente de playlist :
 * - Si coverUri est non-nul : affiche l'image unique.
 * - Si previewCovers a >= 4 éléments : compose une mosaïque 2x2.
 * - Si previewCovers a entre 1 et 3 éléments : affiche la première pochette.
 * - Si aucune pochette : affiche un placeholder avec dégradé AURA chaud et icône Playlist.
 */
@Composable
fun PlaylistMosaicCover(
    modifier: Modifier = Modifier,
    coverUri: String? = null,
    previewCovers: List<String> = emptyList(),
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    iconSize: androidx.compose.ui.unit.Dp = 36.dp,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(DarkGraphite),
        contentAlignment = Alignment.Center,
    ) {
        val validPreviews = remember(previewCovers) { previewCovers.filter { it.isNotBlank() } }

        if (!coverUri.isNullOrBlank()) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (validPreviews.size >= 4) {
            // Mosaïque 2x2
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AsyncImage(
                        model = validPreviews[0],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    AsyncImage(
                        model = validPreviews[1],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AsyncImage(
                        model = validPreviews[2],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    AsyncImage(
                        model = validPreviews[3],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        } else if (validPreviews.isNotEmpty()) {
            AsyncImage(
                model = validPreviews.first(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF2A1C12), Color(0xFF161616), Color(0xFF0F0F0F))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = BlazeOrange.copy(alpha = 0.85f),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}
