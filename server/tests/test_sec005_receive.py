"""Real HTTP multipart/JWT, synthetic bytes and private local files only."""
import asyncio
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api.routes import sync_files
from app.config import get_settings
from tests.test_download_delete_contract import api, bearer  # noqa: F401
from tests.test_download_job_security import service, A, B  # noqa: F401


def upload_app(api):
    client, _, _ = api
    client.app.include_router(sync_files.router)
    return client


async def pieces(size, *, stats=None, hold=None):
    if stats is not None:
        stats.append('body_started')
    yield b'--sec005\r\nContent-Disposition: form-data; name="file"; filename="synthetic.mp3"\r\nContent-Type: audio/mpeg\r\n\r\n'
    left = size
    while left:
        count = min(left, 1024 * 1024)
        yield b'x' * count
        left -= count
        if hold is not None:
            entered, resume = hold
            entered.set()
            await resume.wait()
    yield b'\r\n--sec005--\r\n'


def raw_headers(owner=A, **extra):
    return {**bearer(owner), 'Content-Type': 'multipart/form-data; boundary=sec005', **extra}


def test_real_receive_limit_50mib_plus_one_without_content_length(api):
    client = upload_app(api)
    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app), base_url='http://test') as http:
            response = await http.post('/me/sync/files/trk_large', headers=raw_headers(), content=pieces(50*1024**2+1))
            assert response.status_code == 413
    asyncio.run(run())
    assert not sync_files._paths(A, 'trk_large')[0].exists()


def test_false_content_length_cannot_bypass_file_limit(api, monkeypatch):
    monkeypatch.setenv('UPLOAD_MAX_FILE_BYTES', '1024'); get_settings.cache_clear()
    client = upload_app(api)
    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app), base_url='http://test') as http:
            response = await http.post('/me/sync/files/trk_false_length', headers=raw_headers(**{'Content-Length':'1'}), content=pieces(1025))
            assert response.status_code == 413
    asyncio.run(run())


def test_exact_file_limit_allowed_with_multipart_overhead(api, monkeypatch):
    monkeypatch.setenv('UPLOAD_MAX_FILE_BYTES','1024'); get_settings.cache_clear()
    client=upload_app(api)
    response=client.post('/me/sync/files/trk_exact',headers=bearer(),
                         data={'title':'Synthetic title'},files={'file':('song.mp3',b'x'*1024,'audio/mpeg')})
    assert response.status_code==201
    assert response.json()['data']['size_bytes']==1024


def test_authentication_precedes_body_read_and_spooling(api):
    client=upload_app(api);stats=[]
    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app),base_url='http://test') as http:
            response=await http.post('/me/sync/files/trk_rejected',headers={'Content-Type':'multipart/form-data; boundary=sec005'},content=pieces(10,stats=stats))
            assert response.status_code==401
    asyncio.run(run());assert not stats


def test_two_per_owner_but_other_owner_allowed_and_release_after_cancel(api):
    client=upload_app(api)
    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app),base_url='http://test') as http:
            e1,e2,release=asyncio.Event(),asyncio.Event(),asyncio.Event()
            one=asyncio.create_task(http.post('/me/sync/files/trk_one',headers=raw_headers(),content=pieces(20,hold=(e1,release))))
            two=asyncio.create_task(http.post('/me/sync/files/trk_two',headers=raw_headers(),content=pieces(20,hold=(e2,release))))
            try:
                await asyncio.wait_for(asyncio.gather(e1.wait(),e2.wait()),5)
                stats=[]
                denied=await http.post('/me/sync/files/trk_three',headers=raw_headers(),content=pieces(20,stats=stats))
                assert denied.status_code==429 and not stats
                other=await http.post('/me/sync/files/trk_other',headers=raw_headers(B),content=pieces(20))
                assert other.status_code==201
                one.cancel();await asyncio.gather(one,return_exceptions=True)
                freed=await http.post('/me/sync/files/trk_freed',headers=raw_headers(),content=pieces(20))
                assert freed.status_code==201
            finally:
                release.set();await asyncio.gather(one,two,return_exceptions=True)
    asyncio.run(run())


def test_soft_warnings_do_not_block_or_change_response_contract(api):
    from app.services.storage_safety import budget
    client=upload_app(api)
    guard=budget(sync_files._base_dir())
    guard.received(A,2*1024**3+1)
    for number in range(16):
        response=client.post('/me/sync/files/trk_warning',headers=bearer(),files={'file':('synthetic.mp3',b'ID3-SYNTHETIC')})
        assert response.status_code==201
    assert set(response.headers['X-Aura-Upload-Warnings'].split(','))=={'files_10m','bytes_24h'}
    assert response.json()['data']['track_id']=='trk_warning'


def test_spools_closed_on_size_or_metadata_rejection(api,monkeypatch):
    import app.api.upload_admission as admission
    monkeypatch.setenv('UPLOAD_MAX_FILE_BYTES','1024');get_settings.cache_clear()
    real=admission.tempfile.SpooledTemporaryFile;opened=[]
    def traced(*args,**kwargs):
        stream=real(*args,**kwargs);opened.append(stream);return stream
    monkeypatch.setattr(admission.tempfile,'SpooledTemporaryFile',traced)
    client=upload_app(api)
    assert client.post('/me/sync/files/trk_spool',headers=bearer(),files={'file':('x',b'x'*1025)}).status_code==413
    assert opened and all(s.closed for s in opened)
    assert client.post('/me/sync/files/trk_fields',headers=bearer(),data={'title':'x'*(64*1024+1)},files={'file':('x',b'ID3')}).status_code==400
    assert all(s.closed for s in opened)


def test_private_upload_metadata_staging_failure_preserves_old_pair(api,monkeypatch):
    client=upload_app(api)
    assert client.post('/me/sync/files/trk_pair',headers=bearer(),files={'file':('x',b'ORIGINAL')}).status_code==201
    audio,meta=sync_files._paths(A,'trk_pair');old=(audio.read_bytes(),meta.read_bytes())
    def failure(*args,**kwargs):raise OSError('synthetic metadata failure')
    monkeypatch.setattr(sync_files,'stage_json',failure)
    with TestClient(client.app,raise_server_exceptions=False) as observed:
        assert observed.post('/me/sync/files/trk_pair',headers=bearer(),files={'file':('x',b'REPLACEMENT')}).status_code==500
    assert (audio.read_bytes(),meta.read_bytes())==old
    assert not list(audio.parent.glob('_upload_*'))


def test_insufficient_space_rejected_before_body_and_keeps_old_file(api,monkeypatch):
    from types import SimpleNamespace
    import app.services.storage_safety as storage
    client=upload_app(api)
    audio,meta=sync_files._paths(A,'trk_space');audio.write_bytes(b'EXISTING-VALID-AUDIO')
    monkeypatch.setattr(storage.shutil,'disk_usage',lambda path:SimpleNamespace(total=1000,free=0))
    stats=[]
    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app),base_url='http://test') as http:
            response=await http.post('/me/sync/files/trk_space',headers=raw_headers(),content=pieces(20,stats=stats))
            assert response.status_code==507
    asyncio.run(run());assert not stats
    assert audio.read_bytes()==b'EXISTING-VALID-AUDIO'


def test_truncated_multipart_closes_all_spools_and_releases_slot(api,monkeypatch):
    import app.api.upload_admission as admission
    real=admission.tempfile.SpooledTemporaryFile;opened=[]
    def traced(*args,**kwargs):
        stream=real(*args,**kwargs);opened.append(stream);return stream
    monkeypatch.setattr(admission.tempfile,'SpooledTemporaryFile',traced)
    client=upload_app(api)
    body=(b'--sec005\r\nContent-Disposition: form-data; name="file"; filename="incomplete.mp3"\r\n'
          b'Content-Type: audio/mpeg\r\n\r\nID3-UNFINISHED')
    response=client.post('/me/sync/files/trk_truncated',headers=raw_headers(),content=body)
    assert response.status_code in [400,422]
    assert opened and all(stream.closed for stream in opened)
    from app.services.storage_safety import budget
    assert not budget(sync_files._base_dir()).reservations
