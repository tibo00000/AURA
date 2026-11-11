"""Review regressions: transport uncertainty, malformed replies and pair fencing."""
import asyncio
import threading
from types import SimpleNamespace

import pytest

from app.services.exceptions import ProviderUnavailable
from tests.test_download_job_security import service, row, A
from tests.test_download_delete_contract import api, bearer


def test_uncommitted_insert_is_retried_with_the_same_id(service, monkeypatch):
    instance, db = service
    original = db.table
    inserted_ids = []
    def table(name):
        query = original(name)
        execute = query.execute
        def fail_once():
            if query.operation == 'insert':
                inserted_ids.append(query.payload['id'])
                if len(inserted_ids) == 1:
                    raise TimeoutError('synthetic transport loss before commit')
            return execute()
        query.execute = fail_once
        return query
    monkeypatch.setattr(db, 'table', table)
    async def scenario():
        release = asyncio.Event()
        async def worker(attempt, hint):
            await release.wait()
        monkeypatch.setattr(instance, '_run_download_job_impl', worker)
        with pytest.raises(ProviderUnavailable):
            await instance.create_job(A, 'trk_shared')
        assert not db.rows and len(instance._pending_admissions) == 1
        job = await instance.create_job(A, 'trk_shared')
        assert inserted_ids == [job.id, job.id]
        assert len(db.rows) == len(instance._attempts) == 1
        assert not instance._pending_admissions
        release.set()
        await instance._workers.join()
    asyncio.run(scenario())


@pytest.mark.parametrize('operation', ['create', 'retry', 'resolve', 'reassign'])
def test_unknown_pending_outcome_fences_every_pair_mutator(service, monkeypatch, operation):
    instance, db = service
    db.rows = [row(status='failed')]
    db.fail_write = True
    async def scenario():
        with pytest.raises(ProviderUnavailable):
            await instance.create_job(A, 'trk_shared')
        pending_id = next(iter(instance._pending_admissions))
        db.fail_write = False
        original = instance.get_job
        def unreadable(actor, job_id):
            if job_id == pending_id:
                raise ProviderUnavailable('synthetic SQL outage')
            return original(actor, job_id)
        monkeypatch.setattr(instance, 'get_job', unreadable)
        command = dict(create=lambda: instance.create_job(A, 'trk_shared'),
                       retry=lambda: instance.retry_job(A, 'job_1'),
                       resolve=lambda: instance.resolve_job(A, 'job_1', 'synthetic'),
                       reassign=lambda: instance.reassign_track_audio(A, 'trk_shared', 'synthetic'))
        with pytest.raises(ProviderUnavailable):
            await command[operation]()
        assert not db.mutations and not instance._attempts
        assert list(instance._pending_admissions) == [pending_id]
    asyncio.run(scenario())


@pytest.mark.parametrize('operation,shape', [(operation, shape)
    for operation in ('insert', 'update') for shape in (None, {}, [], [None], 'multiple')
    if not (operation == 'update' and shape == [])])
def test_malformed_admission_is_503_but_committed_work_stays_owned(api, monkeypatch, operation, shape):
    client, instance, db = api
    if operation == 'update':
        db.rows = [row(status='failed')]
    original = db.table
    release = threading.Event()
    started = []
    async def worker(attempt, hint):
        started.append(attempt.job.id)
        await asyncio.to_thread(release.wait, 5)
    monkeypatch.setattr(instance, '_run_download_job_impl', worker)
    def table(name):
        query = original(name)
        execute = query.execute
        def malformed():
            result = execute()
            if query.operation == operation:
                return SimpleNamespace(data=result.data * 2 if shape == 'multiple' else shape)
            return result
        query.execute = malformed
        return query
    monkeypatch.setattr(db, 'table', table)
    try:
        response = (client.post('/downloads', headers=bearer(A), json={'track_id': 'trk_shared'})
                    if operation == 'insert' else client.post('/downloads/job_1/retry', headers=bearer(A)))
        assert response.status_code == 503
        assert len(db.rows) == len(instance._attempts) == 1
        assert not instance._pending_admissions
        assert db.rows[0]['id'] in instance._attempts
        assert db.rows[0]['attempt_count'] == (1 if operation == 'insert' else 2)
    finally:
        release.set()


def test_restart_transport_failure_replays_same_cas_without_double_increment(service, monkeypatch):
    instance, db = service
    db.rows = [row(status='failed')]
    async def scenario():
        release = asyncio.Event()
        async def worker(attempt, hint):
            await release.wait()
        monkeypatch.setattr(instance, '_run_download_job_impl', worker)
        db.fail_write = True
        with pytest.raises(ProviderUnavailable):
            await instance.retry_job(A, 'job_1')
        assert db.rows[0]['attempt_count'] == 1 and instance._pending_admissions
        db.fail_write = False
        job = await instance.retry_job(A, 'job_1')
        assert job.id == 'job_1' and job.attempt_count == 2
        assert len(instance._attempts) == 1 and not instance._pending_admissions
        release.set()
        await instance._workers.join()
    asyncio.run(scenario())
