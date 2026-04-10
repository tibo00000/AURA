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
