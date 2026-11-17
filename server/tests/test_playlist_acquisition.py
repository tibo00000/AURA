import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, call

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import me, sync_files
from app.core.auth import AuthenticatedUser, get_current_user
from app.db.user_client import get_current_user_client
from app.services.playlist_acquisition import PlaylistAcquisitionWorker


def test_fresh_worker_reuses_durable_job_with_no_client_running():
    intent = {
        "user_id": "account-a", "track_id": "trk_fixture", "status": "admitted",
        "lease_token": "new-lease", "reserved_job_id": "job_persisted", "job_id": "job_persisted",
        "metadata": {"title": "Title"}, "failure_count": 0,
    }
    download = SimpleNamespace(admit_playlist_acquisition=AsyncMock(return_value=SimpleNamespace(
        id="job_persisted", status="running",
    )))
    worker = PlaylistAcquisitionWorker(
        download, is_playable=lambda *_: False, authorize=lambda _: {"resolved_video_id": "fixture0000"},
    )
    worker._rpc = AsyncMock(side_effect=[intent, True])
    assert asyncio.run(worker.run_once())
    download.admit_playlist_acquisition.assert_awaited_once_with(
        "account-a", "trk_fixture", "job_persisted", "job_persisted",
        {"resolved_video_id": "fixture0000"}, "new-lease", retry=False,
    )
    assert worker._rpc.await_args.kwargs["p_job_id"] == "job_persisted"


@pytest.mark.parametrize(("file_exists", "job_status", "expected"), [
    (True, None, "available"),
    (False, "succeeded", "failed"),
    (False, None, "source_not_authorized"),
])
def test_only_a_readable_file_counts_as_available(file_exists, job_status, expected):
    intent = {
        "user_id": "account-a", "track_id": "trk_fixture", "status": "pending",
        "lease_token": "lease", "reserved_job_id": "job_persisted", "job_id": None,
        "metadata": {"title": "Title"}, "failure_count": 0,
    }
    job = SimpleNamespace(id="job_persisted", status=job_status) if job_status else None
    download = SimpleNamespace(admit_playlist_acquisition=AsyncMock(return_value=job))
    worker = PlaylistAcquisitionWorker(download, is_playable=lambda *_: file_exists)
    worker._rpc = AsyncMock(side_effect=[intent, True])
    assert asyncio.run(worker.run_once())
    assert worker._rpc.await_args.kwargs["p_status"] == expected
    if file_exists:
        download.admit_playlist_acquisition.assert_not_awaited()
    else:
        assert download.admit_playlist_acquisition.await_args.args[4] is None


def test_acquisition_status_checks_streaming_and_does_not_trust_a_successful_job(monkeypatch):
    rows = [
        {"track_id": "ready", "status": "pending", "job_status": None},
        {"track_id": "removed", "status": "available", "job_status": "succeeded"},
    ]
    db = MagicMock()
    db.rpc.return_value.execute.return_value.data = rows
    monkeypatch.setattr(me, "find_playable_sync_file", lambda owner, track: (
        (object(), {}) if owner == "account" and track == "ready" else (None, None)
    ))
    api = FastAPI()
    api.include_router(me.router)
    api.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(id="account", token="synthetic")
    api.dependency_overrides[get_current_user_client] = lambda: db
    response = TestClient(api).post("/me/playlists/playlist:mix/acquisitions")
    assert response.status_code == 200
    ready, missing = response.json()["data"]
    assert ready["availability"] == "available"
    assert missing["availability"] == "missing"
    assert missing["status"] == "failed"
    assert missing["error_code"] == "cloud_file_missing"
    preview = {
        "p_playlist_id": "playlist:mix", "p_enqueue": True, "p_retry_track_id": None,
    }
    assert db.rpc.call_args_list == [
        call("aura_get_playlist_acquisitions", preview | {"p_enqueue": False}),
        call("aura_get_playlist_acquisitions", preview),
    ]


def test_linked_occurrences_use_confirmed_audio_and_keep_existing_private_copy(monkeypatch):
    private_id = "track:local:1"
    linked_id = "track:local:2"
    canonical_id = "trk_catalogue_fixture"
    rows = [{"track_id": key, "audio_track_id": canonical_id, "status": "pending", "job_status": None}
            for key in (private_id, linked_id)]
    db = MagicMock()
    db.rpc.return_value.execute.return_value.data = rows
    monkeypatch.setattr(me, "find_playable_sync_file", lambda owner, track: (
        object() if owner == "owner" and track in (private_id, canonical_id) else None, {},
    ))
    api = FastAPI()
    api.include_router(me.router)
    api.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(id="owner", token="synthetic")
    api.dependency_overrides[get_current_user_client] = lambda: db

    response = TestClient(api).post("/me/playlists/mix/acquisitions")
    assert response.status_code == 200
    private, linked = response.json()["data"]
    assert private["availability"] == "available" and "audio_track_id" not in private
    assert linked["track_id"] == linked_id and linked["audio_track_id"] == canonical_id
    assert linked["availability"] == "available"
    assert db.rpc.call_args.args[1]["p_existing_track_ids"] == [private_id]


def test_personal_cloud_listing_excludes_missing_and_empty_audio(tmp_path, monkeypatch):
    monkeypatch.setattr(sync_files, "_base_dir", lambda: tmp_path)
    for track, data in [("ready", b"fixture audio"), ("removed", None), ("empty", b"")]:
        path, metadata = sync_files._paths("account", track)
        metadata.write_text(json.dumps({"track_id": track}), encoding="utf-8")
        if data is not None:
            path.write_bytes(data)

    response = asyncio.run(sync_files.list_sync_files(AuthenticatedUser(id="account", token="synthetic")))
    assert response.data == {"items": [{"track_id": "ready"}]}
