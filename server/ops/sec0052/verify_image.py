"""Verify the self-contained candidate's hashes, dependencies and sensitive assets."""
import hashlib
import importlib.metadata as metadata
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tomllib

from packaging.requirements import Requirement

raw=Path('/opt/aura-source-manifest.json').read_bytes()
manifest=json.loads(raw)
assert manifest['format']=='aura-sec0052-context-v1'
for name,value in manifest['files'].items():
    if name=='Dockerfile':continue
    target=(Path('/app')/name.removeprefix('runtime/') if name.startswith('runtime/') else
            Path('/opt/aura-validation')/name.removeprefix('validation/'))
    assert target.is_file() and not target.is_symlink()
    assert hashlib.sha256(target.read_bytes()).hexdigest()==value['sha256'],name
for prefix,folder in [('runtime/app/','/app/app'),('validation/','/opt/aura-validation')]:
    expected={name.removeprefix(prefix) for name in manifest['files'] if name.startswith(prefix)}
    actual={p.relative_to(folder).as_posix() for p in Path(folder).rglob('*') if p.is_file()}
    assert actual==expected,'Unexpected or missing embedded file'
for folder in ['/app','/opt/aura-validation']:
    for path in Path(folder).rglob('*'):
        if not path.is_file():continue
        assert path.name not in ('.env','cookies.txt','credentials.json','local.properties')
        assert path.suffix not in ('.key','.pem','.sqlite3','.audio','.mp3')
        if path.suffix in ('.py','.json','.sql','.toml','.md','.yml','.env_example'):
            data=path.read_bytes()
            assert not re.search(rb'-----BEGIN (?:OPENSSH |EC |RSA )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]+-----END',data)
            assert not re.search(rb'AGE-SECRET-KEY-[A-Z0-9]{16,}|sb_secret_[A-Za-z0-9_-]{25,}|eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}',data)
assert str(importlib.util.find_spec('app').origin).startswith('/app/app/')
assert not Path('/usr/local/lib/python3.12/site-packages/app').exists()
project=tomllib.loads(Path('/app/pyproject.toml').read_text())
for value in project['project']['dependencies']:
    req=Requirement(value);assert metadata.version(req.name) in req.specifier
assert sys.version_info[:2]==(3,12) and metadata.version('supabase')=='2.33.0'
subprocess.run([sys.executable,'-m','pip','check'],check=True)
print(json.dumps(dict(status='verified',manifest_sha256=hashlib.sha256(raw).hexdigest(),
    copied_files_verified=len(manifest['files'])-1,python=sys.version.split()[0],
    packages={d.metadata['Name']:d.version for d in metadata.distributions()},
    application_imports_embedded_code=True,no_runtime_credentials=True),sort_keys=True))
