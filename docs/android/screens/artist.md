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

## Variantes de source
- `Artist local` :
  - s'ouvre immediatement depuis `Room`
  - affiche les compteurs et pistes locales connus
  - utilise `artists.picture_uri` si disponible
  - si l'image ou le resume manque et que les reglages reseau l'autorisent, Android peut lancer une resolution metadata puis persister le resultat en `Room`
- `Artist online` :
  - s'ouvre a partir d'un ID backend opaque
  - consomme le payload detail backend canonical
  - peut afficher un hero plus riche, un resume et une liste d'albums online
- `Artist hybride` :
  - combine le socle local avec un enrichissement online persiste quand un mapping local <-> backend est connu

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
- placeholder image si aucune image n'est encore resolue

## Regles reseau
- l'ouverture d'un `Artist local` ne doit pas dependre du reseau
- l'enrichissement du hero local ou du resume respecte strictement les reglages de `Settings`
- si les reglages bloquent le reseau, l'ecran reste local-only sans tentative backend
