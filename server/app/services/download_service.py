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


def _get_sync_base() -> Path:
    """Retourne le chemin de base absolu pour les fichiers synchronisés."""
    sync_base = Path(get_settings().sync_files_dir)
    return sync_base if sync_base.is_absolute() else Path.cwd() / sync_base


def _get_global_cache_dir() -> Path:
    """Retourne le répertoire de cache global dédoublonné."""
    cache_dir = _get_sync_base() / "_global_cache"
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir


def _get_track_key(track_id: str) -> str:
    """Calcule la clé canonique de stockage à partir du track_id fort."""
    import hashlib
    return hashlib.sha256(track_id.encode("utf-8")).hexdigest()


def _find_globally_cached_track(track_id: str) -> Optional[Tuple[Path, dict]]:
    """
    Vérifie si une piste existe déjà dans le cache global (_global_cache).
    Retourne (chemin_audio, métadonnées) si présente et valide (> 0 octets), sinon None.
    """
    import json
    track_key = _get_track_key(track_id)
    cache_dir = _get_global_cache_dir()
    cached_audio = cache_dir / f"{track_key}.audio"
    cached_json = cache_dir / f"{track_key}.json"

    if cached_audio.exists() and cached_audio.stat().st_size > 0:
        metadata = {}
        if cached_json.exists():
            try:
                metadata = json.loads(cached_json.read_text(encoding="utf-8"))
            except Exception as e:
                logger.warning("Could not read cached metadata for track %s: %s", track_id, e)
        return cached_audio, metadata
    return None


def _link_cached_track_to_user(
    user_id: str,
    track_id: str,
    cached_audio: Path,
    metadata: dict,
    override_metadata: Optional[dict] = None,
) -> bool:
    """
    Crée un hardlink atomique depuis le cache global vers le répertoire personnel d'un utilisateur.
    Écrit le fichier metadata.json correspondant pour le décompte du quota logique.
    """
    import hashlib
    import json
    import shutil
    from datetime import datetime, timezone

    try:
        sync_base = _get_sync_base()
        safe_user_id = hashlib.sha256(user_id.encode("utf-8")).hexdigest()
        user_dir = sync_base / safe_user_id
        user_dir.mkdir(parents=True, exist_ok=True)

        track_key = _get_track_key(track_id)
        target_audio = user_dir / f"{track_key}.audio"
        target_json = user_dir / f"{track_key}.json"

        # Hardlink Linux (même inode, 0 Mo d'espace disque supplémentaire)
        try:
            if target_audio.exists():
                target_audio.unlink()
            os.link(cached_audio, target_audio)
        except Exception:
            shutil.copyfile(cached_audio, target_audio)

        now = datetime.now(timezone.utc).isoformat()
        user_meta = dict(metadata)
        if override_metadata:
            for k in ("title", "artist_name", "album_title", "cover_uri", "duration_ms", "artist_id", "album_id"):
                if override_metadata.get(k):
                    user_meta[k] = override_metadata[k]

        user_meta["track_id"] = track_id
        user_meta["synced"] = True
        user_meta["size_bytes"] = target_audio.stat().st_size if target_audio.exists() else 0
        user_meta["mime_type"] = "audio/mpeg"
        user_meta["uploaded_at"] = now
        user_meta["updated_at"] = now

        target_json.write_text(json.dumps(user_meta, ensure_ascii=False), encoding="utf-8")
        logger.info("Successfully linked globally cached track %s to user %s", track_id, user_id)
        return True
    except Exception as e:
        logger.exception("Failed to link cached track %s to user %s: %s", track_id, user_id, e)
        return False


def _auto_register_in_sync_files(
    user_id: str,
    track_id: str,
    audio_file: Path,
    title: Optional[str] = None,
    artist_name: Optional[str] = None,
    album_title: Optional[str] = None,
    duration_ms: Optional[int] = None,
    artist_id: Optional[str] = None,
    album_id: Optional[str] = None,
    cover_uri: Optional[str] = None,
) -> bool:
    """
    Enregistre un titre téléchargé dans le cache global (_global_cache)
    puis crée un hardlink vers le répertoire personnel de l'utilisateur.
    """
    try:
        import hashlib
        import json
        import shutil
        from datetime import datetime, timezone

        cache_dir = _get_global_cache_dir()
        track_key = _get_track_key(track_id)
        cache_audio = cache_dir / f"{track_key}.audio"
        cache_json = cache_dir / f"{track_key}.json"

        # 1. Enregistrement dans le Cache Global
        try:
            if cache_audio.exists():
                cache_audio.unlink()
            os.link(audio_file, cache_audio)
        except Exception:
            shutil.copyfile(audio_file, cache_audio)

        uploaded_at = datetime.now(timezone.utc).isoformat()
        metadata = {
            "track_id": track_id,
            "synced": True,
            "size_bytes": cache_audio.stat().st_size if cache_audio.exists() else 0,
            "mime_type": "audio/mpeg",
            "title": title,
            "artist_name": artist_name,
            "album_title": album_title,
            "duration_ms": duration_ms,
            "artist_id": artist_id,
            "album_id": album_id,
            "cover_uri": cover_uri,
            "uploaded_at": uploaded_at,
            "updated_at": uploaded_at,
        }
        cache_json.write_text(json.dumps(metadata, ensure_ascii=False), encoding="utf-8")

        # 2. Hardlink vers l'espace personnel de l'utilisateur
        _link_cached_track_to_user(
            user_id=user_id,
            track_id=track_id,
            cached_audio=cache_audio,
            metadata=metadata,
        )

        logger.info("Auto-registered downloaded track %s in global cache and sync_files for user %s", track_id, user_id)
        return True
    except Exception as e:
        logger.exception("Failed to auto-register downloaded file to sync_files for track %s: %s", track_id, e)
        return False


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
        self._download_semaphore = asyncio.Semaphore(2)
        # Verrouillage de concurrence par piste pour déploiement mono-processus Uvicorn.
        # NOTE : Si le backend est mis à l'échelle sur plusieurs workers (Gunicorn --workers N),
        # migrer vers un verrou inter-processus (ex: fcntl.flock ou pg_advisory_lock).
        self._track_locks: Dict[str, asyncio.Lock] = {}
        self._track_locks_guard = asyncio.Lock()
        self._recover_stale_jobs_on_startup()
        self._backfill_global_cache()

    async def _get_track_lock(self, track_id: str) -> asyncio.Lock:
        """Obtient ou crée un verrou asyncio pour le track_id donné (thread-safe mono-processus)."""
        async with self._track_locks_guard:
            if track_id not in self._track_locks:
                self._track_locks[track_id] = asyncio.Lock()
            return self._track_locks[track_id]

    async def _prune_unused_locks(self) -> None:
        """Purge d'hygiène mémoire : supprime les verrous inactifs non détenus du registre."""
        async with self._track_locks_guard:
            unlocked_keys = [k for k, lock in self._track_locks.items() if not lock.locked()]
            for k in unlocked_keys:
                del self._track_locks[k]

    def _backfill_global_cache(self) -> int:
        """
        Recense au démarrage les fichiers audio existants dans sync_files/*/*.audio
        pour peupler automatiquement _global_cache avec tous les titres existants du propriétaire.
        """
        backfilled = 0
        try:
            sync_base = _get_sync_base()
            cache_dir = _get_global_cache_dir()
            for user_dir in sync_base.iterdir():
                if not user_dir.is_dir() or user_dir.name.startswith("_"):
                    continue
                for audio_file in user_dir.glob("*.audio"):
                    track_key = audio_file.stem
                    cache_audio = cache_dir / f"{track_key}.audio"
                    cache_json = cache_dir / f"{track_key}.json"
                    if not cache_audio.exists():
                        try:
                            os.link(audio_file, cache_audio)
                        except Exception:
                            try:
                                import shutil
                                shutil.copyfile(audio_file, cache_audio)
                            except Exception:
                                pass
                        src_json = audio_file.with_suffix(".json")
                        if src_json.exists() and not cache_json.exists():
                            try:
                                import shutil
                                shutil.copyfile(src_json, cache_json)
                            except Exception:
                                pass
                        backfilled += 1
            if backfilled > 0:
                logger.info("Backfill: %d titres existants indexés dans _global_cache.", backfilled)
        except Exception as e:
            logger.warning("Erreur lors du backfill de _global_cache: %s", e)
        return backfilled

    async def cleanup_orphaned_cache(self, max_age_days: int = 30) -> int:
        """
        Éviction sécurisée des orphelins : supprime les fichiers de _global_cache qui ont st_nlink == 1
        (aucun utilisateur ne les référence plus dans son Cloud privé) et inactifs depuis plus de max_age_days.
        CRITIQUE : Acquiert le verrou _track_locks pour chaque fichier candidat avant d'évaluer st_nlink == 1
        et de supprimer, éliminant ainsi toute fenêtre de course avec un cache hit concurrent.
        """
        import time
        now = time.time()
        max_age_seconds = max_age_days * 86400
        pruned_count = 0
        cache_dir = _get_global_cache_dir()

        for audio_file in list(cache_dir.glob("*.audio")):
            try:
                stat = audio_file.stat()
                # Premier filtre rapide sans verrou
                if stat.st_nlink == 1 and (now - stat.st_mtime > max_age_seconds):
                    track_key = audio_file.stem
                    lock = await self._get_track_lock(track_key)
                    # Acquérir le verrou de piste pour protéger l'évaluation et la suppression
                    async with lock:
                        if audio_file.exists():
                            fresh_stat = audio_file.stat()
                            if fresh_stat.st_nlink == 1 and (now - fresh_stat.st_mtime > max_age_seconds):
                                audio_file.unlink()
                                json_file = audio_file.with_suffix(".json")
                                if json_file.exists():
                                    json_file.unlink()
                                pruned_count += 1
                                logger.info("Pruned orphaned cache file: %s", audio_file.name)
            except Exception as e:
                logger.warning("Erreur lors de l'inspection/éviction du fichier %s: %s", audio_file, e)

        await self._prune_unused_locks()
        return pruned_count

    def _recover_stale_jobs_on_startup(self) -> None:
        """Mark stale running jobs as failed on server startup so they don't block polling forever."""
        try:
            supabase.table("download_jobs").update({
                "status": "failed",
                "error_code": "server_restarted",
                "error_message": "Téléchargement interrompu par le redémarrage du serveur.",
            }).eq("status", "running").execute()
            logger.info("Startup sweep: Marked orphaned 'running' download jobs as failed.")
        except Exception as e:
            logger.warning("Could not execute startup stale jobs sweep: %s", e)

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

        # 2. Vérification du Cache Global (_global_cache) pour Hit Instantané et Dédoublonné
        cached = _find_globally_cached_track(track_id)
        if cached:
            cached_audio, metadata = cached
            logger.info("GLOBAL CACHE HIT pour track %s ! Association instantanée à user %s (0s)", track_id, user_id)
            _link_cached_track_to_user(
                user_id=user_id,
                track_id=track_id,
                cached_audio=cached_audio,
                metadata=metadata,
                override_metadata=source_hint,
            )

            job_id = generate_id("job")
            now = datetime.now(timezone.utc)
            job = DownloadJob(
                id=job_id,
                user_id=user_id,
                track_id=track_id,
                provider_name=provider_name,
                status="succeeded",
                progress_percent=100.0,
                attempt_count=1,
                created_at=now,
                updated_at=now,
            )
            try:
                supabase.table("download_jobs").insert(job.to_dict()).execute()
            except Exception as e:
                logger.error("Failed to insert cached job %s in Supabase: %s", job_id, e)
                raise BadRequest(f"Failed to create job in database: {str(e)}")
            return job

        # 3. Cache MISS : Création du job queued standard et lancement du worker
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

    def delete_job(self, user_id: str, job_id: str) -> None:
        """Delete/cancel a specific download job for a user."""
        try:
            supabase.table("download_jobs").delete().eq("id", job_id).eq("user_id", user_id).execute()
            # Clean up potential partial or downloaded files from disk
            for pattern in (f"{job_id}.*", f"{job_id}.*.*"):
                for p in DOWNLOADS_DIR.glob(pattern):
                    try:
                        p.unlink()
                    except Exception:
                        pass
            logger.info("Deleted download job %s for user %s", job_id, user_id)
        except Exception as e:
            logger.error("Failed to delete job %s: %s", job_id, e)
            raise BadRequest(f"Failed to delete job: {str(e)}")

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
        try:
            async with self._download_semaphore:
                await self._run_download_job_impl(job_id, source_hint)
        except Exception as e:
            logger.exception("Unexpected error in download worker for job %s", job_id)
            self._update_job_status(
                job_id,
                status="failed",
                error_code="unexpected_error",
                error_message=str(e),
            )

    async def _run_download_job_impl(self, job_id: str, source_hint: Optional[dict] = None) -> None:
        """
        Background download worker task using yt-dlp.

        1. Resolves the track_id (calls Deezer API or parses source_hint).
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
        # Acquire track concurrency lock (Double-Checked Locking)
        # Prevents parallel yt-dlp runs for the same track_id across concurrent requests in mono-process
        track_lock = await self._get_track_lock(job.track_id)
        async with track_lock:
            # Double-Checked Locking : vérification si la piste a été téléchargée par un job concurrent
            cached = _find_globally_cached_track(job.track_id)
            if cached:
                cached_audio, metadata = cached
                logger.info("Worker job %s: track %s trouvé dans le cache global sous verrou ! Association 0s", job_id, job.track_id)
                _link_cached_track_to_user(
                    user_id=job.user_id,
                    track_id=job.track_id,
                    cached_audio=cached_audio,
                    metadata=metadata,
                    override_metadata=source_hint,
                )
                self._update_job_status(job_id, status="succeeded", progress_percent=100.0)
                return

            await self._execute_download_workflow(job, source_hint)

    async def _execute_download_workflow(self, job: DownloadJob, source_hint: Optional[dict] = None) -> None:
        job_id = job.id
        # 1. Resolve track metadata to get the YouTube search query
        query = None
        artist = None
        title = None
        album = None
        duration_ms = None
        artist_id = None
        album_id = None
        cover_uri = None
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
                duration_ms = int(track_data.get("duration", 0)) * 1000 if track_data.get("duration") else None
                artist_id = f"artist:{artist.lower().strip()}" if artist else None
                album_id = f"album:{artist.lower().strip()}:{album.lower().strip()}" if artist and album else None
                cover_uri = (
                    track_data.get("album", {}).get("cover_xl")
                    or track_data.get("album", {}).get("cover_big")
                    or track_data.get("album", {}).get("cover_medium")
                    or track_data.get("album", {}).get("cover")
                )
                query = f"{artist} {title}".strip()
                logger.info("Resolved query: %r, album: %r, cover: %r from Deezer ID: %s", query, album, cover_uri, ref.provider_id)
        except ValueError:
            # Fallback for manual/test IDs (e.g. trk_cloud_...)
            pass

        if source_hint:
            if not title and source_hint.get("title"):
                title = source_hint["title"]
            if not artist and source_hint.get("artist_name"):
                artist = source_hint["artist_name"]
            if not album and source_hint.get("album_title"):
                album = source_hint["album_title"]
            if not cover_uri and source_hint.get("cover_uri"):
                cover_uri = source_hint["cover_uri"]
            if artist and not artist_id:
                artist_id = f"artist:{artist.lower().strip()}"
            if artist and album and not album_id:
                album_id = f"album:{artist.lower().strip()}:{album.lower().strip()}"
            if artist and title and not query:
                query = f"{artist} {title}".strip()

        # Try to resolve targeted YouTube Music videoId
        resolved_video_id = None
        if source_hint and source_hint.get("resolved_video_id"):
            resolved_video_id = source_hint["resolved_video_id"]
            logger.info("Direct videoId provided in source_hint: %s", resolved_video_id)
        elif artist and title:
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
            if not query and artist and title:
                query = f"{artist} {title}".strip()
            elif not query and source_hint and source_hint.get("title"):
                query = source_hint.get("title")

            if not query or len(query) < 3 or query.startswith("trk_"):
                if artist and title:
                    query = f"{artist} {title}"
                else:
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
                    audio_final = None
                    if expected_file.exists():
                        audio_final = expected_file
                    else:
                        matches = list(DOWNLOADS_DIR.glob(f"{job_id}.*"))
                        non_thumb = [m for m in matches if m.suffix not in (".jpg", ".png", ".webp")]
                        if non_thumb:
                            audio_final = non_thumb[0]

                    if audio_final is not None and audio_final.exists():
                        # Auto-register in user's personal Cloud sync storage
                        _auto_register_in_sync_files(
                            user_id=job.user_id,
                            track_id=job.track_id,
                            audio_file=audio_final,
                            title=title,
                            artist_name=artist,
                            album_title=album,
                            duration_ms=duration_ms,
                            artist_id=artist_id,
                            album_id=album_id,
                            cover_uri=cover_uri,
                        )
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
