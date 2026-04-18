"""
Album service for AURA backend.

Handles album detail requests by orchestrating provider adapters.
"""

import logging

from ..domain.models import ProviderAlbum, ProviderTrack, List
from ..providers.deezer.adapter import DeezerAdapter
from ..providers.deezer.exceptions import (
    DeezerError,
    DeezerNotFound,
    DeezerTimeout,
    DeezerRateLimited,
    DeezerProviderUnavailable,
)
from .exceptions import NotFound, ProviderUnavailable

logger = logging.getLogger(__name__)


class AlbumService:
    """
    Service for retrieving album details.
    
    For now, fetches from Deezer directly. Future versions will:
    - Look up AURA ID in catalog database
    - Resolve AURA → provider ID via track_source_links
    - Cache album details
    - Enrich with local metadata
    """

    def __init__(self, deezer_adapter: DeezerAdapter):
        """
        Initialize album service.
        
        Args:
            deezer_adapter: DeezerAdapter instance
        """
        self.deezer_adapter = deezer_adapter

    async def get_album_details(
        self,
        album_id: str,
    ) -> tuple[ProviderAlbum, List[ProviderTrack]]:
        """
        Get album details and tracks.
        
        Args:
            album_id: Album ID (for now, assumed to be provider ID)
            
        Returns:
            Tuple of (ProviderAlbum, list of ProviderTrack)
            
        Raises:
            NotFound: Album not found
            ProviderUnavailable: Provider error
        """
        if not album_id or not album_id.strip():
            raise NotFound("Album ID required")

        album_id = album_id.strip()

        try:
            album, tracks = await self.deezer_adapter.get_album(album_id)
            return album, tracks
        except DeezerNotFound as e:
            logger.info(f"Album {album_id} not found")
            raise NotFound(f"Album not found") from e
        except DeezerRateLimited as e:
            logger.warning(f"Deezer rate limited: {e}")
            raise ProviderUnavailable("Provider rate limited, please retry in a moment") from e
        except DeezerTimeout as e:
            logger.warning(f"Deezer timeout: {e}")
            raise ProviderUnavailable("Provider timeout, please retry") from e
        except DeezerProviderUnavailable as e:
            logger.error(f"Deezer unavailable: {e}")
            raise ProviderUnavailable("Provider unavailable") from e
        except DeezerError as e:
            logger.error(f"Deezer error: {e}")
            raise ProviderUnavailable("Provider error") from e
