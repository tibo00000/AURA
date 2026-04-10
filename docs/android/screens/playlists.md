# Playlists Screens

## Role
Documenter la liste des playlists et leur detail avec une forte lisibilite des actions d'edition.

## Playlist List - structure generale
- header avec titre `Playlists`
- action primaire `Creer une playlist`
- liste verticale des playlists utilisateur

## Playlist List - ligne de playlist
- cover a gauche
- nom de playlist
- metadonnee secondaire type nombre de pistes
- bouton `...` optionnel si des actions rapides sont exposees depuis la liste
- toucher la ligne ouvre `Playlist Detail`

## Playlist List - etats
- etat vide avec message explicite
- bouton primaire visible meme en etat vide
- etat charge avec tri simple et stable

## Playlist Detail - structure generale
- header hero avec cover, nom et metadonnees
- rangee d'actions principales
- liste verticale des pistes

## Playlist Detail - header
- grande cover ou mosaique
- nom de playlist
- nombre de pistes
- information secondaire eventuelle comme date de mise a jour

## Playlist Detail - actions principales
- `Play`
- `Shuffle`
- `Renommer`
- `Supprimer`

## Playlist Detail - liste des pistes
- `TrackRow` standard
- `Like` visible
- `...` pour toutes les actions secondaires
- toucher une ligne lance la lecture a cette position dans le contexte playlist

## Playlist Detail - menu contextuel d'une piste
- `Lire maintenant`
- `Ajouter a la file d'attente`
- `Ajouter a une autre playlist`
- `Voir l'artiste`
- `Voir l'album`
- `Retirer de cette playlist`
- `Telecharger` ou `Supprimer le telechargement` selon l'etat local

## Etats
- vide
- contenu charge
- suppression avec confirmation
- erreur de sync non bloquante

## Code Mapping
- `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt` : liste playlists, detail playlist, dialogues create/rename/delete, ajout/retrait/reordonnancement local
- `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt` : CRUD playlists locales, items, ordre et lecture du contexte playlist
- `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt` : requetes Room playlists et playlist_items
