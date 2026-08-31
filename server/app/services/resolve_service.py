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

import unicodedata

def _normalize(value: Optional[str]) -> str:
    """Lowercase, strip accents, remove punctuation and collapse whitespace."""
    if not value:
        return ""
    nfkd_form = unicodedata.normalize("NFKD", value.strip().lower())
    without_accents = "".join([c for c in nfkd_form if not unicodedata.combining(c)])
    cleaned = re.sub(r"[^\w\s]", " ", without_accents)
    return re.sub(r"\s+", " ", cleaned).strip()


def _normalize_release_type(value: Optional[str]) -> str:
    normalized = (value or "").strip().lower()
    if normalized in {"album", "single", "ep", "compilation"}:
        return normalized
    return "unknown"


# ---------------------------------------------------------------------------
# ResolveService
# ---------------------------------------------------------------------------

class ResolveService:
    """
    Service for SRV-008 resolve endpoints.

    Resolves entity names to Deezer IDs with strict name matching and
    track/album hints to prevent homonym collisions.
    """

    def __init__(self, deezer_adapter: DeezerAdapter) -> None:
        self.deezer_adapter = deezer_adapter

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def resolve_artist(
        self,
        name: str,
        track_title: Optional[str] = None,
        album_title: Optional[str] = None,
    ) -> dict:
        """
        Resolve an artist name to an AURA backend ID and minimal metadata.
        Uses track_title or album_title hints to disambiguate homonyms if provided.
        Only accepts an exact match on artist name (no fuzzy homonym mixup).
        """
        if not name or not name.strip():
            return {"resolved": False, "match_confidence": 0.0, "artist": None}

        norm_name = _normalize(name)
        if not norm_name:
            return {"resolved": False, "match_confidence": 0.0, "artist": None}

        # 1. Étape 1 : Si un track_title est fourni -> Recherche ciblée sur les morceaux pour isoler le bon artiste
        if track_title and track_title.strip():
            norm_track = _normalize(track_title)
            try:
                track_query = f'artist:"{name.strip()}" track:"{track_title.strip()}"'
                response = await self.deezer_adapter.client.search(
                    track_query, resource_type="track", limit=10
                )
                candidates = response.get("data", [])
                for trk in candidates:
                    artist_obj = trk.get("artist") or {}
                    cand_artist_name = _normalize(artist_obj.get("name"))
                    cand_track_title = _normalize(trk.get("title"))

                    if cand_artist_name == norm_name and (norm_track in cand_track_title or cand_track_title in norm_track):
                        provider_id = str(artist_obj.get("id", ""))
                        aura_id = build_aura_id("artist", "deezer", provider_id)
                        picture_uri = (
                            artist_obj.get("picture_medium")
                            or artist_obj.get("picture_xl")
                            or artist_obj.get("picture")
                        )
                        return {
                            "resolved": True,
                            "match_confidence": 1.0,
                            "artist": {
                                "id": aura_id,
                                "name": artist_obj.get("name", name.strip()),
                                "picture_uri": picture_uri,
                            },
                        }
            except Exception as e:
                logger.warning("Failed track-based disambiguation for artist %s: %s", name, e)

        # 2. Étape 2 : Si un album_title est fourni -> Recherche sur les albums pour isoler le bon artiste
        if album_title and album_title.strip():
            norm_album = _normalize(album_title)
            try:
                album_query = f'artist:"{name.strip()}" album:"{album_title.strip()}"'
                response = await self.deezer_adapter.client.search(
                    album_query, resource_type="album", limit=10
                )
                candidates = response.get("data", [])
                for alb in candidates:
                    artist_obj = alb.get("artist") or {}
                    cand_artist_name = _normalize(artist_obj.get("name"))
                    cand_album_title = _normalize(alb.get("title"))

                    if cand_artist_name == norm_name and (norm_album in cand_album_title or cand_album_title in norm_album):
                        provider_id = str(artist_obj.get("id", ""))
                        aura_id = build_aura_id("artist", "deezer", provider_id)
                        picture_uri = (
                            artist_obj.get("picture_medium")
                            or artist_obj.get("picture_xl")
                            or artist_obj.get("picture")
                        )
                        return {
                            "resolved": True,
                            "match_confidence": 1.0,
                            "artist": {
                                "id": aura_id,
                                "name": artist_obj.get("name", name.strip()),
                                "picture_uri": picture_uri,
                            },
                        }
            except Exception as e:
                logger.warning("Failed album-based disambiguation for artist %s: %s", name, e)

        # 3. Étape 3 : Recherche directe sur l'artiste avec matching STRICT (égalité exacte obligatoire)
        try:
            response = await self.deezer_adapter.client.search(
                name.strip(), resource_type="artist", limit=10
            )
        except DeezerNotFound:
            return {"resolved": False, "match_confidence": 0.0, "artist": None}
        except (DeezerRateLimited, DeezerTimeout, DeezerProviderUnavailable, DeezerError) as exc:
            logger.error("Deezer resolve artist error: %s", exc)
            raise ProviderUnavailable("Provider unavailable during artist resolution") from exc

        candidates = response.get("data", [])
        exact_matches = [
            c for c in candidates
            if _normalize(c.get("name")) == norm_name
        ]

        if not exact_matches:
            return {"resolved": False, "match_confidence": 0.0, "artist": None}

        # Si plusieurs homonymes stricts ont le même nom exact, on retient le plus populaire
        best_candidate = max(exact_matches, key=lambda c: int(c.get("nb_fan") or 0))
        provider_id = str(best_candidate.get("id", ""))
        aura_id = build_aura_id("artist", "deezer", provider_id)
        picture_uri = (
            best_candidate.get("picture_medium")
            or best_candidate.get("picture_xl")
            or best_candidate.get("picture")
        )

        return {
            "resolved": True,
            "match_confidence": 1.0,
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
        track_title: Optional[str] = None,
    ) -> dict:
        """
        Resolve an album title (+ optional artist and track hint) to an AURA backend ID.
        Strict verification:
        - Album title MUST match.
        - If artist_name is provided, Album artist MUST match the requested artist.
        """
        if not title or not title.strip():
            return {"resolved": False, "match_confidence": 0.0, "album": None}

        norm_title = _normalize(title)
        norm_artist = _normalize(artist_name) if artist_name else ""

        # 1. Étape 1 : Si un track_title et artist_name sont fournis -> Recherche par piste pour trouver l'album exact
        if track_title and track_title.strip() and norm_artist:
            try:
                track_query = f'artist:"{artist_name.strip()}" track:"{track_title.strip()}"'
                response = await self.deezer_adapter.client.search(
                    track_query, resource_type="track", limit=10
                )
                for trk in response.get("data", []):
                    alb_obj = trk.get("album") or {}
                    art_obj = trk.get("artist") or {}
                    cand_alb_title = _normalize(alb_obj.get("title"))
                    cand_art_name = _normalize(art_obj.get("name"))
                    if cand_art_name == norm_artist and (norm_title in cand_alb_title or cand_alb_title in norm_title):
                        provider_id = str(alb_obj.get("id", ""))
                        aura_id = build_aura_id("album", "deezer", provider_id)
                        cover_uri = (
                            alb_obj.get("cover_medium")
                            or alb_obj.get("cover_xl")
                            or alb_obj.get("cover")
                        )
                        return {
                            "resolved": True,
                            "match_confidence": 1.0,
                            "album": {
                                "id": aura_id,
                                "title": alb_obj.get("title", title.strip()),
                                "primary_artist_name": art_obj.get("name", artist_name.strip()),
                                "cover_uri": cover_uri,
                                "release_date": None,
                                "track_count": None,
                                "release_type": "album",
                            },
                        }
            except Exception as e:
                logger.warning("Failed track-based album disambiguation for %s - %s: %s", artist_name, title, e)

        # 2. Étape 2 : Recherche directe d'album
        query = f'album:"{title.strip()}" artist:"{artist_name.strip()}"' if artist_name else f'album:"{title.strip()}"'
        try:
            response = await self.deezer_adapter.client.search(
                query, resource_type="album", limit=10
            )
        except DeezerNotFound:
            return {"resolved": False, "match_confidence": 0.0, "album": None}
        except (DeezerRateLimited, DeezerTimeout, DeezerProviderUnavailable, DeezerError) as exc:
            logger.error("Deezer resolve album error: %s", exc)
            raise ProviderUnavailable("Provider unavailable during album resolution") from exc

        candidates = response.get("data", [])
        exact_matches = []
        for alb in candidates:
            cand_title = _normalize(alb.get("title"))
            artist_data = alb.get("artist") or {}
            cand_artist = _normalize(artist_data.get("name"))

            # Titre exact ou sous-titre
            title_matches = (cand_title == norm_title) or (cand_title.startswith(norm_title + " ")) or (norm_title.startswith(cand_title + " "))
            if not title_matches:
                continue

            # Si l'artiste est spécifié, il doit correspondre obligatoirement
            if norm_artist and cand_artist != norm_artist:
                continue

            exact_matches.append(alb)

        if not exact_matches:
            return {"resolved": False, "match_confidence": 0.0, "album": None}

        best_candidate = exact_matches[0]
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
            "match_confidence": 1.0,
            "album": {
                "id": aura_id,
                "title": best_candidate.get("title", "Unknown Album"),
                "primary_artist_name": artist_data.get("name", "Unknown Artist"),
                "cover_uri": cover_uri,
                "release_date": release_date,
                "track_count": track_count,
                "release_type": _normalize_release_type(best_candidate.get("record_type")),
            },
        }
