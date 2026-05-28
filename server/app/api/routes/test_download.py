"""
Test routes for yt-dlp audio download.

These routes have NO auth, NO database, NO job queue.
They exist solely to validate that yt-dlp works on the VPS.

Once validated, this module will be replaced by the real download
infrastructure (SRV-006).
"""

import logging
from dataclasses import asdict

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from app.core.id_generator import generate_id
from app.services.download_test_service import (
    DownloadStatus,
    download_audio,
    get_download,
    get_file_path,
    list_downloads,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/test", tags=["test-downloads"])


class DownloadRequest(BaseModel):
    """Request body for triggering a test download."""
    query: str = Field(
        ...,
        description="YouTube URL or search query (e.g. 'Booba DKR' or 'https://youtube.com/watch?v=...')",
        min_length=1,
        max_length=500,
    )


class DownloadResponse(BaseModel):
    """Response for a download operation."""
    download_id: str
    status: str
    query: str
    title: str | None = None
    artist: str | None = None
    filename: str | None = None
    file_size_bytes: int | None = None
    duration_seconds: int | None = None
    progress_percent: float = 0.0
    error: str | None = None
    source_url: str | None = None
    elapsed_seconds: float | None = None


def _result_to_response(download_id: str, result) -> DownloadResponse:
    elapsed = None
    if result.started_at:
        end = result.finished_at or __import__("time").time()
        elapsed = round(end - result.started_at, 1)

    return DownloadResponse(
        download_id=download_id,
        status=result.status.value,
        query=result.query,
        title=result.title,
        artist=result.artist,
        filename=result.filename,
        file_size_bytes=result.file_size_bytes,
        duration_seconds=result.duration_seconds,
        progress_percent=result.progress_percent,
        error=result.error,
        source_url=result.source_url,
        elapsed_seconds=elapsed,
    )


@router.post("/download", response_model=DownloadResponse)
async def test_download(request: DownloadRequest):
    """
    Trigger a test download via yt-dlp.

    This endpoint blocks until the download is complete (synchronous for
    testing purposes). In production, this would be an async job.

    Examples:
    - {"query": "Booba DKR"}
    - {"query": "https://www.youtube.com/watch?v=dQw4w9WgXcQ"}
    - {"query": "Damso Bruxelles Vie"}
    """
    download_id = generate_id("dl")
    logger.info("Starting test download %s for query: %r", download_id, request.query)

    result = await download_audio(request.query, download_id)

    logger.info(
        "Download %s finished: status=%s title=%r file=%s",
        download_id, result.status, result.title, result.filename,
    )

    return _result_to_response(download_id, result)


@router.get("/downloads", response_model=list[DownloadResponse])
async def list_test_downloads():
    """List all recent test downloads and their status."""
    all_downloads = list_downloads()
    return [
        _result_to_response(did, result)
        for did, result in all_downloads.items()
    ]


@router.get("/downloads/{download_id}", response_model=DownloadResponse)
async def get_test_download(download_id: str):
    """Get the status of a specific test download."""
    result = get_download(download_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Download not found")
    return _result_to_response(download_id, result)


@router.get("/downloads/{download_id}/file")
async def serve_test_file(download_id: str):
    """
    Serve the downloaded audio file.

    Returns the mp3 file for direct playback or download by the client.
    """
    result = get_download(download_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Download not found")

    if result.status != DownloadStatus.SUCCEEDED:
        raise HTTPException(
            status_code=400,
            detail=f"Download is not ready: status={result.status.value}",
        )

    if result.filename is None:
        raise HTTPException(status_code=500, detail="Download succeeded but filename is missing")

    file_path = get_file_path(result.filename)
    if file_path is None:
        raise HTTPException(status_code=404, detail="File not found on disk")

    return FileResponse(
        path=str(file_path),
        media_type="audio/mpeg",
        filename=f"{result.title or download_id}.mp3",
    )
