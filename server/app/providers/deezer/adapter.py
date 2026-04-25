"""
Deezer provider adapter.

Transforms Deezer API responses into canonical domain models.
Handles:
- Response parsing and validation
- Transformation to ProviderTrack/ProviderArtist/ProviderAlbum
- Error mapping to service-level exceptions
"""

import asyncio
import logging
import re
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

    @staticmethod
    def _normalize_search_text(value: Optional[str]) -> str:
        if not value:
            return ""
        return re.sub(r"\s+", " ", value.strip().lower())

    @classmethod
    def _score_text_match(cls, query: str, candidate: Optional[str]) -> int:
        normalized_query = cls._normalize_search_text(query)
        normalized_candidate = cls._normalize_search_text(candidate)

        if not normalized_query or not normalized_candidate:
            return 0
        if normalized_candidate == normalized_query:
            return 100
        if normalized_candidate.startswith(normalized_query):
            return 85
        if normalized_query in normalized_candidate:
            return 72

        query_tokens = set(normalized_query.split(" "))
        candidate_tokens = set(normalized_candidate.split(" "))
        common_tokens = query_tokens & candidate_tokens
        if not common_tokens:
            return 0

        return min(60, 28 + len(common_tokens) * 12)

    @classmethod
    def _score_artist_candidate(cls, query: str, artist: ProviderArtist) -> int:
        return cls._score_text_match(query, artist.display_name)

    @classmethod
    def _score_album_candidate(cls, query: str, album: ProviderAlbum) -> int:
        score = cls._score_text_match(query, album.display_title)
        if album.artist is not None:
            score += cls._score_text_match(query, album.artist.display_name) // 5
        return score

    @classmethod
    def _score_track_candidate(cls, query: str, track: ProviderTrack) -> int:
        score = cls._score_text_match(query, track.display_title)
        if track.artist is not None:
            score += cls._score_text_match(query, track.artist.display_name) // 4
        if track.album is not None:
            score += cls._score_text_match(query, track.album.display_title) // 5
        return score

    @classmethod
    def _pick_best_match(cls, query: str, result: SearchResult):
        best_score = -1
        best_candidate = None

        for artist in result.artists:
            score = cls._score_artist_candidate(query, artist)
            if score > best_score:
                best_score = score
                best_candidate = artist

        for album in result.albums:
            score = cls._score_album_candidate(query, album)
            if score > best_score:
                best_score = score
                best_candidate = album

        for track in result.tracks:
            score = cls._score_track_candidate(query, track)
            if score > best_score:
                best_score = score
                best_candidate = track

        return best_candidate

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
        result = SearchResult()

        try:
            tracks_response, artists_response, albums_response = await asyncio.gather(
                self.client.search(query, resource_type="track", limit=limit_tracks),
                self.client.search(query, resource_type="artist", limit=limit_artists),
                self.client.search(query, resource_type="album", limit=limit_albums),
            )
        except DeezerNotFound:
            # No results is not an error
            return result
        except DeezerError as e:
            logger.error(f"Deezer search error: {e}")
            raise

        for track_data in tracks_response.get("data", [])[:limit_tracks]:
            try:
                provider_track = self._parse_track(track_data)
                result.tracks.append(provider_track)
                if result.best_match is None:
                    result.best_match = provider_track
            except Exception as e:
                logger.warning(f"Failed to parse track: {e}")
                continue

        for artist_data in artists_response.get("data", [])[:limit_artists]:
            try:
                provider_artist = self._parse_artist(artist_data)
                result.artists.append(provider_artist)
                if result.best_match is None:
                    result.best_match = provider_artist
            except Exception as e:
                logger.warning(f"Failed to parse artist: {e}")
                continue

        for album_data in albums_response.get("data", [])[:limit_albums]:
            try:
                provider_album = self._parse_album(album_data)
                result.albums.append(provider_album)
            except Exception as e:
                logger.warning(f"Failed to parse album: {e}")
                continue

        result.best_match = self._pick_best_match(query, result)
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
