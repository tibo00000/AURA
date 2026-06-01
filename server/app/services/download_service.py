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
from app.services.exceptions import BadRequest, NotFound, RequiresResolutionException
from app.db.supabase import supabase

logger = logging.getLogger(__name__)

DOWNLOADS_DIR = Path(os.getenv("DOWNLOADS_DIR", "/app/downloads"))

from ytmusicapi import YTMusic
from rapidfuzz import fuzz

ytmusic = YTMusic()


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

    def _resolve_official_video_id(
        self, artist: str, title: str, album: Optional[str] = None
    ) -> Optional[str]:
        """
        Surgical targeting strategy for official releases on YouTube Music.
        """
        if not artist or not title:
            return None
            
        logger.info(
            "YTM Targeted Search: starting resolution for Artist=%r, Title=%r, Album=%r",
            artist, title, album
        )
        
        resolved_id = None
        
        # We need an album name to run the album filter strategy.
        if album:
            try:
                # Step A: Search for the album
                query = f"{artist} {album}".strip()
                logger.info("YTM Step A: searching albums with query: %r", query)
                search_results = ytmusic.search(query, filter="albums")
                
                validated_album = None
                for item in search_results:
                    artists_found = item.get("artists", [])
                    if not artists_found:
                        continue
                    found_artist = artists_found[0].get("name", "")
                    
                    # Fuzzy match artist name (case-insensitive)
                    ratio = fuzz.ratio(artist.lower(), found_artist.lower())
                    logger.info("YTM Step A: found album %r by artist %r, fuzzy ratio=%d%%", item.get("title"), found_artist, ratio)
                    
                    if ratio >= 75:
                        validated_album = item
                        break
                
                if validated_album:
                    browse_id = validated_album.get("browseId")
                    if browse_id:
                        # Step B: Retrieve the album's tracklist
                        logger.info("YTM Step B: fetching album tracks for browseId: %s", browse_id)
                        album_detail = ytmusic.get_album(browse_id)
                        tracks = album_detail.get("tracks", [])
                        
                        # Step C: Score tracks using fuzzy match
                        best_track = None
                        best_score = -1
                        
                        for track in tracks:
                            track_title = track.get("title", "")
                            track_video_id = track.get("videoId")
                            if not track_title or not track_video_id:
                                continue
                                
                            score = -1
                            # Case-insensitive perfect match (100)
                            if track_title.strip().lower() == title.strip().lower():
                                score = 100
                            # Content match (99)
                            elif title.strip().lower() in track_title.strip().lower():
                                score = 99
                            # Partial match (fuzz.ratio)
                            else:
                                score = fuzz.ratio(title.lower(), track_title.lower())
                                
                            logger.info("YTM Step C: scoring album track %r (videoId=%s) -> score=%d", track_title, track_video_id, score)
                            
                            if score > best_score:
                                best_score = score
                                best_track = track
                                
                        if best_track and best_score >= 75:
                            resolved_id = best_track["videoId"]
                            logger.info(
                                "YTM Targeted Search SUCCESS: selected %r (videoId=%s) with score %d",
                                best_track.get("title"), resolved_id, best_score
                            )
                            return resolved_id
            except Exception as e:
                logger.error("YTM Targeted Search: Exception during album strategy: %s", e, exc_info=True)

        # Fallback to smart song search
        if not resolved_id:
            logger.info("Album strategy failed or skipped, trying smart song search fallback")
            resolved_id = self._smart_song_search(artist, title)
            
        if resolved_id:
            return resolved_id
            
        # If still not found or score < 75%, trigger RequiresResolutionException
        candidates = self._fetch_5_candidates(artist, title)
        raise RequiresResolutionException(candidates=candidates)

    def _smart_song_search(self, artist: str, title: str) -> Optional[str]:
        """
        Smart fallback searching songs and filtering for high confidence match.
        """
        try:
            query = f"{artist} {title}".strip()
            logger.info("YTM Smart Song Search: searching songs for query: %r", query)
            results = ytmusic.search(query, filter="songs")
            if not results:
                return None
                
            best_song_id = None
            best_score = -1
            
            for item in results[:3]:  # inspect top 3 songs
                video_id = item.get("videoId")
                if not video_id:
                    continue
                    
                artists_found = item.get("artists", [])
                found_artist = ", ".join([a.get("name", "") for a in artists_found if a.get("name")])
                found_title = item.get("title", "")
                
                # Compute fuzzy scores
                artist_ratio = fuzz.ratio(artist.lower(), found_artist.lower())
                title_ratio = fuzz.ratio(title.lower(), found_title.lower())
                
                # Combined score
                score = (artist_ratio + title_ratio) / 2
                logger.info("YTM Smart Song Search: candidate %r by %r, score=%d%% (artist=%d%%, title=%d%%)", 
                            found_title, found_artist, score, artist_ratio, title_ratio)
                
                if artist_ratio >= 75 and title_ratio >= 75 and score > best_score:
                    best_score = score
                    best_song_id = video_id
                    
            if best_song_id:
                logger.info("YTM Smart Song Search SUCCESS: selected videoId=%s with score %d%%", best_song_id, best_score)
                return best_song_id
                
            return None
        except Exception as e:
            logger.error("YTM Smart Song Search error: %s", e)
            return None

    def _fetch_5_candidates(self, artist: str, title: str) -> List[dict]:
        """Fetch 5 candidate songs from YTM search for user choice."""
        try:
            query = f"{artist} {title}".strip()
            logger.info("Fetching 5 YTM candidates for query: %r", query)
            results = ytmusic.search(query, filter="songs")
            candidates = []
            for item in results[:5]:
                video_id = item.get("videoId")
                if not video_id:
                    continue
                
                # Format artist name
                artists_found = item.get("artists", [])
                artist_name = ", ".join([a.get("name", "") for a in artists_found if a.get("name")])
                
                # Cover URI
                cover_uri = None
                thumbnails = item.get("thumbnails", [])
                if thumbnails:
                    cover_uri = thumbnails[0].get("url")
                
                duration = item.get("duration")
                album_name = item.get("album", {}).get("name") if isinstance(item.get("album"), dict) else None

                candidates.append({
                    "video_id": video_id,
                    "title": item.get("title", ""),
                    "artist": artist_name or artist,
                    "album": album_name,
                    "duration": duration,
                    "cover_uri": cover_uri,
                })
            return candidates
        except Exception as e:
            logger.error("Failed to fetch YTM candidates: %s", e)
            return []

    def create_job(
        self, user_id: str, track_id: str, provider_name: str = "youtube", source_hint: Optional[dict] = None
    ) -> DownloadJob:
        """Create a new download job and triggers the async background download."""
        if not track_id.startswith("trk_"):
            raise BadRequest("Invalid track ID format. Must start with 'trk_'")

        # Check if a download job already exists for this track and user
        try:
            existing = supabase.table("download_jobs").select("*").eq("user_id", user_id).eq("track_id", track_id).execute()
            if existing.data:
                # Find the most recent active or successful job
                sorted_jobs = sorted(existing.data, key=lambda x: x.get("created_at", ""), reverse=True)
                for job_dict in sorted_jobs:
                    existing_job = DownloadJob.from_dict(job_dict)
                    
                    # If job is currently active (queued, running, requires_resolution), reuse it
                    if existing_job.status in ("queued", "running", "requires_resolution"):
                        logger.info("Reusing existing active download job %s for user %s, track %s", existing_job.id, user_id, track_id)
                        return existing_job
                        
                    # If job is already succeeded, verify the physical file exists on disk
                    if existing_job.status == "succeeded":
                        expected_file = DOWNLOADS_DIR / f"{existing_job.id}.mp3"
                        if expected_file.exists() and expected_file.stat().st_size > 0:
                            logger.info("Reusing existing succeeded download job %s for user %s, track %s (file present)", existing_job.id, user_id, track_id)
                            return existing_job
                        else:
                            # If succeeded but file is missing, we reset its status to queued and trigger it again
                            logger.info("Found succeeded job %s but file was missing. Resetting to queued...", existing_job.id)
                            now = datetime.now(timezone.utc)
                            existing_job.status = "queued"
                            existing_job.progress_percent = 0.0
                            existing_job.error_code = None
                            existing_job.error_message = None
                            existing_job.updated_at = now
                            supabase.table("download_jobs").update({
                                "status": "queued",
                                "progress_percent": 0.0,
                                "error_code": None,
                                "error_message": None,
                                "updated_at": now.isoformat()
                            }).eq("id", existing_job.id).execute()
                            
                            asyncio.create_task(self._run_download_job(existing_job.id, source_hint))
                            return existing_job
        except Exception as e:
            logger.error("Failed to query existing jobs in Supabase: %s", e)
            # Fail silently and proceed to create a new job to prevent blocking downloads

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

        try:
            supabase.table("download_jobs").insert(job.to_dict()).execute()
        except Exception as e:
            logger.error("Failed to insert job %s in Supabase: %s", job_id, e)
            raise BadRequest(f"Failed to create job in database: {str(e)}")

        logger.info("Created download job %s for user %s, track %s in Supabase", job_id, user_id, track_id)

        # Trigger real background download task
        asyncio.create_task(self._run_download_job(job_id, source_hint))

        return job

    def get_job(self, user_id: str, job_id: str) -> DownloadJob:
        """Retrieve a specific job for a user from Supabase."""
        try:
            response = supabase.table("download_jobs").select("*").eq("id", job_id).eq("user_id", user_id).execute()
            if not response.data:
                raise NotFound(f"Download job {job_id} not found for this user")
            return DownloadJob.from_dict(response.data[0])
        except NotFound:
            raise
        except Exception as e:
            logger.error("Failed to retrieve job %s from Supabase: %s", job_id, e)
            raise BadRequest(f"Failed to retrieve job: {str(e)}")

    def list_jobs(
        self, user_id: str, status: Optional[str] = None, limit: int = 20, cursor: Optional[str] = None
    ) -> Tuple[List[DownloadJob], Optional[str]]:
        """List download jobs for a user from Supabase."""
        try:
            query = supabase.table("download_jobs").select("*").eq("user_id", user_id)
            if status:
                query = query.eq("status", status)

            # Cursor-based pagination: created_at < cursor's created_at
            if cursor:
                cursor_response = supabase.table("download_jobs").select("created_at").eq("id", cursor).execute()
                if cursor_response.data:
                    cursor_time = cursor_response.data[0]["created_at"]
                    query = query.lt("created_at", cursor_time)

            # Retrieve limit + 1 items to determine if there is a next page
            response = query.order("created_at", desc=True).limit(limit + 1).execute()
            data_list = response.data or []

            jobs = [DownloadJob.from_dict(d) for d in data_list[:limit]]

            next_cursor = None
            if len(data_list) > limit:
                next_cursor = jobs[-1].id

            return jobs, next_cursor
        except Exception as e:
            logger.error("Failed to list jobs from Supabase: %s", e)
            return [], None

    def retry_job(self, user_id: str, job_id: str) -> DownloadJob:
        """Retry a failed or cancelled job in Supabase."""
        job = self.get_job(user_id, job_id)

        if job.status not in ("failed", "cancelled"):
            raise BadRequest(f"Cannot retry a job that is currently {job.status}")

        now = datetime.now(timezone.utc)
        job.status = "queued"
        job.progress_percent = 0.0
        job.error_code = None
        job.error_message = None
        job.attempt_count += 1
        job.updated_at = now

        try:
            supabase.table("download_jobs").update({
                "status": "queued",
                "progress_percent": 0.0,
                "error_code": None,
                "error_message": None,
                "attempt_count": job.attempt_count,
                "updated_at": now.isoformat()
            }).eq("id", job_id).execute()
        except Exception as e:
            logger.error("Failed to update job %s for retry in Supabase: %s", job_id, e)
            raise BadRequest(f"Failed to retry job: {str(e)}")

        logger.info("Retrying download job %s in Supabase, attempt %d", job_id, job.attempt_count)

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

    def _update_job_status(self, job_id: str, **kwargs) -> None:
        """Helper to quickly update specific job fields in Supabase in real-time."""
        try:
            kwargs["updated_at"] = datetime.now(timezone.utc).isoformat()
            supabase.table("download_jobs").update(kwargs).eq("id", job_id).execute()
        except Exception as e:
            logger.error("Failed to update job status for %s in Supabase: %s", job_id, e)

    async def _run_download_job(self, job_id: str, source_hint: Optional[dict] = None) -> None:
        """
        Background download worker task using yt-dlp.

        1. Resolves the track_id (calls Deezer API if it's an opaque AURA ID).
        2. Initiates the yt-dlp download with progress hooks.
        3. Converts format, embeds metadata and saves file in volumes.
        4. Updates status to succeeded or failed with diagnostic messages.
        """
        try:
            response = supabase.table("download_jobs").select("*").eq("id", job_id).execute()
            if not response.data:
                logger.error("Job %s not found in Supabase", job_id)
                return
            job = DownloadJob.from_dict(response.data[0])
        except Exception as e:
            logger.error("Failed to fetch job %s from Supabase for worker: %s", job_id, e)
            return

        # 1. Resolve track metadata to get the YouTube search query
        query = None
        artist = None
        title = None
        album = None
        ref = None
        try:
            # Check if this is a valid encoded stateless AURA ID
            ref = parse_aura_id(job.track_id)
            if ref.provider_name == "deezer":
                logger.info("Resolving Deezer metadata for track: %s", ref.provider_id)
                track_data = await self.deezer_client.get_track(ref.provider_id)
                title = track_data.get("title", "")
                artist = track_data.get("artist", {}).get("name", "")
                album = track_data.get("album", {}).get("title", "")
                query = f"{artist} {title}".strip()
                logger.info("Resolved query: %r, album: %r from Deezer ID: %s", query, album, ref.provider_id)
        except ValueError:
            # Fallback for manual/test IDs (e.g. trk_01KSRA...)
            pass

        # Try to resolve targeted YouTube Music videoId
        resolved_video_id = None
        if source_hint and source_hint.get("resolved_video_id"):
            resolved_video_id = source_hint["resolved_video_id"]
            logger.info("Direct videoId provided in source_hint: %s", resolved_video_id)
        elif ref and ref.provider_name == "deezer" and artist and title:
            # We run the targeted search in a thread pool since ytmusicapi makes blocking HTTP requests
            def _run_resolve():
                return self._resolve_official_video_id(artist, title, album)
            
            loop = asyncio.get_event_loop()
            try:
                resolved_video_id = await loop.run_in_executor(None, _run_resolve)
            except RequiresResolutionException as e:
                # Capture des candidats et mise à jour du job en requires_resolution
                self._update_job_status(
                    job_id,
                    status="requires_resolution",
                    candidates=e.candidates
                )
                logger.info("Job %s requires user resolution, suspended download thread.", job_id)
                return
            except Exception as e:
                logger.error("Error during targeted search: %s", e)
                # Si autre chose échoue, on continue avec le fallback de téléchargement normal par requête

        # Build download target
        if resolved_video_id:
            download_target = f"https://www.youtube.com/watch?v={resolved_video_id}"
            logger.info("Targeted search resolved official videoId: %s. Direct target: %s", resolved_video_id, download_target)
        else:
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
                
            download_target = query
            logger.info("Targeted search returned no candidate. Falling back to search query: %r", download_target)

        self._update_job_status(job_id, status="running", progress_percent=5.0)
        logger.info("Starting real download for target: %r (Job: %s)", download_target, job_id)

        # 2. Setup progress hooks (with performance throttling to avoid saturating Supabase requests)
        last_update_time = [0.0]
        last_progress = [0.0]

        def _progress_hook(d: dict) -> None:
            if d["status"] == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                downloaded = d.get("downloaded_bytes", 0)
                if total > 0:
                    progress = round((downloaded / total) * 100, 1)
                    now_time = time.time()
                    if (progress - last_progress[0] >= 3.0) or (now_time - last_update_time[0] >= 2.0):
                        self._update_job_status(job_id, progress_percent=progress)
                        last_progress[0] = progress
                        last_update_time[0] = now_time
            elif d["status"] == "finished":
                self._update_job_status(job_id, progress_percent=99.0)

        # 3. Define the blocking yt-dlp execution
        def _execute_yt_dlp():
            opts = _build_yt_dlp_opts(DOWNLOADS_DIR, job_id)
            opts["progress_hooks"] = [_progress_hook]

            status = "failed"
            error_code = None
            error_message = None
            progress_percent = 0.0

            try:
                with yt_dlp.YoutubeDL(opts) as ydl:
                    info = ydl.extract_info(download_target, download=True)
                    if info is None:
                        status = "failed"
                        error_code = "job_failed"
                        error_message = "No search result found on YouTube"
                        return

                    expected_file = DOWNLOADS_DIR / f"{job_id}.mp3"
                    if expected_file.exists():
                        status = "succeeded"
                        progress_percent = 100.0
                    else:
                        matches = list(DOWNLOADS_DIR.glob(f"{job_id}.*"))
                        non_thumb = [m for m in matches if m.suffix not in (".jpg", ".png", ".webp")]
                        if non_thumb:
                            status = "succeeded"
                            progress_percent = 100.0
                        else:
                            status = "failed"
                            error_code = "job_failed"
                            error_message = "Audio conversion failed, no MP3 found."
            except yt_dlp.utils.DownloadError as e:
                status = "failed"
                error_code = "job_failed"
                error_message = str(e).splitlines()[-1] if str(e).splitlines() else str(e)
            except Exception as e:
                status = "failed"
                error_code = "job_failed"
                error_message = f"Unexpected: {type(e).__name__}: {str(e)}"
            finally:
                # Update Supabase with the final state
                self._update_job_status(
                    job_id,
                    status=status,
                    progress_percent=progress_percent if status == "succeeded" else None,
                    error_code=error_code,
                    error_message=error_message,
                )

        # 4. Run blocking yt-dlp in a thread pool
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, _execute_yt_dlp)
        logger.info("Real download job finished. (Job: %s)", job_id)
