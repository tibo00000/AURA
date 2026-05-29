"""
Pydantic schemas for download jobs and cookies.

Matches the canonical contracts defined in docs/server/api-contract.md.
"""

from typing import Optional, List, Literal
from pydantic import BaseModel, Field
from datetime import datetime


class SourceHint(BaseModel):
    """Optional helper for locating the track source."""
    provider_name: str
    provider_track_id: str


class DownloadRequest(BaseModel):
    """Request schema for POST /downloads (triggering a download)."""
    track_id: str = Field(..., description="Target AURA track ID (trk_{ulid})")
    source_hint: Optional[SourceHint] = Field(None, description="Optional advice for track resolution")


class DownloadCreateResponse(BaseModel):
    """Immediate response after submitting a download job."""
    job_id: str
    track_id: str
    status: Literal["queued", "running", "succeeded", "failed", "cancelled", "requires_resolution"]


class YtmCandidate(BaseModel):
    """YouTube Music candidate suggestion for user choice."""
    video_id: str
    title: str
    artist: str
    album: Optional[str] = None
    duration: Optional[str] = None
    cover_uri: Optional[str] = None


class DownloadJobResponse(BaseModel):
    """Standard download job representation in list responses."""
    id: str
    track_id: str
    provider_name: str
    status: Literal["queued", "running", "succeeded", "failed", "cancelled", "requires_resolution"]
    progress_percent: float = 0.0
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    attempt_count: int = 1
    created_at: datetime
    updated_at: datetime


class PaginationMeta(BaseModel):
    """Pagination metadata for listed items."""
    next_cursor: Optional[str] = None
    total_count: Optional[int] = None


class DownloadJobListResponse(BaseModel):
    """Paginated list response for GET /downloads."""
    items: List[DownloadJobResponse]
    meta: PaginationMeta = Field(default_factory=PaginationMeta)


class JobStatusResponse(BaseModel):
    """Generic async job status representation for GET /jobs/{id}."""
    id: str
    kind: Literal["download", "enrichment", "maintenance"]
    status: Literal["queued", "running", "succeeded", "failed", "cancelled", "requires_resolution"]
    progress_percent: float = 0.0
    result: Optional[dict] = None
    error: Optional[dict] = None  # Contains code and message keys
    candidates: Optional[List[YtmCandidate]] = None
    created_at: datetime
    updated_at: datetime


class ResolveDownloadRequest(BaseModel):
    """Request schema to resolve a pending download with a specific video ID."""
    video_id: str = Field(..., description="The YTM video ID selected by the user")


class CookieUploadRequest(BaseModel):
    """Request schema for uploading Netscape cookies."""
    cookies_text: str = Field(..., description="Netscape cookie file text content")
