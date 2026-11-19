"""
API routes for downloads and settings.

Matches the API contracts defined in docs/server/api-contract.md.
All endpoints require authentication and use standard envelope responses.
"""

import logging
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status

from collections import defaultdict
import time

from app.core.auth import AuthenticatedUser, get_current_user
from app.schemas.downloads import (
    CandidatesQueryResponse,
    CookieUploadRequest,
    DownloadCreateResponse,
    DownloadJobListResponse,
    DownloadJobResponse,
    DownloadRequest,
    PaginationMeta,
    ReassignAudioRequest,
    ResolveDownloadRequest,
    YtmCandidate,
)
from app.schemas.responses import ErrorDetails, ResponseEnvelope
from app.services.download_service import DownloadService, DOWNLOADS_DIR
from app.services.exceptions import BadRequest, Conflict, NotFound, ProviderUnavailable
from app.services.download_lifecycle import blocking, legacy_audio
from app.api.routes.sync_files import find_playable_sync_file

logger = logging.getLogger(__name__)

router = APIRouter()
download_service = DownloadService()


@router.get('/me/audio-replacements', response_model=ResponseEnvelope[dict])
async def audio_replacement_history(
    track_id: Optional[str] = Query(None, max_length=512),
    before: Optional[int] = Query(None, ge=1),
    limit: int = Query(50, ge=1, le=100),
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """Authenticated collaborative history. Actor UUIDs, never sessions or source URLs."""
    from app.services.download_service import _get_sync_base
    from app.services.replacement_history import ReplacementHistory
    try:
        items = await blocking(lambda: ReplacementHistory(_get_sync_base()).list(
            track_id=track_id, before=before, limit=limit))
        return ResponseEnvelope(data={'items': items, 'next_cursor': items[-1]['sequence'] if len(items) == limit else None})
    except Exception:
        raise HTTPException(503, 'Replacement history temporarily unavailable') from None


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
        job = await download_service.create_job(
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

    except Conflict as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
    except ProviderUnavailable as e:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))
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
    def read_page():
        jobs, next_cursor = download_service.list_jobs(
            user_id=current_user.id,
            status=status_filter,
            limit=limit,
            cursor=cursor,
        )

        # One owner-scoped lookup per page; no metadata from another account or a
        # fuzzy title match. Historic jobs may outlive their private audio files.
        metadata_rows = download_service.supabase.table("playlist_acquisition_intents").select(
            "track_id,metadata"
        ).eq("user_id", current_user.id).in_("track_id", list({job.track_id for job in jobs})).execute().data if jobs else []
        metadata = {row["track_id"]: row["metadata"] for row in metadata_rows or []
                    if isinstance(row.get("metadata"), dict)}
        items = [
            DownloadJobResponse(
                id=job.id,
                track_id=job.track_id,
                provider_name=job.provider_name,
                operation_type=job.operation_type,
                status=job.status,
                progress_percent=job.progress_percent,
                error_code=job.error_code,
                error_message=job.error_message,
                attempt_count=job.attempt_count,
                created_at=job.created_at,
                updated_at=job.updated_at,
                metadata=metadata.get(job.track_id),
                audio_available=(find_playable_sync_file(current_user.id, job.track_id)[0] is not None
                                 or legacy_audio(DOWNLOADS_DIR, job.id) is not None)
                    if job.status == "succeeded" else None,
            )
            for job in jobs
        ]

        return items, next_cursor

    try:
        items, next_cursor = await blocking(read_page)
    except Exception as exc:
        logger.warning("Download metadata unavailable (%s)", type(exc).__name__)
        raise HTTPException(status_code=503, detail="Download metadata temporarily unavailable") from None

    data = DownloadJobListResponse(
        items=items,
        meta=PaginationMeta(next_cursor=next_cursor, total_count=len(items)),
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
        job = await download_service.retry_job(
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
    except Conflict as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
    except ProviderUnavailable as e:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))
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
        job = await download_service.resolve_job(
            user_id=current_user.id, job_id=job_id, video_id=request.video_id,
        )

        data = DownloadCreateResponse(
            job_id=job.id,
            track_id=job.track_id,
            status="queued",
        )
        return ResponseEnvelope(data=data)
        
    except NotFound as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e),
        )
    except Conflict as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
    except ProviderUnavailable as e:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))
    except BadRequest as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )


@router.delete(
    "/downloads/{job_id}",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def delete_download_job(
    job_id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """Cancel and delete a download job."""
    try:
        await download_service.delete_job(user_id=current_user.id, job_id=job_id)
        return None
    except NotFound as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e),
        )
    except Conflict as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
    except ProviderUnavailable as e:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))
    except BadRequest as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )


_candidates_rate_limits: dict[str, list[float]] = defaultdict(list)


def _check_candidates_rate_limit(user_id: str, limit: int = 15, window_seconds: float = 60.0) -> bool:
    now = time.time()
    timestamps = [t for t in _candidates_rate_limits[user_id] if now - t < window_seconds]
    if len(timestamps) >= limit:
        _candidates_rate_limits[user_id] = timestamps
        return False
    timestamps.append(now)
    _candidates_rate_limits[user_id] = timestamps
    return True


@router.get(
    "/downloads/candidates",
    response_model=ResponseEnvelope[CandidatesQueryResponse],
)
async def get_audio_candidates(
    artist: Optional[str] = Query(None),
    title: Optional[str] = Query(None),
    query: Optional[str] = Query(None),
    limit: int = Query(10, ge=1, le=20),
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Search YouTube Music candidates for manual audio version selection.
    Rate limited to 15 requests per minute per user.
    """
    if not _check_candidates_rate_limit(current_user.id):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Rate limit exceeded for audio candidates search (max 15/min).",
        )

    import asyncio
    loop = asyncio.get_event_loop()
    candidates = await loop.run_in_executor(
        None,
        lambda: download_service.search_candidates(
            artist=artist or "",
            title=title or "",
            query=query,
            limit=limit,
        ),
    )

    items = [YtmCandidate(**c) for c in candidates]
    return ResponseEnvelope(data=CandidatesQueryResponse(items=items))


@router.post(
    "/downloads/reassign",
    response_model=ResponseEnvelope[DownloadCreateResponse],
    status_code=status.HTTP_201_CREATED,
)
async def reassign_audio(
    request: ReassignAudioRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Manually reassign the audio stream of a track to a specific YouTube video_id.
    """
    try:
        hint = request.source_hint.model_dump() if request.source_hint else None
        if request.reason:
            hint = {**(hint or {}), 'replacement_reason': request.reason}
        job = await download_service.reassign_track_audio(
            user_id=current_user.id,
            track_id=request.track_id,
            video_id=request.video_id,
            source_hint=hint,
        )

        data = DownloadCreateResponse(
            job_id=job.id,
            track_id=job.track_id,
            status=job.status,
        )
        return ResponseEnvelope(data=data)

    except Conflict as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
    except ProviderUnavailable as e:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))
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

    except Conflict as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
    except ProviderUnavailable as e:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))
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
        expected_file = await download_service.get_download_file(current_user.id, job_id)

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

    except BadRequest as e:
        raise HTTPException(status_code=400, detail=str(e))
    except ProviderUnavailable as e:
        raise HTTPException(status_code=503, detail=str(e))
