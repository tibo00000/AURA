# ID Generation Strategy - AURA Backend

## Overview

AURA uses **opaque backend IDs** generated server-side to maintain architectural independence from providers and ensure data integrity across the platform.

## ID Format

All AURA backend IDs use **ULID (Universally Unique Lexicographically Sortable Identifier)** with a human-readable prefix:

| Entity Type | Format | Example | Notes |
|---|---|---|---|
| Track | `trk_{ulid}` | `trk_01ARZ3NDEKTSV4RRFFQ69G5FAV` | Uniquely identifies an AURA track |
| Artist | `art_{ulid}` | `art_01ARZ3NDEKTSV4RRFFQ69G5FAV` | Uniquely identifies an AURA artist |
| Album | `alb_{ulid}` | `alb_01ARZ3NDEKTSV4RRFFQ69G5FAV` | Uniquely identifies an AURA album |
| Playlist | `pl_{ulid}` | `pl_01ARZ3NDEKTSV4RRFFQ69G5FAV` | Uniquely identifies a user playlist |
| Link (Mapping) | `link_{ulid}` | `link_01ARZ3NDEKTSV4RRFFQ69G5FAV` | Links AURA ID to provider ID |

## Rationale

### Why ULID?

- **Sortable by timestamp**: Enables chronological queries without extra clock synchronization
- **Distributed generation**: No central ID server needed; collision probability negligible
- **Human-readable**: Base32-encoded, easier to debug than UUID hex
- **Compact**: 26 characters vs 36 for UUID, reducing storage and bandwidth
- **Monotonic**: Sortable across multiple machines without clock skew

### Why Prefix?

- **Type identification**: `art_` vs `alb_` vs `trk_` readable at a glance
- **Prevention of ID confusion**: API can reject malformed IDs (e.g., `trk_` for album endpoint)
- **Database safety**: Easier to identify orphaned records in raw SQL

### Why Backend-Generated?

- **Single Source of Truth**: Backend controls catalog identity
- **Provider Independence**: Never tied to Deezer/Spotify/YouTube Music IDs
- **Future-Proof**: Can migrate providers without ID collision
- **Consistency**: One canonical ID across all clients (Android, web, etc.)

## Critical Rules

### ✅ DO

1. **Generate IDs server-side only** (in `server/app/core/id_generator.py`)
2. **Use ULID for uniqueness** and sortability
3. **Return opaque IDs to clients** (no implementation leaking)
4. **Map provider IDs via `track_source_links`** (never as primary ID)
5. **Validate ID format on receipt** (`trk_`, `art_`, `alb_`, etc.)

### ❌ DON'T

1. **Never reuse MediaStore local IDs** as backend AURA IDs
   - ❌ `track:local:123` → Not an AURA ID
   - ✅ `track:local:123` → Mapped in Android locally, linked in `track_source_links`

2. **Never reuse provider IDs** as backend AURA IDs
   - ❌ Deezer ID `12345678` → Not an AURA ID
   - ✅ Deezer ID stored in `track_source_links.provider_track_id`

3. **Never expose backend ID generation to Android**
   - ❌ Android calling `generate_track_id()` locally
   - ✅ Android receives ID from backend search response

4. **Never mix ID spaces**
   - ❌ Comparing `track:local:123` with `trk_01ARZ3ND...` directly
   - ✅ Use `track_source_links` table for mapping queries

## ID Lifecycle

### Creation Flow

```
1. User searches "Taylor Swift" → GET /search?q=Taylor+Swift
2. Backend calls Deezer API → receives Deezer track ID `12345678`
3. Backend generates AURA ID `trk_01ARZ3NDEKTSV4RRFFQ69G5FAV`
4. Backend creates entry in `tracks` table with AURA ID
5. Backend creates link in `track_source_links`:
   - track_id = `trk_01ARZ3NDEKTSV4RRFFQ69G5FAV` (AURA)
   - provider_name = `deezer`
   - provider_track_id = `12345678` (Deezer)
   - usage_type = `search`
6. Backend returns response with AURA ID to client
```

### Lookup Flow (reverse)

```
1. Backend needs streaming URL from Deezer for track `trk_01ARZ3ND...`
2. Query: SELECT provider_track_id FROM track_source_links WHERE track_id = $1
3. Result: provider_track_id = `12345678`
4. Call Deezer API with `12345678` to get streaming URL
```

### Android Local Sync (AND-005)

```
1. Android imports local track via MediaStore → ID `track:local:456`
2. Android sends to backend for matching/enrichment
3. Backend matches by metadata (title, artist, album)
4. Backend creates entry in `track_source_links`:
   - provider_name = `local_mediastore`
   - provider_track_id = `456` (local MediaStore URI)
   - usage_type = `download`, `stream`, `metadata`
5. No AURA ID generation for local tracks; they remain local
6. Future: if user searches and finds Deezer version, link both via same AURA ID
```

## Implementation

### Backend Generation (`server/app/core/id_generator.py`)

```python
from ulid import ULID

def generate_track_id() -> str:
    return f"trk_{ULID()}"

def generate_artist_id() -> str:
    return f"art_{ULID()}"

def generate_album_id() -> str:
    return f"alb_{ULID()}"

def generate_playlist_id() -> str:
    return f"pl_{ULID()}"

def generate_link_id() -> str:
    return f"link_{ULID()}"
```

### Database Storage

All AURA ID fields are stored as `TEXT` in PostgreSQL (not `UUID`):

```sql
CREATE TABLE artists (
    id TEXT PRIMARY KEY,  -- e.g., art_01ARZ3ND...
    display_artist_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    ...
);

CREATE TABLE track_source_links (
    id TEXT PRIMARY KEY,  -- link_01ARZ3ND...
    track_id TEXT NOT NULL REFERENCES tracks(id),
    provider_name TEXT NOT NULL,
    provider_track_id TEXT NOT NULL,  -- e.g., Deezer 12345678
    ...
);
```

### API Response

All API responses return AURA IDs only (never provider IDs):

```json
{
  "data": {
    "best_match": {
      "id": "trk_01ARZ3NDEKTSV4RRFFQ69G5FAV",
      "title": "Love Story",
      "artist": {
        "id": "art_01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "name": "Taylor Swift"
      },
      "album": {
        "id": "alb_01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "title": "Fearless"
      }
    },
    "tracks": [...],
    "artists": [...],
    "albums": [...]
  },
  "error": null,
  "meta": {}
}
```

## Future Considerations

### Sharding & Partitioning

If AURA scales beyond single PostgreSQL instance:
- Consider date-based sharding (ULIDs already timestamp-sortable)
- Prefix encoding could help distribute across shards

### ID Collision Risk

- ULID collision probability: ~1 in 2^80 (negligible for practical purposes)
- No global coordinator needed; safe for distributed generation

### Audit & Traceability

Store provider metadata in `track_source_links.metadata_json` for:
- Audit trail of data sources
- Provider versioning (if Deezer API changes)
- Quality scoring and confidence levels

### Migration Path

If integrating new provider (Spotify, YouTube Music):
- Add new rows to `track_source_links` with provider_name = `spotify`
- No changes to AURA IDs (guaranteed compatibility)
- Potential consolidation via future deduplication jobs
