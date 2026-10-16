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
- `Exporter (.m3u8)` : génération d'un fichier standard M3U8 avec résolution des chemins physiques Scoped Storage et rapport transparent
- `Supprimer`
- Switch `Télécharger sur l'appareil` (téléchargement par lot automatique ou libération du stockage physique)

## Import / Export Engine
- **Parsing universel** : Détection automatique des encodages (BOM UTF-8, UTF-16, Latin-1, Windows-1252), détection dynamique des délimiteurs CSV (`,`, `;`, `\t`) et mapping d'en-têtes multilingues.
- **Réconciliation Zero-Jank** : Matching insensible aux accents et à la casse via `SearchNormalizer` et `LocalSearchEngine` par lots de 25 titres sur `Dispatchers.Default` avec `StateFlow<ImportProgress>` et barre de progression continue.
- **Sécurité** : Zéro secret hardcodé, tokens chiffrés avec AES-256 GCM adossé au Keystore Android (`MasterKey`), rotation systématique du refresh_token et ré-authentification avec reprise automatique du contexte.

## Playlist Detail - liste des pistes
- `TrackRow` standard (pochette 44dp arrondie, typographie bodyLarge SemiBold)
- Indicateur de synchronisation Cloud/Local (3 états)
- `Like` visible
- `...` pour toutes les actions secondaires
- toucher une ligne lance la lecture a cette position dans le contexte playlist

## Playlist Detail - menu contextuel d'une piste
- `Lire maintenant`
- `Ajouter a la file d'attente`
- `Ajouter a une autre playlist`
- `Voir l'artiste`
- `Voir l'album`
- `Modifier les informations` (pour les pistes locales physiques)
- `Retirer de cette playlist`
- `Telecharger` ou `Supprimer le telechargement` selon l'etat local

## Etats
- vide
- contenu charge
- suppression avec confirmation
- erreur de sync non bloquante

## Code Mapping
- `android/app/src/main/java/com/aura/music/ui/screens/PlaylistDetailScreenNew.kt` : écran de détail moderne de playlist avec switch hors-ligne et matching triplet
- `android/app/src/main/java/com/aura/music/ui/screens/LibraryAndDetailsScreens.kt` : liste playlists, dialogues create/rename/delete, ajout/retrait/reordonnancement local
- `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt` : CRUD playlists locales, items, ordre et lecture du contexte playlist
- `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt` : requetes Room playlists et playlist_items
