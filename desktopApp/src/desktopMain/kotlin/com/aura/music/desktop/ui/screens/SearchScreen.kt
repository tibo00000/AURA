package com.aura.music.desktop.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.RecentSearchEntity
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.AlbumSummary
import com.aura.music.data.network.ArtistSummary
import com.aura.music.data.network.SearchResponseData
import com.aura.music.data.network.TrackSummary
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.desktop.state.DesktopAppState
import com.aura.music.desktop.ui.isCloudOnly
import com.aura.music.desktop.ui.components.DesktopArtworkCover
import com.aura.music.desktop.ui.components.DesktopEditMetadataDialog
import com.aura.music.desktop.ui.components.DesktopTrackContextMenu
import com.aura.music.desktop.ui.components.DesktopTrackTable
import com.aura.music.desktop.ui.components.TrackSortField
import com.aura.music.desktop.ui.components.TrackTableHeaderRow
import com.aura.music.desktop.ui.components.TrackTableRowItem
import com.aura.music.desktop.ui.formatDuration
import com.aura.music.desktop.utils.DesktopTrackMatcher
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.search.SearchNormalizer
import com.aura.music.ui.components.ShimmerTrackRow
import com.aura.music.ui.components.rememberShimmerBrush
import com.aura.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class DesktopHybridSuggestions(
    val localTracks: List<TrackListRow> = emptyList(),
    val localArtists: List<ArtistBrowseRow> = emptyList(),
    val localAlbums: List<AlbumBrowseRow> = emptyList(),
    val onlineTracks: List<TrackSummary> = emptyList(),
    val onlineArtists: List<ArtistSummary> = emptyList(),
    val onlineAlbums: List<AlbumSummary> = emptyList(),
) {
    val isEmpty: Boolean get() = localTracks.isEmpty() && localArtists.isEmpty() && localAlbums.isEmpty() &&
            onlineTracks.isEmpty() && onlineArtists.isEmpty() && onlineAlbums.isEmpty()
}

enum class SearchCategoryFilter(val label: String) {
    ALL("Tout"),
    TRACKS("Titres"),
    ARTISTS("Artistes"),
    ALBUMS("Albums")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    allTracks: List<TrackListRow>,
    allAlbums: List<AlbumBrowseRow>,
    allArtists: List<ArtistBrowseRow>,
    orchestrator: DesktopPlaybackOrchestrator,
    appState: DesktopAppState,
    onToggleLike: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var onlineResults by remember { mutableStateOf<SearchResponseData?>(null) }
    var isOnlineLoading by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf(SearchCategoryFilter.ALL) }
    var isSearchSubmitted by remember { mutableStateOf(appState.searchQuery.isNotBlank()) }

    // Historique des recherches récentes issu de Room
    val recentSearches by remember(orchestrator.database) {
        orchestrator.database.recentSearchDao().getRecentQueriesFlow(12)
    }.collectAsState(initial = emptyList())

    // Pré-indexation O(1) de la bibliothèque locale
    val localTracksById = remember(allTracks) {
        allTracks.associateBy { it.id }
    }
    val localTracksByDeezerId = remember(allTracks) {
        buildMap<String, TrackListRow> {
            for (t in allTracks) {
                val dId = DesktopTrackMatcher.extractDeezerId(t.id)
                if (dId != null) {
                    put(dId, t)
                }
            }
        }
    }
    val localTracksByNormKey = remember(allTracks) {
        buildMap<String, TrackListRow> {
            for (t in allTracks) {
                val tNorm = SearchNormalizer.normalize(t.title)
                val aNorm = SearchNormalizer.normalize(t.artistName)
                if (tNorm.isNotBlank() && aNorm.isNotBlank()) {
                    put("${tNorm}_${aNorm}", t)
                }
            }
        }
    }

    fun resolveLocalMatch(track: TrackSummary): TrackListRow? {
        localTracksById[track.id]?.let { return it }
        val dId = DesktopTrackMatcher.extractDeezerId(track.id)
        if (dId != null) {
            localTracksByDeezerId[dId]?.let { return it }
        }
        val tNorm = SearchNormalizer.normalize(track.title)
        val aNorm = SearchNormalizer.normalize(track.displayArtistName)
        if (tNorm.isNotBlank() && aNorm.isNotBlank()) {
            localTracksByNormKey["${tNorm}_${aNorm}"]?.let { return it }
        }
        return null
    }

    fun performOnlineSearch(query: String) {
        if (query.trim().length < 2) return
        isOnlineLoading = true
        onlineError = null
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val resp = orchestrator.apiService.search(query.trim())
                if (resp.data != null) {
                    onlineResults = resp.data
                } else {
                    onlineError = resp.error?.message ?: "Aucun résultat trouvé"
                }
            } catch (e: Exception) {
                onlineError = "Erreur de connexion : ${e.message}"
            } finally {
                isOnlineLoading = false
            }
        }
    }

    var suggestions by remember { mutableStateOf<DesktopHybridSuggestions?>(null) }
    var trackForContextMenu by remember { mutableStateOf<TrackListRow?>(null) }
    var trackForMetadataEdit by remember { mutableStateOf<TrackListRow?>(null) }

    fun submitSearch(query: String, targetTab: Int? = null) {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            appState.searchQuery = trimmed
            if (targetTab != null) {
                appState.searchTab = targetTab
            }
            isSearchSubmitted = true
            coroutineScope.launch(Dispatchers.IO) {
                orchestrator.database.recentSearchDao().recordSearch(
                    RecentSearchEntity(
                        id = "query:${trimmed.lowercase()}",
                        query = trimmed,
                        searchedAt = System.currentTimeMillis()
                    )
                )
                orchestrator.database.recentSearchDao().trimTo(15)
            }
            if (appState.searchTab == 1 && trimmed.length >= 2) {
                performOnlineSearch(trimmed)
            }
        }
    }

    // Suggestions en temps réel lors de la frappe
    LaunchedEffect(appState.searchQuery, isSearchSubmitted) {
        val q = appState.searchQuery.trim()
        if (q.length < 2 || isSearchSubmitted) {
            suggestions = null
            return@LaunchedEffect
        }

        // Suggestions locales instantanées
        val normQ = SearchNormalizer.normalize(q)
        val locTracks = allTracks.filter {
            SearchNormalizer.normalize(it.title).contains(normQ) ||
            SearchNormalizer.normalize(it.artistName).contains(normQ)
        }.take(3)
        val locArtists = allArtists.filter {
            SearchNormalizer.normalize(it.name).contains(normQ)
        }.take(2)
        val locAlbums = allAlbums.filter {
            SearchNormalizer.normalize(it.title).contains(normQ) ||
            (it.artistName != null && SearchNormalizer.normalize(it.artistName!!).contains(normQ))
        }.take(2)

        suggestions = DesktopHybridSuggestions(
            localTracks = locTracks,
            localArtists = locArtists,
            localAlbums = locAlbums
        )

        // Debounce 250ms pour requêter Deezer en ligne
        delay(250)
        try {
            val resp = orchestrator.apiService.search(query = q, limitTracks = 3, limitArtists = 2, limitAlbums = 2)
            val data = resp.data
            if (data != null && !isSearchSubmitted && appState.searchQuery.trim() == q) {
                suggestions = DesktopHybridSuggestions(
                    localTracks = locTracks,
                    localArtists = locArtists,
                    localAlbums = locAlbums,
                    onlineTracks = data.tracks.take(3),
                    onlineArtists = data.artists.take(2),
                    onlineAlbums = data.albums.take(2)
                )
            }
        } catch (_: Exception) {
            // garder suggestions locales
        }
    }

    // Débouncing pour recherche en ligne automatique si déjà soumise
    LaunchedEffect(appState.searchQuery, isSearchSubmitted, appState.searchTab) {
        if (isSearchSubmitted && appState.searchTab == 1 && appState.searchQuery.trim().length >= 2) {
            delay(350)
            performOnlineSearch(appState.searchQuery)
        }
    }

    // Filtrage local mémoïsé
    val filteredLocalTracks = remember(appState.searchQuery, allTracks) {
        if (appState.searchQuery.isBlank()) allTracks
        else {
            val q = SearchNormalizer.normalize(appState.searchQuery)
            allTracks.filter {
                SearchNormalizer.normalize(it.title).contains(q) ||
                SearchNormalizer.normalize(it.artistName).contains(q) ||
                (it.albumTitle != null && SearchNormalizer.normalize(it.albumTitle!!).contains(q))
            }
        }
    }

    val filteredLocalAlbums = remember(appState.searchQuery, allAlbums) {
        if (appState.searchQuery.isBlank()) allAlbums
        else {
            val q = SearchNormalizer.normalize(appState.searchQuery)
            allAlbums.filter {
                SearchNormalizer.normalize(it.title).contains(q) ||
                (it.artistName != null && SearchNormalizer.normalize(it.artistName!!).contains(q))
            }
        }
    }

    val filteredLocalArtists = remember(appState.searchQuery, allArtists) {
        if (appState.searchQuery.isBlank()) allArtists
        else {
            val q = SearchNormalizer.normalize(appState.searchQuery)
            allArtists.filter { SearchNormalizer.normalize(it.name).contains(q) }
        }
    }

    val uiState by orchestrator.uiState.collectAsState()
    val isBuffering = uiState.playbackState == PlaybackState.Buffering || uiState.playbackState == PlaybackState.Preparing
    val shimmerBrush = rememberShimmerBrush()

    // Animation de positionnement de la barre de recherche
    val topSpacerHeight by animateDpAsState(
        targetValue = if (isSearchSubmitted) 0.dp else 100.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(topSpacerHeight))

        // En-tête héro visible uniquement au repos (quand aucune recherche n'est validée)
        if (!isSearchSubmitted) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = BlazeOrange,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Rechercher",
                    color = PureWhite,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Trouvez vos titres, artistes et albums préférés",
                    color = PureWhite.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(28.dp))
            }
        }

        // 1. Barre de recherche avec écoute de la touche Entrée et bouton d'effacement
        OutlinedTextField(
            value = appState.searchQuery,
            onValueChange = {
                appState.searchQuery = it
                if (it.isBlank()) {
                    isSearchSubmitted = false
                    onlineResults = null
                }
            },
            placeholder = {
                Text(
                    "Rechercher un titre, un artiste, un album...",
                    color = PureWhite.copy(alpha = 0.4f),
                    fontSize = 14.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = BlazeOrange,
                    modifier = Modifier.size(24.dp)
                )
            },
            trailingIcon = {
                if (appState.searchQuery.isNotBlank()) {
                    IconButton(onClick = {
                        appState.searchQuery = ""
                        isSearchSubmitted = false
                        onlineResults = null
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Effacer",
                            tint = PureWhite.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = OffBlack,
                unfocusedContainerColor = OffBlack,
                focusedBorderColor = BlazeOrange,
                unfocusedBorderColor = HairlineDark,
                focusedTextColor = PureWhite,
                unfocusedTextColor = PureWhite,
                cursorColor = BlazeOrange
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { appState.isInputFocused = it.isFocused }
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyUp && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
                        if (appState.searchQuery.isNotBlank()) {
                            submitSearch(appState.searchQuery)
                        }
                        true
                    } else false
                }
        )

        // 2. Écran au repos ou suggestions pendant la saisie
        if (!isSearchSubmitted) {
            Spacer(modifier = Modifier.height(24.dp))

            if (appState.searchQuery.isNotBlank()) {
                // Suggestions lors de la saisie
                val currentSuggestions = suggestions
                if (currentSuggestions != null && !currentSuggestions.isEmpty) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Suggestions",
                            color = PureWhite.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                        )

                        // 1. Titres locaux
                        currentSuggestions.localTracks.forEach { track ->
                            DesktopSuggestionRow(
                                icon = Icons.Rounded.MusicNote,
                                iconTint = PureWhite.copy(alpha = 0.5f),
                                title = track.title,
                                subtitle = "Titre • ${track.artistName} (En bibliothèque)",
                                isOnline = false,
                                onClick = { submitSearch(track.title, targetTab = 0) }
                            )
                        }

                        // 2. Artistes locaux
                        currentSuggestions.localArtists.forEach { artist ->
                            DesktopSuggestionRow(
                                icon = Icons.Rounded.Person,
                                iconTint = PureWhite.copy(alpha = 0.5f),
                                title = artist.name,
                                subtitle = "Artiste (En bibliothèque)",
                                isOnline = false,
                                onClick = { submitSearch(artist.name, targetTab = 0) }
                            )
                        }

                        // 3. Albums locaux
                        currentSuggestions.localAlbums.forEach { album ->
                            DesktopSuggestionRow(
                                icon = Icons.Rounded.Album,
                                iconTint = PureWhite.copy(alpha = 0.5f),
                                title = album.title,
                                subtitle = "Album • ${album.artistName ?: ""} (En bibliothèque)",
                                isOnline = false,
                                onClick = { submitSearch(album.title, targetTab = 0) }
                            )
                        }

                        // 4. Titres en ligne (Deezer) -> bascule directement sur l'onglet en ligne
                        currentSuggestions.onlineTracks.forEach { track ->
                            DesktopSuggestionRow(
                                icon = Icons.Rounded.Search,
                                iconTint = BlazeOrange,
                                title = track.title,
                                subtitle = "Titre • ${track.displayArtistName} (En ligne • Deezer)",
                                isOnline = true,
                                onClick = { submitSearch(track.title, targetTab = 1) }
                            )
                        }

                        // 5. Artistes en ligne (Deezer) -> bascule directement sur l'onglet en ligne
                        currentSuggestions.onlineArtists.forEach { artist ->
                            DesktopSuggestionRow(
                                icon = Icons.Rounded.Person,
                                iconTint = BlazeOrange,
                                title = artist.name,
                                subtitle = "Artiste (En ligne • Deezer)",
                                isOnline = true,
                                onClick = { submitSearch(artist.name, targetTab = 1) }
                            )
                        }

                        // 6. Albums en ligne (Deezer) -> bascule directement sur l'onglet en ligne
                        currentSuggestions.onlineAlbums.forEach { album ->
                            DesktopSuggestionRow(
                                icon = Icons.Rounded.Album,
                                iconTint = BlazeOrange,
                                title = album.title,
                                subtitle = "Album • ${album.primaryArtistName} (En ligne • Deezer)",
                                isOnline = true,
                                onClick = { submitSearch(album.title, targetTab = 1) }
                            )
                        }
                    }
                }
            } else {
                // Recherches récentes en pleine largeur
                if (recentSearches.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = PureWhite.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Recherches récentes",
                                    color = PureWhite,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Text(
                                text = "Tout effacer",
                                color = BlazeOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            orchestrator.database.recentSearchDao().clearAll()
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            recentSearches.forEach { query ->
                                val interactionSource = remember { MutableInteractionSource() }
                                val isHovered by interactionSource.collectIsHoveredAsState()

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isHovered) PureWhite.copy(alpha = 0.05f) else Color.Transparent)
                                        .hoverable(interactionSource)
                                        .clickable {
                                            submitSearch(query)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = PureWhite.copy(alpha = if (isHovered) 0.8f else 0.4f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = query,
                                        color = if (isHovered) PureWhite else PureWhite.copy(alpha = 0.85f),
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    orchestrator.database.recentSearchDao().deleteQuery(query)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Supprimer",
                                            tint = PureWhite.copy(alpha = if (isHovered) 0.6f else 0.35f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tapez sur Entrée pour lancer une recherche dans votre musique ou en ligne.",
                            color = PureWhite.copy(alpha = 0.35f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            // 3. Écran avec recherche active : Onglets & Catégories
            Spacer(modifier = Modifier.height(16.dp))

            TabRow(
                selectedTabIndex = appState.searchTab,
                containerColor = Color.Transparent,
                contentColor = BlazeOrange,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[appState.searchTab]),
                        color = BlazeOrange,
                        height = 3.dp
                    )
                },
                divider = { HorizontalDivider(color = HairlineDark) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = appState.searchTab == 0,
                    onClick = { appState.searchTab = 0 },
                    text = {
                        Text(
                            text = "Ma Bibliothèque (${filteredLocalTracks.size})",
                            color = if (appState.searchTab == 0) PureWhite else PureWhite.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            fontWeight = if (appState.searchTab == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = appState.searchTab == 1,
                    onClick = {
                        appState.searchTab = 1
                        if (onlineResults == null && appState.searchQuery.trim().length >= 2) {
                            performOnlineSearch(appState.searchQuery)
                        }
                    },
                    text = {
                        Text(
                            text = "Catalogue Cloud / En ligne",
                            color = if (appState.searchTab == 1) PureWhite else PureWhite.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            fontWeight = if (appState.searchTab == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Les 4 catégories ergonomiques (largeur harmonieuse bornée à 640dp et survol clippé)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
            ) {
                SearchCategoryFilter.entries.forEach { filter ->
                    val isSelected = selectedCategory == filter
                    val interactionSource = remember { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()
                    val shape = RoundedCornerShape(20.dp)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(shape)
                            .background(
                                when {
                                    isSelected -> BlazeOrange
                                    isHovered -> DarkGraphite
                                    else -> OffBlack
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) BlazeOrange else HairlineDark,
                                shape = shape
                            )
                            .hoverable(interactionSource)
                            .clickable { selectedCategory = filter },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filter.label,
                            color = if (isSelected) PureWhite else PureWhite.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 5. Résultats de recherche
            if (appState.searchTab == 0) {
                // === VUE BIBLIOTHÈQUE LOCALE ===
                when (selectedCategory) {
                    SearchCategoryFilter.ALL -> {
                        val hasAnyLocalResult = filteredLocalArtists.isNotEmpty() || filteredLocalAlbums.isNotEmpty() || filteredLocalTracks.isNotEmpty()
                        if (hasAnyLocalResult) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                // Artistes
                                if (filteredLocalArtists.isNotEmpty()) {
                                    item {
                                        Text(text = "Artistes", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            items(filteredLocalArtists, key = { it.id }) { artist ->
                                                DesktopArtistItem(
                                                    artist = artist,
                                                    onClick = {
                                                        appState.selectedArtistId = artist.id
                                                        appState.navigateTo("artist_detail")
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Albums
                                if (filteredLocalAlbums.isNotEmpty()) {
                                    item {
                                        Text(text = "Albums", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            items(filteredLocalAlbums, key = { it.id }) { album ->
                                                DesktopAlbumItem(
                                                    album = album,
                                                    onClick = {
                                                        appState.selectedAlbumId = album.id
                                                        appState.navigateTo("album_detail")
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Morceaux
                                if (filteredLocalTracks.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "Morceaux (${filteredLocalTracks.size})",
                                            color = PureWhite,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        TrackTableHeaderRow(
                                            sortField = TrackSortField.DEFAULT,
                                            sortAscending = true,
                                            onSortChanged = { },
                                            showAlbumColumn = true,
                                            showDateAddedColumn = false
                                        )
                                        HorizontalDivider(color = HairlineDark, thickness = 1.dp)
                                    }

                                    itemsIndexed(filteredLocalTracks, key = { _, track -> track.id }) { index, track ->
                                        val isCurrent = track.id == uiState.currentTrack?.trackId
                                        TrackTableRowItem(
                                            index = index + 1,
                                            track = track,
                                            isCurrent = isCurrent,
                                            isPlaying = uiState.isPlaying && isCurrent,
                                            isBuffering = isBuffering && isCurrent,
                                            onPlay = {
                                                orchestrator.playTrack(
                                                    trackId = track.id,
                                                    contextType = "search",
                                                    contextId = appState.searchQuery,
                                                    contextTracks = filteredLocalTracks.map { orchestrator.toQueuedTrack(it) },
                                                    startIndex = index
                                                )
                                            },
                                            onToggleLike = { onToggleLike(track.id) },
                                            onOpenArtist = {
                                                appState.selectedArtistId = track.artistId ?: "artist:${track.artistName}"
                                                appState.navigateTo("artist_detail")
                                            },
                                            onOpenAlbum = {
                                                track.albumId?.let {
                                                    appState.selectedAlbumId = it
                                                    appState.navigateTo("album_detail")
                                                }
                                            },
                                            onContextMenu = {
                                                trackForContextMenu = track
                                            },
                                            showAlbumColumn = true,
                                            showDateAddedColumn = false
                                        )
                                    }
                                }
                            }
                        } else {
                            DesktopEmptyLocalSearch(
                                query = appState.searchQuery,
                                onSearchOnline = {
                                    appState.searchTab = 1
                                    performOnlineSearch(appState.searchQuery)
                                }
                            )
                        }
                    }

                    SearchCategoryFilter.TRACKS -> {
                        if (filteredLocalTracks.isNotEmpty()) {
                            DesktopTrackTable(
                                tracks = filteredLocalTracks,
                                activeTrackId = uiState.currentTrack?.trackId,
                                isPlaying = uiState.isPlaying,
                                isBuffering = isBuffering,
                                onTrackClick = { clickedTrack, index ->
                                    orchestrator.playTrack(
                                        trackId = clickedTrack.id,
                                        contextType = "search",
                                        contextId = appState.searchQuery,
                                        contextTracks = filteredLocalTracks.map { orchestrator.toQueuedTrack(it) },
                                        startIndex = index
                                    )
                                },
                                onToggleLike = onToggleLike,
                                onOpenArtist = { artistId ->
                                    appState.selectedArtistId = artistId
                                    appState.navigateTo("artist_detail")
                                },
                                onOpenAlbum = { albumId ->
                                    appState.selectedAlbumId = albumId
                                    appState.navigateTo("album_detail")
                                },
                                onContextMenu = { track ->
                                    trackForContextMenu = track
                                }
                            )
                        } else {
                            DesktopEmptyLocalSearch(
                                query = appState.searchQuery,
                                onSearchOnline = {
                                    appState.searchTab = 1
                                    performOnlineSearch(appState.searchQuery)
                                }
                            )
                        }
                    }

                    SearchCategoryFilter.ARTISTS -> {
                        if (filteredLocalArtists.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(130.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredLocalArtists, key = { it.id }) { artist ->
                                    DesktopArtistItem(
                                        artist = artist,
                                        onClick = {
                                            appState.selectedArtistId = artist.id
                                            appState.navigateTo("artist_detail")
                                        }
                                    )
                                }
                            }
                        } else {
                            DesktopEmptyLocalSearch(
                                query = appState.searchQuery,
                                onSearchOnline = {
                                    appState.searchTab = 1
                                    performOnlineSearch(appState.searchQuery)
                                }
                            )
                        }
                    }

                    SearchCategoryFilter.ALBUMS -> {
                        if (filteredLocalAlbums.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(140.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredLocalAlbums, key = { it.id }) { album ->
                                    DesktopAlbumItem(
                                        album = album,
                                        onClick = {
                                            appState.selectedAlbumId = album.id
                                            appState.navigateTo("album_detail")
                                        }
                                    )
                                }
                            }
                        } else {
                            DesktopEmptyLocalSearch(
                                query = appState.searchQuery,
                                onSearchOnline = {
                                    appState.searchTab = 1
                                    performOnlineSearch(appState.searchQuery)
                                }
                            )
                        }
                    }
                }
            } else {
                // === VUE CATALOGUE CLOUD / EN LIGNE ===
                if (isOnlineLoading) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(7) {
                            ShimmerTrackRow(brush = shimmerBrush)
                        }
                    }
                } else if (onlineError != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = onlineError!!, color = PureWhite.copy(alpha = 0.7f), fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { performOnlineSearch(appState.searchQuery) },
                                colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange)
                            ) {
                                Text("Réessayer")
                            }
                        }
                    }
                } else if (onlineResults != null) {
                    val results = onlineResults!!

                    when (selectedCategory) {
                        SearchCategoryFilter.ALL -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                // Artistes distants
                                if (results.artists.isNotEmpty()) {
                                    item {
                                        Text(text = "Artistes", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            items(results.artists, key = { it.id }) { artist ->
                                                DesktopOnlineArtistItem(
                                                    artist = artist,
                                                    onClick = {
                                                        appState.selectedArtistId = artist.id
                                                        appState.navigateTo("artist_detail")
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Albums distants
                                if (results.albums.isNotEmpty()) {
                                    item {
                                        Text(text = "Albums", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            items(results.albums, key = { it.id }) { album ->
                                                DesktopOnlineAlbumItem(
                                                    album = album,
                                                    onClick = {
                                                        appState.selectedAlbumId = album.id
                                                        appState.navigateTo("album_detail")
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Titres distants en streaming & téléchargement avec en-têtes de colonnes
                                if (results.tracks.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "Titres en streaming & téléchargement (${results.tracks.size})",
                                            color = PureWhite,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        DesktopOnlineTableHeaderRow()
                                        HorizontalDivider(color = HairlineDark, thickness = 1.dp)
                                    }

                                    itemsIndexed(results.tracks, key = { _, track -> track.id }) { index, track ->
                                        val matchedLocal = resolveLocalMatch(track)
                                        val isCurrentPlaying = uiState.currentTrack?.trackId == track.id
                                        DesktopOnlineTrackRow(
                                            index = index + 1,
                                            track = track,
                                            matchedLocal = matchedLocal,
                                            isCurrentPlaying = isCurrentPlaying,
                                            isPlaying = uiState.isPlaying && isCurrentPlaying,
                                            isBuffering = isBuffering && isCurrentPlaying,
                                            onPlay = {
                                                orchestrator.playOnlineTrack(track, results.tracks)
                                            },
                                            onDownload = {
                                                orchestrator.triggerTrackDownload(track)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        SearchCategoryFilter.TRACKS -> {
                            if (results.tracks.isNotEmpty()) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    item {
                                        DesktopOnlineTableHeaderRow()
                                        HorizontalDivider(color = HairlineDark, thickness = 1.dp)
                                    }
                                    itemsIndexed(results.tracks, key = { _, track -> track.id }) { index, track ->
                                        val matchedLocal = resolveLocalMatch(track)
                                        val isCurrentPlaying = uiState.currentTrack?.trackId == track.id
                                        DesktopOnlineTrackRow(
                                            index = index + 1,
                                            track = track,
                                            matchedLocal = matchedLocal,
                                            isCurrentPlaying = isCurrentPlaying,
                                            isPlaying = uiState.isPlaying && isCurrentPlaying,
                                            isBuffering = isBuffering && isCurrentPlaying,
                                            onPlay = {
                                                orchestrator.playOnlineTrack(track, results.tracks)
                                            },
                                            onDownload = {
                                                orchestrator.triggerTrackDownload(track)
                                            }
                                        )
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Aucun titre trouvé en ligne.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                                }
                            }
                        }

                        SearchCategoryFilter.ARTISTS -> {
                            if (results.artists.isNotEmpty()) {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(130.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(results.artists, key = { it.id }) { artist ->
                                        DesktopOnlineArtistItem(
                                            artist = artist,
                                            onClick = {
                                                appState.selectedArtistId = artist.id
                                                appState.navigateTo("artist_detail")
                                            }
                                        )
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Aucun artiste trouvé en ligne.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                                }
                            }
                        }

                        SearchCategoryFilter.ALBUMS -> {
                            if (results.albums.isNotEmpty()) {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(140.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(results.albums, key = { it.id }) { album ->
                                        DesktopOnlineAlbumItem(
                                            album = album,
                                            onClick = {
                                                appState.selectedAlbumId = album.id
                                                appState.navigateTo("album_detail")
                                            }
                                        )
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Aucun album trouvé en ligne.", color = PureWhite.copy(alpha = 0.5f), fontSize = 14.sp)
                                }
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.CloudQueue,
                                contentDescription = null,
                                tint = BlazeOrange.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Catalogue Cloud AURA & Deezer",
                                color = PureWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Entrez au moins 2 caractères pour rechercher parmi des millions de titres.",
                                color = PureWhite.copy(alpha = 0.4f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // Menus contextuels et dialogues d'édition pour les pistes locales
        if (trackForContextMenu != null) {
            val trk = trackForContextMenu!!
            DesktopTrackContextMenu(
                track = trk,
                onDismiss = { trackForContextMenu = null },
                onToggleLike = {
                    onToggleLike(trk.id)
                    trackForContextMenu = null
                },
                onAddToQueue = {
                    orchestrator.addToQueue(orchestrator.toQueuedTrack(trk))
                    trackForContextMenu = null
                },
                onPlayNext = {
                    orchestrator.playNext(orchestrator.toQueuedTrack(trk))
                    trackForContextMenu = null
                },
                onOpenArtist = {
                    appState.selectedArtistId = trk.artistId ?: "artist:${trk.artistName}"
                    appState.navigateTo("artist_detail")
                    trackForContextMenu = null
                },
                onOpenAlbum = {
                    trk.albumId?.let {
                        appState.selectedAlbumId = it
                        appState.navigateTo("album_detail")
                    }
                    trackForContextMenu = null
                },
                onEditMetadata = {
                    trackForMetadataEdit = trk
                    trackForContextMenu = null
                },
                onDownloadTrack = {
                    orchestrator.triggerSingleFileDownload(trk)
                },
                onUploadCloud = {
                    orchestrator.triggerSingleFileUpload(trk)
                }
            )
        }

        if (trackForMetadataEdit != null) {
            DesktopEditMetadataDialog(
                track = trackForMetadataEdit,
                database = orchestrator.database,
                appState = appState,
                onDismiss = { trackForMetadataEdit = null },
                onSaved = { }
            )
        }
    }
}

@Composable
private fun DesktopSuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    isOnline: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered) PureWhite.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isHovered) PureWhite else PureWhite.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = if (isHovered) PureWhite.copy(alpha = 0.7f) else PureWhite.copy(alpha = 0.45f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isOnline) {
            Icon(
                imageVector = Icons.Rounded.ArrowOutward,
                contentDescription = "Ouvrir en ligne",
                tint = if (isHovered) BlazeOrange else BlazeOrange.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.NorthWest,
                contentDescription = "Sélectionner",
                tint = PureWhite.copy(alpha = if (isHovered) 0.5f else 0.25f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun DesktopArtistItem(artist: ArtistBrowseRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DesktopArtworkCover(coverUri = artist.pictureUri, size = 96.dp, shapeRadius = 48.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = artist.name,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Artiste",
            color = PureWhite.copy(alpha = 0.45f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun DesktopAlbumItem(album: AlbumBrowseRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(135.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        DesktopArtworkCover(coverUri = album.coverUri, size = 111.dp, shapeRadius = 8.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = album.title,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (album.artistName != null) {
            Text(
                text = album.artistName!!,
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DesktopOnlineArtistItem(artist: ArtistSummary, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DesktopArtworkCover(coverUri = artist.pictureUri, size = 96.dp, shapeRadius = 48.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = artist.name,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Artiste",
            color = PureWhite.copy(alpha = 0.45f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun DesktopOnlineAlbumItem(album: AlbumSummary, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .width(135.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHovered) DarkGraphite else OffBlack)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        DesktopArtworkCover(coverUri = album.coverUri, size = 111.dp, shapeRadius = 8.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = album.title,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = album.primaryArtistName,
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DesktopEmptyLocalSearch(
    query: String,
    onSearchOnline: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 500.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = PureWhite.copy(alpha = 0.35f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Aucun résultat dans votre bibliothèque",
                color = PureWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (query.isNotBlank()) "Aucun morceau, artiste ou album ne correspond à « $query »."
                else "Aucun élément trouvé pour votre recherche.",
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onSearchOnline,
                colors = ButtonDefaults.buttonColors(containerColor = BlazeOrange),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Rechercher dans le catalogue en ligne",
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DesktopOnlineTableHeaderRow() {
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

        Text(
            text = "TITRE",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(2.5f)
        )

        Text(
            text = "ARTISTE",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1.8f)
        )

        Text(
            text = "ALBUM",
            color = PureWhite.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1.8f)
        )

        Row(
            modifier = Modifier.width(136.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(32.dp))
            Text(
                text = "DURÉE",
                color = PureWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.End
            )
            Spacer(modifier = Modifier.width(56.dp))
        }
    }
}

@Composable
private fun DesktopOnlineTrackRow(
    index: Int,
    track: TrackSummary,
    matchedLocal: TrackListRow?,
    isCurrentPlaying: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isCurrentPlaying -> BlazeOrange.copy(alpha = 0.12f)
                    isHovered -> PureWhite.copy(alpha = 0.04f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interactionSource)
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Numéro ou icône de lecture (# / 40.dp)
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isCurrentPlaying && isBuffering -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BlazeOrange,
                        strokeWidth = 2.dp
                    )
                }
                isCurrentPlaying && isPlaying -> {
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
                        color = if (isCurrentPlaying) BlazeOrange else PureWhite.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        fontWeight = if (isCurrentPlaying) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Titre + Artwork + Indicateurs (weight 2.5f)
        Row(
            modifier = Modifier.weight(2.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DesktopArtworkCover(coverUri = track.coverUri, size = 40.dp, shapeRadius = 4.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = track.title,
                    color = if (isCurrentPlaying) BlazeOrange else PureWhite,
                    fontSize = 13.sp,
                    fontWeight = if (isCurrentPlaying) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (matchedLocal != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (matchedLocal.isCloudOnly) {
                            Icon(
                                imageVector = Icons.Rounded.Cloud,
                                contentDescription = "Sur le Cloud",
                                tint = BlazeOrange,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Sur le Cloud",
                                color = BlazeOrange,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.DownloadDone,
                                contentDescription = "Sur cet ordi",
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Sur cet ordi",
                                color = Color(0xFF00E676),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Artiste (weight 1.8f)
        Text(
            text = track.displayArtistName,
            color = if (isHovered) PureWhite else PureWhite.copy(alpha = 0.65f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.8f)
        )

        // Album (weight 1.8f)
        Text(
            text = track.displayAlbumTitle ?: "-",
            color = if (isHovered) PureWhite.copy(alpha = 0.9f) else PureWhite.copy(alpha = 0.5f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.8f)
        )

        // Durée et Action (136.dp)
        Row(
            modifier = Modifier.width(136.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(32.dp))

            Text(
                text = formatDuration(track.durationMs.toLong()),
                color = if (isHovered) PureWhite else PureWhite.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.End
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Bouton Action (32.dp avec icône 20.dp)
            if (matchedLocal != null && !matchedLocal.isCloudOnly) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = "Sur cet ordinateur",
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else if (matchedLocal != null && matchedLocal.isCloudOnly) {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Télécharger sur cet ordi",
                        tint = BlazeOrange,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = "Ajouter au Cloud",
                        tint = BlazeOrange,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

