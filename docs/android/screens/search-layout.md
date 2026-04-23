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
- **Shape**: Pill-shaped (`RoundedCornerShape(999.dp)`)
- **Height**: 56.dp
- **Width**: fillMaxWidth
- **Placeholder**: "Rechercher..."
- **Leading Icon**: `Icons.Rounded.Search` (20.dp)
- **Trailing Icon**: `Icons.Rounded.Close` (20.dp, visible only if query non-empty)
- **Features**:
  - Single line
  - `ImeAction.Search` (keyboard submit)
  - `TextFieldValue` with cursor positioning (cursor always at end of text)
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
- **Display**: Each query as clickable card with Search icon + text
- **Limit**: 10 most recent queries
- **Interaction**: Clicking a recent query fills search field and triggers search

#### Item 5: Loading Indicator (Conditional)
- **Show When**: `isLoadingFullSearch == true`
- **Type**: `CircularProgressIndicator`
- **Position**: Centered in `Box` with 32.dp padding

#### Item 6: Error Banner (Conditional)
- **Show When**: `errorMessage != null`
- **Type**: `ErrorBanner` composable
- **Behavior**: Non-blocking, allows local results to remain visible
- **Dismiss**: `X` button calls `viewModel.dismissError()`

#### Item 7: BestMatchSection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND `bestMatch != null`
- **Type**: Column with label + hero card
- **Label**: "Meilleur resultat"
- **Hero Card Variants**:

##### BestMatchSection.OnlineTrack
- **Component**: `HeroTrackCard`
- **Layout**: Row with image left + content right
  - Image: 80.dp square, rounded 8.dp
  - Content column: title (`titleSmall`, bold) + artist (`bodySmall`)
- **Click Behavior**: `onPlayTrack()` callback
- **Colors**: Gradient (`ElevatedGraphite` -> `HairlineDark`)

##### BestMatchSection.OnlineArtist
- **Component**: `HeroArtistCard`
- **Layout**: Column with center-aligned content
  - Image: 120.dp circle
  - Name: `headlineSmall`, bold, white
- **Click Behavior**: `onOpenArtist(id)` callback
- **Colors**: Gradient (`#792BEE` -> `HairlineDark`)

##### BestMatchSection.OnlineAlbum
- **Component**: `HeroAlbumCard`
- **Layout**: Column
  - Image: full width, aspect ratio 1:1, rounded 12.dp
  - Title: `headlineSmall`, bold, white
  - Artist: `bodySmall`, white 80% opacity
- **Click Behavior**: `onOpenAlbum(id)` callback
- **Colors**: Gradient (`#FF9E00` -> `HairlineDark`)

##### BestMatchSection.LocalTrack
- **Component**: `HeroLocalTrackCard`
- **Layout**: Row with image left + content right
- **Data**: `TrackListRow`

##### BestMatchSection.LocalArtist
- **Component**: `HeroLocalArtistCard`
- **Layout**: Column with center-aligned content
- **Data**: `ArtistBrowseRow`
- **Interaction**: `onOpenArtist(id)`

##### BestMatchSection.LocalAlbum
- **Component**: `HeroLocalAlbumCard`
- **Layout**: Column
- **Data**: `AlbumBrowseRow`
- **Interaction**: `onOpenAlbum(id)`

#### Item 8: SearchModeTabs (Conditional - Part of Results)
- **Show When**: `isSearchComplete`
- **Type**: `TabRow`
- **Tabs**:
  - `Bibliotheque`
  - `En ligne`
- **Default Selection**: `Bibliotheque`
- **Behavior**: The hero remains shared; the selected tab controls the content rendered below

#### Item 9: LocalLibrarySection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND tab `Bibliotheque` active AND at least one local family is non-empty
- **Header**: "Dans votre bibliotheque" + "Resultats locaux"
- **Layout**: Column with sections

##### LocalLibrarySection.Tracks
- **Label**: "Titres"
- **Content**: Up to 5 `SharedTrackRowItem`
- **Data**: `List<TrackListRow>`
- **Click**: Triggers `onPlayTrack(track, allTracks)`

##### LocalLibrarySection.Artists
- **Label**: "Artistes"
- **Content**: `BrowseArtistRail`
- **Data**: `List<ArtistBrowseRow>`
- **Click**: `onOpenArtist(id)`

##### LocalLibrarySection.Albums
- **Label**: "Albums"
- **Content**: `BrowseAlbumRail`
- **Data**: `List<AlbumBrowseRow>`
- **Click**: `onOpenAlbum(id)`

#### Item 10: OnlineTracksSection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND tab `En ligne` active AND `onlineTracks.isNotEmpty()`
- **Header**: "En ligne - Titres" + "Resultats du backend AURA"
- **Content**: Up to 5 cards showing cover, title, artist
- **Interaction**: Click plays track
- **Data**: `List<TrackSummary>`

#### Item 11: OnlineArtistsSection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND tab `En ligne` active AND `onlineArtists.isNotEmpty()`
- **Header**: "En ligne - Artistes"
- **Content**: Horizontal rail of artist cards
- **Click**: `onOpenArtist(id)`

#### Item 12: OnlineAlbumsSection (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND tab `En ligne` active AND `onlineAlbums.isNotEmpty()`
- **Header**: "En ligne - Albums"
- **Content**: Horizontal rail of album cards
- **Click**: `onOpenAlbum(id)`

#### Item 13: Empty State (Conditional - Part of Results)
- **Show When**: `isSearchComplete` AND all result lists are empty
- **Type**: `EmptyStateSurface`
- **Text**: "Aucun resultat" + "Essayez une autre recherche"

#### Item 14: Bottom Spacer
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
- On completion: display BestMatch + TabRow (`Bibliotheque` by default) + active tab content
- On error: keep local results visible, show error banner

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
