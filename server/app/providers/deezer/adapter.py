"""
Deezer provider adapter.

Transforms Deezer API responses into canonical domain models.
Handles:
- Response parsing and validation
- Transformation to ProviderTrack/ProviderArtist/ProviderAlbum
- Error mapping to service-level exceptions
"""

import logging
from typing import List, Optional, Dict, Any
from .client import DeezerClient
from .exceptions import (
    DeezerError,
    DeezerNotFound,
    DeezerProviderUnavailable,
)
from ...domain.models import (
    ProviderTrack,
    ProviderArtist,
    ProviderAlbum,
    SearchResult,
)

logger = logging.getLogger(__name__)


class DeezerAdapter:
    """
    Adapter for Deezer provider.
    
    Encapsulates Deezer-specific logic and transforms responses into
    domain models for use by services.
    """

    def __init__(self, client: DeezerClient):
        """
        Initialize Deezer adapter.
        
        Args:
            client: DeezerClient instance for API communication
        """
        self.client = client

    async def search(
        self,
        query: str,
        limit_tracks: int = 20,
        limit_artists: int = 10,
        limit_albums: int = 10,
    ) -> SearchResult:
        """
        Search Deezer for tracks, artists, albums.
        
        Args:
            query: Search query string
            limit_tracks: Maximum tracks to return
            limit_artists: Maximum artists to return
            limit_albums: Maximum albums to return
            
        Returns:
            SearchResult with categorized results and best match
            
        Raises:
            Raises domain exceptions (not DeezerError directly)
        """
        try:
            deezer_response = await self.client.search(query, limit=max(limit_tracks, limit_artists, limit_albums))
        except DeezerNotFound:
            # No results is not an error
            return SearchResult()
        except DeezerError as e:
            logger.error(f"Deezer search error: {e}")
            raise

        # Parse response
        result = SearchResult()

        # Extract tracks
        if "data" in deezer_response:
            for track_data in deezer_response["data"][:limit_tracks]:
                try:
                    provider_track = self._parse_track(track_data)
                    result.tracks.append(provider_track)
                    if result.best_match is None:
                        result.best_match = provider_track
                except Exception as e:
                    logger.warning(f"Failed to parse track: {e}")
                    continue

        return result

    async def get_artist(self, artist_id: str) -> tuple[ProviderArtist, List[ProviderTrack], List[ProviderAlbum]]:
        """
        Get artist details and top tracks.
        
        Args:
            artist_id: Deezer artist ID
            
        Returns:
            Tuple of (ProviderArtist, list of ProviderTrack)
            
        Raises:
            DeezerError
        """
        try:
            artist_data = await self.client.get_artist(artist_id)
            top_tracks_data = await self.client.get_artist_top_tracks(artist_id)
            albums_data = await self.client.get_artist_albums(artist_id)
        except DeezerError as e:
            logger.error(f"Deezer get artist error: {e}")
            raise

        artist = self._parse_artist(artist_data)
        tracks = []
        for track_data in top_tracks_data:
            try:
                track = self._parse_track(track_data)
                tracks.append(track)
            except Exception as e:
                logger.warning(f"Failed to parse track: {e}")
                continue

        albums = []
        for album_data in albums_data:
            try:
                album = self._parse_album(album_data)
                albums.append(album)
            except Exception as e:
                logger.warning(f"Failed to parse album: {e}")
                continue

        return artist, tracks, albums

    async def get_album(self, album_id: str) -> tuple[ProviderAlbum, List[ProviderTrack]]:
        """
        Get album details and tracks.
        
        Args:
            album_id: Deezer album ID
            
        Returns:
            Tuple of (ProviderAlbum, list of ProviderTrack)
            
        Raises:
            DeezerError
        """
        try:
            album_data = await self.client.get_album(album_id)
            tracks_data = await self.client.get_album_tracks(album_id)
        except DeezerError as e:
            logger.error(f"Deezer get album error: {e}")
            raise

        album = self._parse_album(album_data)
        tracks = []
        for track_data in tracks_data:
            try:
                track = self._parse_track(track_data)
                tracks.append(track)
            except Exception as e:
                logger.warning(f"Failed to parse track: {e}")
                continue

        return album, tracks

    def _parse_artist(self, data: Dict[str, Any]) -> ProviderArtist:
        """Parse Deezer artist object to ProviderArtist."""
        return ProviderArtist(
            provider_name="deezer",
            provider_id=str(data.get("id", "")),
            display_name=data.get("name", "Unknown Artist"),
            metadata=data,
        )

    def _parse_album(self, data: Dict[str, Any]) -> ProviderAlbum:
        """Parse Deezer album object to ProviderAlbum."""
        artist_data = data.get("artist")
        artist = None
        if artist_data:
            artist = self._parse_artist(artist_data)

        return ProviderAlbum(
            provider_name="deezer",
            provider_id=str(data.get("id", "")),
            display_title=data.get("title", "Unknown Album"),
            artist=artist,
            metadata=data,
        )

    def _parse_track(self, data: Dict[str, Any]) -> ProviderTrack:
        """Parse Deezer track object to ProviderTrack."""
        album_data = data.get("album")
        album = None
        if album_data:
            album = self._parse_album(album_data)

        artist_data = data.get("artist")
        artist = None
        if artist_data:
            artist = self._parse_artist(artist_data)

        return ProviderTrack(
            provider_name="deezer",
            provider_id=str(data.get("id", "")),
            display_title=data.get("title", "Unknown Track"),
            album=album,
            artist=artist,
            duration_ms=data.get("duration") and int(data.get("duration")) * 1000 or None,
            metadata=data,
        )
