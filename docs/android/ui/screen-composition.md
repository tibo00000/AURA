# Composition Des Ecrans

## Objectif
Figer la composition visuelle des ecrans Android avant l'implementation detaillee des surfaces finales.

## Ecrans critiques

### Home
- header editorial compact
- hero `Reprendre`
- rail playlists recentes
- rail mixes / recommandations si disponibles
- acces rapide `Downloads`

### Player
- top bar compacte
- `PlayerHero`
- bloc progression
- transport controls
- actions secondaires
- carte contexte source
- preview de `priority queue`

### Search
- SearchBar sticky
- suggestions locales pendant saisie
- bloc `Meilleur resultat`
- bandeau `Dans votre bibliotheque`
- sections online sous le local

### Playlists
- liste avec CTA `Creer une playlist`
- detail avec hero playlist
- rang d'actions
- liste de `TrackRow`

## Ecrans secondaires

### Library
- header simple
- groupes de navigation personnelle
- acces playlists, downloads, settings
- contenu plus sobre que `Home`

### Artist
- hero artiste
- top tracks
- albums
- actions principales limitees

### Album
- hero album
- metadata album
- CTA `Play`
- liste de pistes

### Downloads
- resume d'etat
- groupes `En cours`, `Termines`, `En erreur`
- retry clair

### Settings
- liste de preferences
- sections reseau, sync, diagnostics
- style utilitaire mais coherent

## Regles de composition
- un ecran critique a toujours un hero ou un bloc d'identite fort.
- un ecran secondaire peut etre plus fonctionnel, mais garde la meme palette et les memes surfaces.
- la navigation secondaire ne doit pas ecraser le contenu principal.
- le mini-player ne doit jamais masquer la derniere action utile.

## Responsive
- les hero cards peuvent se compacter sur petits ecrans
- les rails horizontaux gardent des largeurs fixes lisibles
- la partie basse du Player reste prioritaire pour la queue sans cacher les controles

## Code Mapping
- `android/app/src/main/java/com/aura/music/ui/screens/HomeScreen.kt` : composition `Home`
- `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt` : composition `Search`
- `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt` : `Library`, `Playlists` et surfaces secondaires
- `android/app/src/main/java/com/aura/music/ui/AuraApp.kt` : articulation entre composition ecran et shell global
