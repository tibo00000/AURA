"""Operator probe: credentials via stdin only; no production routes/data are accessed.

Run inside the verified lot-1 image, mounting only this script. Optional --wait-expiry
keeps the first real access token in memory until its actual wall-clock expiration.
This script creates Auth sessions; it requires authorization for the dedicated account.
"""
import io
import json
import logging
import sys
import tempfile
import time
import wave
from pathlib import Path
from unittest.mock import patch
from uuid import UUID

import httpx
import jwt
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.config import Settings
from app.core.auth import get_current_user


def require(value):
    if not value:
        raise RuntimeError("probe_check_failed")


def emit(result):
    print(json.dumps(result, sort_keys=True), flush=True)


def run(credentials, wait_expiry=False):
    logging.disable(logging.CRITICAL)
    from app.api.routes import sync_files

    url = credentials["url"]
    parsed = httpx.URL(url)
    require(parsed.scheme == "https" and parsed.host and not parsed.username and not parsed.password)
    require(not parsed.query and not parsed.fragment and parsed.path in ("", "/"))
    require(credentials["expected_origin"] == url.rstrip("/"))
    result = {"real_session": True, "production_backend_accessed": False}
    access = None
    with httpx.Client(base_url=url.rstrip("/") + "/auth/v1/", headers={"apikey": credentials["public_key"]},
                      timeout=10, follow_redirects=False, trust_env=False) as auth:
        def token_request(grant, body):
            response = auth.post("token", params={"grant_type": grant}, json=body)
            require(response.status_code == 200)
            data = response.json()
            require(isinstance(data.get("access_token"), str) and isinstance(data.get("refresh_token"), str))
            require(data.get("user", {}).get("id") == credentials["user_id"])
            return data

        try:
            initial = token_request("password", {"email": credentials["email"], "password": credentials["password"]})
            access = initial["access_token"]
            result["login"] = 200
            expected_id = str(UUID(credentials["user_id"]))
            with tempfile.TemporaryDirectory(prefix="aura-real-session-") as directory:
                settings = Settings(_env_file=None, app_env="test", app_debug=False,
                                    supabase_url=url, supabase_anon_key="", supabase_service_role_key="",
                                    supabase_jwt_secret="", sync_files_dir=directory,
                                    enable_startup_recovery=False, enable_cache_backfill=False)
                with patch("app.config.get_settings", return_value=settings), patch(
                    "app.core.auth.get_settings", return_value=settings
                ), patch("app.api.routes.sync_files.get_settings", return_value=settings):
                    app = FastAPI(debug=False)
                    app.include_router(sync_files.router)

                    @app.get("/auth-check")
                    async def private(user=Depends(get_current_user)):
                        require(user.id == expected_id)
                        return {"authenticated": True}

                    audio, metadata = sync_files._paths(expected_id, "security-lot1-isolated")
                    with wave.open(str(audio), "wb") as output:
                        output.setnchannels(1)
                        output.setsampwidth(2)
                        output.setframerate(8000)
                        output.writeframes(b"\x00\x00" * 800)
                    metadata.write_text(json.dumps({"mime_type": "audio/wav"}))
                    expected_audio = audio.read_bytes()
                    with TestClient(app, raise_server_exceptions=False) as client:
                        def verified(token):
                            response = client.get("/auth-check", headers={"Authorization": "Bearer " + token})
                            require(response.status_code == 200 and response.json() == {"authenticated": True})
                            # Only after successful production verifier: expose the allowlisted alg.
                            algorithm = jwt.get_unverified_header(token).get("alg")
                            require(algorithm in ("ES256", "RS256"))
                            return algorithm

                        result["login_algorithm"] = verified(access)
                        result["private_route"] = 200
                        subject = client.get("/auth-check", headers={"Authorization": "Bearer " + expected_id})
                        require(subject.status_code == 401)
                        parts = access.split(".")
                        parts[2] = ("A" if parts[2][0] != "A" else "B") + parts[2][1:]
                        require(client.get("/auth-check", headers={"Authorization": "Bearer " + ".".join(parts)}).status_code == 401)
                        require(client.get("/auth-check").status_code == 401)
                        require(client.get("/auth-check", headers={"Authorization": "Bearer invalid"}, params={"token": access}).status_code == 401)
                        result["invalid_uuid_missing_header_precedence"] = 401
                        for value in (access, "Bearer " + access):
                            full = client.get("/me/sync/files/security-lot1-isolated", params={"token": value})
                            require(full.status_code == 200 and full.content == expected_audio)
                            partial = client.get("/me/sync/files/security-lot1-isolated", params={"token": value}, headers={"Range": "bytes=0-31"})
                            require(partial.status_code == 206 and partial.content == expected_audio[:32])
                        result["isolated_streaming"] = 200
                        result["isolated_range"] = 206
                        refreshed = token_request("refresh_token", {"refresh_token": initial["refresh_token"]})
                        access = refreshed["access_token"]
                        result["refresh_algorithm"] = verified(access)
                        result["refresh"] = 200
                        # Signature and claims have already passed the exact backend verifier.
                        expiry = jwt.decode(initial["access_token"], options={"verify_signature": False})["exp"]
                        remaining = max(0, int(expiry - time.time()) + 3)
                        result["expiration_remaining_seconds"] = remaining
                        result["expiration"] = "pending_real_expiry"
                        emit(result)
                        if wait_expiry:
                            require(remaining <= 86400)
                            while time.time() <= expiry + 2:
                                time.sleep(min(20, max(0.1, expiry + 2 - time.time())))
                            require(client.get("/auth-check", headers={"Authorization": "Bearer " + initial["access_token"]}).status_code == 401)
                            result["expiration"] = 401
                            # Renew only this test session for a valid logout after the wait.
                            refreshed = token_request("refresh_token", {"refresh_token": refreshed["refresh_token"]})
                            access = refreshed["access_token"]
                            verified(access)
        finally:
            if access is not None:
                response = auth.post("logout", params={"scope": "local"}, headers={"Authorization": "Bearer " + access})
                result["local_logout"] = response.status_code
                require(response.status_code in (200, 204))
    emit(result)
    return result


if __name__ == "__main__":
    try:
        run(json.load(sys.stdin), "--wait-expiry" in sys.argv[1:])
    except BaseException:
        # No exception values, responses, traceback, email, UUID or URL query on stderr.
        emit({"probe_failed": True, "details_redacted": True})
        sys.exit(1)
