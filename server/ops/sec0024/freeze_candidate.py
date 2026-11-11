"""Offline allowlisted SEC0024 build context; no credentials, network or Git mutations."""
if not __debug__:
    raise SystemExit('optimized_mode_refused')
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[3]
BASE='sha256:5c541f103b62672bc5551aa093f840c878c7129797665ed63d2f0db5a874e16e'
MIGRATION='supabase/migrations/20261017200822_secure_user_data_isolation.sql'
MIGRATION_SHA='ffa144f458f65cd2a0ba000ee07fbbeb6faa352cf9d6316f7c9e0b40a806afa4'
SCRIPTS=['check_sec002_inventory.py','prepare_sec002_environment.py','provision_sec002_accounts.py',
         'verify_sec002_integrated.py','verify_sec002_sql_controls.py','test_user_isolation_postgres.py',
         'verify_security_lot1_local.py']

def freeze(output):
    output=output.resolve()
    assert not output.exists(), 'New output directory required'
    assert hashlib.sha256((ROOT/MIGRATION).read_bytes()).hexdigest()==MIGRATION_SHA
    epoch=int(subprocess.check_output(['git','show','-s','--format=%ct','HEAD'],cwd=ROOT,text=True).strip())
    files={}
    def include(source,target):
        src=ROOT/source
        assert src.is_file() and not src.is_symlink()
        assert not any(p.startswith('.env') or p in ('__pycache__','.git','.venv') for p in src.relative_to(ROOT).parts)
        raw=src.read_bytes()
        # Only source/configuration assets explicitly selected below, never local data.
        assert b'-----BEGIN PRIVATE KEY-----' not in raw and b'-----BEGIN OPENSSH PRIVATE KEY-----' not in raw
        dest=output/target; dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(raw)
        os.utime(dest,(epoch,epoch))
        files[target]={'source':source,'sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw)}
    for p in sorted((ROOT/'server/app').rglob('*')):
        if p.is_file() and p.suffix in ('.py','.json','.sql') and '__pycache__' not in p.parts:
            include(p.relative_to(ROOT).as_posix(),'runtime/app/'+p.relative_to(ROOT/'server/app').as_posix())
    for name in ['pyproject.toml','README.md']:
        include('server/'+name,'runtime/'+name)
    for p in sorted((ROOT/'server/tests').rglob('*.py')):
        if '__pycache__' not in p.parts:
            include(p.relative_to(ROOT).as_posix(),'validation/tests/'+p.relative_to(ROOT/'server/tests').as_posix())
    for name in SCRIPTS:
        include('server/scripts/'+name,'validation/server/scripts/'+name)
    include('server/scripts/consolidate_storage.py','validation/scripts/consolidate_storage.py')
    for folder in ['server/app/db/migrations','server/migrations','supabase/migrations','supabase/sql']:
        for p in sorted((ROOT/folder).glob('*.sql')):
            include(p.relative_to(ROOT).as_posix(),'validation/'+p.relative_to(ROOT).as_posix())
    for name in ['compose.yml','backend.env_example','requirements-tools.txt']:
        include('server/ops/sec0023/'+name,'validation/server/ops/sec0023/'+name)
    include('server/ops/sec0024/image.Dockerfile','Dockerfile')
    manifest={'format':'aura-sec0024-context-v1','base_image':BASE,'git_base':subprocess.check_output(
        ['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'source_date_epoch':epoch,
        'migration_sha256':MIGRATION_SHA,'files':files}
    raw=(json.dumps(manifest,indent=2,sort_keys=True)+'\n').encode()
    (output/'source-manifest.json').write_bytes(raw)
    print(json.dumps({'files':len(files),'manifest_sha256':hashlib.sha256(raw).hexdigest(),
                     'migration_sha256':MIGRATION_SHA,'source_date_epoch':epoch}))
    return manifest

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();freeze(args.output)
