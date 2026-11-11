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
from unittest.mock import MagicMock, patch

# Mock external optional audio libraries if not present in local test venv
for mod in ("yt_dlp", "ytmusicapi"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()
try:
    import rapidfuzz  # noqa: F401
except ImportError:
    sys.modules["rapidfuzz"] = MagicMock()
    sys.modules["rapidfuzz.fuzz"] = MagicMock()



from app.services.download_service import (
    _get_sync_base,
    _get_global_cache_dir,
    _get_track_key,
    _find_globally_cached_track,
    _link_cached_track_to_user,
    _auto_register_in_sync_files as _raw_register,
)

from tests.native_cache_fixture import register_native as _auto_register_in_sync_files


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

    def test_orphan_eviction_disabled_without_shared_coordination(self):
        """Une entrée orpheline reste intacte tant que l’éviction est neutralisée."""
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
            self.assertEqual(pruned, 0)
            self.assertEqual(orphan_audio.read_bytes(), b"ORPHAN_DATA")
            self.assertTrue(orphan_json.exists())

        asyncio.run(run_eviction_test())

    def test_existing_track_backfill_from_user_folder(self):
        track_id = 'trk_deezer_historical_song'
        key = _get_track_key(track_id)
        personal = self.test_dir / hashlib.sha256(b'historical_owner').hexdigest()
        personal.mkdir()
        witness = personal / (key + '.audio')
        witness.write_bytes(b'HISTORICAL-PRIVATE-AUDIO')
        (personal / (key + '.json')).write_text(json.dumps({'track_id': track_id}))
        self.assertIsNone(_find_globally_cached_track(track_id))
        self.assertEqual(witness.read_bytes(), b'HISTORICAL-PRIVATE-AUDIO')
        self.assertFalse((_get_global_cache_dir() / (key + '.audio')).exists())

    def test_cross_alias_cache_hit(self):
        """
        Vérifie qu'un morceau enregistré sous 'deezer:123456' est immédiatement
        trouvé en Cache Hit lorsqu'un autre composant le cherche sous forme AURA 'trk_...'
        ou sous forme numérique '123456'.
        """
        from app.core.aura_id_codec import build_aura_id, get_track_id_aliases
        raw_numeric = "98765432"
        deezer_id = f"deezer:{raw_numeric}"
        aura_id = build_aura_id("track", "deezer", raw_numeric)

        # 1. Vérifier que les alias se réconcilient tous mutuellement
        aliases_from_deezer = get_track_id_aliases(deezer_id)
        aliases_from_aura = get_track_id_aliases(aura_id)
        aliases_from_num = get_track_id_aliases(raw_numeric)

        self.assertIn(raw_numeric, aliases_from_aura)
        self.assertIn(deezer_id, aliases_from_aura)
        self.assertIn(aura_id, aliases_from_deezer)
        self.assertIn(aura_id, aliases_from_num)

        # 2. Enregistrer un fichier avec l'ID deezer:...
        fake_audio = self.test_dir / "temp_alias.mp3"
        content = b"AUDIO_DATA_FOR_ALIAS_TEST"
        fake_audio.write_bytes(content)

        user_a = "user_alpha"
        success = _auto_register_in_sync_files(
            user_id=user_a,
            track_id=deezer_id,
            audio_file=fake_audio,
            title="Cross Alias Hit",
            artist_name="Alias Artist",
        )
        self.assertTrue(success)

        # 3. Rechercher avec l'ID opaque AURA (trk_...) : DOIT être un Cache Hit instantané !
        cached = _find_globally_cached_track(aura_id)
        self.assertIsNotNone(cached)
        cached_path, metadata = cached
        self.assertTrue(cached_path.exists())
        self.assertEqual(cached_path.read_bytes(), content)
        self.assertEqual(metadata.get("title"), "Cross Alias Hit")

        # 4. Rechercher avec l'ID numérique brut : DOIT également être un Cache Hit instantané !
        cached_num = _find_globally_cached_track(raw_numeric)
        self.assertIsNotNone(cached_num)
        cached_num_path, num_meta = cached_num
        self.assertEqual(cached_num_path.read_bytes(), content)

    def test_auth_token_query_parameter(self):
        """Vérifie que get_current_user accepte un jeton transmis via ?token= (streaming)."""
        import time
        import jwt
        from types import SimpleNamespace
        user_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        secret = "synthetic-test-signing-secret-32-bytes-minimum"
        config = SimpleNamespace(supabase_url="https://auth.example.test", supabase_jwt_secret=secret)
        access_token = jwt.encode({
            "sub": user_id, "aud": "authenticated", "iss": "https://auth.example.test/auth/v1",
            "role": "authenticated", "exp": int(time.time()) + 300,
        }, secret, algorithm="HS256")
        async def run_auth_test():
            from app.core.auth import get_current_user
            
            # 1. Via Header
            user_header = await get_current_user(authorization=f"Bearer {access_token}")
            self.assertEqual(user_header.id, user_id)

            # 2. Via Query param
            user_query = await get_current_user(token=access_token)
            self.assertEqual(user_query.id, user_id)

            # 3. Via Query param avec préfixe Bearer
            user_query_bearer = await get_current_user(token=f"Bearer {access_token}")
            self.assertEqual(user_query_bearer.id, user_id)

        with patch("app.core.auth.get_settings", return_value=config):
            asyncio.run(run_auth_test())

    def test_sync_file_download_endpoint_cross_alias(self):
        """Vérifie que l'endpoint download_sync_file sert le fichier même si stocké sous un autre alias."""
        async def run_endpoint_test():
            from app.api.routes.sync_files import download_sync_file
            from app.core.auth import AuthenticatedUser
            from app.core.aura_id_codec import build_aura_id

            raw_num = "55443322"
            deezer_id = f"deezer:{raw_num}"
            aura_id = build_aura_id("track", "deezer", raw_num)

            # Enregistrer sous deezer:... pour user_a
            fake_audio = self.test_dir / "temp_ep.mp3"
            fake_audio.write_bytes(b"ENDPOINT_STREAM_CONTENT")
            _auto_register_in_sync_files(
                user_id="user_owner",
                track_id=deezer_id,
                audio_file=fake_audio,
                title="Stream Endpoint Song",
            )

            # User B (ami) demande la lecture en passant l'AURA ID (trk_...)
            user_friend = AuthenticatedUser(id="friend_uuid_123", token="mock")
            response = await download_sync_file(track_id=aura_id, current_user=user_friend)

            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.headers.get("accept-ranges"), "bytes")
            self.assertTrue(Path(response.path).exists())
            self.assertEqual(Path(response.path).read_bytes(), b"ENDPOINT_STREAM_CONTENT")

        asyncio.run(run_endpoint_test())

    def test_backfill_from_downloads_dir(self):
        import app.services.download_service as ds
        folder = self.test_dir / 'downloads'
        folder.mkdir()
        witness = folder / 'trk_deezer_777123.mp3'
        witness.write_bytes(b'LEGACY-WITNESS')
        with patch.object(ds, 'DOWNLOADS_DIR', folder):
            self.assertIsNone(_find_globally_cached_track('trk_deezer_777123'))
        self.assertEqual(witness.read_bytes(), b'LEGACY-WITNESS')

    def test_is_track_in_global_cache_in_memory(self):
        """Vérifie que is_track_in_global_cache détecte en RAM O(1) les pistes et alias."""
        from app.services.download_service import (
            is_track_in_global_cache,
            get_cached_track_keys_set,
            _cached_global_keys,
        )
        import app.services.download_service as ds

        track_id = "deezer:11223344"
        key = _get_track_key(track_id)
        cache_dir = _get_global_cache_dir()
        audio_file = cache_dir / f"{key}.audio"
        source = self.test_dir / 'source.mp3'
        source.write_bytes(b'TEST_AUDIO_CONTENT')
        self.assertTrue(_auto_register_in_sync_files('native_owner', track_id, source))

        # Forcer le rafraîchissement
        ds._cached_global_keys_last_refresh = 0.0

        # Vérification par identifiant canonique et alias
        self.assertTrue(is_track_in_global_cache(track_id))
        self.assertTrue(is_track_in_global_cache("trk_deezer_11223344"))
        self.assertTrue(is_track_in_global_cache("11223344"))

        # Piste inexistante
        self.assertFalse(is_track_in_global_cache("deezer:9999999999"))

    def test_ephemeral_streaming_without_personal_library_hardlink(self):
        """Vérifie que le streaming éphémère ne crée AUCUN hardlink ni JSON dans le dossier personnel de l'utilisateur."""
        from app.api.routes.sync_files import download_sync_file, _paths, _user_dir
        from app.core.auth import AuthenticatedUser
        import asyncio

        track_id = "deezer:55667788"
        key = _get_track_key(track_id)
        cache_dir = _get_global_cache_dir()
        audio_file = cache_dir / f"{key}.audio"
        json_file = cache_dir / f"{key}.json"
        source = self.test_dir / 'source.mp3'
        source.write_bytes(b'STREAM_AUDIO_BYTES')
        self.assertTrue(_auto_register_in_sync_files('native_owner', track_id, source, title='Ephemeral Song'))

        test_user = AuthenticatedUser(id="user_friend_uuid", token="test_token_123")
        user_personal_dir = _user_dir(test_user.id)

        # Avant le stream, le dossier utilisateur est vide
        self.assertEqual(len(list(user_personal_dir.glob("*"))), 0)

        # Appel direct de download_sync_file (simule GET /me/sync/files/{track_id})
        async def run_stream():
            resp = await download_sync_file(track_id=track_id, current_user=test_user)
            self.assertEqual(resp.path, str(audio_file))

        asyncio.run(run_stream())

        # APRÈS le stream : AUCUN fichier ne doit avoir été créé dans le dossier personnel (0 hardlink, 0 JSON)
        self.assertEqual(len(list(user_personal_dir.glob("*"))), 0)
        personal_audio, personal_json = _paths(test_user.id, track_id)
        self.assertFalse(personal_audio.exists())
        self.assertFalse(personal_json.exists())

    def test_is_track_in_global_cache_by_metadata_doublet(self):
        from app.services.download_service import is_track_in_global_cache
        track = 'track:local:1000081054'
        folder = self.test_dir / hashlib.sha256(b'private_owner').hexdigest()
        folder.mkdir()
        key = _get_track_key(track)
        (folder / (key + '.audio')).write_bytes(b'PRIVATE-AUDIO')
        (folder / (key + '.json')).write_text(json.dumps({'track_id': track,
            'title': 'Song', 'artist_name': 'Artist'}))
        self.assertFalse(is_track_in_global_cache('trk_deezer_1397669462', title='Song', artist_name='Artist'))
        self.assertFalse((_get_global_cache_dir() / (_get_track_key('trk_deezer_1397669462') + '.audio')).exists())

    def test_client_track_deezer_prefix_alias_resolution(self):
        """Vérifie que le préfixe client Android 'track:deezer:12345' réconcilie avec deezer:12345 et le cache global."""
        from app.core.aura_id_codec import get_track_id_aliases
        from app.services.download_service import (
            _find_globally_cached_track,
        )

        aliases = get_track_id_aliases("track:deezer:987654321")
        self.assertIn("deezer:987654321", aliases)
        self.assertIn("987654321", aliases)
        self.assertIn("track:deezer:987654321", aliases)

        # Enregistrer un fichier avec deezer:987654321
        fake_audio = self.test_dir / "downloaded_track_deezer.mp3"
        fake_audio.write_bytes(b"FAKE_AUDIO_DATA_FOR_DEEZER_ALIAS_TEST")

        _auto_register_in_sync_files(
            user_id="user_test_deezer_alias",
            track_id="deezer:987654321",
            audio_file=fake_audio,
            title="Deezer Alias Track",
            artist_name="Deezer Artist",
        )

        # La requête avec l'identifiant Android 'track:deezer:987654321' doit trouver le cache
        cached = _find_globally_cached_track("track:deezer:987654321")
        self.assertIsNotNone(cached)
        cached_path, metadata = cached
        self.assertTrue(cached_path.exists())
        self.assertEqual(metadata.get("title"), "Deezer Alias Track")

    def test_strict_isolation_for_track_local(self):
        """Vérifie que les identifiants locaux track:local: restent strictement isolés sans aliasing de catalogue."""
        from app.core.aura_id_codec import get_track_id_aliases

        local_id = "track:local:user_private_recording_999"
        aliases = get_track_id_aliases(local_id)
        # Ne doit JAMAIS normaliser ou créer d'alias deezer, spotify ou ytm
        self.assertEqual(aliases, [local_id])

    def test_auto_eviction_of_corrupt_cached_file(self):
        """Vérifie qu'un fichier maître corrompu (0 octet ou HTML) dans _global_cache est automatiquement purgé."""
        from app.services.download_service import (
            _find_globally_cached_track,
            _get_track_key,
            _get_global_cache_dir,
        )

        track_id = "trk_deezer_corrupt_test_555"
        track_key = _get_track_key(track_id)
        cache_dir = _get_global_cache_dir()

        corrupt_audio = cache_dir / f"{track_key}.audio"
        corrupt_json = cache_dir / f"{track_key}.json"

        # Simuler un fichier maître corrompu (ex: page d'erreur HTML ou 0 octet)
        corrupt_audio.write_bytes(b"<!DOCTYPE html><html><body>Error 502</body></html>")
        corrupt_json.write_text(json.dumps({"track_id": track_id, "title": "Corrupt Song"}), encoding="utf-8")

        self.assertTrue(corrupt_audio.exists())

        # La recherche globale doit détecter l'invalidité, purger le fichier et renvoyer None (Cache MISS)
        cached = _find_globally_cached_track(track_id)
        self.assertIsNone(cached)
        self.assertTrue(corrupt_audio.exists())
        self.assertTrue(corrupt_json.exists())

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(TEST_TEMP_DIR, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()



