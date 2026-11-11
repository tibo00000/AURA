"""Durable, bounded-field collaborative audit on the audio volume, not in access logs."""
from contextlib import contextmanager
from datetime import datetime, timezone
import os
from pathlib import Path
import sqlite3
import uuid

RUN_ID = uuid.uuid4().hex
REASONS = {'incorrect_audio', 'quality', 'wrong_version', 'other'}


def now():
    return datetime.now(timezone.utc).isoformat()


class ReplacementHistory:
    def __init__(self, root):
        self.root = Path(root) / '_audit'
        self.path = self.root / 'audio-replacements.sqlite3'

    @contextmanager
    def connection(self):
        if self.root.is_symlink() or self.root.is_junction() or self.path.is_symlink():
            raise OSError('Unsafe audit storage')
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd = os.open(self.path, os.O_CREAT | os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        os.close(fd)
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('PRAGMA synchronous=FULL')
            db.execute('''CREATE TABLE IF NOT EXISTS audio_replacements (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT NOT NULL UNIQUE, job_id TEXT NOT NULL,
                track_id TEXT NOT NULL, actor_id TEXT NOT NULL,
                operation TEXT NOT NULL DEFAULT 'shared_audio_replace',
                reason TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                old_sha256 TEXT, new_sha256 TEXT, status TEXT NOT NULL,
                run_id TEXT NOT NULL,
                CHECK (status IN ('pending','publishing','succeeded','failed','partial','cancelled','indeterminate'))
            )''')
            # Process-owned events only; never infer success from a hash or mutate download jobs.
            db.execute("UPDATE audio_replacements SET status='indeterminate', updated_at=? "
                       "WHERE run_id<>? AND status IN ('pending','publishing')", (now(), RUN_ID))
            db.commit()
            yield db
        finally:
            db.close()

    def begin(self, attempt, reason=None):
        with self.connection() as db:
            stamp = now()
            db.execute('''INSERT OR IGNORE INTO audio_replacements
                (event_id,job_id,track_id,actor_id,reason,created_at,updated_at,status,run_id)
                VALUES (?,?,?,?,?,?,?,'pending',?)''', (attempt.identity, attempt.job.id,
                attempt.job.track_id, attempt.job.user_id, reason if reason in REASONS else None,
                stamp, stamp, RUN_ID))
            db.commit()

    def prepare(self, attempt, old_sha256, new_sha256):
        with self.connection() as db:
            count = db.execute("UPDATE audio_replacements SET old_sha256=?,new_sha256=?,"
                "status='publishing',updated_at=? WHERE event_id=? AND actor_id=? AND status='pending'",
                (old_sha256, new_sha256, now(), attempt.identity, attempt.job.user_id)).rowcount
            if count != 1:
                raise RuntimeError('Audit intent unavailable')
            db.commit()

    def finish(self, attempt, status, *, pending_only=False):
        assert status in ('succeeded', 'failed', 'partial', 'cancelled')
        with self.connection() as db:
            db.execute("UPDATE audio_replacements SET status=?,updated_at=? WHERE event_id=? AND actor_id=? "
                       + ("AND status='pending'" if pending_only else "AND status IN ('pending','publishing')"),
                       (status, now(), attempt.identity, attempt.job.user_id))
            db.commit()

    def settle(self, attempt, cancelled):
        with self.connection() as db:
            db.execute("UPDATE audio_replacements SET status=CASE WHEN status='publishing' THEN 'indeterminate' "
                "ELSE ? END,updated_at=? WHERE event_id=? AND actor_id=? AND status IN ('pending','publishing')",
                ('cancelled' if cancelled else 'failed', now(), attempt.identity, attempt.job.user_id))
            db.commit()

    def list(self, *, track_id=None, before=None, limit=50):
        with self.connection() as db:
            sql = 'SELECT sequence,event_id,job_id,track_id,actor_id,operation,reason,created_at,updated_at,old_sha256,new_sha256,status FROM audio_replacements WHERE 1=1'
            values = []
            if track_id is not None:
                sql += ' AND track_id=?'; values.append(track_id)
            if before is not None:
                sql += ' AND sequence<?'; values.append(before)
            values.append(limit)
            return [dict(row) for row in db.execute(sql + ' ORDER BY sequence DESC LIMIT ?', values)]
