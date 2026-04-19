"""
Deezer HTTP client for direct API communication.

Handles HTTP requests, error handling, and response parsing.
"""

from typing import Dict, Any, List, Optional
import httpx
import logging

from .exceptions import (
    DeezerNotFound,
    DeezerRateLimited,
    DeezerTimeout,
    DeezerNetworkError,
    DeezerProviderUnavailable,
    DeezerParseError,
)

logger = logging.getLogger(__name__)


class DeezerClient:
    """
    Async HTTP client for Deezer API.
    
    Handles:
    - HTTP requests with proper error handling
    - Rate limit detection and retryable errors
    - Response parsing and validation
    """

    def __init__(self, base_url: str, timeout: int = 10):
        """
        Initialize Deezer client.
        
        Args:
            base_url: Deezer API base URL (e.g., https://api.deezer.com)
            timeout: Request timeout in seconds
        """
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    async def search(
        self,
        query: str,
        types: Optional[List[str]] = None,
        limit: int = 100,
    ) -> Dict[str, Any]:
        """
        Search Deezer for tracks, artists, albums.
        
        Args:
            query: Search query string
            types: List of resource types to search (default: track, artist, album)
            limit: Maximum results per type
            
        Returns:
            Parsed Deezer search response
            
        Raises:
            DeezerError subclasses
        """
        if types is None:
            types = ["track", "artist", "album"]

        url = f"{self.base_url}/search"
        params = {
            "q": query,
            "limit": limit,
            "strict": "off",
        }

        try:
            async with httpx.AsyncClient() as client:
                resp = await client.get(url, params=params, timeout=self.timeout)
                return self._handle_response(resp)
        except httpx.TimeoutException as e:
            logger.warning(f"Deezer timeout for query '{query}'")
            raise DeezerTimeout(f"Deezer search timeout: {e}") from e
        except httpx.NetworkError as e:
            logger.warning(f"Deezer network error for query '{query}'")
            raise DeezerNetworkError(f"Deezer network error: {e}") from e

    async def get_artist(self, artist_id: str) -> Dict[str, Any]:
        """
        Get artist details from Deezer.
        
        Args:
            artist_id: Deezer artist ID
            
        Returns:
            Artist object
            
        Raises:
            DeezerError subclasses
        """
        url = f"{self.base_url}/artist/{artist_id}"
        return await self._get(url, f"artist {artist_id}")

    async def get_artist_top_tracks(
        self,
        artist_id: str,
        limit: int = 50,
    ) -> List[Dict[str, Any]]:
        """
        Get top tracks for artist from Deezer.
        
        Args:
            artist_id: Deezer artist ID
            limit: Maximum results
            
        Returns:
            List of track objects
            
        Raises:
            DeezerError subclasses
        """
        url = f"{self.base_url}/artist/{artist_id}/top"
        params = {"limit": limit}
        try:
            async with httpx.AsyncClient() as client:
                resp = await client.get(url, params=params, timeout=self.timeout)
                data = self._handle_response(resp)
                return data.get("data", [])
        except httpx.TimeoutException as e:
            raise DeezerTimeout(f"Deezer get top tracks timeout: {e}") from e
        except httpx.NetworkError as e:
            raise DeezerNetworkError(f"Deezer network error: {e}") from e

    async def get_artist_albums(
        self,
        artist_id: str,
        limit: int = 50,
    ) -> List[Dict[str, Any]]:
        """
        Get albums for artist from Deezer.

        Args:
            artist_id: Deezer artist ID
            limit: Maximum results

        Returns:
            List of album objects

        Raises:
            DeezerError subclasses
        """
        url = f"{self.base_url}/artist/{artist_id}/albums"
        params = {"limit": limit}
        try:
            async with httpx.AsyncClient() as client:
                resp = await client.get(url, params=params, timeout=self.timeout)
                data = self._handle_response(resp)
                return data.get("data", [])
        except httpx.TimeoutException as e:
            raise DeezerTimeout(f"Deezer get artist albums timeout: {e}") from e
        except httpx.NetworkError as e:
            raise DeezerNetworkError(f"Deezer network error: {e}") from e

    async def get_album(self, album_id: str) -> Dict[str, Any]:
        """
        Get album details from Deezer.
        
        Args:
            album_id: Deezer album ID
            
        Returns:
            Album object
            
        Raises:
            DeezerError subclasses
        """
        url = f"{self.base_url}/album/{album_id}"
        return await self._get(url, f"album {album_id}")

    async def get_album_tracks(
        self,
        album_id: str,
        limit: int = 100,
    ) -> List[Dict[str, Any]]:
        """
        Get tracks for album from Deezer.
        
        Args:
            album_id: Deezer album ID
            limit: Maximum results
            
        Returns:
            List of track objects
            
        Raises:
            DeezerError subclasses
        """
        url = f"{self.base_url}/album/{album_id}/tracks"
        params = {"limit": limit}
        try:
            async with httpx.AsyncClient() as client:
                resp = await client.get(url, params=params, timeout=self.timeout)
                data = self._handle_response(resp)
                return data.get("data", [])
        except httpx.TimeoutException as e:
            raise DeezerTimeout(f"Deezer get album tracks timeout: {e}") from e
        except httpx.NetworkError as e:
            raise DeezerNetworkError(f"Deezer network error: {e}") from e

    async def get_track(self, track_id: str) -> Dict[str, Any]:
        """
        Get track details from Deezer.
        
        Args:
            track_id: Deezer track ID
            
        Returns:
            Track object
            
        Raises:
            DeezerError subclasses
        """
        url = f"{self.base_url}/track/{track_id}"
        return await self._get(url, f"track {track_id}")

    async def _get(self, url: str, resource_label: str) -> Dict[str, Any]:
        """
        Helper method for GET requests.
        
        Args:
            url: Full URL to request
            resource_label: Label for logging/errors
            
        Returns:
            Parsed response
            
        Raises:
            DeezerError subclasses
        """
        try:
            async with httpx.AsyncClient() as client:
                resp = await client.get(url, timeout=self.timeout)
                return self._handle_response(resp)
        except httpx.TimeoutException as e:
            logger.warning(f"Deezer timeout for {resource_label}")
            raise DeezerTimeout(f"Deezer {resource_label} timeout: {e}") from e
        except httpx.NetworkError as e:
            logger.warning(f"Deezer network error for {resource_label}")
            raise DeezerNetworkError(f"Deezer network error: {e}") from e

    def _handle_response(self, response: httpx.Response) -> Dict[str, Any]:
        """
        Handle HTTP response from Deezer API.
        
        Args:
            response: httpx.Response object
            
        Returns:
            Parsed JSON response
            
        Raises:
            DeezerError subclasses
        """
        if response.status_code == 404:
            raise DeezerNotFound("Resource not found on Deezer")
        elif response.status_code == 429:
            logger.warning("Deezer rate limit exceeded")
            raise DeezerRateLimited("Deezer rate limit exceeded")
        elif response.status_code >= 500:
            logger.error(f"Deezer server error: {response.status_code}")
            raise DeezerProviderUnavailable(f"Deezer server error: {response.status_code}")
        elif response.status_code >= 400:
            logger.error(f"Deezer client error: {response.status_code}")
            raise DeezerProviderUnavailable(f"Deezer client error: {response.status_code}")

        try:
            return response.json()
        except Exception as e:
            logger.error(f"Deezer response parse error: {e}")
            raise DeezerParseError(f"Failed to parse Deezer response: {e}") from e
