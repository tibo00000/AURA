"""Child worker for the disposable PostgreSQL restart test, using synthetic audio."""
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from types import SimpleNamespace
import wave

from fastapi import Depends, FastAPI
from httpx import ASGITransport, AsyncClient
import jwt

from app.api.routes import me, sync_files
from app.core.auth import AuthenticatedUser, get_current_user
from app.db.user_client import get_current_user_client
from app.services import download_service
from app.services.playlist_acquisition import PlaylistAcquisitionWorker


OWNER = "c0000000-0000-0000-0000-000000000061"
OTHER_ACCOUNT = "c0000000-0000-0000-0000-000000000062"
PLAYLIST = "restart-fixture"
TRACK = "trk_restart_fixture"
VIDEO = "fixture0000"
EXIT_AFTER_COMMIT = 42


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, (bool, int, float)):
        return json.dumps(value)
    if isinstance(value, (dict, list)):
        return literal(json.dumps(value)) + "::jsonb"
    if isinstance(value, str):
        return "'" + value.replace("'", "''") + "'"
    raise TypeError(type(value))


class DockerPostgres:
    """Only the service calls used by this fixture; values come from synthetic inputs."""
    def __init__(self, container, owner=None):
        self.container = container
        self.owner = owner

    def scalar(self, statement):
        role = "SET ROLE service_role;" if self.owner is None else (
            f'SET ROLE authenticated; SET "request.jwt.claim.sub" = {literal(self.owner)};'
        )
        result = subprocess.run(
            ["docker", "exec", "-i", self.container, "psql", "-X", "-qAt",
             "-v", "ON_ERROR_STOP=1", "-U", "postgres"],
            input=f"{role}\n{statement};\n", text=True,
            capture_output=True, timeout=12, check=True,
        )
        return json.loads(result.stdout.strip() or "null")

    def rpc(self, name, arguments):
        allowed = {"aura_get_playlist_acquisitions"} if self.owner is not None else {
            "aura_claim_playlist_acquisition", "aura_insert_playlist_download_job",
            "aura_restart_playlist_download_job", "aura_finish_playlist_acquisition",
        }
        assert name in allowed
        args = ", ".join(f"{key} => {literal(value)}" for key, value in arguments.items())
        return SimpleNamespace(execute=lambda: SimpleNamespace(data=self.scalar(f"SELECT to_jsonb(public.{name}({args}))")))

    def table(self, name):
        assert name == "download_jobs"
        return Jobs(self)


class Jobs:
    columns = {"id", "user_id", "track_id", "status", "attempt_count", "created_at"}
    table = "public.download_jobs"

    def __init__(self, database):
        self.database = database
        self.filters = []
        self.order_limit = ""
        self.changes = None

    def select(self, fields):
        assert fields == "*"
        return self

    def eq(self, key, value):
        assert key in self.columns
        self.filters.append(f"{key} = {literal(value)}")
        return self

    def in_(self, key, values):
        assert key in self.columns
        self.filters.append(f"{key} IN ({', '.join(map(literal, values))})")
        return self

    def order(self, key, *, desc):
        assert key in self.columns
        self.order_limit += f" ORDER BY {key}" + (" DESC" if desc else "")
        return self

    def limit(self, count):
        self.order_limit += f" LIMIT {int(count)}"
        return self

    def update(self, changes):
        self.changes = changes
        return self

    def execute(self):
        where = " AND ".join(self.filters)
        assert where
        if self.changes is None:
            query = f"SELECT * FROM {self.table} WHERE {where}{self.order_limit}"
            sql = f"SELECT COALESCE(jsonb_agg(to_jsonb(row)), '[]') FROM ({query}) row"
        else:
            columns = {"status", "progress_percent", "error_code", "error_message", "updated_at"}
            assert self.changes.keys() <= columns
            assignments = ", ".join(f"{key} = {literal(value)}" for key, value in self.changes.items())
            sql = (f"WITH changed AS (UPDATE {self.table} SET {assignments} WHERE {where} RETURNING *) "
                   "SELECT COALESCE(jsonb_agg(to_jsonb(changed)), '[]') FROM changed")
        return SimpleNamespace(data=self.database.scalar(sql))


def make_downloader(interrupt):
    class FixtureDownloader:
        def __init__(self, options):
            self.target = Path(options["outtmpl"]).parent / "audio.mp3"

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            pass

        def extract_info(self, target, *, download):
            assert download and target == f"https://www.youtube.com/watch?v={VIDEO}"
            if interrupt:
                os._exit(EXIT_AFTER_COMMIT)
            with wave.open(str(self.target), "wb") as audio:
                audio.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
                audio.writeframes(b"\x00\x00" * 800)
            return {"id": VIDEO}

    return FixtureDownloader


async def assert_http_availability(container):
    def headers(owner):
        claims = {
            "sub": owner, "role": "authenticated", "aud": "authenticated",
            "iss": os.environ["SUPABASE_URL"] + "/auth/v1", "exp": int(time.time()) + 60,
        }
        bearer = jwt.encode(claims, os.environ["SUPABASE_JWT_SECRET"], algorithm="HS256")
        return {"Authorization": f"Bearer {bearer}"}

    def user_database(user: AuthenticatedUser = Depends(get_current_user)):
        return DockerPostgres(container, owner=user.id)

    api = FastAPI()
    api.include_router(me.router)
    api.include_router(sync_files.router)
    api.dependency_overrides[get_current_user_client] = user_database
    async with AsyncClient(transport=ASGITransport(app=api), base_url="http://testserver") as client:
        report_url = f"/me/playlists/{PLAYLIST}/acquisitions"
        audio_url = f"/me/sync/files/{TRACK}"
        assert (await client.get(report_url, headers=headers(OTHER_ACCOUNT))).status_code == 404
        report = await client.get(report_url, headers=headers(OWNER))
        assert report.status_code == 200, report.text
        assert len(report.json()["data"]) == 1
        acquisition = report.json()["data"][0]
        assert acquisition["track_id"] == TRACK and acquisition["availability"] == "available"
        assert acquisition["job_status"] == "succeeded"
        assert (await client.get(audio_url)).status_code == 401
        audio = await client.get(audio_url, headers=headers(OWNER) | {"Range": "bytes=0-3"})
        assert audio.status_code == 206, audio.status_code
        assert audio.content == b"RIFF"
        assert audio.headers["content-range"].startswith("bytes 0-3/")


async def run(container, phase):
    assert phase in {"interrupt", "resume"}
    manifest = Path("audio-authorizations.json")
    manifest.write_text(json.dumps({TRACK: {
        "video_id": VIDEO, "provenance": "locally generated silence",
        "authorized_for_cloud": True,
    }}), encoding="utf-8")
    download_service.yt_dlp.YoutubeDL = make_downloader(interrupt=phase == "interrupt")
    database = DockerPostgres(container)
    service = download_service.DownloadService(supabase_client=database)
    worker = PlaylistAcquisitionWorker(service, manifest)
    assert await worker.run_once()
    await service._workers.join()
    if phase == "resume":
        database.scalar(f"WITH due AS (UPDATE public.playlist_acquisition_intents SET "
                        f"next_attempt_at = clock_timestamp() WHERE track_id = {literal(TRACK)} RETURNING 1) "
                        "SELECT count(*) FROM due")
        assert await worker.run_once()
        path, metadata = sync_files.find_playable_sync_file(OWNER, TRACK)
        assert path and path.read_bytes().startswith(b"RIFF")
        assert metadata["title"] == "Fixture title" and metadata["duration_ms"] == 100
        await assert_http_availability(container)
    await service.shutdown()


if __name__ == "__main__":
    asyncio.run(run(*sys.argv[1:]))
