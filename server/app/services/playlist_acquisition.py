"""Durable admission of playlist audio using the existing download service."""
import asyncio
import json
import logging
import re
from pathlib import Path

from app.api.routes.sync_files import find_playable_sync_file
from app.core.id_generator import generate_id
from app.services.download_lifecycle import blocking
from app.services.exceptions import NotFound

logger = logging.getLogger(__name__)
_VIDEO_ID = re.compile(r"[A-Za-z0-9_-]{11}\Z")


class PlaylistAcquisitionWorker:
    def __init__(self, downloads, manifest_path="", *, poll_seconds=10, is_playable=None, authorize=None):
        self.downloads = downloads
        self.manifest_path = manifest_path
        self.poll_seconds = poll_seconds
        self.is_playable = is_playable or self._is_playable
        self.authorize = authorize or self._authorized_hint
        self.stopping = asyncio.Event()

    @staticmethod
    def _is_playable(user_id, track_id):
        source, _ = find_playable_sync_file(user_id, track_id)
        return source is not None

    def _authorized_hint(self, intent):
        if not self.manifest_path:
            return None
        with Path(self.manifest_path).open(encoding="utf-8") as handle:
            manifest = json.load(handle)
        track_id = intent["track_id"]
        entry = manifest.get(track_id) if isinstance(manifest, dict) else None
        video_id = entry.get("video_id") if isinstance(entry, dict) else None
        if not isinstance(video_id, str) or not _VIDEO_ID.fullmatch(video_id):
            return None
        if entry.get("authorized_for_cloud") is not True or not entry.get("provenance"):
            return None
        metadata = intent["metadata"]
        return {
            "resolved_video_id": video_id,
            "title": metadata.get("title"),
            "artist_name": metadata.get("artist"),
            "album_title": metadata.get("album"),
            "duration_ms": metadata.get("duration_ms"),
            "cover_uri": metadata.get("cover_uri"),
        }

    async def _rpc(self, name, **args):
        return await blocking(lambda: self.downloads.supabase.rpc(name, args).execute().data)

    async def _finish(self, intent, status, *, job=None, error=None, retry_after=0):
        finished = await self._rpc(
            "aura_finish_playlist_acquisition",
            p_user_id=intent["user_id"], p_track_id=intent["track_id"], p_lease_token=intent["lease_token"],
            p_status=status, p_job_id=job, p_error_code=error, p_retry_seconds=retry_after,
        )
        if finished is not True:
            logger.info("Playlist acquisition lease expired before completion")

    async def run_once(self):
        intent = await self._rpc("aura_claim_playlist_acquisition", p_job_id=generate_id("job"))
        if intent is None:
            return False
        user_id, track_id = intent["user_id"], intent["track_id"]
        try:
            if await blocking(self.is_playable, user_id, track_id):
                await self._finish(intent, "available")
                return True
            source_hint = await blocking(self.authorize, intent)
            if not track_id.startswith("trk_"):
                source_hint = None
            job = await self.downloads.admit_playlist_acquisition(
                user_id, track_id, intent["reserved_job_id"], intent["job_id"], source_hint, intent["lease_token"],
                retry=intent.get("error_code") == "retry_requested",
            )
            if job is None:
                await self._finish(intent, "source_not_authorized", error="authorized_audio_source_missing")
            elif job.status == "requires_resolution":
                await self._finish(intent, "admitted", job=job.id, retry_after=300)
            elif job.status == "succeeded":
                if await blocking(self.is_playable, user_id, track_id):
                    await self._finish(intent, "available", job=job.id)
                else:
                    await self._finish(intent, "failed", job=job.id, error="cloud_file_missing")
            elif job.status in ("failed", "cancelled", "superseded"):
                await self._finish(intent, "failed", job=job.id, error=job.error_code or "download_failed")
            else:
                await self._finish(intent, "admitted", job=job.id, retry_after=30)
        except NotFound:
            await self._finish(intent, "cancelled", error="download_job_removed")
        except Exception as exc:
            logger.warning("Playlist acquisition retry (%s)", type(exc).__name__)
            delay = min(900, 15 * 2 ** min(intent["failure_count"], 6))
            code = "retry_requested" if intent.get("error_code") == "retry_requested" else "acquisition_temporarily_unavailable"
            await self._finish(intent, intent["status"], error=code, retry_after=delay)
        return True

    async def run(self):
        while not self.stopping.is_set():
            try:
                for _ in range(8):
                    if self.stopping.is_set() or not await self.run_once():
                        break
            except Exception as exc:
                logger.warning("Playlist acquisition poll failed (%s)", type(exc).__name__)
            try:
                await asyncio.wait_for(self.stopping.wait(), self.poll_seconds)
            except TimeoutError:
                pass
