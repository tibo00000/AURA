import hashlib
import json
import logging
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, status
from fastapi.responses import FileResponse

from app.config import get_settings
from app.schemas.responses import AppVersionResponse, ResponseEnvelope

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/app", tags=["app-updates"])


def _get_updates_dir() -> Path:
    """Retourne le répertoire contenant les mises à jour et APKs."""
    settings = get_settings()
    configured = Path(settings.updates_dir)
    if configured.is_absolute():
        configured.mkdir(parents=True, exist_ok=True)
        return configured

    sync_base = Path(settings.sync_files_dir)
    sync_base_abs = sync_base if sync_base.is_absolute() else Path.cwd() / sync_base
    sync_updates = sync_base_abs / "updates"
    if sync_updates.exists():
        return sync_updates

    target = Path.cwd() / configured
    target.mkdir(parents=True, exist_ok=True)
    return target


def _compute_sha256(file_path: Path) -> str:
    """Calcule le hash SHA-256 d'un fichier par blocs de 64KB."""
    sha256 = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            sha256.update(chunk)
    return sha256.hexdigest()


@router.get("/version", response_model=ResponseEnvelope[AppVersionResponse])
def get_app_version() -> ResponseEnvelope[AppVersionResponse]:
    """
    Retourne les informations de la dernière version disponible de l'application Android.
    Lit le fichier version.json présent dans le dossier d'updates.
    Si le SHA-256 est omis ou défini sur 'auto', il est calculé automatiquement à partir de l'APK.
    """
    updates_dir = _get_updates_dir()
    version_file = updates_dir / "version.json"

    if not version_file.exists():
        # Fallback si aucune version n'est encore publiée
        default_version = AppVersionResponse(
            version_code=1,
            version_name="0.1.0",
            download_url="/app/updates/latest.apk",
            sha256="",
            release_notes="Aucune mise à jour disponible.",
            min_supported_version=1,
        )
        return ResponseEnvelope(data=default_version)

    try:
        data = json.loads(version_file.read_text(encoding="utf-8"))
    except Exception as e:
        logger.error(f"Erreur lors de la lecture de version.json : {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Fichier de version corrompu ou illisible sur le serveur",
        )

    download_url = data.get("download_url", "/app/updates/latest.apk")
    apk_filename = Path(download_url).name if download_url else "latest.apk"
    apk_path = updates_dir / apk_filename

    sha256_hash = data.get("sha256", "").strip()
    if (not sha256_hash or sha256_hash.lower() == "auto") and apk_path.exists():
        sha256_hash = _compute_sha256(apk_path)

    version_response = AppVersionResponse(
        version_code=int(data.get("version_code", 1)),
        version_name=str(data.get("version_name", "0.1.0")),
        download_url=download_url,
        sha256=sha256_hash,
        release_notes=data.get("release_notes"),
        min_supported_version=int(data.get("min_supported_version", 1)),
    )
    return ResponseEnvelope(data=version_response)


@router.get("/updates/{filename}")
def download_update_apk(filename: str):
    """
    Sert le fichier binaire APK d'une mise à jour avec le content-type officiel Android.
    Protection stricte contre le path traversal.
    """
    updates_dir = _get_updates_dir().resolve()
    target_file = (updates_dir / filename).resolve()

    if not target_file.is_relative_to(updates_dir) or not target_file.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Fichier d'installation APK introuvable sur le serveur",
        )

    return FileResponse(
        path=target_file,
        media_type="application/vnd.android.package-archive",
        filename=filename,
    )
