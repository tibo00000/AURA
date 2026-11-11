"""Allowlisted local SEC-003 context. No Docker, network, credentials or Git mutations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
BASE = 'sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2'
MIGRATION = 'supabase/migrations/20261017200822_secure_user_data_isolation.sql'
MIGRATION_SHA = 'ffa144f458f65cd2a0ba000ee07fbbeb6faa352cf9d6316f7c9e0b40a806afa4'
SCRIPTS = ('check_sec002_inventory.py', 'prepare_sec002_environment.py', 'provision_sec002_accounts.py',
           'verify_sec002_integrated.py', 'verify_sec002_sql_controls.py', 'test_user_isolation_postgres.py',
           'verify_security_lot1_local.py')
SUSPECT = re.compile(rb'-----BEGIN (?:OPENSSH |EC |RSA )?PRIVATE KEY-----|AGE-SECRET-KEY-[A-Z0-9]{16,}|sb_secret_[A-Za-z0-9_-]{25,}|eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}')


def freeze(output):
    if not __debug__:
        raise RuntimeError('Optimized mode refused')
    output = output.resolve()
    if output.exists():
        raise FileExistsError('A new context directory is required')
    if hashlib.sha256((ROOT/MIGRATION).read_bytes()).hexdigest() != MIGRATION_SHA:
        raise RuntimeError('Frozen lot2 migration changed')
    epoch = int(subprocess.check_output(['git', 'show', '-s', '--format=%ct', 'HEAD'], cwd=ROOT, text=True).strip())
    selected = {}

    def include(source, target):
        path = ROOT/source
        if (not path.is_file() or path.is_symlink()
                or any(part.startswith('.env') or part in ('__pycache__', '.git', '.venv')
                       for part in path.relative_to(ROOT).parts)):
            raise ValueError('Forbidden source; content not printed')
        raw = path.read_bytes()
        if SUSPECT.search(raw):
            raise ValueError('Suspect literal; content not printed')
        selected[target] = (source, raw)

    for folder, target, extensions in (
            ('server/app', 'runtime/app', {'.py', '.json', '.sql'}),
            ('server/tests', 'validation/tests', {'.py'})):
        for path in sorted((ROOT/folder).rglob('*')):
            if path.is_file() and path.suffix in extensions and '__pycache__' not in path.parts:
                include(path.relative_to(ROOT).as_posix(), target+'/'+path.relative_to(ROOT/folder).as_posix())
    for name in ('pyproject.toml', 'README.md'):
        include('server/'+name, 'runtime/'+name)
    for name in SCRIPTS:
        include('server/scripts/'+name, 'validation/server/scripts/'+name)
    include('server/scripts/consolidate_storage.py', 'validation/scripts/consolidate_storage.py')
    for folder in ('server/app/db/migrations', 'server/migrations', 'supabase/migrations', 'supabase/sql', 'supabase/tests'):
        for path in sorted((ROOT/folder).glob('*.sql')):
            include(path.relative_to(ROOT).as_posix(), 'validation/'+path.relative_to(ROOT).as_posix())
    for name in ('compose.yml', 'backend.env_example', 'requirements-tools.txt'):
        include('server/ops/sec0023/'+name, 'validation/server/ops/sec0023/'+name)
    include('server/ops/sec0033/verify_image.py', 'validation/server/ops/sec0033/verify_image.py')
    include('server/ops/sec0033/image.Dockerfile', 'Dockerfile')
    files = {name: dict(source=source, sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw))
             for name, (source, raw) in selected.items()}
    manifest = dict(format='aura-sec0033-context-v1', base_image=BASE, source_date_epoch=epoch,
                    git_base=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                    baseline_sec0032_patch_sha256='b4751045575cd3245601e781173b852baedfa37e18979240053ff5e34066eacb',
                    migration_sha256=MIGRATION_SHA, files=files)
    output.mkdir(parents=True, exist_ok=False)
    for name, (_, raw) in selected.items():
        target = output/name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        os.utime(target, (epoch, epoch))
    raw = (json.dumps(manifest, indent=2, sort_keys=True)+'\n').encode()
    (output/'source-manifest.json').write_bytes(raw)
    print(json.dumps(dict(files=len(files), manifest_sha256=hashlib.sha256(raw).hexdigest(),
                          source_date_epoch=epoch, migration_sha256=MIGRATION_SHA)))
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    freeze(parser.parse_args().output)
