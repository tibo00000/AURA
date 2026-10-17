"""After explicit authorization only: two disposable files in the test user's space.

Execute inside the corrected aura-api, credentials/token on stdin. No upload/provider,
database access, recursive delete or production library listing. Always keep maintenance
if a check fails. This program does not change the proxy or any operational settings.
"""
import hashlib
import io
import json
import logging
from pathlib import Path
import sys
import uuid
import wave

import httpx

from app.config import get_settings
from app.core.auth import _decode_jwt_payload


FILES = ("app/core/auth.py", "app/api/router.py", "app/core/operational_logging.py", "app/core/security-logging.json")


def require(condition):
    if not condition:
        raise RuntimeError("target_probe_failed")


def run(payload, client_factory=httpx.Client, expected_base=Path("/app/downloads/sync_files")):
    logging.disable(logging.CRITICAL)
    require(all(hashlib.sha256(Path(name).read_bytes()).hexdigest() == payload["hashes"][name] for name in FILES))
    settings = get_settings()
    require(not settings.app_debug and settings.supabase_url.rstrip("/") == payload["expected_origin"])
    user = _decode_jwt_payload(payload["access_token"])["sub"]
    require(user == str(uuid.UUID(payload["user_id"])))
    base = Path(settings.sync_files_dir).resolve()
    require(base == expected_base.resolve() and base.is_dir())
    folder = base / hashlib.sha256(user.encode()).hexdigest()
    require(not folder.is_symlink())
    new_folder = not folder.exists()
    folder.mkdir(exist_ok=True)
    track = "security-lot1-" + uuid.UUID(payload["fixture_id"]).hex
    key = hashlib.sha256(track.encode()).hexdigest()
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(8000)
        wav.writeframes(b"\x00\x00" * 800)
    audio = buffer.getvalue()
    created = []
    result = {}
    try:
        for name, content in ((key + ".audio", audio), (key + ".json", b'{"mime_type":"audio/wav"}')):
            path = folder / name
            # Exclusive creation; record ownership immediately, including on partial writes.
            stream = path.open("xb")
            created.append((path, content))
            with stream:
                stream.write(content)
        target = "https://aura-prod.duckdns.org" if payload["route"] == "public" else "http://127.0.0.1:8000"
        require(payload["route"] in ("internal", "public"))
        with client_factory(base_url=target, timeout=6, follow_redirects=False, trust_env=False) as client:
            require(client.get("/health").status_code == 200)
            path = "/me/sync/files/" + track
            require(client.get(path, params={"token": "SYNTHETIC_TARGET_REFUSAL"}).status_code == 401)
            for token in (payload["access_token"], "Bearer " + payload["access_token"]):
                full = client.get(path, params={"token": token})
                require(full.status_code == 200 and full.content == audio)
                partial = client.get(path, params={"token": token}, headers={"Range": "bytes=0-31"})
                require(partial.status_code == 206 and partial.content == audio[:32])
            require(client.get(path, headers={"Authorization": "Bearer " + payload["access_token"]}).status_code == 200)
            for retired in ("/test", "/test/downloads", "/test/downloads/security-lot1/file"):
                require(client.get(retired).status_code == 404)
            result = {"target_private_and_streaming": 200, "target_range": 206, "target_invalid": 401,
                      "target_test_route": 404, "route": payload["route"], "synthetic_files_created": 2}
    finally:
        # Unlink only our exact exclusively-created paths; never recurse or delete by glob.
        for path, content in created:
            require(not path.is_symlink() and path.parent == folder)
            path.unlink()
        if new_folder:
            folder.rmdir()  # Refuse to remove a directory another operation has populated.
        result["synthetic_files_removed"] = len(created)
    return result


if __name__ == "__main__":
    try:
        print(json.dumps(run(json.load(sys.stdin))), flush=True)
    except BaseException:
        print(json.dumps({"target_probe_failed": True, "details_redacted": True}), flush=True)
        sys.exit(1)
