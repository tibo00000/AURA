# Artist Screen

## Role
Afficher un artiste et proposer les actions de lecture et d'exploration associees.

## Structure generale
- header hero artiste
- bloc d'actions
- section titres populaires
- section albums

## Header hero
- image artiste large
- nom de l'artiste
- courte metadonnee secondaire si disponible

## Actions principales
- `Lire`
- `Lancer un mix` si la fonction existe

## Titres populaires
- liste verticale de `TrackRow`
- `Like` visible
- `...` visible
- toucher la ligne lance la lecture

## Albums
- rail horizontal de cartes album
- toucher une carte ouvre `Album`

## Etats
- local only si l'artiste est connu depuis la bibliotheque
- enrichi online si le reseau est disponible
- erreur non bloquante si la source externe ne repond pas
