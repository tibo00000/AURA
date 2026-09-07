package com.aura.music.desktop.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.domain.search.SearchNormalizer
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.*
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.desktop.ui.components.DesktopTrackTable
import com.aura.music.ui.components.rememberShimmerBrush
import com.aura.music.ui.components.shimmer
import com.aura.music.ui.theme.*

enum class LibrarySourceFilter(val label: String) {
    ALL("Tous"),
    OFFLINE("Hors-ligne"),
    CLOUD("Cloud")
}

enum class LibrarySortOrder(val label: String) {
    RECENT("Ajout récent"),
    TITLE_AZ("Titre (A à Z)"),
    TITLE_ZA("Titre (Z à A)"),
    ARTIST("Artiste"),
    DURATION("Durée")
}

@Composable
fun LibraryScreen(
    allTracks: List<TrackListRow>,
    allAlbums: List<AlbumBrowseRow>,
    allArtists: List<ArtistBrowseRow>,
    playlists: List<PlaylistListRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onToggleLike: (String) -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) } // 0: Titres, 1: Albums, 2: Artistes
    var localSearchQuery by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf(LibrarySourceFilter.ALL) }
    var sortOrder by remember { mutableStateOf(LibrarySortOrder.RECENT) }
    val searchFocusRequester = remember { FocusRequester() }
    val isMac = remember { System.getProperty("os.name")?.lowercase()?.contains("mac") == true }

    // Statistiques globales de la bibliothèque
    val totalTracksCount = allTracks.size
    val totalMs = remember(allTracks) { allTracks.sumOf { it.durationMs ?: 0L } }
    val totalHours = totalMs / 3_600_000
    val formattedTrackCount = remember(totalTracksCount) {
        java.text.NumberFormat.getInstance(java.util.Locale.FRENCH).format(totalTracksCount)
    }
    val librarySubtitle = remember(formattedTrackCount, totalHours) {
        if (totalHours > 0) {
            "$formattedTrackCount morceaux • $totalHours h de musique"
        } else {
            "$formattedTrackCount morceaux"
        }
    }

    // Filtrage par source (Tous / Hors-ligne / Cloud)
    val offlineAlbumIds = remember(allTracks) {
        allTracks.filter { !it.isCloudOnly }.mapNotNull { it.albumId }.toSet()
    }
    val cloudAlbumIds = remember(allTracks) {
        allTracks.filter { it.isCloudOnly }.mapNotNull { it.albumId }.toSet()
    }
    val offlineArtistIds = remember(allTracks) {
        allTracks.filter { !it.isCloudOnly }.mapNotNull { it.artistId }.toSet()
    }
    val cloudArtistIds = remember(allTracks) {
        allTracks.filter { it.isCloudOnly }.mapNotNull { it.artistId }.toSet()
    }

    val sourceFilteredTracks = remember(allTracks, sourceFilter) {
        when (sourceFilter) {
            LibrarySourceFilter.ALL -> allTracks
            LibrarySourceFilter.OFFLINE -> allTracks.filter { !it.isCloudOnly }
            LibrarySourceFilter.CLOUD -> allTracks.filter { it.isCloudOnly }
        }
    }

    val sourceFilteredAlbums = remember(allAlbums, sourceFilter, offlineAlbumIds, cloudAlbumIds) {
        when (sourceFilter) {
            LibrarySourceFilter.ALL -> allAlbums
            LibrarySourceFilter.OFFLINE -> allAlbums.filter { it.id in offlineAlbumIds }
            LibrarySourceFilter.CLOUD -> allAlbums.filter { it.id in cloudAlbumIds }
        }
    }

    val sourceFilteredArtists = remember(allArtists, sourceFilter, offlineArtistIds, cloudArtistIds) {
        when (sourceFilter) {
            LibrarySourceFilter.ALL -> allArtists
            LibrarySourceFilter.OFFLINE -> allArtists.filter { it.id in offlineArtistIds }
            LibrarySourceFilter.CLOUD -> allArtists.filter { it.id in cloudArtistIds }
        }
    }

    // Filtrage textuel et tri dynamiques selon l'onglet actif
    val filteredTracks = remember(sourceFilteredTracks, localSearchQuery, sortOrder) {
        val q = SearchNormalizer.normalize(localSearchQuery).trim()
        val base = if (q.isBlank()) {
            sourceFilteredTracks
        } else {
            sourceFilteredTracks.filter {
                SearchNormalizer.normalize(it.title).contains(q) ||
                SearchNormalizer.normalize(it.artistName).contains(q) ||
                (it.albumTitle != null && SearchNormalizer.normalize(it.albumTitle!!).contains(q))
            }
        }
        when (sortOrder) {
            LibrarySortOrder.RECENT -> base
            LibrarySortOrder.TITLE_AZ -> base.sortedBy { SearchNormalizer.normalize(it.title) }
            LibrarySortOrder.TITLE_ZA -> base.sortedByDescending { SearchNormalizer.normalize(it.title) }
            LibrarySortOrder.ARTIST -> base.sortedBy { SearchNormalizer.normalize(it.artistName) }
            LibrarySortOrder.DURATION -> base.sortedByDescending { it.durationMs ?: 0L }
        }
    }

    val filteredAlbums = remember(sourceFilteredAlbums, localSearchQuery, sortOrder) {
        val q = SearchNormalizer.normalize(localSearchQuery).trim()
        val base = if (q.isBlank()) {
            sourceFilteredAlbums
        } else {
            sourceFilteredAlbums.filter {
                SearchNormalizer.normalize(it.title).contains(q) ||
                (it.artistName != null && SearchNormalizer.normalize(it.artistName!!).contains(q))
            }
        }
        when (sortOrder) {
            LibrarySortOrder.TITLE_AZ -> base.sortedBy { SearchNormalizer.normalize(it.title) }
            LibrarySortOrder.TITLE_ZA -> base.sortedByDescending { SearchNormalizer.normalize(it.title) }
            LibrarySortOrder.ARTIST -> base.sortedBy { SearchNormalizer.normalize(it.artistName ?: "") }
            else -> base
        }
    }

    val filteredArtists = remember(sourceFilteredArtists, localSearchQuery, sortOrder) {
        val q = SearchNormalizer.normalize(localSearchQuery).trim()
        val base = if (q.isBlank()) {
            sourceFilteredArtists
        } else {
            sourceFilteredArtists.filter {
                SearchNormalizer.normalize(it.name).contains(q)
            }
        }
        when (sortOrder) {
            LibrarySortOrder.TITLE_ZA -> base.sortedByDescending { SearchNormalizer.normalize(it.name) }
            else -> base.sortedBy { SearchNormalizer.normalize(it.name) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.F) {
                    val isModifierPressed = if (isMac) keyEvent.isMetaPressed else keyEvent.isCtrlPressed
                    if (isModifierPressed) {
                        try {
                            searchFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // Ligne 1 : Titre Bibliothèque (avec sous-titre stats discret) + Onglets simples (sans compteurs)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Titre et sous-titre de volume
            Column {
                Text(
                    text = "Bibliothèque",
                    color = PureWhite,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = librarySubtitle,
                    color = PureWhite.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
            }

            // Onglets textuels simples style pills (sans compteur)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkGraphite.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, HairlineDark)
            ) {
                Row(
                    modifier = Modifier.padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val tabs = listOf("Titres", "Albums", "Artistes")
                    tabs.forEachIndexed { index, label ->
                        val isSelected = selectedTab == index
                        val interactionSource = remember { MutableInteractionSource() }
                        val isHovered by interactionSource.collectIsHoveredAsState()

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    when {
                                        isSelected -> BlazeOrange.copy(alpha = 0.2f)
                                        isHovered -> PureWhite.copy(alpha = 0.06f)
                                        else -> Color.Transparent
                                    }
                                )
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) BlazeOrange.copy(alpha = 0.6f) else Color.Transparent,
                                    shape = RoundedCornerShape(7.dp)
                                )
                                .hoverable(interactionSource)
                                .handClickable(interactionSource = interactionSource) {
                                    selectedTab = index
                                }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) PureWhite else PureWhite.copy(alpha = if (isHovered) 0.95f else 0.65f),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Ligne 2 : Barre d'outils dédiée (Filtrage inline permanent à gauche, Source au milieu, Tri à droite)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Gauche : Champ de filtrage instantané inline permanent (~230dp) avec raccourci Ctrl+F / ⌘F
            LibraryInlineSearchBar(
                query = localSearchQuery,
                onQueryChange = { localSearchQuery = it },
                placeholder = "Filtrer dans la bibliothèque...",
                focusRequester = searchFocusRequester,
                isMac = isMac,
                modifier = Modifier.width(230.dp)
            )

            // Milieu : Filtre de téléchargement / source (Tous / Hors-ligne / Cloud)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = DarkGraphite.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, HairlineDark)
            ) {
                Row(
                    modifier = Modifier.padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    LibrarySourceFilter.values().forEach { filter ->
                        val isSelected = sourceFilter == filter
                        val interactionSource = remember { MutableInteractionSource() }
                        val isHovered by interactionSource.collectIsHoveredAsState()

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    when {
                                        isSelected -> PureWhite.copy(alpha = 0.12f)
                                        isHovered -> PureWhite.copy(alpha = 0.05f)
                                        else -> Color.Transparent
                                    }
                                )
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) PureWhite.copy(alpha = 0.25f) else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .hoverable(interactionSource)
                                .handClickable(interactionSource = interactionSource) {
                                    sourceFilter = filter
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = filter.label,
                                color = if (isSelected) PureWhite else PureWhite.copy(alpha = if (isHovered) 0.85f else 0.55f),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Droite : Menu déroulant de tri
            LibrarySortMenu(
                selectedTab = selectedTab,
                currentSort = sortOrder,
                onSortSelected = { sortOrder = it }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Contenu dynamique selon l'onglet
        when (selectedTab) {
            0 -> {
                // --- ONGLET TITRES ---
                if (filteredTracks.isEmpty() && !isLoading) {
                    if (localSearchQuery.isNotBlank()) {
                        EmptyLibrarySearchResult(
                            query = localSearchQuery,
                            onClear = { localSearchQuery = "" }
                        )
                    } else {
                        EmptyLibraryPlaceholder(
                            message = when (sourceFilter) {
                                LibrarySourceFilter.OFFLINE -> "Aucun titre hors-ligne dans votre bibliothèque"
                                LibrarySourceFilter.CLOUD -> "Aucun titre cloud dans votre bibliothèque"
                                else -> "Aucun titre dans votre bibliothèque"
                            }
                        )
                    }
                } else {
                    val uiState by orchestrator.uiState.collectAsState()
                    DesktopTrackTable(
                        tracks = filteredTracks,
                        activeTrackId = uiState.currentTrack?.trackId,
                        isPlaying = uiState.playbackState == com.aura.music.domain.player.PlaybackState.Playing,
                        isBuffering = uiState.playbackState == com.aura.music.domain.player.PlaybackState.Buffering || uiState.playbackState == com.aura.music.domain.player.PlaybackState.Preparing,
                        onTrackClick = { track, index ->
                            orchestrator.playTrack(
                                trackId = track.id,
                                contextType = "all",
                                contextId = "all",
                                contextTracks = filteredTracks.map { orchestrator.toQueuedTrack(it) },
                                startIndex = index
                            )
                        },
                        onToggleLike = onToggleLike,
                        onOpenArtist = { appState.openArtist(it) },
                        onOpenAlbum = { appState.openAlbum(it) },
                        isLoading = isLoading
                    )
                }
            }
            1 -> {
                // --- ONGLET ALBUMS ---
                if (allAlbums.isEmpty() && isLoading) {
                    val shimmerBrush = rememberShimmerBrush()
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(12) {
                            ShimmerAlbumCard(brush = shimmerBrush)
                        }
                    }
                } else if (filteredAlbums.isEmpty()) {
                    if (localSearchQuery.isNotBlank()) {
                        EmptyLibrarySearchResult(
                            query = localSearchQuery,
                            onClear = { localSearchQuery = "" }
                        )
                    } else {
                        EmptyLibraryPlaceholder(
                            message = when (sourceFilter) {
                                LibrarySourceFilter.OFFLINE -> "Aucun album hors-ligne dans votre bibliothèque"
                                LibrarySourceFilter.CLOUD -> "Aucun album cloud dans votre bibliothèque"
                                else -> "Aucun album dans votre bibliothèque"
                            }
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredAlbums, key = { it.id }) { album ->
                            AlbumGridCard(
                                album = album,
                                onClick = { appState.openAlbum(album.id) }
                            )
                        }
                    }
                }
            }
            2 -> {
                // --- ONGLET ARTISTES ---
                if (allArtists.isEmpty() && isLoading) {
                    val shimmerBrush = rememberShimmerBrush()
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(12) {
                            ShimmerArtistCard(brush = shimmerBrush)
                        }
                    }
                } else if (filteredArtists.isEmpty()) {
                    if (localSearchQuery.isNotBlank()) {
                        EmptyLibrarySearchResult(
                            query = localSearchQuery,
                            onClear = { localSearchQuery = "" }
                        )
                    } else {
                        EmptyLibraryPlaceholder(
                            message = when (sourceFilter) {
                                LibrarySourceFilter.OFFLINE -> "Aucun artiste hors-ligne dans votre bibliothèque"
                                LibrarySourceFilter.CLOUD -> "Aucun artiste cloud dans votre bibliothèque"
                                else -> "Aucun artiste dans votre bibliothèque"
                            }
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredArtists, key = { it.id }) { artist ->
                            ArtistGridCard(
                                artist = artist,
                                onClick = { appState.openArtist(artist.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryInlineSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    isMac: Boolean,
    modifier: Modifier = Modifier
) {
    val shortcutLabel = if (isMac) "⌘F" else "Ctrl+F"

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = DarkGraphite.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, HairlineDark),
        modifier = modifier.height(36.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = if (query.isNotEmpty()) BlazeOrange else PureWhite.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = PureWhite.copy(alpha = 0.35f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = PureWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(BlazeOrange),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key == Key.Escape) {
                                onQueryChange("")
                                true
                            } else false
                        }
                )
            }

            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(18.dp).handCursor()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Effacer",
                        tint = PureWhite.copy(alpha = 0.5f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = PureWhite.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.12f)),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = shortcutLabel,
                        color = PureWhite.copy(alpha = 0.35f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySortMenu(
    selectedTab: Int,
    currentSort: LibrarySortOrder,
    onSortSelected: (LibrarySortOrder) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val availableSorts = when (selectedTab) {
        0 -> listOf(
            LibrarySortOrder.RECENT,
            LibrarySortOrder.TITLE_AZ,
            LibrarySortOrder.TITLE_ZA,
            LibrarySortOrder.ARTIST,
            LibrarySortOrder.DURATION
        )
        1 -> listOf(
            LibrarySortOrder.RECENT,
            LibrarySortOrder.TITLE_AZ,
            LibrarySortOrder.TITLE_ZA,
            LibrarySortOrder.ARTIST
        )
        2 -> listOf(
            LibrarySortOrder.TITLE_AZ,
            LibrarySortOrder.TITLE_ZA
        )
        else -> listOf(LibrarySortOrder.RECENT)
    }

    Box {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = DarkGraphite.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, HairlineDark),
            modifier = Modifier.height(36.dp)
        ) {
            Row(
                modifier = Modifier
                    .handClickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sort,
                    contentDescription = null,
                    tint = PureWhite.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = currentSort.label,
                    color = PureWhite.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = PureWhite.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(OffBlack).border(1.dp, HairlineDark, RoundedCornerShape(8.dp))
        ) {
            availableSorts.forEach { sort ->
                val isSelected = currentSort == sort
                DropdownMenuItem(
                    text = {
                        Text(
                            text = sort.label,
                            color = if (isSelected) BlazeOrange else PureWhite,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = BlazeOrange,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = {
                        onSortSelected(sort)
                        expanded = false
                    },
                    modifier = Modifier.handCursor()
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrarySearchResult(
    query: String,
    onClear: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.SearchOff,
                contentDescription = null,
                tint = PureWhite.copy(alpha = 0.25f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Aucun résultat pour \"$query\"",
                color = PureWhite.copy(alpha = 0.75f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Effacer le filtre",
                color = BlazeOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .handClickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun EmptyLibraryPlaceholder(
    message: String
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = PureWhite.copy(alpha = 0.25f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = message,
                color = PureWhite.copy(alpha = 0.7f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun AlbumGridCard(album: AlbumBrowseRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .handClickable(interactionSource = interactionSource, onClick = onClick)
            .padding(12.dp)
    ) {
        DesktopArtworkCover(
            coverUri = album.coverUri,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shapeRadius = 8.dp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = album.title,
            color = PureWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = album.artistName ?: "Artiste inconnu",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ArtistGridCard(artist: ArtistBrowseRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .handClickable(interactionSource = interactionSource, onClick = onClick)
            .padding(16.dp)
    ) {
        DesktopArtworkCover(
            coverUri = artist.pictureUri,
            size = 110.dp,
            shapeRadius = 55.dp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = artist.name,
            color = PureWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlaylistGridCard(playlist: PlaylistListRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .handClickable(interactionSource = interactionSource, onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkGraphite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = BlazeOrange,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = playlist.name,
            color = PureWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${playlist.trackCount} titres",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ShimmerAlbumCard(brush: androidx.compose.ui.graphics.Brush) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OffBlack)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shimmer(brush, RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(14.dp)
                .shimmer(brush, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .height(12.dp)
                .shimmer(brush, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
private fun ShimmerArtistCard(brush: androidx.compose.ui.graphics.Brush) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OffBlack)
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .shimmer(brush, CircleShape)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(14.dp)
                .shimmer(brush, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
private fun ShimmerPlaylistCard(brush: androidx.compose.ui.graphics.Brush) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OffBlack)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shimmer(brush, RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(14.dp)
                .shimmer(brush, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(12.dp)
                .shimmer(brush, RoundedCornerShape(4.dp))
        )
    }
}
