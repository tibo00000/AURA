# Album Screen

## Role
Afficher un album, ses metadonnees et la liste ordonnee de ses pistes.

## Structure generale
- header hero album
- rangee d'actions principales
- liste verticale des pistes

## Header hero
- grande cover album
- titre album
- artiste
- informations secondaires comme annee ou nombre de pistes si connues

## Variantes de source
- `Album local` :
  - s'ouvre immediatement depuis `Room`
  - affiche les pistes locales connues dans l'ordre disponible
  - utilise `albums.cover_uri` ou la meilleure cover locale disponible
  - si la cover ou les metadonnees secondaires manquent et que les reglages reseau l'autorisent, Android peut lancer une resolution metadata puis persister le resultat en `Room`
- `Album online` :
  - s'ouvre a partir d'un ID backend opaque
  - consomme le payload detail backend canonical
  - peut fournir une tracklist online canonique, une date de sortie et une cover distante
  - consomme `release_type` si present pour distinguer album, single, EP ou compilation
- `Album hybride` :
  - combine une tracklist locale avec des metadonnees hero enrichies quand un mapping local <-> backend est connu

## Actions principales
- `Play`
- `Shuffle`

## Liste des pistes
- ordre canonique de l'album
- `TrackRow` standard
- `Like` visible
- `...` visible
- toucher une ligne lance la lecture a cette position dans le contexte album

## Etats
- album local
- album enrichi online
- erreur si metadonnees indisponibles
- placeholder cover si aucune image n'est encore resolue

## Regles reseau
- l'ouverture d'un `Album local` ne doit pas dependre du reseau
- l'enrichissement de la cover ou des metadonnees secondaires respecte strictement les reglages de `Settings`
- si les reglages bloquent le reseau, l'ecran reste local-only sans tentative backend
