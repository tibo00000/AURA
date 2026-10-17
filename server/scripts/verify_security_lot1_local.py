"""Offline lot-1 probe. Run in an isolated backend image with no mounted volumes.

Only synthetic keys/users/audio are used. No .env is loaded, no remote calls are
made, and output contains only statuses/booleans. Production settings are untouched.
"""

import copy
import io
import json
import logging
import logging.config
import socket
import sys
import tempfile
import threading
import time
import wave
from pathlib import Path
from unittest.mock import patch

import httpx
import jwt
import uvicorn
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.config import Settings
from app.core.auth import get_current_user


MARKER = "SYNTHETIC_LOT1_LOG_MARKER"
USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
SECRET = "synthetic-offline-fixture-only-64-byte-secret-not-a-real-credential-123"


def require(condition):
    if not condition:
        raise RuntimeError("probe_assertion_failed")


def streaming_probe():
    from app.api.routes import sync_files

    with tempfile.TemporaryDirectory(prefix="aura-lot1-") as directory:
        settings = Settings(
            _env_file=None, app_env="test", app_debug=False,
            supabase_url="https://auth.example.test", supabase_jwt_secret=SECRET,
            supabase_anon_key="synthetic", supabase_service_role_key="synthetic",
            sync_files_dir=directory, enable_startup_recovery=False,
            enable_cache_backfill=False,
        )
        with patch("app.config.get_settings", return_value=settings), patch(
            "app.core.auth.get_settings", return_value=settings
        ), patch(
            "app.api.routes.sync_files.get_settings", return_value=settings
        ):
            # Use an already-present personal file; do not invoke uploads/cache writes.
            audio, metadata = sync_files._paths(USER, "lot1-synthetic")
            with wave.open(str(audio), "wb") as output:
                output.setnchannels(1)
                output.setsampwidth(2)
                output.setframerate(8000)
                output.writeframes(b"\x00\x00" * 800)
            metadata.write_text(json.dumps({"mime_type": "audio/wav"}))
            expected = audio.read_bytes()
            app = FastAPI(debug=False)
            app.include_router(sync_files.router)

            @app.get("/private")
            async def private(user=Depends(get_current_user)):
                return {"authenticated": user.id == USER}

            def token(expiry):
                return jwt.encode({
                    "sub": USER, "iss": "https://auth.example.test/auth/v1",
                    "aud": "authenticated", "role": "authenticated", "exp": expiry,
                }, SECRET, algorithm="HS256")

            valid, expired = token(int(time.time()) + 120), token(int(time.time()) - 60)
            # Disable client request logging before any JWT-bearing request exists.
            logging.getLogger("httpx").setLevel(logging.WARNING)
            logging.getLogger("httpcore").setLevel(logging.WARNING)
            with TestClient(app) as client:
                require(client.get("/private", headers={"Authorization": "Bearer " + valid}).json() == {"authenticated": True})
                for invalid in ("invalid", expired):
                    require(client.get("/private", headers={"Authorization": "Bearer " + invalid}).status_code == 401)
                for value in (valid, "Bearer " + valid):
                    response = client.get("/me/sync/files/lot1-synthetic", params={"token": value})
                    require(response.status_code == 200 and response.content == expected)
                    partial = client.get("/me/sync/files/lot1-synthetic", params={"token": value}, headers={"Range": "bytes=0-31"})
                    require(partial.status_code == 206 and partial.content == expected[:32])
                    require(partial.headers["content-range"] == f"bytes 0-31/{len(expected)}")
            return {"private": 200, "invalid": 401, "expired": 401, "stream_raw_and_bearer_query": 200, "range_raw_and_bearer_query": 206, "audio_bytes_match": True}


def logging_probe(access_log):
    # No JWT or credential is used by this HTTP/logging experiment.
    buffers = {name: io.StringIO() for name in ("access", "error", "application")}
    config = copy.deepcopy(uvicorn.config.LOGGING_CONFIG)
    config["handlers"]["access"]["stream"] = buffers["access"]
    config["handlers"]["default"]["stream"] = buffers["error"]
    for formatter in config["formatters"].values():
        formatter["use_colors"] = False
    logging.config.dictConfig(config)
    logger = logging.getLogger("lot1.synthetic.application")
    handler = logging.StreamHandler(buffers["application"])
    logger.handlers = [handler]
    logger.propagate = False
    logger.setLevel(logging.INFO)
    app = FastAPI(debug=False)

    @app.get("/private")
    async def private(user=Depends(get_current_user)):
        return {"authenticated": True}

    @app.get("/ok")
    async def ok():
        logger.info("synthetic request accepted")
        return {"ok": True}

    @app.get("/error")
    async def error():
        logger.error("synthetic application failure")
        raise RuntimeError("synthetic application failure")

    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(128)
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, log_config=None, access_log=access_log, lifespan="off"))
        thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + 15
            while not server.started and thread.is_alive() and time.monotonic() < deadline:
                time.sleep(0.01)
            require(server.started)
            statuses = []
            settings = Settings(_env_file=None, supabase_url="https://auth.example.test")
            with patch("app.core.auth.get_settings", return_value=settings), httpx.Client(base_url=f"http://127.0.0.1:{port}", trust_env=False, timeout=10) as client:
                for query in ("token=" + MARKER, "token=Bearer%20" + MARKER, "%74oken=" + MARKER, "token=" + MARKER + "&token=" + MARKER):
                    headers = {"Authorization": "Bearer " + MARKER, "Referer": "https://example.test/?token=" + MARKER, "Range": "bytes=0-31", "Connection": "close"}
                    for path, status in (("/private", 401), ("/ok", 200), ("/missing", 404), ("/error", 500)):
                        response = client.get(path + "?" + query, headers=headers)
                        require(response.status_code == status)
                        statuses.append(status)
        finally:
            server.should_exit = True
            thread.join(timeout=15)
            require(not thread.is_alive())
    found = {name: MARKER in buffer.getvalue() for name, buffer in buffers.items()}
    require(found == {"access": access_log, "error": False, "application": False})
    require("Traceback" in buffers["error"].getvalue())
    require("synthetic application failure" in buffers["application"].getvalue())
    return {"access_log_enabled": access_log, "marker_present": found, "error_trace_retained": True, "statuses": sorted(set(statuses)), "requests": len(statuses)}


if __name__ == "__main__":
    try:
        result = {"streaming_synthetic": streaming_probe(), "uvicorn_default": logging_probe(True), "uvicorn_no_access_log_candidate": logging_probe(False)}
    except Exception as exc:
        # Never emit exception text, locals, request URLs, JWTs or tracebacks.
        print(json.dumps({"passed": False, "error_type": type(exc).__name__}))
        sys.exit(1)
    print(json.dumps({"passed": True, **result}, sort_keys=True))
