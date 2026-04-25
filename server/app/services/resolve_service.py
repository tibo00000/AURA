"""
Resolve service for AURA backend (SRV-008).

Handles GET /resolve/artist and GET /resolve/album:
- Takes a local entity name/title and resolves it to an opaque backend ID.
- Returns minimal enrichment metadata (picture_uri / cover_uri) for Android to persist.
- match_confidence is a normalised score [0.0, 1.0].
"""

import logging
import re
from typing import Optional

from ..core.aura_id_codec import build_aura_id
from ..domain.models import ProviderArtist, ProviderAlbum
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


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _normalize(value: str) -> str:
    """Lowercase + collapse whitespace."""
    return re.sub(r"\s+", " ", value.strip().lower())


def _score_text(query: str, candidate: Optional[str]) -> float:
    """Return a [0.0, 1.0] similarity score between query and candidate."""
    if not query or not candidate:
        return 0.0
    q = _normalize(query)
    c = _normalize(candidate)
    if not q or not c:
        return 0.0
    if c == q:
        return 1.0
    if c.startswith(q):
        return 0.85
    if q in c:
        return 0.72
    q_tokens = set(q.split())
    c_tokens = set(c.split())
    common = q_tokens & c_tokens
    if not common:
        return 0.0
    return min(0.60, 0.28 + len(common) * 0.12)


# ---------------------------------------------------------------------------
# ResolveService
# ---------------------------------------------------------------------------

class ResolveService:
    """
    Service for SRV-008 resolve endpoints.

    Both resolve routes search Deezer for candidate entities, pick the best
    textual match, and wrap the result in an opaque AURA ID.
    """

    def __init__(self, deezer_adapter: DeezerAdapter) -> None:
        self.deezer_adapter = deezer_adapter

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def resolve_artist(
        self,
        name: str,
    ) -> dict:
        """
        Resolve an artist name to an AURA backend ID and minimal metadata.

        Returns a dict matching the /resolve/artist contract:
          {resolved, match_confidence, artist: {id, name, picture_uri}}
        """
        if not name or not name.strip():
            return {"resolved": False, "match_confidence": 0.0, "artist": None}

        try:
            response = await self.deezer_adapter.client.search(
                name.strip(), resource_type="artist", limit=5
            )
        except DeezerNotFound:
            return {"resolved": False, "match_confidence": 0.0, "artist": None}
        except (DeezerRateLimited, DeezerTimeout, DeezerProviderUnavailable, DeezerError) as exc:
            logger.error("Deezer resolve artist error: %s", exc)
            raise ProviderUnavailable("Provider unavailable during artist resolution") from exc

        candidates = response.get("data", [])
        if not candidates:
            return {"resolved": False, "match_confidence": 0.0, "artist": None}

        best_score = 0.0
        best_candidate = None
        for data in candidates:
            score = _score_text(name, data.get("name"))
            if score > best_score:
                best_score = score
                best_candidate = data

        if best_candidate is None or best_score == 0.0:
            return {"resolved": False, "match_confidence": 0.0, "artist": None}

        provider_id = str(best_candidate.get("id", ""))
        aura_id = build_aura_id("artist", "deezer", provider_id)
        picture_uri = (
            best_candidate.get("picture_medium")
            or best_candidate.get("picture_xl")
            or best_candidate.get("picture")
        )

        return {
            "resolved": True,
            "match_confidence": round(best_score, 4),
            "artist": {
                "id": aura_id,
                "name": best_candidate.get("name", "Unknown Artist"),
                "picture_uri": picture_uri,
            },
        }

    async def resolve_album(
        self,
        title: str,
        artist_name: Optional[str] = None,
    ) -> dict:
        """
        Resolve an album title (+ optional artist hint) to an AURA backend ID.

        Returns a dict matching the /resolve/album contract:
          {resolved, match_confidence, album: {id, title, primary_artist_name,
           cover_uri, release_date, track_count}}
        """
        if not title or not title.strip():
            return {"resolved": False, "match_confidence": 0.0, "album": None}

        query = f"{artist_name} {title}".strip() if artist_name else title.strip()

        try:
            response = await self.deezer_adapter.client.search(
                query, resource_type="album", limit=8
            )
        except DeezerNotFound:
            return {"resolved": False, "match_confidence": 0.0, "album": None}
        except (DeezerRateLimited, DeezerTimeout, DeezerProviderUnavailable, DeezerError) as exc:
            logger.error("Deezer resolve album error: %s", exc)
            raise ProviderUnavailable("Provider unavailable during album resolution") from exc

        candidates = response.get("data", [])
        if not candidates:
            return {"resolved": False, "match_confidence": 0.0, "album": None}

        best_score = 0.0
        best_candidate = None
        for data in candidates:
            title_score = _score_text(title, data.get("title"))
            artist_score = (
                _score_text(artist_name, data.get("artist", {}).get("name"))
                if artist_name
                else 0.0
            )
            # Weight: title dominates, artist is a tiebreaker.
            combined = title_score * 0.75 + artist_score * 0.25
            if combined > best_score:
                best_score = combined
                best_candidate = data

        if best_candidate is None or best_score == 0.0:
            return {"resolved": False, "match_confidence": 0.0, "album": None}

        provider_id = str(best_candidate.get("id", ""))
        aura_id = build_aura_id("album", "deezer", provider_id)
        cover_uri = (
            best_candidate.get("cover_medium")
            or best_candidate.get("cover_xl")
            or best_candidate.get("cover")
        )
        artist_data = best_candidate.get("artist") or {}
        release_date = best_candidate.get("release_date")
        nb_tracks = best_candidate.get("nb_tracks")
        track_count: Optional[int] = None
        if nb_tracks is not None:
            try:
                track_count = int(nb_tracks)
            except (TypeError, ValueError):
                pass

        return {
            "resolved": True,
            "match_confidence": round(best_score, 4),
            "album": {
                "id": aura_id,
                "title": best_candidate.get("title", "Unknown Album"),
                "primary_artist_name": artist_data.get("name", "Unknown Artist"),
                "cover_uri": cover_uri,
                "release_date": release_date,
                "track_count": track_count,
            },
        }
