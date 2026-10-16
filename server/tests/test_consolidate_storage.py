"""
Tests unitaires pour le script de consolidation et nettoyage du stockage VPS (consolidate_storage.py).

Valide l'ensemble de la matrice de sécurité à 7 cas :
- Cas A : Fichiers récents / en cours ignorés
- Cas B : Orphelins non résolus protégés (jamais supprimés)
- Cas C : Migration sécurisée vers _global_cache avec métadonnées
- Cas D : Même inode physique -> quarantaine sans risque
- Cas E : Inodes distincts, même hash SHA-256 -> quarantaine validée
- Cas F : Conflit de hash SHA-256 -> préservation absolue
- Cas G : Fichier vide corrompu (> 2h) -> suppression
- Sécurité de rétention de la quarantaine (7 jours)
"""

import hashlib
import json
import os
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock

# Ensure server/ directory is in sys.path
SERVER_ROOT = Path(__file__).resolve().parent.parent
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

# Mock external optional audio libraries if not present in test venv
for mod in ("yt_dlp", "ytmusicapi", "rapidfuzz", "rapidfuzz.fuzz"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

# Import the script module
import scripts.consolidate_storage as cs


class MockSupabaseClient:
    """Mock Supabase client for testing storage consolidation."""

    def __init__(self, jobs: dict):
        self._jobs = jobs

    def table(self, name: str):
        self._current_table = name
        return self

    def select(self, *args, **kwargs):
        return self

    def eq(self, field: str, value: str):
        self._eq_field = field
        self._eq_value = value
        return self

    def limit(self, count: int):
        return self

    def execute(self):
        if self._current_table == "download_jobs":
            if hasattr(self, "_eq_field") and self._eq_field == "status" and self._eq_value == "running":
                running = [j for j in self._jobs.values() if j.get("status") == "running"]
                return MagicMock(data=running)
            elif hasattr(self, "_eq_field") and self._eq_field == "id":
                job = self._jobs.get(self._eq_value)
                return MagicMock(data=[job] if job else [])
        return MagicMock(data=[])


class TestStorageConsolidation(unittest.TestCase):

    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="aura_test_consolidate_"))
        self.downloads_dir = self.temp_dir / "downloads"
        self.downloads_dir.mkdir(parents=True, exist_ok=True)
        self.sync_base = self.downloads_dir / "sync_files"
        self.global_cache = self.sync_base / "_global_cache"
        self.global_cache.mkdir(parents=True, exist_ok=True)
        self.mock_jobs = {}
        self.mock_supabase = MockSupabaseClient(self.mock_jobs)
        self.consolidator = cs.StorageConsolidator(self.downloads_dir, self.mock_supabase)

    def tearDown(self):
        if self.temp_dir.exists():
            shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _set_file_age(self, path: Path, seconds_ago: float):
        past = time.time() - seconds_ago
        os.utime(path, (past, past))

    def test_case_A_recent_file_ignored(self):
        """Cas A : Fichier modifié il y a moins de 30 min -> IGNORÉ."""
        recent_file = self.downloads_dir / "job_recent123.mp3"
        recent_file.write_bytes(b"some audio content")
        self._set_file_age(recent_file, seconds_ago=300)  # 5 minutes

        info = self.consolidator.inspect_file(recent_file)
        self.assertEqual(info["case"], "A")
        self.assertEqual(info["action"], "IGNORE")

    def test_case_B_unresolved_orphan_preserved(self):
        """Cas B : Fichier ancien sans entrée en base -> PRÉSERVÉ (jamais supprimé)."""
        orphan_file = self.downloads_dir / "job_orphan456.mp3"
        orphan_file.write_bytes(b"valid audio bytes")
        self._set_file_age(orphan_file, seconds_ago=7200)  # 2 hours

        info = self.consolidator.inspect_file(orphan_file)
        self.assertEqual(info["case"], "B")
        self.assertEqual(info["action"], "PRESERVE_AND_FLAG")
        self.assertTrue(orphan_file.exists())

    def test_case_C_migration_to_global_cache(self):
        """Cas C : Morceau présent en base mais absent de _global_cache -> MIGRÉ puis QUARANTAINE."""
        job_id = "job_migrate789"
        track_id = "trk_song_to_migrate"
        user_id = "user_owner_uuid"

        self.mock_jobs[job_id] = {
            "id": job_id,
            "track_id": track_id,
            "user_id": user_id,
            "status": "succeeded",
        }

        cand_file = self.downloads_dir / f"{job_id}.mp3"
        content = b"migration audio data stream"
        cand_file.write_bytes(content)
        self._set_file_age(cand_file, seconds_ago=7200)

        info = self.consolidator.inspect_file(cand_file)
        self.assertEqual(info["case"], "C")
        self.assertEqual(info["action"], "MIGRATE_TO_GLOBAL_CACHE")

        # Exécuter la consolidation
        self.consolidator.consolidate()

        # Vérifier que le fichier est maintenant dans _global_cache
        track_key = cs.get_track_key(track_id)
        cached_audio = self.global_cache / f"{track_key}.audio"
        cached_json = self.global_cache / f"{track_key}.json"
        self.assertTrue(cached_audio.exists())
        self.assertEqual(cached_audio.read_bytes(), content)
        self.assertTrue(cached_json.exists())

        # Vérifier qu'il est lié au dossier utilisateur
        user_dir = self.sync_base / cs.get_user_key(user_id)
        user_audio = user_dir / f"{track_key}.audio"
        self.assertTrue(user_audio.exists())

        # Vérifier que le fichier original a été déplacé en quarantaine (pas supprimé de la machine)
        self.assertFalse(cand_file.exists())
        quarantine_files = list(self.downloads_dir.glob("_legacy_trash/*/*.mp3"))
        self.assertEqual(len(quarantine_files), 1)
        self.assertEqual(quarantine_files[0].read_bytes(), content)

    def test_case_D_same_inode_quarantined(self):
        """Cas D : Fichier déjà hardlinké vers _global_cache (même st_ino) -> QUARANTAINE."""
        job_id = "job_hardlinked111"
        track_id = "trk_already_hardlinked"

        self.mock_jobs[job_id] = {
            "id": job_id,
            "track_id": track_id,
            "user_id": "usr_test",
            "status": "succeeded",
        }

        track_key = cs.get_track_key(track_id)
        cached_audio = self.global_cache / f"{track_key}.audio"
        cached_audio.write_bytes(b"shared inode audio data")

        cand_file = self.downloads_dir / f"{job_id}.mp3"
        os.link(cached_audio, cand_file)
        self._set_file_age(cand_file, seconds_ago=7200)

        # Vérifier qu'ils ont le même inode
        self.assertEqual(cand_file.stat().st_ino, cached_audio.stat().st_ino)

        info = self.consolidator.inspect_file(cand_file)
        self.assertEqual(info["case"], "D")
        self.assertEqual(info["action"], "MOVE_TO_QUARANTINE")

        # Exécuter consolidation
        self.consolidator.consolidate()
        self.assertFalse(cand_file.exists())
        # Le fichier dans _global_cache reste intact !
        self.assertTrue(cached_audio.exists())

    def test_case_E_different_inode_identical_hash(self):
        """Cas E : Inodes distincts mais SHA-256 rigoureusement identique -> QUARANTAINE."""
        job_id = "job_copy222"
        track_id = "trk_copy_track"

        self.mock_jobs[job_id] = {
            "id": job_id,
            "track_id": track_id,
            "user_id": "usr_test",
            "status": "succeeded",
        }

        content = b"independent copy with same bytes"
        track_key = cs.get_track_key(track_id)
        cached_audio = self.global_cache / f"{track_key}.audio"
        cached_audio.write_bytes(content)

        cand_file = self.downloads_dir / f"{job_id}.mp3"
        cand_file.write_bytes(content)  # Écrit indépendamment -> inode distinct
        self._set_file_age(cand_file, seconds_ago=7200)

        self.assertNotEqual(cand_file.stat().st_ino, cached_audio.stat().st_ino)

        info = self.consolidator.inspect_file(cand_file)
        self.assertEqual(info["case"], "E")
        self.assertEqual(info["action"], "MOVE_TO_QUARANTINE")

        self.consolidator.consolidate()
        self.assertFalse(cand_file.exists())
        self.assertTrue(cached_audio.exists())

    def test_case_F_content_conflict_preserved(self):
        """Cas F : Inodes distincts ET hash différent (conflit de contenu) -> PRÉSERVÉ (jamais touché)."""
        job_id = "job_conflict333"
        track_id = "trk_conflict_track"

        self.mock_jobs[job_id] = {
            "id": job_id,
            "track_id": track_id,
            "user_id": "usr_test",
            "status": "succeeded",
        }

        track_key = cs.get_track_key(track_id)
        cached_audio = self.global_cache / f"{track_key}.audio"
        cached_audio.write_bytes(b"version A in cache")

        cand_file = self.downloads_dir / f"{job_id}.mp3"
        cand_file.write_bytes(b"version B divergent content")
        self._set_file_age(cand_file, seconds_ago=7200)

        info = self.consolidator.inspect_file(cand_file)
        self.assertEqual(info["case"], "F")
        self.assertEqual(info["action"], "PRESERVE_AND_FLAG")

        self.consolidator.consolidate()
        # Le fichier conflictuel DOIT être préservé
        self.assertTrue(cand_file.exists())

    def test_case_G_empty_corrupted_removed(self):
        """Cas G : Fichier vide de 0 octet âgé de plus de 2 heures -> SUPPRIMÉ."""
        empty_file = self.downloads_dir / "job_empty_failed.mp3"
        empty_file.write_bytes(b"")
        self._set_file_age(empty_file, seconds_ago=10000)

        info = self.consolidator.inspect_file(empty_file)
        self.assertEqual(info["case"], "G")
        self.assertEqual(info["action"], "REMOVE_CORRUPTED")

        self.consolidator.consolidate()
        self.assertFalse(empty_file.exists())

    def test_purge_trash_7_day_retention_policy(self):
        """Vérifie que la purge respecte strictement le délai de rétention de 7 jours."""
        trash_base = self.downloads_dir / "_legacy_trash"
        recent_quarantine = trash_base / "quarantine_recent"
        recent_quarantine.mkdir(parents=True, exist_ok=True)
        (recent_quarantine / "dummy.mp3").write_bytes(b"recent trash")
        self._set_file_age(recent_quarantine, seconds_ago=86400 * 2)  # 2 jours

        old_quarantine = trash_base / "quarantine_old"
        old_quarantine.mkdir(parents=True, exist_ok=True)
        (old_quarantine / "dummy.mp3").write_bytes(b"old trash")
        self._set_file_age(old_quarantine, seconds_ago=86400 * 10)  # 10 jours

        # Purge standard : ne doit purger que le dossier de 10 jours
        self.consolidator.purge_trash(force_immediate=False, min_age_days=7)

        self.assertTrue(recent_quarantine.exists())
        self.assertFalse(old_quarantine.exists())

        # Purge forcée immédiate : purge tout
        self.consolidator.purge_trash(force_immediate=True)
        self.assertFalse(recent_quarantine.exists())


if __name__ == "__main__":
    unittest.main()
