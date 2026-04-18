"""
Artist endpoints for AURA API.

GET /artists/{id}
"""

from fastapi import APIRouter, HTTPException, Path
from typing import Optional

from ...schemas.responses import ResponseEnvelope, ArtistDetailsResponse, ArtistResponse, TrackResponse, AlbumResponse, ErrorDetails
from ...services.artist_service import ArtistService
from ...services.exceptions import NotFound, ProviderUnavailable
from ...providers.deezer.adapter import DeezerAdapter
from ...providers.deezer.client import DeezerClient
from ...config import get_settings

router = APIRouter(tags=["artists"], prefix="/artists")

# Initialize services
_client: Optional[DeezerClient] = None
_adapter: Optional[DeezerAdapter] = None
_artist_service: Optional[ArtistService] = None


def _get_artist_service() -> ArtistService:
    """Lazy-load artist service (dependency injection)."""
    global _artist_service, _adapter, _client
    if _artist_service is None:
        settings = get_settings()
        _client = DeezerClient(settings.deezer_api_base_url)
        _adapter = DeezerAdapter(_client)
        _artist_service = ArtistService(_adapter)
    return _artist_service


@router.get("/{id}", response_model=ResponseEnvelope[ArtistDetailsResponse])
async def get_artist(
    id: str = Path(..., description="AURA or provider artist ID"),
) -> ResponseEnvelope[ArtistDetailsResponse]:
    """
    Get artist details and top tracks.
    
    Path parameters:
    - id: Artist ID (AURA or provider ID)
    """
    try:
        service = _get_artist_service()
        artist, top_tracks = await service.get_artist_details(id)
        
        # Transform to response schemas
        tracks = [
            TrackResponse(
                id=t.provider_id,
                title=t.display_title,
                album=AlbumResponse(
                    id=t.album.provider_id,
                    title=t.album.display_title,
                ) if t.album else None,
                artist=ArtistResponse(
                    id=t.artist.provider_id,
                    name=t.artist.display_name,
                ) if t.artist else None,
                duration_ms=t.duration_ms,
            )
            for t in top_tracks
        ]
        
        artist_response = ArtistResponse(
            id=artist.provider_id,
            name=artist.display_name,
        )
        
        details_response = ArtistDetailsResponse(
            artist=artist_response,
            top_tracks=tracks,
        )
        
        return ResponseEnvelope(data=details_response)
    
    except NotFound as e:
        error = ErrorDetails(code="not_found", message=str(e), retryable=False)
        raise HTTPException(status_code=404, detail={"error": error})
    except ProviderUnavailable as e:
        error = ErrorDetails(code="provider_unavailable", message=str(e), retryable=True)
        raise HTTPException(status_code=503, detail={"error": error})
