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
