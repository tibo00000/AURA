"""Real admission/worker/publication paths; only SQL transport and provider are synthetic."""
import asyncio
import copy
import hashlib
from pathlib import Path

import pytest

from app.domain.models import DownloadJob
from app.services import download_service as downloads
from app.services.exceptions import BadRequest, Conflict, ProviderUnavailable
from app.services.download_lifecycle import AdmissionIntent
from app.services.replacement_history import ReplacementHistory
from tests.native_cache_fixture import register_native
from tests.test_download_job_security import A, B, row, service  # noqa: F401
from tests.test_download_delete_contract import api, bearer  # noqa: F401

OLD = b'ID3-SYNTHETIC-ORIGINAL-CONTENT'
NEW = b'ID3-SYNTHETIC-EXPLICIT-REPLACEMENT'
VIDEO = 'chosen_0051'


def seed(service, tmp_path, *, cached=True, operation='manual_reassign', status='failed'):
    _, db = service
    db.rows = [row('job_resume', A, status, operation_type=operation)]
    if cached:
        source = tmp_path / 'original.mp3'
        source.write_bytes(OLD)
        assert register_native(A, 'trk_shared', source)
        assert downloads._link_cached_track_to_user(B, 'trk_shared', *downloads._find_globally_cached_track('trk_shared'))


def provider(monkeypatch, *, failure=False):
    calls = []

    class Native:
        def __init__(self, options): self.options = options
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def extract_info(self, target, download):
            calls.append(target)
            assert download is True
            if failure: raise TimeoutError('synthetic provider timeout')
            Path(self.options['outtmpl'].replace('%(ext)s', 'mp3')).write_bytes(NEW)
            return {'id': VIDEO}

    monkeypatch.setattr(downloads.yt_dlp, 'YoutubeDL', Native)
    # Any automatic source search is forbidden for these explicitly selected tests.
    def no_search(*args): raise AssertionError('An explicit replacement must never search')
    monkeypatch.setattr(downloads.DownloadService, '_resolve_official_video_id', no_search)
    return calls


async def finish(instance):
    await instance._workers.join()
    await instance._finalizers.join()
    assert not instance._attempts


def personal_bytes(actor):
    return (downloads._get_sync_base() / hashlib.sha256(actor.encode()).hexdigest()
            / (downloads._get_track_key('trk_shared') + '.audio')).read_bytes()


@pytest.mark.parametrize('cached', [False, True])
@pytest.mark.parametrize('fresh_service', [False, True])
def test_resolve_persisted_manual_job_keeps_shared_intent_without_trigger(service, tmp_path, monkeypatch, cached, fresh_service):
    seed(service, tmp_path, cached=cached)
    instance, db = service
    if fresh_service:
        instance = downloads.DownloadService(supabase_client=db)
    assert not instance._attempts and not instance._pending_admissions
    calls = provider(monkeypatch)

    async def scenario():
        job = await instance.resolve_job(A, 'job_resume', VIDEO)
        assert job.operation_type == 'manual_reassign'
        await finish(instance)
    asyncio.run(scenario())

    assert calls == ['https://www.youtube.com/watch?v=' + VIDEO]
    assert db.rows[0]['status'] == 'succeeded'
    assert personal_bytes(A) == NEW
    if cached: assert personal_bytes(B) == NEW
    event, = ReplacementHistory(downloads._get_sync_base()).list()
    assert event['status'] == 'succeeded' and event['actor_id'] == A
    assert event['old_sha256'] == (hashlib.sha256(OLD).hexdigest() if cached else None)
    assert event['new_sha256'] == hashlib.sha256(NEW).hexdigest()


@pytest.mark.parametrize('cached', [False, True])
def test_retry_manual_without_persisted_source_rejected_before_any_mutation(service, tmp_path, monkeypatch, cached):
    seed(service, tmp_path, cached=cached)
    _, db = service
    instance = downloads.DownloadService(supabase_client=db)  # no worker hints after restart
    calls = provider(monkeypatch)
    before = copy.deepcopy(db.rows)

    async def scenario():
        try:
            with pytest.raises(BadRequest, match='explicit'):
                await instance.retry_job(A, 'job_resume')
        finally:
            await finish(instance)
    asyncio.run(scenario())
    assert db.rows == before and not db.mutations and not calls
    assert ReplacementHistory(downloads._get_sync_base()).list() == []
    if cached: assert personal_bytes(A) == personal_bytes(B) == OLD


@pytest.mark.parametrize('cached', [False, True])
def test_resolved_manual_provider_failure_is_audited_without_trigger(service, tmp_path, monkeypatch, cached):
    seed(service, tmp_path, cached=cached)
    instance, db = service
    calls = provider(monkeypatch, failure=True)

    async def scenario():
        await instance.resolve_job(A, 'job_resume', VIDEO)
        await finish(instance)
    asyncio.run(scenario())
    assert len(calls) == 1 and db.rows[0]['status'] == 'failed'
    assert db.rows[0]['error_code'] == 'provider_timeout'
    event, = ReplacementHistory(downloads._get_sync_base()).list()
    assert event['status'] == 'failed' and event['new_sha256'] is None
    if cached: assert personal_bytes(A) == personal_bytes(B) == OLD


def test_resolved_manual_partial_propagation_is_audited(service, tmp_path, monkeypatch):
    seed(service, tmp_path)
    instance, db = service
    calls = provider(monkeypatch)
    monkeypatch.setattr(downloads, '_propagate_reassigned_track_to_all_users', lambda *a, **kw: {'updated': 0, 'failed': 1})

    async def scenario():
        await instance.resolve_job(A, 'job_resume', VIDEO)
        await finish(instance)
    asyncio.run(scenario())
    assert len(calls) == 1 and db.rows[0]['error_code'] == 'shared_audio_replace_partial'
    assert db.rows[0]['status'] == 'failed'
    event, = ReplacementHistory(downloads._get_sync_base()).list()
    assert event['status'] == 'partial' and event['new_sha256'] == hashlib.sha256(NEW).hexdigest()


@pytest.mark.parametrize('cached', [False, True])
@pytest.mark.parametrize('hint', [None, {'trigger': 'manual_reassign'}])
def test_manual_worker_lost_source_never_defaults_or_hits_old_cache(service, tmp_path, monkeypatch, hint, cached):
    seed(service, tmp_path, status='queued', cached=cached)
    instance, db = service
    calls = provider(monkeypatch)

    async def scenario():
        instance._start_download_task(DownloadJob.from_dict(db.rows[0]), hint)
        await finish(instance)
    asyncio.run(scenario())
    assert not calls and db.rows[0]['status'] == 'failed'
    assert db.rows[0]['error_code'] == 'replacement_source_required'
    if cached: assert personal_bytes(A) == personal_bytes(B) == OLD
    event, = ReplacementHistory(downloads._get_sync_base()).list()
    assert event['status'] == 'failed'


@pytest.mark.parametrize('action', ['retry', 'resolve', 'create'])
def test_ordinary_downloads_keep_cache_behavior_and_have_no_shared_history(service, tmp_path, monkeypatch, action):
    seed(service, tmp_path, operation='download', status='succeeded' if action == 'create' else 'failed')
    instance, db = service
    calls = provider(monkeypatch)

    async def scenario():
        if action == 'retry': await instance.retry_job(A, 'job_resume')
        elif action == 'resolve': await instance.resolve_job(A, 'job_resume', VIDEO)
        else: await instance.create_job(A, 'trk_shared')
        await finish(instance)
    asyncio.run(scenario())
    assert not calls and db.rows[0]['status'] == 'succeeded'
    assert personal_bytes(A) == personal_bytes(B) == OLD
    assert ReplacementHistory(downloads._get_sync_base()).list() == []


def test_ordinary_create_cannot_implicitly_restart_completed_manual_job(service, tmp_path, monkeypatch):
    seed(service, tmp_path, status='succeeded')  # historical job audio missing, shared cache still present
    instance, db = service
    calls = provider(monkeypatch)
    before = copy.deepcopy(db.rows)

    async def scenario():
        try:
            with pytest.raises(BadRequest, match='explicit'):
                await instance.create_job(A, 'trk_shared', source_hint={'resolved_video_id': VIDEO})
        finally: await finish(instance)
    asyncio.run(scenario())
    assert not calls and not db.mutations and db.rows == before


def test_manual_retry_http_400_and_foreign_404_have_no_effect(api, tmp_path, monkeypatch):
    client, instance, db = api
    provider(monkeypatch)  # also bounds the deliberately vulnerable before run
    db.rows = [row('job_resume', A, 'failed', operation_type='manual_reassign')]
    assert client.post('/downloads/job_resume/retry', headers=bearer()).status_code == 400
    assert client.post('/downloads/job_resume/retry', headers=bearer(B)).status_code == 404
    assert client.post('/downloads/job_resume/resolve', headers=bearer(B), json={'video_id': VIDEO}).status_code == 404
    assert not db.mutations and not instance._attempts


@pytest.mark.parametrize('video_id', ['', '   '])
def test_manual_resolve_blank_source_rejected_without_mutation(service, tmp_path, video_id):
    seed(service, tmp_path)
    instance, db = service
    before = copy.deepcopy(db.rows)
    with pytest.raises(BadRequest, match='explicit'):
        asyncio.run(instance.resolve_job(A, 'job_resume', video_id))
    assert db.rows == before and not db.mutations and not instance._attempts


def test_transient_trigger_cannot_turn_ordinary_cache_hit_into_shared_replace(service, tmp_path, monkeypatch):
    seed(service, tmp_path, operation='download', status='queued')
    instance, db = service
    calls = provider(monkeypatch)

    async def scenario():
        instance._start_download_task(DownloadJob.from_dict(db.rows[0]),
            {'trigger': 'manual_reassign', 'resolved_video_id': VIDEO})
        await finish(instance)
    asyncio.run(scenario())
    assert not calls and db.rows[0]['status'] == 'succeeded'
    assert personal_bytes(A) == personal_bytes(B) == OLD
    assert ReplacementHistory(downloads._get_sync_base()).list() == []


def test_actual_reassign_failure_restart_retry_refusal_and_explicit_resolve(service, tmp_path, monkeypatch):
    seed(service, tmp_path)
    original, db = service
    db.rows = []
    first_calls = provider(monkeypatch, failure=True)

    async def scenario():
        created = await original.reassign_track_audio(A, 'trk_shared', 'initial_0051',
            {'replacement_reason': 'incorrect_audio'})
        await finish(original)
        assert original.get_job(A, created.id).status == 'failed'
        restarted = downloads.DownloadService(supabase_client=db)
        with pytest.raises(BadRequest, match='explicit'):
            await restarted.retry_job(A, created.id)
        second_calls = provider(monkeypatch)
        await restarted.resolve_job(A, created.id, VIDEO)
        await finish(restarted)
        assert second_calls == ['https://www.youtube.com/watch?v=' + VIDEO]
    asyncio.run(scenario())
    assert first_calls == ['https://www.youtube.com/watch?v=initial_0051']
    assert personal_bytes(A) == personal_bytes(B) == NEW
    succeeded, failed = ReplacementHistory(downloads._get_sync_base()).list()
    assert (succeeded['status'], failed['status']) == ('succeeded', 'failed')
    assert succeeded['job_id'] == failed['job_id']
    assert succeeded['event_id'] != failed['event_id']
    assert failed['reason'] == 'incorrect_audio' and succeeded['reason'] is None


def test_resolved_manual_sql_failure_after_publication_is_audited_partial(service, tmp_path, monkeypatch):
    seed(service, tmp_path)
    instance, db = service
    provider(monkeypatch)
    mutate = instance._mutate_job

    def fail_success(job, changes=None, **kwargs):
        if (changes or {}).get('status') == 'succeeded':
            raise ProviderUnavailable('synthetic SQL failure after file publication')
        return mutate(job, changes, **kwargs)
    monkeypatch.setattr(instance, '_mutate_job', fail_success)

    async def scenario():
        await instance.resolve_job(A, 'job_resume', VIDEO)
        await finish(instance)
    asyncio.run(scenario())
    assert db.rows[0]['status'] == 'failed' and personal_bytes(A) == personal_bytes(B) == NEW
    event, = ReplacementHistory(downloads._get_sync_base()).list()
    assert event['status'] == 'partial'


def test_new_resolve_does_not_replay_uncertain_old_manual_source(service, tmp_path, monkeypatch):
    seed(service, tmp_path)
    instance, db = service
    provider_calls = provider(monkeypatch)
    previous = DownloadJob.from_dict(db.rows[0])
    expected = DownloadJob.from_dict({**previous.to_dict(), 'status': 'queued'})
    intent = AdmissionIntent(expected, previous, {'status': 'queued'}, {'resolved_video_id': 'old_choice'})
    instance._pending_admissions[previous.id] = intent
    before = copy.deepcopy(db.rows)

    async def scenario():
        try:
            with pytest.raises(Conflict):
                await instance.resolve_job(A, previous.id, VIDEO)
        finally: await finish(instance)
    asyncio.run(scenario())
    assert not provider_calls and db.rows == before and not db.mutations
    assert instance._pending_admissions[previous.id] is intent
