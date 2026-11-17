"""Read-only cache lookup; only authorized native publication creates receipts.

Private uploads and legacy cache entries are deliberately outside this namespace.
Receipts are server-owned, never accepted from a client or a personal metadata file.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
from app.services.storage_safety import STORAGE_LOCK, replace_pair, reserve_copy, stage_json

from app.core.aura_id_codec import get_track_id_aliases


def key(track_id):
    return hashlib.sha256(track_id.encode('utf-8')).hexdigest()


def directory(base: Path, *, create=False):
    parent, root = base / '_global_cache', base / '_global_cache' / '_native_v1'
    for path in (parent, root):
        if path.is_symlink() or path.is_junction():
            raise ValueError('Unsafe native cache directory')
        if create:
            path.mkdir(parents=True, exist_ok=True)
    return root


def regular(path):
    try:
        return stat.S_ISREG(path.lstat().st_mode)
    except OSError:
        return False


def read_entry(root: Path, name: str):
    audio, receipt = root / (name + '.audio'), root / (name + '.json')
    try:
        if not regular(audio) or not regular(receipt):
            return None
        data = json.loads(receipt.read_text(encoding='utf-8'))
        if (data.get('origin') != 'aura-native-attempt-v1'
                or key(data['track_id']) != name or not isinstance(data.get('metadata'), dict)):
            return None
        with audio.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != data.get('sha256') or audio.stat().st_size == 0:
            return None
        return audio, dict(data['metadata'])
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return None


def lookup(base: Path, track_id: str, *, aliases: list[str] | None = None):
    root = directory(base)
    for alias in get_track_id_aliases(track_id) if aliases is None else aliases:
        found = read_entry(root, key(alias))
        if found:
            return found
    return None


def entries(base: Path):
    root = directory(base)
    if root.is_dir():
        for receipt in root.glob('*.json'):
            found = read_entry(root, receipt.stem)
            if found:
                yield found


def publish(base: Path, track_id: str, source: Path, metadata: dict):
    root = directory(base, create=True)
    target = root / (key(track_id) + '.audio')
    receipt = target.with_suffix('.json')
    temporary = []
    with STORAGE_LOCK, reserve_copy(base, source.stat().st_size) as (guard, reservation):
        return _publish(root, target, receipt, track_id, source, metadata, temporary, guard, reservation)


def _publish(root, target, receipt, track_id, source, metadata, temporary, guard, reservation):
    try:
        flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_BINARY', 0)
        with os.fdopen(os.open(source, flags), 'rb') as reader:
            if not stat.S_ISREG(os.fstat(reader.fileno()).st_mode):
                raise ValueError('Invalid native source')
            fd, name = tempfile.mkstemp(dir=root, prefix='_publish_')
            temporary.append(Path(name))
            with os.fdopen(fd, 'wb') as writer:
                while chunk := reader.read(1024 * 1024):
                    guard.check()
                    writer.write(chunk)
                    guard.allocated(reservation, writer.tell())
                writer.flush()
                os.fsync(writer.fileno())
        with temporary[0].open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        data = dict(origin='aura-native-attempt-v1', track_id=track_id,
                    sha256=digest, metadata=metadata)
        temporary.append(stage_json(receipt, data))
        replace_pair(temporary[0], target, temporary[1], receipt, guard.root)
        return target
    finally:
        for path in temporary:
            path.unlink(missing_ok=True)
