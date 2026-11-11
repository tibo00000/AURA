"""SEC-003.1 adversarial probes: synthetic data only, regressions and provenance controls."""
import asyncio
import hashlib
import threading
from types import SimpleNamespace
import pytest

from tests.test_download_job_security import service, row, A, B
from tests.test_download_delete_contract import api, bearer
from app.services import download_service as downloads
from app.services.download_lifecycle import DownloadAttempt
from app.domain.models import DownloadJob
from app.core.aura_id_codec import build_aura_id
from app.services.exceptions import ProviderUnavailable, BadRequest, Conflict


async def join_workers(instance):
    tasks = [a.task for a in tuple(instance._attempts.values()) if a.task]
    await asyncio.gather(*tasks, return_exceptions=True)


def test_owner_cannot_publish_file_outside_download_root(api, tmp_path, monkeypatch):
    client, instance, db = api
    async def no_network(attempt, hint):
        await instance._update_job_status(attempt, status='failed')
    monkeypatch.setattr(instance, '_run_download_job_impl', no_network)
    sync = tmp_path / 'sync'
    victim_dir = sync / hashlib.sha256(B.encode()).hexdigest()
    victim_dir.mkdir(parents=True)
    victim_key = hashlib.sha256(b'trk_private_B').hexdigest()
    victim = victim_dir / (victim_key + '.audio')
    private = b'ID3-SYNTHETIC-B-ONLY-AUDIO-DO-NOT-PUBLISH'
    victim.write_bytes(private)
    provider_id = '../sync/' + victim_dir.name + '/' + victim_key
    crafted = build_aura_id('track', 'custom', provider_id)
    response = client.post('/downloads', headers=bearer(A), json={'track_id': crafted})
    assert response.status_code == 201
    job_id = response.json()['data']['job_id']
    downloaded = client.get('/downloads/' + job_id + '/file', headers=bearer(A))
    print('OBSERVATION traversal: create=', response.status_code, 'file=', downloaded.status_code,
          'B_bytes_returned_to_A=', downloaded.content == private)
    assert victim.read_bytes() == private
    assert downloaded.status_code in (400, 404)
    assert downloaded.content != private, 'A received B synthetic file through untrusted provider alias'


def test_cancelled_create_does_not_leave_queued_job_without_worker(service, monkeypatch):
    instance, db = service
    async def scenario():
        entered, resume, finish = asyncio.Event(), asyncio.Event(), asyncio.Event()
        original = instance._with_attempt
        async def observed(*args):
            entered.set()
            await resume.wait()
            return await original(*args)
        async def worker(attempt, source_hint):
            await finish.wait()
            await instance._update_job_status(attempt, status='failed')
        monkeypatch.setattr(instance, '_with_attempt', observed)
        monkeypatch.setattr(instance, '_run_download_job_impl', worker)
        task = asyncio.create_task(instance.create_job(A, 'trk_shared'))
        try:
            await asyncio.wait_for(entered.wait(), 3)
            task.cancel()
            await asyncio.sleep(0)  # Scheduler handshake, not a timing delay.
            task.cancel()
            await asyncio.sleep(0)
            assert not task.done()
        finally:
            resume.set()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 3)
        current = db.rows[0]
        assert current['status'] != 'queued' or current['id'] in instance._attempts
        finish.set()
        await join_workers(instance)
        assert not instance._attempts and db.rows[0]['status'] == 'failed'
    asyncio.run(scenario())


def test_cancelled_finalizer_discards_old_context(service, monkeypatch):
    instance, db = service
    db.rows = [row(status='failed')]
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()
        async def progress():
            entered.set()
            await release.wait()
        async def implementation(attempt, source_hint):
            attempt.progress_tasks.add(asyncio.create_task(progress()))
        monkeypatch.setattr(instance, '_run_download_job_impl', implementation)
        task = instance._start_download_task(DownloadJob.from_dict(db.rows[0]))
        attempt = instance._attempts['job_1']
        await asyncio.wait_for(entered.wait(), 3)
        for _ in range(3):
            task.cancel()
            await asyncio.sleep(0)
            assert instance._attempts.get('job_1') is attempt and not task.done()
        release.set()
        await asyncio.wait_for(task, 3)
        assert 'job_1' not in instance._attempts
        assert attempt.finalizer.done()
    asyncio.run(scenario())


def test_committed_insert_with_lost_reply_has_recoverable_admission(service, monkeypatch):
    instance, db = service
    original = db.table
    def table(name):
        query = original(name)
        execute = query.execute
        def lose_reply():
            result = execute()
            if query.operation == 'insert':
                raise TimeoutError('synthetic transport loss after commit')
            return result
        query.execute = lose_reply
        return query
    monkeypatch.setattr(db, 'table', table)
    async def scenario():
        finish = asyncio.Event()
        async def worker(attempt, source_hint):
            await finish.wait()
        monkeypatch.setattr(instance, '_run_download_job_impl', worker)
        job = await instance.create_job(A, 'trk_shared')
        again = await instance.create_job(A, 'trk_shared')
        assert again.id == job.id and job.id in instance._attempts
        assert len(db.rows) == 1 and not instance._pending_admissions
        finish.set()
        await join_workers(instance)
    asyncio.run(scenario())


def test_ambiguous_delete_representation_returns_503(api, monkeypatch):
    client, instance, db = api
    db.rows = [row()]
    original = db.table
    def table(name):
        query = original(name)
        execute = query.execute
        def unknown_shape():
            result = execute()
            return SimpleNamespace(data=None) if query.operation == 'delete' else result
        query.execute = unknown_shape
        return query
    monkeypatch.setattr(db, 'table', table)
    response = client.delete('/downloads/job_1', headers=bearer(A))
    print('OBSERVATION malformed delete representation: http=', response.status_code, 'row_deleted=', not db.rows)
    assert response.status_code == 503, 'ambiguous mutation response is mapped to missing resource instead of SQL uncertainty'


def test_create_and_retry_share_active_admission(service, monkeypatch):
    instance, db = service
    db.rows = [row(status='failed')]
    blocked, release_insert = threading.Event(), threading.Event()
    original = db.table
    def table(name):
        query = original(name)
        execute = query.execute
        def pause_insert():
            if query.operation == 'insert':
                blocked.set()
                if not release_insert.wait(5):
                    raise RuntimeError('test barrier timed out')
            return execute()
        query.execute = pause_insert
        return query
    monkeypatch.setattr(db, 'table', table)
    async def scenario():
        hold = asyncio.Event()
        retry_read = threading.Event()
        original_get = instance.get_job
        def observed_get(*args):
            result = original_get(*args)
            if blocked.is_set():
                retry_read.set()
            return result
        monkeypatch.setattr(instance, 'get_job', observed_get)
        async def worker(attempt, source_hint):
            await hold.wait()
        monkeypatch.setattr(instance, '_run_download_job_impl', worker)
        create = asyncio.create_task(instance.create_job(A, 'trk_shared'))
        try:
            assert await asyncio.wait_for(asyncio.to_thread(blocked.wait, 4), 5)
            retry = asyncio.create_task(instance.retry_job(A, 'job_1'))
            assert await asyncio.wait_for(asyncio.to_thread(retry_read.wait, 3), 4)
            assert not retry.done()
            release_insert.set()
            await asyncio.wait_for(create, 4)
            with pytest.raises((Conflict, BadRequest)):
                await asyncio.wait_for(retry, 4)
            active = [r for r in db.rows if r['status'] in ('queued', 'running')]
            assert len(active) == 1 and len(instance._attempts) == 1
        finally:
            release_insert.set()
            hold.set()
            await asyncio.gather(create, return_exceptions=True)
            await join_workers(instance)
    asyncio.run(scenario())
