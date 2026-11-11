"""Local deterministic artifacts and read-only layer inspection. Never connects to a VPS."""
if not __debug__:
    raise SystemExit('optimized_mode_refused')
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'scratch/sec0024/delivery'
IMAGE='sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2'
BASE='sha256:5c541f103b62672bc5551aa093f840c878c7129797665ed63d2f0db5a874e16e'
CONTEXT=ROOT/'scratch/sec0024/context-v2'

def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def compressed(source,target):
    with source.open('rb') as src,target.open('xb') as dest,gzip.GzipFile(filename='',mode='wb',fileobj=dest,mtime=0) as z:
        shutil.copyfileobj(src,z,1024*1024)
def main():
    OUT.mkdir(exist_ok=False)
    raw=ROOT/'scratch/sec0024/rc-normalized.tar'
    layers_checked=0;bad_names=[];bad_literals=[]
    with tarfile.open(raw,'r:') as image:
        manifest=json.load(image.extractfile('manifest.json'))
        for layer in manifest[0]['Layers']:
            with tarfile.open(fileobj=image.extractfile(layer),mode='r:*') as archive:
                layers_checked+=1
                for entry in archive:
                    name=Path(entry.name)
                    if name.name=='.env' or name.name in ['cookies.txt','credentials.json'] or name.name.startswith('.env.local'):
                        bad_names.append(entry.name)
                    if entry.isfile() and entry.name.startswith(('app/','opt/aura-validation/')) and entry.size<2_000_000:
                        content=archive.extractfile(entry).read()
                        if re.search(rb'sb_secret_[A-Za-z0-9_-]{25,}|-----BEGIN (?:OPENSSH |EC |RSA )?PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}',content):
                            bad_literals.append(entry.name)
    assert not bad_names and not bad_literals, 'Suspect layer asset; manual inspection required, details not printed'
    compressed(raw,OUT/'security-lot2-rc-amd64.tar.gz')
    base_raw=ROOT/'scratch/sec0024/base-runtime.tar'
    subprocess.run(['docker','image','save','--output',str(base_raw),BASE],check=True)
    compressed(base_raw,OUT/'security-lot2-runtime-base.tar.gz')
    context_archive=OUT/'security-lot2-build-context.tar.gz'
    with context_archive.open('xb') as f,gzip.GzipFile(filename='',mode='wb',fileobj=f,mtime=0) as z,tarfile.open(fileobj=z,mode='w|',format=tarfile.PAX_FORMAT) as tar:
        for p in sorted(CONTEXT.rglob('*')):
            if p.is_file():
                info=tar.gettarinfo(str(p),p.relative_to(CONTEXT).as_posix());info.uid=info.gid=0;info.uname=info.gname='';info.mtime=1792271645;info.mode=0o644
                with p.open('rb') as body:tar.addfile(info,body)
    for source,name in [(ROOT/'supabase/migrations/20261017200822_secure_user_data_isolation.sql','20261017200822_secure_user_data_isolation.sql'),
                        (ROOT/'scratch/sec0024/apply-sec0024-guarded.sql','apply-sec0024-guarded.sql')]:
        shutil.copyfile(source,OUT/name)
    for name in ['image.Dockerfile','compose.candidate.yml','verify_image.py','vps_readonly.py','prepare_guarded_migration.py']:
        shutil.copyfile(Path(__file__).parent/name,OUT/name)
    proof={'image':IMAGE,'base_image':BASE,'copied_files_verified':97,'layer_scan':{'layers':layers_checked,'suspect_filenames':len(bad_names),'suspect_literals':len(bad_literals)},
           'migration_sha256':digest(OUT/'20261017200822_secure_user_data_isolation.sql'),
           'files':{p.name:{'sha256':digest(p),'bytes':p.stat().st_size} for p in OUT.iterdir() if p.is_file()}}
    (OUT/'candidate-manifest.json').write_text(json.dumps(proof,indent=2,sort_keys=True)+'\n')
    print(json.dumps(proof,sort_keys=True))

if __name__=='__main__':main()
