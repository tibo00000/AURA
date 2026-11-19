"""Actual REST/OpenAPI contracts and prepared runner; no real Supabase access."""
import importlib.util
import os
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest
from unittest.mock import MagicMock

from app.api.routes import me, sync, sync_files
from app.core.auth import AuthenticatedUser, get_current_user
from app.db.user_client import get_current_user_client
from app.schemas.sync import PullBatchRequest, PushBatchRequest, BootstrapRequest
from app.services.sync_service import generate_sync_token

ROOT = Path(os.environ.get("AURA_REPOSITORY_ROOT",Path(__file__).resolve().parents[2]))
spec = importlib.util.spec_from_file_location("prepared_runner",ROOT/"server/scripts/verify_sec002_integrated.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


@pytest.mark.parametrize(("provider", "external_id", "status", "expected"), [
    ("spotify", "0123456789ABCDEFGHIJKL", "applied", 200),
    ("deezer", "123", "conflict", 409),
    (None, None, "applied", 200),
    ("spotify", "https://example.com/playlist", None, 422),
    ("deezer", "123", "not_found", 404),
])
def test_playlist_source_uses_canonical_reference_and_scoped_rpc(provider, external_id, status, expected):
    db = MagicMock()
    db.rpc.return_value.execute.return_value.data = {
        "status": status, "provider": provider, "external_id": external_id, "source_revision": 2,
    }
    api = FastAPI()
    api.include_router(me.router)
    api.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(id="owner", token="synthetic")
    api.dependency_overrides[get_current_user_client] = lambda: db
    response = TestClient(api).put("/me/playlists/playlist:original/source", json={
        "provider": provider, "external_id": external_id, "expected_revision": 1,
    })
    assert response.status_code == expected
    if expected == 422:
        db.rpc.assert_not_called()
    else:
        db.rpc.assert_called_once_with("aura_set_playlist_source", {
            "p_playlist_id": "playlist:original", "p_provider": provider,
            "p_external_id": external_id, "p_expected_revision": 1,
        })
    if expected == 200:
        assert response.json()["data"]["source_revision"] == 2


def test_playlist_refresh_passes_canonical_track_identity_and_stable_request_to_sql():
    from app.core.aura_id_codec import build_aura_id

    request_id = "00000000-0000-0000-0000-000000010031"
    db = MagicMock()
    db.rpc.return_value.execute.return_value.data = {
        "status": "applied", "request_id": request_id, "source_revision": 1,
        "completed_at": "2026-11-14T10:30:00Z", "added_count": 1,
        "excluded_count": 0, "present_count": 0, "uncertain": [],
    }
    api = FastAPI()
    api.include_router(me.router)
    api.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(id="owner", token="synthetic")
    api.dependency_overrides[get_current_user_client] = lambda: db
    response = TestClient(api).post("/me/playlists/playlist:original/refresh", json={
        "request_id": request_id, "source_revision": 1,
        "tracks": [{"provider": "deezer", "provider_id": "789", "title": "Title", "artist": "Artist"}],
    })
    assert response.status_code == 200
    assert response.json()["data"]["request_id"] == request_id
    name, args = db.rpc.call_args.args
    assert name == "aura_commit_playlist_refresh"
    assert args["p_request_id"] == request_id
    assert args["p_tracks"][0]["track_id"] == build_aura_id("track", "deezer", "789")


@pytest.mark.parametrize("reason,expected_header", [("playlist_link_changed", "playlist_link_changed"), (None, None)])
def test_refresh_conflict_separates_copy_change_from_source_revision(reason, expected_header):
    db = MagicMock()
    db.rpc.return_value.execute.return_value.data = {"status": "conflict", "reason": reason}
    api = FastAPI()
    api.include_router(me.router)
    api.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(id="owner", token="synthetic")
    api.dependency_overrides[get_current_user_client] = lambda: db
    response = TestClient(api).post("/me/playlists/mix/refresh", json={
        "request_id": "00000000-0000-0000-0000-000000010081", "source_revision": 1,
        "tracks": [{"provider": "deezer", "provider_id": "789", "title": "Title", "artist": "Artist"}],
    })
    assert response.status_code == 409
    assert response.headers.get("X-Aura-Conflict") == expected_header


@pytest.fixture
def document():
    app=FastAPI()
    app.include_router(me.router)
    app.include_router(sync.router)
    app.include_router(sync_files.router)
    return app.openapi()


def test_runner_validates_actual_exposed_routes(document):
    assert "get" not in document["paths"]["/me/playlists/{id}"]
    assert "get" in document["paths"]["/me/playlists"]
    runner.validate_api_contract(document)


def test_runner_refuses_missing_route_before_credentials(document):
    del document["paths"]["/me/sync/pull-batch"]
    with pytest.raises(ValueError):
        runner.validate_api_contract(document)


def test_list_contract_retrieves_own_playlist_and_excludes_other_account():
    assert runner.owned_playlist([{"id":"own","updated_at":"date"}],"own","foreign")["id"] == "own"
    with pytest.raises(ValueError):
        runner.owned_playlist([{"id":"own"},{"id":"foreign"}],"own","foreign")
    with pytest.raises(ValueError):
        runner.owned_playlist([],"own","foreign")


def test_pull_uses_real_returned_sync_token_and_actual_request_schema():
    token=generate_sync_token()
    payload=runner.pull_request("synthetic-device",{"sync_token":{"value":token}})
    assert payload["since_token"] == token and token != "st_"
    assert PullBatchRequest.model_validate(payload).since_token == token


def test_runner_request_shapes_match_actual_batch_schemas():
    assert BootstrapRequest.model_validate({"device_id":"synthetic"}).device_id == "synthetic"
    request=PushBatchRequest.model_validate({"device_id":"synthetic","batch_id":"batch","sent_at":"date",
        "operations":[{"operation_id":"op","entity_type":"playlist","entity_id":"own","operation_type":"update",
                       "device_id":"synthetic","occurred_at":"date","payload":{"name":"new"}}]})
    assert request.operations[0].payload == {"name":"new"}


@pytest.mark.parametrize("returned_owner,count,expected_status", [
    ("owner", 1, 200),
    ("owner", 2, 503),
    ("foreign", 1, 503),
])
def test_playlist_snapshot_requires_a_complete_inventory_for_the_authenticated_owner(
    returned_owner, count, expected_status,
):
    stamp = "2026-11-14T10:30:00+00:00"
    aggregate = {
        "user_id": returned_owner,
        "contract_version": 1,
        "revision": 9,
        "playlist_count": 1,
        "item_count": count,
        "playlists": [{
            "id": "playlist",
            "user_id": returned_owner,
            "name": "Mix",
            "is_pinned": False,
            "created_at": stamp,
            "updated_at": stamp,
            "item_count": 1,
            "items": [{
                "id": "occurrence",
                "playlist_id": "playlist",
                "track_id": "track",
                "position": 0,
                "added_at": stamp,
            }],
        }],
    }
    db = MagicMock()
    db.rpc.return_value.execute.return_value.data = aggregate
    api = FastAPI()
    api.include_router(me.router)
    api.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(id="owner", token="synthetic")
    api.dependency_overrides[get_current_user_client] = lambda: db
    response = TestClient(api).get("/me/playlists/snapshot")
    assert response.status_code == expected_status
    db.rpc.assert_called_once_with("aura_playlist_snapshot", {})
    if expected_status == 200:
        assert response.headers["Cache-Control"] == "private, no-store"
        assert response.json()["data"]["revision"] == 9
        assert response.json()["data"]["playlists"][0]["order_token"].startswith("ord_")
    else:
        assert "playlists" not in response.json()
