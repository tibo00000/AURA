"""Exercise Aura's real Supabase client within the disposable Docker fixture."""

import asyncio
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
from uuid import uuid4

from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient, TransportError
import jwt


async def wait_for_supabase(client):
    issue = None
    for _ in range(40):
        try:
            auth = await client.get("/auth/v1/health")
            # Even without OpenAPI or table grants, a ready PostgREST answers its root.
            rest = await client.get("/rest/v1/")
            if auth.status_code == 200 and rest.status_code == 200:
                return
            issue = (auth.status_code, rest.status_code)
        except TransportError as exc:
            issue = str(exc)
        await asyncio.sleep(0.25)
    raise RuntimeError(f"Supabase Auth or PostgREST was not ready on fixture loopback: {issue}")


def owned_track_rows(client, table, owner, track_id):
    return client.table(table).select("*").eq("user_id", owner).eq("track_id", track_id).execute().data


async def serve_worker(phase, owner, track_id):
    from app.api.routes.downloads import download_service
    from app.main import app
    from app.services import download_service as downloader_module
    from playlist_worker_restart_fixture import EXIT_AFTER_COMMIT, make_downloader

    async def unavailable_catalog(_):
        raise RuntimeError("Deezer catalog unavailable in this fixture")

    download_service.deezer_client.get_track = unavailable_catalog
    downloader_module.yt_dlp.YoutubeDL = make_downloader(interrupt=phase == "interrupt")
    async with app.router.lifespan_context(app):
        async with asyncio.timeout(55):
            while True:
                rows = await asyncio.to_thread(
                    owned_track_rows, download_service.supabase, "playlist_acquisition_intents", owner, track_id,
                )
                assert len(rows) == 1
                intent = rows[0]
                assert intent["status"] in ("pending", "admitted", "available"), intent["error_code"]
                if intent["status"] == "available":
                    assert phase == "resume", f"Expected worker interruption with exit code {EXIT_AFTER_COMMIT}"
                    return
                await asyncio.sleep(0.5)


def verify_started_worker(owner, track_id, workspace):
    from app.db.supabase import supabase
    from playlist_worker_restart_fixture import EXIT_AFTER_COMMIT, VIDEO

    manifest = workspace / "authorized-audio.json"
    manifest.write_text(json.dumps({track_id: {
        "video_id": VIDEO, "authorized_for_cloud": True, "provenance": "locally generated silence",
    }}), encoding="utf-8")
    worker_env = os.environ | {
        "APP_ENV": "local", "ENABLE_PLAYLIST_ACQUISITION_WORKER": "true",
        "ENABLE_STARTUP_RECOVERY": "false", "ENABLE_CACHE_BACKFILL": "false",
        "PLAYLIST_AUTHORIZED_AUDIO_MANIFEST": str(manifest),
    }
    command = [sys.executable, "-B", __file__, "worker"]
    first = subprocess.run([*command, "interrupt", owner, track_id], env=worker_env, timeout=60)
    assert first.returncode == EXIT_AFTER_COMMIT, first.returncode
    initial, = owned_track_rows(supabase, "download_jobs", owner, track_id)
    assert initial["status"] == "running" and initial["attempt_count"] == 1 and initial["playlist_acquisition"]
    intent, = owned_track_rows(supabase, "playlist_acquisition_intents", owner, track_id)
    assert intent["job_id"] == initial["id"] and intent["reserved_job_id"] == initial["id"]

    # The first process can also stop while classifying the queued manual entry.
    # Advance the schedule for this fixture's unfinished intents past the lease timeout.
    past = "2000-01-01T00:00:00Z"
    unfinished = supabase.table("playlist_acquisition_intents").select("track_id,lease_token").eq(
        "user_id", owner,
    ).in_("status", ["pending", "admitted"]).execute().data
    assert track_id in {row["track_id"] for row in unfinished}
    for row in unfinished:
        schedule = {"next_attempt_at": past}
        if row["lease_token"] is not None:
            schedule["lease_until"] = past
        updated = supabase.table("playlist_acquisition_intents").update(schedule).eq(
            "user_id", owner,
        ).eq("track_id", row["track_id"]).execute().data
        assert len(updated) == 1

    subprocess.run([*command, "resume", owner, track_id], env=worker_env, timeout=65, check=True)
    final, = owned_track_rows(supabase, "download_jobs", owner, track_id)
    assert final["id"] == initial["id"] and final["status"] == "succeeded" and final["attempt_count"] == 2
    return final["id"]


async def verify(ca_file):
    base_url = "https://localhost:8443"
    jwt_secret = os.environ["SUPABASE_JWT_SECRET"]
    os.environ.update({
        "APP_ENV": "test", "SUPABASE_URL": base_url, "SSL_CERT_FILE": str(ca_file), "NO_PROXY": "localhost,127.0.0.1",
        "SUPABASE_ANON_KEY": jwt.encode({"role": "anon", "exp": int(time.time()) + 300}, jwt_secret, "HS256"),
        "SUPABASE_SERVICE_ROLE_KEY": jwt.encode({"role": "service_role", "exp": int(time.time()) + 300}, jwt_secret, "HS256"),
        "DOWNLOADS_DIR": str(ca_file.parent / "downloads"), "SYNC_FILES_DIR": str(ca_file.parent / "sync"),
    })
    from app.api.routes import me, sync_files
    from app.core.aura_id_codec import build_aura_id

    async with AsyncClient(base_url=base_url, timeout=5) as auth:
        await wait_for_supabase(auth)

        async def create_account(name):
            credentials = {"email": f"{name}@example.com", "password": secrets.token_urlsafe(24)}
            signup = await auth.post("/auth/v1/signup", json=credentials)
            assert signup.status_code == 200, ("signup", signup.status_code)
            return credentials

        async def sign_in(credentials):
            session = await auth.post("/auth/v1/token", params={"grant_type": "password"}, json=credentials)
            assert session.status_code == 200, ("sign-in", session.status_code)
            return {"Authorization": "Bearer " + session.json()["access_token"]}

        owner = await create_account("playlist-owner")
        owner_first = await sign_in(owner)
        owner_second = await sign_in(owner)
        other = await sign_in(await create_account("other-account"))
        assert owner_first != owner_second

    api = FastAPI()
    api.include_router(me.router)
    api.include_router(sync_files.router)
    async with AsyncClient(transport=ASGITransport(app=api), base_url="http://testserver") as aura:
        async def data(method, path, headers, **kwargs):
            response = await aura.request(method, path, headers=headers, **kwargs)
            assert response.status_code == 200, (method, path, response.status_code)
            return response.json()["data"]

        playlists = "/me/playlists"
        snapshot = playlists + "/snapshot"
        playlist = playlists + "/existing-fixture"
        await data("POST", playlists, owner_first, json={"id": "existing-fixture", "name": "Existing playlist"})
        await data("POST", playlists, other, json={"id": "foreign-fixture", "name": "Other playlist"})
        entry = {"id": "manual-entry", "track_id": "track:local:fixture", "position": 0}
        await data("POST", playlist + "/tracks", owner_first, json=entry)
        source = await data("PUT", playlist + "/source", owner_first, json={
            "provider": "deezer", "external_id": "123", "expected_revision": 0,
        })
        assert source == {"provider": "deezer", "external_id": "123", "source_revision": 1}

        metadata = {"provider": "deezer", "provider_id": "456", "title": "Fixture title", "artist": "Fixture artist",
                    "album": "Fixture album", "cover_uri": "https://cdn-images.dzcdn.net/fixture.jpg", "duration_ms": 2000}
        refresh = {"request_id": str(uuid4()), "source_revision": 1, "tracks": [metadata]}
        before_refresh = await data("GET", snapshot, owner_second)
        invalid = metadata | {"provider_id": "789", "cover_uri": "https://example.com/fixture.jpg"}
        response = await aura.post(playlist + "/refresh", headers=owner_first, json=refresh | {"tracks": [metadata, invalid]})
        assert response.status_code == 422
        assert await data("GET", snapshot, owner_second) == before_refresh

        report = await data("POST", playlist + "/refresh", owner_first, json=refresh)
        assert report["added_count"] == 1
        assert await data("POST", playlist + "/refresh", owner_second, json=refresh) == report

        restored = await data("GET", snapshot, owner_second)
        assert [row["id"] for row in restored["playlists"]] == ["existing-fixture"]
        saved = restored["playlists"][0]
        assert saved["source_external_id"] == "123" and saved["last_refresh"] == report
        manual, added = saved["items"]
        assert all(manual[key] == value for key, value in entry.items())
        track_id = build_aura_id("track", "deezer", "456")
        assert added["source_metadata"] == metadata and added["track_id"] == track_id

        acquisitions = await data("GET", playlist + "/acquisitions", owner_second)
        assert {row["track_id"]: row["status"] for row in acquisitions} == {
            track_id: "pending", entry["track_id"]: "not_requested",
        }
        assert all(row["availability"] == "missing" for row in acquisitions)
        queued = await data("POST", playlist + "/acquisitions", owner_second)
        assert len(queued) == 2 and all(row["status"] == "pending" for row in queued)
        assert await data("POST", playlist + "/acquisitions", owner_first) == queued

        foreign_snapshot = await data("GET", snapshot, other)
        assert [row["id"] for row in foreign_snapshot["playlists"]] == ["foreign-fixture"]
        assert (await aura.get(playlist + "/acquisitions", headers=other)).status_code == 404
        assert (await aura.get(snapshot)).status_code == 401

        job_id = await asyncio.to_thread(verify_started_worker, restored["user_id"], track_id, ca_file.parent)
        published = await data("GET", playlist + "/acquisitions", owner_second)
        by_track = {row["track_id"]: row for row in published}
        audio = by_track[track_id]
        assert audio["job_id"] == job_id and audio["job_status"] == "succeeded"
        assert audio["status"] == "available" and audio["availability"] == "available"
        manual_audio = by_track[entry["track_id"]]
        assert manual_audio["status"] == "source_not_authorized", manual_audio["status"]
        assert manual_audio["job_id"] is None
        for identifier in (track_id, "track:deezer:456"):
            stream = "/me/sync/files/" + identifier
            partial = await aura.get(stream, headers=owner_second | {"Range": "bytes=0-3"})
            assert partial.status_code == 206 and partial.content == b"RIFF"
            assert (await aura.get(stream)).status_code == 401
        # Trusted native files can be streamed through the shared cache without entering another library.
        cached = await aura.get("/me/sync/files/" + track_id, headers=other | {"Range": "bytes=0-3"})
        assert cached.status_code == 206 and cached.content == b"RIFF"
        assert (await data("GET", "/me/sync/files", other))["items"] == []
        owner_library = (await data("GET", "/me/sync/files", owner_second))["items"]
        assert [item["track_id"] for item in owner_library] == [track_id]


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "worker":
        asyncio.run(serve_worker(*sys.argv[2:]))
        sys.exit(0)
    with tempfile.TemporaryDirectory(prefix="aura-tls-") as workspace:
        ca_file = Path(workspace) / "ca.pem"
        ca_file.write_bytes(sys.stdin.buffer.read())
        asyncio.run(verify(ca_file))
    print(json.dumps({"signed_in_accounts": 2, "owner_sessions": 2, "https_verified": True,
                      "source_refresh_and_intent_replayed": True, "invalid_inventory_rolled_back": True,
                      "cross_account_isolation": True, "worker_started_by_lifespan_and_resumed": True,
                      "partial_audio_and_private_library_verified": True}))
