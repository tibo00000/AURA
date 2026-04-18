"""
Artist service for AURA backend.

Handles artist detail requests by orchestrating provider adapters.
"""

import logging

from ..domain.models import ProviderArtist, ProviderTrack, List
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


class ArtistService:
    """
    Service for retrieving artist details.
    
    For now, fetches from Deezer directly. Future versions will:
    - Look up AURA ID in catalog database
    - Resolve AURA → provider ID via track_source_links
    - Cache artist details
    - Enrich with local metadata
    """

    def __init__(self, deezer_adapter: DeezerAdapter):
        """
        Initialize artist service.
        
        Args:
            deezer_adapter: DeezerAdapter instance
        """
        self.deezer_adapter = deezer_adapter

    async def get_artist_details(
        self,
        artist_id: str,
    ) -> tuple[ProviderArtist, List[ProviderTrack]]:
        """
        Get artist details and top tracks.
        
        Args:
            artist_id: Artist ID (for now, assumed to be provider ID)
            
        Returns:
            Tuple of (ProviderArtist, list of ProviderTrack)
            
        Raises:
            NotFound: Artist not found
            ProviderUnavailable: Provider error
        """
        if not artist_id or not artist_id.strip():
            raise NotFound("Artist ID required")

        artist_id = artist_id.strip()

        try:
            artist, tracks = await self.deezer_adapter.get_artist(artist_id)
            return artist, tracks
        except DeezerNotFound as e:
            logger.info(f"Artist {artist_id} not found")
            raise NotFound(f"Artist not found") from e
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
