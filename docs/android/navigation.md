# Android Navigation

## Graphe principal
- `Home`
- `Search`
- `Library`

## Destinations detail
- `Player`
- `PlaylistList`
- `PlaylistDetail`
- `Artist`
- `Album`
- `Downloads`
- `Settings`

## Regles
- `Player` est modal plein ecran depuis le mini-player.
- Les ecrans detail recoivent un identifiant metier stable.
- Le retour depuis `Player` revient a l'ecran precedent sans perdre l'etat de scroll.
- `PlaylistList` et `Downloads` partent de `Library`.

## Code Mapping
- `android/app/src/main/java/com/aura/music/ui/AuraApp.kt` : graphe de navigation Compose, routes, scaffold avec bottom bar et mini-player
- `android/app/src/main/java/com/aura/music/ui/screens/HomeScreen.kt` : ecran Home avec dashboard et liste de tracks recentes
- `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt` : ecran Search avec recherche locale
- `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt` : ecrans Library, Playlists, PlaylistDetail, Artist, Album, Downloads, Settings, Player
