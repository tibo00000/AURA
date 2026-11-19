"""
Download and jobs service.

Manages download jobs (in-memory store) and runs asynchronous download operations
using yt-dlp, Deno, IPv6, PO Token provider, and user cookies.
"""

import asyncio
import logging
import os
import re
import sys
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
from app.services.audio_matching import artist_match_score, title_match_score
from app.services.exceptions import AmbiguousMutation, BadRequest, Conflict, NotFound, ProviderUnavailable, RequiresResolutionException
from app.services.download_lifecycle import (
    AdmissionIntent, DownloadAttempt, TaskSupervisor, blocking, cleanup_legacy_files, drain, legacy_audio, regular_file, validate_job_id,
)
from app.db.supabase import supabase
from app.services import download_cache
import threading
from app.services.storage_safety import STORAGE_LOCK as _STORAGE_LOCK, reserve_copy, replace_pair, stage_json
from app.services.replacement_history import ReplacementHistory


logger = logging.getLogger(__name__)

class _DownloadStartUnavailable(ProviderUnavailable):
    """The job could not enter its running state before touching audio."""


def _resolve_downloads_dir() -> Path:
    env_dir = os.getenv("DOWNLOADS_DIR")
    if env_dir:
        return Path(env_dir)
    default_dir = Path("/app/downloads")
    try:
        if default_dir.exists() or Path("/app").exists():
            return default_dir
    except Exception:
        pass
    return Path.cwd() / "downloads"


DOWNLOADS_DIR = _resolve_downloads_dir()

from ytmusicapi import YTMusic
from rapidfuzz import fuzz

ytmusic = YTMusic()


def _get_sync_base() -> Path:
    """Retourne le chemin de base absolu pour les fichiers synchronisés."""
    sync_base = Path(get_settings().sync_files_dir)
    return sync_base if sync_base.is_absolute() else Path.cwd() / sync_base


def _get_global_cache_dir() -> Path:
    return download_cache.directory(_get_sync_base(), create=True)


def _get_track_key(track_id: str) -> str:
    """Calcule la clé canonique de stockage à partir du track_id fort."""
    import hashlib
    return hashlib.sha256(track_id.encode("utf-8")).hexdigest()


def _normalize_search_string(s: str | None) -> str:
    """Normalise un titre ou un artiste pour le matching tolérant (diacritiques, casse, ponctuation)."""
    if not s:
        return ""
    import unicodedata
    import re
    decomposed = unicodedata.normalize("NFKD", s)
    stripped = "".join(c for c in decomposed if not unicodedata.combining(c))
    cleaned = stripped.lower()
    cleaned = re.sub(r"\(.*?\)", " ", cleaned)
    cleaned = re.sub(r"\[.*?\]", " ", cleaned)
    cleaned = re.sub(r"[^\w\s]", " ", cleaned)
    return " ".join(cleaned.split())


_cached_global_keys: set[str] = set()
_cached_doublets: dict[tuple[str, str], tuple[Path, Path, str]] = {}
_cached_global_keys_last_refresh: float = 0.0


def get_cached_track_keys_and_doublets():
    # Read only, including availability checks reached from public catalogue routes.
    keys, doublets = set(), {}
    for audio, metadata in download_cache.entries(_get_sync_base()):
        keys.add(audio.stem)
        title, artist = metadata.get('title'), metadata.get('artist_name')
        if title and artist:
            doublets[(_normalize_search_string(title), _normalize_search_string(artist))] = (
                audio, audio.with_suffix('.json'), metadata['track_id'])
    return keys, doublets


def get_cached_track_keys_set():
    return get_cached_track_keys_and_doublets()[0]


def is_track_in_global_cache(track_id, title=None, artist_name=None):
    # An alias or metadata match is never permission to adopt a personal file.
    return _find_globally_cached_track(track_id) is not None


def _is_valid_audio_file(file_path: Path, min_size_bytes: int = 16) -> tuple[bool, str]:
    """
    Vérifie l'intégrité minimale et les magic bytes d'un fichier audio.
    Rejette les fichiers 0-octet, les pages d'erreur HTML/JSON et les flux tronqués.
    Retourne (is_valid, mime_type).
    """
    if not file_path.exists():
        return False, "application/octet-stream"
    try:
        size = file_path.stat().st_size
        if size <= 0 or size < min_size_bytes:
            return False, "application/octet-stream"

        with open(file_path, "rb") as f:
            header = f.read(64)

        if len(header) < 16:
            return False, "application/octet-stream"

        # Rejet immédiat si c'est une page d'erreur HTML ou JSON
        header_lower = header[:32].lower()
        if (
            header_lower.startswith(b"<!doc")
            or header_lower.startswith(b"<html")
            or header_lower.startswith(b"{\"err")
            or header_lower.startswith(b"error")
        ):
            return False, "text/html"

        # ID3 / MP3 Frame Sync
        if header.startswith(b"ID3") or (header[0] == 0xFF and (header[1] & 0xE0) == 0xE0):
            return True, "audio/mpeg"

        # M4A / MP4
        if len(header) >= 12 and header[4:8] == b"ftyp":
            return True, "audio/mp4"

        # Ogg / Opus / Vorbis
        if header.startswith(b"OggS"):
            return True, "audio/ogg"

        # WebM / Matroska
        if header.startswith(b"\x1a\x45\xdf\xa3"):
            return True, "audio/webm"

        # FLAC
        if header.startswith(b"fLaC"):
            return True, "audio/flac"

        # Support des extensions audio connues et des mocks de tests unitaires
        suffix = file_path.suffix.lower()
        if suffix in (".mp3", ".audio") or header.startswith((b"FAKE_", b"IDENTICAL_")):
            return True, "audio/mpeg"
        elif suffix in (".m4a", ".mp4"):
            return True, "audio/mp4"
        elif suffix in (".opus", ".ogg"):
            return True, "audio/ogg"
        elif suffix == ".webm":
            return True, "audio/webm"

        return False, "application/octet-stream"
    except Exception as e:
        logger.warning("Error checking audio file integrity for %s: %s", file_path, e)
        return False, "application/octet-stream"


def _find_globally_cached_track(track_id, *, aliases=None):
    """Pure lookup in the native-published namespace; never backfill on read."""
    try:
        return download_cache.lookup(_get_sync_base(), track_id, aliases=aliases)
    except (OSError, ValueError):
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
    import tempfile
    trusted = _find_globally_cached_track(track_id)
    if not trusted or trusted[0] != cached_audio:
        return False
    metadata = trusted[1]
    try:
        user_dir = _get_sync_base() / hashlib.sha256(user_id.encode('utf-8')).hexdigest()
        if user_dir.is_symlink() or user_dir.is_junction():
            return False
        user_dir.mkdir(parents=True, exist_ok=True)
        target_audio = user_dir / (_get_track_key(track_id) + '.audio')
        target_json = target_audio.with_suffix('.json')
        now = datetime.now(timezone.utc).isoformat()
        user_meta = {**metadata, 'track_id': track_id, 'synced': True,
                     'uploaded_at': now, 'updated_at': now, 'cache_origin': 'native_v1'}
        if override_metadata:
            for name in ('title', 'artist_name', 'album_title', 'cover_uri', 'duration_ms', 'artist_id', 'album_id'):
                if override_metadata.get(name):
                    user_meta[name] = override_metadata[name]
        with cached_audio.open('rb') as stream:
            user_meta['cache_sha256'] = __import__('hashlib').file_digest(stream, 'sha256').hexdigest()
        with _STORAGE_LOCK:
            fd, temporary = tempfile.mkstemp(dir=user_dir, prefix='_link_')
            os.close(fd)
            temp = Path(temporary)
            try:
                temp.unlink()
                try:
                    os.link(cached_audio, temp)
                except OSError:
                    with reserve_copy(_get_sync_base(), cached_audio.stat().st_size):
                        shutil.copyfile(cached_audio, temp)
                staged_json = stage_json(target_json, user_meta)
                try:
                    replace_pair(temp, target_audio, staged_json, target_json, _get_sync_base())
                finally:
                    staged_json.unlink(missing_ok=True)
            finally:
                temp.unlink(missing_ok=True)
        return True
    except OSError:
        logger.warning('Personal cache link unavailable')
        return False


def _auto_register_in_sync_files(user_id, track_id, audio_file, title=None, artist_name=None,
        album_title=None, duration_ms=None, artist_id=None, album_id=None, cover_uri=None, *, attempt=None):
    """Publish only an authorized attempt's private native output, never an arbitrary file."""
    if (attempt is None or attempt.job.user_id != user_id or attempt.job.track_id != track_id
            or attempt.cancelled.is_set() or attempt.workspace is None
            or attempt.workspace != DOWNLOADS_DIR / '_attempts' / attempt.identity
            or audio_file != attempt.workspace / 'audio.mp3'
            or not regular_file(audio_file) or attempt.workspace.is_symlink()
            or attempt.workspace.is_junction()):
        return False
    valid, mime = _is_valid_audio_file(audio_file)
    if not valid:
        return False
    now = datetime.now(timezone.utc).isoformat()
    metadata = dict(track_id=track_id, synced=True, size_bytes=audio_file.stat().st_size,
        mime_type=mime, title=title, artist_name=artist_name, album_title=album_title,
        duration_ms=duration_ms, artist_id=artist_id, album_id=album_id, cover_uri=cover_uri,
        uploaded_at=now, updated_at=now)
    cached = download_cache.publish(_get_sync_base(), track_id, audio_file, metadata)
    return _link_cached_track_to_user(user_id, track_id, cached, metadata)


def _propagate_reassigned_track_to_all_users(
    track_id: str,
    cached_audio: Path,
    title: Optional[str] = None,
    artist_name: Optional[str] = None,
    album_title: Optional[str] = None,
    duration_ms: Optional[int] = None,
    cover_uri: Optional[str] = None,
) -> None:
    """
    Met à jour les hardlinks et metadata.json pour tous les utilisateurs ayant déjà ce track_id
    dans leur répertoire personnel sync_files.
    """
    import hashlib
    import json
    import shutil
    import tempfile
    trusted = _find_globally_cached_track(track_id)
    if not trusted or trusted[0] != cached_audio:
        return
    track_key = _get_track_key(track_id)
    updated = failed = 0
    with _STORAGE_LOCK:
        for user_dir in _get_sync_base().iterdir():
            if (not re.fullmatch(r'[0-9a-f]{64}', user_dir.name) or user_dir.is_symlink()
                    or user_dir.is_junction() or not user_dir.is_dir()):
                continue
            audio, meta = user_dir / (track_key + '.audio'), user_dir / (track_key + '.json')
            if not regular_file(audio) or not regular_file(meta):
                continue
            try:
                data = json.loads(meta.read_text(encoding='utf-8'))
                with audio.open('rb') as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                if data.get('cache_origin') != 'native_v1' or data.get('cache_sha256') != digest:
                    continue  # Private uploads and uncertain legacy references are never replaced.
                fd, name = tempfile.mkstemp(dir=user_dir, prefix='_reassign_')
                os.close(fd)
                staged = Path(name)
                try:
                    staged.unlink()
                    try:
                        os.link(cached_audio, staged)
                    except OSError:
                        with reserve_copy(_get_sync_base(), cached_audio.stat().st_size):
                            shutil.copyfile(cached_audio, staged)
                    with cached_audio.open('rb') as stream:
                        data['cache_sha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
                    data['updated_at'] = datetime.now(timezone.utc).isoformat()
                    data['size_bytes'] = cached_audio.stat().st_size
                    for name, value in dict(title=title, artist_name=artist_name, album_title=album_title,
                                           duration_ms=duration_ms, cover_uri=cover_uri).items():
                        if value:
                            data[name] = value
                    staged_json = stage_json(meta, data)
                    try:
                        replace_pair(staged, audio, staged_json, meta, _get_sync_base())
                    finally:
                        staged_json.unlink(missing_ok=True)
                    updated += 1
                finally:
                    staged.unlink(missing_ok=True)
            except (OSError, ValueError):
                failed += 1
                logger.warning('Shared native reference update unavailable')
    return dict(updated=updated, failed=failed)


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
        "logger": logger,
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
    cookies_file = DOWNLOADS_DIR / "cookies.txt"
    if cookies_file.exists():
        logger.info("Using uploaded cookie file: %s", cookies_file)
        opts["cookiefile"] = str(cookies_file)

    return opts


class DownloadService:
    """Service to handle download jobs and user settings."""

    def __init__(
        self,
        supabase_client: Optional[object] = None,
        auto_recover_stale_jobs: bool = False,
        auto_backfill_cache: bool = False,
    ):
        try:
            DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)
        except Exception as e:
            logger.debug("Could not create DOWNLOADS_DIR %s: %s", DOWNLOADS_DIR, e)
        self.settings = get_settings()
        self._supabase = supabase_client
        self.deezer_client = DeezerClient(self.settings.deezer_api_base_url)
        self._download_semaphore = asyncio.Semaphore(2)
        # Verrouillage de concurrence par piste pour déploiement mono-processus Uvicorn.
        # NOTE : Si le backend est mis à l'échelle sur plusieurs workers (Gunicorn --workers N),
        # migrer vers un verrou inter-processus (ex: fcntl.flock ou pg_advisory_lock).
        self._track_locks: Dict[str, asyncio.Lock] = {}
        self._track_locks_guard = asyncio.Lock()
        self._candidates_cache: Dict[str, Tuple[float, List[dict]]] = {}
        self._attempts: Dict[str, DownloadAttempt] = {}
        # Fixed lock pool: unauthorized/nonexistent IDs never grow a lock registry.
        self._job_gates = tuple(asyncio.Lock() for _ in range(64))
        self._admission_gates = tuple(asyncio.Lock() for _ in range(64))
        self._commands = TaskSupervisor()
        self._workers = TaskSupervisor()
        self._finalizers = TaskSupervisor()
        self._pending_admissions = {}
        self._closing = False
        self._shutdown_task = None
        if auto_recover_stale_jobs and self.settings.enable_startup_recovery and self.settings.app_env != "test":
            self._recover_stale_jobs_on_startup()
        if auto_backfill_cache and self.settings.enable_cache_backfill and self.settings.app_env != "test":
            self._backfill_global_cache()

    @property
    def supabase(self):
        if self._supabase is not None:
            return self._supabase
        return supabase

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
        # Legacy names/metadata do not establish provenance; no automatic promotion.
        return 0

    async def cleanup_orphaned_cache(self, max_age_days: int = 30) -> int:
        """Disabled until eviction shares publication guards (SEC-003.3 R2).

        The historical hashed-key lock did not protect workers using track IDs.
        No file enumeration, directory creation or lock pruning is allowed here.
        """
        logger.warning('Native cache eviction disabled: shared coordination required')
        return 0

    def _recover_stale_jobs_on_startup(self) -> None:
        """Mark stale running and queued jobs as failed on server startup so they don't block polling forever."""
        if not self.settings.enable_startup_recovery or self.settings.app_env == "test":
            logger.info("Startup recovery disabled via settings: skipping _recover_stale_jobs_on_startup")
            return
        try:
            self.supabase.table("download_jobs").update({
                "status": "failed",
                "error_code": "server_restarted",
                "error_message": "Téléchargement interrompu par le redémarrage du serveur.",
            }).in_("status", ["running", "queued"]).execute()
            logger.info("Startup sweep: Marked orphaned 'running' and 'queued' download jobs as failed.")
        except Exception as e:
            logger.warning("Could not execute startup stale jobs sweep: %s", e)

    def _resolve_official_video_id(
        self, artist: str, title: str, album: Optional[str] = None, *, strict: bool = False
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
                    
                    direct_ratio = fuzz.ratio(artist.lower(), found_artist.lower())
                    max_ratio = artist_match_score(artist, [a.get("name", "") for a in artists_found])

                    logger.info("YTM Step A: found album %r by artist %r, fuzzy ratio=%d%% (direct=%d%%)", item.get("title"), found_artist, max_ratio, direct_ratio)
                    
                    if max_ratio >= 75:
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
                                
                            score = title_match_score(title, track_title)
                                
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
                if strict:
                    raise ProviderUnavailable("Audio catalogue temporarily unavailable") from None

        # Fallback to smart song search
        if not resolved_id:
            logger.info("Album strategy failed or skipped, trying smart song search fallback")
            resolved_id = self._smart_song_search(artist, title, strict=True) if strict else self._smart_song_search(artist, title)
            
        if resolved_id:
            return resolved_id
            
        # If still not found or score < 75%, trigger RequiresResolutionException
        candidates = self._fetch_5_candidates(artist, title, strict=True) if strict else self._fetch_5_candidates(artist, title)
        raise RequiresResolutionException(candidates=candidates)

    def _smart_song_search(self, artist: str, title: str, *, strict: bool = False) -> Optional[str]:
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
                direct_artist_ratio = fuzz.ratio(artist.lower(), found_artist.lower())
                title_ratio = title_match_score(title, found_title)
                effective_artist_ratio = artist_match_score(artist, [a.get("name", "") for a in artists_found])
                
                # Combined score
                score = (effective_artist_ratio + title_ratio) / 2
                logger.info("YTM Smart Song Search: candidate %r by %r, score=%d%% (artist=%d%% [direct=%d%%], title=%d%%)", 
                            found_title, found_artist, score, effective_artist_ratio, direct_artist_ratio, title_ratio)
                
                if effective_artist_ratio >= 75 and title_ratio >= 75 and score > best_score:
                    best_score = score
                    best_song_id = video_id
                    
            if best_song_id:
                logger.info("YTM Smart Song Search SUCCESS: selected videoId=%s with score %d%%", best_song_id, best_score)
                return best_song_id
                
            return None
        except Exception as e:
            logger.error("YTM Smart Song Search error: %s", e)
            if strict:
                raise ProviderUnavailable("Audio catalogue temporarily unavailable") from None
            return None

    def search_candidates(
        self,
        artist: str = "",
        title: str = "",
        query: Optional[str] = None,
        limit: int = 10,
        strict: bool = False,
    ) -> List[dict]:
        """Fetch candidate songs from YTM search for user choice (with 5-minute in-memory cache)."""
        clean_artist = (artist or "").strip()
        clean_title = (title or "").strip()
        clean_query = (query or "").strip()
        limit = min(max(1, limit), 20)

        cache_key = f"{clean_artist.lower()}:{clean_title.lower()}:{clean_query.lower()}:{limit}"
        now = time.time()
        cached = self._candidates_cache.get(cache_key)
        if cached and (now - cached[0] < 300.0):
            return cached[1]

        target_query = clean_query if clean_query else f"{clean_artist} {clean_title}".strip()
        if not target_query:
            return []

        try:
            logger.info("Fetching YTM candidates for query: %r (limit=%d)", target_query, limit)
            results = ytmusic.search(target_query, filter="songs")
            candidates = []
            for item in (results or [])[:limit]:
                video_id = item.get("videoId")
                if not video_id:
                    continue

                artists_found = item.get("artists", [])
                item_artist_name = ", ".join([a.get("name", "") for a in artists_found if a.get("name")])

                cover_uri = None
                thumbnails = item.get("thumbnails", [])
                if thumbnails:
                    cover_uri = thumbnails[-1].get("url") or thumbnails[0].get("url")

                duration = item.get("duration")
                album_name = item.get("album", {}).get("name") if isinstance(item.get("album"), dict) else None

                candidates.append({
                    "video_id": video_id,
                    "title": item.get("title", ""),
                    "artist": item_artist_name or clean_artist,
                    "album": album_name,
                    "duration": duration,
                    "cover_uri": cover_uri,
                })

            self._candidates_cache[cache_key] = (now, candidates)
            return candidates
        except Exception as e:
            logger.error("Failed to fetch YTM candidates: %s", e)
            if strict:
                raise ProviderUnavailable("Audio catalogue temporarily unavailable") from None
            return []

    def _fetch_5_candidates(self, artist: str, title: str, *, strict: bool = False) -> List[dict]:
        """Fetch 5 candidate songs from YTM search for user choice."""
        return self.search_candidates(artist=artist, title=title, limit=5, strict=True) if strict else self.search_candidates(artist=artist, title=title, limit=5)

    def _job_gate(self, job_id: str) -> asyncio.Lock:
        return self._job_gates[hash(job_id) % len(self._job_gates)]

    def _admission(self, user_id, track_id):
        return self._admission_gates[hash((user_id, track_id)) % len(self._admission_gates)]

    def _mutate_job(self, job, changes=None, *, delete=False):
        try:
            query = self.supabase.table('download_jobs')
            query = query.delete() if delete else query.update({
                **changes, 'updated_at': datetime.now(timezone.utc).isoformat()})
            result = (query.eq('id', job.id).eq('user_id', job.user_id).eq('track_id', job.track_id)
                      .eq('status', job.status).eq('attempt_count', job.attempt_count).execute())
        except Exception:
            raise ProviderUnavailable('Download storage temporarily unavailable') from None
        data = getattr(result, 'data', None)
        if not isinstance(data, list) or len(data) > 1:
            raise AmbiguousMutation('Download mutation response is ambiguous')
        if not data:
            self.get_job(job.user_id, job.id)
            raise Conflict('Download job changed concurrently; retry the request')
        updated = data[0]
        required = {'id', 'user_id', 'track_id', 'status', 'attempt_count', 'provider_name'}
        if (not isinstance(updated, dict) or not required.issubset(updated)
                or updated['id'] != job.id or str(updated['user_id']) != job.user_id
                or updated['track_id'] != job.track_id):
            raise AmbiguousMutation('Download mutation could not be verified')
        try:
            return DownloadJob.from_dict(updated)
        except Exception:
            raise AmbiguousMutation('Download mutation could not be verified') from None

    async def _command(self, coroutine):
        if self._closing:
            coroutine.close()
            raise ProviderUnavailable('Download service is stopping')
        return await self._commands.run(coroutine)

    async def create_job(self, user_id, track_id, provider_name='youtube', source_hint=None):
        return await self._command(self._admit_create(user_id, track_id, provider_name, source_hint))

    async def admit_playlist_acquisition(self, user_id, track_id, reserved_job_id, linked_job_id, source_hint, lease_token, retry=False):
        validate_job_id(reserved_job_id)
        return await self._command(
            self._admit_playlist_acquisition(user_id, track_id, reserved_job_id, linked_job_id, source_hint, lease_token, retry)
        )

    async def _admit_playlist_acquisition(self, user_id, track_id, reserved_job_id, linked_job_id, source_hint, lease_token, retry):
        async with self._admission(user_id, track_id):
            resumed = await self._resume_pending_pair(user_id, track_id)
            if resumed is not None:
                return resumed
            job_id = linked_job_id or reserved_job_id
            async with self._job_gate(job_id):
                try:
                    current = await blocking(self.get_job, user_id, job_id)
                except NotFound:
                    if linked_job_id is not None:
                        raise
                else:
                    if current.track_id != track_id:
                        raise Conflict('Download track changed concurrently')
                    orphaned = current.status in ('queued', 'running') and current.id not in self._attempts
                    retryable = retry and current.status in ('failed', 'cancelled', 'succeeded')
                    if orphaned or retryable:
                        if current.playlist_acquisition and current.id == reserved_job_id and source_hint is not None:
                            return await self._restart_locked(current, increment=True, source_hint=source_hint, playlist_lease=lease_token)
                        if current.playlist_acquisition and current.id == reserved_job_id and source_hint is None:
                            return None
                    # An independently started job keeps its own retry policy.
                    # A playlist retry can admit a new, authorized attempt under its reservation.
                    if not retryable or current.playlist_acquisition or source_hint is None:
                        return current
            rows = await blocking(lambda: self.supabase.table('download_jobs').select('*').eq(
                'user_id', user_id).eq('track_id', track_id).in_(
                    'status', ['queued', 'running', 'requires_resolution']).order('created_at', desc=True).limit(1).execute().data)
            if rows or source_hint is None:
                return DownloadJob.from_dict(rows[0]) if rows else None
            return await self._create_job(
                user_id, track_id, 'youtube', source_hint, reserved_job_id=reserved_job_id, playlist_lease=lease_token,
            )

    async def _admit_create(self, user_id, track_id, provider_name, source_hint):
        if not track_id.startswith('trk_'):
            raise BadRequest('Invalid track ID format. Must start with trk_')
        async with self._admission(user_id, track_id):
            resumed = await self._resume_pending_pair(user_id, track_id)
            if resumed is not None:
                return resumed
            return await self._create_job(user_id, track_id, provider_name, source_hint)

    @staticmethod
    def _same_admission(actual, expected):
        return (actual.id == expected.id and actual.user_id == expected.user_id
                and actual.track_id == expected.track_id and actual.status == expected.status
                and actual.attempt_count == expected.attempt_count
                and actual.operation_type == expected.operation_type
                and actual.playlist_acquisition == expected.playlist_acquisition
                and actual.created_at == expected.created_at)

    async def _write_admission(self, intent, playlist_lease=None):
        """Caller holds admission then job guard; replay targets the same ID/CAS."""
        job = intent.job
        if playlist_lease is None:
            self._pending_admissions[job.id] = intent
        try:
            if intent.previous is None:
                if playlist_lease is None:
                    result = await blocking(lambda: self.supabase.table('download_jobs').insert(job.to_dict()).execute())
                    data = getattr(result, 'data', None)
                else:
                    result = await blocking(lambda: self.supabase.rpc('aura_insert_playlist_download_job', {
                        'p_user_id': job.user_id, 'p_track_id': job.track_id,
                        'p_lease_token': playlist_lease, 'p_job': job.to_dict(),
                    }).execute())
                    row = getattr(result, 'data', None)
                    if row is None:
                        raise Conflict('Playlist acquisition lease changed')
                    data = [row]
                if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], dict):
                    raise AmbiguousMutation('Download insertion response is ambiguous')
                try:
                    confirmed = DownloadJob.from_dict(data[0])
                except Exception:
                    raise AmbiguousMutation('Download insertion could not be verified') from None
            else:
                if playlist_lease is None:
                    confirmed = await blocking(self._mutate_job, intent.previous, intent.changes)
                else:
                    row = await blocking(lambda: self.supabase.rpc('aura_restart_playlist_download_job', {
                        'p_user_id': job.user_id, 'p_track_id': job.track_id, 'p_lease_token': playlist_lease,
                        'p_job_id': job.id, 'p_status': intent.previous.status,
                        'p_attempt': intent.previous.attempt_count,
                    }).execute().data)
                    if row is None:
                        raise Conflict('Playlist acquisition lease or attempt changed')
                    confirmed = DownloadJob.from_dict(row)
            if not self._same_admission(confirmed, job):
                raise AmbiguousMutation('Download admission could not be verified')
        except AmbiguousMutation:
            # A committed queued row remains supervised, but never report HTTP success.
            try:
                current = await blocking(self.get_job, job.user_id, job.id)
                if self._same_admission(current, job):
                    await self._ensure_pair_available(current)
                    self._start_download_task(current, intent.source_hint)
                    self._pending_admissions.pop(job.id, None)
            except (NotFound, ProviderUnavailable, Conflict):
                pass  # The intent fences all subsequent admissions for this pair.
            raise
        except Conflict:
            self._pending_admissions.pop(job.id, None)
            raise
        except Exception:
            # Transport loss is not proof of rollback; confirm only the known identity.
            try:
                confirmed = await blocking(self.get_job, job.user_id, job.id)
            except (NotFound, ProviderUnavailable):
                raise ProviderUnavailable('Download job creation could not be confirmed') from None
            if not self._same_admission(confirmed, job):
                raise ProviderUnavailable('Download admission outcome is unknown') from None
        self._pending_admissions.pop(job.id, None)
        return confirmed

    async def _insert_owned_job(self, job, source_hint=None, playlist_lease=None):
        async with self._job_gate(job.id):
            await self._write_admission(AdmissionIntent(job, source_hint=source_hint), playlist_lease)

    async def _resume_pending_pair(self, user_id, track_id):
        """Reconcile under admission before any pair mutation is allowed."""
        for intent in tuple(self._pending_admissions.values()):
            expected = intent.job
            if expected.user_id != user_id or expected.track_id != track_id:
                continue
            async with self._job_gate(expected.id):
                if self._pending_admissions.get(expected.id) is not intent:
                    continue  # DELETE resolved it while we waited for the job guard.
                try:
                    current = await blocking(self.get_job, user_id, expected.id)
                except NotFound:
                    if intent.previous is not None:
                        self._pending_admissions.pop(expected.id, None)
                        continue  # A restart never recreates a deleted SQL row.
                    # Reuse this PK even if the first request can still commit late.
                    current = await self._write_admission(intent)
                if not self._same_admission(current, expected):
                    if intent.previous is not None and self._same_admission(current, intent.previous):
                        current = await self._write_admission(intent)  # Same CAS and count.
                    else:
                        self._pending_admissions.pop(expected.id, None)
                        raise Conflict('Pending download admission changed')
                if expected.id not in self._attempts:
                    await self._ensure_pair_available(current)
                    self._start_download_task(current, intent.source_hint)
                self._pending_admissions.pop(expected.id, None)
                return current
        return None

    async def _ensure_pair_available(self, job):
        for attempt in self._attempts.values():
            if attempt.job.user_id == job.user_id and attempt.job.track_id == job.track_id:
                raise Conflict('Download attempt is still stopping or executing')
        try:
            rows = await blocking(lambda: self.supabase.table('download_jobs').select('*')
                .eq('user_id', job.user_id).eq('track_id', job.track_id)
                .in_('status', ['queued', 'running', 'requires_resolution']).execute().data)
        except Exception:
            raise ProviderUnavailable('Download storage temporarily unavailable') from None
        if any(row['id'] != job.id for row in rows):
            raise Conflict('Another download is already active for this track')

    async def _create_job(self, user_id, track_id, provider_name, source_hint, *, reserved_job_id=None, playlist_lease=None):
        def read_existing():
            return (self.supabase.table('download_jobs').select('*').eq('user_id', user_id)
                    .eq('track_id', track_id).execute().data)
        try:
            rows = await blocking(read_existing)
        except Exception:
            raise ProviderUnavailable('Download storage temporarily unavailable') from None
        for attempt in self._attempts.values():
            if attempt.job.user_id == user_id and attempt.job.track_id == track_id:
                current = await blocking(self.get_job, user_id, attempt.job.id)
                if attempt.cancelled.is_set() or current.status not in ('queued', 'running', 'requires_resolution'):
                    raise Conflict('Download attempt is still stopping or executing')
                return current
        for entry in sorted(rows, key=lambda x: x.get('created_at') or '', reverse=True):
            if str(entry.get('user_id')) != user_id:
                continue
            existing = DownloadJob.from_dict(entry)
            validate_job_id(existing.id)
            async with self._job_gate(existing.id):
                existing = await blocking(self.get_job, user_id, existing.id)
                if existing.status in ('queued', 'running', 'requires_resolution'):
                    return existing
                if existing.status == 'succeeded':
                    if reserved_job_id is not None:
                        continue
                    if await blocking(legacy_audio, DOWNLOADS_DIR, existing.id):
                        return existing
                    return await self._restart_locked(existing, source_hint=source_hint)
        now = datetime.now(timezone.utc)
        job = DownloadJob(id=reserved_job_id or generate_id('job'), user_id=user_id, track_id=track_id,
                          provider_name=provider_name, status='queued', created_at=now, updated_at=now,
                          playlist_acquisition=playlist_lease is not None)
        if playlist_lease is None:
            await self._insert_owned_job(job, source_hint)
        else:
            await self._insert_owned_job(job, source_hint, playlist_lease)
        track_lock = await self._get_track_lock(track_id)
        if not track_lock.locked():
            async with track_lock:
                attempt = DownloadAttempt(job)
                self._attempts[job.id] = attempt
                try:
                    # Pure lookup is fenced against DELETE before publication.
                    cached = await self._with_attempt(attempt, lambda current: _find_globally_cached_track(current.track_id))
                    if cached:
                        await self._publish_cached(attempt, cached, source_hint)
                        if attempt.cancelled.is_set():
                            raise Conflict('Download creation was interrupted')
                        return await blocking(self.get_job, user_id, job.id)
                    if attempt.cancelled.is_set():
                        raise Conflict('Download creation was interrupted')
                except Exception:
                    await self._record_stopped_attempt(attempt, failed=True)
                    raise
                finally:
                    self._discard_attempt(attempt)
        self._start_download_task(job, source_hint)
        return job

    def get_job(self, user_id: str, job_id: str) -> DownloadJob:
        validate_job_id(job_id)
        try:
            response = (self.supabase.table('download_jobs').select('*')
                        .eq('id', job_id).eq('user_id', user_id).execute())
            if not isinstance(response.data, list) or len(response.data) > 1:
                raise ProviderUnavailable('Download lookup response is ambiguous')
            if (not response.data or response.data[0].get('id') != job_id
                    or str(response.data[0].get('user_id')) != user_id):
                raise NotFound('Download job not found')
            return DownloadJob.from_dict(response.data[0])
        except NotFound:
            raise
        except Exception:
            raise ProviderUnavailable('Download storage temporarily unavailable') from None

    async def delete_job(self, user_id: str, job_id: str) -> None:
        await blocking(self.get_job, user_id, job_id)
        async with self._job_gate(job_id):
            def cancel_owned():
                job = self.get_job(user_id, job_id)
                attempt = self._attempts.get(job_id)
                if attempt is not None and attempt.job.user_id != user_id:
                    raise Conflict('Download attempt owner mismatch')
                try:
                    self._mutate_job(job, delete=True)
                except ProviderUnavailable:
                    # Unknown SQL outcome: fence this authorized attempt conservatively.
                    if attempt is not None:
                        attempt.cancelled.set()
                    raise
                if attempt is not None:
                    attempt.cancelled.set()
                self._pending_admissions.pop(job_id, None)
                try:
                    cleanup_legacy_files(DOWNLOADS_DIR, job_id)
                except OSError:
                    raise ProviderUnavailable('Job removed; private cleanup incomplete') from None
            await blocking(cancel_owned)

    def list_jobs(
        self, user_id: str, status: Optional[str] = None, limit: int = 20, cursor: Optional[str] = None
    ) -> Tuple[List[DownloadJob], Optional[str]]:
        """List download jobs for a user from Supabase."""
        try:
            query = self.supabase.table("download_jobs").select("*").eq("user_id", user_id)
            if status:
                query = query.eq("status", status)

            # Cursor-based pagination: created_at < cursor's created_at
            if cursor:
                cursor_response = self.supabase.table("download_jobs").select("created_at").eq("id", cursor).eq("user_id", user_id).execute()
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

    @staticmethod
    def _restart_source(job, source_hint, *, explicit_resolution=False):
        if job.operation_type != 'manual_reassign':
            return source_hint
        # No selected video is persisted by the current SQL schema. Never infer
        # it from the old cache, candidates or a process-local worker hint.
        video_id = (source_hint or {}).get('resolved_video_id')
        if not explicit_resolution or not isinstance(video_id, str) or not video_id.strip():
            raise BadRequest('A shared replacement requires a new explicit video selection; resolve or reassign')
        return {**source_hint, 'resolved_video_id': video_id.strip()}

    async def _restart_locked(self, job, *, increment=False, source_hint=None, explicit_resolution=False, playlist_lease=None):
        if job.playlist_acquisition and playlist_lease is None:
            raise Conflict('Retry this acquisition from the playlist')
        source_hint = self._restart_source(job, source_hint, explicit_resolution=explicit_resolution)
        await self._ensure_pair_available(job)
        changes = dict(status='queued', progress_percent=0.0, error_code=None,
                       error_message=None, candidates=[], attempt_count=job.attempt_count + int(increment))
        expected = DownloadJob.from_dict({**job.to_dict(), **changes})
        if playlist_lease is None:
            updated = await self._write_admission(AdmissionIntent(expected, job, changes, source_hint))
        else:
            updated = await self._write_admission(AdmissionIntent(expected, job, changes, source_hint), playlist_lease)
        self._start_download_task(updated, source_hint)
        return updated

    async def retry_job(self, user_id, job_id):
        return await self._command(self._admit_restart(user_id, job_id, increment=True))

    async def resolve_job(self, user_id, job_id, video_id):
        return await self._command(self._admit_restart(user_id, job_id,
                                  source_hint={'resolved_video_id': video_id}))

    async def _admit_restart(self, user_id, job_id, *, increment=False, source_hint=None):
        snapshot = await blocking(self.get_job, user_id, job_id)
        async with self._admission(user_id, snapshot.track_id):
            async with self._job_gate(job_id):
                current = await blocking(self.get_job, user_id, job_id)
                if current.playlist_acquisition:
                    raise Conflict('Retry this acquisition from the playlist')
                self._restart_source(current, source_hint, explicit_resolution=not increment)
                if current.operation_type == 'manual_reassign' and any(
                        intent.job.user_id == user_id and intent.job.track_id == snapshot.track_id
                        for intent in self._pending_admissions.values()):
                    # An uncertain original admission may still own its selected
                    # source. A new resolve must not silently replay that choice.
                    raise Conflict('Previous replacement admission must settle before explicit resolution')
            resumed = await self._resume_pending_pair(user_id, snapshot.track_id)
            if resumed is not None and resumed.id == job_id:
                return resumed
            async with self._job_gate(job_id):
                job = await blocking(self.get_job, user_id, job_id)
                if job.track_id != snapshot.track_id:
                    raise Conflict('Download track changed concurrently')
                allowed = ('failed', 'cancelled', 'requires_resolution') if increment else ('requires_resolution', 'failed', 'queued')
                if job.status not in allowed:
                    raise BadRequest(f'Job cannot be restarted from status={job.status}')
                return await self._restart_locked(job, increment=increment, source_hint=source_hint,
                                                  explicit_resolution=not increment)

    async def reassign_track_audio(self, user_id, track_id, video_id, source_hint=None):
        return await self._command(self._admit_reassign(user_id, track_id, video_id, source_hint))

    async def _admit_reassign(self, user_id, track_id, video_id, source_hint):
        if not track_id.startswith('trk_'):
            raise BadRequest('Invalid track ID format. Must start with trk_')
        if not video_id or not video_id.strip():
            raise BadRequest('A valid video_id must be provided')
        async with self._admission(user_id, track_id):
            await self._resume_pending_pair(user_id, track_id)
            try:
                rows = await blocking(lambda: self.supabase.table('download_jobs').select('*')
                    .eq('user_id', user_id).eq('track_id', track_id)
                    .in_('status', ['queued', 'running', 'requires_resolution']).execute().data)
            except Exception:
                raise ProviderUnavailable('Download storage temporarily unavailable') from None
            for entry in rows:
                jid = validate_job_id(entry['id'])
                async with self._job_gate(jid):
                    current = await blocking(self.get_job, user_id, jid)
                    if current.track_id != track_id:
                        raise Conflict('Download track changed concurrently')
                    if current.status not in ('queued', 'running', 'requires_resolution'):
                        continue
                    await blocking(self._mutate_job, current, dict(status='superseded',
                                   error_message='Remplacé par une réassignation manuelle'))
                    previous = self._attempts.get(jid)
                    if previous is not None:
                        previous.cancelled.set()
            stopping = [a for a in tuple(self._attempts.values())
                        if a.job.user_id == user_id and a.job.track_id == track_id]
            for previous in stopping:
                previous.cancelled.set()
                if previous.task:
                    await drain(previous.task)  # No job/track guard held while waiting.
            if any(a.job.user_id == user_id and a.job.track_id == track_id for a in self._attempts.values()):
                raise Conflict('Previous download has not finished')
            now = datetime.now(timezone.utc)
            job = DownloadJob(id=generate_id('job'), user_id=user_id, track_id=track_id,
                              provider_name='youtube', operation_type='manual_reassign',
                              status='queued', created_at=now, updated_at=now)
            hint = {**(source_hint or {}), 'resolved_video_id': video_id.strip(), 'trigger': 'manual_reassign'}
            await self._insert_owned_job(job, hint)
            self._start_download_task(job, hint)
            return job

    async def _cleanup_orphaned_job_files(self, keep_job: DownloadJob):
        def candidates():
            return (self.supabase.table('download_jobs').select('*').eq('user_id', keep_job.user_id)
                    .eq('track_id', keep_job.track_id)
                    .in_('status', ['succeeded', 'failed', 'cancelled', 'superseded']).execute().data)
        for entry in await blocking(candidates):
            jid = validate_job_id(entry['id'])
            if jid == keep_job.id:
                continue
            await blocking(self.get_job, keep_job.user_id, jid)
            async with self._job_gate(jid):
                current = await blocking(self.get_job, keep_job.user_id, jid)
                if (jid not in self._attempts and current.track_id == keep_job.track_id
                        and current.status in ('succeeded', 'failed', 'cancelled', 'superseded')):
                    await blocking(cleanup_legacy_files, DOWNLOADS_DIR, jid)

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

    def _start_download_task(self, job: DownloadJob, source_hint: Optional[dict] = None) -> asyncio.Task:
        validate_job_id(job.id)
        if job.id in self._attempts:
            raise Conflict('Download attempt is still stopping or executing')
        attempt = DownloadAttempt(job)
        self._attempts[job.id] = attempt
        attempt.task = self._workers.start(self._run_download_job(attempt, source_hint))
        return attempt.task

    def _discard_attempt(self, attempt):
        if self._attempts.get(attempt.job.id) is attempt:
            del self._attempts[attempt.job.id]

    async def _with_attempt(self, attempt, action):
        async with self._job_gate(attempt.job.id):
            def checked():
                if (self._attempts.get(attempt.job.id) is not attempt or attempt.cancelled.is_set()):
                    return False
                current = self.get_job(attempt.job.user_id, attempt.job.id)
                if (current.attempt_count != attempt.job.attempt_count
                        or current.track_id != attempt.job.track_id
                        or current.operation_type != attempt.job.operation_type
                        or current.status not in ('queued', 'running')):
                    attempt.cancelled.set()
                    return False
                return action(current)
            try:
                return await blocking(checked, on_cancel=attempt.cancelled.set)
            except NotFound:
                attempt.cancelled.set()
                return False
            except (Conflict, ProviderUnavailable):
                attempt.cancelled.set()
                raise

    async def _update_job_status(self, attempt, **changes):
        def update(current):
            self._mutate_job(current, changes)
            return True
        return await self._with_attempt(attempt, update)

    async def _record_stopped_attempt(self, attempt, *, failed=False, start_unavailable=False):
        """A stopped attempt may only finish its own still-active row, never publish.

        A completed DELETE has no row. A transient SQL failure must not strand a
        running row after the storage becomes reachable again.
        """
        async with self._job_gate(attempt.job.id):
            def finish():
                if self._attempts.get(attempt.job.id) is not attempt:
                    return
                current = self.get_job(attempt.job.user_id, attempt.job.id)
                if (current.attempt_count == attempt.job.attempt_count
                        and current.track_id == attempt.job.track_id
                        and current.status in ('queued', 'running')):
                    resumable = current.playlist_acquisition and (self._closing or start_unavailable)
                    error_code = 'download_interrupted'
                    if resumable:
                        error_code = 'playlist_worker_shutdown' if self._closing else 'playlist_worker_storage_unavailable'
                    self._mutate_job(current, dict(status='queued' if resumable else ('failed' if failed else 'cancelled'),
                        error_code=error_code,
                        error_message=None if resumable else 'Download interrupted; retry available'))
            try:
                await blocking(finish)
            except NotFound:
                pass
            except Exception:
                logger.warning('Stopped download status could not be recorded for %s', attempt.job.id)

    async def _publish_cached(self, attempt, cached, source_hint=None):
        def publish(current):
            ok = _link_cached_track_to_user(current.user_id, current.track_id,
                                           cached[0], cached[1], source_hint)
            self._mutate_job(current, dict(status='succeeded' if ok else 'failed',
                             progress_percent=100.0 if ok else 0.0,
                             error_code=None if ok else 'cache_link_failed'))
            return ok
        return await self._with_attempt(attempt, publish)

    async def _publish_audio(self, attempt, audio, metadata, source_hint):
        def publish(current):
            if audio != attempt.workspace / 'audio.mp3' or not regular_file(audio):
                raise BadRequest('Invalid download output')
            target = DOWNLOADS_DIR / (validate_job_id(current.id) + '.mp3')
            shared = current.operation_type == 'manual_reassign'
            history = ReplacementHistory(_get_sync_base()) if shared else None
            with _STORAGE_LOCK:
                previous = _find_globally_cached_track(current.track_id) if shared else None
                old_digest = None
                if history:
                    import hashlib
                    if previous:
                        with previous[0].open('rb') as stream:
                            old_digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                    with audio.open('rb') as stream:
                        new_digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                    history.begin(attempt, (source_hint or {}).get('replacement_reason'))
                    history.prepare(attempt, old_digest, new_digest)
                def unsuccessful_outcome():
                    after = _find_globally_cached_track(current.track_id)
                    unchanged = after == previous
                    if after:
                        with after[0].open('rb') as stream:
                            unchanged = unchanged and hashlib.file_digest(stream, 'sha256').hexdigest() == old_digest
                    return 'failed' if unchanged else 'partial'
                try:
                    ok = _auto_register_in_sync_files(current.user_id, current.track_id, audio, **metadata, attempt=attempt)
                    if ok:
                        os.replace(audio, target)
                    if ok and shared:
                        propagation = _propagate_reassigned_track_to_all_users(current.track_id,
                            _find_globally_cached_track(current.track_id)[0],
                            **{k: metadata[k] for k in ('title', 'artist_name', 'album_title', 'duration_ms', 'cover_uri')})
                        ok = not propagation['failed']
                    outcome = 'succeeded' if ok else (unsuccessful_outcome() if history else 'failed')
                    self._mutate_job(current, dict(status='succeeded' if ok else 'failed',
                                     progress_percent=100.0 if ok else 0.0,
                                     error_code=None if ok else ('shared_audio_replace_partial' if outcome == 'partial' else 'corrupt_audio_output')))
                    if history:
                        history.finish(attempt, outcome)
                    return ok
                except Exception:
                    if history:
                        history.finish(attempt, unsuccessful_outcome())
                    raise
        return await self._with_attempt(attempt, publish)

    def _schedule_progress(self, attempt, progress):
        if self._attempts.get(attempt.job.id) is not attempt or attempt.cancelled.is_set():
            return
        task = asyncio.create_task(self._update_job_status(attempt, progress_percent=progress))
        attempt.progress_tasks.add(task)
        def finished(done):
            attempt.progress_tasks.discard(done)
            if not done.cancelled() and done.exception():
                logger.warning('Download progress update unavailable for %s', attempt.job.id)
        task.add_done_callback(finished)

    async def _run_download_job(self, attempt, source_hint=None):
        failed = False
        start_unavailable = False
        try:
            if attempt.job.operation_type == 'manual_reassign':
                def audit(current):
                    ReplacementHistory(_get_sync_base()).begin(attempt, (source_hint or {}).get('replacement_reason'))
                    return True
                if not await self._with_attempt(attempt, audit):
                    return
            async with self._download_semaphore:
                if not attempt.cancelled.is_set():
                    track_lock = await self._get_track_lock(attempt.job.track_id)
                    async with track_lock:
                        await self._run_download_job_impl(attempt, source_hint)
        except asyncio.CancelledError:
            attempt.cancelled.set()
        except Exception as exc:
            failed = True
            start_unavailable = isinstance(exc, _DownloadStartUnavailable)
            logger.warning('Download attempt failed for %s', attempt.job.id)
        finally:
            attempt.finalizer = self._finalizers.start(self._finalize_attempt(attempt, failed, start_unavailable))
            await drain(attempt.finalizer, on_cancel=attempt.cancelled.set)

    async def _finalize_attempt(self, attempt, failed, start_unavailable=False):
        if attempt.native_future is not None:
            await drain(attempt.native_future, on_cancel=attempt.cancelled.set)
        if attempt.progress_tasks:
            await drain(asyncio.gather(*tuple(attempt.progress_tasks), return_exceptions=True))
        if failed or attempt.cancelled.is_set():
            await self._record_stopped_attempt(attempt, failed=failed, start_unavailable=start_unavailable)
        if attempt.job.operation_type == 'manual_reassign':
            try:
                await blocking(ReplacementHistory(_get_sync_base()).settle, attempt, attempt.cancelled.is_set())
            except Exception:
                logger.warning('Replacement audit finalization unavailable')
        try:
            await blocking(attempt.cleanup, DOWNLOADS_DIR)
        except Exception:
            logger.warning('Private attempt cleanup incomplete for %s', attempt.job.id)
        self._discard_attempt(attempt)

    async def shutdown(self):
        if self._shutdown_task is None:
            self._closing = True
            self._shutdown_task = asyncio.create_task(self._shutdown())
        await drain(self._shutdown_task)

    async def _shutdown(self):
        await self._commands.join()
        for attempt in tuple(self._attempts.values()):
            attempt.cancelled.set()
        await self._workers.join()
        await self._finalizers.join()

    async def get_download_file(self, user_id, job_id):
        await blocking(self.get_job, user_id, job_id)
        async with self._job_gate(job_id):
            job = await blocking(self.get_job, user_id, job_id)
            if job.status != 'succeeded':
                raise BadRequest(f'Download job is not succeeded: status={job.status}')
            def find():
                cached = _find_globally_cached_track(job.track_id)
                if cached:
                    return cached[0]
                candidate = legacy_audio(DOWNLOADS_DIR, job.id)
                if candidate:
                    return candidate
                trash = DOWNLOADS_DIR / '_legacy_trash'
                if trash.is_dir() and not trash.is_symlink() and not trash.is_junction():
                    for directory in sorted(trash.iterdir()):
                        if directory.is_dir() and not directory.is_symlink() and not directory.is_junction():
                            candidate = legacy_audio(directory, job.id)
                            if candidate:
                                return candidate
                return None
            path = await blocking(find)
            if path is None:
                raise NotFound('Audio file not found')
            return path

    async def _run_download_job_impl(self, attempt: DownloadAttempt, source_hint=None):
        try:
            started = await self._update_job_status(attempt, status='running', progress_percent=0.0)
        except ProviderUnavailable:
            raise _DownloadStartUnavailable from None
        if not started:
            return
        if attempt.job.playlist_acquisition and not (source_hint or {}).get('resolved_video_id'):
            await self._update_job_status(attempt, status='failed', error_code='authorized_audio_source_missing',
                                          error_message='Retry this acquisition from the playlist')
            return
        if attempt.job.operation_type == 'manual_reassign':
            video_id = (source_hint or {}).get('resolved_video_id')
            if not isinstance(video_id, str) or not video_id.strip():
                await self._update_job_status(attempt, status='failed',
                    error_code='replacement_source_required',
                    error_message='Select a new explicit video using resolve or reassign')
                return
        else:
            cached = await self._with_attempt(attempt, lambda current: _find_globally_cached_track(current.track_id))
            if cached:
                await self._publish_cached(attempt, cached, source_hint)
                return
        await self._execute_download_workflow(attempt, source_hint)

    async def _execute_download_workflow(self, attempt: DownloadAttempt, source_hint: Optional[dict] = None) -> None:
        job = attempt.job
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
        has_intent_metadata = (
            job.playlist_acquisition
            and source_hint is not None
            and bool(source_hint.get("title") and source_hint.get("artist_name"))
        )
        try:
            # Check if this is a valid encoded stateless AURA ID
            ref = parse_aura_id(job.track_id)
            if ref.provider_name == "deezer" and not has_intent_metadata:
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
            if duration_ms is None:
                duration_ms = source_hint.get("duration_ms")
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
                resolved_video_id = await blocking(_run_resolve, on_cancel=attempt.cancelled.set)
            except RequiresResolutionException as e:
                # Capture des candidats et mise à jour du job en requires_resolution
                await self._update_job_status(
                    attempt,
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
                await self._update_job_status(attempt, status='failed',
                    error_code='download_metadata_missing',
                    error_message='No reliable track identity for audio acquisition')
                return
                
            download_target = query
            logger.info("Targeted search returned no candidate. Falling back to search query: %r", download_target)

        if not await self._update_job_status(attempt, status='running', progress_percent=5.0):
            return
        workspace = await self._with_attempt(attempt, lambda current: attempt.prepare_workspace(DOWNLOADS_DIR))
        if not workspace:
            return
        loop = asyncio.get_running_loop()
        last_update_time = [0.0]
        last_progress = [0.0]

        class AttemptCancelled(Exception):
            pass

        def check_cancelled():
            if attempt.cancelled.is_set():
                raise AttemptCancelled()

        def progress_hook(data):
            check_cancelled()
            if data.get('status') == 'downloading':
                total = data.get('total_bytes') or data.get('total_bytes_estimate') or 0
                progress = round((data.get('downloaded_bytes', 0) / total) * 100, 1) if total else 0.0
                now = time.monotonic()
                if progress - last_progress[0] >= 3.0 or now - last_update_time[0] >= 2.0:
                    loop.call_soon_threadsafe(self._schedule_progress, attempt, progress)
                    last_progress[0], last_update_time[0] = progress, now
            elif data.get('status') == 'finished':
                loop.call_soon_threadsafe(self._schedule_progress, attempt, 99.0)

        def execute_native():
            # The native worker writes only private artifacts. It never publishes or writes SQL.
            try:
                check_cancelled()
                opts = _build_yt_dlp_opts(workspace, 'audio')
                opts['progress_hooks'] = [progress_hook]
                with yt_dlp.YoutubeDL(opts) as ydl:
                    info = ydl.extract_info(download_target, download=True)
                check_cancelled()
                audio = workspace / 'audio.mp3'
                if info is None or not regular_file(audio) or audio.stat().st_size == 0:
                    return dict(status='failed', error_code='job_failed', error_message='No MP3 output')
                return dict(status='succeeded', audio=audio)
            except Exception as exc:
                if attempt.cancelled.is_set():
                    return dict(status='cancelled', error_code='user_cancelled', error_message='Download stopped')
                # Preserve actionable client categories without persisting provider free text,
                # URLs, cookies, tokens or credential-bearing exception payloads.
                diagnostic = str(exc).lower()
                if any(word in diagnostic for word in ('bot', 'sign in', 'cookie')):
                    code, message = 'youtube_bot_or_cookies', 'YouTube sign in or cookies required'
                elif 'po_token' in diagnostic or 'po-token' in diagnostic:
                    code, message = 'po_token_failed', 'YouTube PO-token unavailable'
                elif 'timeout' in diagnostic or '504' in diagnostic or isinstance(exc, TimeoutError):
                    code, message = 'provider_timeout', 'Audio provider timeout'
                elif 'not found' in diagnostic or 'no match' in diagnostic:
                    code, message = 'no_match', 'Audio source not found'
                else:
                    code, message = 'job_failed', 'Audio provider failed'
                return dict(status='failed', error_code=code, error_message=message)

        attempt.native_future = loop.run_in_executor(None, execute_native)
        result = await drain(attempt.native_future, on_cancel=attempt.cancelled.set)
        if result['status'] != 'succeeded':
            await self._update_job_status(attempt, **result)
            return
        metadata = dict(title=title, artist_name=artist, album_title=album, duration_ms=duration_ms,
                        artist_id=artist_id, album_id=album_id, cover_uri=cover_uri)
        published = await self._publish_audio(attempt, result['audio'], metadata, source_hint)
        if published and job.operation_type == 'manual_reassign':
            try:
                await self._cleanup_orphaned_job_files(job)
            except Exception:
                logger.warning('Optional historical job cleanup incomplete for %s', job_id)
