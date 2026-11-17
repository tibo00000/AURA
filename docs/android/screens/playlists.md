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
- Le menu permet d'associer une playlist existante à une référence Spotify ou Deezer, de changer la source et de la retirer. La référence et sa révision sont répliquées par Aura sur les autres appareils. Le contenu et l'ordre de la playlist sont conservés. Les jetons Spotify restent sur l'appareil.
- `Actualiser depuis Spotify/Deezer` ajoute les nouveautés et présente le résultat confirmé ainsi que les correspondances à vérifier. `Rendre disponible dans le cloud` lance les intentions pour les titres de toute playlist et permet de consulter leur bilan, de réessayer et de choisir une version. Le réglage de copie locale est indépendant.
- La lecture fournisseur vérifie le total et la continuité des pages avant tout envoi. Spotify contrôle aussi la révision de la playlist et l’offset déclaré par chaque page. Deezer compare deux inventaires successifs. Une lecture interrompue ou incohérente laisse la date de dernière réussite et le contenu Aura en place.
- Le menu `Consulter le bilan cloud` ouvre le bilan d’une playlist synchronisée. Il se recharge après un résultat d’actualisation, à l’ouverture de la fiche synchronisée et au retour du réseau. La fiche le vérifie périodiquement tant qu’une acquisition est en cours ou que le bilan est ouvert. Une erreur de lecture ou une coupure réseau conserve le dernier bilan confirmé, quand il existe. `Réessayer la lecture` consulte le bilan sans créer de demande de téléchargement.
- Avant « Rendre disponible dans le cloud » ou « Réessayer », Aura synchronise les éditions locales en attente de cette playlist. Si elles n’ont pas pu être confirmées, la fiche affiche l’erreur et la consultation du bilan reste indépendante. Les modifications d’une autre playlist n’entraînent pas de synchronisation supplémentaire.
- Le rejeu d’une actualisation acquitte sa demande locale et conserve le bilan le plus récent de la même source, même si un autre appareil a actualisé la playlist entre-temps.
- La suppression d’une playlist retire ses occurrences, son association, ses bilans et ses exclusions. Les jobs et les fichiers audio partagés restent rattachés aux titres. Une playlist recréée retrouve une nouvelle identité et peut être associée de nouveau à sa source.

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
- `Monter dans la playlist` et `Descendre dans la playlist` ciblent une occurrence et synchronisent le déplacement vers les autres appareils.
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
