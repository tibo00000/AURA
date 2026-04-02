# Room Relationships

## Objectif
Donner une vue relationnelle des tables `Room` sans recopier l'integralite du schema colonne par colonne.

## Principes de lecture
- Ce diagramme ne couvre que les relations structurelles de `Room`.
- Les resultats online transitoires n'entrent pas en base tant qu'aucune action metier significative n'a eu lieu.
- La `priority queue` n'apparait pas ici car elle n'est pas persistee.

```mermaid
erDiagram
    ARTISTS {
      TEXT id PK
      TEXT name
    }

    ALBUMS {
      TEXT id PK
      TEXT primary_artist_id FK
      TEXT title
    }

    TRACKS {
      TEXT id PK
      TEXT primary_artist_id FK
      TEXT album_id FK
      TEXT title
    }

    TRACK_MEDIA_LINKS {
      TEXT id PK
      TEXT track_id FK
      INTEGER media_store_id
    }

    TRACK_SOURCE_LINKS {
      TEXT id PK
      TEXT track_id FK
      TEXT usage_type
      TEXT provider_name
    }

    TRACK_LIKES {
      TEXT track_id PK
      INTEGER liked_at
    }

    PLAYLISTS {
      TEXT id PK
      TEXT name
      INTEGER updated_at
    }

    PLAYLIST_ITEMS {
      TEXT id PK
      TEXT playlist_id FK
      TEXT track_id FK
      INTEGER position
    }

    PLAYBACK_SNAPSHOTS {
      TEXT id PK
      TEXT current_track_id FK
      INTEGER position_ms
    }

    HISTORY_ITEMS {
      TEXT id PK
      TEXT track_id FK
      TEXT listening_session_id FK
      INTEGER played_at
    }

    LISTENING_SESSIONS {
      TEXT id PK
      INTEGER started_at
      INTEGER ended_at
    }

    PLAYBACK_EVENTS {
      TEXT id PK
      TEXT session_id FK
      TEXT track_id FK
      TEXT event_type
    }

    USER_TRACK_STATS {
      TEXT id PK
      TEXT track_id FK
      TEXT period_type
      TEXT period_start
    }

    DOWNLOAD_JOBS {
      TEXT id PK
      TEXT track_id FK
      TEXT status
    }

    RECENT_SEARCHES {
      TEXT id PK
      TEXT query
      INTEGER searched_at
    }

    USER_SETTINGS {
      TEXT id PK
      INTEGER sync_enabled
    }

    SYNC_OUTBOX {
      TEXT id PK
      TEXT entity_type
      TEXT entity_id
      TEXT status
    }

    ARTISTS ||--o{ ALBUMS : "primary artist"
    ARTISTS ||--o{ TRACKS : "primary artist"
    ALBUMS ||--o{ TRACKS : "album"
    TRACKS ||--o| TRACK_MEDIA_LINKS : "local file link"
    TRACKS ||--o{ TRACK_SOURCE_LINKS : "provider mappings"
    TRACKS ||--o| TRACK_LIKES : "like row"
    PLAYLISTS ||--o{ PLAYLIST_ITEMS : "contains"
    TRACKS ||--o{ PLAYLIST_ITEMS : "playlist member"
    TRACKS ||--o{ HISTORY_ITEMS : "played track"
    LISTENING_SESSIONS ||--o{ HISTORY_ITEMS : "groups history"
    LISTENING_SESSIONS ||--o{ PLAYBACK_EVENTS : "emits"
    TRACKS ||--o{ PLAYBACK_EVENTS : "event target"
    TRACKS ||--o{ USER_TRACK_STATS : "aggregated into"
    TRACKS ||--o{ DOWNLOAD_JOBS : "download target"
    TRACKS ||--o{ PLAYBACK_SNAPSHOTS : "current track"
```

## Cardinalites importantes
- `track_media_links` impose une relation 1 piste AURA <-> 1 entree `MediaStore` dans le schema actuel.
- `track_source_links` autorise plusieurs mappings par piste, differencies par `usage_type`.
- `playlist_items` porte l'ordre reel de lecture dans une playlist et autorise plusieurs occurrences d'une meme piste.
- `playback_snapshots` ne contient qu'une seule ligne active au niveau applicatif, meme si la relation vers `tracks` reste structurellement optionnelle.
- `recent_searches`, `user_settings` et `sync_outbox` sont volontairement faiblement relies au reste du graphe.

## Regles de presence en base
- Une piste online entre dans `Room` quand elle devient utile a l'etat metier : lecture, like, ajout a playlist, cache detaille ou preparation de telechargement.
- Une piste online issue d'une simple recherche non actionnee reste ephemere et ne cree pas d'ecriture `Room`.
