# Postgres Schema

## Objectif
Definir le schema detaille de `Supabase/Postgres` pour les donnees transactionnelles et synchronisables d'AURA.

## Relation avec le schema local
- Voir `docs/android/room-schema.md` pour le schema `Room`.
- Le cloud ne reproduit pas les tables purement locales ou l'etat UI.

## Principes generaux
- Postgres stocke les donnees durables synchronisables.
- `MediaStore` n'est pas replique tel quel dans le cloud.
- L'etat purement UI n'est pas synchronise.
- Les `download_jobs` termines peuvent etre archives cote cloud meme s'ils sont purges du telephone.
- Les statistiques sont synchronisees en Wi-Fi uniquement.

## Strategie d'identite canonique
- `profiles.id` reste un `UUID` car il suit l'identite d'authentification.
- Toutes les autres entites AURA exposees ou synchronisees utilisent des identifiants `TEXT` opaques.
- Les entites de catalogue online server-authoritative (`artists`, `albums`, `tracks`) recoivent des IDs backend et ne reutilisent pas les IDs locaux derives de `MediaStore`.
- Les identifiants locaux Android comme `track:local:*`, `artist:*` ou `album:*` ne deviennent pas des PK cloud du catalogue online.
- La correspondance local <-> online passe par les tables de mapping et par le matching de metadonnees, pas par un partage force de PK.
- Les entites user-scoped synchronisables (`playlists`, `playlist_items`, `history_items`, `listening_sessions`, `playback_events`, `download_jobs`) peuvent conserver un ID texte opaque deja genere cote client ou cote serveur.

## Tables

### `profiles`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `UUID` | no | PK | meme identifiant que l'utilisateur auth si auth active |
| `display_name` | `TEXT` | yes |  | nom utilisateur |
| `avatar_uri` | `TEXT` | yes |  | avatar |
| `created_at` | `TIMESTAMPTZ` | no |  | creation |
| `updated_at` | `TIMESTAMPTZ` | no |  | maj |

### `user_settings`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `user_id` | `UUID` | no | PK FK -> `profiles.id` | utilisateur |
| `sync_enabled` | `BOOLEAN` | no |  | sync active |
| `online_search_enabled` | `BOOLEAN` | no |  | recherche online |
| `online_search_network_policy` | `TEXT` | no |  | `wifi_only`, `any_network`, `disabled` |
| `stats_sync_network_policy` | `TEXT` | no |  | `wifi_only` |
| `updated_at` | `TIMESTAMPTZ` | no |  | maj |

### `artists`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | id AURA opaque |
| `name` | `TEXT` | no | INDEX | nom |
| `normalized_name` | `TEXT` | no | INDEX | recherche |
| `picture_uri` | `TEXT` | yes |  | image |
| `summary` | `TEXT` | yes |  | resume |
| `created_at` | `TIMESTAMPTZ` | no |  | creation |
| `updated_at` | `TIMESTAMPTZ` | no |  | maj |

### `albums`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | id AURA opaque |
| `primary_artist_id` | `TEXT` | yes | FK -> `artists.id` | artiste principal |
| `title` | `TEXT` | no | INDEX | titre |
| `normalized_title` | `TEXT` | no | INDEX | recherche |
| `cover_uri` | `TEXT` | yes |  | cover |
| `release_date` | `DATE` | yes |  | date ou annee connue |
| `track_count` | `INTEGER` | yes |  | nombre de pistes |
| `created_at` | `TIMESTAMPTZ` | no |  | creation |
| `updated_at` | `TIMESTAMPTZ` | no |  | maj |

### `tracks`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | id AURA opaque |
| `primary_artist_id` | `TEXT` | yes | FK -> `artists.id` | artiste principal |
| `album_id` | `TEXT` | yes | FK -> `albums.id` | album principal |
| `title` | `TEXT` | no | INDEX | titre |
| `normalized_title` | `TEXT` | no | INDEX | recherche |
| `display_artist_name` | `TEXT` | no |  | affichage |
| `display_album_title` | `TEXT` | yes |  | affichage |
| `duration_ms` | `INTEGER` | yes |  | duree |
| `cover_uri` | `TEXT` | yes |  | cover |
| `is_explicit` | `BOOLEAN` | yes |  | explicite |
| `popularity` | `INTEGER` | yes |  | score metadata |
| `genres_json` | `JSONB` | yes |  | liste de genres |
| `created_at` | `TIMESTAMPTZ` | no |  | creation |
| `updated_at` | `TIMESTAMPTZ` | no |  | maj |

### `track_source_links`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | id mapping opaque |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste AURA |
| `usage_type` | `TEXT` | no | INDEX | `search`, `stream`, `download`, `metadata` |
| `provider_name` | `TEXT` | no | INDEX | nom provider |
| `provider_track_id` | `TEXT` | no |  | id piste externe |
| `provider_album_id` | `TEXT` | yes |  | id album externe |
| `provider_artist_id` | `TEXT` | yes |  | id artiste externe |
| `match_score` | `NUMERIC` | yes |  | score |
| `is_active_for_usage` | `BOOLEAN` | no |  | actif |
| `metadata_json` | `JSONB` | yes |  | payload source |
| `created_at` | `TIMESTAMPTZ` | no |  | creation |
| `updated_at` | `TIMESTAMPTZ` | no |  | maj |

Constraints:
- `UNIQUE(track_id, usage_type, provider_name, provider_track_id)`

### `playlists`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | playlist |
| `user_id` | `UUID` | no | FK -> `profiles.id` | proprietaire |
| `name` | `TEXT` | no | INDEX | nom |
| `cover_uri` | `TEXT` | yes |  | cover |
| `is_pinned` | `BOOLEAN` | no |  | epingle |
| `created_at` | `TIMESTAMPTZ` | no |  | creation |
| `updated_at` | `TIMESTAMPTZ` | no |  | maj |

### `playlist_items`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | item |
| `playlist_id` | `TEXT` | no | FK -> `playlists.id` | playlist |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste |
| `position` | `INTEGER` | no | INDEX | ordre |
| `added_at` | `TIMESTAMPTZ` | no |  | date ajout |
| `added_from_context_type` | `TEXT` | yes |  | contexte |
| `added_from_context_id` | `TEXT` | yes |  | contexte |

Constraints:
- `UNIQUE(playlist_id, position)`

### `likes`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `user_id` | `UUID` | no | PK FK -> `profiles.id` | utilisateur |
| `track_id` | `TEXT` | no | PK FK -> `tracks.id` | piste |
| `liked_at` | `TIMESTAMPTZ` | no |  | date |
| `source_context_type` | `TEXT` | yes |  | contexte |
| `source_context_id` | `TEXT` | yes |  | contexte |

### `playback_snapshots`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `user_id` | `UUID` | no | PK FK -> `profiles.id` | utilisateur |
| `current_track_id` | `TEXT` | yes | FK -> `tracks.id` | piste courante |
| `playback_context_type` | `TEXT` | yes |  | contexte |
| `playback_context_id` | `TEXT` | yes |  | id contexte |
| `playback_context_index` | `INTEGER` | yes |  | position |
| `position_ms` | `INTEGER` | no |  | progression |
| `shuffle_enabled` | `BOOLEAN` | no |  | shuffle |
| `repeat_mode` | `TEXT` | no |  | repeat |
| `updated_at` | `TIMESTAMPTZ` | no |  | maj |

### `history_items`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | item historique |
| `user_id` | `UUID` | no | FK -> `profiles.id` | utilisateur |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste |
| `listening_session_id` | `TEXT` | yes | FK -> `listening_sessions.id` | session |
| `played_at` | `TIMESTAMPTZ` | no | INDEX | date |
| `completion_percent` | `NUMERIC` | yes |  | completion |
| `was_skipped` | `BOOLEAN` | no |  | skip |
| `source_context_type` | `TEXT` | yes |  | contexte |
| `source_context_id` | `TEXT` | yes |  | contexte |

### `download_jobs`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | job |
| `user_id` | `UUID` | no | FK -> `profiles.id` | utilisateur |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste |
| `provider_name` | `TEXT` | no |  | source |
| `status` | `TEXT` | no | INDEX | etat |
| `progress_percent` | `NUMERIC` | yes |  | progression |
| `error_code` | `TEXT` | yes |  | code |
| `error_message` | `TEXT` | yes |  | message |
| `attempt_count` | `INTEGER` | no |  | tentatives |
| `created_at` | `TIMESTAMPTZ` | no |  | creation |
| `updated_at` | `TIMESTAMPTZ` | no |  | maj |
| `archived_at` | `TIMESTAMPTZ` | yes |  | archive |

### `listening_sessions`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | session |
| `user_id` | `UUID` | no | FK -> `profiles.id` | utilisateur |
| `started_at` | `TIMESTAMPTZ` | no | INDEX | debut |
| `ended_at` | `TIMESTAMPTZ` | yes |  | fin |
| `source_type` | `TEXT` | yes |  | contexte |
| `source_id` | `TEXT` | yes |  | contexte |
| `device_type` | `TEXT` | yes |  | mobile, autre |
| `network_type` | `TEXT` | yes |  | offline, wifi, cellular |
| `total_listening_ms` | `INTEGER` | no |  | temps |

### `playback_events`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | evenement |
| `session_id` | `TEXT` | no | FK -> `listening_sessions.id` | session |
| `user_id` | `UUID` | no | FK -> `profiles.id` | utilisateur |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste |
| `event_type` | `TEXT` | no | INDEX | `play`, `pause`, `resume`, `seek`, `complete`, `skip`, `like` |
| `occurred_at` | `TIMESTAMPTZ` | no | INDEX | date |
| `position_start_ms` | `INTEGER` | yes |  | position debut |
| `position_end_ms` | `INTEGER` | yes |  | position fin |
| `completion_percent` | `NUMERIC` | yes |  | completion |
| `skip_reason` | `TEXT` | yes |  | `manual`, `next`, `error`, `autoplay` |
| `liked_during_playback` | `BOOLEAN` | no |  | booleen |

### `user_track_stats`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | agregat |
| `user_id` | `UUID` | no | FK -> `profiles.id` | utilisateur |
| `track_id` | `TEXT` | no | FK -> `tracks.id` | piste |
| `period_type` | `TEXT` | no | INDEX | `day`, `week`, `month`, `all_time` |
| `period_start` | `DATE` | no | INDEX | debut de periode |
| `play_count` | `INTEGER` | no |  | lectures |
| `skip_count` | `INTEGER` | no |  | skips |
| `complete_play_count` | `INTEGER` | no |  | completes |
| `last_played_at` | `TIMESTAMPTZ` | yes |  | derniere ecoute |
| `total_listening_ms` | `INTEGER` | no |  | temps |
| `average_completion_percent` | `NUMERIC` | yes |  | moyenne |
| `queue_add_count` | `INTEGER` | no |  | ajouts queue |
| `playlist_add_count` | `INTEGER` | no |  | ajouts playlist |
| `is_liked` | `BOOLEAN` | no |  | etat derive |

Constraints:
- `UNIQUE(user_id, track_id, period_type, period_start)`

### `recent_searches`

| Column | Type | Null | Key | Notes |
|---|---|---|---|---|
| `id` | `TEXT` | no | PK | recherche |
| `user_id` | `UUID` | no | FK -> `profiles.id` | utilisateur |
| `query` | `TEXT` | no |  | requete |
| `searched_at` | `TIMESTAMPTZ` | no | INDEX | date |

Retention:
- optionnelle
- borne glissante de 10 recherches si active

## Tables explicitement absentes
- aucune replication brute de `MediaStore`
- aucune table de pile de navigation
- aucune table de niveau de scroll
- aucune table de `priority_queue_items`

## Politique de sync
- tout le durable est synchronisable
- les statistiques et leurs evenements ne montent que si le reseau autorise la sync en Wi-Fi
- les jobs termines peuvent etre archives en cloud puis purges localement
