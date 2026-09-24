from collections import OrderedDict
import hashlib
import json
import logging
from pathlib import Path
from typing import Optional, Tuple

from fastapi import APIRouter, HTTPException, Query, status
from fastapi.responses import FileResponse

from app.config import get_settings
from app.schemas.responses import AppVersionResponse, ResponseEnvelope

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/app", tags=["app-updates"])


def _get_updates_dir() -> Path:
    """Retourne le répertoire contenant les mises à jour et APKs/MSIs."""
    import os
    settings = get_settings()

    configured = Path(settings.updates_dir)
    if configured.is_absolute() and configured.exists():
        return configured

    # 1. Vérifier DOWNLOADS_DIR (défini dans docker-compose.vps.yml : /app/downloads)
    downloads_env = os.environ.get("DOWNLOADS_DIR")
    if downloads_env:
        cand = Path(downloads_env) / "updates"
        if cand.exists():
            return cand

    # 2. Vérifier chemin direct /app/downloads/updates (volume partagé VPS)
    if Path("/app/downloads/updates").exists():
        return Path("/app/downloads/updates")

    # 3. Vérifier sous sync_files_dir
    sync_base = Path(settings.sync_files_dir)
    sync_base_abs = sync_base if sync_base.is_absolute() else Path.cwd() / sync_base
    sync_updates = sync_base_abs / "updates"
    if sync_updates.exists():
        return sync_updates

    # 4. Vérifier Path.cwd() / "updates"
    if (Path.cwd() / "updates").exists():
        return Path.cwd() / "updates"

    # Fallback création
    if downloads_env:
        cand = Path(downloads_env) / "updates"
        cand.mkdir(parents=True, exist_ok=True)
        return cand

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


class BoundedLRUHashCache:
    """Cache LRU borné en mémoire pour les empreintes SHA-256 de binaires."""
    def __init__(self, maxsize: int = 50):
        self.maxsize = maxsize
        self._cache: OrderedDict[Tuple[str, int, int], str] = OrderedDict()

    def get_or_compute(self, file_path: Path) -> str:
        stat = file_path.stat()
        key = (str(file_path.resolve()), stat.st_mtime_ns, stat.st_size)

        if key in self._cache:
            self._cache.move_to_end(key)
            return self._cache[key]

        digest = _compute_sha256(file_path)

        if len(self._cache) >= self.maxsize:
            self._cache.popitem(last=False)

        self._cache[key] = digest
        return digest

    def clear(self):
        self._cache.clear()


_hash_cache = BoundedLRUHashCache(maxsize=50)


@router.get("/version", response_model=ResponseEnvelope[AppVersionResponse])
def get_app_version(
    platform: str = Query("android", pattern="^(android|desktop)$")
) -> ResponseEnvelope[AppVersionResponse]:
    """
    Retourne les informations de la dernière version disponible de l'application (Android ou Desktop).
    Lit le fichier version.json correspondant.
    Si le SHA-256 est omis ou défini sur 'auto', il est calculé/récupéré du cache LRU.
    """
    updates_dir = _get_updates_dir()

    if platform == "desktop":
        version_file = updates_dir / "desktop" / "version.json"
        if not version_file.exists():
            version_file = updates_dir / "version_desktop.json"
        default_version = AppVersionResponse(
            version_code=1,
            version_name="1.0.0",
            download_url="/app/updates/desktop/latest.msi",
            sha256="",
            release_notes="Aucune mise à jour disponible.",
            min_supported_version=1,
        )
    else:
        version_file = updates_dir / "version.json"
        default_version = AppVersionResponse(
            version_code=1,
            version_name="0.1.0",
            download_url="/app/updates/latest.apk",
            sha256="",
            release_notes="Aucune mise à jour disponible.",
            min_supported_version=1,
        )

    if not version_file.exists():
        return ResponseEnvelope(data=default_version)

    try:
        data = json.loads(version_file.read_text(encoding="utf-8"))
    except Exception as e:
        logger.error(f"Erreur lors de la lecture de {version_file.name} : {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Fichier de version corrompu ou illisible sur le serveur ({version_file.name})",
        )

    download_url = data.get("download_url", default_version.download_url)
    
    # Résolution du chemin relatif du binaire par rapport à updates_dir
    if download_url.startswith("/app/updates/"):
        rel_subpath = download_url[len("/app/updates/"):].lstrip("/")
        binary_path = updates_dir / rel_subpath
    else:
        binary_path = updates_dir / Path(download_url).name

    sha256_hash = data.get("sha256", "").strip()
    if (not sha256_hash or sha256_hash.lower() == "auto") and binary_path.exists() and binary_path.is_file():
        sha256_hash = _hash_cache.get_or_compute(binary_path)

    version_response = AppVersionResponse(
        version_code=int(data.get("version_code", default_version.version_code)),
        version_name=str(data.get("version_name", default_version.version_name)),
        download_url=download_url,
        sha256=sha256_hash,
        release_notes=data.get("release_notes"),
        min_supported_version=int(data.get("min_supported_version", default_version.min_supported_version)),
    )
    return ResponseEnvelope(data=version_response)


@router.get("/updates/{filepath:path}")
def download_update_file(filepath: str):
    """
    Sert le fichier binaire (APK, MSI, EXE, ZIP) d'une mise à jour avec le bon content-type.
    Protection stricte contre le path traversal.
    """
    updates_dir = _get_updates_dir().resolve()
    target_file = (updates_dir / filepath).resolve()

    if not target_file.is_relative_to(updates_dir) or not target_file.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Fichier d'installation introuvable sur le serveur",
        )

    ext = target_file.suffix.lower()
    media_type_map = {
        ".apk": "application/vnd.android.package-archive",
        ".msi": "application/x-msi",
        ".exe": "application/vnd.microsoft.portable-executable",
        ".zip": "application/zip",
    }
    media_type = media_type_map.get(ext, "application/octet-stream")

    return FileResponse(
        path=target_file,
        media_type=media_type,
        filename=target_file.name,
    )
