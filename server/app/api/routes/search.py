"""Search endpoint for AURA API."""

from typing import Optional

from fastapi import APIRouter, Query
from fastapi.responses import JSONResponse

from ...config import get_settings
from ...core.aura_id_codec import build_aura_id
from ...domain.models import ProviderAlbum, ProviderArtist, ProviderTrack
from ...providers.deezer.adapter import DeezerAdapter
from ...providers.deezer.client import DeezerClient
from ...schemas.responses import (
    AlbumSummaryResponse,
    ArtistSummaryResponse,
    ErrorDetails,
    Meta,
    ResponseEnvelope,
    SearchBestMatchResponse,
    SearchResponse,
    TrackSummaryResponse,
)
from ...services.exceptions import BadRequest, ProviderUnavailable
from ...services.search_service import SearchService

router = APIRouter(tags=["search"])

_client: Optional[DeezerClient] = None
_adapter: Optional[DeezerAdapter] = None
_search_service: Optional[SearchService] = None


def _get_search_service() -> SearchService:
    global _search_service, _adapter, _client
    if _search_service is None:
        settings = get_settings()
        _client = DeezerClient(settings.deezer_api_base_url)
        _adapter = DeezerAdapter(_client)
        _search_service = SearchService(_adapter)
    return _search_service


def _to_artist_summary(artist) -> ArtistSummaryResponse:
    return ArtistSummaryResponse(
        id=build_aura_id("artist", artist.provider_name, artist.provider_id),
        name=artist.display_name,
        picture_uri=artist.metadata.get("picture_medium") or artist.metadata.get("picture"),
    )


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
        is_liked=False,
        is_local_available=False,
        is_downloaded_by_aura=False,
    )


def _to_best_match_response(best_match) -> SearchBestMatchResponse | None:
    if isinstance(best_match, ProviderTrack):
        return SearchBestMatchResponse(kind="track", item=_to_track_summary(best_match))
    if isinstance(best_match, ProviderArtist):
        return SearchBestMatchResponse(kind="artist", item=_to_artist_summary(best_match))
    if isinstance(best_match, ProviderAlbum):
        return SearchBestMatchResponse(kind="album", item=_to_album_summary(best_match))
    return None


@router.get("/search", response_model=ResponseEnvelope[SearchResponse])
async def search(
    q: str = Query(..., min_length=3, description="Search query"),
    limit_tracks: int = Query(20, ge=1, le=100, description="Maximum tracks to return"),
    limit_artists: int = Query(10, ge=1, le=100, description="Maximum artists to return"),
    limit_albums: int = Query(10, ge=1, le=100, description="Maximum albums to return"),
) -> ResponseEnvelope[SearchResponse] | JSONResponse:
    try:
        service = _get_search_service()
        result = await service.search(q, limit_tracks, limit_artists, limit_albums)

        response = SearchResponse(
            query=q.strip(),
            best_match=_to_best_match_response(result.best_match),
            tracks=[_to_track_summary(track) for track in result.tracks],
            artists=[_to_artist_summary(artist) for artist in result.artists],
            albums=[_to_album_summary(album) for album in result.albums],
        )
        return ResponseEnvelope(
            data=response,
            meta=Meta(
                partial_failure=False,
                provider_status={"deezer": "ok"},
            ),
        )
    except BadRequest as exc:
        return JSONResponse(
            status_code=400,
            content=ResponseEnvelope(
                error=ErrorDetails(code="invalid_request", message=str(exc), retryable=False),
            ).model_dump(mode="json"),
        )
    except ProviderUnavailable as exc:
        return JSONResponse(
            status_code=503,
            content=ResponseEnvelope(
                error=ErrorDetails(code="provider_unavailable", message=str(exc), retryable=True),
                meta=Meta(partial_failure=False, provider_status={"deezer": "unavailable"}),
            ).model_dump(mode="json"),
        )
