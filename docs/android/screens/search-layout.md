# Search Screen - UI Layout Specification

## Composable Structure

### SearchScreen (Main Container)
- **Type**: `RouteScaffold` with `LazyColumn`
- **Behavior**: Sticky search bar, scrollable content below

#### Item 1: Spacer
- Height: 12.dp
- Padding: horizontal 16.dp

#### Item 2: SearchBarInput
- **Type**: `OutlinedTextField`
- **Shape**: Pill-shaped (RoundedCornerShape(999.dp))
- **Height**: 56.dp
- **Width**: fillMaxWidth
- **Placeholder**: "Rechercher..."
- **Leading Icon**: Icons.Rounded.Search (20.dp)
- **Trailing Icon**: Icons.Rounded.Close (20.dp, visible only if query non-empty)
- **Features**:
  - Single line
  - ImeAction.Search (keyboard submit)
  - TextFieldValue with cursor positioning (cursor always at end of text)
  - Keyboard hidden on submit

#### Item 3: LocalSuggestionsSection (Conditional)
- **Show When**: `query.length >= 3` AND not performing full search
- **Type**: `Card` list
- **Content**: Up to 3 suggestions per type
  - Tracks
  - Artists
  - Albums
- **Interaction**: Selecting suggestion fills query and triggers full search
- **Cursor Position**: Set to end of text after selection

#### Item 4: RecentSearchesSection (Conditional)
- **Show When**: 
  - No active search (`!isSearchComplete`)
  - No suggestions dropdown (`!shouldShowSuggestions`)
  - Recent queries exist (`recentQueries.isNotEmpty()`)
- **Content**: List of recent search queries
- **Display**: Each query as clickable Card with Search icon + text
- **Limit**: 10 most recent queries
- **Interaction**: Clicking a recent query fills search field and triggers search

#### Item 5: Loading Indicator (Conditional)
- **Show When**: `isLoadingFullSearch == true`
- **Type**: `CircularProgressIndicator`
- **Position**: Centered in Box with 32.dp padding

#### Item 6: Error Banner (Conditional)
- **Show When**: `errorMessage != null`
- **Type**: `ErrorBanner` composable
- **Behavior**: Non-blocking, allows local results to remain visible
- **Dismiss**: `X` button calls `viewModel.dismissError()`

#### Item 7: BestMatchSection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND `bestMatch != null`
- **Type**: Column with label + hero card
- **Label**: "Meilleur résultat"
- **Hero Card Variants**:

##### BestMatchSection.OnlineTrack
- **Component**: `HeroTrackCard`
- **Layout**: Row with image left + content right
  - Image: 80.dp square, rounded 8.dp
  - Content column: title (titleSmall, bold) + artist (bodySmall)
- **Click Behavior**: `onPlayTrack()` callback (saves recent search)
- **Colors**: Gradient (ElevatedGraphite → HairlineDark)

##### BestMatchSection.OnlineArtist
- **Component**: `HeroArtistCard`
- **Layout**: Column with center-aligned content
  - Image: 120.dp circle
  - Name: headlineSmall, bold, white
- **Click Behavior**: `onOpenArtist(id)` callback (clickable card)
- **Colors**: Gradient (#792BEE → HairlineDark)

##### BestMatchSection.OnlineAlbum
- **Component**: `HeroAlbumCard`
- **Layout**: Column
  - Image: fullWidth, aspectRatio 1:1, rounded 12.dp
  - Title: headlineSmall, bold, white
  - Artist: bodySmall, white 80% opacity
- **Click Behavior**: `onOpenAlbum(id)` callback (clickable card)
- **Colors**: Gradient (#FF9E00 → HairlineDark)

##### BestMatchSection.LocalTrack
- **Component**: `HeroLocalTrackCard`
- **Layout**: Row with image left + content right (same as online track)
- **Data**: Uses `TrackListRow` (local entity)
- **Colors**: Gradient (ElevatedGraphite → HairlineDark)

##### BestMatchSection.LocalArtist
- **Component**: `HeroLocalArtistCard`
- **Layout**: Column with center-aligned content (same as online artist)
- **Data**: Uses `ArtistBrowseRow` (local entity)
- **Colors**: Gradient (#792BEE → HairlineDark)
- **Interaction**: Clickable, calls `onOpenArtist(id)`

##### BestMatchSection.LocalAlbum
- **Component**: `HeroLocalAlbumCard`
- **Layout**: Column (same as online album)
- **Data**: Uses `AlbumBrowseRow` (local entity)
- **Colors**: Gradient (#FF9E00 → HairlineDark)
- **Interaction**: Clickable, calls `onOpenAlbum(id)`

#### Item 8: LocalLibrarySection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND (localTracks OR localArtists OR localAlbums non-empty)
- **Header**: "Dans votre bibliothèque" + "Résultats locaux"
- **Layout**: Column with sections

##### LocalLibrarySection.Tracks
- **Label**: "Titres" (labelMedium, bold)
- **Content**: Up to 5 `SharedTrackRowItem` composables
- **Data**: `List<TrackListRow>`
- **Click**: Triggers `onPlayTrack(track, allTracks)` and saves recent search

##### LocalLibrarySection.Artists
- **Label**: "Artiste" (labelMedium, bold) - **ADDED**
- **Content**: `BrowseArtistRail` (horizontal scroll)
- **Data**: `List<ArtistBrowseRow>`
- **Click**: Triggers `onOpenArtist(id)`

##### LocalLibrarySection.Albums
- **Label**: "Album" (labelMedium, bold) - **ADDED**
- **Content**: `BrowseAlbumRail` (horizontal scroll)
- **Data**: `List<AlbumBrowseRow>`
- **Click**: Triggers `onOpenAlbum(id)`

#### Item 9: OnlineTracksSection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND `onlineTracks.isNotEmpty()`
- **Header**: "En ligne - Titres" + "Résultats du backend AURA"
- **Content**: Up to 5 cards, each showing:
  - Cover image (40.dp, rounded 6.dp)
  - Title + artist in column
- **Interaction**: Click saves recent search and plays track
- **Data**: `List<TrackSummary>` from API response

#### Item 10: OnlineArtistsSection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND `onlineArtists.isNotEmpty()`
- **Header**: "En ligne - Artistes"
- **Content**: Horizontal rail of artist cards
- **Click**: `onOpenArtist(id)`

#### Item 11: OnlineAlbumsSection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND `onlineAlbums.isNotEmpty()`
- **Header**: "En ligne - Albums"
- **Content**: Horizontal rail of album cards
- **Click**: `onOpenAlbum(id)`

#### Item 12: Empty State (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND all result lists are empty
- **Type**: `EmptyStateSurface`
- **Text**: "Aucun résultat" + "Essayez une autre recherche"

#### Item 13: Bottom Spacer
- **Height**: 24.dp

---

## State Management

### SearchUiState
```kotlin
data class SearchUiState(
    val query: String = "",
    val isLoadingFullSearch: Boolean = false,
    val currentFullSearchResult: HybridSearchResult? = null,
    val localSuggestions: HybridSearchResult? = null,
    val recentQueries: List<String> = emptyList(),
    val showLocalSuggestionsDropdown: Boolean = false,
    val errorMessage: String? = null
)
```

---

## Search Flow

### 1. Startup
- Load recent queries (up to 10)
- Display search bar + recent searches list
- No suggestions, no results

### 2. Typing (0-2 chars)
- Clear suggestions dropdown
- Clear results
- Show search bar + recent searches (if available)

### 3. Typing (3+ chars)
- Load local suggestions in parallel
- Show suggestions dropdown below search bar
- Hide recent searches list
- No full search yet

### 4. Submit (Keyboard or Button)
- Save current query to recent searches (10-entry bounded window)
- Launch full hybrid search (local + online parallel)
- Show loading indicator
- On completion: Display BestMatch + LocalLibrary + Online sections
- On error: Keep local results visible, show error banner

### 5. Clear
- Reset query to empty
- Clear all search state
- Clear suggestions
- Show recent searches list again

---

## Accessibility

- All icons have content descriptions
- Text contrast meets WCAG AA standards
- Touch targets min 48.dp
- Keyboard navigation fully supported
- Screen reader friendly composable structure

---

## Performance Considerations

- `LocalSuggestionsSection`: Lazy load from local DB
- `RecentSearchesSection`: Pre-loaded on ViewModel init
- `BestMatchSection`: Determine type at DAO query time (not runtime parsing)
- `OnlineTracksSection`: Limit to 5 displayed items (lazy load if scroll)
- Images: Async loading via Coil with placeholder

---

## Future Enhancements

- Pagination for online results (pagination cursor in API meta)
- Saved searches vs recent searches distinction
- Search history analytics
- Personalized suggestions based on user preferences
- Voice search input
- Search filters (release date, explicit, etc.)
