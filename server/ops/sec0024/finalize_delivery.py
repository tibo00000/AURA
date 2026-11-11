"""Local delivery manifest + cumulative HEAD delta; no staging, commit, network or VPS access."""
if not __debug__:
    raise SystemExit('optimized_mode_refused')
import difflib
import hashlib
import gzip
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'scratch/sec0024/delivery'
BASE='16332183c7f453c27ce840658f862b6183947208'
EXCLUDED={'security-lot1-review.patch','security-lot1-tests.md'}

def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def main():
    assert git('rev-parse','HEAD').decode().strip()==BASE
    assert not git('diff','--cached','--name-only').strip()
    changed=git('diff','--name-only','HEAD').decode().splitlines()
    new=git('ls-files','--others','--exclude-standard').decode().splitlines()
    paths=sorted(set(changed+new)-EXCLUDED)
    assert all(p in ['BUILD.md','llms.txt','llms-full.txt'] or p.startswith(('server/','supabase/','docs/')) for p in paths)
    rows=[];parts=[git('diff','--binary','HEAD','--',*changed)]
    for name in paths:
        path=ROOT/name;assert path.is_file() and not path.is_symlink()
        assert not path.name.startswith('.env') and path.suffix not in ['.key','.pem','.dpapi']
        raw=path.read_bytes()
        assert not re.search(rb'sb_secret_[A-Za-z0-9_-]{25,}|-----BEGIN (?:OPENSSH |EC |RSA )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/=\r\n]{40,}',raw), 'Suspect source; details not printed'
        before=git('show',BASE+':'+name) if name in changed else None
        rows.append({'path':name,'change':'modified' if before is not None else 'added',
                     'before_git_blob_sha256':sha(before) if before is not None else None,
                     'after_raw_sha256':sha(raw),'after_lf_sha256':sha(raw.replace(b'\r\n',b'\n'))})
        if before is None:
            text=raw.decode('utf-8-sig').replace('\r\n','\n')
            assert text.endswith('\n'), 'Source must end in newline'
            diff='diff --git a/'+name+' b/'+name+'\nnew file mode 100644\n'
            diff+=''.join(difflib.unified_diff([],text.splitlines(keepends=True),fromfile='/dev/null',tofile='b/'+name))
            parts.append(diff.encode())
    patch=b''.join(parts)
    (OUT/'security-lot2-cumulative.patch').write_bytes(patch)
    replay=Path(tempfile.mkdtemp(prefix='replay-final-',dir=ROOT/'scratch/sec0024'))
    assert replay.resolve().is_relative_to((ROOT/'scratch/sec0024').resolve())
    for row in rows:
        if row['change']=='modified':
            p=replay/row['path'];p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(git('show',BASE+':'+row['path']))
    directory=replay.relative_to(ROOT).as_posix()
    git('apply','--check','--directory='+directory,str(OUT/'security-lot2-cumulative.patch'))
    git('apply','--directory='+directory,str(OUT/'security-lot2-cumulative.patch'))
    for row in rows:
        raw=(replay/row['path']).read_bytes().replace(b'\r\n',b'\n')
        assert sha(raw)==row['after_lf_sha256'], 'Cumulative patch replay mismatch'
    manifest={'base_commit':BASE,'files':rows,'excluded_preexisting_files':sorted(EXCLUDED),
              'patch_sha256':sha(patch),'patch_replayed_from_base':True,
              'comparison':'LF-normalized replay; exact build bytes in build-context archive/source manifest'}
    (OUT/'worktree-delta-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (OUT/'changes.md').write_text('# Exhaustive SEC-002 through SEC-002.4 delta\n\nBase: `'+BASE+'`.\n\n'+
        '\n'.join('- `'+r['path']+'` — '+r['change'] for r in rows)+'\n',encoding='utf-8')
    for path in (ROOT/'server/ops/sec0024').iterdir():
        if path.is_file():shutil.copyfile(path,OUT/path.name)
    shutil.copyfile(ROOT/'docs/ops/security-lot2-deployment.md',OUT/'runbook.md')
    shutil.copyfile(ROOT/'infra/security-lot1/target-log-check.py',OUT/'target-log-check.py')
    shutil.copyfile(ROOT/'scratch/sec0024/context-v2/source-manifest.json',OUT/'source-manifest.json')
    with (OUT/'security-lot2-ops.tar.gz').open('wb') as file,gzip.GzipFile(filename='',mode='wb',fileobj=file,mtime=0) as z,tarfile.open(fileobj=z,mode='w|',format=tarfile.PAX_FORMAT) as tar:
        for path in sorted(OUT.iterdir()):
            if path.is_file() and (path.suffix in ['.py','.sql','.yml','.Dockerfile'] or path.name in ['runbook.md','source-manifest.json','isolated-expected-postcheck.json']):
                info=tar.gettarinfo(str(path),path.name);info.uid=info.gid=0;info.uname=info.gname='';info.mtime=1792271645;info.mode=0o644
                with path.open('rb') as body:tar.addfile(info,body)
    candidate=json.loads((OUT/'candidate-manifest.json').read_text())
    candidate['delta_file_count']=len(rows);candidate['patch_replayed_from_base']=True
    candidate['files']={p.name:{'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size} for p in sorted(OUT.iterdir())
                        if p.is_file() and p.name!='candidate-manifest.json' and not p.name.startswith('SHA256SUMS')}
    (OUT/'candidate-manifest.json').write_text(json.dumps(candidate,indent=2,sort_keys=True)+'\n')
    (OUT/'SHA256SUMS-vps').write_text(''.join(sha((OUT/name).read_bytes())+'  '+name+'\n' for name in
        ['security-lot2-rc-amd64.tar.gz','security-lot2-ops.tar.gz','candidate-manifest.json']))
    (OUT/'SHA256SUMS').write_text(''.join(sha(p.read_bytes())+'  '+p.name+'\n' for p in sorted(OUT.iterdir())
                                      if p.is_file() and p.name!='SHA256SUMS'))
    print(json.dumps({'delta_files':len(rows),'patch_sha256':sha(patch),'replay_verified':True,
                      'candidate_manifest_sha256':sha((OUT/'candidate-manifest.json').read_bytes())}))

if __name__=='__main__':main()
