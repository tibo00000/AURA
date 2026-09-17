"""
Deezer HTTP client for direct API communication.

Handles HTTP requests, error handling, and response parsing.
Includes discovery endpoints (related artists, radio, editorial, charts, genres)
with exponential backoff retry on rate limiting (429).
"""

from typing import Dict, Any, List, Optional
import asyncio
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
    - Exponential backoff retry on 429 (for discovery endpoints)
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

    # -----------------------------------------------------------------------
    # Existing endpoints (search, artist, album, track)
    # -----------------------------------------------------------------------

    async def search(
        self,
        query: str,
        resource_type: str = "track",
        limit: int = 100,
    ) -> Dict[str, Any]:
        """
        Search Deezer for tracks, artists, albums.
        
        Args:
            query: Search query string
            resource_type: Resource type to search (`track`, `artist`, or `album`)
            limit: Maximum results per type
            
        Returns:
            Parsed Deezer search response
            
        Raises:
            DeezerError subclasses
        """
        normalized_resource_type = resource_type.strip().lower()
        if normalized_resource_type not in {"track", "artist", "album"}:
            raise ValueError(f"Unsupported Deezer search resource type: {resource_type}")

        url = (
            f"{self.base_url}/search"
            if normalized_resource_type == "track"
            else f"{self.base_url}/search/{normalized_resource_type}"
        )
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

    # -----------------------------------------------------------------------
    # Discovery endpoints (new — with retry on 429)
    # -----------------------------------------------------------------------

    async def get_artist_related(
        self,
        artist_id: str,
        limit: int = 25,
    ) -> List[Dict[str, Any]]:
        """
        Get related/similar artists from Deezer (collaborative filtering).
        
        GET /artist/{id}/related — No auth required.
        Returns artists that fans of the given artist also listen to.
        
        Args:
            artist_id: Deezer artist ID
            limit: Maximum results (default 25, max 100)
            
        Returns:
            List of artist objects with id, name, picture_*, nb_fan, etc.
        """
        url = f"{self.base_url}/artist/{artist_id}/related"
        params = {"limit": limit}
        data = await self._get_with_retry(url, f"artist {artist_id} related", params)
        return data.get("data", [])

    async def get_artist_radio(
        self,
        artist_id: str,
    ) -> List[Dict[str, Any]]:
        """
        Get algorithmic radio for an artist from Deezer.
        
        GET /artist/{id}/radio — No auth required.
        Returns ~25 tracks of the artist AND stylistically similar artists.
        This is Deezer's built-in recommendation engine for any artist.
        
        Args:
            artist_id: Deezer artist ID
            
        Returns:
            List of track objects (~25 tracks)
        """
        url = f"{self.base_url}/artist/{artist_id}/radio"
        data = await self._get_with_retry(url, f"artist {artist_id} radio")
        return data.get("data", [])

    async def get_editorial_releases(
        self,
        genre_id: int = 0,
        limit: int = 50,
    ) -> List[Dict[str, Any]]:
        """
        Get new/recent releases from Deezer editorial.
        
        GET /editorial/{genre_id}/releases — No auth required.
        genre_id=0 means all genres (global).
        
        Args:
            genre_id: Deezer genre ID (0 = all genres)
            limit: Maximum results
            
        Returns:
            List of album objects with release_date, record_type, artist, etc.
        """
        url = f"{self.base_url}/editorial/{genre_id}/releases"
        params = {"limit": limit}
        data = await self._get_with_retry(url, f"editorial {genre_id} releases", params)
        return data.get("data", [])

    async def get_chart_tracks(
        self,
        genre_id: int = 0,
        limit: int = 50,
    ) -> List[Dict[str, Any]]:
        """
        Get top trending tracks from Deezer charts.
        
        GET /chart/{genre_id}/tracks — No auth required.
        genre_id=0 means global chart.
        
        Args:
            genre_id: Deezer genre ID (0 = global)
            limit: Maximum results
            
        Returns:
            List of track objects with position, artist, album, etc.
        """
        url = f"{self.base_url}/chart/{genre_id}/tracks"
        params = {"limit": limit}
        data = await self._get_with_retry(url, f"chart {genre_id} tracks", params)
        return data.get("data", [])

    async def get_genres(self) -> List[Dict[str, Any]]:
        """
        Get list of all Deezer genres.
        
        GET /genre — No auth required.
        
        Returns:
            List of genre objects with id, name, picture, etc.
        """
        url = f"{self.base_url}/genre"
        data = await self._get_with_retry(url, "genres")
        return data.get("data", [])

    # -----------------------------------------------------------------------
    # Internal helpers
    # -----------------------------------------------------------------------

    async def _get(self, url: str, resource_label: str) -> Dict[str, Any]:
        """
        Helper method for GET requests (no retry).
        
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

    async def _get_with_retry(
        self,
        url: str,
        resource_label: str,
        params: Optional[Dict[str, Any]] = None,
        max_retries: int = 3,
    ) -> Dict[str, Any]:
        """
        GET request with exponential backoff retry on 429 (rate limit).
        
        Used by discovery endpoints which may generate bursts of requests.
        Retries on DeezerRateLimited with delays of 1s, 2s, 4s.
        
        Args:
            url: Full URL to request
            resource_label: Label for logging/errors
            params: Optional query parameters
            max_retries: Maximum retry attempts on 429
            
        Returns:
            Parsed response
            
        Raises:
            DeezerError subclasses (after exhausting retries)
        """
        for attempt in range(max_retries + 1):
            try:
                async with httpx.AsyncClient() as client:
                    resp = await client.get(
                        url, params=params, timeout=self.timeout
                    )
                    return self._handle_response(resp)
            except DeezerRateLimited:
                if attempt == max_retries:
                    raise
                wait = 2 ** attempt  # 1s, 2s, 4s
                logger.warning(
                    "Deezer 429 on %s, retry %d/%d in %ds",
                    resource_label, attempt + 1, max_retries, wait,
                )
                await asyncio.sleep(wait)
            except httpx.TimeoutException as e:
                logger.warning(f"Deezer timeout for {resource_label}")
                raise DeezerTimeout(f"Deezer {resource_label} timeout: {e}") from e
            except httpx.NetworkError as e:
                logger.warning(f"Deezer network error for {resource_label}")
                raise DeezerNetworkError(f"Deezer network error: {e}") from e
        # Should never reach here, but satisfy type checker
        raise DeezerProviderUnavailable(f"Exhausted retries for {resource_label}")

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
            data = response.json()
        except Exception as e:
            logger.error(f"Deezer response parse error: {e}")
            raise DeezerParseError(f"Failed to parse Deezer response: {e}") from e

        # Deezer sometimes returns 200 with an error object in the JSON body
        if isinstance(data, dict) and "error" in data:
            error = data["error"]
            error_code = error.get("code", 0)
            error_msg = error.get("message", "Unknown Deezer error")
            if error_code == 4:  # Quota / rate limit
                raise DeezerRateLimited(f"Deezer quota exceeded: {error_msg}")
            elif error_code == 800:  # Not found
                raise DeezerNotFound(f"Deezer resource not found: {error_msg}")
            else:
                raise DeezerProviderUnavailable(f"Deezer API error {error_code}: {error_msg}")

        return data
