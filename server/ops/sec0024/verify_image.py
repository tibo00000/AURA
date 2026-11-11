"""Read-only verifier, run as python -B stdin INSIDE the candidate (no mounts)."""
if not __debug__:
    raise SystemExit('optimized_mode_refused')
import hashlib
import importlib.metadata as metadata
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tomllib
from packaging.requirements import Requirement

manifest_path=Path('/opt/aura-source-manifest.json')
manifest=json.loads(manifest_path.read_text())
verified=0
for name,value in manifest['files'].items():
    if name=='Dockerfile':continue  # Recipe separately hashed in external context/archive.
    target=Path('/app')/name.removeprefix('runtime/') if name.startswith('runtime/') else Path('/opt/aura-validation')/name.removeprefix('validation/')
    assert target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest()==value['sha256'], 'Embedded file mismatch'
    verified+=1
expected={name.removeprefix('runtime/app/') for name in manifest['files'] if name.startswith('runtime/app/')}
actual={p.relative_to('/app/app').as_posix() for p in Path('/app/app').rglob('*') if p.is_file()}
assert actual==expected, 'Unexpected application file'
assert not list(Path('/app').rglob('.env')) and not list(Path('/opt/aura-validation').rglob('.env'))
assert not list(Path('/app').rglob('*.pem')) and not list(Path('/app').rglob('*.key'))
assert str(importlib.util.find_spec('app').origin).startswith('/app/app/')
assert not Path('/usr/local/lib/python3.12/site-packages/app').exists(), 'Old installed application remains'
project=tomllib.loads(Path('/app/pyproject.toml').read_text())
for raw in project['project']['dependencies']:
    req=Requirement(raw)
    assert metadata.version(req.name) in req.specifier, 'Project requirement mismatch'
assert sys.version_info[:2]==(3,12) and metadata.version('supabase')=='2.33.0'
subprocess.run([sys.executable,'-m','pip','check'],check=True)
print(json.dumps({'status':'verified','copied_files_verified':verified,
 'manifest_sha256':hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
 'python':sys.version.split()[0],'packages':{d.metadata['Name']:d.version for d in metadata.distributions()},
 'no_env_or_private_key_in_application':True,'application_imports_embedded_code':True},sort_keys=True))
