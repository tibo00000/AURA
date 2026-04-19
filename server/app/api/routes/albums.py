"""Album endpoints for AURA API."""

from typing import Optional

from fastapi import APIRouter, Path
from fastapi.responses import JSONResponse

from ...config import get_settings
from ...core.aura_id_codec import build_aura_id
from ...providers.deezer.adapter import DeezerAdapter
from ...providers.deezer.client import DeezerClient
from ...schemas.responses import AlbumDetailsResponse, ErrorDetails, ResponseEnvelope, TrackSummaryResponse
from ...services.album_service import AlbumService
from ...services.exceptions import NotFound, ProviderUnavailable

router = APIRouter(tags=["albums"], prefix="/albums")

_client: Optional[DeezerClient] = None
_adapter: Optional[DeezerAdapter] = None
_album_service: Optional[AlbumService] = None


def _get_album_service() -> AlbumService:
    global _album_service, _adapter, _client
    if _album_service is None:
        settings = get_settings()
        _client = DeezerClient(settings.deezer_api_base_url)
        _adapter = DeezerAdapter(_client)
        _album_service = AlbumService(_adapter)
    return _album_service


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


@router.get("/{id}", response_model=ResponseEnvelope[AlbumDetailsResponse])
async def get_album(
    id: str = Path(..., description="AURA album ID from GET /search"),
) -> ResponseEnvelope[AlbumDetailsResponse] | JSONResponse:
    try:
        service = _get_album_service()
        album, tracks = await service.get_album_details(id)

        track_count = album.metadata.get("nb_tracks")
        if track_count is not None:
            try:
                track_count = int(track_count)
            except (TypeError, ValueError):
                track_count = None

        response = AlbumDetailsResponse(
            id=build_aura_id("album", album.provider_name, album.provider_id),
            title=album.display_title,
            primary_artist_name=album.artist.display_name if album.artist else "Unknown Artist",
            cover_uri=album.metadata.get("cover_medium") or album.metadata.get("cover"),
            release_date=album.metadata.get("release_date"),
            track_count=track_count if track_count is not None else len(tracks),
            tracks=[_to_track_summary(track) for track in tracks],
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
