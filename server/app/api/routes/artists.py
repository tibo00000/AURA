"""Artist endpoints for AURA API."""

from typing import Optional

from fastapi import APIRouter, Path
from fastapi.responses import JSONResponse

from ...config import get_settings
from ...core.aura_id_codec import build_aura_id
from ...providers.deezer.adapter import DeezerAdapter
from ...providers.deezer.client import DeezerClient
from ...schemas.responses import (
    AlbumSummaryResponse,
    ArtistDetailsResponse,
    ErrorDetails,
    ResponseEnvelope,
    TrackSummaryResponse,
)
from ...services.artist_service import ArtistService
from ...services.exceptions import NotFound, ProviderUnavailable

router = APIRouter(tags=["artists"], prefix="/artists")

_client: Optional[DeezerClient] = None
_adapter: Optional[DeezerAdapter] = None
_artist_service: Optional[ArtistService] = None


def _get_artist_service() -> ArtistService:
    global _artist_service, _adapter, _client
    if _artist_service is None:
        settings = get_settings()
        _client = DeezerClient(settings.deezer_api_base_url)
        _adapter = DeezerAdapter(_client)
        _artist_service = ArtistService(_adapter)
    return _artist_service


def _to_album_summary(album) -> AlbumSummaryResponse:
    track_count = album.metadata.get("nb_tracks")
    if track_count is not None:
        try:
            track_count = int(track_count)
        except (TypeError, ValueError):
            track_count = None

    return AlbumSummaryResponse(
        id=build_aura_id("album", album.provider_name, album.provider_id),
        title=album.display_title,
        primary_artist_name=album.artist.display_name if album.artist else "Unknown Artist",
        cover_uri=album.metadata.get("cover_medium") or album.metadata.get("cover"),
        release_date=album.metadata.get("release_date"),
        track_count=track_count,
    )


def _to_track_summary(track) -> TrackSummaryResponse:
    return TrackSummaryResponse(
        id=build_aura_id("track", track.provider_name, track.provider_id),
        title=track.display_title,
        display_artist_name=track.artist.display_name if track.artist else "Unknown Artist",
        display_album_title=track.album.display_title if track.album else None,
        duration_ms=track.duration_ms,
        cover_uri=(track.album.metadata.get("cover_medium") or track.album.metadata.get("cover")) if track.album else None,
        is_explicit=bool(track.metadata.get("explicit_lyrics")) if track.metadata.get("explicit_lyrics") is not None else None,
    )


@router.get("/{id}", response_model=ResponseEnvelope[ArtistDetailsResponse])
async def get_artist(
    id: str = Path(..., description="AURA artist ID from GET /search"),
) -> ResponseEnvelope[ArtistDetailsResponse] | JSONResponse:
    try:
        service = _get_artist_service()
        artist, top_tracks, albums = await service.get_artist_details(id)
        response = ArtistDetailsResponse(
            id=build_aura_id("artist", artist.provider_name, artist.provider_id),
            name=artist.display_name,
            picture_uri=artist.metadata.get("picture_medium") or artist.metadata.get("picture"),
            summary=artist.metadata.get("description"),
            top_tracks=[_to_track_summary(track) for track in top_tracks],
            albums=[_to_album_summary(album) for album in albums],
        )
        return ResponseEnvelope(data=response)
    except NotFound as exc:
        return JSONResponse(
            status_code=404,
            content=ResponseEnvelope(
                error=ErrorDetails(code="not_found", message=str(exc), retryable=False),
            ).model_dump(mode="json"),
        )
    except ProviderUnavailable as exc:
        return JSONResponse(
            status_code=503,
            content=ResponseEnvelope(
                error=ErrorDetails(code="provider_unavailable", message=str(exc), retryable=True),
            ).model_dump(mode="json"),
        )
