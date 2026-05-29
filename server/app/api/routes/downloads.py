"""
API routes for downloads and settings.

Matches the API contracts defined in docs/server/api-contract.md.
All endpoints require authentication and use standard envelope responses.
"""

import logging
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.auth import AuthenticatedUser, get_current_user
from app.schemas.downloads import (
    CookieUploadRequest,
    DownloadCreateResponse,
    DownloadJobListResponse,
    DownloadJobResponse,
    DownloadRequest,
    PaginationMeta,
    ResolveDownloadRequest,
)
from app.schemas.responses import ErrorDetails, ResponseEnvelope
from app.services.download_service import DownloadService, DOWNLOADS_DIR
from app.services.exceptions import BadRequest, NotFound

logger = logging.getLogger(__name__)

router = APIRouter()
download_service = DownloadService()


@router.post(
    "/downloads",
    response_model=ResponseEnvelope[DownloadCreateResponse],
    status_code=status.HTTP_201_CREATED,
)
async def create_download(
    request: DownloadRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Request a track download.

    Validates user authentication and creates an asynchronous download job.
    """
    try:
        job = download_service.create_job(
            user_id=current_user.id,
            track_id=request.track_id,
            provider_name="youtube",
            source_hint=request.source_hint.model_dump() if request.source_hint else None,
        )

        data = DownloadCreateResponse(
            job_id=job.id,
            track_id=job.track_id,
            status=job.status,
        )
        return ResponseEnvelope(data=data)

    except BadRequest as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )


@router.get(
    "/downloads",
    response_model=ResponseEnvelope[DownloadJobListResponse],
)
async def list_downloads(
    status_filter: Optional[str] = Query(None, alias="status"),
    limit: int = Query(20, ge=1, le=100),
    cursor: Optional[str] = Query(None),
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    List download jobs for the authenticated user.

    Supports filtering by status and cursor-based pagination.
    """
    jobs, next_cursor = download_service.list_jobs(
        user_id=current_user.id,
        status=status_filter,
        limit=limit,
        cursor=cursor,
    )

    items = [
        DownloadJobResponse(
            id=job.id,
            track_id=job.track_id,
            provider_name=job.provider_name,
            status=job.status,
            progress_percent=job.progress_percent,
            error_code=job.error_code,
            error_message=job.error_message,
            attempt_count=job.attempt_count,
            created_at=job.created_at,
            updated_at=job.updated_at,
        )
        for job in jobs
    ]

    data = DownloadJobListResponse(
        items=items,
        meta=PaginationMeta(next_cursor=next_cursor, total_count=len(jobs)),
    )
    return ResponseEnvelope(data=data)


@router.post(
    "/downloads/{job_id}/retry",
    response_model=ResponseEnvelope[DownloadCreateResponse],
)
async def retry_download(
    job_id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """Retry a failed or cancelled download job."""
    try:
        job = download_service.retry_job(
            user_id=current_user.id,
            job_id=job_id,
        )

        data = DownloadCreateResponse(
            job_id=job.id,
            track_id=job.track_id,
            status=job.status,
        )
        return ResponseEnvelope(data=data)

    except NotFound as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e),
        )
    except BadRequest as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )


@router.post(
    "/downloads/{job_id}/resolve",
    response_model=ResponseEnvelope[DownloadCreateResponse],
)
async def resolve_download(
    job_id: str,
    request: ResolveDownloadRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Resolve a pending download job by choosing one of the YouTube Music candidates.
    """
    try:
        job = download_service.get_job(user_id=current_user.id, job_id=job_id)
        if job.status != "requires_resolution":
            raise BadRequest(f"Job is not in requires_resolution state: status={job.status}")
            
        import asyncio
        from datetime import datetime, timezone
        
        job.status = "queued"
        job.progress_percent = 0.0
        job.error_code = None
        job.error_message = None
        job.candidates = []
        job.updated_at = datetime.now(timezone.utc)
        
        # Trigger background download task with the selected video_id injected in source_hint
        asyncio.create_task(
            download_service._run_download_job(
                job_id=job_id,
                source_hint={"resolved_video_id": request.video_id}
            )
        )
        
        data = DownloadCreateResponse(
            job_id=job.id,
            track_id=job.track_id,
            status=job.status,
        )
        return ResponseEnvelope(data=data)
        
    except NotFound as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e),
        )
    except BadRequest as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )



@router.post(
    "/me/settings/cookies",
    response_model=ResponseEnvelope[dict],
)
async def upload_cookies(
    request: CookieUploadRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Upload YouTube cookies in Netscape format.

    Enables clients (Android AURA App) to easily refresh cookies to bypass YouTube blocks.
    """
    try:
        success = download_service.update_user_cookies(request.cookies_text)
        return ResponseEnvelope(data={"success": success})

    except BadRequest as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )


@router.get(
    "/downloads/{job_id}/file",
)
async def serve_downloaded_file(
    job_id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Fetch the physical downloaded audio file (MP3).

    Accessible only after the corresponding job is in 'succeeded' state.
    """
    from fastapi.responses import FileResponse
    from pathlib import Path
    
    try:
        job = download_service.get_job(user_id=current_user.id, job_id=job_id)
        
        if job.status != "succeeded":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Download job is not succeeded: status={job.status}",
            )
            
        expected_file = DOWNLOADS_DIR / f"{job_id}.mp3"
        if not expected_file.exists():
            # Fallback checks
            matches = list(DOWNLOADS_DIR.glob(f"{job_id}.*"))
            non_thumb = [m for m in matches if m.suffix not in (".jpg", ".png", ".webp")]
            if non_thumb:
                expected_file = non_thumb[0]
            else:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail="Audio file not found on disk",
                )
                
        return FileResponse(
            path=str(expected_file),
            media_type="audio/mpeg",
            filename=f"{job_id}.mp3",
        )
        
    except NotFound as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e),
        )

