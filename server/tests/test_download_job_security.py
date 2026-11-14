"""Finding 4: synthetic accounts, exact files, and deterministic lifecycle races."""
import asyncio
import copy
import inspect
import threading
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from app.config import get_settings
from app.services import download_service as downloads
from app.services.exceptions import BadRequest, Conflict, NotFound, ProviderUnavailable
from app.services.download_lifecycle import DownloadAttempt
from app.domain.models import DownloadJob

A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'


def row(job_id='job_1', user_id=A, status='queued', **changes):
    return dict(id=job_id, user_id=user_id, track_id='trk_shared', provider_name='youtube',
                status=status, attempt_count=1, **changes)


class Database:
    """Privileged SQL double: deliberately has no implicit owner filtering."""
    def __init__(self, rows=()):
        self.rows = copy.deepcopy(list(rows))
        self.mutations = []
        self.before_write = None
        self.fail_write = False
        self.lock = threading.RLock()

    def table(self, name):
        assert name == 'download_jobs'
        db = self
        class Query:
            operation = 'select'
            payload = None
            def __init__(self): self.filters = []
            def select(self, *args): return self
            def eq(self, key, value):
                self.filters.append(lambda r: r.get(key) == value)
                return self
            def in_(self, key, values):
                self.filters.append(lambda r: r.get(key) in values)
                return self
            def update(self, payload):
                self.operation, self.payload = 'update', payload
                return self
            def delete(self): self.operation = 'delete'; return self
            def insert(self, payload):
                self.operation, self.payload = 'insert', payload
                return self
            def execute(self):
                with db.lock:
                    if self.operation != 'select':
                        if db.fail_write: raise RuntimeError('synthetic SQL failure')
                        if db.before_write:
                            callback, db.before_write = db.before_write, None
                            callback(db)
                    matched = [r for r in db.rows if all(f(r) for f in self.filters)]
                    previous = copy.deepcopy(matched)
                    if self.operation == 'delete':
                        db.rows[:] = [r for r in db.rows if r not in matched]
                    elif self.operation == 'update':
                        for r in matched: r.update(self.payload)
                        previous = copy.deepcopy(matched)
                    elif self.operation == 'insert':
                        assert not any(r['id'] == self.payload['id'] for r in db.rows)
                        db.rows.append(copy.deepcopy(self.payload))
                        previous = [copy.deepcopy(self.payload)]
                    if self.operation != 'select':
                        db.mutations.append((self.operation, copy.deepcopy(self.payload)))
                    return SimpleNamespace(data=previous)
        return Query()


@pytest.fixture
def service(tmp_path, monkeypatch):
    monkeypatch.setenv('APP_ENV', 'test')
    monkeypatch.setenv('ENABLE_STARTUP_RECOVERY', 'false')
    monkeypatch.setenv('ENABLE_CACHE_BACKFILL', 'false')
    monkeypatch.setenv('SYNC_FILES_DIR', str(tmp_path / 'sync'))
    monkeypatch.setattr(downloads, 'DOWNLOADS_DIR', tmp_path / 'downloads')
    get_settings.cache_clear()
    db = Database()
    instance = downloads.DownloadService(supabase_client=db)
    yield instance, db
    get_settings.cache_clear()


async def invoke(function, **kwargs):
    result = function(**kwargs)
    return await result if inspect.isawaitable(result) else result


@pytest.mark.parametrize(('status', 'operation'), [
    ('failed', 'retry'), ('requires_resolution', 'resolve'), ('succeeded', 'repair'),
])
def test_playlist_job_cannot_be_restarted_with_an_unapproved_source(service, status, operation):
    instance, db = service
    db.rows = [row(status=status, playlist_acquisition=True)]

    async def scenario():
        with pytest.raises(Conflict, match='Retry this acquisition from the playlist'):
            if operation == 'retry':
                await instance.retry_job(A, 'job_1')
            elif operation == 'resolve':
                await instance.resolve_job(A, 'job_1', 'arbitrary11')
            else:
                await instance.create_job(A, 'trk_shared', source_hint={'resolved_video_id': 'arbitrary11'})

    asyncio.run(scenario())
    assert db.mutations == []
    assert instance._attempts == {}


def test_graceful_shutdown_leaves_playlist_jobs_resumable(service):
    instance, db = service
    db.rows = [row(status='running', playlist_acquisition=True)]
    attempt = DownloadAttempt(DownloadJob.from_dict(db.rows[0]))
    instance._attempts[attempt.job.id] = attempt

    async def scenario():
        instance._closing = True
        attempt.cancelled.set()
        await instance._finalize_attempt(attempt, failed=False)

    asyncio.run(scenario())
    assert db.rows[0]['status'] == 'queued'
    assert db.rows[0]['error_code'] == 'playlist_worker_shutdown'
    assert db.rows[0]['attempt_count'] == 1


def test_foreign_delete_has_no_memory_sql_or_file_effect(service):
    instance, db = service
    db.rows = [row(user_id=B)]
    path = downloads.DOWNLOADS_DIR / 'job_1.mp3'
    path.write_bytes(b'B-private-audio')
    task = MagicMock()
    if hasattr(instance, '_running_tasks'):
        instance._running_tasks['job_1'] = task
    attempt = DownloadAttempt(DownloadJob.from_dict(db.rows[0]))
    if hasattr(instance, '_attempts'):
        instance._attempts['job_1'] = attempt
    async def scenario():
        with pytest.raises(NotFound):
            await invoke(instance.delete_job, user_id=A, job_id='job_1')
    asyncio.run(scenario())
    task.cancel.assert_not_called()
    assert not db.mutations
    assert not attempt.cancelled.is_set()
    assert path.read_bytes() == b'B-private-audio'
    assert not getattr(instance, '_cancelled_jobs', set())


def test_state_change_between_authorization_and_delete_does_not_cancel_or_unlink(service):
    instance, db = service
    db.rows = [row()]
    attempt = DownloadAttempt(DownloadJob.from_dict(db.rows[0]))
    instance._attempts['job_1'] = attempt
    witness = downloads.DOWNLOADS_DIR / 'job_1.mp3'
    witness.write_bytes(b'KEEP')
    db.before_write = lambda database: database.rows[0].update(status='succeeded')
    async def scenario():
        with pytest.raises(Conflict):
            await instance.delete_job(A, 'job_1')
    asyncio.run(scenario())
    assert not attempt.cancelled.is_set()
    assert witness.read_bytes() == b'KEEP'
    assert db.rows[0]['status'] == 'succeeded'


def test_sql_failure_is_not_success_and_does_not_remove_files(service):
    instance, db = service
    db.rows = [row()]
    db.fail_write = True
    witness = downloads.DOWNLOADS_DIR / 'job_1.mp3'
    witness.write_bytes(b'KEEP')
    async def scenario():
        with pytest.raises(ProviderUnavailable):
            await instance.delete_job(A, 'job_1')
    asyncio.run(scenario())
    assert witness.read_bytes() == b'KEEP'
    assert db.rows[0]['status'] == 'queued'


def test_unknown_sql_outcome_fences_attempt_without_false_success(service, monkeypatch):
    instance, db = service
    db.rows = [row()]
    attempt = DownloadAttempt(DownloadJob.from_dict(db.rows[0]))
    instance._attempts['job_1'] = attempt
    original = db.table
    def table(name):
        query = original(name)
        execute = query.execute
        def uncertain():
            result = execute()
            if query.operation == 'delete':
                raise RuntimeError('synthetic response lost after commit')
            return result
        query.execute = uncertain
        return query
    monkeypatch.setattr(db, 'table', table)
    async def scenario():
        with pytest.raises(ProviderUnavailable):
            await instance.delete_job(A, 'job_1')
        assert not await instance._update_job_status(attempt, status='succeeded')
    asyncio.run(scenario())
    assert attempt.cancelled.is_set()
    assert not db.rows


def test_real_sdk_keeps_owner_state_attempt_and_returned_representation(service):
    import httpx
    from supabase import create_client, ClientOptions
    instance, db = service
    item = row()
    calls = []
    def handler(request):
        assert request.url.host == 'example.supabase.co'
        assert request.url.path == '/rest/v1/download_jobs'
        assert request.url.params['id'] == 'eq.job_1'
        assert request.url.params['user_id'] == 'eq.' + A
        calls.append(request.method)
        if request.method == 'DELETE':
            assert request.url.params['status'] == 'eq.queued'
            assert request.url.params['attempt_count'] == 'eq.1'
            assert 'return=representation' in request.headers['prefer']
        return httpx.Response(200, json=[item])
    with httpx.Client(transport=httpx.MockTransport(handler), trust_env=False) as transport:
        instance._supabase = create_client('https://example.supabase.co', 'synthetic-key', options=ClientOptions(
            httpx_client=transport, persist_session=False, auto_refresh_token=False))
        asyncio.run(instance.delete_job(A, 'job_1'))
    assert calls == ['GET', 'GET', 'DELETE']


def test_symlink_in_attempt_does_not_delete_external_witness(service, tmp_path):
    instance, db = service
    witness = tmp_path / 'external-witness'
    witness.write_bytes(b'KEEP')
    attempt = DownloadAttempt(DownloadJob.from_dict(row()))
    workspace = attempt.prepare_workspace(downloads.DOWNLOADS_DIR)
    try:
        (workspace / 'link').symlink_to(witness)
    except OSError:
        attempt.cleanup(downloads.DOWNLOADS_DIR)
        pytest.skip('Symbolic links require a local Windows privilege; exercised on Linux')
    attempt.cleanup(downloads.DOWNLOADS_DIR)
    assert witness.read_bytes() == b'KEEP'


def test_delete_preserves_shared_hardlinks_and_unknown_suffixes(service, tmp_path):
    import os
    instance, db = service
    db.rows = [row(status='succeeded')]
    source = downloads.DOWNLOADS_DIR / 'job_1.mp3'
    source.write_bytes(b'SHARED-INODE')
    cache, personal_a, personal_b = [tmp_path / name for name in ('cache.audio', 'a.audio', 'b.audio')]
    for link in (cache, personal_a, personal_b):
        os.link(source, link)
    witness = downloads.DOWNLOADS_DIR / 'job_1.mp3.witness'
    witness.write_bytes(b'WITNESS')
    asyncio.run(instance.delete_job(A, 'job_1'))
    assert not source.exists()
    assert all(p.read_bytes() == b'SHARED-INODE' for p in (cache, personal_a, personal_b))
    assert len({p.stat().st_ino for p in (cache, personal_a, personal_b)}) == 1
    assert witness.read_bytes() == b'WITNESS'


def test_native_cancellation_keeps_context_and_prevents_late_publication(service, monkeypatch):
    instance, db = service
    db.rows = [row()]
    entered, release = threading.Event(), threading.Event()
    published = MagicMock(return_value=True)
    monkeypatch.setattr(downloads, '_find_globally_cached_track', lambda _: None)
    monkeypatch.setattr(downloads, '_auto_register_in_sync_files', published)
    class Native:
        def __init__(self, options): self.options = options
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def extract_info(self, *args, **kwargs):
            entered.set()
            assert release.wait(10)
            from pathlib import Path
            Path(self.options['outtmpl'].replace('%(ext)s', 'mp3')).write_bytes(b'ID3' + b'0' * 40)
            return {'id': 'synthetic'}  # Deliberately ignores the progress/cancel hook.
    monkeypatch.setattr(downloads.yt_dlp, 'YoutubeDL', Native)
    async def scenario():
        task = instance._start_download_task(DownloadJob.from_dict(db.rows[0]), {'resolved_video_id': 'synthetic'})
        try:
            assert await asyncio.wait_for(asyncio.to_thread(entered.wait, 5), 6)
            attempt = instance._attempts['job_1']
            workspace = attempt.workspace
            await instance.delete_job(A, 'job_1')
            assert attempt.cancelled.is_set()
            assert instance._attempts['job_1'] is attempt
            assert not attempt.native_future.done()
            assert not db.rows
        finally:
            release.set()
            await asyncio.wait_for(task, 5)
        assert 'job_1' not in instance._attempts
        assert not workspace.exists()
        assert not (downloads.DOWNLOADS_DIR / 'job_1.mp3').exists()
    asyncio.run(scenario())
    published.assert_not_called()


def test_cancel_wrapper_does_not_release_native_tracking_or_track_lock(service, monkeypatch):
    instance, db = service
    db.rows = [row()]
    entered, release = threading.Event(), threading.Event()
    monkeypatch.setattr(downloads, '_find_globally_cached_track', lambda _: None)
    class Native:
        def __init__(self, opts): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def extract_info(self, *args, **kwargs):
            entered.set()
            assert release.wait(10)
            return None
    monkeypatch.setattr(downloads.yt_dlp, 'YoutubeDL', Native)
    async def scenario():
        task = instance._start_download_task(DownloadJob.from_dict(db.rows[0]), {'resolved_video_id': 'synthetic'})
        try:
            assert await asyncio.to_thread(entered.wait, 5)
            attempt = instance._attempts['job_1']
            task.cancel()
            # Handshake through the worker's cooperative stop event, no timing sleep.
            assert await asyncio.to_thread(attempt.cancelled.wait, 5)
            assert not task.done()
            assert not attempt.native_future.done()
            assert (await instance._get_track_lock('trk_shared')).locked()
            db.rows[0]['status'] = 'failed'
            with pytest.raises(Conflict):
                await instance.retry_job(A, 'job_1')
        finally:
            release.set()
            await asyncio.wait_for(task, 5)
    asyncio.run(scenario())


def test_cancellation_wins_immediately_before_publication(service, monkeypatch):
    instance, db = service
    db.rows = [row()]
    published = MagicMock()
    monkeypatch.setattr(downloads, '_auto_register_in_sync_files', published)
    async def scenario():
        attempt = DownloadAttempt(DownloadJob.from_dict(db.rows[0]))
        instance._attempts['job_1'] = attempt
        workspace = attempt.prepare_workspace(downloads.DOWNLOADS_DIR)
        audio = workspace / 'audio.mp3'
        audio.write_bytes(b'ID3' + b'0' * 40)
        ready, resume = asyncio.Event(), asyncio.Event()
        async def candidate():
            ready.set()
            await resume.wait()
            return await instance._publish_audio(attempt, audio, {}, None)
        task = asyncio.create_task(candidate())
        await ready.wait()
        await instance.delete_job(A, 'job_1')
        resume.set()
        assert await task is False
        attempt.cleanup(downloads.DOWNLOADS_DIR)
    asyncio.run(scenario())
    published.assert_not_called()


def test_publication_wins_before_delete_and_delete_waits_for_commit(service, monkeypatch):
    instance, db = service
    db.rows = [row(status='running')]
    publishing, release, delete_read = threading.Event(), threading.Event(), threading.Event()
    original_get = instance.get_job
    def get(*args):
        result = original_get(*args)
        if publishing.is_set(): delete_read.set()
        return result
    monkeypatch.setattr(instance, 'get_job', get)
    def publish(*args, **kwargs):
        publishing.set()
        assert release.wait(10)
        return True
    monkeypatch.setattr(downloads, '_auto_register_in_sync_files', publish)
    async def scenario():
        attempt = DownloadAttempt(DownloadJob.from_dict(db.rows[0]))
        instance._attempts['job_1'] = attempt
        audio = attempt.prepare_workspace(downloads.DOWNLOADS_DIR) / 'audio.mp3'
        audio.write_bytes(b'ID3' + b'0' * 40)
        pub = asyncio.create_task(instance._publish_audio(attempt, audio, {}, None))
        try:
            assert await asyncio.to_thread(publishing.wait, 5)
            deletion = asyncio.create_task(instance.delete_job(A, 'job_1'))
            assert await asyncio.to_thread(delete_read.wait, 5)
            assert not deletion.done()
        finally:
            release.set()
        assert await asyncio.wait_for(pub, 5)
        await asyncio.wait_for(deletion, 5)
        assert not db.rows
        assert attempt.cancelled.is_set()
        assert not (downloads.DOWNLOADS_DIR / 'job_1.mp3').exists()
        attempt.cleanup(downloads.DOWNLOADS_DIR)
    asyncio.run(scenario())


@pytest.mark.parametrize('operation', ['retry', 'resolve'])
def test_only_one_concurrent_restart_is_admitted(service, monkeypatch, operation):
    instance, db = service
    db.rows = [row(status='requires_resolution')]
    async def scenario():
        started, finish = asyncio.Event(), asyncio.Event()
        async def worker(*args):
            started.set()
            await finish.wait()
        monkeypatch.setattr(instance, '_run_download_job_impl', worker)
        def restart():
            return instance.retry_job(A, 'job_1') if operation == 'retry' else instance.resolve_job(A, 'job_1', 'synthetic')
        results = await asyncio.gather(restart(), restart(), return_exceptions=True)
        await started.wait()
        attempt = instance._attempts['job_1']
        assert sum(isinstance(r, DownloadJob) for r in results) == 1
        assert sum(isinstance(r, (Conflict, BadRequest)) for r in results) == 1
        assert db.rows[0]['attempt_count'] == (2 if operation == 'retry' else 1)
        finish.set()
        await attempt.task
    asyncio.run(scenario())


def test_late_finally_and_progress_do_not_erase_or_update_new_context(service, monkeypatch):
    instance, db = service
    db.rows = [row()]
    async def scenario():
        started, finish = asyncio.Event(), asyncio.Event()
        async def worker(*args):
            started.set()
            await finish.wait()
        monkeypatch.setattr(instance, '_run_download_job_impl', worker)
        old_task = instance._start_download_task(DownloadJob.from_dict(db.rows[0]))
        await started.wait()
        old = instance._attempts['job_1']
        newer = DownloadAttempt(DownloadJob.from_dict(db.rows[0]))
        # Defensive invariant even if a future caller accidentally replaces admission state.
        instance._attempts['job_1'] = newer
        instance._schedule_progress(old, 99.0)
        finish.set()
        await old_task
        assert instance._attempts['job_1'] is newer
        assert not old.progress_tasks
        assert not db.mutations
    asyncio.run(scenario())


def test_transient_worker_read_failure_does_not_leave_unretryable_active_row(service, monkeypatch):
    instance, db = service
    db.rows = [row()]
    original = instance.get_job
    calls = 0
    def transient(*args):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise ProviderUnavailable('synthetic transient storage failure')
        return original(*args)
    monkeypatch.setattr(instance, 'get_job', transient)
    async def scenario():
        await instance._start_download_task(DownloadJob.from_dict(db.rows[0]))
        assert db.rows[0]['status'] in ('failed', 'cancelled')
        assert not instance._attempts
        async def inert(*args): pass
        monkeypatch.setattr(instance, '_run_download_job_impl', inert)
        updated = await instance.retry_job(A, 'job_1')
        assert updated.attempt_count == 2
        await instance._attempts['job_1'].task
    asyncio.run(scenario())


def test_create_cache_lookup_side_effects_are_fenced(service, monkeypatch):
    instance, db = service
    calls = []
    def lookup(track_id):
        # The real helper can backfill cache files, not merely read.
        job_id = db.rows[0]['id']
        assert job_id in instance._attempts
        assert instance._job_gate(job_id).locked()
        calls.append(track_id)
        return None
    monkeypatch.setattr(downloads, '_find_globally_cached_track', lookup)
    async def scenario():
        async def inert(*args): pass
        monkeypatch.setattr(instance, '_run_download_job_impl', inert)
        job = await instance.create_job(A, 'trk_shared')
        await instance._attempts[job.id].task
    asyncio.run(scenario())
    assert calls == ['trk_shared']


def test_transient_inline_cache_read_failure_finishes_its_inserted_job(service, monkeypatch):
    instance, db = service
    original = instance.get_job
    calls = 0
    def transient(*args):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise ProviderUnavailable('synthetic transient cache admission failure')
        return original(*args)
    monkeypatch.setattr(instance, 'get_job', transient)
    async def scenario():
        with pytest.raises(ProviderUnavailable):
            await instance.create_job(A, 'trk_shared')
    asyncio.run(scenario())
    assert not instance._attempts
    assert db.rows[0]['status'] == 'failed'


@pytest.mark.parametrize('message,diagnostic', [('Sign in required: cookies expired', 'cookie'),
    ('YouTube PO_TOKEN failed', 'po_token'), ('Provider timeout 504', 'timeout'),
    ('Video not found', 'no_match')])
def test_native_failure_keeps_actionable_client_diagnostic(service, monkeypatch, message, diagnostic):
    instance, db = service
    db.rows = [row()]
    monkeypatch.setattr(downloads, '_find_globally_cached_track', lambda _: None)
    class Native:
        def __init__(self, opts): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def extract_info(self, *args, **kwargs): raise RuntimeError(message)
    monkeypatch.setattr(downloads.yt_dlp, 'YoutubeDL', Native)
    async def scenario():
        await instance._start_download_task(DownloadJob.from_dict(db.rows[0]), {'resolved_video_id': 'synthetic'})
    asyncio.run(scenario())
    assert db.rows[0]['status'] == 'failed'
    assert diagnostic in (str(db.rows[0]['error_code']) + str(db.rows[0]['error_message'])).lower()
@pytest.mark.parametrize('job_id', ['*', 'job_?', 'job_[1]', '../job_1', 'job_1%2a'])
def test_pattern_and_path_inputs_preserve_witness(service, job_id):
    instance, db = service
    path = downloads.DOWNLOADS_DIR / 'job_witness.mp3'
    path.write_bytes(b'WITNESS')
    async def scenario():
        with pytest.raises((BadRequest, NotFound)):
            await invoke(instance.delete_job, user_id=A, job_id=job_id)
    asyncio.run(scenario())
    assert path.read_bytes() == b'WITNESS'
    assert not db.mutations
