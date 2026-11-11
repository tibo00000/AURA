"""Synthetic authorized native output, not an untrusted personal file promotion."""
from pathlib import Path
import shutil

from app.domain.models import DownloadJob
from app.services.download_lifecycle import DownloadAttempt
from app.services import download_service as module


def register_native(user_id, track_id, audio_file, **metadata):
    attempt = DownloadAttempt(DownloadJob(id='job_fixture', user_id=user_id,
        track_id=track_id, provider_name='youtube', status='running'))
    root = module.DOWNLOADS_DIR
    root.mkdir(parents=True, exist_ok=True)
    workspace = attempt.prepare_workspace(root)
    native = workspace / 'audio.mp3'
    shutil.copyfile(audio_file, native)
    try:
        return module._auto_register_in_sync_files(user_id, track_id, native,
                                                  **metadata, attempt=attempt)
    finally:
        attempt.cleanup(root)
