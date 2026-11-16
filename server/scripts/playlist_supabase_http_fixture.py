"""Exercise Aura's real Supabase client within the disposable Docker fixture."""

import asyncio
import json
import os
from pathlib import Path
import secrets
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


async def verify(ca_file):
    base_url = "https://localhost:8443"
    jwt_secret = os.environ["SUPABASE_JWT_SECRET"]
    os.environ.update({
        "APP_ENV": "test", "SUPABASE_URL": base_url, "SSL_CERT_FILE": str(ca_file), "NO_PROXY": "localhost,127.0.0.1",
        "SUPABASE_ANON_KEY": jwt.encode({"role": "anon", "exp": int(time.time()) + 300}, jwt_secret, "HS256"),
        "SUPABASE_SERVICE_ROLE_KEY": jwt.encode({"role": "service_role", "exp": int(time.time()) + 300}, jwt_secret, "HS256"),
    })
    from app.api.routes import me
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


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="aura-tls-") as workspace:
        ca_file = Path(workspace) / "ca.pem"
        ca_file.write_bytes(sys.stdin.buffer.read())
        asyncio.run(verify(ca_file))
    print(json.dumps({"signed_in_accounts": 2, "owner_sessions": 2, "https_verified": True,
                      "source_refresh_and_intent_replayed": True, "invalid_inventory_rolled_back": True,
                      "cross_account_isolation": True}))
