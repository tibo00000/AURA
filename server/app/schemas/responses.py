"""
Response schemas for API endpoints.

All API responses follow a canonical envelope format:
- Success: {data: T, error: null, meta: {}}
- Error: {data: null, error: {code, message, retryable, details}, meta: {}}

This maintains consistency across all endpoints and enables standardized
error handling on clients.
"""

from typing import TypeVar, Generic, Optional, Dict, Any, List
from pydantic import BaseModel, Field
from datetime import datetime

T = TypeVar("T")


class Meta(BaseModel):
    """Metadata about the response."""
    request_id: Optional[str] = None
    timestamp: Optional[datetime] = None
    partial_failure: bool = False


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


class ArtistResponse(BaseModel):
    """Response for an artist in search or detail."""
    id: str                                     # AURA artist ID
    name: str
    provider_references: Optional[Dict[str, Any]] = None


class AlbumResponse(BaseModel):
    """Response for an album in search or detail."""
    id: str                                     # AURA album ID
    title: str
    artist: Optional[ArtistResponse] = None
    provider_references: Optional[Dict[str, Any]] = None


class TrackResponse(BaseModel):
    """Response for a track in search or detail."""
    id: str                                     # AURA track ID
    title: str
    album: Optional[AlbumResponse] = None
    artist: Optional[ArtistResponse] = None
    duration_ms: Optional[int] = None
    provider_references: Optional[Dict[str, Any]] = None


class SearchResponse(BaseModel):
    """Response for GET /search endpoint."""
    best_match: Optional[TrackResponse] = None
    tracks: List[TrackResponse] = Field(default_factory=list)
    artists: List[ArtistResponse] = Field(default_factory=list)
    albums: List[AlbumResponse] = Field(default_factory=list)


class ArtistDetailsResponse(BaseModel):
    """Response for GET /artists/{id} endpoint."""
    artist: ArtistResponse
    top_tracks: List[TrackResponse] = Field(default_factory=list)


class AlbumDetailsResponse(BaseModel):
    """Response for GET /albums/{id} endpoint."""
    album: AlbumResponse
    tracks: List[TrackResponse] = Field(default_factory=list)
