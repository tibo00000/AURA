"""
Response schemas for API endpoints.

All API responses follow a canonical envelope format:
- Success: {data: T, error: null, meta: {}}
- Error: {data: null, error: {code, message, retryable, details}, meta: {}}

This maintains consistency across all endpoints and enables standardized
error handling on clients.
"""

from typing import TypeVar, Generic, Optional, Dict, Any, List, Literal, Union
from pydantic import BaseModel, Field
from datetime import datetime

T = TypeVar("T")


class Meta(BaseModel):
    """Metadata about the response."""
    request_id: Optional[str] = None
    timestamp: Optional[datetime] = None
    partial_failure: bool = False
    provider_status: Optional[Dict[str, str]] = None


class ErrorDetails(BaseModel):
    """Error details."""
    code: str                                   # e.g., "not_found", "provider_unavailable"
    message: str
    retryable: bool = False
    details: Optional[Dict[str, Any]] = None


class ResponseEnvelope(BaseModel, Generic[T]):
    """
    Generic response envelope for all API responses.
    
    Success example:
    {
      "data": {...},
      "error": null,
      "meta": {}
    }
    
    Error example:
    {
      "data": null,
      "error": {"code": "not_found", "message": "...", "retryable": false, "details": {}},
      "meta": {"request_id": "req_123"}
    }
    """
    data: Optional[T] = None
    error: Optional[ErrorDetails] = None
    meta: Meta = Field(default_factory=Meta)


# Specific response schemas

class HealthResponse(BaseModel):
    """Response for GET /health endpoint."""
    status: str                                 # e.g., "ok"
    service: str                                # e.g., "aura-api"
    time: datetime                              # ISO 8601 UTC


class ArtistSummaryResponse(BaseModel):
    """Canonical summary for an artist."""
    id: str
    name: str
    picture_uri: Optional[str] = None


class AlbumSummaryResponse(BaseModel):
    """Canonical summary for an album."""
    id: str
    title: str
    primary_artist_name: str
    cover_uri: Optional[str] = None
    release_date: Optional[str] = None
    track_count: Optional[int] = None


class TrackSummaryResponse(BaseModel):
    """Canonical summary for a track."""
    id: str
    title: str
    display_artist_name: str
    display_album_title: Optional[str] = None
    duration_ms: Optional[int] = None
    cover_uri: Optional[str] = None
    is_explicit: Optional[bool] = None
    is_liked: bool = False
    is_local_available: bool = False
    is_downloaded_by_aura: bool = False


class SearchBestMatchResponse(BaseModel):
    """Best match payload for GET /search."""
    kind: Literal["track", "artist", "album"]
    item: Union[TrackSummaryResponse, ArtistSummaryResponse, AlbumSummaryResponse]


class SearchResponse(BaseModel):
    """Response for GET /search endpoint."""
    query: str
    best_match: Optional[SearchBestMatchResponse] = None
    tracks: List[TrackSummaryResponse] = Field(default_factory=list)
    artists: List[ArtistSummaryResponse] = Field(default_factory=list)
    albums: List[AlbumSummaryResponse] = Field(default_factory=list)


class ArtistDetailsResponse(BaseModel):
    """Response for GET /artists/{id} endpoint."""
    id: str
    name: str
    picture_uri: Optional[str] = None
    summary: Optional[str] = None
    top_tracks: List[TrackSummaryResponse] = Field(default_factory=list)
    albums: List[AlbumSummaryResponse] = Field(default_factory=list)


class AlbumDetailsResponse(BaseModel):
    """Response for GET /albums/{id} endpoint."""
    id: str
    title: str
    primary_artist_name: str
    cover_uri: Optional[str] = None
    release_date: Optional[str] = None
    track_count: Optional[int] = None
    tracks: List[TrackSummaryResponse] = Field(default_factory=list)


# Resolve Responses (SRV-008)

class ResolvedArtistData(BaseModel):
    id: str
    name: str
    picture_uri: Optional[str] = None


class ResolveArtistResponseData(BaseModel):
    resolved: bool
    match_confidence: float
    artist: Optional[ResolvedArtistData] = None


class ResolvedAlbumData(BaseModel):
    id: str
    title: str
    primary_artist_name: str
    cover_uri: Optional[str] = None
    release_date: Optional[str] = None
    track_count: Optional[int] = None


class ResolveAlbumResponseData(BaseModel):
    resolved: bool
    match_confidence: float
    album: Optional[ResolvedAlbumData] = None
