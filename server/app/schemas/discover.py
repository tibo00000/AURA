"""
Pydantic schemas for Discovery Engine API endpoints.

Covers request/response models for:
- POST /me/discover/generate
- GET  /me/discover/feed
- POST /me/discover/feedback/{item_id}
- GET  /me/discover/weights
"""

from typing import List, Optional, Literal
from pydantic import BaseModel, Field
from datetime import datetime


# ---------------------------------------------------------------------------
# Response models
# ---------------------------------------------------------------------------

class DiscoveryItemResponse(BaseModel):
    """A single discovery recommendation item."""
    id: str
    source_strategy: Literal[
        "artist_radar", "genre_drift", "artist_radio", "wildcard"
    ]
    reason_text: Optional[str] = None
    source_artist_name: Optional[str] = None

    # Track metadata
    deezer_track_id: int
    track_title: str
    artist_name: str
    album_title: Optional[str] = None
    cover_url: Optional[str] = None
    duration_ms: Optional[int] = None
    release_date: Optional[str] = None
    record_type: Optional[str] = None
    preview_url: Optional[str] = None

    # Download state
    download_status: Literal["pending", "downloading", "ready", "failed"]
    aura_track_id: Optional[str] = None

    # Scoring
    score: float

    created_at: Optional[datetime] = None


class DiscoveryFeedResponse(BaseModel):
    """Response for GET /me/discover/feed."""
    items: List[DiscoveryItemResponse] = Field(default_factory=list)
    batch_id: Optional[str] = None
    is_stale: bool = False
    total_count: int = 0


class GenerateBatchResponse(BaseModel):
    """Response for POST /me/discover/generate."""
    batch_id: str
    items_generated: int
    predownloads_triggered: int
    is_cold_start: bool = False


class StrategyWeightsResponse(BaseModel):
    """Response for GET /me/discover/weights."""
    artist_radar_weight: float
    genre_drift_weight: float
    artist_radio_weight: float
    wildcard_weight: float
    total_feedback_count: int


# ---------------------------------------------------------------------------
# Request models
# ---------------------------------------------------------------------------

class FeedbackRequest(BaseModel):
    """Request body for POST /me/discover/feedback/{item_id}."""
    action: Literal[
        "seen", "played", "completed", "skipped",
        "liked", "playlist_add", "downloaded"
    ]
