"""
Pydantic schemas for the /me endpoints.
"""

from typing import Optional, List
from pydantic import BaseModel, Field


class UserSettingsResponse(BaseModel):
    """User settings model."""
    sync_enabled: bool
    online_search_enabled: bool
    online_search_network_policy: str
    stats_sync_network_policy: str
    updated_at: Optional[str] = None


class UserSettingsPatch(BaseModel):
    """User settings patch request model."""
    sync_enabled: Optional[bool] = None
    online_search_enabled: Optional[bool] = None
    online_search_network_policy: Optional[str] = None
    stats_sync_network_policy: Optional[str] = None


class PlaybackSnapshotResponse(BaseModel):
    """Playback snapshot model."""
    current_track_id: Optional[str] = None
    playback_context_type: Optional[str] = None
    playback_context_id: Optional[str] = None
    playback_context_index: Optional[int] = None
    position_ms: int
    shuffle_enabled: bool
    repeat_mode: str
    updated_at: Optional[str] = None


class PlaylistItemResponse(BaseModel):
    """Playlist item model."""
    id: str
    playlist_id: str
    track_id: str
    position: int
    added_at: str
    added_from_context_type: Optional[str] = None
    added_from_context_id: Optional[str] = None


class PlaylistResponse(BaseModel):
    """Playlist model."""
    id: str
    user_id: str
    name: str
    cover_uri: Optional[str] = None
    is_pinned: bool
    created_at: str
    updated_at: str
    items: List[PlaylistItemResponse] = Field(default_factory=list)


class LikeResponse(BaseModel):
    """Like / Favorite model."""
    track_id: str
    liked_at: str
    source_context_type: Optional[str] = None
    source_context_id: Optional[str] = None


class LikeRequest(BaseModel):
    """Request model to like a track."""
    source_context_type: Optional[str] = None
    source_context_id: Optional[str] = None


class HistoryItemResponse(BaseModel):
    """History item model."""
    id: str
    track_id: str
    played_at: str
    completion_percent: Optional[float] = None
    was_skipped: bool
    source_context_type: Optional[str] = None
    source_context_id: Optional[str] = None


class HistoryResponseData(BaseModel):
    """Envelope wrapper for history items."""
    items: List[HistoryItemResponse]


class PlaylistCreate(BaseModel):
    """Request model to create a playlist."""
    id: str
    name: str
    cover_uri: Optional[str] = None
    is_pinned: bool = False


class PlaylistItemCreate(BaseModel):
    """Request model to append a track to a playlist."""
    id: str
    track_id: str
    position: int
    added_from_context_type: Optional[str] = None
    added_from_context_id: Optional[str] = None

