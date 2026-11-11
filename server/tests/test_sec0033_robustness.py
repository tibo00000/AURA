"""Local R1/R2 controls: preserve personal bytes and disable unsafe cache eviction."""
import asyncio
import os
from unittest.mock import MagicMock

import pytest

from app.api.routes import sync_files
from app.services import download_service as downloads
from tests.native_cache_fixture import register_native
from tests.test_download_job_security import service, A, B


@pytest.mark.parametrize('failure', ['copy', 'replace'])
def test_failed_reassignment_preserves_old_personal_audio_and_metadata(service, tmp_path, monkeypatch, failure):
    instance, db = service
    source = tmp_path / 'native.mp3'
    track = 'trk_deezer_654321'
    source.write_bytes(b'ID3-OLD-AUTHORIZED-NATIVE')
    assert register_native(A, track, source)
    audio, metadata = sync_files._paths(A, track)
    witness = tmp_path / 'hardlink-witness.mp3'
    os.link(audio, witness)
    before = audio.read_bytes(), metadata.read_bytes(), audio.stat().st_ino
    source.write_bytes(b'ID3-NEW-AUTHORIZED-NATIVE')
    assert register_native(B, track, source)
    cached = downloads._find_globally_cached_track(track)[0]

    def fail_link(*args, **kwargs):
        raise OSError('synthetic link failure')
    def fail_copy(src, dest, *args, **kwargs):
        # Simulate a partial copy before disk-full, not just a pre-write exception.
        from pathlib import Path
        Path(dest).write_bytes(b'PARTIAL')
        raise OSError('synthetic copy failure')
    def fail_replace(*args, **kwargs):
        raise OSError('synthetic replace failure')
    monkeypatch.setattr(os, 'link', fail_link)
    if failure == 'copy':
        import shutil
        monkeypatch.setattr(shutil, 'copyfile', fail_copy)
    else:
        monkeypatch.setattr(os, 'replace', fail_replace)
    downloads._propagate_reassigned_track_to_all_users(track, cached, title='Replacement')
    assert audio.is_file(), 'Propagation destroyed the previous personal entry'
    assert (audio.read_bytes(), metadata.read_bytes(), audio.stat().st_ino) == before
    assert witness.read_bytes() == before[0]
    assert cached.read_bytes() == source.read_bytes()
    assert not list(audio.parent.glob('_reassign_*'))


def test_reassignment_copy_fallback_replaces_personal_entry_atomically(service, tmp_path, monkeypatch):
    instance, db = service
    source = tmp_path / 'native.mp3'
    track = 'trk_deezer_654321'
    source.write_bytes(b'ID3-OLD-NATIVE-AUDIO')
    assert register_native(A, track, source)
    audio, metadata = sync_files._paths(A, track)
    witness = tmp_path / 'witness.mp3'
    os.link(audio, witness)
    source.write_bytes(b'ID3-NEW-NATIVE-AUDIO')
    assert register_native(B, track, source)
    cached = downloads._find_globally_cached_track(track)[0]
    def no_hardlink(*args, **kwargs):
        raise OSError('synthetic cross-device link failure')
    monkeypatch.setattr(os, 'link', no_hardlink)
    downloads._propagate_reassigned_track_to_all_users(track, cached)
    assert audio.read_bytes() == source.read_bytes()
    assert witness.read_bytes() == b'ID3-OLD-NATIVE-AUDIO'
    assert not list(audio.parent.glob('_reassign_*'))


def test_disabled_eviction_preserves_unreferenced_old_cache(service, tmp_path):
    instance, db = service
    source = tmp_path / 'native.mp3'
    source.write_bytes(b'ID3-ORPHANED-VERIFIED-NATIVE')
    track = 'trk_deezer_654321'
    assert register_native(A, track, source)
    personal, meta = sync_files._paths(A, track)
    personal.unlink()
    meta.unlink()
    cached = downloads._find_globally_cached_track(track)[0]
    os.utime(cached, (1000000000, 1000000000))
    before = cached.read_bytes(), cached.with_suffix('.json').read_bytes()
    assert asyncio.run(instance.cleanup_orphaned_cache(max_age_days=1)) == 0
    assert (cached.read_bytes(), cached.with_suffix('.json').read_bytes()) == before


def test_disabled_eviction_never_enters_storage_or_track_locks(service, monkeypatch):
    instance, db = service
    lookup = MagicMock(side_effect=AssertionError('Disabled eviction accessed storage'))
    guard = MagicMock(side_effect=AssertionError('Disabled eviction acquired a track guard'))
    monkeypatch.setattr(downloads, '_get_global_cache_dir', lookup)
    monkeypatch.setattr(instance, '_get_track_lock', guard)
    assert asyncio.run(instance.cleanup_orphaned_cache(max_age_days=0)) == 0
    lookup.assert_not_called()
    guard.assert_not_called()
