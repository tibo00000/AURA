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
