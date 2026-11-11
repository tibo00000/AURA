"""Run inside the immutable SEC-003 candidate without a source mount or credentials."""
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

if not __debug__:
    raise RuntimeError('Optimized mode refused')
path = Path('/opt/aura-source-manifest.json')
raw = path.read_bytes()
manifest = json.loads(raw)
if manifest['format'] != 'aura-sec0033-context-v1':
    raise RuntimeError('Wrong source manifest')
verified = 0
for name, value in manifest['files'].items():
    if name == 'Dockerfile':
        continue
    target = (Path('/app')/name.removeprefix('runtime/') if name.startswith('runtime/') else
              Path('/opt/aura-validation')/name.removeprefix('validation/'))
    if not target.is_file() or target.is_symlink() or hashlib.sha256(target.read_bytes()).hexdigest() != value['sha256']:
        raise RuntimeError('Embedded file mismatch; contents not printed')
    verified += 1
for prefix, folder in (('runtime/app/', '/app/app'), ('validation/', '/opt/aura-validation')):
    expected = {name.removeprefix(prefix) for name in manifest['files'] if name.startswith(prefix)}
    actual = {p.relative_to(folder).as_posix() for p in Path(folder).rglob('*') if p.is_file()}
    if actual != expected:
        raise RuntimeError('Unexpected embedded application/validation file')
suspect = re.compile(rb'-----BEGIN (?:OPENSSH |EC |RSA )?PRIVATE KEY-----|AGE-SECRET-KEY-[A-Z0-9]{16,}|sb_secret_[A-Za-z0-9_-]{25,}|eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}')
for folder in ('/app', '/opt/aura-validation'):
    for asset in Path(folder).rglob('*'):
        if not asset.is_file():
            continue
        if asset.name == '.env' or asset.name in ('cookies.txt', 'credentials.json') or asset.suffix in ('.key', '.pem'):
            raise RuntimeError('Forbidden file; contents not printed')
        if asset.suffix in ('.py', '.json', '.sql', '.toml', '.md') and suspect.search(asset.read_bytes()):
            raise RuntimeError('Suspect literal; contents not printed')
if not str(importlib.util.find_spec('app').origin).startswith('/app/app/'):
    raise RuntimeError('Application does not import embedded code')
if Path('/usr/local/lib/python3.12/site-packages/app').exists():
    raise RuntimeError('Old installed application remains')
project = tomllib.loads(Path('/app/pyproject.toml').read_text())
for requirement in project['project']['dependencies']:
    req = Requirement(requirement)
    if metadata.version(req.name) not in req.specifier:
        raise RuntimeError('Dependency does not meet frozen project requirements')
if sys.version_info[:2] != (3, 12) or metadata.version('supabase') != '2.33.0':
    raise RuntimeError('Unexpected Python or SDK version')
subprocess.run([sys.executable, '-m', 'pip', 'check'], check=True)
print(json.dumps(dict(status='verified', copied_files_verified=verified,
                     manifest_sha256=hashlib.sha256(raw).hexdigest(), python=sys.version.split()[0],
                     packages={d.metadata['Name']:d.version for d in metadata.distributions()},
                     no_env_or_private_key_in_application=True, application_imports_embedded_code=True), sort_keys=True))
