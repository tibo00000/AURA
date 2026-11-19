# Postgres Schema

## Confirmation d'une copie déjà associée

La migration `20261119005133_playlist_confirmed_occurrence_reuse.sql` remplace
uniquement `aura_commit_playlist_refresh`. Sous le verrou existant de la playlist,
une réutilisation explicitement confirmée d'une occurrence déjà associée crée
une autre occurrence du même `track_id`, à la fin, avec la nouvelle référence.
La première occurrence, ses métadonnées, son ordre et les exclusions restent
intacts. La détection d'identité audio ambiguë conserve son refus d'acquisition
automatique. Aucun lien n'est inféré à partir du titre/artiste. Ni table ni
permission ni migration Room ne sont modifiées. La suppression manuelle et
l'idempotence gardent la priorité sur une confirmation concurrente.


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

## Acces au catalogue server-authoritative

- `artists`, `albums`, `tracks` et `track_source_links` ont RLS active.
- Aucun role client (`anon`, `authenticated`) ne recoit de privilege direct sur ces tables et aucune politique RLS publique n'est definie.
- Le catalogue reste accessible aux clients uniquement par les endpoints FastAPI. Le backend utilise sa cle `service_role` exclusivement cote serveur pour les traitements de catalogue.
- Toute evolution du schema Supabase est ajoutee dans `supabase/migrations/` sous forme de fichier SQL horodate. Son application distante est soumise a une autorisation explicite puis a une requete de controle.

## Tables

### Isolation des données personnelles — SEC-002

**Appliqué en production le2026-10-18 après autorisation nominative** :
`supabase/migrations/20261017200822_secure_user_data_isolation.sql`.
Historique Supabase `20261018203759 / secure_user_data_isolation`, wrapper d'intégrité et
postcheck conformes ; exécution dans `docs/ops/security-lot2-deployment.md`.
Architecture, matrice et limites : `docs/server/security-lot2-isolation.md`.

- Les routes privées REST/sync utilisent un client lié au JWT vérifié ; `authenticated`
  reçoit les droits CRUD avec RLS propriétaire et garde restrictive, `anon` aucun droit.
- `playlist_items` est autorisée par sa playlist possédée ; événements et historique
  vérifient également la session propriétaire. Les FK composites ci-dessous protègent
  aussi les écritures serveur privilégiées.
- `download_jobs` demeure serveur uniquement. `user_track_stats` et `recent_searches`,
  lorsqu'elles existent, ne sont accessibles directement qu'en lecture propriétaire.
- Le catalogue conserve ses privilèges serveur. Les anciens `FOR ALL USING` ne sont pas
  considérés dépourvus de contrôle INSERT : PostgreSQL réutilise USING comme WITH CHECK
  en l'absence de clause explicite. Le problème confirmé était son contournement par service_role.

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
- `UNIQUE(playlist_id, position) DEFERRABLE INITIALLY IMMEDIATE` après SEC-002.
- La RPC `aura_mutate_playlist_items` est `SECURITY INVOKER`, verrouille la playlist
  possédée, valide tous les IDs puis insère/supprime/réordonne dans une transaction.
  La contrainte est rétablie avant retour ; une collision annule tous les déplacements.
  Plusieurs occurrences d'une même piste restent autorisées.
- SEC-002.2 ajoute `rest_insert` (sans décalage), `rest_delete_track` (toutes les occurrences,
  sans compaction) et `rest_patch` (métadonnées, null ignoré). Mutation et touch du parent
  partagent le verrou propriétaire et la transaction. Les actions REST et items batch calculent
  `greatest(clock_timestamp(), parent.updated_at + interval '1 microsecond')` après verrou.
  Une erreur SQL du touch annule aussi les écritures enfants. Migration et backend actifs en
  production depuis SEC-002.4-RUN ; preuves dans `docs/ops/security-lot2-deployment.md`.

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

Après SEC-002, `history_items(user_id,listening_session_id)` référence
`listening_sessions(user_id,id)`. Session facultative ; sa suppression met uniquement
`listening_session_id` à NULL et conserve le propriétaire/historique (PostgreSQL >=15).

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

Après SEC-002 : `listening_sessions` porte aussi `UNIQUE(user_id,id)` et
`playback_events(user_id,session_id)` la référence avec `ON DELETE CASCADE`.
Une session étrangère ne peut pas recevoir les événements du compte courant.

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

### `processed_operations` et `processed_batches`

Après SEC-002, PK respectives `(user_id,operation_id)` et `(user_id,batch_id)` ; IDs
opaques conservés, dates `processed_at` conservées. Les conflits d'upsert ciblent la même
clé composite avec `ignore_duplicates`. A et B peuvent réutiliser le même ID de marqueur
sans interférence. Aucune suppression de marqueur existant n'est prévue.

## Tables explicitement absentes
- aucune replication brute de `MediaStore`
- aucune table de pile de navigation
- aucune table de niveau de scroll
- aucune table de `priority_queue_items`

## Politique de sync
- tout le durable est synchronisable
- les statistiques et leurs evenements ne montent que si le reseau autorise la sync en Wi-Fi
- les jobs termines peuvent etre archives en cloud puis purges localement

## Transition multi-artistes

`20261119161443_multi_artist_credits_and_existing_copy_reuse.sql` ajoute `tracks.artists` JSONB (tableau) et reprend le principal connu. Elle remplace la RPC d'actualisation en conservant signature, RLS, auth.uid() et droits. Les nouvelles métadonnées restent aussi dans `playlist_items.source_metadata` et les intentions persistantes existantes. Les crédits de fichiers privés restent dans leurs métadonnées personnelles, sans réécriture du catalogue public depuis les fichiers utilisateur.
