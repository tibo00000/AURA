"""SQLite online backup. Local file operation only; caller supplies private storage.

Does not instantiate ReplacementHistory or change the source's logical records.
For production use, stage the destination in a protected tmpfs and encrypt it
before persisting; authorization is required separately. Never copy only a WAL DB.
"""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys


def backup(source, destination):
    source=Path(source);destination=Path(destination)
    if not stat.S_ISREG(source.lstat().st_mode) or source.is_symlink():
        raise RuntimeError('Backup source must be a regular existing file')
    if not destination.parent.is_dir() or destination.parent.is_symlink():
        raise RuntimeError('A pre-created private destination directory is required')
    if stat.S_IMODE(destination.parent.stat().st_mode) & 0o077:
        raise RuntimeError('Backup destination directory must be operator-private')
    fd=os.open(destination,os.O_CREAT|os.O_EXCL|os.O_WRONLY|getattr(os,'O_NOFOLLOW',0),0o600)
    os.close(fd)
    started=datetime.now(timezone.utc).isoformat()
    try:
        with sqlite3.connect(source.resolve().as_uri()+'?mode=ro',uri=True,timeout=10) as src:
            with sqlite3.connect(destination,timeout=10) as dst:
                src.backup(dst,pages=128,sleep=.05)
                assert dst.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
                assert not dst.execute('PRAGMA foreign_key_check').fetchall()
                rows=dst.execute('SELECT * FROM audio_replacements ORDER BY sequence').fetchall()
                digest=hashlib.sha256(json.dumps(rows,separators=(',',':')).encode()).hexdigest()
        return dict(started_at=started,completed_at=datetime.now(timezone.utc).isoformat(),
            rows=len(rows),rows_sha256=digest,backup_sha256=hashlib.sha256(destination.read_bytes()).hexdigest(),
            integrity_check='ok',foreign_key_check='ok')
    except BaseException:
        # Only the destination we created; never touch the source or other files.
        destination.unlink(missing_ok=True)
        raise


if __name__=='__main__':
    if len(sys.argv)!=3:raise SystemExit('Usage: backup_history.py SOURCE PRIVATE_DESTINATION')
    print(json.dumps(backup(sys.argv[1],sys.argv[2]),sort_keys=True))
