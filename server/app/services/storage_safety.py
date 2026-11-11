"""Single-process storage admission; never truncate an existing shared inode."""
from contextlib import contextmanager
from dataclasses import dataclass
from functools import lru_cache
import json
import os
from pathlib import Path
import shutil
import tempfile
import threading
import time
from collections import deque

from app.config import get_settings

STORAGE_LOCK = threading.RLock()
_BUDGETS_LOCK = threading.RLock()
MIB = 1024 * 1024


class StorageLimit(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code
        super().__init__(code)


@dataclass(eq=False)
class Reservation:
    owner: str
    remaining: int
    allocated: int = 0


class StorageBudget:
    def __init__(self, root, max_file, min_free, free_ratio, concurrent=2, clock=time.monotonic):
        self.root, self.max_file = Path(root), max_file
        self.min_free, self.free_ratio, self.concurrent = min_free, free_ratio, concurrent
        self.lock = threading.RLock()
        self.reservations = set()
        self.activity = {}
        self.clock = clock

    def check(self, extra=0):
        with self.lock:
            usage = shutil.disk_usage(self.root)
            floor = max(self.min_free, int(usage.total * self.free_ratio))
            if usage.free - sum(r.remaining for r in self.reservations) - extra < floor:
                raise StorageLimit(507, 'storage_headroom')

    def acquire(self, owner=None, size=None):
        with self.lock:
            if owner and sum(r.owner == owner for r in self.reservations) >= self.concurrent:
                raise StorageLimit(429, 'upload_concurrency')
            amount = 2 * self.max_file + MIB if size is None else size
            self.check(amount)
            token = Reservation(owner or '', amount)
            self.reservations.add(token)
            if owner:
                self._activity(owner)[0].append(self.clock())
            return token

    def allocated(self, token, total):
        with self.lock:
            token.remaining = max(0, token.remaining - max(0, total - token.allocated))
            token.allocated = max(token.allocated, total)

    def release(self, token):
        with self.lock:
            self.reservations.discard(token)

    def _activity(self, owner):
        now = self.clock()
        # Drop inactive identities and keep minute byte buckets, not one entry per chunk.
        for key in list(self.activity):
            if self.activity[key][2] < now - 86460:
                del self.activity[key]
        calls, buckets, _ = self.activity.setdefault(owner, (deque(), {}, now))
        while calls and calls[0] <= now - 600:
            calls.popleft()
        for minute in list(buckets):
            if minute < int((now - 86400) // 60):
                del buckets[minute]
        self.activity[owner] = calls, buckets, now
        return calls, buckets

    def received(self, owner, count):
        with self.lock:
            _, buckets = self._activity(owner)
            minute = int(self.clock() // 60)
            buckets[minute] = buckets.get(minute, 0) + count

    def warnings(self, owner):
        with self.lock:
            calls, buckets = self._activity(owner)
            return (["files_10m"] if len(calls) > 15 else []) + (
                ["bytes_24h"] if sum(buckets.values()) > 2 * 1024**3 else [])


@lru_cache(maxsize=32)
def _budget(root, max_file, min_free, ratio):
    return StorageBudget(root, max_file, min_free, ratio)


def budget(root):
    settings = get_settings()
    root = Path(root)
    if root.is_symlink() or root.is_junction():
        raise StorageLimit(503, 'unsafe_storage')
    root.mkdir(parents=True, exist_ok=True)
    with _BUDGETS_LOCK:
        return _budget(str(root.resolve()), settings.upload_max_file_bytes,
                       settings.upload_min_free_bytes, settings.upload_min_free_ratio)


@contextmanager
def reserve_copy(root, size):
    guard = budget(root)
    token = guard.acquire(size=size + MIB)
    try:
        yield guard, token
    finally:
        guard.release(token)


def stage_json(target, data):
    fd, name = tempfile.mkstemp(dir=target.parent, prefix='_metadata_')
    path = Path(name)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(data, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        return path
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def sync_directory(path):
    if os.name == 'posix':
        fd = os.open(path, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def replace_pair(audio_stage, audio, json_stage, metadata, budget_root=None):
    """Under STORAGE_LOCK. Roll back ordinary rename failure; not a two-file crash transaction."""
    backups = {}
    changed = []
    try:
        for target in (audio, metadata):
            if target.is_symlink() or target.is_junction():
                raise StorageLimit(503, 'unsafe_storage')
            if target.exists():
                fd, name = tempfile.mkstemp(dir=target.parent, prefix='_rollback_')
                os.close(fd)
                link = Path(name)
                link.unlink()
                backups[target] = link
                try:
                    os.link(target, link)
                except OSError:
                    # Preserve SEC-003's copy fallback, including bounded space for old large files.
                    with reserve_copy(budget_root or audio.parent, target.stat().st_size) as (guard, token):
                        with target.open('rb') as reader, link.open('wb') as writer:
                            while chunk := reader.read(MIB):
                                guard.check()
                                writer.write(chunk)
                                guard.allocated(token, writer.tell())
                            writer.flush()
                            os.fsync(writer.fileno())
        for staged, target in ((audio_stage, audio), (json_stage, metadata)):
            os.replace(staged, target)
            changed.append(target)
        for parent in {audio.parent, metadata.parent}:
            sync_directory(parent)
    except BaseException:
        for target in reversed(changed):
            if target in backups:
                os.replace(backups.pop(target), target)
            else:
                target.unlink(missing_ok=True)
        raise
    finally:
        for path in backups.values():
            path.unlink(missing_ok=True)
