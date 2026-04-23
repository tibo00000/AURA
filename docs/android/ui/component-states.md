# Etats Des Composants UI

## Objectif
Definir les etats visuels et interactifs minimaux que chaque composant critique doit supporter pour eviter les interpretations divergentes pendant l'implementation.

## MiniPlayer
- `loading`
- `playing`
- `paused`
- `error`
- `resumable_without_active_playback`

## TrackRow
- `idle`
- `pressed`
- `currently_playing`
- `liked`
- `downloaded`
- `online_only`
- `disabled`

### TrackRow - Architecture du Menu Contextuel
Le menu contextuel du `TrackRow` est gere via le parametre `contextType` de `SharedTrackRowItem`. **Aucun `trailingIcon` custom ne doit etre passe** ; laisser le menu contextuel par defaut gerer les cas documentes.

**Parametres de SharedTrackRowItem** :
```kotlin
fun SharedTrackRowItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    contextType: String = "standard",  // "album", "playlist", "favorites", "search_online", "standard"
    onAddToPlaylist: (() -> Unit)? = null,
    onLike: (() -> Unit)? = null,
    onUnlike: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    // ... autres parametres
)
```

### Contextes et Actions Correspondantes

**Contexte Standard** (`contextType = "standard"` ou defaut)
- Utilise par : Search, Home, Album, Library
- Actions affichees :
  - "Ajouter a une playlist" (si `onAddToPlaylist` fourni)
  - "Ajouter aux favoris" (si `onLike` fourni)
  - "Plus" (si `onMore` fourni)

```kotlin
SharedTrackRowItem(
    title = track.title,
    subtitle = track.artistName,
    onClick = { playTrack() },
    contextType = "standard",
    onAddToPlaylist = { /* ... */ },
    onLike = { /* ... */ },
)
```

**Contexte Playlist** (`contextType = "playlist"`)
- Utilise par : PlaylistDetailScreen
- Actions affichees :
  - "Retirer de cette playlist" (si `onRemoveFromPlaylist` fourni)
  - "Ajouter a une autre playlist" (si `onAddToPlaylist` fourni)

```kotlin
SharedTrackRowItem(
    title = track.title,
    subtitle = track.artistName,
    onClick = { playTrack() },
    contextType = "playlist",
    onRemoveFromPlaylist = { repository.removeTrackFromPlaylist(...) },
    onAddToPlaylist = { /* ouvrir dialog */ },
)
```

**Contexte Favoris** (`contextType = "favorites"`)
- Utilise par : FavoritesScreen
- Actions affichees :
  - "Retirer des favoris" (si `onUnlike` fourni)
  - "Ajouter a une playlist" (si `onAddToPlaylist` fourni)

```kotlin
SharedTrackRowItem(
    title = track.title,
    subtitle = track.artistName,
    onClick = { playTrack() },
    contextType = "favorites",
    onUnlike = { repository.unlikeTrack(...) },
    onAddToPlaylist = { /* ... */ },
)
```

**Contexte Recherche Online** (`contextType = "search_online"`)
- Utilise par : SearchScreen, onglet `En ligne`
- Actions affichees :
  - "Ajouter a une playlist" (si `onAddToPlaylist` fourni)
- Remarque :
  - ce contexte ne montre pas l'action favoris tant que la piste n'est pas encore une entite locale stable

### Regles d'Implementation
1. **Ne jamais passer `trailingIcon`** ; laisser le menu par defaut gerer les cas documentes.
2. **Toujours fournir `contextType`** pour que le menu affiche les actions appropriees.
3. **Passer les callbacks correspondant au contexte** ; le menu ne les affiche que s'ils sont non-null.
4. **Code Mapping** : `SharedTrackRowItem` dans `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`

## PlayerQueueRow
- `queued`
- `dragging`
- `current_track`
- `removable`
- `non_removable`

## SearchBar
- `empty_unfocused`
- `focused_no_query`
- `typing_local_suggestions`
- `validated_loading`
- `results_loaded`
- `inline_online_error_non_blocking`

## SegmentedTabs
- `inactive`
- `active`
- `disabled`

## AlbumCard / ArtistCard / PlaylistCard
- `default`
- `pressed`
- `loading_placeholder`
- `empty_cover_fallback`
- `disabled`
- Mapping code : `AlbumCard` et `ArtistCard` sont portes par les items internes de `BrowseAlbumRail` et `BrowseArtistRail` ; `PlaylistCard` reste porte par les surfaces playlists.

## HeroResumeCard
- `no_resume_data`
- `resume_ready`
- `resume_with_context`
- `resume_error_non_blocking`

## EmptyStateCard
- `pure_empty`
- `empty_with_primary_action`
- `empty_offline_constraint`
- `empty_provider_failure_non_blocking`
- Mapping code : `EmptyStateSurface`.

## PlayerHero
- `loading_track`
- `playing`
- `paused`
- `buffering`
- `error`

## Regles
- Les etats `loading` utilisent des placeholders ou des skeletons calmes.
- Les etats `error` n'annulent pas le langage visuel global.
- Les etats `disabled` doivent rester lisibles sans paraitre casses.
- Les etats `currently_playing` doivent etre reconnaissables immediatement sans utiliser seulement la couleur.

## Code Mapping
- `android/app/src/main/java/com/aura/music/ui/AuraApp.kt` : etats shell actuels du mini-player et des listes principales
- `android/app/src/main/java/com/aura/music/ui/player/PlayerViewModel.kt` : source des etats player
- `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt` : etats de saisie, suggestions et recherche
- `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt` : etats visuels partages (`EmptyStateSurface`, `BrowseAlbumRail`, `BrowseArtistRail`, `SectionTitle`, `FilterRow`, `SharedTrackRowItem` avec menus contextuels)
- `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt` : implementation du contexte playlist avec menu "Retirer de playlist"
