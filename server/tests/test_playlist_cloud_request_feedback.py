from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import MagicMock, call

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import me, downloads
from app.core.auth import AuthenticatedUser, get_current_user
from app.db.user_client import get_current_user_client


def client_for(router, db):
    api = FastAPI()
    api.include_router(router)
    api.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(id="owner", token="fixture")
    api.dependency_overrides[get_current_user_client] = lambda: db
    return TestClient(api)


def test_bulk_retry_preserves_ready_active_and_identity_ambiguous_rows(monkeypatch):
    rows = [dict(track_id=key, status=status, job_status=job, error_code=error) for key, status, job, error in (
        ("ready", "available", "succeeded", None),
        ("ambiguous", "source_not_authorized", None, "audio_source_ambiguous"),
        ("failed", "failed", "failed", "network"),
        ("active", "admitted", "running", None),
        ("choose", "admitted", "requires_resolution", None),
        ("identity", "source_not_authorized", None, "source_identity_ambiguous"),
    )]
    db = MagicMock()
    db.rpc.return_value.execute.return_value.data = rows
    monkeypatch.setattr(me, "find_playable_sync_file", lambda owner, track: (object() if track == "ready" else None, {}))
    response = client_for(me.router, db).post("/me/playlists/fixture/acquisitions?retry_failed=true")
    assert response.status_code == 200
    calls = [args.kwargs if args.kwargs else args.args[1] for args in db.rpc.call_args_list]
    assert [args["p_retry_track_id"] for args in calls] == [None, None, "ambiguous", "failed"]
    assert all(args["p_playlist_id"] == "fixture" for args in calls)
    assert all(args.get("p_existing_track_ids") == ["ready"] for args in calls[1:])
    assert calls[1]["p_enqueue"] is True
    assert all(not args["p_enqueue"] for args in calls[2:])


def test_get_never_retries_and_a_partial_retry_failure_is_not_reported_as_success(monkeypatch):
    row = dict(track_id="missing", status="failed", job_status="failed", error_code="download_failed")
    db = MagicMock()
    db.rpc.return_value.execute.return_value.data = [row]
    monkeypatch.setattr(me, "find_playable_sync_file", lambda *_: (None, {}))
    client = client_for(me.router, db)
    assert client.get("/me/playlists/fixture/acquisitions?retry_failed=true").status_code == 200
    assert db.rpc.call_args_list == [call("aura_get_playlist_acquisitions", {
        "p_playlist_id": "fixture", "p_enqueue": False, "p_retry_track_id": None,
    })]
    db.rpc.return_value.execute.side_effect = [SimpleNamespace(data=[row]), SimpleNamespace(data=[row]), RuntimeError("fixture network")]
    assert client.post("/me/playlists/fixture/acquisitions?retry_failed=true").status_code == 503


def test_download_metadata_uses_exact_owner_and_does_not_invent_a_cloud_file(monkeypatch):
    db = MagicMock()
    db.table.return_value.select.return_value.eq.return_value.in_.return_value.execute.return_value.data = [
        {"track_id": "trk_fixture", "metadata": {"title": "CAFé CON RON", "artist": "Bad Bunny", "provider": "spotify"}}
    ]
    job = SimpleNamespace(id="job_fixture", track_id="trk_fixture", provider_name="youtube", operation_type="download",
        status="succeeded", progress_percent=100, error_code=None, error_message=None, attempt_count=1,
        created_at=datetime.now(timezone.utc), updated_at=datetime.now(timezone.utc), playlist_acquisition=True)
    monkeypatch.setattr(downloads, "download_service", SimpleNamespace(supabase=db, list_jobs=lambda **_: ([job], None)))
    monkeypatch.setattr(downloads, "find_playable_sync_file", lambda *_: (None, {}))
    monkeypatch.setattr(downloads, "legacy_audio", lambda *_: None)
    response = client_for(downloads.router, db).get("/downloads")
    assert response.status_code == 200
    item = response.json()["data"]["items"][0]
    assert item["metadata"]["title"] == "CAFé CON RON"
    assert item["audio_available"] is False
    db.table.assert_called_once_with("playlist_acquisition_intents")
    db.table.return_value.select.return_value.eq.assert_called_once_with("user_id", "owner")
    db.table.return_value.select.return_value.eq.return_value.in_.assert_called_once_with("track_id", ["trk_fixture"])


def test_metadata_failure_returns_unconfirmed_page_instead_of_fabricated_labels(monkeypatch):
    db = MagicMock()
    db.table.return_value.select.return_value.eq.return_value.in_.return_value.execute.side_effect = TimeoutError("fixture timeout")
    monkeypatch.setattr(downloads, "download_service", SimpleNamespace(supabase=db,
        list_jobs=lambda **_: ([SimpleNamespace(track_id="trk_fixture")], None)))
    assert client_for(downloads.router, db).get("/downloads").status_code == 503
