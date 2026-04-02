# API Sync Flows

## Objectif
Donner une vue des principaux flux entre l'application Android, l'API FastAPI, `Supabase / Postgres`, `Qdrant` et les workers de jobs.

## Principes de lecture
- Les flux ci-dessous decrivent uniquement les echanges online et sync.
- Le mode local-first continue d'exister sans ces flux.
- La fusion entre resultats locaux et online reste toujours cote Android.

## Flux 1 - Recherche online enrichie
```mermaid
sequenceDiagram
    participant Android as Android App
    participant API as FastAPI
    participant Provider as Provider Adapter
    participant PG as Supabase/Postgres

    Android->>API: GET /search?q=...
    API->>Provider: Search tracks, artists, albums
    Provider-->>API: Provider results
    API->>PG: Upsert canonical entities and mappings if needed
    PG-->>API: Canonical AURA ids and metadata
    API-->>Android: best_match + tracks + artists + albums

    Note over Android: Android merges online results with local results from Room and MediaStore.
    Note over API,Provider: Provider failure may produce partial_failure without breaking local UX.
```

## Flux 2 - Lecture locale avec sync cloud optionnelle
```mermaid
sequenceDiagram
    participant Android as Android App
    participant Room as Room
    participant API as FastAPI
    participant PG as Supabase/Postgres

    Android->>Room: Read local playlist, likes, snapshot, history
    Room-->>Android: Local durable state
    Android->>Android: Play locally with Media3

    alt Sync enabled and auth available
        Android->>API: PUT /me/playback-snapshot
        API->>PG: Upsert playback snapshot
        PG-->>API: Snapshot stored
        API-->>Android: Snapshot acknowledged
    else Local-only mode
        Note over Android: No backend call is required.
    end
```

## Flux 3 - Sync des playlists, likes et historique
```mermaid
flowchart LR
    Android["Android App"] -->|"POST /me/playlists, PATCH /me/playlists/{id}, POST /me/playlists/{id}/tracks"| API["FastAPI"]
    Android -->|"PUT /me/tracks/{trackId}/like"| API
    Android -->|"GET /me/history"| API
    Android -->|"POST /me/stats/batch"| API
    API --> PG["Supabase / Postgres"]
    PG --> API
    API --> Android

    SyncNote["Sync optionnelle : ces flux n'existent que si le compte cloud est actif."]:::note
    Android -.-> SyncNote

    classDef note fill:#f3f4f6,stroke:#9ca3af,color:#111827
```

## Flux 4 - Telechargement et job asynchrone
```mermaid
sequenceDiagram
    participant Android as Android App
    participant API as FastAPI
    participant PG as Supabase/Postgres
    participant Worker as Job Worker
    participant Provider as Provider Adapter

    Android->>API: POST /downloads
    API->>PG: Create download job
    PG-->>API: job_id
    API-->>Android: job queued

    Worker->>PG: Poll pending jobs
    PG-->>Worker: queued job
    Worker->>Provider: Resolve source and fetch asset or metadata
    Provider-->>Worker: provider output
    Worker->>PG: Update progress or final status

    Android->>API: GET /jobs/{id}
    API->>PG: Read job status
    PG-->>API: current status
    API-->>Android: queued, running, succeeded or failed

    Note over API,Worker: The download contract stays generic. The backend chooses the internal strategy.
```

## Flux 5 - Recommandation et recherche vectorielle
```mermaid
sequenceDiagram
    participant API as FastAPI
    participant Q as Qdrant
    participant PG as Supabase/Postgres

    API->>Q: Vector similarity query
    Q-->>API: track_id list + payload metadata
    API->>PG: Read canonical track entities
    PG-->>API: Track rows and mappings
    API-->>API: Build AURA response objects

    Note over Q: Qdrant stores vectors and payload metadata, not transactional truth.
```

## Limites explicites
- Aucun flux n'encode la `priority queue`, car elle n'est pas persistee.
- Aucun flux n'encode la navigation UI ou le niveau de scroll.
- Le choix interne exact de source de telechargement reste volontairement opaque a ce stade.
