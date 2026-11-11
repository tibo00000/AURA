"""Real ASGI routing and locally signed JWTs; no Supabase network or private data."""
import time

from fastapi import FastAPI
from fastapi.testclient import TestClient
import jwt
import pytest

from app.config import Settings
from app.core import auth
from app.api.routes import downloads as routes
from app.api.routes import jobs
from app.core.id_generator import generate_id
from app.services.download_lifecycle import validate_job_id
from tests.test_download_job_security import A, B, row, service  # noqa: F401

SIGNING_KEY = 'SEC003-synthetic-local-test-signing-key-0000000000'


def bearer(actor=A):
    now = int(time.time())
    token = jwt.encode(dict(sub=actor, role='authenticated', aud='authenticated',
                            iss='https://example.supabase.co/auth/v1', iat=now-2, exp=now+300),
                       SIGNING_KEY, algorithm='HS256')
    return {'Authorization': 'Bearer ' + token}


@pytest.fixture
def api(service, monkeypatch):
    instance, db = service
    monkeypatch.setattr(routes, 'download_service', instance)
    monkeypatch.setattr(jobs, 'download_service', instance)
    from app.services import download_service as module
    monkeypatch.setattr(routes, 'DOWNLOADS_DIR', module.DOWNLOADS_DIR)
    monkeypatch.setattr(auth, 'get_settings', lambda: Settings(
        supabase_url='https://example.supabase.co', supabase_jwt_secret=SIGNING_KEY))
    app = FastAPI()
    app.include_router(routes.router)
    app.include_router(jobs.router)
    with TestClient(app) as client:
        yield client, instance, db


def test_one_delete_handler_and_openapi_matches_empty_204(api):
    client, instance, db = api
    declarations = [r for r in routes.router.routes
                    if getattr(r, 'path', None) == '/downloads/{job_id}' and 'DELETE' in r.methods]
    assert len(declarations) == 1
    operation = client.get('/openapi.json').json()['paths']['/downloads/{job_id}']['delete']
    assert '204' in operation['responses'] and '200' not in operation['responses']
    assert 'content' not in operation['responses']['204']
    db.rows = [row()]
    response = client.delete('/downloads/job_1', headers=bearer())
    assert response.status_code == 204 and response.content == b''
    assert not db.rows
    assert client.delete('/downloads/job_1', headers=bearer()).status_code == 404


def test_foreign_and_missing_are_indistinguishable_and_jwt_b_cannot_cancel_a(api):
    client, instance, db = api
    db.rows = [row()]
    foreign = client.delete('/downloads/job_1', headers=bearer(B))
    missing = client.delete('/downloads/job_missing', headers=bearer(B))
    assert foreign.status_code == missing.status_code == 404
    assert foreign.json() == missing.json()
    assert db.rows[0]['user_id'] == A
    assert not db.mutations and not instance._attempts


def test_invalid_and_missing_jwt_fail_before_mutation(api):
    client, instance, db = api
    db.rows = [row()]
    for headers in ({}, {'Authorization': 'Bearer synthetic-invalid'}):
        assert client.delete('/downloads/job_1', headers=headers).status_code == 401
    assert not db.mutations and not instance._attempts


@pytest.mark.parametrize('identifier', ['job_%2a', 'job_%252a', 'job_%3f', 'job_%5b1%5d', 'job_' + 'a'*125])
def test_http_encoded_patterns_are_rejected(api, identifier):
    client, instance, db = api
    assert client.delete('/downloads/' + identifier, headers=bearer()).status_code == 400
    assert not db.mutations and not instance._attempts


def test_sql_unavailability_returns_503_and_keeps_row(api):
    client, instance, db = api
    db.rows = [row()]
    db.fail_write = True
    assert client.delete('/downloads/job_1', headers=bearer()).status_code == 503
    assert db.rows[0]['status'] == 'queued'


def test_sql_compare_and_swap_conflict_returns_409(api):
    client, instance, db = api
    db.rows = [row()]
    db.before_write = lambda database: database.rows[0].update(status='succeeded')
    assert client.delete('/downloads/job_1', headers=bearer()).status_code == 409
    assert db.rows[0]['status'] == 'succeeded'


@pytest.mark.parametrize('suffix', ['.mp3', '.m4a', '.webm', '.opus', '.ogg', '.wav', '.flac', '.aac', '.mp4'])
def test_existing_download_file_and_range_remain_accessible_to_owner(api, suffix):
    client, instance, db = api
    db.rows = [row(status='succeeded')]
    path = routes.DOWNLOADS_DIR / ('job_1' + suffix)
    path.write_bytes(b'ID3-SYNTHETIC-AUDIO')
    assert client.get('/downloads/job_1/file', headers=bearer(B)).status_code == 404
    full = client.get('/downloads/job_1/file', headers=bearer())
    partial = client.get('/downloads/job_1/file', headers={**bearer(), 'Range': 'bytes=0-2'})
    assert full.status_code == 200 and full.content == b'ID3-SYNTHETIC-AUDIO'
    assert partial.status_code == 206 and partial.content == b'ID3'


def test_foreign_retry_and_resolve_have_no_effect(api):
    client, instance, db = api
    db.rows = [row(status='requires_resolution')]
    for path, body in [('/retry', {}), ('/resolve', {'video_id': 'synthetic'})]:
        assert client.post('/downloads/job_1' + path, headers=bearer(B), json=body).status_code == 404
    assert not db.mutations and not instance._attempts


@pytest.mark.parametrize('identifier', ['job_1', 'job_old_to_delete', 'job_legacy_1', generate_id('job')])
def test_produced_and_documented_historical_identifiers_are_accepted(identifier):
    assert validate_job_id(identifier) == identifier
