"""
Download and jobs service.

Manages download jobs (in-memory store) and runs asynchronous download operations
using yt-dlp, Deno, IPv6, PO Token provider, and user cookies.
"""

import asyncio
import logging
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import yt_dlp

from app.config import get_settings
from app.core.aura_id_codec import parse_aura_id
from app.core.id_generator import generate_id
from app.domain.models import DownloadJob
from app.providers.deezer.client import DeezerClient
from app.services.exceptions import BadRequest, NotFound

logger = logging.getLogger(__name__)

DOWNLOADS_DIR = Path(os.getenv("DOWNLOADS_DIR", "/app/downloads"))

# In-memory global store for jobs (job_id -> DownloadJob)
_jobs_db: Dict[str, DownloadJob] = {}


def _build_yt_dlp_opts(output_dir: Path, download_id: str) -> dict:
    """Build yt-dlp options optimised for VPS bypass."""
    pot_url = os.getenv("POT_PROVIDER_URL", "http://localhost:4416/token")
    
    opts = {
        # Output
        "outtmpl": str(output_dir / f"{download_id}.%(ext)s"),
        "final_ext": "mp3",

        # Format selection
        "format": "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best",

        # Network resilience
        "geo_bypass": True,
        "nocheckcertificate": True,
        "socket_timeout": 30,
        "retries": 3,
        "fragment_retries": 3,
        
        # Bypassing VPS anti-bot blocks (IPv6 + PO Token)
        "force_ipv6": True,
        "extractor_args": {
            "youtube": {
                "po_token": [f"web+{pot_url}"],
                "client": ["web_safari"],  # Safari emulation
            }
        },

        # Enable downloading JS solvers for Deno from GitHub
        "remote_components": ["ejs:github"],

        # Search: get the first result
        "default_search": "ytsearch1",

        # Quiet output
        "quiet": True,
        "no_warnings": False,
        "noplaylist": True,

        # Embed metadata & thumbnail
        "postprocessors": [
            {
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "320",
            },
            {
                "key": "FFmpegMetadata",
                "add_metadata": True,
            },
            {
                "key": "EmbedThumbnail",
            },
        ],
        "writethumbnail": True,
    }

    # Inject user cookies if uploaded in the downloads folder
    cookies_file = output_dir / "cookies.txt"
    if cookies_file.exists():
        logger.info("Using uploaded cookie file: %s", cookies_file)
        opts["cookiefile"] = str(cookies_file)

    return opts


class DownloadService:
    """Service to handle download jobs and user settings."""

    def __init__(self):
        DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)
        self.settings = get_settings()
        self.deezer_client = DeezerClient(self.settings.deezer_api_base_url)

    def create_job(
        self, user_id: str, track_id: str, provider_name: str = "youtube", source_hint: Optional[dict] = None
    ) -> DownloadJob:
        """Create a new download job and triggers the async background download."""
        if not track_id.startswith("trk_"):
            raise BadRequest("Invalid track ID format. Must start with 'trk_'")

        job_id = generate_id("job")
        now = datetime.now(timezone.utc)

        job = DownloadJob(
            id=job_id,
            user_id=user_id,
            track_id=track_id,
            provider_name=provider_name,
            status="queued",
            progress_percent=0.0,
            attempt_count=1,
            created_at=now,
            updated_at=now,
        )

        _jobs_db[job_id] = job
        logger.info("Created download job %s for user %s, track %s", job_id, user_id, track_id)

        # Trigger real background download task
        asyncio.create_task(self._run_download_job(job_id, source_hint))

        return job

    def get_job(self, user_id: str, job_id: str) -> DownloadJob:
        """Retrieve a specific job for a user."""
        job = _jobs_db.get(job_id)
        if not job or job.user_id != user_id:
            raise NotFound(f"Download job {job_id} not found for this user")
        return job

    def list_jobs(
        self, user_id: str, status: Optional[str] = None, limit: int = 20, cursor: Optional[str] = None
    ) -> Tuple[List[DownloadJob], Optional[str]]:
        """List download jobs for a user."""
        user_jobs = [j for j in _jobs_db.values() if j.user_id == user_id]

        if status:
            user_jobs = [j for j in user_jobs if j.status == status]

        user_jobs.sort(key=lambda j: j.created_at or datetime.min, reverse=True)

        start_idx = 0
        if cursor:
            for i, job in enumerate(user_jobs):
                if job.id == cursor:
                    start_idx = i + 1
                    break

        sliced_jobs = user_jobs[start_idx : start_idx + limit]

        next_cursor = None
        if len(user_jobs) > start_idx + limit:
            next_cursor = sliced_jobs[-1].id

        return sliced_jobs, next_cursor

    def retry_job(self, user_id: str, job_id: str) -> DownloadJob:
        """Retry a failed or cancelled job."""
        job = _jobs_db.get(job_id)
        if not job or job.user_id != user_id:
            raise NotFound(f"Download job {job_id} not found")

        if job.status not in ("failed", "cancelled"):
            raise BadRequest(f"Cannot retry a job that is currently {job.status}")

        now = datetime.now(timezone.utc)
        job.status = "queued"
        job.progress_percent = 0.0
        job.error_code = None
        job.error_message = None
        job.attempt_count += 1
        job.updated_at = now

        logger.info("Retrying download job %s, attempt %d", job_id, job.attempt_count)

        # Trigger real background download task
        asyncio.create_task(self._run_download_job(job_id))

        return job

    def update_user_cookies(self, cookies_text: str) -> bool:
        """Upload Netscape cookies to the persistent storage."""
        cookies_text = cookies_text.strip()
        if not cookies_text:
            raise BadRequest("Cookies text content is empty")

        lines = cookies_text.splitlines()
        is_netscape = any(line.startswith("# Netscape") for line in lines[:5])
        has_tabs = any("\t" in line for line in lines if line and not line.startswith("#"))

        if not (is_netscape or has_tabs):
            raise BadRequest("Invalid cookie format. Must be Netscape format.")

        cookies_file = DOWNLOADS_DIR / "cookies.txt"
        try:
            cookies_file.write_text(cookies_text, encoding="utf-8")
            logger.info("Updated YouTube cookies file at %s", cookies_file)
            return True
        except Exception as e:
            logger.error("Failed to write cookies file: %s", e)
            raise BadRequest(f"Internal error saving cookies: {str(e)}")

    async def _run_download_job(self, job_id: str, source_hint: Optional[dict] = None) -> None:
        """
        Background download worker task using yt-dlp.

        1. Resolves the track_id (calls Deezer API if it's an opaque AURA ID).
        2. Initiates the yt-dlp download with progress hooks.
        3. Converts format, embeds metadata and saves file in volumes.
        4. Updates status to succeeded or failed with diagnostic messages.
        """
        job = _jobs_db.get(job_id)
        if not job:
            return

        # 1. Resolve track metadata to get the YouTube search query
        query = None
        try:
            # Check if this is a valid encoded stateless AURA ID
            ref = parse_aura_id(job.track_id)
            if ref.provider_name == "deezer":
                logger.info("Resolving Deezer metadata for track: %s", ref.provider_id)
                track_data = await self.deezer_client.get_track(ref.provider_id)
                title = track_data.get("title", "")
                artist = track_data.get("artist", {}).get("name", "")
                query = f"{artist} {title}".strip()
                logger.info("Resolved query: %r from Deezer ID: %s", query, ref.provider_id)
        except ValueError:
            # Fallback for manual/test IDs (e.g. trk_01KSRA...)
            pass

        # If we couldn't resolve via Deezer, fallback to source_hint or track_id name
        if not query:
            if source_hint and source_hint.get("provider_track_id"):
                query = source_hint["provider_track_id"]
            else:
                # Last resort fallback (uses track_id as a search query if test ID)
                query = job.track_id

        # Clean fallback in case query is too short
        if not query or len(query) < 3:
            query = "Booba DKR"  # Default fallback for testing

        job.status = "running"
        job.progress_percent = 5.0
        job.updated_at = datetime.now(timezone.utc)
        logger.info("Starting real download for query: %r (Job: %s)", query, job_id)

        # 2. Setup progress hooks
        def _progress_hook(d: dict) -> None:
            if d["status"] == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                downloaded = d.get("downloaded_bytes", 0)
                if total > 0:
                    job.progress_percent = round((downloaded / total) * 100, 1)
                    job.updated_at = datetime.now(timezone.utc)
            elif d["status"] == "finished":
                job.progress_percent = 99.0
                job.updated_at = datetime.now(timezone.utc)

        # 3. Define the blocking yt-dlp execution
        def _execute_yt_dlp():
            opts = _build_yt_dlp_opts(DOWNLOADS_DIR, job_id)
            opts["progress_hooks"] = [_progress_hook]

            try:
                with yt_dlp.YoutubeDL(opts) as ydl:
                    info = ydl.extract_info(query, download=True)
                    if info is None:
                        job.status = "failed"
                        job.error_code = "job_failed"
                        job.error_message = "No search result found on YouTube"
                        return

                    expected_file = DOWNLOADS_DIR / f"{job_id}.mp3"
                    if expected_file.exists():
                        job.status = "succeeded"
                        job.progress_percent = 100.0
                    else:
                        matches = list(DOWNLOADS_DIR.glob(f"{job_id}.*"))
                        non_thumb = [m for m in matches if m.suffix not in (".jpg", ".png", ".webp")]
                        if non_thumb:
                            job.status = "succeeded"
                            job.progress_percent = 100.0
                        else:
                            job.status = "failed"
                            job.error_code = "job_failed"
                            job.error_message = "Audio conversion failed, no MP3 found."
            except yt_dlp.utils.DownloadError as e:
                job.status = "failed"
                job.error_code = "job_failed"
                job.error_message = str(e).splitlines()[-1] if str(e).splitlines() else str(e)
            except Exception as e:
                job.status = "failed"
                job.error_code = "job_failed"
                job.error_message = f"Unexpected: {type(e).__name__}: {str(e)}"
            finally:
                job.updated_at = datetime.now(timezone.utc)

        # 4. Run blocking yt-dlp in a thread pool
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, _execute_yt_dlp)
        logger.info("Real download job finished. Status: %s, Progress: %s%%", job.status, job.progress_percent)
