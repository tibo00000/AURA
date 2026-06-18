import hashlib
import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile, status
from fastapi.responses import FileResponse

from app.config import get_settings
from app.core.auth import AuthenticatedUser, get_current_user
from app.schemas.responses import ResponseEnvelope

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/me/sync/files", tags=["sync-files"])


def _base_dir() -> Path:
    configured = Path(get_settings().sync_files_dir)
    return configured if configured.is_absolute() else Path.cwd() / configured


def _track_key(track_id: str) -> str:
    return hashlib.sha256(track_id.encode("utf-8")).hexdigest()


def _user_dir(user_id: str) -> Path:
    safe_user_id = hashlib.sha256(user_id.encode("utf-8")).hexdigest()
    path = _base_dir() / safe_user_id
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
    file: UploadFile = File(...),
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    target_file, metadata_file = _paths(current_user.id, track_id)
    uploaded_at = datetime.now(timezone.utc).isoformat()

    size = 0
    try:
        with target_file.open("wb") as output:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                output.write(chunk)
    except Exception as exc:
        logger.exception("Failed to store sync file for user=%s track=%s", current_user.id, track_id)
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    finally:
        await file.close()

    metadata = {
        "track_id": track_id,
        "synced": True,
        "size_bytes": size,
        "mime_type": file.content_type or "application/octet-stream",
        "uploaded_at": uploaded_at,
        "updated_at": uploaded_at,
    }
    metadata_file.write_text(json.dumps(metadata, ensure_ascii=False), encoding="utf-8")
    return ResponseEnvelope(data=metadata)


@router.get(
    "",
    response_model=ResponseEnvelope[dict[str, list[dict[str, Any]]]],
)
async def list_sync_files(
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    user_dir = _user_dir(current_user.id)
    items = []
    for metadata_path in user_dir.glob("*.json"):
        metadata = _read_metadata(metadata_path)
        if metadata is not None:
            items.append(metadata)

    items.sort(key=lambda item: item.get("uploaded_at") or "", reverse=True)
    return ResponseEnvelope(data={"items": items})


@router.get("/{track_id}")
async def download_sync_file(
    track_id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    target_file, metadata_file = _paths(current_user.id, track_id)
    metadata = _read_metadata(metadata_file)
    if metadata is None or not target_file.exists():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Synced audio file not found")

    return FileResponse(
        path=str(target_file),
        media_type=metadata.get("mime_type") or "audio/mpeg",
        filename=f"{track_id}.audio",
    )


@router.delete(
    "/{track_id}",
    response_model=ResponseEnvelope[dict[str, Any]],
)
async def delete_sync_file(
    track_id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    target_file, metadata_file = _paths(current_user.id, track_id)
    deleted = False
    for path in (target_file, metadata_file):
        if path.exists():
            path.unlink()
            deleted = True

    return ResponseEnvelope(data={"track_id": track_id, "deleted": deleted})
