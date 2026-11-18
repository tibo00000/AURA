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
- Le menu `Bilan de synchronisation` ouvre explicitement le résultat d’actualisation et la disponibilité cloud. Aucun bilan, compteur cloud ou date d’actualisation n’est affiché automatiquement dans l’en-tête. Une actualisation confirmée affiche seulement un retour bref ; les correspondances non résolues restent accessibles par l’action de ce retour et les options de synchronisation. La fiche vérifie le cloud après actualisation, à l’ouverture et au retour du réseau, puis périodiquement tant qu’une acquisition attend ou travaille. Une erreur conserve le dernier bilan confirmé. `Réessayer la lecture` consulte le bilan sans créer de demande.
- Avant « Rendre disponible dans le cloud » ou « Réessayer », Aura synchronise les éditions locales en attente de cette playlist. Si elles n’ont pas pu être confirmées, la fiche affiche l’erreur et la consultation du bilan reste indépendante. Les modifications d’une autre playlist n’entraînent pas de synchronisation supplémentaire.
- Le rejeu d’une actualisation acquitte sa demande locale et conserve le bilan le plus récent de la même source, même si un autre appareil a actualisé la playlist entre-temps.
- La suppression d’une playlist retire ses occurrences, son association, ses bilans et ses exclusions. Les jobs et les fichiers audio partagés restent rattachés aux titres. Une playlist recréée retrouve une nouvelle identité et peut être associée de nouveau à sa source.
- Un import depuis une playlist Spotify/Deezer conserve la référence de playlist et les identifiants catalogue. La création réutilise l’actualisation validée et ses intentions persistantes, sans déclencher les anciennes routes de téléchargement automatique. Une erreur après création ouvre la playlist créée avec un message explicite, sans inciter à la recréer.
- Les correspondances de métadonnées restent des propositions. Pour une ancienne playlist, les propositions uniques peuvent être confirmées ensemble, avec titre, artiste et album visibles. Une égalité de titre et d’artiste ne fusionne pas automatiquement deux enregistrements. Les correspondances exactes d’identifiants fournisseur sont réutilisées sans cette confirmation.
- Une ligne affiche le nuage uniquement pour un fichier cloud confirmé. Une intention sans job affiche une horloge statique et ne simule pas un téléchargement actif. Les indicateurs de file et de progression exigent un job confirmé ; un fichier local reste prioritaire. Les nouveaux jobs signalés par le bilan sont récupérés dans le gestionnaire de téléchargements existant, sans transfert local automatique ni rafraîchissement répété pour un état inchangé.
- Les actions liées à la source, aux fichiers cloud et aux correspondances sont regroupées dans les `Options de synchronisation`. Les actions du menu playlist et les déplacements du menu d’un titre comportent des icônes cohérentes avec les composants existants. Les identifiants catalogue exacts sont réutilisés automatiquement ; une simple égalité de titre et d’artiste reste une proposition explicite.

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
## Cohérence Android après acquisition

Le résumé d'import distingue les références catalogue reconnues, les titres avec copies possibles dans la bibliothèque et les titres sans correspondance. Une copie possible n'est ni un nouveau titre certain ni une association automatique. Les compteurs sont des nombres courts, séparés de leurs libellés et lisibles à grande taille de police.

Les choix de correspondance utilisent des pochettes asynchrones, le titre, l'artiste, l'album et l'état local/cloud confirmé. Le choix individuel filtre les copies sur le titre et l'artiste normalisés ; les occurrences volontaires restent distinctes. Les propositions non ambiguës peuvent être validées en une action explicite. Les métadonnées seules ne fusionnent jamais les identifiants.

Une acquisition refusée faute de source audio garde un pictogramme et le libellé « Source audio indisponible » dans la liste. Son état ne devient ni disponible ni téléchargement actif. Les fichiers locaux ou cloud déjà confirmés ont priorité sur ce refus.

La fin d'un job lié à une playlist actualise l'inventaire cloud partagé et invalide l'index local de recherche. L'inventaire n'est publié qu'après la réconciliation Room réussie. Les écrans bibliothèque et recherche observent ce cache ; aucune requête réseau n'est ajoutée pendant la frappe. Une réponse identique conserve l'index et évite les écritures SQLite inutiles. Un calcul d'index invalidé pendant sa construction ne peut pas remettre en cache son ancien résultat.
