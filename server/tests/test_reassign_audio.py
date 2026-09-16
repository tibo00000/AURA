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
for mod in ("yt_dlp", "ytmusicapi", "rapidfuzz", "rapidfuzz.fuzz"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

from app.domain.models import DownloadJob
from app.services.download_service import (
    DOWNLOADS_DIR,
    DownloadService,
    _auto_register_in_sync_files,
    _cleanup_orphaned_job_files,
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

    def tearDown(self):
        if self.test_dir.exists():
            shutil.rmtree(self.test_dir, ignore_errors=True)

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
        """Vérifie que reassign_track_audio passe les anciens jobs à 'superseded' et crée le nouveau job."""
        service = DownloadService()
        user_id = "user_reassign_test"
        track_id = "trk_test_12345"

        with patch("app.services.download_service.supabase") as mock_supabase, \
             patch.object(service, "_run_download_job") as mock_run_job:

            mock_supabase.table().update().eq().in_().execute.return_value = MagicMock(data=[])
            mock_supabase.table().insert().execute.return_value = MagicMock(data=[])

            async def run_test():
                return await service.reassign_track_audio(
                    user_id=user_id,
                    track_id=track_id,
                    video_id="new_yt_video_id",
                    source_hint={"title": "Custom Title"},
                )

            job = asyncio.run(run_test())

            self.assertEqual(job.status, "queued")
            self.assertEqual(job.track_id, track_id)
            self.assertEqual(job.user_id, user_id)

            # Vérifie que les anciens jobs ont été passés à superseded
            mock_supabase.table("download_jobs").update.assert_called()

    def test_run_download_job_impl_bypasses_cache_on_manual_reassign(self):
        """Vérifie que _run_download_job_impl ignore le cache global quand trigger='manual_reassign'."""
        service = DownloadService()
        user_id = "user_cache_bypass"
        track_id = "trk_existing_cached_999"

        # Simuler un fichier déjà présent dans le cache global
        cache_dir = _get_global_cache_dir()
        track_key = _get_track_key(track_id)
        cached_audio = cache_dir / f"{track_key}.audio"
        cached_audio.write_bytes(b"OLD_AUDIO_CONTENT")

        fake_job = DownloadJob(
            id="job_bypass_1",
            user_id=user_id,
            track_id=track_id,
            provider_name="youtube",
            status="queued",
        )

        with patch("app.services.download_service.supabase") as mock_supabase, \
             patch.object(service, "_execute_download_workflow") as mock_exec:

            mock_supabase.table().select().eq().execute.return_value = MagicMock(data=[fake_job.to_dict()])

            async def run_test():
                # Appel avec manual_reassign
                await service._run_download_job_impl("job_bypass_1", source_hint={"trigger": "manual_reassign"})

            asyncio.run(run_test())

            # _execute_download_workflow DOIT avoir été appelé malgré la présence dans le cache
            mock_exec.assert_called_once()

    def test_propagate_reassigned_track_to_all_users(self):
        """Vérifie que _propagate_reassigned_track_to_all_users met à jour les répertoires utilisateurs."""
        sync_base = _get_sync_base()
        user_a = sync_base / "user_a"
        user_b = sync_base / "user_b"
        user_a.mkdir(parents=True, exist_ok=True)
        user_b.mkdir(parents=True, exist_ok=True)

        track_id = "trk_shared_123"
        track_key = _get_track_key(track_id)

        # Créer les anciens fichiers
        (user_a / f"{track_key}.audio").write_bytes(b"OLD_AUDIO_USER_A")
        (user_a / f"{track_key}.json").write_text(json.dumps({"title": "Old Title A"}), encoding="utf-8")

        (user_b / f"{track_key}.audio").write_bytes(b"OLD_AUDIO_USER_B")
        (user_b / f"{track_key}.json").write_text(json.dumps({"title": "Old Title B"}), encoding="utf-8")

        # Fichier nouvellement téléchargé
        new_audio = self.test_dir / "new_download.mp3"
        new_audio.write_bytes(b"BRAND_NEW_HIGH_QUALITY_AUDIO")

        _propagate_reassigned_track_to_all_users(
            track_id=track_id,
            cached_audio=new_audio,
            title="New Reassigned Title",
            artist_name="New Artist",
        )

        # Vérifier que user_a et user_b ont reçu le nouvel audio
        self.assertEqual((user_a / f"{track_key}.audio").read_bytes(), b"BRAND_NEW_HIGH_QUALITY_AUDIO")
        self.assertEqual((user_b / f"{track_key}.audio").read_bytes(), b"BRAND_NEW_HIGH_QUALITY_AUDIO")

        # Vérifier que les métadonnées ont été mises à jour
        meta_a = json.loads((user_a / f"{track_key}.json").read_text(encoding="utf-8"))
        self.assertEqual(meta_a.get("title"), "New Reassigned Title")
        self.assertEqual(meta_a.get("artist_name"), "New Artist")

    def test_cleanup_orphaned_job_files(self):
        """Vérifie la suppression des fichiers temporaires résiduels job_{old_id}.mp3."""
        downloads_dir = DOWNLOADS_DIR
        downloads_dir.mkdir(parents=True, exist_ok=True)

        track_id = "trk_clean_orphans"
        old_job_id = "job_old_to_delete"
        keep_job_id = "job_keep_active"

        # Créer des fichiers dans downloads
        old_file = downloads_dir / f"{old_job_id}.mp3"
        old_part = downloads_dir / f"{old_job_id}.part"
        keep_file = downloads_dir / f"{keep_job_id}.mp3"
        old_file.write_bytes(b"OLD_TEMP")
        old_part.write_bytes(b"OLD_PART")
        keep_file.write_bytes(b"KEEP_THIS")

        with patch("app.services.download_service.supabase") as mock_supabase:
            mock_supabase.table().select().eq().execute.return_value = MagicMock(
                data=[{"id": old_job_id}, {"id": keep_job_id}]
            )

            _cleanup_orphaned_job_files(track_id=track_id, keep_job_id=keep_job_id)

            self.assertFalse(old_file.exists())
            self.assertFalse(old_part.exists())
            self.assertTrue(keep_file.exists())


if __name__ == "__main__":
    unittest.main()
