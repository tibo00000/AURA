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
