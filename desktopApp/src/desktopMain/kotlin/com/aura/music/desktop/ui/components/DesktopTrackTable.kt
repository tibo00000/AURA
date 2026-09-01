package com.aura.music.desktop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.TrackListRow
import com.aura.music.ui.theme.*

enum class TrackSortField {
    DEFAULT, TITLE, ARTIST, ALBUM, DURATION, DATE_ADDED
}

@Composable
fun DesktopTrackTable(
    tracks: List<TrackListRow>,
    activeTrackId: String?,
    isPlaying: Boolean,
    onTrackClick: (TrackListRow, Int) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenArtist: ((String) -> Unit)? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
    onContextMenu: ((TrackListRow) -> Unit)? = null,
    showAlbumColumn: Boolean = true,
    showDateAddedColumn: Boolean = false,
    modifier: Modifier = Modifier
) {
    var sortField by remember { mutableStateOf(TrackSortField.DEFAULT) }
    var sortAscending by remember { mutableStateOf(true) }

    // Tri mémoïsé (ne se recalcule QUE lors d'un changement de champ/ordre ou de liste)
    val sortedTracks = remember(tracks, sortField, sortAscending) {
        when (sortField) {
            TrackSortField.DEFAULT -> tracks
            TrackSortField.TITLE -> if (sortAscending) tracks.sortedBy { it.title.lowercase() } else tracks.sortedByDescending { it.title.lowercase() }
            TrackSortField.ARTIST -> if (sortAscending) tracks.sortedBy { it.displayArtist.lowercase() } else tracks.sortedByDescending { it.displayArtist.lowercase() }
            TrackSortField.ALBUM -> if (sortAscending) tracks.sortedBy { it.displayAlbum?.lowercase() ?: "" } else tracks.sortedByDescending { it.displayAlbum?.lowercase() ?: "" }
            TrackSortField.DURATION -> if (sortAscending) tracks.sortedBy { it.durationMs ?: 0L } else tracks.sortedByDescending { it.durationMs ?: 0L }
            TrackSortField.DATE_ADDED -> if (sortAscending) tracks.sortedBy { it.createdAt ?: 0L } else tracks.sortedByDescending { it.createdAt ?: 0L }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // En-tête des colonnes
        TrackTableHeaderRow(
            sortField = sortField,
            sortAscending = sortAscending,
            onSortChanged = { field ->
                if (sortField == field) {
                    sortAscending = !sortAscending
                } else {
                    sortField = field
                    sortAscending = true
                }
            },
            showAlbumColumn = showAlbumColumn,
            showDateAddedColumn = showDateAddedColumn
        )

        HorizontalDivider(color = HairlineDark, thickness = 1.dp)

        // Liste virtualisée des lignes
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(sortedTracks, key = { _, track -> track.id }) { index, track ->
                val isCurrent = track.id == activeTrackId

                TrackTableRowItem(
                    index = index + 1,
                    track = track,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying && isCurrent,
                    onPlay = { onTrackClick(track, index) },
                    onToggleLike = { onToggleLike(track.id) },
                    onOpenArtist = { onOpenArtist?.invoke(track.artistId) },
                    onOpenAlbum = { track.albumId?.let { onOpenAlbum?.invoke(it) } },
                    onContextMenu = { onContextMenu?.invoke(track) },
                    showAlbumColumn = showAlbumColumn,
                    showDateAddedColumn = showDateAddedColumn
                )
            }
        }
    }
}

@Composable
private fun TrackTableHeaderRow(
    sortField: TrackSortField,
    sortAscending: Boolean,
    onSortChanged: (TrackSortField) -> Unit,
    showAlbumColumn: Boolean,
    showDateAddedColumn: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Center
        )

        HeaderSortableColumn(
            label = "TITRE",
            field = TrackSortField.TITLE,
            activeField = sortField,
            sortAscending = sortAscending,
            onClick = onSortChanged,
            modifier = Modifier.weight(2.5f)
        )

        HeaderSortableColumn(
            label = "ARTISTE",
            field = TrackSortField.ARTIST,
            activeField = sortField,
            sortAscending = sortAscending,
            onClick = onSortChanged,
            modifier = Modifier.weight(1.8f)
        )

        if (showAlbumColumn) {
            HeaderSortableColumn(
                label = "ALBUM",
                field = TrackSortField.ALBUM,
                activeField = sortField,
                sortAscending = sortAscending,
                onClick = onSortChanged,
                modifier = Modifier.weight(1.8f)
            )
        }

        if (showDateAddedColumn) {
            HeaderSortableColumn(
                label = "AJOUTÉ LE",
                field = TrackSortField.DATE_ADDED,
                activeField = sortField,
                sortAscending = sortAscending,
                onClick = onSortChanged,
                modifier = Modifier.weight(1.2f)
            )
        }

        Row(
            modifier = Modifier.width(100.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderSortableColumn(
                label = "DURÉE",
                field = TrackSortField.DURATION,
                activeField = sortField,
                sortAscending = sortAscending,
                onClick = onSortChanged,
                modifier = Modifier
            )
        }
    }
}

@Composable
private fun HeaderSortableColumn(
    label: String,
    field: TrackSortField,
    activeField: TrackSortField,
    sortAscending: Boolean,
    onClick: (TrackSortField) -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = activeField == field

    Row(
        modifier = modifier.clickable { onClick(field) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isActive) BlazeOrange else PureWhite.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        if (isActive) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (sortAscending) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = BlazeOrange,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackTableRowItem(
    index: Int,
    track: TrackListRow,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onContextMenu: () -> Unit,
    showAlbumColumn: Boolean,
    showDateAddedColumn: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isCurrent -> BlazeOrange.copy(alpha = 0.12f)
                    isHovered -> DarkGraphite.copy(alpha = 0.6f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interactionSource)
            .combinedClickable(
                onClick = onPlay,
                onDoubleClick = onPlay,
                onLongClick = onContextMenu
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Numéro ou icône Play / Égaliseur
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isPlaying -> {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "En lecture",
                        tint = BlazeOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }
                isHovered -> {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Lire",
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
                else -> {
                    Text(
                        text = "$index",
                        color = if (isCurrent) BlazeOrange else PureWhite.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Titre & Pochette
        Row(
            modifier = Modifier.weight(2.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DesktopArtworkCover(
                coverUri = track.coverUri,
                size = 40.dp,
                shapeRadius = 4.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = track.title,
                    color = if (isCurrent) BlazeOrange else PureWhite,
                    fontSize = 13.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.isCloudOnly) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Cloud,
                            contentDescription = "Cloud",
                            tint = BlazeOrange,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Cloud",
                            color = BlazeOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Artiste
        Text(
            text = track.displayArtist,
            color = PureWhite.copy(alpha = 0.7f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1.8f)
                .clickable { onOpenArtist() }
        )

        // Album
        if (showAlbumColumn) {
            Text(
                text = track.displayAlbum ?: "-",
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1.8f)
                    .clickable { onOpenAlbum() }
            )
        }

        // Date d'ajout
        if (showDateAddedColumn) {
            Text(
                text = formatTimestamp(track.createdAt),
                color = PureWhite.copy(alpha = 0.4f),
                fontSize = 12.sp,
                modifier = Modifier.weight(1.2f)
            )
        }

        // Actions & Durée
        Row(
            modifier = Modifier.width(100.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleLike,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favori",
                    tint = if (track.isLiked) BlazeOrange else if (isHovered) PureWhite.copy(alpha = 0.6f) else Color.Transparent,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = formatDuration(track.durationMs ?: 0L),
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.width(42.dp),
                textAlign = TextAlign.End
            )

            IconButton(
                onClick = onContextMenu,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Options",
                    tint = if (isHovered) PureWhite.copy(alpha = 0.7f) else Color.Transparent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "-"
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val date = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    return "${date.dayOfMonth}/${date.monthValue}/${date.year}"
}
