# Systeme De Design Et Directives UI

## Objectif
Ce document definit les fondations visuelles d'AURA. Tout nouvel ecran Android doit utiliser ces jetons afin de garder une interface coherente.

## Palette sombre
- `DeepBlack` : `#000000`
- `OffBlack` : `#121212`
- `DarkGraphite` : `#1E1E1E`
- `TextPrimary` : `#FFFFFF`
- `SteelGray` : `#B3B3B3`

## Accents
- `BlazeOrange` : `#FF6B00`
- `AmberGlow` : `#FF9E00`
- `LighterOrange` : `#FFB74D`

## Gradients
- `PrimaryGradient` : `BlazeOrange` vers `AmberGlow`
- `DiscoveryGradient` : `#792BEE` vers `#00E0FF`

## Couleurs semantiques
- `Error` : `#FF5252`
- `Success` : `#00E676`
- `Warning` : `#FFEA00`

## Typographie
- Les grands headers sont en gras, tres visibles, contrastes sur fond sombre.
- Les titres de section sont gras et compacts.
- Les titres de pistes restent blancs, les metadonnees secondaires en gris.

## Formes
- Boutons d'action et recherche : forme pilule.
- Cartes et pochettes : rectangle ou carre a coins arrondis.
- Avatars artistes : cercle parfait.
- Mini-player : carte flottante tres arrondie avec bordure orange fine.

## Regles d'espacement
- Prevoir un bottom padding genereux dans chaque liste pour ne jamais masquer le dernier element sous le mini-player.
- Garder des espacements constants entre header, barre de recherche, sections et listes.
