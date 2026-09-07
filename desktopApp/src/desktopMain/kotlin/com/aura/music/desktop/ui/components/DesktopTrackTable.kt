package com.aura.music.desktop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackListRow
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.formatDuration
import com.aura.music.desktop.ui.*
import com.aura.music.domain.player.PlaybackState
import com.aura.music.ui.components.rememberShimmerBrush
import com.aura.music.ui.components.shimmer
import com.aura.music.ui.theme.*

enum class TrackSortField {
    DEFAULT, TITLE, ARTIST, ALBUM, DURATION, DATE_ADDED
}

@Composable
fun DesktopTrackTable(
    tracks: List<TrackListRow>,
    currentPlayingTrackId: String?,
    isPlaying: Boolean,
    orchestrator: DesktopPlaybackOrchestrator,
    database: AuraDatabase,
    appState: DesktopAppState,
    onTrackClick: (TrackListRow) -> Unit,
    onToggleLike: (String) -> Unit,
    showAlbumColumn: Boolean = true,
    showDateAddedColumn: Boolean = false,
    isLoading: Boolean = false,
    keyProvider: ((index: Int, track: TrackListRow) -> Any)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by orchestrator.uiState.collectAsState()
    val isBuffering = uiState.playbackState == PlaybackState.Buffering || uiState.playbackState == PlaybackState.Preparing
    var trackForContextMenu by remember { mutableStateOf<TrackListRow?>(null) }
    var trackForMetadataEdit by remember { mutableStateOf<TrackListRow?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        DesktopTrackTable(
            tracks = tracks,
            activeTrackId = currentPlayingTrackId,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            onTrackClick = { trk, _ -> onTrackClick(trk) },
            onToggleLike = onToggleLike,
            onOpenArtist = { artId ->
                appState.openArtist(artId)
            },
            onOpenAlbum = { albId ->
                appState.openAlbum(albId)
            },
            onContextMenu = { trk ->
                trackForContextMenu = trk
            },
            showAlbumColumn = showAlbumColumn,
            showDateAddedColumn = showDateAddedColumn,
            isLoading = isLoading,
            keyProvider = keyProvider
        )

        // Menu contextuel
        if (trackForContextMenu != null) {
            val trk = trackForContextMenu!!
            DesktopTrackContextMenu(
                expanded = true,
                onDismissRequest = { trackForContextMenu = null },
                track = trk,
                onPlayNext = {
                    orchestrator.addToQueue(orchestrator.toQueuedTrack(trk))
                },
                onAddToQueue = {
                    orchestrator.addToQueue(orchestrator.toQueuedTrack(trk))
                },
                onAddToPlaylist = {
                    appState.trackIdToAddToPlaylist = trk.id
                    appState.showAddToPlaylistDialog = true
                },
                onOpenArtist = {
                    appState.navigateTo("artist_detail")
                    appState.selectedArtistId = trk.artistId ?: "artist:${trk.artistName}"
                },
                onOpenAlbum = {
                    trk.albumId?.let {
                        appState.navigateTo("album_detail")
                        appState.selectedAlbumId = it
                    }
                },
                onToggleLike = {
                    onToggleLike(trk.id)
                },
                onEditMetadata = {
                    trackForMetadataEdit = trk
                },
                onDownloadCloud = {
                    orchestrator.triggerSingleFileDownload(trk)
                },
                onUploadCloud = {
                    orchestrator.triggerSingleFileUpload(trk)
                }
            )
        }

        // Dialogue d'édition de métadonnées
        if (trackForMetadataEdit != null) {
            DesktopEditMetadataDialog(
                track = trackForMetadataEdit,
                database = database,
                appState = appState,
                onDismiss = { trackForMetadataEdit = null },
                onSaved = { }
            )
        }
    }
}

@Composable
fun DesktopTrackTable(
    tracks: List<TrackListRow>,
    activeTrackId: String?,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    onTrackClick: (TrackListRow, Int) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenArtist: ((String) -> Unit)? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
    onContextMenu: ((TrackListRow) -> Unit)? = null,
    showAlbumColumn: Boolean = true,
    showDateAddedColumn: Boolean = false,
    isLoading: Boolean = false,
    keyProvider: ((index: Int, track: TrackListRow) -> Any)? = null,
    modifier: Modifier = Modifier
) {
    var sortField by remember { mutableStateOf(TrackSortField.DEFAULT) }
    var sortAscending by remember { mutableStateOf(true) }

    val sortedTracks = remember(tracks, sortField, sortAscending) {
        when (sortField) {
            TrackSortField.DEFAULT -> tracks
            TrackSortField.TITLE -> if (sortAscending) tracks.sortedBy { it.title.lowercase() } else tracks.sortedByDescending { it.title.lowercase() }
            TrackSortField.ARTIST -> if (sortAscending) tracks.sortedBy { it.displayArtist.lowercase() } else tracks.sortedByDescending { it.displayArtist.lowercase() }
            TrackSortField.ALBUM -> if (sortAscending) tracks.sortedBy { it.displayAlbum?.lowercase() ?: "" } else tracks.sortedByDescending { it.displayAlbum?.lowercase() ?: "" }
            TrackSortField.DURATION -> if (sortAscending) tracks.sortedBy { it.durationMs ?: 0L } else tracks.sortedByDescending { it.durationMs ?: 0L }
            TrackSortField.DATE_ADDED -> if (sortAscending) tracks.sortedBy { it.createdAt } else tracks.sortedByDescending { it.createdAt }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
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

        if (sortedTracks.isEmpty() && isLoading) {
            val shimmerBrush = rememberShimmerBrush()
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(14) {
                    DesktopTrackTableShimmerRow(
                        brush = shimmerBrush,
                        showAlbumColumn = showAlbumColumn,
                        showDateAddedColumn = showDateAddedColumn
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    sortedTracks,
                    key = { index, track -> keyProvider?.invoke(index, track) ?: track.id }
                ) { index, track ->
                    val isCurrent = track.id == activeTrackId

                    TrackTableRowItem(
                        index = index + 1,
                        track = track,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying && isCurrent,
                        isBuffering = isBuffering && isCurrent,
                        onPlay = { onTrackClick(track, index) },
                        onToggleLike = { onToggleLike(track.id) },
                        onOpenArtist = { onOpenArtist?.invoke(track.artistId ?: "artist:${track.artistName}") },
                        onOpenAlbum = { track.albumId?.let { onOpenAlbum?.invoke(it) } },
                        onContextMenu = { onContextMenu?.invoke(track) },
                        showAlbumColumn = showAlbumColumn,
                        showDateAddedColumn = showDateAddedColumn
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopTrackTableShimmerRow(
    brush: androidx.compose.ui.graphics.Brush,
    showAlbumColumn: Boolean,
    showDateAddedColumn: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // # placeholder
        Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(16.dp, 12.dp)
                    .shimmer(brush, RoundedCornerShape(3.dp))
            )
        }

        // Titre + Vignette Artwork
        Row(
            modifier = Modifier.weight(2.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shimmer(brush, RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(13.dp)
                        .shimmer(brush, RoundedCornerShape(4.dp))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.35f)
                        .height(10.dp)
                        .shimmer(brush, RoundedCornerShape(4.dp))
                )
            }
        }

        // Artiste
        Box(modifier = Modifier.weight(1.8f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(12.dp)
                    .shimmer(brush, RoundedCornerShape(4.dp))
            )
        }

        // Album
        if (showAlbumColumn) {
            Box(modifier = Modifier.weight(1.8f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(12.dp)
                        .shimmer(brush, RoundedCornerShape(4.dp))
                )
            }
        }

        // Date d'ajout
        if (showDateAddedColumn) {
            Box(modifier = Modifier.weight(1.2f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(12.dp)
                        .shimmer(brush, RoundedCornerShape(4.dp))
                )
            }
        }

        // Durée et Actions (alignés à droite sur 136.dp)
        Row(
            modifier = Modifier.width(136.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .shimmer(brush, RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(48.dp, 12.dp)
                    .shimmer(brush, RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .shimmer(brush, RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(20.dp))
        }
    }
}

@Composable
fun TrackTableHeaderRow(
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
            modifier = Modifier.width(136.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(32.dp))
            HeaderSortableColumn(
                label = "DURÉE",
                field = TrackSortField.DURATION,
                activeField = sortField,
                sortAscending = sortAscending,
                onClick = onSortChanged,
                modifier = Modifier.width(48.dp),
                horizontalArrangement = Arrangement.End
            )
            Spacer(modifier = Modifier.width(56.dp))
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
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start
) {
    val isActive = activeField == field

    Row(
        modifier = modifier.clickable { onClick(field) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement
    ) {
        if (isActive && horizontalArrangement == Arrangement.End) {
            Icon(
                imageVector = if (sortAscending) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = BlazeOrange,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
        Text(
            text = label,
            color = if (isActive) BlazeOrange else PureWhite.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = if (horizontalArrangement == Arrangement.End) 0.sp else 1.sp
        )
        if (isActive && horizontalArrangement != Arrangement.End) {
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
fun TrackTableRowItem(
    index: Int,
    track: TrackListRow,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
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
                    isHovered -> PureWhite.copy(alpha = 0.04f)
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
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isCurrent && isBuffering -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BlazeOrange,
                        strokeWidth = 2.dp
                    )
                }
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

        Text(
            text = track.displayArtist,
            color = if (isHovered) PureWhite else PureWhite.copy(alpha = 0.65f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1.8f)
                .clickable { onOpenArtist() }
        )

        if (showAlbumColumn) {
            Text(
                text = track.displayAlbum ?: "-",
                color = if (isHovered) PureWhite.copy(alpha = 0.9f) else PureWhite.copy(alpha = 0.5f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1.8f)
                    .clickable { onOpenAlbum() }
            )
        }

        if (showDateAddedColumn) {
            Text(
                text = formatTimestamp(track.createdAt),
                color = if (isHovered) PureWhite.copy(alpha = 0.75f) else PureWhite.copy(alpha = 0.4f),
                fontSize = 12.sp,
                modifier = Modifier.weight(1.2f)
            )
        }

        Row(
            modifier = Modifier.width(136.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleLike),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favori",
                    tint = if (track.isLiked) BlazeOrange else if (isHovered) PureWhite.copy(alpha = 0.7f) else Color.Transparent,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = formatDuration(track.durationMs ?: 0L),
                color = if (isHovered) PureWhite else PureWhite.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.End
            )

            Spacer(modifier = Modifier.width(16.dp))

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onContextMenu),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Options",
                    tint = if (isHovered) PureWhite else Color.Transparent,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}


private fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "-"
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val date = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    return "${date.dayOfMonth}/${date.monthValue}/${date.year}"
}
