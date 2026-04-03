# Room Schema

## Objectif
Definir le schema detaille de `Room` pour AURA, avec les cles, les relations et les contraintes qui doivent permettre une utilisation hors ligne robuste.

## Principes generaux
- `Room` est la source de verite de l'etat applicatif AURA.
- Chaque piste connue par AURA recoit un `id` interne stable.
- Les fichiers locaux observes via `MediaStore` sont relies a ces pistes, mais ne remplacent pas les tables metier.
- La `priority queue` n'est pas persistee en base.
- Un seul snapshot de lecture actif est conserve.
- Les statistiques sont stockees a la fois en evenements fins et en agregats.

## Quand une piste online entre dans Room
- Une piste online n'entre pas dans `Room` pour un simple resultat de recherche transitoire.
- Une piste online est inseree ou mise a jour dans `Room` des qu'au moins une des actions suivantes survient :
  - lancement de lecture
  - ajout a une playlist
  - like
  - ouverture d'une page album ou artiste necessitant un cache detaille
  - lancement ou preparation d'un telechargement
- Les resultats de recherche online non actionnes restent ephemeres tant qu'ils ne deviennent pas utiles a l'etat metier.

## Convention des identifiants AURA

Toutes les PK des tables d'entites metier sont des `TEXT` avec un format prefixe deterministe.

| Entite | Format PK | Exemple |
|---|---|---|
| Track (local) | `track:local:{mediaStoreId}` | `track:local:42` |
| Artist | `artist:{slug}` | `artist:daft-punk` |
| Album | `album:{slug_artiste}:{slug_album}` | `album:daft-punk:discovery` |

Le slug est produit par la fonction `normalize` : lowercase, remplacement des caracteres non-lettre/non-chiffre Unicode (`\p{L}\p{N}`) par des tirets, trim. Si le resultat est vide (nom compose uniquement de ponctuation), un hash SHA-256 tronque a 16 caracteres hexadecimaux sert de fallback deterministe.

Les FK (`primary_artist_id`, `album_id`, `track_id`, etc.) referencent directement ces PK.

## Tables

### `artists`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant AURA |
| `name` | `TEXT` | no | INDEX | nom affiche |
| `normalized_name` | `TEXT` | no | INDEX | nom normalise pour recherche |
| `picture_uri` | `TEXT` | yes |  | image locale ou distante |
| `summary` | `TEXT` | yes |  | resume enrichi |
| `created_at` | `INTEGER` | no |  | epoch ms |
| `updated_at` | `INTEGER` | no |  | epoch ms |

Constraints:
- `UNIQUE(normalized_name)` seulement si la fusion canonique d'artistes est jugee fiable. Sinon, simple index.

### `albums`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant AURA |
| `primary_artist_id` | `TEXT` | yes | FK -> `artists.id` | artiste principal |
| `title` | `TEXT` | no | INDEX | titre album |
| `normalized_title` | `TEXT` | no | INDEX | titre normalise |
| `cover_uri` | `TEXT` | yes |  | cover locale ou distante |
| `release_date` | `TEXT` | yes |  | ISO date ou annee |
| `track_count` | `INTEGER` | yes |  | nombre de pistes |
| `created_at` | `INTEGER` | no |  | epoch ms |
| `updated_at` | `INTEGER` | no |  | epoch ms |

Indexes:
- `INDEX(primary_artist_id, normalized_title)`

### `tracks`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant AURA |
| `primary_artist_id` | `TEXT` | yes | FK -> `artists.id` | artiste principal |
| `album_id` | `TEXT` | yes | FK -> `albums.id` | album principal |
| `title` | `TEXT` | no | INDEX | titre |
| `normalized_title` | `TEXT` | no | INDEX | titre normalise |
| `display_artist_name` | `TEXT` | no |  | chaine affichee |
| `display_album_title` | `TEXT` | yes |  | chaine affichee |
| `duration_ms` | `INTEGER` | yes |  | duree connue |
| `cover_uri` | `TEXT` | yes |  | image locale ou distante |
| `canonical_audio_source_type` | `TEXT` | no |  | `local`, `downloaded`, `stream`, `cloud_only` |
| `is_liked` | `INTEGER` | no |  | booleen denormalise |
| `is_downloaded_by_aura` | `INTEGER` | no |  | booleen denormalise |
| `is_explicit` | `INTEGER` | yes |  | booleen |
| `popularity` | `INTEGER` | yes |  | metadata provider ou qdrant |
| `genres_json` | `TEXT` | yes |  | liste json si connue |
| `created_at` | `INTEGER` | no |  | epoch ms |
| `updated_at` | `INTEGER` | no |  | epoch ms |

Indexes:
- `INDEX(primary_artist_id)`
- `INDEX(album_id)`
- `INDEX(normalized_title, primary_artist_id)`

### `track_media_links`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant local |
| `track_id` | `TEXT` | no | UNIQUE FK -> `tracks.id` | un seul fichier local par piste AURA dans ce schema |
| `media_store_id` | `INTEGER` | no | UNIQUE | identifiant MediaStore |
| `content_uri` | `TEXT` | no |  | URI Android |
| `file_size_bytes` | `INTEGER` | yes |  | taille |
| `mime_type` | `TEXT` | yes |  | type MIME |
| `date_modified_epoch_ms` | `INTEGER` | yes |  | date systeme |
| `availability_status` | `TEXT` | no |  | `present`, `missing`, `stale` |
| `last_scanned_at` | `INTEGER` | no |  | dernier passage d'indexation |

### `track_source_links`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant local |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste AURA |
| `usage_type` | `TEXT` | no | INDEX | `search`, `stream`, `download`, `metadata` |
| `provider_name` | `TEXT` | no | INDEX | `deezer`, `tidal_bridge`, autre |
| `provider_track_id` | `TEXT` | no |  | id externe |
| `provider_album_id` | `TEXT` | yes |  | id album externe |
| `provider_artist_id` | `TEXT` | yes |  | id artiste externe |
| `match_score` | `REAL` | yes |  | score de correspondance |
| `is_active_for_usage` | `INTEGER` | no |  | booleen |
| `metadata_json` | `TEXT` | yes |  | payload source utile |
| `created_at` | `INTEGER` | no |  | epoch ms |
| `updated_at` | `INTEGER` | no |  | epoch ms |

Constraints:
- `UNIQUE(track_id, usage_type, provider_name, provider_track_id)`

### `track_likes`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `track_id` | `TEXT` | no | PK FK -> `tracks.id` | piste likee |
| `liked_at` | `INTEGER` | no |  | epoch ms |
| `source_context_type` | `TEXT` | yes |  | contexte du like |
| `source_context_id` | `TEXT` | yes |  | identifiant source |

Rule:
- `tracks.is_liked` doit refleter l'existence ou non d'une ligne dans `track_likes`.

### `playlists`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant AURA |
| `name` | `TEXT` | no | INDEX | nom playlist |
| `cover_uri` | `TEXT` | yes |  | cover ou mosaique calculee |
| `is_pinned` | `INTEGER` | no |  | booleen |
| `created_at` | `INTEGER` | no |  | epoch ms |
| `updated_at` | `INTEGER` | no |  | epoch ms |

### `playlist_items`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant item |
| `playlist_id` | `TEXT` | no | FK -> `playlists.id` | playlist |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste |
| `position` | `INTEGER` | no | INDEX | ordre explicite |
| `added_at` | `INTEGER` | no |  | secours en cas de reordonnancement |
| `added_from_context_type` | `TEXT` | yes |  | origine d'ajout |
| `added_from_context_id` | `TEXT` | yes |  | origine d'ajout |

Constraints:
- `UNIQUE(playlist_id, position)`
- un meme `track_id` peut apparaitre plusieurs fois dans une playlist.

### `playback_snapshots`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | valeur fixe recommandee : `active` |
| `current_track_id` | `TEXT` | yes | FK -> `tracks.id` | piste courante |
| `playback_context_type` | `TEXT` | yes |  | `playlist`, `album`, `artist_mix`, `search_result`, `library`, `single_track` |
| `playback_context_id` | `TEXT` | yes |  | identifiant contexte |
| `playback_context_index` | `INTEGER` | yes |  | position actuelle dans le contexte |
| `position_ms` | `INTEGER` | no |  | progression courante |
| `shuffle_enabled` | `INTEGER` | no |  | booleen |
| `repeat_mode` | `TEXT` | no |  | `off`, `one`, `all` |
| `updated_at` | `INTEGER` | no |  | epoch ms |

Rule:
- une seule ligne active doit exister.

### `history_items`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant item |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste |
| `listening_session_id` | `TEXT` | yes | FK -> `listening_sessions.id` | session source |
| `played_at` | `INTEGER` | no | INDEX | date d'ecoute |
| `completion_percent` | `REAL` | yes |  | progression finale |
| `was_skipped` | `INTEGER` | no |  | booleen |
| `source_context_type` | `TEXT` | yes |  | contexte source |
| `source_context_id` | `TEXT` | yes |  | id contexte |

Retention:
- historique long local
- eligible a la sync cloud

### `download_jobs`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant job |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste cible |
| `provider_name` | `TEXT` | no |  | source ou backend |
| `status` | `TEXT` | no | INDEX | `queued`, `running`, `succeeded`, `failed`, `cancelled` |
| `progress_percent` | `REAL` | yes |  | progression |
| `error_code` | `TEXT` | yes |  | code erreur |
| `error_message` | `TEXT` | yes |  | message erreur |
| `attempt_count` | `INTEGER` | no |  | nombre de tentatives |
| `created_at` | `INTEGER` | no |  | epoch ms |
| `updated_at` | `INTEGER` | no |  | epoch ms |
| `archived_in_cloud_at` | `INTEGER` | yes |  | archive cloud |
| `purge_after_at` | `INTEGER` | yes |  | date de purge locale |

Retention:
- conserver les jobs actifs localement
- autoriser la purge locale des jobs termines apres archivage cloud

### `recent_searches`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant local |
| `query` | `TEXT` | no | UNIQUE | texte cherche |
| `searched_at` | `INTEGER` | no | INDEX | date de derniere utilisation |

Retention:
- liste glissante limitee aux 10 recherches les plus recentes

### `listening_sessions`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant session |
| `started_at` | `INTEGER` | no | INDEX | debut |
| `ended_at` | `INTEGER` | yes |  | fin |
| `source_type` | `TEXT` | yes |  | `playlist`, `album`, `search`, `library`, `single_track` |
| `source_id` | `TEXT` | yes |  | id source |
| `network_type` | `TEXT` | yes |  | `offline`, `wifi`, `cellular`, autre |
| `total_listening_ms` | `INTEGER` | no |  | temps cumule |

### `playback_events`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant evenement |
| `session_id` | `TEXT` | no | FK -> `listening_sessions.id` | session parente |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste |
| `event_type` | `TEXT` | no | INDEX | `play`, `pause`, `resume`, `seek`, `complete`, `skip`, `like` |
| `occurred_at` | `INTEGER` | no | INDEX | date evenement |
| `position_start_ms` | `INTEGER` | yes |  | position initiale |
| `position_end_ms` | `INTEGER` | yes |  | position finale |
| `completion_percent` | `REAL` | yes |  | completion finale si pertinente |
| `skip_reason` | `TEXT` | yes |  | `manual`, `next`, `error`, `autoplay` |
| `liked_during_playback` | `INTEGER` | no |  | booleen |

### `user_track_stats`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant agrege |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste |
| `period_type` | `TEXT` | no | INDEX | `day`, `week`, `month`, `all_time` |
| `period_start` | `TEXT` | no | INDEX | debut de periode ISO |
| `play_count` | `INTEGER` | no |  | nombre de lectures |
| `skip_count` | `INTEGER` | no |  | nombre de skips |
| `complete_play_count` | `INTEGER` | no |  | lectures completes |
| `last_played_at` | `INTEGER` | yes |  | derniere ecoute |
| `total_listening_ms` | `INTEGER` | no |  | temps cumule |
| `average_completion_percent` | `REAL` | yes |  | moyenne |
| `queue_add_count` | `INTEGER` | no |  | ajouts en queue |
| `playlist_add_count` | `INTEGER` | no |  | ajouts en playlist |

Constraints:
- `UNIQUE(track_id, period_type, period_start)`

### `user_settings`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | valeur fixe recommandee : `default` |
| `sync_enabled` | `INTEGER` | no |  | booleen |
| `online_search_enabled` | `INTEGER` | no |  | booleen |
| `online_search_network_policy` | `TEXT` | no |  | `wifi_only`, `any_network`, `disabled` |
| `stats_sync_network_policy` | `TEXT` | no |  | `wifi_only` par defaut |
| `last_sync_at` | `INTEGER` | yes |  | date de sync |

### `sync_outbox`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | identifiant item |
| `entity_type` | `TEXT` | no | INDEX | `playlist`, `playlist_item`, `track_like`, `snapshot`, `stats`, autre |
| `entity_id` | `TEXT` | no | INDEX | identifiant local |
| `operation_type` | `TEXT` | no |  | `insert`, `update`, `delete`, `archive` |
| `payload_json` | `TEXT` | yes |  | charge utile |
| `status` | `TEXT` | no | INDEX | `pending`, `running`, `failed`, `done` |
| `attempt_count` | `INTEGER` | no |  | tentatives |
| `created_at` | `INTEGER` | no |  | epoch ms |
| `updated_at` | `INTEGER` | no |  | epoch ms |

## Tables explicitement absentes
- aucune table de `priority_queue_items`
- aucune table de pile de navigation
- aucune table de niveau de scroll

## Regles de retention
- toutes les donnees metier durables sont conservees localement
- `recent_searches` est limitee a 10 entrees
- `download_jobs` termines sont purgeables localement apres archivage cloud

## Code Mapping
- `android/app/src/main/java/com/aura/music/data/local/LocalEntities.kt` : entities Room correspondant aux tables ci-dessus
- `android/app/src/main/java/com/aura/music/data/local/AuraDaos.kt` : DAOs Room pour les queries et upserts
- `android/app/src/main/java/com/aura/music/data/local/AuraDatabase.kt` : declaration de la base Room avec liste des entities et des DAOs
