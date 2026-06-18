"""
API routes for generic jobs monitoring.

Matches the API contracts defined in docs/server/api-contract.md.
Provides status tracking for asynchronous tasks (e.g. downloads).
"""

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.auth import AuthenticatedUser, get_current_user
from app.schemas.downloads import JobStatusResponse
from app.schemas.responses import ResponseEnvelope
from app.services.download_service import DownloadService
from app.services.exceptions import NotFound

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/jobs", tags=["jobs"])
download_service = DownloadService()


@router.get(
    "/{job_id}",
    response_model=ResponseEnvelope[JobStatusResponse],
)
async def get_job_status(
    job_id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Get the status of an asynchronous job.

    Enables clients to monitor progress for tasks like track downloads.
    """
    try:
        # Currently, all jobs are downloads in our service layer
        job = download_service.get_job(
            user_id=current_user.id,
            job_id=job_id,
        )

        error_payload = None
        if job.error_code:
            error_payload = {
                "code": job.error_code,
                "message": job.error_message or "Job failed",
            }

        data = JobStatusResponse(
            id=job.id,
            kind="download",
            status=job.status,
            progress_percent=job.progress_percent,
            error=error_payload,
            candidates=job.candidates or None,
            created_at=job.created_at,
            updated_at=job.updated_at,
        )
        return ResponseEnvelope(data=data)

    except NotFound as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e),
        )
