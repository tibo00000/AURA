"""
Unit tests for multi-artist collaboration matching and retry in download_service.
"""

import os
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

TEST_TEMP_DIR = Path(tempfile.mkdtemp(prefix="aura_test_multi_artist_"))
os.environ["SYNC_FILES_DIR"] = str(TEST_TEMP_DIR / "sync_files")
os.environ["DOWNLOADS_DIR"] = str(TEST_TEMP_DIR / "downloads")

# Only mock heavy external modules if not already loaded, but keep rapidfuzz real
for mod in ("yt_dlp", "ytmusicapi"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

from app.domain.models import DownloadJob
from app.services.download_service import DownloadService
from app.services.exceptions import BadRequest


class TestSmartSongSearchMultiArtist(unittest.TestCase):

    def setUp(self):
        from app.config import get_settings
        get_settings.cache_clear()
        self.service = DownloadService()

    @patch("app.services.download_service.ytmusic.search")
    def test_vegedream_merci_les_bleus_collaboration(self, mock_search):
        """
        Vérifie qu'un morceau en collaboration (KABONGO-DJ, Vegedream)
        est correctement reconnu pour une recherche de Vegedream - Merci les bleus.
        """
        mock_search.return_value = [
            {
                "videoId": "SyXNwZtqa9Y",
                "title": "Merci les bleus",
                "artists": [{"name": "KABONGO-DJ"}, {"name": "Vegedream"}],
            }
        ]

        result = self.service._smart_song_search(artist="Vegedream", title="Merci les bleus")
        self.assertEqual(result, "SyXNwZtqa9Y")

    @patch("app.services.download_service.ytmusic.search")
    def test_featuring_in_artist_string(self, mock_search):
        """
        Vérifie qu'un featuring sous forme de chaîne unique (Laylow feat. Damso)
        est correctement reconnu pour l'artiste principal Laylow.
        """
        mock_search.return_value = [
            {
                "videoId": "laylow_special_123",
                "title": "Special",
                "artists": [{"name": "Laylow feat. Damso"}],
            }
        ]

        result = self.service._smart_song_search(artist="Laylow", title="Special")
        self.assertEqual(result, "laylow_special_123")

    @patch("app.services.download_service.ytmusic.search")
    def test_anti_false_positive_different_artist_same_title(self, mock_search):
        """
        Vérifie le garde-fou anti-faux positif :
        Si le titre correspond parfaitement mais qu'aucun artiste de la collaboration ne correspond,
        le morceau doit être rejeté (None).
        """
        mock_search.return_value = [
            {
                "videoId": "wrong_video_999",
                "title": "Merci les bleus",
                "artists": [{"name": "Fanfare des Supporters"}],
            }
        ]

        result = self.service._smart_song_search(artist="Vegedream", title="Merci les bleus")
        self.assertIsNone(result)

    @patch("app.services.download_service.ytmusic.search")
    def test_anti_false_positive_generic_title_low_ratio(self, mock_search):
        """
        Vérifie qu'un titre générique court ("Intro") avec un artiste sans rapport est rejeté.
        """
        mock_search.return_value = [
            {
                "videoId": "intro_booba_123",
                "title": "Intro",
                "artists": [{"name": "Booba"}],
            }
        ]

        result = self.service._smart_song_search(artist="Artiste Inconnu", title="Intro")
        self.assertIsNone(result)

    @patch("app.services.download_service.supabase")
    @patch.object(DownloadService, "get_job")
    @patch("app.services.download_service.asyncio.create_task")
    def test_retry_job_allows_requires_resolution(self, mock_create_task, mock_get_job, mock_supabase):
        """
        Vérifie que retry_job accepte désormais le statut 'requires_resolution'
        sans lever d'exception BadRequest (HTTP 400).
        """
        mock_job = DownloadJob(
            id="job_test_resolution_123",
            user_id="user_abc",
            track_id="trk_vegedream_bleus",
            status="requires_resolution",
            provider_name="youtube",
            attempt_count=1,
            created_at=datetime.now(timezone.utc),
            updated_at=datetime.now(timezone.utc),
        )
        mock_get_job.return_value = mock_job
        mock_supabase.table().update().eq().execute.return_value = MagicMock()

        retried_job = self.service.retry_job(user_id="user_abc", job_id="job_test_resolution_123")

        self.assertEqual(retried_job.status, "queued")
        self.assertEqual(retried_job.attempt_count, 2)
        mock_create_task.assert_called_once()


if __name__ == "__main__":
    unittest.main()
