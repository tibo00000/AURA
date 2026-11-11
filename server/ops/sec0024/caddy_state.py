"""Read-only Caddy gate; prepared for the authorized operation, never switches configuration."""
if not __debug__:
    raise SystemExit('optimized_mode_refused')
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

def verify(mode,inode_file,root=Path('/opt/aura'),run=None):
    if run is None:
        def run(args):return subprocess.check_output(args,text=True,stderr=subprocess.DEVNULL,timeout=15)
    active_file=root/'infra/caddy/Caddyfile'
    candidate=root/('infra/security-lot1/Caddyfile.'+mode)
    assert not active_file.is_symlink() and active_file.is_file()
    digest=hashlib.sha256(active_file.read_bytes()).hexdigest()
    assert digest==hashlib.sha256(candidate.read_bytes()).hexdigest()
    assert run(['docker','exec','aura-caddy','sha256sum','/etc/caddy/Caddyfile']).split()[0]==digest
    adapted=json.loads(run(['docker','exec','aura-caddy','caddy','adapt','--config','/etc/caddy/Caddyfile','--adapter','caddyfile']))
    active=json.loads(run(['docker','exec','aura-caddy','wget','-qO-','http://127.0.0.1:2019/config/']))
    assert active==adapted
    info=active_file.stat();inode=str(info.st_dev)+':'+str(info.st_ino)
    assert inode==inode_file.read_text().strip()
    return {'caddy_mode_verified':mode,'host_bind_sha256':digest,'active_json_exact':True,'inode_preserved':True}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--expect',choices=['containment','release'],required=True)
    p.add_argument('--inode-file',type=Path,required=True);args=p.parse_args()
    try:print(json.dumps(verify(args.expect,args.inode_file)))
    except Exception:
        print('{"caddy_gate_failed":true,"details_redacted":true}');sys.exit(1)
