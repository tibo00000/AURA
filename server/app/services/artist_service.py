"""
Artist service for AURA backend.

Handles artist detail requests by orchestrating provider adapters.
"""

import logging
from typing import List

from ..core.aura_id_codec import parse_aura_id
from ..domain.models import ProviderArtist, ProviderTrack, ProviderAlbum
from ..providers.deezer.adapter import DeezerAdapter
from ..providers.deezer.exceptions import (
    DeezerError,
    DeezerNotFound,
    DeezerProviderUnavailable,
    DeezerRateLimited,
    DeezerTimeout,
)
from .exceptions import NotFound, ProviderUnavailable

logger = logging.getLogger(__name__)


class ArtistService:
    """
    Service for retrieving artist details.

    For SRV-002, artist IDs from `/search` are opaque backend IDs resolved
    statelessly to provider references. SRV-004 will replace this with a
    persisted catalog lookup backed by Postgres mappings.
    """

    def __init__(self, deezer_adapter: DeezerAdapter):
        self.deezer_adapter = deezer_adapter

    async def get_artist_details(
        self,
        artist_id: str,
    ) -> tuple[ProviderArtist, List[ProviderTrack], List[ProviderAlbum]]:
        """
        Get artist details, top tracks, and album list.

        Args:
            artist_id: AURA artist ID from `/search`, or raw provider ID as fallback
        """
        if not artist_id or not artist_id.strip():
            raise NotFound("Artist ID required")

        resolved_artist_id = self._resolve_provider_artist_id(artist_id.strip())

        try:
            artist, tracks, albums = await self.deezer_adapter.get_artist(resolved_artist_id)
            return artist, tracks, albums
        except DeezerNotFound as e:
            logger.info("Artist %s not found", artist_id)
            raise NotFound("Artist not found") from e
        except DeezerRateLimited as e:
            logger.warning("Deezer rate limited: %s", e)
            raise ProviderUnavailable("Provider rate limited, please retry in a moment") from e
        except DeezerTimeout as e:
            logger.warning("Deezer timeout: %s", e)
            raise ProviderUnavailable("Provider timeout, please retry") from e
        except DeezerProviderUnavailable as e:
            logger.error("Deezer unavailable: %s", e)
            raise ProviderUnavailable("Provider unavailable") from e
        except DeezerError as e:
            logger.error("Deezer error: %s", e)
            raise ProviderUnavailable("Provider error") from e

    @staticmethod
    def _resolve_provider_artist_id(artist_id: str) -> str:
        if artist_id.startswith("art_"):
            return parse_aura_id(artist_id, expected_kind="artist").provider_id
        return artist_id
