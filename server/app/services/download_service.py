"""
Download and jobs service.

Manages download jobs (in-memory store for now) and simulates the progress 
of the asynchronous tasks to validate the client integration of SRV-006.
Also handles saving and validating user cookies for YouTube download bypass.
"""

import asyncio
import logging
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from app.core.id_generator import generate_id
from app.domain.models import DownloadJob
from app.services.exceptions import BadRequest, NotFound

logger = logging.getLogger(__name__)

DOWNLOADS_DIR = Path(os.getenv("DOWNLOADS_DIR", "/app/downloads"))

# In-memory global store for jobs (job_id -> DownloadJob)
_jobs_db: Dict[str, DownloadJob] = {}


class DownloadService:
    """Service to handle download jobs and user settings."""

    def __init__(self):
        DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)

    def create_job(
        self, user_id: str, track_id: str, provider_name: str = "youtube", source_hint: Optional[dict] = None
    ) -> DownloadJob:
        """
        Create a new download job.

        Args:
            user_id: Owner of the job
            track_id: Target track (trk_{ulid})
            provider_name: The download source provider (default: "youtube")
            source_hint: Optional hints for matching
        """
        # Simple ID validation
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

        # Trigger simulated background work
        asyncio.create_task(self._simulate_job_progress(job_id))

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
        """
        List download jobs for a user, sorted by creation date descending.

        Supports filtering by status and cursor pagination.
        """
        # Filter jobs for user
        user_jobs = [j for j in _jobs_db.values() if j.user_id == user_id]

        if status:
            user_jobs = [j for j in user_jobs if j.status == status]

        # Sort: newest first
        user_jobs.sort(key=lambda j: j.created_at or datetime.min, reverse=True)

        # Basic pagination via cursor (which is the job_id of the last item in previous page)
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

        logger.info("Retrying download job %s, attempt count: %d", job_id, job.attempt_count)

        # Trigger simulated background work again
        asyncio.create_task(self._simulate_job_progress(job_id))

        return job

    def update_user_cookies(self, cookies_text: str) -> bool:
        """
        Upload cookies text (Netscape format) to the persistent storage.

        Validates the Netscape format structure slightly to avoid trash text.
        """
        cookies_text = cookies_text.strip()
        if not cookies_text:
            raise BadRequest("Cookies text content is empty")

        # Basic Netscape cookies.txt format check (starts with # Netscape HTTP Cookie File or contains tabs)
        lines = cookies_text.splitlines()
        is_netscape = any(line.startswith("# Netscape") for line in lines[:5])
        has_tabs = any("\t" in line for line in lines if line and not line.startswith("#"))

        if not (is_netscape or has_tabs):
            raise BadRequest("Invalid cookie format. Must be Netscape HTTP Cookie File format (tab-separated)")

        cookies_file = DOWNLOADS_DIR / "cookies.txt"
        try:
            cookies_file.write_text(cookies_text, encoding="utf-8")
            logger.info("Updated YouTube cookies file at %s", cookies_file)
            return True
        except Exception as e:
            logger.error("Failed to write cookies file: %s", e)
            raise BadRequest(f"Internal error saving cookies: {str(e)}")

    async def _simulate_job_progress(self, job_id: str) -> None:
        """
        Background simulation task for a job.
        
        Progresses the job status: queued -> running -> succeeded (or failed).
        Updates state in the global memory dictionary.
        """
        job = _jobs_db.get(job_id)
        if not job:
            return

        # 1. Wait in queue shortly
        await asyncio.sleep(2)
        
        # Check if status has changed (e.g. cancelled)
        if job.status != "queued":
            return

        # 2. Transition to running
        job.status = "running"
        job.progress_percent = 5.0
        job.updated_at = datetime.now(timezone.utc)
        logger.debug("Job %s changed status to running", job_id)

        # 3. Simulate progress over a few seconds
        steps = 5
        for i in range(1, steps + 1):
            await asyncio.sleep(1.5)
            if job.status != "running":
                # Interrupted
                return
            
            # Progress goes up
            job.progress_percent = round((i / steps) * 90.0 + 5.0, 1)
            job.updated_at = datetime.now(timezone.utc)

        # 4. Final step (conversion and finishing)
        await asyncio.sleep(1)
        if job.status != "running":
            return

        # Simulating potential failure to test client-side error handling
        # Let's say: if the track_id ends with 'fail' or 10% chance
        should_fail = job.track_id.endswith("_fail") or (hash(job_id) % 10 == 0)

        now = datetime.now(timezone.utc)
        if should_fail:
            job.status = "failed"
            job.progress_percent = 95.0
            job.error_code = "job_failed"
            job.error_message = "Proof-of-Origin Token validation failed (Simulated Error)"
            logger.warning("Simulated job failure for %s", job_id)
        else:
            job.status = "succeeded"
            job.progress_percent = 100.0
            logger.info("Simulated job success for %s", job_id)

        job.updated_at = now
