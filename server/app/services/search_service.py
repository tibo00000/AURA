"""
Search service for AURA backend.

Handles search requests by orchestrating provider adapters and database queries.
"""

import logging
from typing import Optional

from ..domain.models import SearchResult
from ..providers.deezer.adapter import DeezerAdapter
from ..providers.deezer.exceptions import (
    DeezerError,
    DeezerTimeout,
    DeezerRateLimited,
    DeezerProviderUnavailable,
)
from .exceptions import BadRequest, ProviderUnavailable

logger = logging.getLogger(__name__)


class SearchService:
    """
    Service for searching music across providers.
    
    For now, searches only Deezer. Future versions will:
    - Cache results in database
    - Deduplicate across providers
    - Fallback to local catalog for offline
    """

    def __init__(self, deezer_adapter: DeezerAdapter):
        """
        Initialize search service.
        
        Args:
            deezer_adapter: DeezerAdapter instance
        """
        self.deezer_adapter = deezer_adapter

    async def search(
        self,
        query: str,
        limit_tracks: int = 20,
        limit_artists: int = 10,
        limit_albums: int = 10,
    ) -> SearchResult:
        """
        Search for tracks, artists, albums.
        
        Args:
            query: Search query string (must be at least 3 characters)
            limit_tracks: Maximum tracks to return
            limit_artists: Maximum artists to return
            limit_albums: Maximum albums to return
            
        Returns:
            SearchResult with categorized results
            
        Raises:
            BadRequest: Query validation failed
            ProviderUnavailable: All providers failed
        """
        # Validate query
        if not query or len(query.strip()) < 3:
            raise BadRequest("Search query must be at least 3 characters")

        query = query.strip()

        # Search Deezer
        try:
            result = await self.deezer_adapter.search(
                query,
                limit_tracks=limit_tracks,
                limit_artists=limit_artists,
                limit_albums=limit_albums,
            )
            return result
        except DeezerRateLimited as e:
            logger.warning(f"Deezer rate limited: {e}")
            raise ProviderUnavailable("Search provider rate limited, please retry in a moment") from e
        except DeezerTimeout as e:
            logger.warning(f"Deezer timeout: {e}")
            raise ProviderUnavailable("Search provider timeout, please retry") from e
        except DeezerProviderUnavailable as e:
            logger.error(f"Deezer unavailable: {e}")
            raise ProviderUnavailable("Search provider unavailable") from e
        except DeezerError as e:
            logger.error(f"Deezer error: {e}")
            raise ProviderUnavailable("Search provider error") from e
