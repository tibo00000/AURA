"""
Unit tests for manual audio reassignment, candidate search, and cache propagation.
"""

import asyncio
import hashlib
import json
import os
import shutil
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

TEST_TEMP_DIR = Path(tempfile.mkdtemp(prefix="aura_test_reassign_"))
os.environ["SYNC_FILES_DIR"] = str(TEST_TEMP_DIR / "sync_files")
os.environ["DOWNLOADS_DIR"] = str(TEST_TEMP_DIR / "downloads")

import sys
for mod in ("yt_dlp", "ytmusicapi"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()
try:
    import rapidfuzz  # noqa: F401
except ImportError:
    sys.modules["rapidfuzz"] = MagicMock()
    sys.modules["rapidfuzz.fuzz"] = MagicMock()

from app.domain.models import DownloadJob
from app.services.download_service import (
    DOWNLOADS_DIR,
    DownloadService,
    _auto_register_in_sync_files,
    _find_globally_cached_track,
    _get_global_cache_dir,
    _get_sync_base,
    _get_track_key,
    _propagate_reassigned_track_to_all_users,
)
from app.api.routes.downloads import _check_candidates_rate_limit, _candidates_rate_limits


class TestReassignAudio(unittest.TestCase):

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="aura_reassign_unit_"))
        os.environ["SYNC_FILES_DIR"] = str(self.test_dir / "sync_files")
        os.environ["DOWNLOADS_DIR"] = str(self.test_dir / "downloads")
        from app.config import get_settings
        get_settings.cache_clear()
        _get_global_cache_dir().mkdir(parents=True, exist_ok=True)
        (self.test_dir / "downloads").mkdir(parents=True, exist_ok=True)
        self._sweep_patcher = patch.object(DownloadService, "_recover_stale_jobs_on_startup")
        self._sweep_patcher.start()

    def tearDown(self):
        self._sweep_patcher.stop()
        if self.test_dir.exists():
            shutil.rmtree(self.test_dir, ignore_errors=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(TEST_TEMP_DIR, ignore_errors=True)

    def test_search_candidates_cached(self):
        """Vérifie que la recherche de candidats YTM met en cache les résultats."""
        service = DownloadService()
        with patch("app.services.download_service.ytmusic.search") as mock_search:
            mock_search.return_value = [
                {
                    "videoId": "vid123",
                    "title": "Song Title",
                    "artists": [{"name": "Artist Name"}],
                    "album": {"name": "Album Name"},
                    "duration": "3:30",
                    "thumbnails": [{"url": "https://img.yt/1.jpg"}],
                }
            ]

            # Premier appel : sollicite ytmusic.search
            res1 = service.search_candidates(artist="Artist Name", title="Song Title")
            self.assertEqual(len(res1), 1)
            self.assertEqual(res1[0]["video_id"], "vid123")
            self.assertEqual(mock_search.call_count, 1)

            # Deuxième appel identique : doit frapper le cache mémoire TTL 5 min
            res2 = service.search_candidates(artist="Artist Name", title="Song Title")
            self.assertEqual(len(res2), 1)
            self.assertEqual(mock_search.call_count, 1)  # Toujours 1 !

    def test_rate_limiting_candidates(self):
        """Vérifie la limitation de débit (15 req/min/user)."""
        _candidates_rate_limits.clear()
        user_id = "test_user_rate_limit"

        for i in range(15):
            self.assertTrue(_check_candidates_rate_limit(user_id, limit=15, window_seconds=60.0))

        # La 16e requête dans la même minute doit être rejetée
        self.assertFalse(_check_candidates_rate_limit(user_id, limit=15, window_seconds=60.0))

    def test_reassign_track_audio_supersedes_and_creates_job(self):
        from tests.test_download_job_security import Database, row, A, B
        db = Database([row('job_own', A), row('job_foreign', B)])
        service = DownloadService(supabase_client=db)
        with patch.object(service, '_start_download_task') as start:
            job = asyncio.run(service.reassign_track_audio(A, 'trk_shared', 'new_yt_video_id'))
        self.assertEqual(job.status, 'queued')
        self.assertEqual(job.user_id, A)
        self.assertEqual(job.operation_type, 'manual_reassign')
        self.assertEqual(db.rows[0]['status'], 'superseded')
        self.assertEqual(db.rows[1]['status'], 'queued')
        start.assert_called_once()

    def test_download_job_defaults_operation_type_for_legacy_rows(self):
        """Les jobs antérieurs à la migration restent des téléchargements standard."""
        legacy_row = {
            "id": "job_legacy_1",
            "user_id": "user_legacy",
            "track_id": "trk_legacy_1",
            "provider_name": "youtube",
            "status": "succeeded",
        }

        job = DownloadJob.from_dict(legacy_row)

        self.assertEqual(job.operation_type, "download")
        self.assertEqual(job.to_dict()["operation_type"], "download")

    def test_run_download_job_impl_bypasses_cache_on_manual_reassign(self):
        from tests.test_download_job_security import Database, row
        from app.services.download_lifecycle import DownloadAttempt
        job_row = row('job_bypass_1', operation_type='manual_reassign')
        service = DownloadService(supabase_client=Database([job_row]))
        attempt = DownloadAttempt(DownloadJob.from_dict(job_row))
        service._attempts[attempt.job.id] = attempt
        with patch.object(service, '_execute_download_workflow') as execute, \
             patch('app.services.download_service._find_globally_cached_track') as cache:
            asyncio.run(service._run_download_job_impl(attempt, {'resolved_video_id': 'explicit_video'}))
        execute.assert_awaited_once()
        cache.assert_not_called()

    def test_propagate_reassigned_track_to_all_users(self):
        """Vérifie que _propagate_reassigned_track_to_all_users met à jour les répertoires utilisateurs."""
        from tests.native_cache_fixture import register_native
        from app.services.download_service import _link_cached_track_to_user
        track_id = 'trk_shared_123'
        old = self.test_dir / 'old.mp3'
        old.write_bytes(b'ID3-ORIGINAL-NATIVE-AUDIO')
        self.assertTrue(register_native('user_a', track_id, old))
        trusted = _find_globally_cached_track(track_id)
        self.assertTrue(_link_cached_track_to_user('user_b', track_id, *trusted))
        private = _get_sync_base() / hashlib.sha256(b'private_owner').hexdigest()
        private.mkdir()
        key = _get_track_key(track_id)
        (private / (key + '.audio')).write_bytes(b'PRIVATE-UPLOAD-WITNESS')
        (private / (key + '.json')).write_text('{}')
        new_audio = self.test_dir / 'new.mp3'
        new_audio.write_bytes(b'ID3-BRAND-NEW-HIGH-QUALITY-AUDIO')
        self.assertTrue(register_native('user_a', track_id, new_audio, title='New Reassigned Title', artist_name='New Artist'))
        _propagate_reassigned_track_to_all_users(track_id, _find_globally_cached_track(track_id)[0],
                                               title='New Reassigned Title', artist_name='New Artist')
        for user in ('user_a', 'user_b'):
            folder = _get_sync_base() / hashlib.sha256(user.encode()).hexdigest()
            self.assertEqual((folder / (key + '.audio')).read_bytes(), new_audio.read_bytes())
            self.assertEqual(json.loads((folder / (key + '.json')).read_text())['title'], 'New Reassigned Title')
        self.assertEqual((private / (key + '.audio')).read_bytes(), b'PRIVATE-UPLOAD-WITNESS')

    def test_cleanup_orphaned_job_files(self):
        from tests.test_download_job_security import Database, row, A
        keep_id, old_id = 'job_keep_active', 'job_old_to_delete'
        db = Database([row(keep_id, A, 'succeeded'), row(old_id, A, 'failed')])
        service = DownloadService(supabase_client=db)
        old_file = DOWNLOADS_DIR / (old_id + '.mp3')
        keep_file = DOWNLOADS_DIR / (keep_id + '.mp3')
        old_file.write_bytes(b'OLD_TEMP')
        keep_file.write_bytes(b'KEEP_THIS')
        asyncio.run(service._cleanup_orphaned_job_files(DownloadJob.from_dict(db.rows[0])))
        self.assertFalse(old_file.exists())
        self.assertTrue(keep_file.exists())



if __name__ == "__main__":
    unittest.main()
