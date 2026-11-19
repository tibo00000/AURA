"""Explicit one-time operator request; no audio or metadata edits here."""
import argparse,hashlib,json,os
from app.api.routes.sync_files import _base_dir,_list_playable_sync_files
from app.db.supabase import supabase
from app.services.storage_safety import stage_json


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--playlist',required=True)
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    parents=supabase.table('playlists').select('user_id').eq('id',args.playlist).execute().data
    assert len(parents)==1,'Unknown playlist owner'
    owner=parents[0]['user_id']
    items=_list_playable_sync_files(owner)
    request={'user_id':owner,'version':1,'status':'pending','track_ids':sorted({i['track_id'] for i in items}),'completed':{}}
    print(json.dumps({'tracks':len(request['track_ids']),'version':1,'apply':args.apply}))
    if not args.apply:return
    root=_base_dir()/'_metadata_transitions'/'requests'
    root.mkdir(parents=True,exist_ok=True,mode=0o700)
    assert not root.is_symlink()
    target=root/(hashlib.sha256(owner.encode()).hexdigest()+'.json')
    if target.exists():
        old=json.loads(target.read_text(encoding='utf-8'));assert old['user_id']==owner and old['version']==1
        print(json.dumps({'existing_run_preserved':True,'status':old['status']}));return
    staged=stage_json(target,request);os.replace(staged,target);target.chmod(0o600)


if __name__=='__main__':main()
