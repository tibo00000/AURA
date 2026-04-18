"""
Search endpoint for AURA API.

GET /search?q=...&limit_tracks=20&limit_artists=10&limit_albums=10
"""

from fastapi import APIRouter, Query, HTTPException
from typing import Optional

from ...schemas.responses import ResponseEnvelope, SearchResponse, TrackResponse, ArtistResponse, AlbumResponse, ErrorDetails
from ...services.search_service import SearchService
from ...services.exceptions import BadRequest, ProviderUnavailable
from ...providers.deezer.adapter import DeezerAdapter
from ...providers.deezer.client import DeezerClient
from ...config import get_settings

router = APIRouter(tags=["search"])

# Initialize services
_client: Optional[DeezerClient] = None
_adapter: Optional[DeezerAdapter] = None
_search_service: Optional[SearchService] = None


def _get_search_service() -> SearchService:
    """Lazy-load search service (dependency injection)."""
    global _search_service, _adapter, _client
    if _search_service is None:
        settings = get_settings()
        _client = DeezerClient(settings.deezer_api_base_url)
        _adapter = DeezerAdapter(_client)
        _search_service = SearchService(_adapter)
    return _search_service


@router.get("/search", response_model=ResponseEnvelope[SearchResponse])
async def search(
    q: str = Query(..., min_length=3, description="Search query"),
    limit_tracks: int = Query(20, ge=1, le=100, description="Maximum tracks to return"),
    limit_artists: int = Query(10, ge=1, le=100, description="Maximum artists to return"),
    limit_albums: int = Query(10, ge=1, le=100, description="Maximum albums to return"),
) -> ResponseEnvelope[SearchResponse]:
    """
    Search for tracks, artists, and albums.
    
    Query parameters:
    - q: Search query (required, minimum 3 characters)
    - limit_tracks: Maximum tracks (default 20)
    - limit_artists: Maximum artists (default 10)
    - limit_albums: Maximum albums (default 10)
    """
    try:
        service = _get_search_service()
        result = await service.search(q, limit_tracks, limit_artists, limit_albums)
        
        # Transform to response schemas
        tracks = [
            TrackResponse(
                id=t.provider_id,
                title=t.display_title,
                album=AlbumResponse(
                    id=t.album.provider_id,
                    title=t.album.display_title,
                    artist=ArtistResponse(
                        id=t.album.artist.provider_id,
                        name=t.album.artist.display_name,
                    ) if t.album and t.album.artist else None,
                ) if t.album else None,
                artist=ArtistResponse(
                    id=t.artist.provider_id,
                    name=t.artist.display_name,
                ) if t.artist else None,
                duration_ms=t.duration_ms,
            )
            for t in result.tracks
        ]
        
        artists = [
            ArtistResponse(
                id=a.provider_id,
                name=a.display_name,
            )
            for a in result.artists
        ]
        
        albums = [
            AlbumResponse(
                id=alb.provider_id,
                title=alb.display_title,
                artist=ArtistResponse(
                    id=alb.artist.provider_id,
                    name=alb.artist.display_name,
                ) if alb.artist else None,
            )
            for alb in result.albums
        ]
        
        best_match = None
        if result.best_match:
            best_match = TrackResponse(
                id=result.best_match.provider_id,
                title=result.best_match.display_title,
                album=AlbumResponse(
                    id=result.best_match.album.provider_id,
                    title=result.best_match.album.display_title,
                    artist=ArtistResponse(
                        id=result.best_match.album.artist.provider_id,
                        name=result.best_match.album.artist.display_name,
                    ) if result.best_match.album and result.best_match.album.artist else None,
                ) if result.best_match.album else None,
                artist=ArtistResponse(
                    id=result.best_match.artist.provider_id,
                    name=result.best_match.artist.display_name,
                ) if result.best_match.artist else None,
                duration_ms=result.best_match.duration_ms,
            )
        
        search_response = SearchResponse(
            best_match=best_match,
            tracks=tracks,
            artists=artists,
            albums=albums,
        )
        
        return ResponseEnvelope(data=search_response)
    
    except BadRequest as e:
        error = ErrorDetails(code="bad_request", message=str(e), retryable=False)
        raise HTTPException(status_code=400, detail={"error": error})
    except ProviderUnavailable as e:
        error = ErrorDetails(code="provider_unavailable", message=str(e), retryable=True)
        raise HTTPException(status_code=503, detail={"error": error})
