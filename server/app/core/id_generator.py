"""
ID generation for AURA backend entities.

AURA IDs are opaque, backend-generated strings using ULID + prefix:
- Artists: art_{ulid}
- Albums: alb_{ulid}
- Tracks: trk_{ulid}
- Playlists: pl_{ulid}

ULID (Universally Unique Lexicographically Sortable Identifiers) provides:
- Sortability by timestamp
- Distributed uniqueness without central coordinator
- Smaller than UUIDs (26 chars vs 36)
- Human-readable (base32 encoded)

**Never** reuse MediaStore local IDs (track:local:*) or provider IDs (Deezer 12345)
as backend AURA IDs. Mapping between local/provider and backend IDs is done
via track_source_links table (reserved for AND-005).
"""

from ulid import ULID


def generate_track_id() -> str:
    """Generate an AURA track ID."""
    return f"trk_{ULID()}"


def generate_artist_id() -> str:
    """Generate an AURA artist ID."""
    return f"art_{ULID()}"


def generate_album_id() -> str:
    """Generate an AURA album ID."""
    return f"alb_{ULID()}"


def generate_playlist_id() -> str:
    """Generate an AURA playlist ID."""
    return f"pl_{ULID()}"


def generate_link_id() -> str:
    """Generate a mapping link ID."""
    return f"link_{ULID()}"
