"""
Tests unitaires pour le Global Track Cache et le dédoublonnage de fichiers AURA.

Vérifie :
1. Clé canonique de hachage SHA-256 (track_key)
2. Inscription dans le cache global et liaison vers l'espace personnel d'un utilisateur
3. Dédoublonnage : association d'une même piste pour deux utilisateurs différents
4. Invariance de contenu et vérification d'inode / links
5. Double-Checked Locking et synchronisation par piste
6. Routine d'éviction des orphelins (st_nlink == 1)
"""

import asyncio
import hashlib
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path

# Mock config to use a temporary sync_files directory for tests
TEST_TEMP_DIR = Path(tempfile.mkdtemp(prefix="aura_test_sync_"))
os.environ["SYNC_FILES_DIR"] = str(TEST_TEMP_DIR)

import sys
from unittest.mock import MagicMock

# Mock external optional audio libraries if not present in local test venv
for mod in ("yt_dlp", "ytmusicapi", "rapidfuzz", "rapidfuzz.fuzz"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

from app.services.download_service import (
    _get_sync_base,
    _get_global_cache_dir,
    _get_track_key,
    _find_globally_cached_track,
    _link_cached_track_to_user,
    _auto_register_in_sync_files,
)


class TestGlobalTrackCache(unittest.TestCase):

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="aura_cache_test_"))
        os.environ["SYNC_FILES_DIR"] = str(self.test_dir)
        from app.config import get_settings
        get_settings.cache_clear()

    def tearDown(self):
        if self.test_dir.exists():
            shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_canonical_track_key(self):
        """Vérifie que la clé canonique est le SHA-256 strict du track_id."""
        track_id = "trk_deezer_12345678"
        expected = hashlib.sha256(track_id.encode("utf-8")).hexdigest()
        self.assertEqual(_get_track_key(track_id), expected)

    def test_cache_miss_then_hit(self):
        """Vérifie le passage de Cache Miss à Cache Hit après enregistrement."""
        track_id = "trk_deezer_99999"
        
        # 1. Cache Miss initial
        self.assertIsNone(_find_globally_cached_track(track_id))

        # 2. Création d'un faux fichier MP3 téléchargé
        fake_audio = self.test_dir / "temp_download.mp3"
        fake_audio.write_bytes(b"FAKE_AUDIO_DATA_FOR_TEST_12345")

        # 3. Enregistrement par l'utilisateur A
        user_a = "user_alpha_uuid"
        success = _auto_register_in_sync_files(
            user_id=user_a,
            track_id=track_id,
            audio_file=fake_audio,
            title="Test Song",
            artist_name="Test Artist",
            album_title="Test Album",
            duration_ms=210000,
        )
        self.assertTrue(success)

        # 4. Cache Hit
        cached = _find_globally_cached_track(track_id)
        self.assertIsNotNone(cached)
        cached_path, metadata = cached
        self.assertTrue(cached_path.exists())
        self.assertEqual(metadata.get("title"), "Test Song")
        self.assertEqual(metadata.get("artist_name"), "Test Artist")

    def test_deduplication_between_two_users(self):
        """
        Vérifie qu'un deuxième utilisateur associant la même musique bénéficie
        du dédoublonnage (même contenu, hardlink ou copie sans re-téléchargement).
        """
        track_id = "trk_deezer_shared_song"
        fake_audio = self.test_dir / "downloaded.mp3"
        content = b"IDENTICAL_AUDIO_PAYLOAD_FOR_BOTH_USERS"
        fake_audio.write_bytes(content)

        user_a = "user_alpha"
        user_b = "user_beta"

        # User A télécharge
        _auto_register_in_sync_files(
            user_id=user_a,
            track_id=track_id,
            audio_file=fake_audio,
            title="Daft Punk - One More Time",
            artist_name="Daft Punk",
            duration_ms=320000,
        )

        # User B arrive : Cache Hit instantané
        cached = _find_globally_cached_track(track_id)
        self.assertIsNotNone(cached)
        cached_audio, metadata = cached

        link_success = _link_cached_track_to_user(
            user_id=user_b,
            track_id=track_id,
            cached_audio=cached_audio,
            metadata=metadata,
        )
        self.assertTrue(link_success)

        # Vérification des fichiers dans les répertoires personnels de A et B
        safe_a = hashlib.sha256(user_a.encode("utf-8")).hexdigest()
        safe_b = hashlib.sha256(user_b.encode("utf-8")).hexdigest()
        track_key = _get_track_key(track_id)

        file_a = self.test_dir / safe_a / f"{track_key}.audio"
        file_b = self.test_dir / safe_b / f"{track_key}.audio"
        meta_b = self.test_dir / safe_b / f"{track_key}.json"

        self.assertTrue(file_a.exists())
        self.assertTrue(file_b.exists())
        self.assertTrue(meta_b.exists())

        # Contenus strictement identiques
        self.assertEqual(file_a.read_bytes(), content)
        self.assertEqual(file_b.read_bytes(), content)

        # Vérification des métadonnées du User B (décompte quota logique)
        b_meta = json.loads(meta_b.read_text(encoding="utf-8"))
        self.assertEqual(b_meta.get("size_bytes"), len(content))
        self.assertEqual(b_meta.get("title"), "Daft Punk - One More Time")

    def test_lock_concurrency_isolation(self):
        """Vérifie que _get_track_lock garantit le même verrou par track_id."""
        async def run_lock_test():
            from app.services.download_service import DownloadService
            # On instancie un mock minimal
            svc = object.__new__(DownloadService)
            svc._track_locks = {}
            svc._track_locks_guard = asyncio.Lock()

            lock1 = await svc._get_track_lock("trk_123")
            lock2 = await svc._get_track_lock("trk_123")
            lock_diff = await svc._get_track_lock("trk_456")

            self.assertIs(lock1, lock2)
            self.assertIsNot(lock1, lock_diff)

            # Test de purge d'hygiène
            await svc._prune_unused_locks()
            self.assertEqual(len(svc._track_locks), 0)

        asyncio.run(run_lock_test())

    def test_orphan_eviction_under_lock(self):
        """Vérifie que cleanup_orphaned_cache supprime les fichiers orphelins sans références."""
        async def run_eviction_test():
            from app.services.download_service import DownloadService, _get_global_cache_dir
            svc = object.__new__(DownloadService)
            svc._track_locks = {}
            svc._track_locks_guard = asyncio.Lock()

            cache_dir = _get_global_cache_dir()
            orphan_audio = cache_dir / "orphan_track.audio"
            orphan_json = cache_dir / "orphan_track.json"
            orphan_audio.write_bytes(b"ORPHAN_DATA")
            orphan_json.write_text("{}", encoding="utf-8")

            # Forcer mtime dans le passé (> 30 jours)
            old_time = 1000000000
            os.utime(orphan_audio, (old_time, old_time))

            # Exécuter l'éviction
            pruned = await svc.cleanup_orphaned_cache(max_age_days=1)
            self.assertEqual(pruned, 1)
            self.assertFalse(orphan_audio.exists())
            self.assertFalse(orphan_json.exists())

        asyncio.run(run_eviction_test())

    def test_existing_track_backfill_from_user_folder(self):
        """
        Vérifie qu'un morceau déjà présent dans le dossier d'un utilisateur historique
        (téléchargé avant l'existence du _global_cache) est automatiquement indexé
        dans le cache global dès qu'il est recherché.
        """
        track_id = "trk_deezer_historical_song"
        track_key = _get_track_key(track_id)
        owner_user_id = "12345678-1234-1234-1234-1234567890ab"
        safe_owner = hashlib.sha256(owner_user_id.encode("utf-8")).hexdigest()

        # Simuler un fichier téléchargé dans le passé uniquement dans le dossier de l'owner
        owner_dir = self.test_dir / safe_owner
        owner_dir.mkdir(parents=True, exist_ok=True)
        historical_audio = owner_dir / f"{track_key}.audio"
        historical_json = owner_dir / f"{track_key}.json"

        content = b"HISTORICAL_AUDIO_FILE_ON_VPS"
        historical_audio.write_bytes(content)
        historical_json.write_text(json.dumps({
            "track_id": track_id,
            "title": "Historical Hit",
            "artist_name": "Legend",
        }), encoding="utf-8")

        # Vérifier que le cache global ne l'a pas au départ
        cache_dir = _get_global_cache_dir()
        self.assertFalse((cache_dir / f"{track_key}.audio").exists())

        # Appel de _find_globally_cached_track : doit le trouver et le backfiller
        cached = _find_globally_cached_track(track_id)
        self.assertIsNotNone(cached)
        cached_path, metadata = cached

        # Vérifier qu'il est maintenant dans le cache global
        self.assertTrue(cached_path.exists())
        self.assertEqual(cached_path.read_bytes(), content)
        self.assertEqual(metadata.get("title"), "Historical Hit")
        self.assertEqual(metadata.get("artist_name"), "Legend")


if __name__ == "__main__":
    unittest.main()
