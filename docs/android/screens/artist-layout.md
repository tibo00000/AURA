# Artist Screen Layout

## Objectif
Definir un layout canonique pour `Artist` en distinguant clairement les variantes locales et online, avec un hero capable d'evoluer si une image ou un enrichissement arrive apres l'ouverture de l'ecran.

## Architecture verticale
- bouton retour
- hero artiste
- barre d'actions
- bloc source et etat d'enrichissement si utile
- section `Titres populaires`
- section `Albums`

## Hero local
- grand visuel circulaire ou arrondi
- placeholder graphique accepte tant que `artists.picture_uri` est absent
- nom artiste tres visible
- metadonnee locale concise : nombre de titres, nombre d'albums ou mention `Dans votre bibliotheque`
- apparition reactive de l'image si un enrichissement reseau la complete ensuite

## Hero online
- image distante prioritaire
- nom artiste
- resume court si disponible
- badge ou sous-texte de source online
- pas de degrade de fond colore sur la page artiste online

## Differences de comportement
- hero local : ne bloque jamais l'ouverture ; peut s'enrichir apres coup
- hero online : repose sur le payload backend detail
- hero hybride : conserve la navigation locale mais affiche les metadonnees enrichies si un mapping backend est connu

## Actions
- ouverture album depuis le rail

## Section titres
- liste verticale de `TrackRow`
- contexte local ou online selon la source de l'ecran

## Section albums
- rail horizontal de cartes album
- si l'ecran est local, le rail privilegie les albums locaux
- si l'ecran est online, les sorties sont triees de la plus recente a la plus ancienne
- si l'ecran est online, separer `Albums` et `Singles` via `AlbumSummary.release_type`
- si `release_type` est absent ou `unknown`, `track_count = 1` reste un fallback visuel uniquement
- dans la page artiste online, les cartes album n'affichent pas le nom de l'artiste comme metadonnee secondaire

## Etats
- `loading_local`
- `local_ready_without_image`
- `local_ready_with_image`
- `hybrid_enriching`
- `online_ready`
- `online_error_non_blocking`
