# Android Local Persistence

## Objectif
Definir quelles donnees sont observees via `MediaStore`, lesquelles sont persistes dans `Room`, et quelles donnees doivent survivre a un redemarrage de l'application.

## Reference de schema
- Voir `docs/android/room-schema.md` pour les tables detaillees, les colonnes, PK, FK et contraintes.

## Regle de separation
- `MediaStore` est la source de verite des fichiers audio reels presents sur l'appareil.
- `Room` est la source de verite de l'etat applicatif AURA.
- Une meme piste peut avoir des informations venant des deux couches sans que l'une remplace l'autre.
- L'etat purement UI, comme la pile d'ecrans de navigation ou le niveau de scroll d'une liste, ne fait pas partie de ce modele de persistance metier.

## Comment fonctionne MediaStore
- `MediaStore` est l'index systeme Android des medias presents sur l'appareil.
- L'application le lit pour decouvrir les fichiers audio existants.
- Il expose des informations comme l'URI systeme, la duree, le titre, l'album, l'artiste et des metadonnees de base.
- Il ne remplace pas une base applicative, car il ne porte pas l'etat metier propre a AURA.

## Comment fonctionne Room
- `Room` est une couche de persistance locale au-dessus de SQLite.
- L'application y stocke ses objets metier, ses etats utilisateur et ses donnees de reprise.
- `Room` est la bonne couche pour les playlists, les likes, l'etat du player, les jobs et les statistiques d'ecoute.

## Donnees qui doivent vivre dans MediaStore
- presence physique d'un fichier audio local
- URI systeme du fichier
- duree technique du fichier
- titre, album et artiste tels qu'exposes par le systeme
- informations techniques de base utiles a l'import local

## Donnees qui doivent vivre dans Room
- identifiant canonique AURA d'une piste
- relation entre une piste AURA et une entree MediaStore
- playlists et ordre des pistes
- likes et preferes utilisateur
- contexte de lecture, queue prioritaire et snapshot de reprise
- jobs de telechargement
- historique recent
- recherches recentes
- statistiques d'ecoute
- mappings provider et metadonnees enrichies

## Principe de retention locale
- Par defaut, les donnees metier et utilisateur doivent etre conservees.
- Les exceptions explicites sont `recent_searches` et `download_jobs` termines.
- `recent_searches` suit une retention bornee et glissante.
- `download_jobs` termines peuvent etre archives cote cloud puis supprimes du telephone apres retention locale.

## Tables Room principales

### `tracks`
- role : representation canonique locale d'une piste dans AURA
- champs principaux :
  - `id`
  - `title`
  - `artist_name`
  - `album_title`
  - `duration_ms`
  - `cover_uri`
  - `canonical_audio_source_type`
  - `is_liked`
  - `is_downloaded_by_aura`
  - `created_at`
  - `updated_at`

### `track_media_links`
- role : lien entre une piste AURA et une entree observee dans `MediaStore`
- champs principaux :
  - `id`
  - `track_id`
  - `media_store_id`
  - `content_uri`
  - `file_size_bytes`
  - `mime_type`
  - `date_modified`
  - `availability_status`

### `track_source_links`
- role : lien entre une piste AURA et ses sources online par usage
- champs principaux :
  - `id`
  - `track_id`
  - `provider_name`
  - `provider_track_id`
  - `provider_album_id`
  - `provider_artist_id`
  - `provider_match_score`
  - `is_primary_mapping`

### `artists`
- role : entite artiste locale enrichie
- champs principaux :
  - `id`
  - `name`
  - `picture_uri`
  - `summary`
  - `created_at`
  - `updated_at`

### `albums`
- role : entite album locale enrichie
- champs principaux :
  - `id`
  - `title`
  - `artist_id`
  - `cover_uri`
  - `release_date`
  - `track_count`
  - `created_at`
  - `updated_at`

### `playlists`
- role : playlist utilisateur
- champs principaux :
  - `id`
  - `name`
  - `cover_uri`
  - `is_pinned`
  - `created_at`
  - `updated_at`

### `playlist_items`
- role : ordre des pistes dans une playlist
- champs principaux :
  - `id`
  - `playlist_id`
  - `track_id`
  - `position`
  - `added_at`

### `playback_snapshots`
- role : reprise de lecture locale
- champs principaux :
  - `id`
  - `current_track_id`
  - `playback_context_type`
  - `playback_context_id`
  - `playback_context_index`
  - `position_ms`
  - `shuffle_enabled`
  - `repeat_mode`
  - `updated_at`

### `history_items`
- role : historique recent de lecture
- champs principaux :
  - `id`
  - `snapshot_id`
  - `track_id`
  - `played_at`
  - `completion_percent`

### `download_jobs`
- role : suivi local des jobs de telechargement
- champs principaux :
  - `id`
  - `track_id`
  - `provider_name`
  - `status`
  - `progress_percent`
  - `error_code`
  - `error_message`
  - `created_at`
  - `updated_at`
- retention :
  - conserver les jobs actifs localement
  - conserver temporairement les jobs termines localement
  - autoriser une purge locale apres archivage cloud ou apres expiration de la retention locale

### `recent_searches`
- role : requetes recentes et suggestions locales
- champs principaux :
  - `id`
  - `query`
  - `searched_at`
- retention :
  - liste glissante limitee a 10 recherches

### `listening_sessions`
- role : session d'ecoute utilisateur
- champs principaux :
  - `id`
  - `started_at`
  - `ended_at`
  - `source_type`
  - `source_id`
  - `network_type`
  - `total_listening_ms`

### `playback_events`
- role : evenements fins de lecture et de skip
- champs principaux :
  - `id`
  - `session_id`
  - `track_id`
  - `event_type`
  - `position_start_ms`
  - `position_end_ms`
  - `completion_percent`
  - `skip_reason`
  - `liked_during_playback`
  - `created_at`

### `user_track_stats`
- role : agregats locaux par piste
- champs principaux :
  - `track_id`
  - `play_count`
  - `skip_count`
  - `complete_play_count`
  - `last_played_at`
  - `total_listening_ms`
  - `average_completion_percent`

### `user_settings`
- role : preferences applicatives locales
- champs principaux :
  - `id`
  - `sync_enabled`
  - `online_search_enabled`
  - `online_search_network_policy`
  - `stats_sync_network_policy`
  - `last_sync_at`

### `sync_outbox`
- role : operations locales en attente de synchronisation cloud
- champs principaux :
  - `id`
  - `entity_type`
  - `entity_id`
  - `operation_type`
  - `payload_json`
  - `status`
  - `attempt_count`
  - `created_at`
  - `updated_at`

## Donnees a faire survivre a un redemarrage
- playlists
- likes
- playback snapshot
- historique recent
- jobs
- recherches recentes
- statistiques d'ecoute
- settings
- mappings provider et metadonnees enrichies

## Donnees purement reconstituables
- index brut MediaStore, relu depuis le systeme
- suggestions online temporaires
- details de recherche online non valides par l'utilisateur
- etat purement UI de navigation
- pile d'ecrans precedente
- niveau de scroll si aucune exigence explicite de restauration forte n'est retenue
- `priority queue`

## Statistiques d'ecoute recommandees
- nombre d'ecoutes par piste
- nombre de skips par piste
- pourcentage moyen de completion
- temps d'ecoute cumule par piste
- derniere date d'ecoute
- nombre de likes
- sessions d'ecoute
- temps d'ecoute par session
- source de lecture la plus frequente
- nombre d'ajouts a la queue
- nombre d'ajouts en playlist
- nombre d'ecoutes completes
- nombre d'ecoutes abandonnees tres tot
- repartition par contexte source comme playlist, album ou recherche
- repartition par type de reseau si utile a l'analyse produit

## Filtrage MediaStore

La query MediaStore applique deux filtres :
- `IS_MUSIC != 0` : exclut les sonneries, alarmes et notifications systeme
- `DURATION >= 30000` : exclut les fichiers audio courts (vocaux WhatsApp, sons de notification) inferieurs a 30 secondes

Aucune limite arbitraire n'est appliquee au nombre de fichiers retournes.

## Code Mapping
- `android/app/src/main/java/com/aura/music/data/local/LocalEntities.kt` : entities Room pour toutes les tables (tracks, artists, albums, playlists, snapshots, settings, etc.)
- `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt` : DAOs Room pour les operations de base
- `android/app/src/main/java/com/aura/music/data/local/AuraDatabase.kt` : singleton Room database, version 1
- `android/app/src/main/java/com/aura/music/data/media/MediaStoreAudioDataSource.kt` : lecture des medias locaux via MediaStore, filtre duree >= 30s
- `android/app/src/main/java/com/aura/music/data/repository/LocalLibraryRepository.kt` : orchestration locale Room + MediaStore, import, recherche, playlists, snapshot, generation d'ID
