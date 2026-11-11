"""Prepared metadata-only check of exact synthetic paths. No SSH, API or SQL.

Default prints a plan-only notice. --stdin-plan is for a separately authorized V
exec in the verified backend container; never list a library or read an audio file.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import uuid
from validation_v import digest,IMAGE,AUTH,Stop

def check(root,plan):
    if plan['expected_image']!=IMAGE or plan['auth']!=AUTH: raise Stop('wrong_probe_target')
    if set(plan['accounts'])!={'A','B'} or len(set(plan['accounts'].values()))!=2: raise Stop('wrong_probe_accounts')
    for uid in plan['accounts'].values():
        if str(uuid.UUID(uid))!=uid: raise Stop('wrong_probe_uid')
    if set(plan['tracks'])!={'personal_a','personal_b','shared','ordinary'}: raise Stop('wrong_probe_tracks')
    for role,track in plan['tracks'].items():
        expected='trk_'+base64.urlsafe_b64encode(('v1:track:local:sec0052v-'+plan['namespace']+'-'+role).encode()).decode().rstrip('=')
        if track!=expected: raise Stop('wrong_probe_namespace')
    root=Path(root)
    if root.is_symlink() or not root.is_dir(): raise Stop('unsafe_probe_root')
    a,b=plan['accounts'].values(); t=plan['tracks']
    pairs=((a,t['personal_a']),(b,t['personal_b']),(a,t['shared']),(b,t['shared']),(a,t['ordinary']))
    paths=[]
    for uid,track in pairs:
        directory=root/hashlib.sha256(uid.encode()).hexdigest()
        if directory.is_symlink(): raise Stop('unsafe_probe_parent')
        for suffix in ('.audio','.json'): paths.append(directory/(hashlib.sha256(track.encode()).hexdigest()+suffix))
    for directory in (root/'_global_cache',root/'_global_cache/_native_v1'):
        if directory.is_symlink(): raise Stop('unsafe_probe_parent')
        for track in t.values():
            for suffix in ('.audio','.json','.mp3'):
                paths.append(directory/(hashlib.sha256(track.encode()).hexdigest()+suffix))
    present=0
    for path in paths:
        try: os.lstat(path); present+=1
        except FileNotFoundError: pass
    if present: raise Stop('synthetic_path_collision')
    return dict(ok=True,plan_sha256=digest(plan),expected_image=IMAGE,checked_paths=len(paths),
                collisions=0,metadata_only=True)

if __name__=='__main__':
    try:
        if sys.argv[1:]!=['--stdin-plan']:
            print(json.dumps({'prepared':True,'network_used':False}));raise SystemExit(0)
        from app.config import get_settings
        print(json.dumps(check(get_settings().sync_files_dir,json.load(sys.stdin))))
    except Exception:
        print(json.dumps({'ok':False,'diagnostic':'collision_probe_stopped_private_details_withheld'}));raise SystemExit(1)
