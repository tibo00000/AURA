"""Private attempt state and literal file targeting for the single-process worker."""
import asyncio
from dataclasses import dataclass, field
from pathlib import Path
import re
import shutil
import stat
import threading
import uuid

from app.domain.models import DownloadJob
from app.services.exceptions import BadRequest


def validate_job_id(job_id: str) -> str:
    # Historical API examples are shorter than ULIDs. Never normalize/decode an ID.
    if not isinstance(job_id, str) or not re.fullmatch(r'job_[A-Za-z0-9_-]{1,124}', job_id):
        raise BadRequest('Invalid download job identifier')
    return job_id


LEGACY_AUDIO_SUFFIXES = ('.mp3', '.m4a', '.webm', '.opus', '.ogg', '.wav', '.flac', '.aac', '.mp4')
LEGACY_SUFFIXES = (*LEGACY_AUDIO_SUFFIXES, '.part', '.ytdl', '.mp3.part',
                   '.m4a.part', '.webm.part', '.jpg', '.png', '.webp')


def regular_file(path: Path) -> bool:
    try:
        return stat.S_ISREG(path.lstat().st_mode)
    except FileNotFoundError:
        return False


def legacy_audio(root: Path, job_id: str) -> Path | None:
    validate_job_id(job_id)
    for suffix in LEGACY_AUDIO_SUFFIXES:
        path = root / (job_id + suffix)
        if regular_file(path) and path.stat().st_size:
            return path
    return None


def cleanup_legacy_files(root: Path, job_id: str) -> None:
    validate_job_id(job_id)
    for suffix in LEGACY_SUFFIXES:
        path = root / (job_id + suffix)
        # unlink removes one owned directory entry, never truncates a shared inode.
        if regular_file(path) or path.is_symlink():
            path.unlink(missing_ok=True)


@dataclass(eq=False)
class AdmissionIntent:
    job: DownloadJob
    previous: DownloadJob | None = None
    changes: dict | None = None
    source_hint: dict | None = None


@dataclass(eq=False)
class DownloadAttempt:
    job: DownloadJob
    identity: str = field(default_factory=lambda: uuid.uuid4().hex)
    cancelled: threading.Event = field(default_factory=threading.Event)
    task: asyncio.Task | None = None
    native_future: asyncio.Future | None = None
    workspace: Path | None = None
    progress_tasks: set = field(default_factory=set)
    finalizer: asyncio.Task | None = None

    def prepare_workspace(self, root: Path) -> Path:
        parent = root / '_attempts'
        parent.mkdir(mode=0o700, exist_ok=True)
        if parent.is_symlink() or not parent.is_dir() or parent.is_junction():
            raise BadRequest('Unsafe download workspace')
        path = parent / self.identity
        path.mkdir(mode=0o700, exist_ok=False)
        self.workspace = path
        return path

    def cleanup(self, root: Path) -> None:
        path = self.workspace
        if path is None:
            return
        if path != root / '_attempts' / self.identity or path.is_symlink() or path.is_junction():
            raise BadRequest('Unsafe download workspace')
        # rmtree does not traverse symlink entries; Linux uses its fd-based variant.
        shutil.rmtree(path)
        self.workspace = None


async def blocking(function, *args, on_cancel=None):
    """Do not release a lifecycle gate while its blocking operation still executes.

    Cancellation is cooperative: finish the in-flight authorized operation. Native
    downloads additionally receive their own stop event. No executor Future is cancelled.
    """
    future = asyncio.get_running_loop().run_in_executor(None, function, *args)
    return await drain(future, on_cancel=on_cancel)


async def drain(future, *, on_cancel=None):
    """Await the actual executor future even if its asyncio owner is cancelled."""
    while True:
        try:
            return await asyncio.shield(future)
        except asyncio.CancelledError:
            if on_cancel:
                on_cancel()
            if future.done():
                return future.result()


class TaskSupervisor:
    """Strong ownership, exception retrieval and repeated-cancellation-safe joins.

    Caller cancellation is delivered only after the supervised operation settles.
    The operation is never cancelled because its HTTP waiter went away.
    """
    def __init__(self):
        self.tasks = set()

    def start(self, coroutine):
        task = asyncio.create_task(coroutine)
        self.tasks.add(task)
        def finished(done):
            self.tasks.discard(done)
            if not done.cancelled():
                done.exception()
        task.add_done_callback(finished)
        return task

    async def run(self, coroutine):
        task = self.start(coroutine)
        cancelled = False
        def mark_cancelled():
            nonlocal cancelled
            cancelled = True
        result = await drain(task, on_cancel=mark_cancelled)
        if cancelled:
            raise asyncio.CancelledError()
        return result

    async def join(self):
        while self.tasks:
            await drain(asyncio.gather(*tuple(self.tasks), return_exceptions=True))
