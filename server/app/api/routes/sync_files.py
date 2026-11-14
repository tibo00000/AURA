import hashlib
import json
import logging
import os
import stat
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile, status
from fastapi.responses import FileResponse

from app.config import get_settings
from app.core.auth import AuthenticatedUser, get_current_user
from app.schemas.responses import ResponseEnvelope
from app.api.upload_admission import UploadAdmissionRoute
from app.services.download_lifecycle import blocking
from app.services.storage_safety import StorageLimit, replace_pair, stage_json

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/me/sync/files", tags=["sync-files"], route_class=UploadAdmissionRoute)


def _base_dir() -> Path:
    configured = Path(get_settings().sync_files_dir)
    return configured if configured.is_absolute() else Path.cwd() / configured


def _track_key(track_id: str) -> str:
    return hashlib.sha256(track_id.encode("utf-8")).hexdigest()


def _user_dir(user_id: str) -> Path:
    safe_user_id = hashlib.sha256(user_id.encode("utf-8")).hexdigest()
    path = _base_dir() / safe_user_id
    if path.is_symlink() or path.is_junction():
        raise HTTPException(503, 'Unsafe personal storage')
    path.mkdir(parents=True, exist_ok=True)
    return path


def _paths(user_id: str, track_id: str) -> tuple[Path, Path]:
    key = _track_key(track_id)
    return _user_dir(user_id) / f"{key}.audio", _user_dir(user_id) / f"{key}.json"


def _read_metadata(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        logger.warning("Ignoring invalid sync file metadata at %s", path)
        return None


@router.post(
    "/{track_id}",
    response_model=ResponseEnvelope[dict[str, Any]],
    status_code=status.HTTP_201_CREATED,
)
async def upload_sync_file(
    track_id: str,
    request: Request,
    file: UploadFile = File(...),
    title: str = Form(None),
    artist_name: str = Form(None),
    album_title: str = Form(None),
    duration_ms: int = Form(None),
    artist_id: str = Form(None),
    album_id: str = Form(None),
    cover_uri: str = Form(None),
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    if getattr(request.state, 'upload_owner', current_user.id) != current_user.id:
        raise HTTPException(401, 'Upload session changed')
    target_file, metadata_file = await blocking(_paths, current_user.id, track_id)
    uploaded_at = datetime.now(timezone.utc).isoformat()

    # Stage independently: replacing an upload must not modify a shared native inode.
    fd, temporary = await blocking(lambda: tempfile.mkstemp(dir=target_file.parent, prefix='_upload_'))
    staged = Path(temporary)
    size = 0
    try:
        with os.fdopen(fd, 'wb') as output:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > get_settings().upload_max_file_bytes:
                    raise StorageLimit(413, 'upload_file_too_large')
                reservation = getattr(request.state, 'upload_reservation', None)
                if reservation:
                    guard, token = reservation
                    await blocking(guard.check)
                await blocking(output.write, chunk)
                if reservation:
                    guard.allocated(token, token.allocated + len(chunk))
            await blocking(output.flush)
            await blocking(os.fsync, output.fileno())
    except BaseException as exc:
        staged.unlink(missing_ok=True)
        if not isinstance(exc, Exception):
            raise
        if isinstance(exc, StorageLimit):
            raise HTTPException(exc.status, exc.code) from None
        logger.warning('Personal upload staging failed')
        raise HTTPException(status_code=500, detail='Personal upload failed') from None
    finally:
        await file.close()

    metadata = {
        "track_id": track_id,
        "synced": True,
        "size_bytes": size,
        "mime_type": file.content_type or "application/octet-stream",
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
    from app.services.download_service import _STORAGE_LOCK
    staged_metadata = None
    def publish():
        nonlocal staged_metadata
        staged_metadata = stage_json(metadata_file, metadata)
        with _STORAGE_LOCK:
            replace_pair(staged, target_file, staged_metadata, metadata_file, _base_dir())
    try:
        await blocking(publish)
    finally:
        staged.unlink(missing_ok=True)
        if staged_metadata:
            staged_metadata.unlink(missing_ok=True)
    # Uploads remain personal, even when a client supplies a catalogue-shaped ID.

    return ResponseEnvelope(data=metadata)


@router.get(
    "",
    response_model=ResponseEnvelope[dict[str, list[dict[str, Any]]]],
)
async def list_sync_files(
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    items = await blocking(_list_playable_sync_files, current_user.id)
    return ResponseEnvelope(data={"items": items})


def _list_playable_sync_files(user_id: str) -> list[dict[str, Any]]:
    user_dir = _user_dir(user_id)
    items = []
    for metadata_path in user_dir.glob("*.json"):
        if metadata_path.is_symlink() or metadata_path.is_junction():
            continue
        metadata = _read_metadata(metadata_path)
        if not isinstance(metadata, dict) or not isinstance(metadata.get("track_id"), str):
            continue
        if metadata_path.stem != _track_key(metadata["track_id"]):
            continue
        try:
            audio = metadata_path.with_suffix(".audio").lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISREG(audio.st_mode) and audio.st_size > 0:
            items.append(metadata)

    items.sort(key=lambda item: item.get("uploaded_at") or "", reverse=True)
    return items


def find_playable_sync_file(user_id: str, track_id: str):
    from app.core.aura_id_codec import get_track_id_aliases
    from app.services.download_service import _find_globally_cached_track

    aliases = get_track_id_aliases(track_id)
    target_file: Path | None = None
    metadata: dict[str, Any] | None = None

    # 1. Vérification dans le répertoire personnel de l'utilisateur (track_id ou alias)
    for alias in aliases:
        cand_file, cand_meta = _paths(user_id, alias)
        try:
            info = cand_file.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISREG(info.st_mode) and info.st_size > 0:
            target_file = cand_file
            metadata = _read_metadata(cand_meta) or {}
            break

    # 2. Si absent du dossier personnel, vérification du Cache Global (_global_cache et backfill)
    # STREAMING ÉPHÉMÈRE : on sert directement le fichier audio du cache global
    # SANS créer de hardlink silencieux ni de fichier .json dans l'espace personnel de l'utilisateur (zéro pollution de bibliothèque / quota).
    if target_file is None:
        cached = _find_globally_cached_track(track_id)
        if cached:
            cached_audio, cached_meta = cached
            target_file = cached_audio
            metadata = cached_meta
    return target_file, metadata


@router.get("/{track_id}")
async def download_sync_file(
    track_id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    target_file, metadata = await blocking(find_playable_sync_file, current_user.id, track_id)
    if target_file is None or not target_file.exists():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Synced audio file not found")

    stat_res = target_file.stat()
    last_modified = datetime.fromtimestamp(stat_res.st_mtime, tz=timezone.utc).strftime("%a, %d %b %Y %H:%M:%S GMT")
    etag = f'"{hashlib.md5(f"{stat_res.st_mtime}-{stat_res.st_size}".encode()).hexdigest()}"'

    return FileResponse(
        path=str(target_file),
        media_type=(metadata.get("mime_type") if metadata else None) or "audio/mpeg",
        filename=f"{track_id}.audio",
        headers={
            "Accept-Ranges": "bytes",
            "ETag": etag,
            "Last-Modified": last_modified,
        },
    )



@router.put(
    "/{track_id}/metadata",
    response_model=ResponseEnvelope[dict[str, Any]],
)
async def update_sync_file_metadata(
    track_id: str,
    payload: dict[str, Any],
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    target_file, metadata_file = _paths(current_user.id, track_id)
    existing = _read_metadata(metadata_file) or {
        "track_id": track_id,
        "synced": True,
        "size_bytes": target_file.stat().st_size if target_file.exists() else 0,
        "mime_type": "audio/mpeg",
        "uploaded_at": datetime.now(timezone.utc).isoformat(),
    }

    now = datetime.now(timezone.utc).isoformat()
    if "title" in payload and payload["title"]:
        existing["title"] = payload["title"]
    if "artist_name" in payload and payload["artist_name"]:
        existing["artist_name"] = payload["artist_name"]
    if "album_title" in payload:
        existing["album_title"] = payload["album_title"]
    if "duration_ms" in payload and payload["duration_ms"]:
        existing["duration_ms"] = payload["duration_ms"]
    if "artist_id" in payload:
        existing["artist_id"] = payload["artist_id"]
    if "album_id" in payload:
        existing["album_id"] = payload["album_id"]
    if "cover_uri" in payload:
        existing["cover_uri"] = payload["cover_uri"]
    existing["updated_at"] = now

    metadata_file.write_text(json.dumps(existing, ensure_ascii=False), encoding="utf-8")
    return ResponseEnvelope(data=existing)


@router.post(
    "/batch-metadata",
    response_model=ResponseEnvelope[dict[str, Any]],
)
async def batch_update_sync_files_metadata(
    payload: list[dict[str, Any]],
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    now = datetime.now(timezone.utc).isoformat()
    updated_count = 0
    for item in payload:
        track_id = item.get("track_id")
        if not track_id:
            continue
        target_file, metadata_file = _paths(current_user.id, track_id)
        existing = _read_metadata(metadata_file) or {
            "track_id": track_id,
            "synced": True,
            "size_bytes": target_file.stat().st_size if target_file.exists() else 0,
            "mime_type": "audio/mpeg",
            "uploaded_at": now,
        }
        if item.get("title"):
            existing["title"] = item["title"]
        if item.get("artist_name"):
            existing["artist_name"] = item["artist_name"]
        if "album_title" in item:
            existing["album_title"] = item["album_title"]
        if item.get("duration_ms"):
            existing["duration_ms"] = item["duration_ms"]
        if "artist_id" in item:
            existing["artist_id"] = item["artist_id"]
        if "album_id" in item:
            existing["album_id"] = item["album_id"]
        if "cover_uri" in item:
            existing["cover_uri"] = item["cover_uri"]
        existing["updated_at"] = now

        metadata_file.write_text(json.dumps(existing, ensure_ascii=False), encoding="utf-8")
        updated_count += 1

    return ResponseEnvelope(data={"updated_count": updated_count})


@router.delete(
    "/{track_id}",
    response_model=ResponseEnvelope[dict[str, Any]],
)
async def delete_sync_file(
    track_id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    from app.core.aura_id_codec import get_track_id_aliases

    aliases = get_track_id_aliases(track_id)
    from app.services.download_service import _STORAGE_LOCK
    from app.services.download_lifecycle import blocking
    def remove_owned():
        deleted = False
        with _STORAGE_LOCK:
            for alias in aliases:
                target_file, metadata_file = _paths(current_user.id, alias)
                for path in (target_file, metadata_file):
                    if path.exists() or path.is_symlink():
                        try:
                            path.unlink()
                            deleted = True
                        except OSError as exc:
                            logger.warning("Failed to delete %s: %s", path, exc)
        return deleted
    deleted = await blocking(remove_owned)

    return ResponseEnvelope(data={"track_id": track_id, "deleted": deleted})
