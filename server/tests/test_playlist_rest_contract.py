"""Actual REST/OpenAPI contracts and prepared runner; no real Supabase access."""
import importlib.util
import os
from pathlib import Path

from fastapi import FastAPI
import pytest

from app.api.routes import me, sync, sync_files
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
