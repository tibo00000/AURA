# Database Setup - AURA Backend

## Overview

This document describes the database schema setup, migration strategy, and maintenance procedures for the AURA backend PostgreSQL database.

## Architecture

The AURA backend uses **PostgreSQL** as the primary data store with the following layers:

1. **Authentication & Profiles** (`profiles` table) — User identity tied to UUIDs (auth layer)
2. **Catalog Layer** (`artists`, `albums`, `tracks`) — Online provider data (Deezer, etc.) indexed by opaque AURA IDs
3. **Mapping Layer** (`track_source_links`) — Links between AURA IDs and provider IDs for enrichment and sync
4. **User Sync Layer** (future: `playlists`, `playlist_tracks`, etc.) — User-scoped collections

## Catalog Schema

### ID Generation Strategy

All AURA backend IDs are **opaque strings** generated server-side, never derived from provider data or local MediaStore IDs:

- **Artists**: `art_{ulid}` (e.g., `art_01ARZ3NDEKTSV4RRFFQ69G5FAV`)
- **Albums**: `alb_{ulid}` (e.g., `alb_01ARZ3NDEKTSV4RRFFQ69G5FAV`)
- **Tracks**: `trk_{ulid}` (e.g., `trk_01ARZ3NDEKTSV4RRFFQ69G5FAV`)
- **Playlists**: `pl_{ulid}` (future)

**Rationale**:
- ULIDs are sortable, distributed-friendly, and avoid UUID collision overhead
- Prefix makes ID type obvious without DB lookup
- Opaque format protects backend implementation details from clients
- Never reuse MediaStore local IDs (`track:local:*`) or provider IDs (Deezer `12345`) as backend IDs

### Tables

#### `artists`
```sql
id TEXT PRIMARY KEY                          -- AURA artist ID (art_{ulid})
display_artist_name TEXT NOT NULL            -- Display name
normalized_name TEXT NOT NULL                -- Indexed for search
provider_references JSONB                    -- {deezer: {id: "123", ...}, ...}
created_at TIMESTAMP WITH TIME ZONE          -- Record creation
updated_at TIMESTAMP WITH TIME ZONE          -- Last update
```

#### `albums`
```sql
id TEXT PRIMARY KEY                          -- AURA album ID (alb_{ulid})
display_album_title TEXT NOT NULL            -- Display name
normalized_title TEXT NOT NULL               -- Indexed for search
artist_id TEXT NOT NULL FK → artists.id      -- Album artist reference
provider_references JSONB                    -- {deezer: {id: "456", ...}, ...}
created_at TIMESTAMP WITH TIME ZONE
updated_at TIMESTAMP WITH TIME ZONE
```

#### `tracks`
```sql
id TEXT PRIMARY KEY                          -- AURA track ID (trk_{ulid})
display_title TEXT NOT NULL                  -- Display name
normalized_title TEXT NOT NULL               -- Indexed for search
album_id TEXT NOT NULL FK → albums.id        -- Album reference
artist_id TEXT NOT NULL FK → artists.id      -- Track artist reference
duration_ms INTEGER                          -- Track duration in milliseconds
provider_references JSONB                    -- {deezer: {id: "789", ...}, ...}
created_at TIMESTAMP WITH TIME ZONE
updated_at TIMESTAMP WITH TIME ZONE
```

#### `track_source_links` (Mapping)
```sql
id TEXT PRIMARY KEY                          -- Mapping ID (link_{ulid})
track_id TEXT NOT NULL FK → tracks.id        -- AURA track ID
provider_name TEXT NOT NULL                  -- Provider name (e.g., "deezer")
provider_track_id TEXT NOT NULL              -- Provider track ID
provider_album_id TEXT                       -- Provider album ID (nullable)
provider_artist_id TEXT                      -- Provider artist ID (nullable)
usage_type TEXT NOT NULL                     -- Usage type (search, stream, download, metadata)
match_score NUMERIC                          -- Quality score (0-1, nullable)
is_active_for_usage BOOLEAN DEFAULT TRUE     -- Active/inactive flag
metadata_json JSONB                          -- Raw provider response (for audit)
created_at TIMESTAMP WITH TIME ZONE
updated_at TIMESTAMP WITH TIME ZONE

UNIQUE(track_id, usage_type, provider_name, provider_track_id) WHERE is_active_for_usage = TRUE
```

**Note**: The `track_source_links` table is foundational for AND-005 (Android sync), which will populate this table with persistent mappings between local MediaStore `track:local:*` IDs and backend AURA IDs. For now, it supports search enrichment and streaming URL resolution.

## Migrations

### File Structure
```
server/app/db/
├── migrations/
│   ├── 001_create_catalog_tables.sql
│   ├── 002_create_mapping_tables.sql
│   └── ...
└── README.md
```

### Running Migrations

**Option A: Raw SQL (psql)**
```bash
psql $DATABASE_URL -f server/app/db/migrations/001_create_catalog_tables.sql
psql $DATABASE_URL -f server/app/db/migrations/002_create_mapping_tables.sql
```

**Option B: SQLAlchemy + Alembic (recommended for production)**
- Initialize Alembic in `server/app/db/alembic/`
- Link each migration `.sql` file as Alembic revision
- Enables rollback, audit trail, and multi-environment consistency

### Best Practices

1. **Naming**: Use sequential numbering with descriptive names (`001_create_catalog_tables.sql`)
2. **Idempotency**: Use `IF NOT EXISTS` for table/index creation; use `IF EXISTS` for drops
3. **Timestamps**: All tables include `created_at` and `updated_at` (UTC)
4. **Constraints**: Foreign keys use `ON DELETE RESTRICT` to prevent orphaned records (e.g., can't delete artist with albums)
5. **Indexes**: Create on `normalized_*` (search) and foreign keys (joins)
6. **Testing**: Always test migrations on a copy of production data before applying

## Seeding Catalog Data

Catalog data (artists, albums, tracks) is populated by **provider adapters** (SRV-002: Deezer) on-demand:

1. User searches for "Taylor Swift"
2. Deezer adapter queries Deezer API
3. Adapter creates AURA IDs for new artists/albums/tracks
4. Backend inserts into `artists`, `albums`, `tracks` with `provider_references`
5. Mapping links stored in `track_source_links` for future enrichment

No bulk seeding needed; catalog grows organically with user queries and admin enrichment jobs (future).

## Querying Patterns

### Find all Deezer provider IDs for a track
```sql
SELECT provider_track_id, provider_album_id, provider_artist_id
FROM track_source_links
WHERE track_id = $1 AND provider_name = 'deezer' AND is_active_for_usage = TRUE;
```

### Find AURA ID for a Deezer track ID (reverse lookup)
```sql
SELECT track_id FROM track_source_links
WHERE provider_name = 'deezer' AND provider_track_id = $1 AND is_active_for_usage = TRUE
LIMIT 1;
```

### Search artists by normalized name
```sql
SELECT * FROM artists
WHERE normalized_name ILIKE $1 || '%'
ORDER BY created_at DESC
LIMIT 20;
```

### Get album with artist details
```sql
SELECT a.*, art.display_artist_name
FROM albums a
JOIN artists art ON a.artist_id = art.id
WHERE a.id = $1;
```

## Maintenance

### Rebuilding Indexes (if performance degrades)
```sql
REINDEX TABLE artists;
REINDEX TABLE albums;
REINDEX TABLE tracks;
REINDEX TABLE track_source_links;
```

### Vacuum & Analyze
```sql
VACUUM ANALYZE artists;
VACUUM ANALYZE albums;
VACUUM ANALYZE tracks;
VACUUM ANALYZE track_source_links;
```

### Monitoring Slow Queries
Enable `log_statement = 'slow'` in PostgreSQL config and tune `log_min_duration_statement`.

## Security

- All table definitions use `TEXT` for IDs (never expose implementation details)
- Foreign keys prevent data corruption
- No direct user data in catalog tables (catalog is read-only from provider perspective)
- User-scoped data (playlists, favorites) will be in separate tables with `user_id` FK + row-level security (future)

## Future Enhancements

1. **Sharding**: If catalog grows beyond single instance, consider vertical partitioning by provider
2. **Materialized Views**: Pre-computed search indexes or trending artist/album rankings
3. **TTL Policies**: Auto-expire stale provider metadata to keep catalog fresh
4. **Version Control**: Track provider schema changes (e.g., new Deezer fields) via metadata_json
