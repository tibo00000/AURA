# Composants UI Reutilisables

## Objectif
Definir les composants visuels et interactifs reutilisables qui doivent porter la DA d'AURA de facon coherente.

## Composants partages d'ecrans
- Les composants de support multi-ecrans sont centralises dans `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt`.
- Ce fichier porte les briques reutilisables suivantes : `HeroIdentityCard`, `EmptyStateSurface`, `DownloadStateCard`, `FilterRow`, `SectionTitle`, `BrowseArtistRail`, `BrowseAlbumRail`.
- Les ecrans qui reutilisent ces briques les consomment directement depuis le package `com.aura.music.ui.screens`.
- Toute evolution structurelle de ces composants se documente ici avant duplication dans un ecran specifique.

## Lexique canonique et mapping code
- `AlbumCard` : composant unitaire de carte album. Implementation actuelle via les items internes de `BrowseAlbumRail` dans `ScreenSharedComponents.kt`.
- `ArtistCard` : composant unitaire de carte artiste. Implementation actuelle via les items internes de `BrowseArtistRail` dans `ScreenSharedComponents.kt`.
- `SectionHeader` : composant de separation de section. Mapping code direct vers `SectionTitle`.
- `EmptyStateCard` : composant d'etat vide. Mapping code direct vers `EmptyStateSurface`.
- `SegmentedTabs` : composant de filtre segment ecran. Implementation actuelle via `FilterRow` quand l'usage est un filtre horizontal.

## MiniPlayer
- Role : lecteur persistant au-dessus de la navigation basse.
- Structure :
  - pochette compacte
  - bloc titre + artiste
  - controles `previous`, `play/pause`, `next`
- Style :
  - fond `ElevatedGraphite`
  - bordure orange fine
  - coins tres arrondis
- Regles :
  - visible sur toutes les surfaces principales si lecture active ou resumable
  - ouvre `PlayerScreen` sur la zone principale
  - les controles de transport restent secondaires par rapport a l'ouverture du Player

## TrackRow
- Role : affichage standard d'une piste dans bibliotheque, playlists, historique, recherche locale et queue compacte.
- Structure :
  - gauche : cover
  - centre : titre, artiste, album/context secondaire
  - droite : `Like` visible si pertinent, puis menu `...`
- Variantes :
  - standard
  - compact
  - currently playing
  - search-result online
- Regles :
  - le titre a toujours la priorite visuelle
  - les metadonnees restent sur une seule ligne si possible
  - les actions visibles restent limitees pour ne pas encombrer
- Menu contextuel canonique :
  - `Lire maintenant`
  - `Ajouter a la file d'attente`
  - `Ajouter a une playlist`
  - `Voir l'artiste`
  - `Voir l'album`
  - `Telecharger` uniquement si la piste n'est pas disponible localement
  - `Supprimer le telechargement` uniquement si la piste est deja disponible localement
  - `Supprimer` uniquement dans les contextes ou la suppression a un sens metier

## PlayerQueueRow
- Role : ligne dediee a la `priority queue` dans le Player.
- Structure :
  - indicateur d'ordre
  - titre / artiste
  - `drag handle`
  - suppression visible si piste en attente
- Regles :
  - la piste courante ne montre pas d'action de suppression
  - le reorder ne modifie jamais le contexte source

## AlbumCard
- Role : carte de navigation album locale ou online.
- Structure :
  - grande cover
  - titre
  - artiste
- Style :
  - format carre ou quasi carre
  - coins arrondis affirmes
  - usage prefere en rail horizontal
- Mapping code : le pattern de rail est porte par `BrowseAlbumRail`, la carte unitaire est l'item visuel interne.

## ArtistCard
- Role : carte de navigation artiste.
- Structure :
  - image dominante
  - nom artiste
  - meta secondaire optionnelle
- Style :
  - impact visuel plus fort qu'un album
  - image dominante pouvant etre ronde ou encadree selon le layout
- Mapping code : le pattern de rail est porte par `BrowseArtistRail`, la carte unitaire est l'item visuel interne.

## PlaylistCard
- Role : carte de navigation playlist.
- Structure :
  - cover ou mosaique
  - nom
  - metadonnee type nombre de pistes
- Regles :
  - doit etre lisible dans `Home`, `Library` et `Playlists`
  - supporte les etats vide, normale et pinned

## PlayerHero
- Role : bloc hero du full player.
- Structure :
  - artwork
  - titre
  - artiste
  - album ou contexte
- Regles :
  - la cover reste l'element visuel dominant
  - le hero ne doit pas etre noye dans des controles annexes

## SearchBar
- Role : saisie uniforme des recherches.
- Structure :
  - loupe gauche
  - texte ou requete
  - `X` a droite si non vide
- Regles :
  - forme pilule
  - placeholder `Rechercher...`
  - suggestions locales uniquement pendant la saisie

## SegmentedTabs
- Role : onglets locaux de `Search` et filtres de sections similaires.
- Structure :
  - onglets `Titres`, `Albums`, `Artistes`, `Playlists`
- Regles :
  - etat actif fortement visible
  - etat inactif contrastant sans disparaitre
  - nombre limite d'onglets visibles sans scrolling si possible

## SectionHeader
- Role : separer des zones dans un scroll vertical.
- Variantes :
  - standard
  - accent orange
  - avec action secondaire
- Mapping code : `SectionTitle`.

## HeroResumeCard
- Role : carte de reprise sur `Home`.
- Structure :
  - cover ou visuel dominant
  - contexte de reprise
  - CTA `Reprendre`
  - menu `...`

## EmptyStateCard
- Role : etat vide descriptif pour playlists, downloads, recherche offline et ecrans detail sans contenu.
- Structure :
  - titre
  - message
  - action primaire optionnelle
- Regles :
  - le ton reste calme et premium
  - l'action principale reste explicite
- Mapping code : `EmptyStateSurface`.

## Code Mapping
- `android/app/src/main/java/com/aura/music/ui/AuraApp.kt` : `MiniPlayer`, `TrackRow` shell
- `android/app/src/main/java/com/aura/music/ui/player/PlayerViewModel.kt` : etats pilotant `MiniPlayer`, `PlayerHero` et `PlayerQueueRow`
- `android/app/src/main/java/com/aura/music/ui/screens/HomeScreen.kt` : `HeroResumeCard`, `PlaylistCard`
- `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt` : `SearchBar`, `SegmentedTabs`, variantes `TrackRow` Search
- `android/app/src/main/java/com/aura/music/ui/screens/ScreenSharedComponents.kt` : `HeroIdentityCard`, `EmptyStateSurface`, `DownloadStateCard`, `FilterRow`, `SectionTitle`, `BrowseAlbumRail`, `BrowseArtistRail`
- `android/app/src/main/java/com/aura/music/ui/screens/SettingsScreen.kt` : composition de `SettingsCard` et usage de `HeroIdentityCard` + `EmptyStateSurface`
- `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt` : `PlaylistCard`, `PlayerHero`, `PlayerQueueRow`, surfaces detail secondaires
