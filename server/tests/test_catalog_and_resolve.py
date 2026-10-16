"""
Unit and route tests for Catalog Search, Entity Resolution, and Deezer Adapter scoring.

Covers:
- SearchService: query validation (< 3 chars -> 400 BadRequest), provider error -> 503 ProviderUnavailable
- ResolveService: artist resolution (track hint, album hint, exact match, homonym fan count tie-breaker, non-exact rejection)
  and album resolution (title + artist match, track fallback)
- DeezerAdapter: _pick_best_match scoring & tie-breaking rules
"""

import asyncio
import sys
import unittest
from unittest.mock import AsyncMock, MagicMock

for mod in ("yt_dlp", "ytmusicapi"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

from fastapi.testclient import TestClient
from app.main import app
from app.domain.models import ProviderAlbum, ProviderArtist, ProviderTrack, SearchResult
from app.providers.deezer.adapter import DeezerAdapter
from app.providers.deezer.exceptions import DeezerTimeout
from app.services.exceptions import BadRequest, ProviderUnavailable
from app.services.resolve_service import ResolveService
from app.services.search_service import SearchService


class TestDeezerAdapterBestMatch(unittest.TestCase):

    def test_pick_best_match_scores_exact_artist_and_album_artist_bonus(self):
        artist_dp = ProviderArtist(
            provider_name="deezer",
            provider_id="27",
            display_name="Daft Punk",
        )
        album_discovery = ProviderAlbum(
            provider_name="deezer",
            provider_id="302127",
            display_title="Discovery",
            artist=artist_dp,
        )
        track_hbfs = ProviderTrack(
            provider_name="deezer",
            provider_id="3135556",
            display_title="Harder, Better, Faster, Stronger",
            artist=artist_dp,
            album=album_discovery,
        )

        result = SearchResult(
            tracks=[track_hbfs],
            artists=[artist_dp],
            albums=[album_discovery],
        )

        best = DeezerAdapter._pick_best_match("Daft Punk", result)
        self.assertIsNotNone(best)
        self.assertIsInstance(best, ProviderArtist)
        self.assertEqual(best.provider_id, "27")

    def test_score_text_match_handles_exact_prefix_substring_and_disjoint(self):
        self.assertEqual(DeezerAdapter._score_text_match("Daft Punk", "daft punk"), 100)
        self.assertEqual(DeezerAdapter._score_text_match("Daft", "Daft Punk"), 85)
        self.assertEqual(DeezerAdapter._score_text_match("Punk", "The Daft Punk Experience"), 72)
        self.assertEqual(DeezerAdapter._score_text_match("zxywvut", "Mozart Symphony"), 0)


class TestSearchAndResolveServices(unittest.TestCase):

    def test_search_rejects_short_query_and_maps_provider_errors(self):
        mock_adapter = MagicMock()
        service = SearchService(mock_adapter)

        with self.assertRaises(BadRequest):
            asyncio.run(service.search(query="ab", limit_tracks=5, limit_artists=5, limit_albums=5))

        mock_adapter.search = AsyncMock(side_effect=DeezerTimeout("Deezer timeout"))
        with self.assertRaises(ProviderUnavailable):
            asyncio.run(service.search(query="daft punk", limit_tracks=5, limit_artists=5, limit_albums=5))

    def test_resolve_artist_uses_track_hint_and_homonym_fan_count_tiebreaker(self):
        mock_adapter = MagicMock()
        mock_adapter.client = MagicMock()
        service = ResolveService(mock_adapter)

        # Case 1: Resolved via track_title hint
        mock_adapter.client.search = AsyncMock(
            return_value={
                "data": [
                    {
                        "title": "Harder, Better, Faster, Stronger",
                        "artist": {
                            "id": 27,
                            "name": "Daft Punk",
                            "picture_medium": "https://cdn.deezer.com/artist/27.jpg",
                        },
                    }
                ]
            }
        )
        res_hint = asyncio.run(
            service.resolve_artist(name="Daft Punk", track_title="Harder, Better, Faster, Stronger")
        )
        self.assertTrue(res_hint["resolved"])
        self.assertEqual(res_hint["match_confidence"], 1.0)
        self.assertEqual(res_hint["artist"]["name"], "Daft Punk")
        self.assertTrue(res_hint["artist"]["id"].startswith("art_"))

        # Case 2: Homonym resolution via nb_fan tie-breaker when no hint is provided
        mock_adapter.client.search = AsyncMock(
            return_value={
                "data": [
                    {"id": 1001, "name": "Justice", "nb_fan": 500, "picture_medium": "https://cdn/1001.jpg"},
                    {"id": 1002, "name": "Justice", "nb_fan": 850000, "picture_medium": "https://cdn/1002.jpg"},
                ]
            }
        )
        res_homonym = asyncio.run(service.resolve_artist(name="Justice"))
        self.assertTrue(res_homonym["resolved"])
        self.assertEqual(res_homonym["match_confidence"], 1.0)
        # Must select id 1002 (highest nb_fan)
        self.assertEqual(res_homonym["artist"]["picture_uri"], "https://cdn/1002.jpg")

        # Case 3: Non-exact match is rejected to prevent wrong artist artwork
        mock_adapter.client.search = AsyncMock(
            return_value={"data": [{"id": 9999, "name": "Justice Crew", "nb_fan": 50000}]}
        )
        res_mismatch = asyncio.run(service.resolve_artist(name="Justice"))
        self.assertFalse(res_mismatch["resolved"])
        self.assertIsNone(res_mismatch["artist"])

    def test_resolve_album_matches_title_and_artist(self):
        mock_adapter = MagicMock()
        mock_adapter.client = MagicMock()
        service = ResolveService(mock_adapter)

        mock_adapter.client.search = AsyncMock(
            return_value={
                "data": [
                    {
                        "id": 302127,
                        "title": "Discovery (Deluxe Edition)",
                        "artist": {"name": "Daft Punk"},
                        "cover_medium": "https://cdn.deezer.com/album/302127.jpg",
                        "nb_tracks": 14,
                        "record_type": "album",
                    }
                ]
            }
        )

        res = asyncio.run(service.resolve_album(title="Discovery", artist_name="Daft Punk"))
        self.assertTrue(res["resolved"])
        self.assertEqual(res["match_confidence"], 1.0)
        self.assertEqual(res["album"]["primary_artist_name"], "Daft Punk")
        self.assertEqual(res["album"]["track_count"], 14)
        self.assertEqual(res["album"]["release_type"], "album")

    def test_search_route_rejects_short_and_whitespace_queries(self):
        client = TestClient(app)
        # FastAPI Query(min_length=3) rejects 1-char query with 422
        resp_short = client.get("/search?q=a")
        self.assertEqual(resp_short.status_code, 422)

        # 3-space query passes FastAPI min_length=3 and triggers SearchService BadRequest -> 400
        resp_spaces = client.get("/search?q=%20%20%20")
        self.assertEqual(resp_spaces.status_code, 400)
        body = resp_spaces.json()
        self.assertEqual(body["error"]["code"], "invalid_request")


if __name__ == "__main__":
    unittest.main()

