"""
Minimal yt-dlp download service for testing audio downloads on the VPS.

This is a test-only module — no database, no jobs, no auth.
The goal is to validate that yt-dlp can successfully download audio
from the Contabo VPS without IP blocks or anti-bot issues.
"""

import asyncio
import logging
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from enum import Enum

import yt_dlp

logger = logging.getLogger(__name__)

DOWNLOADS_DIR = Path(os.getenv("DOWNLOADS_DIR", "/app/downloads"))


class DownloadStatus(str, Enum):
    QUEUED = "queued"
    SEARCHING = "searching"
    DOWNLOADING = "downloading"
    CONVERTING = "converting"
    SUCCEEDED = "succeeded"
    FAILED = "failed"


@dataclass
class DownloadResult:
    status: DownloadStatus = DownloadStatus.QUEUED
    query: str = ""
    title: str | None = None
    artist: str | None = None
    filename: str | None = None
    file_size_bytes: int | None = None
    duration_seconds: int | None = None
    progress_percent: float = 0.0
    error: str | None = None
    source_url: str | None = None
    started_at: float = 0.0
    finished_at: float | None = None


# In-memory store of recent downloads (test only — no persistence)
_recent_downloads: dict[str, DownloadResult] = {}


def _build_yt_dlp_opts(output_dir: Path, download_id: str) -> dict:
    """
    Build yt-dlp options optimised for VPS reliability.

    Key choices:
    - format: best audio only (no video), prefer m4a/opus to avoid re-encoding
    - postprocessors: convert to mp3 320kbps via ffmpeg
    - geo_bypass: try to bypass geo-restrictions
    - no_check_certificates: avoid cert issues on some VPS
    - socket_timeout: prevent hanging forever
    - retries: be resilient to transient failures
    - force_ipv6: YouTube blocking is less strict on IPv6 subnets
    - extractor_args: Configure PO Token Provider to bypass bot checks
    """
    pot_url = os.getenv("POT_PROVIDER_URL", "http://localhost:4416/token")
    
    return {
        # Output
        "outtmpl": str(output_dir / f"{download_id}.%(ext)s"),
        "final_ext": "mp3",

        # Format selection: best audio, prefer formats that don't need re-encoding
        "format": "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best",

        # Network resilience for VPS
        "geo_bypass": True,
        "nocheckcertificate": True,  # True to bypass potential VPS ssl cert issues
        "socket_timeout": 30,
        "retries": 3,
        "fragment_retries": 3,
        
        # Bypassing VPS anti-bot blocks (IPv6 + PO Token)
        "force_ipv6": True,
        "extractor_args": {
            "youtube": {
                "po_token": [f"web+{pot_url}"],
                "client": ["web_safari"],  # Safari emulation to avoid DRM and blocks
            }
        },

        # Search: use ytsearch1 to get the first result
        "default_search": "ytsearch1",

        # Quiet output (we capture via hooks)
        "quiet": True,
        "no_warnings": False,

        # Don't download playlists
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


async def download_audio(query: str, download_id: str) -> DownloadResult:
    """
    Download audio matching the query using yt-dlp.

    Runs yt-dlp in a thread pool to avoid blocking the async event loop.
    The query can be:
    - A YouTube URL (direct download)
    - A search term (uses ytsearch1: prefix automatically)
    """
    result = DownloadResult(
        status=DownloadStatus.SEARCHING,
        query=query,
        started_at=time.time(),
    )
    _recent_downloads[download_id] = result

    DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)

    def _progress_hook(d: dict) -> None:
        if d["status"] == "downloading":
            result.status = DownloadStatus.DOWNLOADING
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            downloaded = d.get("downloaded_bytes", 0)
            if total > 0:
                result.progress_percent = round((downloaded / total) * 100, 1)
        elif d["status"] == "finished":
            result.status = DownloadStatus.CONVERTING
            result.progress_percent = 100.0

    def _do_download() -> None:
        opts = _build_yt_dlp_opts(DOWNLOADS_DIR, download_id)
        opts["progress_hooks"] = [_progress_hook]

        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(query, download=True)

                if info is None:
                    result.status = DownloadStatus.FAILED
                    result.error = "yt-dlp returned no info — search may have found nothing"
                    return

                # Handle search results (info might be a playlist wrapper)
                if info.get("_type") == "playlist" and info.get("entries"):
                    info = info["entries"][0]

                result.title = info.get("title")
                result.artist = info.get("artist") or info.get("uploader") or info.get("channel")
                result.duration_seconds = info.get("duration")
                result.source_url = info.get("webpage_url")

                # Find the final mp3 file
                expected_file = DOWNLOADS_DIR / f"{download_id}.mp3"
                if expected_file.exists():
                    result.filename = expected_file.name
                    result.file_size_bytes = expected_file.stat().st_size
                    result.status = DownloadStatus.SUCCEEDED
                else:
                    # Try to find any file with our download_id
                    matches = list(DOWNLOADS_DIR.glob(f"{download_id}.*"))
                    non_thumb = [m for m in matches if m.suffix not in (".jpg", ".png", ".webp")]
                    if non_thumb:
                        result.filename = non_thumb[0].name
                        result.file_size_bytes = non_thumb[0].stat().st_size
                        result.status = DownloadStatus.SUCCEEDED
                    else:
                        result.status = DownloadStatus.FAILED
                        result.error = f"Download completed but no audio file found. Glob found: {[m.name for m in matches]}"

        except yt_dlp.utils.DownloadError as e:
            result.status = DownloadStatus.FAILED
            result.error = f"yt-dlp DownloadError: {str(e)}"
            logger.error("yt-dlp download failed for %r: %s", query, e)
        except Exception as e:
            result.status = DownloadStatus.FAILED
            result.error = f"Unexpected error: {type(e).__name__}: {str(e)}"
            logger.exception("Unexpected error downloading %r", query)

        result.finished_at = time.time()

    # Run blocking yt-dlp in a thread
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, _do_download)

    return result


def get_download(download_id: str) -> DownloadResult | None:
    """Get a download result by ID."""
    return _recent_downloads.get(download_id)


def list_downloads() -> dict[str, DownloadResult]:
    """List all recent downloads."""
    return dict(_recent_downloads)


def get_file_path(filename: str) -> Path | None:
    """Get the full path of a downloaded file, if it exists."""
    path = DOWNLOADS_DIR / filename
    if path.exists() and path.is_file():
        return path
    return None
