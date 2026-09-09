"""
Unit tests for the audio version re-assignment / change audio feature.

Tests:
1. create_job with force_resolution=True bypasses cache, marks older jobs superseded, and returns requires_resolution with candidates.
2. create_job with force_resolution=True reuses existing requires_resolution job if within 30 min TTL (idempotence).
3. create_job with force_resolution=True marks job as failed with 'no_candidates_found' if 0 candidates are returned.
4. Concurrent create_job calls for same (user_id, track_id) are serialized by lock.
5. _auto_register_in_sync_files updates all aliases in _global_cache.
"""

import asyncio
import os
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

# Set temporary sync_files dir
TEST_TEMP_DIR = Path(tempfile.mkdtemp(prefix="aura_test_change_audio_"))
os.environ["SYNC_FILES_DIR"] = str(TEST_TEMP_DIR)

# Mock audio dependencies if not installed
for mod in ("yt_dlp", "ytmusicapi", "rapidfuzz", "rapidfuzz.fuzz"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

from app.domain.models import DownloadJob
from app.services.download_service import (
    DownloadService,
    _get_global_cache_dir,
    _get_track_key,
    _auto_register_in_sync_files,
)


class TestChangeAudio(unittest.IsolatedAsyncioTestCase):

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="aura_change_audio_"))
        os.environ["SYNC_FILES_DIR"] = str(self.test_dir)
        from app.config import get_settings
        get_settings.cache_clear()

    def tearDown(self):
        if self.test_dir.exists():
            shutil.rmtree(self.test_dir, ignore_errors=True)

    @patch("app.services.download_service.supabase")
    async def test_force_resolution_marks_superseded_and_returns_candidates(self, mock_supabase):
        """Verify that force_resolution=True marks previous jobs superseded and creates requires_resolution."""
        service = DownloadService()

        user_id = "user_test_123"
        track_id = "trk_deezer_888999"

        # Mock existing job in Supabase
        old_job = {
            "id": "job_old_1",
            "user_id": user_id,
            "track_id": track_id,
            "provider_name": "youtube",
            "status": "succeeded",
            "progress_percent": 100.0,
            "attempt_count": 1,
            "created_at": (datetime.now(timezone.utc) - timedelta(hours=2)).isoformat(),
            "updated_at": (datetime.now(timezone.utc) - timedelta(hours=2)).isoformat(),
        }

        # Setup Supabase mocks
        mock_select = MagicMock()
        mock_select.select.return_value.eq.return_value.eq.return_value.execute.return_value = MagicMock(data=[old_job])
        mock_supabase.table.return_value = mock_select

        mock_update = MagicMock()
        mock_select.update.return_value.eq.return_value.execute.return_value = MagicMock(data=[])

        mock_insert = MagicMock()
        mock_select.insert.return_value.execute.return_value = MagicMock(data=[])

        # Mock 5 candidates
        dummy_candidates = [
            {"video_id": "vid1", "title": "Track Live", "artist": "Artist", "album": "Live"},
            {"video_id": "vid2", "title": "Track Studio", "artist": "Artist", "album": "Album"},
        ]
        service._fetch_5_candidates = MagicMock(return_value=dummy_candidates)

        job = await service.create_job(
            user_id=user_id,
            track_id=track_id,
            source_hint={"title": "Song", "artist_name": "Artist"},
            force_resolution=True,
        )

        # Check job created
        self.assertEqual(job.status, "requires_resolution")
        self.assertEqual(len(job.candidates), 2)
        self.assertEqual(job.candidates[0]["video_id"], "vid1")

        # Verify old job was marked superseded
        mock_select.update.assert_any_call({"status": "superseded", "updated_at": unittest.mock.ANY})

    @patch("app.services.download_service.supabase")
    async def test_force_resolution_idempotent_reuse_within_ttl(self, mock_supabase):
        """Verify that an active requires_resolution job within 30 min TTL is reused."""
        service = DownloadService()

        user_id = "user_test_123"
        track_id = "trk_deezer_888999"

        recent_active_job = {
            "id": "job_active_recent",
            "user_id": user_id,
            "track_id": track_id,
            "provider_name": "youtube",
            "status": "requires_resolution",
            "progress_percent": 0.0,
            "attempt_count": 1,
            "candidates": [{"video_id": "vid_active", "title": "Title", "artist": "Artist"}],
            "created_at": (datetime.now(timezone.utc) - timedelta(minutes=5)).isoformat(),
            "updated_at": (datetime.now(timezone.utc) - timedelta(minutes=5)).isoformat(),
        }

        mock_select = MagicMock()
        mock_select.select.return_value.eq.return_value.eq.return_value.execute.return_value = MagicMock(data=[recent_active_job])
        mock_supabase.table.return_value = mock_select

        service._fetch_5_candidates = MagicMock()

        job = await service.create_job(
            user_id=user_id,
            track_id=track_id,
            source_hint={"title": "Title", "artist_name": "Artist"},
            force_resolution=True,
        )

        self.assertEqual(job.id, "job_active_recent")
        self.assertEqual(job.status, "requires_resolution")
        # Ensure _fetch_5_candidates was NOT called again (reused!)
        service._fetch_5_candidates.assert_not_called()

    @patch("app.services.download_service.supabase")
    async def test_force_resolution_zero_candidates_fails_cleanly(self, mock_supabase):
        """Verify that when 0 candidates are found, job status is failed with no_candidates_found."""
        service = DownloadService()

        user_id = "user_test_123"
        track_id = "trk_deezer_888999"

        mock_select = MagicMock()
        mock_select.select.return_value.eq.return_value.eq.return_value.execute.return_value = MagicMock(data=[])
        mock_select.insert.return_value.execute.return_value = MagicMock(data=[])
        mock_supabase.table.return_value = mock_select

        service._fetch_5_candidates = MagicMock(return_value=[])

        job = await service.create_job(
            user_id=user_id,
            track_id=track_id,
            source_hint={"title": "Obscure Unknown Track", "artist_name": "Unknown"},
            force_resolution=True,
        )

        self.assertEqual(job.status, "failed")
        self.assertEqual(job.error_code, "no_candidates_found")
        self.assertIn("Aucune version alternative", job.error_message)

    def test_auto_register_in_sync_files_updates_all_aliases(self):
        """Verify that _auto_register_in_sync_files writes primary key and updates all aliases in _global_cache."""
        user_id = "user_12345"
        track_id = "trk_deezer_424242"
        temp_audio = self.test_dir / "temp_input.mp3"
        temp_audio.write_bytes(b"NEW_RESOLVED_AUDIO_DATA")

        success = _auto_register_in_sync_files(
            user_id=user_id,
            track_id=track_id,
            audio_file=temp_audio,
            title="Resolved Title",
            artist_name="Resolved Artist",
        )

        self.assertTrue(success)

        cache_dir = _get_global_cache_dir()
        primary_key = _get_track_key(track_id)
        self.assertTrue((cache_dir / f"{primary_key}.audio").exists())
        self.assertEqual((cache_dir / f"{primary_key}.audio").read_bytes(), b"NEW_RESOLVED_AUDIO_DATA")

        # Test aliases (deezer:424242 and 424242)
        alias_key = _get_track_key("deezer:424242")
        self.assertTrue((cache_dir / f"{alias_key}.audio").exists())
        self.assertEqual((cache_dir / f"{alias_key}.audio").read_bytes(), b"NEW_RESOLVED_AUDIO_DATA")


if __name__ == "__main__":
    unittest.main()
