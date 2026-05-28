"""
Domain models for AURA music entities.

These represent the stable, canonical internal structure of music entities.
They are used across services and adapters to maintain consistency.
"""

from dataclasses import dataclass, field
from typing import Optional, Dict, Any, List, Union
from datetime import datetime


@dataclass
class Artist:
    """Internal representation of an artist."""
    id: str                                     # AURA ID (art_{ulid})
    display_artist_name: str
    normalized_name: str
    provider_references: Optional[Dict[str, Any]] = None
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.display_artist_name,
            "normalized_name": self.normalized_name,
            "provider_references": self.provider_references or {},
        }


@dataclass
class Album:
    """Internal representation of an album."""
    id: str                                     # AURA ID (alb_{ulid})
    display_album_title: str
    normalized_title: str
    artist_id: str                              # AURA artist ID
    provider_references: Optional[Dict[str, Any]] = None
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "title": self.display_album_title,
            "normalized_title": self.normalized_title,
            "artist_id": self.artist_id,
            "provider_references": self.provider_references or {},
        }


@dataclass
class Track:
    """Internal representation of a track."""
    id: str                                     # AURA ID (trk_{ulid})
    display_title: str
    normalized_title: str
    album_id: str                               # AURA album ID
    artist_id: str                              # AURA artist ID
    duration_ms: Optional[int] = None
    provider_references: Optional[Dict[str, Any]] = None
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "title": self.display_title,
            "normalized_title": self.normalized_title,
            "album_id": self.album_id,
            "artist_id": self.artist_id,
            "duration_ms": self.duration_ms,
            "provider_references": self.provider_references or {},
        }


@dataclass
class ProviderArtist:
    """
    Provider-agnostic artist representation from adapters.
    Used as bridge between provider format (Deezer) and internal domain models.
    """
    provider_name: str                          # e.g., "deezer"
    provider_id: str                            # Provider-specific ID
    display_name: str
    metadata: Optional[Dict[str, Any]] = field(default_factory=dict)


@dataclass
class ProviderAlbum:
    """
    Provider-agnostic album representation from adapters.
    """
    provider_name: str                          # e.g., "deezer"
    provider_id: str                            # Provider-specific ID
    display_title: str
    artist: Optional[ProviderArtist] = None
    metadata: Optional[Dict[str, Any]] = field(default_factory=dict)


@dataclass
class ProviderTrack:
    """
    Provider-agnostic track representation from adapters.
    This is the normalized format returned by all provider adapters.
    """
    provider_name: str                          # e.g., "deezer"
    provider_id: str                            # Provider-specific track ID
    display_title: str
    album: Optional[ProviderAlbum] = None
    artist: Optional[ProviderArtist] = None
    duration_ms: Optional[int] = None
    metadata: Optional[Dict[str, Any]] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "provider_name": self.provider_name,
            "provider_id": self.provider_id,
            "title": self.display_title,
            "album": self.album.to_dict() if self.album else None,
            "artist": self.artist.to_dict() if self.artist else None,
            "duration_ms": self.duration_ms,
            "metadata": self.metadata or {},
        }


@dataclass
class SearchResult:
    """Result from a search operation."""
    best_match: Optional[Union[ProviderTrack, ProviderArtist, ProviderAlbum]] = None
    tracks: List[ProviderTrack] = field(default_factory=list)
    artists: List[ProviderArtist] = field(default_factory=list)
    albums: List[ProviderAlbum] = field(default_factory=list)


@dataclass
class DownloadJob:
    """Internal representation of a download job."""
    id: str                                     # AURA ID (job_{ulid})
    user_id: str                                # UUID string
    track_id: str                               # AURA track ID (trk_{ulid})
    provider_name: str                          # e.g., "backend", "youtube"
    status: str                                 # "queued", "running", "succeeded", "failed", "cancelled"
    progress_percent: float = 0.0
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    attempt_count: int = 1
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None
    archived_at: Optional[datetime] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "user_id": self.user_id,
            "track_id": self.track_id,
            "provider_name": self.provider_name,
            "status": self.status,
            "progress_percent": self.progress_percent,
            "error_code": self.error_code,
            "error_message": self.error_message,
            "attempt_count": self.attempt_count,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
            "archived_at": self.archived_at.isoformat() if self.archived_at else None,
        }

