"""
Pydantic schemas for the /me endpoints.
"""

import re
from typing import Optional, List, Literal
from uuid import UUID
from pydantic import BaseModel, Field, model_validator


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
    playlist_occurrence_id: str | None = Field(default=None, max_length=512)


from app.schemas.artist_credits import ArtistCredit


class MetadataResolveRequest(BaseModel):
    track_id: str = Field(min_length=1, max_length=250)
    title: str = Field(min_length=1, max_length=512)
    artist_name: str = Field(min_length=1, max_length=512)
    album_title: str | None = Field(default=None, max_length=512)
    duration_ms: int | None = Field(default=None, ge=0, le=999999999)
    artists: list[ArtistCredit] = Field(default_factory=list, max_length=32)


class PlaylistTrackMetadata(BaseModel):
    provider: Literal["spotify", "deezer"]
    provider_id: str = Field(min_length=1, max_length=22)
    title: str = Field(min_length=1, max_length=512)
    artist: str = Field(min_length=1, max_length=512)
    artists: list[ArtistCredit] = Field(default_factory=list, max_length=32)
    album: str | None = Field(default=None, max_length=512)
    duration_ms: int | None = Field(default=None, ge=0, le=999999999)
    cover_uri: str | None = Field(default=None, max_length=1200)


class PlaylistRefreshTrack(PlaylistTrackMetadata):
    requires_confirmation: bool = False


class PlaylistRefreshRequest(BaseModel):
    request_id: UUID
    source_revision: int = Field(ge=1, le=9223372036854775807)
    tracks: list[PlaylistRefreshTrack] = Field(max_length=10000)
    confirmed_distinct: list[str] = Field(default_factory=list, max_length=10000)
    confirmed_links: dict[str, str] = Field(default_factory=dict, max_length=10000)


class PlaylistUncertainTrack(PlaylistTrackMetadata):
    track_id: str


class PlaylistRefreshResult(BaseModel):
    request_id: UUID
    source_revision: int
    completed_at: str
    added_count: int
    excluded_count: int
    present_count: int
    uncertain: list[PlaylistUncertainTrack] = Field(default_factory=list)


class PlaylistItemResponse(BaseModel):
    """Playlist item model."""
    id: str
    playlist_id: str
    track_id: str
    position: int
    added_at: str
    added_from_context_type: Optional[str] = None
    added_from_context_id: Optional[str] = None
    source_metadata: PlaylistTrackMetadata | None = None


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


class PlaylistSnapshotEntry(PlaylistResponse):
    order_token: str
    item_count: int = Field(ge=0)
    items: List[PlaylistItemResponse]
    source_provider: Literal["spotify", "deezer"] | None = None
    source_external_id: str | None = None
    source_revision: int = Field(default=0, ge=0)
    last_refresh: PlaylistRefreshResult | None = None


class PlaylistSourceResponse(BaseModel):
    provider: Literal["spotify", "deezer"] | None
    external_id: str | None
    source_revision: int = Field(ge=0)


class PlaylistSourceRequest(BaseModel):
    provider: Literal["spotify", "deezer"] | None
    external_id: str | None = Field(max_length=22)
    expected_revision: int = Field(ge=0, le=9223372036854775807)

    @model_validator(mode="after")
    def validate_reference(self):
        if self.provider is None and self.external_id is None:
            return self
        if self.provider is None or self.external_id is None:
            raise ValueError("Provider and external ID must be provided together")
        pattern = r"[A-Za-z0-9]{22}" if self.provider == "spotify" else r"[1-9][0-9]{0,19}"
        if not re.fullmatch(pattern, self.external_id):
            raise ValueError("Invalid playlist source reference")
        return self


class PlaylistSnapshotResponse(BaseModel):
    user_id: str
    contract_version: int = Field(ge=1, le=1)
    revision: int = Field(ge=0)
    playlist_count: int = Field(ge=0)
    item_count: int = Field(ge=0)
    playlists: List[PlaylistSnapshotEntry]


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


class PlaylistUpdate(BaseModel):
    """Request model to update an existing playlist."""
    name: Optional[str] = None
    cover_uri: Optional[str] = None
    is_pinned: Optional[bool] = None


