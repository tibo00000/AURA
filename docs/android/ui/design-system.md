# Systeme De Design Et Directives UI

## Objectif
Figer la direction visuelle complete d'AURA avant la poursuite des ecrans produits. Ce document sert de reference pour les tokens, la hierarchie visuelle, la densite et les regles d'adaptation mobile.

## Direction visuelle
- AURA suit une interface sombre, dense mais lisible, avec une sensation premium et musicale.
- Le contraste repose sur des fonds noirs profonds, des surfaces graphite et un accent orange chaud.
- Les ecrans principaux doivent privilegier de grandes masses visuelles, des covers marquees et peu de chrome inutile.
- Les ecrans de contenu doivent paraitre plus editoriaux que techniques.

## Palette canonique

### Fonds
- `DeepBlack`: `#050505`
- `OffBlack`: `#101010`
- `DarkGraphite`: `#1A1A1A`
- `ElevatedGraphite`: `#232323`
- `HairlineDark`: `#2F2F2F`

### Texte
- `TextPrimary`: `#FFFFFF`
- `TextSecondary`: `#C2C2C2`
- `TextMuted`: `#8F8F8F`
- `TextOnAccent`: `#160A00`

### Accent principal
- `BlazeOrange`: `#FF6B00`
- `AmberGlow`: `#FF9E00`
- `LighterOrange`: `#FFB74D`
- `BurntOrange`: `#DB5800`

### Accents secondaires
- `ElectricCyan`: `#00E0FF`
- `DeepViolet`: `#792BEE`
- `RoseSignal`: `#FF5E7A`

### Couleurs semantiques
- `Success`: `#00E676`
- `Warning`: `#FFEA00`
- `Error`: `#FF5252`
- `Info`: `#59B8FF`

## Gradients
- `PrimaryGradient`: `BlazeOrange` vers `AmberGlow`
- `DiscoveryGradient`: `DeepViolet` vers `ElectricCyan`
- `EditorialGradient`: `#1C1C1C` vers `#0B0B0B`
- Les gradients sont reserves aux cartes hero, CTA et categories editoriales.

## Typographie

### Principe
- Les titres doivent etre compacts, contrastes et tres lisibles sur fond sombre.
- Les metadonnees restent plus discretes et ne doivent jamais concurrencer la piste ou la section principale.

### Echelle recommandee
- `Display`: usage exceptionnel pour hero Home ou Player, tres grand, gras
- `HeadlineLarge`: titres hero de sections ou ecrans
- `TitleLarge`: titres de cartes majeures, bloc reprise, meilleur resultat
- `TitleMedium`: titres de section et lignes prioritaires
- `BodyLarge`: piste principale, textes actionnables
- `BodyMedium`: metadonnees de pistes, sous-textes
- `LabelLarge`: boutons et onglets

### Regles
- Les titres de pistes restent en `TextPrimary`.
- Les artistes, albums et informations de contexte restent en `TextSecondary`.
- Les compteurs, aides et captions restent en `TextMuted`.
- Les boutons a accent utilisent `TextOnAccent`.

## Formes
- Rayon standard de surface: `20dp`
- Rayon des cartes hero: `28dp`
- Rayon des boutons pilule: `999dp`
- Rayon des covers album et playlist: `20dp`
- Rayon des mini-cards et chips: `16dp`
- Les avatars artistes sont circulaires.

## Elevation et bordures
- Les surfaces standards utilisent une separation subtile par contraste de fond, pas par ombre lourde.
- Les surfaces elevees utilisent un fond `ElevatedGraphite`.
- Les cartes critiques peuvent avoir une bordure de `1dp` en orange tres discret.
- Le mini-player doit avoir une bordure orange fine et un fond sombre eleve.

## Ombres et lumieres
- Pas d'ombres Material generiques tres visibles.
- Les effets d'accent se font par :
  - contraste de fond
  - bordures fines
  - glow orange discret sous CTA ou hero selectionne
- Le glow reste reserve aux composants prioritaires.

## Espacement

### Tokens
- `SpaceXS`: `4dp`
- `SpaceS`: `8dp`
- `SpaceM`: `12dp`
- `SpaceL`: `16dp`
- `SpaceXL`: `20dp`
- `Space2XL`: `24dp`
- `Space3XL`: `32dp`

### Regles
- Conserver un rythme vertical constant entre header, hero, sections et listes.
- Les rails horizontaux doivent garder un padding lateral stable.
- Toute liste scrollable doit garder un bottom padding suffisant pour ne jamais etre masquee par le mini-player et la navigation basse.

## Composants visuels critiques

### Mini-player
- Surface tres arrondie, flottante, fond `ElevatedGraphite`, bordure orange fine.
- La pochette reste petite mais lisible.
- Le titre est prioritaire sur l'artiste.
- La zone tactile principale ouvre le Player.

### Full player
- Grande cover hero centree.
- Progression, transport et contexte doivent rester visibles sans scrolling avant la queue.
- La partie basse peut scroller pour la queue, pas la zone critique hero.

### Hero cards
- Utiliser une surface forte, soit image dominante, soit gradient editorial.
- Les CTA principaux doivent etre immediatement visibles.
- Le hero ne doit pas ressembler a une simple liste agrandie.

### Cartes Album / Artist / Playlist
- Les cartes d'exploration sont plus visuelles que textuelles.
- Elles doivent pouvoir vivre en rails horizontaux.
- Les cartes `Artist` utilisent une image dominante plus expressive.
- Les cartes `Album` et `Playlist` utilisent des covers ou mosaiques franches.

### TrackRow
- Ligne sobre, lisible, dense.
- La hierarchie est toujours :
  - titre
  - artiste / album
  - actions
- Les actions visibles doivent rester limitees pour ne pas surcharger.

### Search tabs
- Onglets pilule ou segmented control haut contraste.
- L'etat actif est orange ou gradient chaud.
- L'etat inactif reste sombre mais lisible.

## Densite et responsive

### Mobile compact
- Reduire d'abord la taille des artworks avant de sacrifier les controles critiques.
- Garder toujours visibles :
  - titre principal
  - slider player
  - boutons `Previous`, `Play/Pause`, `Next`
  - au moins un apercu de la queue dans le Player

### Mobile standard
- Favoriser des hero cards larges et des rails horizontaux respirants.
- Le bas d'ecran doit rester reserve aux controles persistants.

### Grand ecran
- Ne pas etirer artificiellement les rows.
- Preferer des largeurs de contenu bornees et des gutters plus grands.

## Regles de coherence
- `Home`, `Player`, `Search` et `Playlists` definissent le ton visuel du produit.
- `Library`, `Artist`, `Album`, `Downloads` et `Settings` doivent rester de la meme famille sans surjouer les effets.
- Les etats vides doivent etre calmes, explicites et jamais agressifs.
- Les ecrans de gestion ne doivent pas casser le langage premium etabli par les ecrans musicaux.

## Regles d'implementation
- Toute nouvelle surface Android doit reprendre ces tokens avant d'inventer un style local.
- Toute deviation importante doit etre documentee avant implementation.

## Code Mapping
- `android/app/src/main/java/com/aura/music/ui/AuraApp.kt` : shell visuel global, mini-player shell et structure top-level actuelle
- `android/app/src/main/java/com/aura/music/ui/screens/HomeScreen.kt` : premier point d'application des hero cards et de la hierarchie Home
- `android/app/src/main/java/com/aura/music/ui/screens/SearchScreen.kt` : premier point d'application de la SearchBar, des tabs et des resultats mixtes
- `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt` : surfaces secondaires a realigner apres validation de la DA complete
