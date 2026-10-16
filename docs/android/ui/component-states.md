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

### États de Synchronisation Cloud / Local (Indicateur visuel à droite)
Chaque ligne de morceau affiche un statut visuel clair basé sur la résolution par triplet `(title, artistName, albumTitle)` :
1. **Téléchargé sur l'appareil** (`local_downloaded`) : Icône `Icons.Rounded.CheckCircle` verte (`#4CAF50`). Le fichier est physiquement présent sur le téléphone.
2. **Sur le Cloud uniquement** (`cloud_only`) : Icône `Icons.Rounded.Cloud` orange Blaze (`#FF6E00`). Le morceau est sauvegardé sur votre serveur VPS mais absent du stockage local.
3. **Absent du Cloud et de l'appareil** (`available_online`) : Icône `Icons.Rounded.CloudDownload` orange Blaze (`#FF6E00`). Le morceau provient du catalogue en ligne et peut être téléchargé.

### Switch Mode Hors-Ligne (Playlists & Favoris)
- Présent sur les écrans `PlaylistDetailScreenNew` et `FavoritesScreen`.
- **ON** : Lance le téléchargement en arrière-plan de tous les morceaux non présents physiquement (contrôle de flux par sémaphore).
- **OFF** : Libère le stockage physique de l'appareil en supprimant les fichiers téléchargés locaux, tout en conservant intacts les fichiers sur le Cloud VPS.

### TrackRow - Architecture du Menu Contextuel
Le menu contextuel du `TrackRow` est structuré de façon hiérarchique à 2 niveaux dans `SharedTrackRowItem`.
- Le **bouton cœur (`FavoriteHeartButton`)** est situé directement sur la ligne de chaque morceau et dispose d'une animation de rebond élastique et d'un retour optimiste immédiat (0 ms).
- Le **menu contextuel (`...`)** est épuré à 4-5 options musicales directes au premier niveau, et regroupe les actions de gestion de fichiers/cloud/tags sous le sous-menu **"Plus d'options ›"**.

**Niveaux du Menu Contextuel** :
1. **Niveau 1 — Actions Musicales Principales** :
   - `Ajouter à la file d'attente` (si `onAddToQueue` != null)
   - `Ajouter à une playlist` (si `onAddToPlaylist` != null)
   - `Voir l'artiste` (si `onViewArtist` != null)
   - `Voir l'album` (si `onViewAlbum` != null)
   - `Plus d'options  ›` (si des actions avancées sont disponibles)
2. **Niveau 2 — Fichiers, Cloud & Gestion Avancée** :
   - `‹ Retour` (revient au niveau 1)
   - `Retirer de la playlist` (si `onRemoveFromPlaylist` != null)
   - `Télécharger sur l'appareil` / `Ajouter au Cloud personnel` (si `onDownload` != null)
   - `Supprimer du téléphone` (si `onDeleteDownload` != null)
   - `Ajouter au Cloud` (si `onUploadToCloud` != null)
   - `Récupérer depuis le Cloud` (si `onDownloadFromCloud` != null)
   - `Supprimer du Cloud` (si `onDeleteFromCloud` != null)
   - `Modifier les informations` (si `onEditMetadata` != null)

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

## CloudRecoveryBanner
- `hidden` (quand le nombre de pistes cloud_only <= 5)
- `visible` (quand le nombre de pistes cloud_only > 5)
- Mapping code : `CloudRecoveryBanner` dans `HomeScreen.kt`.

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
- `android/app/src/main/java/com/aura/music/ui/screens/HomeScreen.kt` : `CloudRecoveryBanner`
- `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt` : etats de saisie, suggestions et recherche
- `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt` : etats visuels partages (`EmptyStateSurface`, `BrowseAlbumRail`, `BrowseArtistRail`, `SectionTitle`, `FilterRow`, `SharedTrackRowItem` avec menus contextuels)
- `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt` : implementation du contexte playlist avec menu "Retirer de playlist"
