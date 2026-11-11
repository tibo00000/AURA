"""PREPARED production positive smoke. Offline by default; future specific approval required.

Uses the existing DPAPI dedicated lot1 test account. No account/profile/playlist creation.
Execution creates Auth sessions and exactly two temporary audio files plus an intent journal.
"""
if not __debug__:
    raise SystemExit('optimized_mode_refused')
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[3]
IMAGE='sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2'
ORIGIN='https://neosgkuccazvgkzwzjzh.supabase.co'

def legacy_module():
    path=ROOT/'infra/security-lot1/windows-session.py'
    spec=importlib.util.spec_from_file_location('lot1_operator',path)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def target_program(legacy):
    inner=(ROOT/'infra/security-lot1/target-session-probe.py').read_text(encoding='utf-8')
    marker='            result = {"target_private_and_streaming"'
    assert inner.count(marker)==1
    inner=inner.replace(marker,'            require(client.get("/me/playlists",headers={"Authorization":"Bearer "+payload["access_token"]}).status_code == 200)\n'+marker)
    return legacy.TARGET_OUTER.replace('__IDENTITIES__',repr({IMAGE})).replace('__INNER__',repr(inner))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--route',choices=['internal','public'],required=True)
    p.add_argument('--execute-production',action='store_true');a=p.parse_args()
    legacy=legacy_module();outer=target_program(legacy)
    if not a.execute_production:
        print(json.dumps({'prepared':True,'network_accessed':False,'credentials_read':False,'writes':False,'expected_image':IMAGE}))
        return 0
    data=json.loads(legacy.crypt(legacy.STORE.read_bytes(),decrypt=True))
    assert data['expected_origin']==ORIGIN and data['url']==ORIGIN and data['creation_state']=='created'
    assert data['email'].startswith('security-lot1-') and data['email'].endswith('@aura.invalid')
    session=legacy.auth_request(data,'token?grant_type=password',{'email':data['email'],'password':data['password']})
    access=session['access_token']
    try:
        assert session['user']['id']==data['user_id']
        session=legacy.auth_request(data,'token?grant_type=refresh_token',{'refresh_token':session['refresh_token']})
        access=session['access_token'];assert session['user']['id']==data['user_id']
        import jwt
        start=jwt.decode(access,options={'verify_signature':False}).get('iat',0)-time.time()
        assert start<=2
        if start>0:time.sleep(start+.1)
        files=('app/core/auth.py','app/api/router.py','app/core/operational_logging.py','app/core/security-logging.json')
        payload={'expected_origin':ORIGIN,'access_token':access,'user_id':data['user_id'],'route':a.route,
                 'fixture_id':str(uuid.uuid4()),'hashes':{name:hashlib.sha256((ROOT/'server'/name).read_bytes()).hexdigest() for name in files}}
        command=['ssh','-T','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10',
                 'root@212.90.121.80','python3 -B -c '+shlex.quote(outer)]
        result=subprocess.run(command,input=json.dumps(payload),text=True,capture_output=True,timeout=100)
        assert result.returncode==0
        report=json.loads(result.stdout);assert report['synthetic_files_removed']==2
    finally:
        legacy.auth_request(data,'logout?scope=local',token=access)
    print(json.dumps({**report,'private_playlist_read':200,'refreshed_session':True,'logout_succeeded':True}))
    return 0

if __name__=='__main__':
    try:sys.exit(main())
    except Exception:
        print('{"probe_failed":true,"details_redacted":true}');sys.exit(1)
