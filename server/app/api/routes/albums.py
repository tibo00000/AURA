"""
Album endpoints for AURA API.

GET /albums/{id}
"""

from fastapi import APIRouter, HTTPException, Path
from typing import Optional

from ...schemas.responses import ResponseEnvelope, AlbumDetailsResponse, AlbumResponse, TrackResponse, ArtistResponse, ErrorDetails
from ...services.album_service import AlbumService
from ...services.exceptions import NotFound, ProviderUnavailable
from ...providers.deezer.adapter import DeezerAdapter
from ...providers.deezer.client import DeezerClient
from ...config import get_settings

router = APIRouter(tags=["albums"], prefix="/albums")

# Initialize services
_client: Optional[DeezerClient] = None
_adapter: Optional[DeezerAdapter] = None
_album_service: Optional[AlbumService] = None


def _get_album_service() -> AlbumService:
    """Lazy-load album service (dependency injection)."""
    global _album_service, _adapter, _client
    if _album_service is None:
        settings = get_settings()
        _client = DeezerClient(settings.deezer_api_base_url)
        _adapter = DeezerAdapter(_client)
        _album_service = AlbumService(_adapter)
    return _album_service


@router.get("/{id}", response_model=ResponseEnvelope[AlbumDetailsResponse])
async def get_album(
    id: str = Path(..., description="AURA or provider album ID"),
) -> ResponseEnvelope[AlbumDetailsResponse]:
    """
    Get album details and tracks.
    
    Path parameters:
    - id: Album ID (AURA or provider ID)
    """
    try:
        service = _get_album_service()
        album, tracks = await service.get_album_details(id)
        
        # Transform to response schemas
        track_responses = [
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
            for t in tracks
        ]
        
        album_response = AlbumResponse(
            id=album.provider_id,
            title=album.display_title,
            artist=ArtistResponse(
                id=album.artist.provider_id,
                name=album.artist.display_name,
            ) if album.artist else None,
        )
        
        details_response = AlbumDetailsResponse(
            album=album_response,
            tracks=track_responses,
        )
        
        return ResponseEnvelope(data=details_response)
    
    except NotFound as e:
        error = ErrorDetails(code="not_found", message=str(e), retryable=False)
        raise HTTPException(status_code=404, detail={"error": error})
    except ProviderUnavailable as e:
        error = ErrorDetails(code="provider_unavailable", message=str(e), retryable=True)
        raise HTTPException(status_code=503, detail={"error": error})
