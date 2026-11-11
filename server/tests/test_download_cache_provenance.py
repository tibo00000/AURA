"""Native cache authority is independent of names, aliases and private metadata."""
import asyncio
import hashlib
import json
import threading
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from app.core.aura_id_codec import build_aura_id
from app.domain.models import DownloadJob
from app.services import download_service as module
from app.services.exceptions import BadRequest, Conflict, NotFound
from app.api.routes import sync_files
from tests.test_download_job_security import service, row, A, B
from tests.test_download_delete_contract import api, bearer
from tests.native_cache_fixture import register_native


def test_personal_delete_and_reassignment_propagation_share_storage_guard(service, tmp_path, monkeypatch):
    from types import SimpleNamespace
    instance, db = service
    track = 'trk_deezer_654321'
    source = tmp_path / 'native.mp3'
    source.write_bytes(b'ID3-NATIVE-OLD-AUDIO')
    assert register_native(A, track, source)
    audio, meta = sync_files._paths(A, track)
    source.write_bytes(b'ID3-NATIVE-REPLACEMENT')
    assert register_native(B, track, source)
    cached = module._find_globally_cached_track(track)[0]
    entered, release = threading.Event(), threading.Event()
    original = module.os.replace
    def pause_replace(source, target, *args, **kwargs):
        if Path(target) == audio and not entered.is_set():
            entered.set()
            assert release.wait(5)
        return original(source, target, *args, **kwargs)
    monkeypatch.setattr(module.os, 'replace', pause_replace)
    async def scenario():
        propagation = asyncio.create_task(asyncio.to_thread(
            module._propagate_reassigned_track_to_all_users, track, cached))
        deletion = None
        try:
            assert await asyncio.to_thread(entered.wait, 3)
            deletion = asyncio.create_task(sync_files.delete_sync_file(track, SimpleNamespace(id=A)))
            await asyncio.sleep(0)
            assert not deletion.done()
        finally:
            release.set()
        await propagation
        response = await deletion
        assert response.data['deleted'] is True
        assert not audio.exists() and not meta.exists()
        # The opposite order cannot recreate an entry which is already absent.
        await asyncio.to_thread(module._propagate_reassigned_track_to_all_users, track, cached)
        assert not audio.exists() and not meta.exists()
        assert sync_files._paths(B, track)[0].exists() and cached.exists()
    asyncio.run(scenario())


@pytest.mark.parametrize('track', ['trk_spotify_job_secret', 'trk_local_job_secret',
    build_aura_id('track', 'custom', '../downloads/job_secret'),
    build_aura_id('track', 'custom', '/tmp/job_secret')])
def test_legacy_names_and_opaque_paths_never_authorize_global_cache(service, track):
    instance, db = service
    witness = module.DOWNLOADS_DIR / 'job_secret.mp3'
    witness.write_bytes(b'ID3-PRIVATE-JOB-SECRET-WITNESS')
    assert module._find_globally_cached_track(track) is None
    assert not module.is_track_in_global_cache(track, title='Same', artist_name='Artist')
    assert not (module._get_sync_base() / '_global_cache').exists()
    assert witness.read_bytes() == b'ID3-PRIVATE-JOB-SECRET-WITNESS'


@pytest.mark.parametrize('location', ['personal', 'legacy_cache', 'unverified_native'])
def test_valid_hash_and_metadata_are_not_publication_authority(service, location):
    instance, db = service
    track = 'trk_deezer_123'
    root = module._get_sync_base()
    if location == 'personal':
        folder = root / hashlib.sha256(B.encode()).hexdigest()
    elif location == 'legacy_cache':
        folder = root / '_global_cache'
    else:
        folder = root / '_global_cache' / '_native_v1'
    folder.mkdir(parents=True)
    audio = folder / (module._get_track_key(track) + '.audio')
    audio.write_bytes(b'ID3-PRIVATE-AUDIO-WITH-VALID-NAME')
    audio.with_suffix('.json').write_text(json.dumps(dict(track_id=track, title='Same', artist_name='Artist')))
    before = {str(p): p.read_bytes() for p in folder.iterdir() if p.is_file()}
    assert module._find_globally_cached_track(track) is None
    assert not module.is_track_in_global_cache(track, title='Same', artist_name='Artist')
    assert not module._link_cached_track_to_user(A, track, audio, {})
    assert not module._auto_register_in_sync_files(A, track, audio)
    assert module.get_cached_track_keys_and_doublets() == (set(), {})
    assert before == {str(p): p.read_bytes() for p in folder.iterdir() if p.is_file()}


@pytest.mark.parametrize('track', ['track:local:123', 'trk_deezer_123', 'job_1'])
def test_private_upload_stays_private_even_under_catalogue_or_job_name(api, track):
    client, instance, db = api
    client.app.include_router(sync_files.router)
    content = b'ID3-PRIVATE-UPLOADED-BY-A'
    assert client.post('/me/sync/files/' + track, headers=bearer(A),
                       files={'file': ('sample.mp3', content, 'audio/mpeg')}).status_code == 201
    assert client.get('/me/sync/files/' + track, headers=bearer(A)).content == content
    assert client.get('/me/sync/files/' + track, headers=bearer(B)).status_code == 404
    assert not module.is_track_in_global_cache(track)
    assert not (module._get_sync_base() / '_global_cache').exists()


def test_upload_replacement_detaches_native_hardlink_and_keeps_shared_bytes(api, tmp_path):
    client, instance, db = api
    client.app.include_router(sync_files.router)
    track = 'trk_deezer_444'
    source = tmp_path / 'source.mp3'
    source.write_bytes(b'ID3-LEGITIMATE-NATIVE-SHARED-AUDIO')
    assert register_native(A, track, source)
    cached = module._find_globally_cached_track(track)
    assert module._link_cached_track_to_user(B, track, *cached)
    personal_a, _ = sync_files._paths(A, track)
    personal_b, _ = sync_files._paths(B, track)
    assert personal_a.stat().st_ino == personal_b.stat().st_ino
    response = client.post('/me/sync/files/' + track, headers=bearer(A),
                           files={'file': ('private.mp3', b'PRIVATE-REPLACEMENT', 'audio/mpeg')})
    assert response.status_code == 201
    assert personal_a.read_bytes() == b'PRIVATE-REPLACEMENT'
    assert personal_b.read_bytes() == cached[0].read_bytes() == source.read_bytes()
    assert personal_a.stat().st_ino != personal_b.stat().st_ino
    assert client.get('/me/sync/files/' + track, headers=bearer(B)).content == source.read_bytes()


def test_failed_upload_keeps_existing_shared_inode(api, tmp_path, monkeypatch):
    client, instance, db = api
    from starlette.datastructures import UploadFile
    track = 'trk_deezer_445'
    source = tmp_path / 'source.mp3'
    source.write_bytes(b'ID3-LEGITIMATE-NATIVE-SHARED-AUDIO')
    assert register_native(A, track, source)
    original = sync_files._paths(A, track)[0]
    async def failure(*args):
        raise OSError('synthetic failed upload')
    monkeypatch.setattr(UploadFile, 'read', failure)
    client.app.include_router(sync_files.router)
    assert client.post('/me/sync/files/' + track, headers=bearer(A),
                       files={'file': ('private.mp3', b'PRIVATE', 'audio/mpeg')}).status_code == 500
    assert original.read_bytes() == source.read_bytes()
    assert not list(original.parent.glob('_upload_*'))


def test_receipt_and_content_are_both_required_after_native_publication(service, tmp_path):
    instance, db = service
    track = 'trk_deezer_1122'
    source = tmp_path / 'source.mp3'
    source.write_bytes(b'ID3-NATIVE-AUTHORIZED-AUDIO')
    assert register_native(A, track, source)
    audio, metadata = module._find_globally_cached_track(track)
    audio.write_bytes(b'PRIVATE-INODE-TAMPER')
    assert module._find_globally_cached_track(track) is None
    assert not module._link_cached_track_to_user(B, track, audio, metadata)
    assert audio.read_bytes() == b'PRIVATE-INODE-TAMPER'  # Lookup never evicts on read.


def test_global_symlink_is_never_a_trusted_entry(service, tmp_path):
    instance, db = service
    track = 'trk_deezer_1234'
    source = tmp_path / 'source.mp3'
    source.write_bytes(b'ID3-NATIVE-AUTHORIZED-AUDIO')
    assert register_native(A, track, source)
    audio, metadata = module._find_globally_cached_track(track)
    audio.unlink()
    try:
        audio.symlink_to(source)
    except OSError:
        pytest.skip('Symlink privilege unavailable on Windows; exercised on Linux')
    assert module._find_globally_cached_track(track) is None
    assert source.read_bytes() == b'ID3-NATIVE-AUTHORIZED-AUDIO'


def test_get_file_lookup_and_delete_are_ordered_without_backfill(service, monkeypatch):
    instance, db = service
    db.rows = [row(status='succeeded')]
    witness = module.DOWNLOADS_DIR / 'job_1.mp3'
    witness.write_bytes(b'ID3-LEGACY-OWNED-WITNESS')
    entered, release, delete_read = threading.Event(), threading.Event(), threading.Event()
    original_find, original_get = module._find_globally_cached_track, instance.get_job
    def paused_find(track):
        entered.set()
        assert release.wait(5)
        return original_find(track)
    def observed_get(*args):
        result = original_get(*args)
        if entered.is_set():
            delete_read.set()
        return result
    monkeypatch.setattr(module, '_find_globally_cached_track', paused_find)
    monkeypatch.setattr(instance, 'get_job', observed_get)
    async def scenario():
        reading = asyncio.create_task(instance.get_download_file(A, 'job_1'))
        try:
            assert await asyncio.to_thread(entered.wait, 3)
            deleting = asyncio.create_task(instance.delete_job(A, 'job_1'))
            assert await asyncio.to_thread(delete_read.wait, 3)
            assert not deleting.done()
        finally:
            release.set()
        assert await reading == witness
        await deleting
        assert not db.rows and not witness.exists()
        assert not (module._get_sync_base() / '_global_cache').exists()
    asyncio.run(scenario())


def test_delete_before_get_recheck_prevents_even_cache_lookup(service, monkeypatch):
    instance, db = service
    db.rows = [row(status='succeeded')]
    entered, release = threading.Event(), threading.Event()
    original_get = instance.get_job
    once = threading.Lock()
    def paused_get(*args):
        result = original_get(*args)
        if once.acquire(blocking=False):
            entered.set()
            assert release.wait(5)
        return result
    lookup = MagicMock()
    monkeypatch.setattr(instance, 'get_job', paused_get)
    monkeypatch.setattr(module, '_find_globally_cached_track', lookup)
    async def scenario():
        reading = asyncio.create_task(instance.get_download_file(A, 'job_1'))
        try:
            assert await asyncio.to_thread(entered.wait, 3)
            await instance.delete_job(A, 'job_1')
        finally:
            release.set()
        with pytest.raises(NotFound):
            await reading
        lookup.assert_not_called()
    asyncio.run(scenario())


def test_restart_rechecks_sql_after_waiting_for_admission(service, monkeypatch):
    instance, db = service
    db.rows = [row(status='failed')]
    read = threading.Event()
    original = instance.get_job
    def observe(*args):
        result = original(*args)
        read.set()
        return result
    monkeypatch.setattr(instance, 'get_job', observe)
    async def scenario():
        gate = instance._admission(A, 'trk_shared')
        await gate.acquire()
        retry = asyncio.create_task(instance.retry_job(A, 'job_1'))
        assert await asyncio.to_thread(read.wait, 3)
        db.rows[0]['status'] = 'succeeded'
        gate.release()
        with pytest.raises(BadRequest):
            await retry
        assert not db.mutations and not instance._attempts
    asyncio.run(scenario())


def test_reassign_waits_for_old_attempt_before_admitting_new_pair(service, monkeypatch):
    instance, db = service
    db.rows = [row()]
    async def scenario():
        entered, release_old, new_entered, release_new = [asyncio.Event() for _ in range(4)]
        async def worker(attempt, hint):
            if attempt.job.id == 'job_1':
                entered.set()
                await release_old.wait()
            else:
                new_entered.set()
                await release_new.wait()
        monkeypatch.setattr(instance, '_run_download_job_impl', worker)
        old = instance._start_download_task(DownloadJob.from_dict(db.rows[0]))
        await entered.wait()
        previous = instance._attempts['job_1']
        reassign = asyncio.create_task(instance.reassign_track_audio(A, 'trk_shared', 'synthetic'))
        assert await asyncio.to_thread(previous.cancelled.wait, 3)
        assert 'job_1' in instance._attempts and not reassign.done()
        create = asyncio.create_task(instance.create_job(A, 'trk_shared'))
        release_old.set()
        replacement = await reassign
        reused = await create
        await new_entered.wait()
        assert reused.id == replacement.id
        assert len(instance._attempts) == 1 and 'job_1' not in instance._attempts
        assert len([r for r in db.rows if r['status'] in ('queued', 'running')]) == 1
        release_new.set()
        await instance._workers.join()
        await old
    asyncio.run(scenario())


def test_repeated_shutdown_cancellation_keeps_native_future_and_finalization(service, monkeypatch):
    instance, db = service
    db.rows = [row()]
    entered, release = threading.Event(), threading.Event()
    monkeypatch.setattr(module, '_find_globally_cached_track', lambda _: None)
    class Native:
        def __init__(self, opts): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def extract_info(self, *args, **kwargs):
            entered.set()
            assert release.wait(5)
            return None
    monkeypatch.setattr(module.yt_dlp, 'YoutubeDL', Native)
    async def scenario():
        instance._start_download_task(DownloadJob.from_dict(db.rows[0]), {'resolved_video_id': 'synthetic'})
        assert await asyncio.to_thread(entered.wait, 3)
        attempt = instance._attempts['job_1']
        closing = asyncio.create_task(instance.shutdown())
        try:
            assert await asyncio.to_thread(attempt.cancelled.wait, 3)
            for _ in range(3):
                closing.cancel()
                await asyncio.sleep(0)
                assert not closing.done() and not attempt.native_future.done()
                assert instance._attempts['job_1'] is attempt
        finally:
            release.set()
        await asyncio.wait_for(closing, 3)
        assert not instance._attempts and attempt.finalizer.done()
        assert not instance._workers.tasks and not instance._finalizers.tasks
        assert db.rows[0]['status'] == 'cancelled'
    asyncio.run(scenario())
