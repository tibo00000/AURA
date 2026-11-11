import asyncio
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.domain.models import DownloadJob
from app.services import download_service as downloads
from app.services.download_lifecycle import DownloadAttempt
from app.services.replacement_history import ReplacementHistory
from app.services.storage_safety import StorageBudget, StorageLimit, replace_pair, stage_json
from tests.test_download_job_security import service, A, B, row  # noqa: F401
from tests.test_download_delete_contract import api, bearer  # noqa: F401
from tests.native_cache_fixture import register_native


def test_reservations_and_floor_include_simultaneous_temporary_copies(tmp_path,monkeypatch):
    import app.services.storage_safety as storage
    usage=SimpleNamespace(total=1000,free=600)
    monkeypatch.setattr(storage.shutil,'disk_usage',lambda path:usage)
    guard=StorageBudget(tmp_path,100,100,.10)
    # Scale away the 1MiB envelope for this arithmetic assertion by using an explicit size.
    first=guard.acquire(A,size=200);second=guard.acquire(A,size=200)
    with pytest.raises(StorageLimit) as failure:guard.acquire(B,size=101)
    assert failure.value.status==507
    with pytest.raises(StorageLimit) as concurrency:guard.acquire(A,size=1)
    assert concurrency.value.status==429
    usage.free=550;guard.allocated(first,50);guard.check()
    usage.free=449
    with pytest.raises(StorageLimit):guard.check()
    guard.release(first);guard.release(second);assert not guard.reservations


def test_soft_metrics_expire_and_never_become_admission_quotas(tmp_path,monkeypatch):
    import app.services.storage_safety as storage
    monkeypatch.setattr(storage.shutil,'disk_usage',lambda path:SimpleNamespace(total=10**12,free=10**12))
    clock=[100000.0];guard=StorageBudget(tmp_path,10,0,0,clock=lambda:clock[0])
    for _ in range(16):
        token=guard.acquire(A);guard.release(token)
    guard.received(A,2*1024**3+1)
    assert guard.warnings(A)==['files_10m','bytes_24h']
    token=guard.acquire(A);guard.release(token)
    clock[0]+=601;assert guard.warnings(A)==['bytes_24h']
    clock[0]+=86460;assert guard.warnings(A)==[]


def test_pair_rollback_preserves_old_shared_inode_on_second_replace_failure(tmp_path,monkeypatch):
    import app.services.storage_safety as storage
    audio=tmp_path/'a.audio';meta=tmp_path/'a.json';other=tmp_path/'b.audio'
    audio.write_bytes(b'OLD');meta.write_text('{"old":true}');other.hardlink_to(audio)
    stage=tmp_path/'new';stage.write_bytes(b'NEW');staged_meta=stage_json(meta,{'new':True})
    original=storage.os.replace
    def fail(source,target):
        if target==meta:raise OSError('synthetic final rename failure')
        original(source,target)
    monkeypatch.setattr(storage.os,'replace',fail)
    with pytest.raises(OSError):replace_pair(stage,audio,staged_meta,meta)
    assert audio.read_bytes()==other.read_bytes()==b'OLD'
    assert meta.read_text()=='{"old":true}'
    assert not list(tmp_path.glob('_rollback_*'))


def prepared(service,tmp_path):
    instance,db=service;track='trk_shared_005'
    old=tmp_path/'old.mp3';old.write_bytes(b'ID3-SYNTHETIC-OLD-CONTENT')
    assert register_native(A,track,old)
    cached=downloads._find_globally_cached_track(track)
    assert downloads._link_cached_track_to_user(B,track,*cached)
    job=row('job_005',A,'running');job['track_id']=track;job['operation_type']='manual_reassign'
    db.rows=[job];attempt=DownloadAttempt(DownloadJob.from_dict(job));instance._attempts[attempt.job.id]=attempt
    root=attempt.prepare_workspace(downloads.DOWNLOADS_DIR)
    new=root/'audio.mp3';new.write_bytes(b'ID3-SYNTHETIC-NEW-CONTENT')
    metadata={k:None for k in ['title','artist_name','album_title','duration_ms','cover_uri']}
    return instance,db,attempt,old.read_bytes(),new,metadata


def test_explicit_shared_replace_propagates_and_has_durable_success_history(service,tmp_path):
    instance,db,attempt,old,new,metadata=prepared(service,tmp_path);new_bytes=new.read_bytes()
    assert asyncio.run(instance._publish_audio(attempt,new,metadata,{'trigger':'manual_reassign','replacement_reason':'incorrect_audio'}))
    for actor in [A,B]:
        folder=downloads._get_sync_base()/hashlib.sha256(actor.encode()).hexdigest()
        assert (folder/(downloads._get_track_key(attempt.job.track_id)+'.audio')).read_bytes()==new_bytes
    event=ReplacementHistory(downloads._get_sync_base()).list()[0]
    assert event['status']=='succeeded' and event['actor_id']==A
    assert event['old_sha256']==hashlib.sha256(old).hexdigest()
    assert event['new_sha256']==hashlib.sha256(new_bytes).hexdigest()
    assert event['reason']=='incorrect_audio' and db.rows[0]['status']=='succeeded'
    assert not any(word in event for word in ['token','email','video_id'])


def test_failed_global_replacement_has_no_false_success_and_preserves_old(service,tmp_path,monkeypatch):
    instance,db,attempt,old,new,metadata=prepared(service,tmp_path)
    import app.services.download_cache as cache
    original=cache.os.replace
    def fail(source,target):
        if Path(target).parent.name=='_native_v1' and Path(target).suffix=='.audio':raise OSError('synthetic audio publication failure')
        original(source,target)
    monkeypatch.setattr(cache.os,'replace',fail)
    with pytest.raises(OSError):asyncio.run(instance._publish_audio(attempt,new,metadata,{'trigger':'manual_reassign'}))
    event=ReplacementHistory(downloads._get_sync_base()).list()[0]
    assert event['status']=='failed'
    assert downloads._find_globally_cached_track(attempt.job.track_id)[0].read_bytes()==old
    assert db.rows[0]['status']=='running'


def test_sql_failure_after_audio_effect_is_partial_not_success(service,tmp_path):
    instance,db,attempt,old,new,metadata=prepared(service,tmp_path)
    db.fail_write=True
    with pytest.raises(Exception):asyncio.run(instance._publish_audio(attempt,new,metadata,{'trigger':'manual_reassign'}))
    assert ReplacementHistory(downloads._get_sync_base()).list()[0]['status']=='partial'


def test_propagation_failure_is_audited_partial_and_job_not_succeeded(service,tmp_path,monkeypatch):
    instance,db,attempt,old,new,metadata=prepared(service,tmp_path)
    monkeypatch.setattr(downloads,'_propagate_reassigned_track_to_all_users',lambda *a,**kw:dict(updated=0,failed=1))
    assert not asyncio.run(instance._publish_audio(attempt,new,metadata,{'trigger':'manual_reassign'}))
    assert db.rows[0]['status']=='failed'
    assert ReplacementHistory(downloads._get_sync_base()).list()[0]['status']=='partial'


def test_worker_provider_failure_recorded_and_context_finalized(service,monkeypatch):
    instance,db=service;data=row('job_failed_native',A,'running');data['operation_type']='manual_reassign';db.rows=[data]
    attempt=DownloadAttempt(DownloadJob.from_dict(data));instance._attempts[data['id']]=attempt
    async def broken(*args):raise OSError('synthetic provider failure')
    monkeypatch.setattr(instance,'_run_download_job_impl',broken)
    asyncio.run(instance._run_download_job(attempt,{'trigger':'manual_reassign'}))
    assert ReplacementHistory(downloads._get_sync_base()).list()[0]['status']=='failed'
    assert not instance._attempts and db.rows[0]['status']=='failed'


def test_pending_event_survives_restart_without_inventing_success(service,tmp_path,monkeypatch):
    instance,db,attempt,old,new,metadata=prepared(service,tmp_path)
    history=ReplacementHistory(downloads._get_sync_base());history.begin(attempt);history.prepare(attempt,'a'*64,'b'*64)
    import app.services.replacement_history as audit
    monkeypatch.setattr(audit,'RUN_ID','a-synthetic-new-process')
    result=ReplacementHistory(downloads._get_sync_base()).list()[0]
    assert result['status']=='indeterminate' and result['old_sha256']=='a'*64


def test_history_unavailable_prevents_shared_audio_effect(service,tmp_path,monkeypatch):
    instance,db,attempt,old,new,metadata=prepared(service,tmp_path)
    def broken(*args,**kwargs):raise OSError('synthetic audit failure')
    monkeypatch.setattr(ReplacementHistory,'prepare',broken)
    with pytest.raises(OSError):asyncio.run(instance._publish_audio(attempt,new,metadata,{'trigger':'manual_reassign'}))
    assert downloads._find_globally_cached_track(attempt.job.track_id)[0].read_bytes()==old
    assert db.rows[0]['status']=='running'


def test_history_is_authenticated_collaborative_and_paginated(api,tmp_path):
    client,instance,db=api
    event=DownloadAttempt(DownloadJob.from_dict(row('job_audit',A)))
    history=ReplacementHistory(downloads._get_sync_base());history.begin(event);history.finish(event,'failed')
    assert client.get('/me/audio-replacements').status_code==401
    response=client.get('/me/audio-replacements?limit=1',headers=bearer(B))
    assert response.status_code==200
    assert response.json()['data']['items'][0]['actor_id']==A
    assert client.get('/me/audio-replacements?before=1',headers=bearer(B)).json()['data']['items']==[]
    assert client.get('/me/audio-replacements?track_id=other',headers=bearer(B)).json()['data']['items']==[]


def test_global_cache_growth_respects_upload_reservation(service,tmp_path,monkeypatch):
    from app.services.storage_safety import budget
    import app.services.storage_safety as storage
    instance,db,attempt,old,new,metadata=prepared(service,tmp_path)
    guard=budget(downloads._get_sync_base());token=guard.acquire(A)
    try:
        monkeypatch.setattr(storage.shutil,'disk_usage',lambda path:SimpleNamespace(total=10**12,free=token.remaining+512*1024))
        with pytest.raises(StorageLimit):
            asyncio.run(instance._publish_audio(attempt,new,metadata,{'trigger':'manual_reassign'}))
        assert downloads._find_globally_cached_track(attempt.job.track_id)[0].read_bytes()==old
        assert ReplacementHistory(downloads._get_sync_base()).list()[0]['status']=='failed'
    finally:guard.release(token)


def test_invalid_output_has_failed_history_without_claiming_partial_effect(service,tmp_path):
    instance,db,attempt,old,new,metadata=prepared(service,tmp_path)
    new.write_bytes(b'bad')
    assert not asyncio.run(instance._publish_audio(attempt,new,metadata,{'trigger':'manual_reassign'}))
    assert ReplacementHistory(downloads._get_sync_base()).list()[0]['status']=='failed'
    assert db.rows[0]['error_code']=='corrupt_audio_output'
    assert downloads._find_globally_cached_track(attempt.job.track_id)[0].read_bytes()==old


def test_final_audit_write_failure_never_manufactures_success(service,tmp_path,monkeypatch):
    instance,db,attempt,old,new,metadata=prepared(service,tmp_path)
    def unavailable(*args,**kwargs):raise OSError('synthetic audit commit failure')
    monkeypatch.setattr(ReplacementHistory,'finish',unavailable)
    with pytest.raises(OSError):asyncio.run(instance._publish_audio(attempt,new,metadata,{'trigger':'manual_reassign'}))
    assert ReplacementHistory(downloads._get_sync_base()).list()[0]['status']=='publishing'
    import app.services.replacement_history as audit
    monkeypatch.setattr(audit,'RUN_ID','synthetic-restart-after-crash')
    assert ReplacementHistory(downloads._get_sync_base()).list()[0]['status']=='indeterminate'


def test_concurrent_audit_entries_are_durable_and_have_unique_sequence(service):
    from concurrent.futures import ThreadPoolExecutor
    history=ReplacementHistory(downloads._get_sync_base())
    # Initialize once just as the running service does, then use independent connections.
    assert history.list()==[]
    def action(number):
        event=DownloadAttempt(DownloadJob.from_dict(row('job_parallel_'+str(number),A if number%2 else B)))
        history.begin(event);history.finish(event,'failed')
    with ThreadPoolExecutor(max_workers=4) as executor:list(executor.map(action,range(12)))
    entries=history.list()
    assert len(entries)==len({e['sequence'] for e in entries})==12
    assert all(e['status']=='failed' for e in entries)
