"""
Album service for AURA backend.

Handles album detail requests by orchestrating provider adapters.
"""

import logging
from typing import List

from ..core.aura_id_codec import parse_aura_id
from ..domain.models import ProviderAlbum, ProviderTrack
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


class AlbumService:
    """
    Service for retrieving album details.

    For SRV-002, album IDs from `/search` are opaque backend IDs resolved
    statelessly to provider references. SRV-004 will replace this with a
    persisted catalog lookup backed by Postgres mappings.
    """

    def __init__(self, deezer_adapter: DeezerAdapter):
        self.deezer_adapter = deezer_adapter

    async def get_album_details(
        self,
        album_id: str,
    ) -> tuple[ProviderAlbum, List[ProviderTrack]]:
        """
        Get album details and tracks.

        Args:
            album_id: AURA album ID from `/search`, or raw provider ID as fallback
        """
        if not album_id or not album_id.strip():
            raise NotFound("Album ID required")

        resolved_album_id = self._resolve_provider_album_id(album_id.strip())

        try:
            album, tracks = await self.deezer_adapter.get_album(resolved_album_id)
            return album, tracks
        except DeezerNotFound as e:
            logger.info("Album %s not found", album_id)
            raise NotFound("Album not found") from e
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
    def _resolve_provider_album_id(album_id: str) -> str:
        if album_id.startswith("alb_"):
            return parse_aura_id(album_id, expected_kind="album").provider_id
        return album_id
