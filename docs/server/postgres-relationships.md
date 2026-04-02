# Postgres Relationships

## Objectif
Donner une vue relationnelle des tables synchronisables cote `Supabase / Postgres`.

## Principes de lecture
- Ce diagramme couvre les donnees cloud durables exposees ou manipulees par le backend.
- Le cloud ne replique pas `MediaStore`, la navigation UI, le scroll ni la `priority queue`.
- Les donnees restent optionnelles a l'echelle produit : elles ne sont utiles que si la sync ou le compte cloud est actif.

```mermaid
erDiagram
    PROFILES {
      UUID id PK
      TEXT display_name
    }

    USER_SETTINGS {
      UUID user_id PK
      BOOLEAN sync_enabled
      TEXT online_search_network_policy
    }

    ARTISTS {
      UUID id PK
      TEXT name
    }

    ALBUMS {
      UUID id PK
      UUID primary_artist_id FK
      TEXT title
    }

    TRACKS {
      UUID id PK
      UUID primary_artist_id FK
      UUID album_id FK
      TEXT title
    }

    TRACK_SOURCE_LINKS {
      UUID id PK
      UUID track_id FK
      TEXT usage_type
      TEXT provider_name
    }

    PLAYLISTS {
      UUID id PK
      UUID user_id FK
      TEXT name
    }

    PLAYLIST_ITEMS {
      UUID id PK
      UUID playlist_id FK
      UUID track_id FK
      INTEGER position
    }

    LIKES {
      UUID user_id PK
      UUID track_id PK
      TIMESTAMPTZ liked_at
    }

    PLAYBACK_SNAPSHOTS {
      UUID user_id PK
      UUID current_track_id FK
      INTEGER position_ms
    }

    HISTORY_ITEMS {
      UUID id PK
      UUID user_id FK
      UUID track_id FK
      UUID listening_session_id FK
    }

    LISTENING_SESSIONS {
      UUID id PK
      UUID user_id FK
      TIMESTAMPTZ started_at
    }

    PLAYBACK_EVENTS {
      UUID id PK
      UUID session_id FK
      UUID user_id FK
      UUID track_id FK
      TEXT event_type
    }

    USER_TRACK_STATS {
      UUID id PK
      UUID user_id FK
      UUID track_id FK
      TEXT period_type
      DATE period_start
    }

    DOWNLOAD_JOBS {
      UUID id PK
      UUID user_id FK
      UUID track_id FK
      TEXT status
    }

    RECENT_SEARCHES {
      UUID id PK
      UUID user_id FK
      TEXT query
    }

    PROFILES ||--|| USER_SETTINGS : "owns settings"
    PROFILES ||--o{ PLAYLISTS : "owns playlists"
    PLAYLISTS ||--o{ PLAYLIST_ITEMS : "contains"
    TRACKS ||--o{ PLAYLIST_ITEMS : "playlist member"
    PROFILES ||--o{ LIKES : "likes"
    TRACKS ||--o{ LIKES : "liked track"
    PROFILES ||--|| PLAYBACK_SNAPSHOTS : "resume state"
    TRACKS ||--o{ PLAYBACK_SNAPSHOTS : "current track"
    PROFILES ||--o{ HISTORY_ITEMS : "listening history"
    TRACKS ||--o{ HISTORY_ITEMS : "played track"
    LISTENING_SESSIONS ||--o{ HISTORY_ITEMS : "session history"
    PROFILES ||--o{ LISTENING_SESSIONS : "owns sessions"
    LISTENING_SESSIONS ||--o{ PLAYBACK_EVENTS : "emits"
    PROFILES ||--o{ PLAYBACK_EVENTS : "owns events"
    TRACKS ||--o{ PLAYBACK_EVENTS : "event target"
    PROFILES ||--o{ USER_TRACK_STATS : "owns aggregates"
    TRACKS ||--o{ USER_TRACK_STATS : "aggregated track"
    PROFILES ||--o{ DOWNLOAD_JOBS : "owns jobs"
    TRACKS ||--o{ DOWNLOAD_JOBS : "download target"
    PROFILES ||--o{ RECENT_SEARCHES : "optional history"
    ARTISTS ||--o{ ALBUMS : "primary artist"
    ARTISTS ||--o{ TRACKS : "primary artist"
    ALBUMS ||--o{ TRACKS : "album"
    TRACKS ||--o{ TRACK_SOURCE_LINKS : "provider mappings"
```

## Cardinalites importantes
- `profiles` est la racine des donnees utilisateur synchronisables.
- `user_settings` et `playback_snapshots` sont en relation 1:1 avec un profil.
- `likes` est une table de jointure utilisateur <-> piste.
- `user_track_stats` agrege une piste par utilisateur et par periode.
- `recent_searches` reste optionnelle et bornee a une fenetre glissante.

## Donnees explicitement absentes du cloud
- aucune replication brute de `MediaStore`
- aucune `priority queue`
- aucune pile de navigation
- aucun niveau de scroll
