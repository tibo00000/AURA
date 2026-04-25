"""Resolve endpoints for AURA API (SRV-008).

GET /resolve/artist?name=...
GET /resolve/album?title=...&artist_name=...
"""

from typing import Optional

from fastapi import APIRouter, Query
from fastapi.responses import JSONResponse

from ...config import get_settings
from ...providers.deezer.adapter import DeezerAdapter
from ...providers.deezer.client import DeezerClient
from ...schemas.responses import ErrorDetails, ResponseEnvelope, ResolveArtistResponseData, ResolveAlbumResponseData
from ...services.resolve_service import ResolveService
from ...services.exceptions import ProviderUnavailable

router = APIRouter(tags=["resolve"], prefix="/resolve")

_client: Optional[DeezerClient] = None
_adapter: Optional[DeezerAdapter] = None
_resolve_service: Optional[ResolveService] = None


def _get_resolve_service() -> ResolveService:
    global _resolve_service, _adapter, _client
    if _resolve_service is None:
        settings = get_settings()
        _client = DeezerClient(settings.deezer_api_base_url)
        _adapter = DeezerAdapter(_client)
        _resolve_service = ResolveService(_adapter)
    return _resolve_service


@router.get("/artist", response_model=ResponseEnvelope[ResolveArtistResponseData])
async def resolve_artist(
    name: str = Query(..., description="Artist name to resolve"),
) -> ResponseEnvelope[ResolveArtistResponseData] | JSONResponse:
    """
    Resolve a local artist name to an opaque AURA backend ID + enrichment metadata.

    Android persists the returned picture_uri and id in artist_source_links.
    """
    if not name or not name.strip():
        return JSONResponse(
            status_code=400,
            content=ResponseEnvelope(
                error=ErrorDetails(
                    code="invalid_request",
                    message="Query parameter 'name' is required and must not be empty.",
                    retryable=False,
                )
            ).model_dump(mode="json"),
        )
    try:
        service = _get_resolve_service()
        result = await service.resolve_artist(name.strip())
        return ResponseEnvelope(data=result)
    except ProviderUnavailable as exc:
        return JSONResponse(
            status_code=503,
            content=ResponseEnvelope(
                error=ErrorDetails(code="provider_unavailable", message=str(exc), retryable=True),
            ).model_dump(mode="json"),
        )


@router.get("/album", response_model=ResponseEnvelope[ResolveAlbumResponseData])
async def resolve_album(
    title: str = Query(..., description="Album title to resolve"),
    artist_name: Optional[str] = Query(None, description="Artist name hint (strongly recommended)"),
) -> ResponseEnvelope[ResolveAlbumResponseData] | JSONResponse:
    """
    Resolve a local album title to an opaque AURA backend ID + enrichment metadata.

    Android persists the returned cover_uri and id in album_source_links.
    """
    if not title or not title.strip():
        return JSONResponse(
            status_code=400,
            content=ResponseEnvelope(
                error=ErrorDetails(
                    code="invalid_request",
                    message="Query parameter 'title' is required and must not be empty.",
                    retryable=False,
                )
            ).model_dump(mode="json"),
        )
    try:
        service = _get_resolve_service()
        result = await service.resolve_album(title.strip(), artist_name)
        return ResponseEnvelope(data=result)
    except ProviderUnavailable as exc:
        return JSONResponse(
            status_code=503,
            content=ResponseEnvelope(
                error=ErrorDetails(code="provider_unavailable", message=str(exc), retryable=True),
            ).model_dump(mode="json"),
        )
