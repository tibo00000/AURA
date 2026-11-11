"""Synthetic persistent-volume and WAL backup probes, no network or credentials."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys

from app.domain.models import DownloadJob
from app.services.download_lifecycle import DownloadAttempt
from app.services.replacement_history import ReplacementHistory
from backup_history import backup

root=Path(os.environ['SYNC_FILES_DIR'])
assert root==Path('/audio/sync')
history=ReplacementHistory(root)
if sys.argv[1]=='create':
    assert not history.path.exists()
    attempt=DownloadAttempt(DownloadJob(id='job_history_probe',user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        track_id='trk_history_probe',provider_name='youtube',status='running',operation_type='manual_reassign'))
    history.begin(attempt,'quality');history.prepare(attempt,'a'*64,'b'*64);history.finish(attempt,'succeeded')
    with history.connection() as db:
        assert db.execute('PRAGMA journal_mode').fetchone()[0]=='wal'
        assert db.execute('PRAGMA synchronous').fetchone()[0]==2
        permissions={p.name:oct(stat.S_IMODE(p.stat().st_mode)) for p in history.root.iterdir()}
        assert all(stat.S_IMODE(p.stat().st_mode)==0o600 for p in history.root.iterdir() if p.is_file())
    assert stat.S_IMODE(history.root.stat().st_mode)==0o700
    print(json.dumps(dict(created=True,root_on_audio_volume=True,directory_mode='0o700',files=permissions,rows=1)))
else:
    entries=history.list();assert len(entries)==1 and entries[0]['status']=='succeeded'
    # Keep a real WAL open and uncheckpointed through the backup.
    with history.connection() as db:
        db.execute('PRAGMA wal_autocheckpoint=0')
        db.execute("UPDATE audio_replacements SET reason='other'")
        db.commit()
        wal=Path(str(history.path)+'-wal');assert wal.is_file() and wal.stat().st_size>0
        private=Path('/tmp/history-backup');private.mkdir(mode=0o700)
        receipt=backup(history.path,private/'history.sqlite3')
        with sqlite3.connect(private/'history.sqlite3') as restored:
            assert restored.execute('SELECT reason FROM audio_replacements').fetchone()[0]=='other'
            assert restored.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        assert stat.S_IMODE((private/'history.sqlite3').stat().st_mode)==0o600
        receipt.update(persisted_across_containers=True,wal_committed_row_restored=True,
            source_wal_existed=True,restored_file_mode='0o600')
    print(json.dumps(receipt,sort_keys=True))
