# Album Screen Layout

## Objectif
Definir un layout canonique pour `Album` en distinguant les variantes locales et online, avec une hero cover qui peut etre enrichie apres ouverture quand l'album est d'abord connu localement sans image.

## Architecture verticale
- bouton retour
- hero album
- barre d'actions
- liste verticale des pistes

## Hero local
- grande cover carree ou placeholder graphique si `albums.cover_uri` manque
- titre album
- artiste cliquable
- metadonnees locales : nombre de pistes connues, annee si disponible, mention `Dans votre bibliotheque`
- mise a jour reactive si une cover ou une date de sortie est enrichie ensuite

## Hero online
- cover distante prioritaire
- titre album
- artiste principal
- metadonnees online : date de sortie, nombre de pistes, source online

## Differences de comportement
- album local : ouverture immediate depuis `Room`, sans attente reseau
- album online : depend du payload backend detail
- album hybride : tracklist locale + hero enrichi quand un mapping backend est connu

## Actions
- `Play`
- `Shuffle`

## Liste des pistes
- `TrackRow` standard
- ordre canonique local si l'album est local-only
- ordre backend si l'album est online ou si une version canonique enrichie est disponible

## Etats
- `loading_local`
- `local_ready_without_cover`
- `local_ready_with_cover`
- `hybrid_enriching`
- `online_ready`
- `online_error_non_blocking`
