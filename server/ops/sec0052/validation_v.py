"""Restricted V runner. No network at import/default CLI. No SQL/filesystem cleanup.

Live execution requires a separate reviewed plan, named accounts, approval and D
receipt. Tokens remain in memory. Never run against a personal library.
"""
import argparse
import base64
import getpass
import hashlib
import io
import json
import re
from pathlib import Path
import time
from urllib.parse import quote, unquote
import uuid
import wave

IMAGE = 'sha256:cd2a68b333fcafd5753a3654d34be12a5dc5a492d11f0469ffb9446d7d824e91'
API = 'http://127.0.0.1:18052'
AUTH = 'https://neosgkuccazvgkzwzjzh.supabase.co'
REQUIRED = {
    '/health':['get'], '/me/playlists':['get'],
    '/me/sync/files':['get'],
    '/me/sync/files/{track_id}':['get','post','delete'],
    '/downloads':['get','post'], '/downloads/reassign':['post'],
    '/downloads/{job_id}':['delete'], '/downloads/{job_id}/retry':['post'],
    '/downloads/{job_id}/resolve':['post'], '/downloads/{job_id}/file':['get'],
    '/jobs/{job_id}':['get'], '/me/audio-replacements':['get'],
}

class Stop(Exception):
    """Only fixed diagnostic codes, never server bodies or tokens."""

def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def new_plan():
    nonce = uuid.uuid4().hex
    tracks = {role:'trk_'+base64.urlsafe_b64encode(('v1:track:local:sec0052v-'+nonce+'-'+role).encode()).decode().rstrip('=')
              for role in ('personal_a','personal_b','shared','ordinary')}
    return dict(format='sec0052-V-v2',namespace=nonce,expected_image=IMAGE,api=API,auth=AUTH,
                production_accounts_confirmed=False,accounts=dict(A=None,B=None),tracks=tracks,
                video_ids=[],max_requests=120,max_upload_bytes=160*1024**2,max_jobs=4,
                native_source_max_seconds=60,provider_storage_budget_bytes=100*1024**2)

def check_openapi(document):
    for path,methods in REQUIRED.items():
        for method in methods:
            if method not in document.get('paths',{}).get(path,{}): raise Stop('route_contract_mismatch')
    components = document['components']['schemas']
    for name,fields in [('DownloadRequest',{'track_id','source_hint'}),
                        ('ResolveDownloadRequest',{'video_id'}),
                        ('ReassignAudioRequest',{'track_id','video_id','source_hint','reason'})]:
        if not fields <= set(components[name]['properties']): raise Stop('request_schema_mismatch')
    body = document['paths']['/me/sync/files/{track_id}']['post']['requestBody']['content']['multipart/form-data']['schema']
    schema = components[body['$ref'].rsplit('/',1)[1]]
    file_schema=schema['properties']['file']
    binary=file_schema.get('format')=='binary' or (
        file_schema.get('type')=='string' and file_schema.get('contentMediaType')=='application/octet-stream')
    if not binary or 'file' not in schema['required']:
        raise Stop('multipart_schema_mismatch')
    if document['paths']['/downloads/{job_id}']['delete']['responses'].get('204') is None:
        raise Stop('delete_contract_mismatch')
    return dict(routes=len(REQUIRED),request_schemas=4,delete_204=True,openapi_sha256=digest(document))

def fixture_audio():
    output = io.BytesIO()
    with wave.open(output,'wb') as stream:
        stream.setparams((1,2,8000,0,'NONE','not compressed')); stream.writeframes(b'\0\0'*8000)
    return output.getvalue()

def mutation_signature(value):
    if isinstance(value,bytes): return {'bytes':len(value),'sha256':hashlib.sha256(value).hexdigest()}
    if isinstance(value,(tuple,list)): return [mutation_signature(v) for v in value]
    if isinstance(value,dict): return {k:mutation_signature(v) for k,v in value.items()}
    if value is None or isinstance(value,(str,int,bool,float)): return value
    raise Stop('unsupported_mutation_payload')

class Runner:
    def __init__(self, client, plan, sessions, verify_uid):
        if plan['api'] != API or str(client.base_url).rstrip('/') != API: raise Stop('wrong_api_target')
        if client.follow_redirects: raise Stop('redirects_forbidden')
        if plan['expected_image'] != IMAGE or plan['auth'] != AUTH: raise Stop('wrong_candidate_target')
        uids = list(plan['accounts'].values())
        if len(set(uids)) != 2 or any(not v or str(uuid.UUID(v)) != v for v in uids): raise Stop('invalid_uid_allowlist')
        tracks = plan['tracks']; nonce = plan['namespace']
        if set(tracks)!={'personal_a','personal_b','shared','ordinary'} or len(set(tracks.values())) != 4: raise Stop('invalid_track_allowlist')
        for role,value in tracks.items():
            expected = 'trk_'+base64.urlsafe_b64encode(('v1:track:local:sec0052v-'+nonce+'-'+role).encode()).decode().rstrip('=')
            if value != expected: raise Stop('track_outside_synthetic_namespace')
        if not 1 <= plan['max_requests'] <= 120 or not 1 <= plan['max_upload_bytes'] <= 160*1024**2 or not 1 <= plan['max_jobs'] <= 4:
            raise Stop('budget_not_approved')
        self.client=client; self.plan=json.loads(json.dumps(plan)); self.sessions=dict(sessions); self.verify_uid=verify_uid
        a,b = uids; t=tracks
        self.positive={(a,t['personal_a']),(b,t['personal_b']),(a,t['shared']),(b,t['shared']),(a,t['ordinary'])}
        self.negative={(a,t['personal_b']),(b,t['personal_a'])}
        self.requests=0; self.upload_bytes=0; self.absent=set(); self.files=set(); self.jobs={}
        self.job_admissions=0; self.cleaning=False
        self._pending_mutation=None
        self.uncertain_job_admission=False; self.events=[]
        self.native_seed_sha=None
        for uid in uids: self.identity(uid)

    def identity(self,uid):
        if uid not in self.plan['accounts'].values() or uid not in self.sessions: raise Stop('uid_not_authorized')
        try: actual=self.verify_uid(self.sessions[uid])
        except Exception: raise Stop('identity_verification_unavailable') from None
        if actual != uid: raise Stop('session_identity_mismatch')

    def scope(self,uid,track,negative=False):
        if (uid,track) not in (self.negative if negative else self.positive): raise Stop('track_uid_pair_not_authorized')
        self.identity(uid)

    def _mutation_request(self,operation,uid,method,path,expected,**kwargs):
        if self._pending_mutation is not None: raise Stop('mutation_already_prepared')
        token=object()
        self._pending_mutation=dict(token=token,operation=operation,uid=uid,method=method,path=path,
                                    payload=digest(mutation_signature(kwargs)),expected=frozenset(expected))
        try: return self.request(uid,method,path,expected,_mutation_token=token,**kwargs)
        finally: self._pending_mutation=None

    def request(self,uid,method,path,expected,**kwargs):
        token=kwargs.pop('_mutation_token',None); prepared=None
        if method in ('POST','DELETE'):
            prepared=self._pending_mutation
            self._pending_mutation=None  # Single use, including failures/reentrant callbacks.
            if prepared is None or token is not prepared['token']: raise Stop('mutation_not_prepared')
            if (uid,method,path,frozenset(expected),digest(mutation_signature(kwargs))) != (
                prepared['uid'],prepared['method'],prepared['path'],prepared['expected'],prepared['payload']):
                raise Stop('mutation_preparation_mismatch')
        elif token is not None: raise Stop('unexpected_mutation_token')
        operation=prepared['operation'] if prepared else None
        charge=0; admission=None; file_intent=None
        cross_probe=kwargs.pop('_cross_account_probe',False)
        if not path.startswith('/') or '?' in path or '..' in path or '://' in path: raise Stop('route_not_authorized')
        if uid is None:
            if method!='GET' or path not in ('/health','/openapi.json','/me/playlists'): raise Stop('anonymous_mutation_forbidden')
        elif path=='/me/sync/files':
            if method!='GET' or kwargs: raise Stop('route_not_authorized')
            self.identity(uid)
        elif path.startswith('/me/sync/files/'):
            track=unquote(path.removeprefix('/me/sync/files/'))
            self.scope(uid,track,negative=(uid,track) in self.negative and method=='GET')
            if method not in ('GET','POST','DELETE'): raise Stop('route_not_authorized')
            if method=='POST':
                if operation!='upload' or (uid,track) not in self.absent: raise Stop('preexisting_or_unchecked_file')
                part=kwargs.get('files',{}).get('file')
                if not isinstance(part,tuple) or len(part)!=3 or not isinstance(part[1],bytes): raise Stop('unsupported_upload_payload')
                charge=len(part[1])+4096
                if self.upload_bytes+charge>self.plan['max_upload_bytes']: raise Stop('upload_budget_exhausted')
                file_intent=(uid,track)
            if method=='DELETE' and (operation!='cleanup_file' or not self.cleaning or (uid,track) not in self.files or (uid,track) not in self.absent):
                raise Stop('file_cleanup_not_prepared')
        elif path=='/me/audio-replacements':
            if method!='GET': raise Stop('audit_mutation_forbidden')
            self.scope(uid,kwargs.get('params',{}).get('track_id'))
        elif path in ('/downloads','/downloads/reassign'):
            if method=='POST':
                body=kwargs.get('json',{}); self.scope(uid,body.get('track_id'))
                if path.endswith('reassign') and body.get('video_id') not in self.plan['video_ids']: raise Stop('video_not_authorized')
                shared=path.endswith('reassign'); track=body['track_id']
                required_operation='reassign' if shared else 'create_job'
                b_native=(not shared and uid==self.plan['accounts']['B'] and track==self.plan['tracks']['shared'])
                role='shared' if shared or b_native else 'ordinary'
                video=body.get('video_id') if shared else body.get('source_hint',{}).get('resolved_video_id')
                if operation!=required_operation or track!=self.plan['tracks'][role] or (uid,track) not in self.absent:
                    raise Stop('job_admission_not_prepared')
                if b_native and (video!=self.plan['video_ids'][0] or not self.native_seed_sha):
                    raise Stop('native_seed_not_confirmed')
                if video not in self.plan['video_ids']: raise Stop('video_not_authorized')
                if self.job_admissions>=self.plan['max_jobs']: raise Stop('job_budget_exhausted')
                if self.uncertain_job_admission: raise Stop('uncertain_admission_requires_human')
                admission=(uid,track,shared)
            elif method!='GET' or path!='/downloads': raise Stop('route_not_authorized')
        elif path.startswith(('/jobs/','/downloads/')):
            pieces=path.strip('/').split('/'); job=unquote(pieces[1])
            if job not in self.jobs: raise Stop('job_not_authorized')
            owner=self.jobs[job][0]
            if owner!=uid and not (cross_probe and method=='GET' and pieces[0]=='jobs' and len(pieces)==2 and expected=={403,404}):
                raise Stop('job_not_authorized')
            permitted=(pieces[0]=='jobs' and method=='GET' and len(pieces)==2) or (
                pieces[0]=='downloads' and ((method=='DELETE' and len(pieces)==2) or
                (method=='POST' and len(pieces)==3 and pieces[2] in ('retry','resolve')) or
                (method=='GET' and len(pieces)==3 and pieces[2]=='file')))
            if not permitted: raise Stop('route_not_authorized')
            if method=='DELETE' and operation!='cancel_job': raise Stop('job_cleanup_not_prepared')
            if method=='POST' and operation!=pieces[2]: raise Stop('job_resume_not_prepared')
            if method=='GET' and pieces[0]=='downloads' and pieces[2]=='file':
                if self.owned_job(uid,job)['status']!='succeeded': raise Stop('job_file_not_ready')
        elif path!='/me/playlists' or method!='GET': raise Stop('route_not_authorized')
        if uid is not None:
            self.identity(uid); kwargs['headers']={**kwargs.get('headers',{}),'Authorization':'Bearer '+self.sessions[uid]}
        limit=self.plan['max_requests'] if self.cleaning else max(0,self.plan['max_requests']-16)
        if self.requests >= limit: raise Stop('request_budget_exhausted')
        self.requests+=1
        if file_intent is not None:
            self.upload_bytes+=charge; self.files.add(file_intent)
        if admission is not None:
            self.job_admissions+=1; self.uncertain_job_admission=True
            self.files.add((admission[0],admission[1]))  # Intent includes native outputs, before HTTP.
        try: response=self.client.request(method,path,**kwargs)
        except Exception: raise Stop('http_failure_effect_may_be_uncertain') from None
        self.events.append(dict(method=method,route_sha256=hashlib.sha256(path.encode()).hexdigest(),status=response.status_code))
        if response.status_code not in expected: raise Stop('unexpected_http_status')
        if admission is not None:
            data=response.json()['data']; created=data['job_id']
            if data['track_id']!=admission[1] or not isinstance(created,str) or not re.fullmatch(r'job_[A-Za-z0-9_-]{1,128}',created):
                raise Stop('untrusted_job_response')
            if created in self.jobs: raise Stop('job_collision')
            self.jobs[created]=admission; self.uncertain_job_admission=False
        return response

    def assert_new(self,uid,track):
        self.scope(uid,track)
        self.request(uid,'GET','/me/sync/files/'+quote(track,safe=''),{404})
        self.absent.add((uid,track))

    def upload(self,uid,track,content,expected=201):
        self.scope(uid,track)
        return self._mutation_request('upload',uid,'POST','/me/sync/files/'+quote(track,safe=''),{expected},
                            files={'file':('fixture.wav',content,'audio/wav')},data={'title':'SEC0052 synthetic'})

    def read(self,uid,track,*,negative=False,range_bytes=None):
        self.scope(uid,track,negative)
        headers={'Range':range_bytes} if range_bytes else {}
        return self.request(uid,'GET','/me/sync/files/'+quote(track,safe=''),
                            {403,404} if negative else ({206} if range_bytes else {200}),headers=headers)

    def start_job(self,uid,track,*,shared=False,video_id):
        self.scope(uid,track)
        if video_id not in self.plan['video_ids']: raise Stop('video_not_authorized')
        if shared and track != self.plan['tracks']['shared']: raise Stop('shared_track_not_authorized')
        b_native=(uid==self.plan['accounts']['B'] and track==self.plan['tracks']['shared'])
        if not shared and track != self.plan['tracks']['ordinary'] and not b_native: raise Stop('ordinary_track_not_authorized')
        if (uid,track) not in self.absent: raise Stop('track_not_proven_new')
        hint=dict(title='SEC0052 synthetic',artist_name='Synthetic',resolved_video_id=video_id)
        payload=dict(track_id=track,source_hint=hint)
        if shared: payload.update(video_id=video_id,reason='quality')
        response=self._mutation_request('reassign' if shared else 'create_job',uid,'POST',
                                        '/downloads/reassign' if shared else '/downloads',{201},json=payload)
        return response.json()['data']['job_id']

    def verify_native(self,uid,track,expected_sha=None):
        self.scope(uid,track)
        if track!=self.plan['tracks']['shared'] or (uid,track) not in self.files:
            raise Stop('native_fixture_not_registered')
        rows=self.request(uid,'GET','/me/sync/files',{200}).json()['data']['items']
        selected=[row for row in rows if row.get('track_id')==track]
        if len(selected)!=1 or selected[0].get('cache_origin')!='native_v1':
            raise Stop('native_reference_not_proven')
        content=self.read(uid,track).content
        observed=hashlib.sha256(content).hexdigest()
        if not content or selected[0].get('cache_sha256')!=observed or (expected_sha and expected_sha!=observed):
            raise Stop('native_reference_hash_mismatch')
        return observed

    def wait_succeeded(self,uid,job):
        # Three jobs x at most 16 list requests fit the 104-request test budget.
        for index in range(16):
            data=self.owned_job(uid,job)
            if data['status']=='succeeded': return
            if data['status'] in ('failed','cancelled','requires_resolution','superseded'):
                raise Stop('provider_or_job_did_not_succeed')
            if index<15: time.sleep(1)
        raise Stop('job_wait_budget_exhausted')

    def owned_job(self,uid,job):
        self.identity(uid)
        if job not in self.jobs or self.jobs[job][0] != uid: raise Stop('job_not_authorized')
        # The real /jobs response has id/kind/status, but no track_id. Use the
        # owner-scoped download list, whose embedded schema actually exposes it.
        listing=self.request(uid,'GET','/downloads',{200},params={'limit':100}).json()['data']
        if listing.get('meta',{}).get('next_cursor') is not None: raise Stop('unexpected_job_pagination')
        rows=[row for row in listing['items'] if row['id']==job]
        if len(rows)!=1: raise Stop('registered_job_not_found')
        data=rows[0]
        if data['track_id'] != self.jobs[job][1]: raise Stop('job_identity_changed')
        return data

    def resume(self,uid,job,*,video_id=None):
        data=self.owned_job(uid,job)
        if video_id is None:
            if not self.jobs[job][2]: raise Stop('manual_retry_only')
            return self._mutation_request('retry',uid,'POST','/downloads/'+quote(job,safe='')+'/retry',{400})
        if video_id not in self.plan['video_ids']: raise Stop('video_not_authorized')
        if data['status'] not in ('failed','cancelled','requires_resolution'): raise Stop('resolve_state_not_admissible')
        return self._mutation_request('resolve',uid,'POST','/downloads/'+quote(job,safe='')+'/resolve',{200},json={'video_id':video_id})

    def read_job(self,uid,job,*,range_bytes=None):
        headers={'Range':range_bytes} if range_bytes else {}
        return self.request(uid,'GET','/downloads/'+quote(job,safe='')+'/file',
                            {206} if range_bytes else {200},headers=headers)

    def cancel_job(self,uid,job):
        self.owned_job(uid,job)
        response=self._mutation_request('cancel_job',uid,'DELETE','/downloads/'+quote(job,safe=''),{204})
        if response.content: raise Stop('delete_204_not_empty')
        self.request(uid,'GET','/jobs/'+quote(job,safe=''),{404})
        del self.jobs[job]
        return response

    def deny_job(self,uid,job):
        if uid not in self.plan['accounts'].values() or job not in self.jobs or self.jobs[job][0]==uid:
            raise Stop('negative_job_probe_not_authorized')
        return self.request(uid,'GET','/jobs/'+quote(job,safe=''),{403,404},_cross_account_probe=True)

    def history(self,uid,track):
        self.scope(uid,track)
        return self.request(uid,'GET','/me/audio-replacements',{200},params={'track_id':track,'limit':20}).json()['data']['items']

    def replace_session(self,uid,new_token):
        if uid not in self.sessions or self.verify_uid(new_token) != uid: raise Stop('refresh_identity_changed')
        self.sessions[uid]=new_token

    def cleanup(self):
        # Reserve capacity separately from the test request budget, bounded by the
        # At most 3 remaining jobs +5 files after the ordinary cancellation:
        # 3 requests per job +5 DELETE files =14, within the 16 reserved.
        result=[]; self.cleaning=True
        for job,(uid,track,_) in list(self.jobs.items()):
            try:
                self.cancel_job(uid,job); result.append('job_removed')
            except Exception: result.append('job_cleanup_blocked')
        if self.uncertain_job_admission or 'job_cleanup_blocked' in result:
            self.cleaning=False
            return dict(results=result+['files_retained_pending_job_resolution'],
                        uncertain_job_admission=self.uncertain_job_admission,audit_deleted=False,requires_human=True)
        for uid,track in sorted(self.files):
            try:
                self.scope(uid,track)
                self._mutation_request('cleanup_file',uid,'DELETE','/me/sync/files/'+quote(track,safe=''),{200})
                self.files.discard((uid,track))
                result.append('synthetic_file_removed')
            except Exception: result.append('file_cleanup_blocked')
        self.cleaning=False
        return dict(results=result,uncertain_job_admission=self.uncertain_job_admission,
                    audit_deleted=False,requires_human=any('blocked' in s for s in result) or self.uncertain_job_admission)

    def smoke(self):
        schema=self.request(None,'GET','/openapi.json',{200}).json(); check_openapi(schema)
        self.request(None,'GET','/health',{200})
        self.request(None,'GET','/me/playlists',{401})
        self.request(None,'GET','/me/playlists',{401},headers={'Authorization':'Bearer SYNTHETIC_INVALID_TOKEN'})
        a,b=self.plan['accounts'].values(); t=self.plan['tracks']; audio=fixture_audio()
        for uid,track in self.positive: self.assert_new(uid,track)
        for uid in (a,b):
            # Dedicated accounts must have no pre-existing download jobs.
            if self.request(uid,'GET','/downloads',{200},params={'limit':100}).json()['data']['items']:
                raise Stop('test_account_has_preexisting_jobs')
            if self.request(uid,'GET','/me/sync/files',{200}).json()['data']['items']:
                raise Stop('test_account_has_preexisting_files')
            if self.request(uid,'GET','/me/playlists',{200}).json()['data']:
                raise Stop('test_account_has_preexisting_playlists')
        self.upload(a,t['personal_a'],audio); self.upload(b,t['personal_b'],audio)
        self.read(b,t['personal_a'],negative=True); self.read(a,t['personal_b'],negative=True)
        response=self.read(a,t['personal_a'],range_bytes='bytes=0-1023')
        if response.content != audio[:1024] or not response.headers.get('Content-Range'): raise Stop('range_mismatch')
        self.upload(a,t['personal_a'],b'x'*(50*1024**2),expected=201)
        self.upload(a,t['personal_a'],b'x'*(50*1024**2+1),expected=413)
        if len(self.read(a,t['personal_a']).content) != 50*1024**2: raise Stop('file_changed_after_413')
        if len(set(self.plan['video_ids'])) < 2: raise Stop('two_public_sources_not_approved')
        ordinary=self.start_job(a,t['ordinary'],video_id=self.plan['video_ids'][0])
        # DELETE is meaningful while the dedicated ordinary job can still be active.
        self.owned_job(a,ordinary)
        self.deny_job(b,ordinary)
        self.cancel_job(a,ordinary)
        shared=self.start_job(a,t['shared'],shared=True,video_id=self.plan['video_ids'][0])
        self.wait_succeeded(a,shared)
        job_audio=self.read_job(a,shared).content
        job_range=self.read_job(a,shared,range_bytes='bytes=0-1023')
        if job_range.content!=job_audio[:1024] or not job_range.headers.get('Content-Range'): raise Stop('job_range_mismatch')
        new_sha=self.verify_native(a,t['shared'])
        self.native_seed_sha=new_sha
        attached=self.start_job(b,t['shared'],video_id=self.plan['video_ids'][0])
        self.wait_succeeded(b,attached)
        self.verify_native(b,t['shared'],new_sha)
        events=self.history(a,t['shared'])
        if not any(row['status']=='succeeded' and row['new_sha256']==new_sha for row in events):
            raise Stop('shared_publication_history_mismatch')
        replacement=self.start_job(a,t['shared'],shared=True,video_id=self.plan['video_ids'][1])
        self.wait_succeeded(a,replacement)
        replaced_sha=self.verify_native(a,t['shared'])
        self.verify_native(b,t['shared'],replaced_sha)
        if replaced_sha==new_sha: raise Stop('replacement_not_propagated')
        if not any(row['status']=='succeeded' and row['old_sha256']==new_sha and row['new_sha256']==replaced_sha
                   for row in self.history(a,t['shared'])): raise Stop('replacement_history_mismatch')
        self.resume(a,shared)  # manual retry must refuse even after context loss.
        return dict(ok=True,scope='restricted_smoke_only',deployment_go=False,api_requests=self.requests,upload_bytes=self.upload_bytes,
                    remaining_manual_resolve='requires admissible dedicated job; covered locally',
                    expiration_real='separate naturally expired dedicated JWT required',audit_preserved=True)


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--plan',type=Path); parser.add_argument('--write-example',type=Path)
    parser.add_argument('--execute',action='store_true'); parser.add_argument('--approval',type=Path); parser.add_argument('--d-receipt',type=Path)
    parser.add_argument('--collision-receipt',type=Path)
    args=parser.parse_args()
    if args.write_example:
        with args.write_example.open('x',encoding='utf-8') as output: json.dump(new_plan(),output,indent=2)
        print('{"prepared":true,"network_used":false}'); return
    if not args.execute:
        print('{"prepared":true,"network_used":false,"requires":"named V approval and exact plan"}'); return
    if not all((args.plan,args.approval,args.d_receipt,args.collision_receipt)): raise Stop('approval_d_and_collision_receipt_required')
    plan=json.loads(args.plan.read_text()); approval=json.loads(args.approval.read_text()); deployed=json.loads(args.d_receipt.read_text())
    if not plan['production_accounts_confirmed'] or approval.get('phase')!='V' or approval.get('plan_sha256')!=digest(plan): raise Stop('V_not_authorized')
    if deployed.get('image_id')!=IMAGE or deployed.get('healthy') is not True or deployed.get('workers')!=1: raise Stop('D_gate_missing')
    collision=json.loads(args.collision_receipt.read_text())
    if (collision.get('ok') is not True or collision.get('plan_sha256')!=digest(plan)
            or collision.get('expected_image')!=IMAGE or collision.get('metadata_only') is not True
            or collision.get('collisions')!=0): raise Stop('collision_gate_missing')
    import httpx
    with httpx.Client(base_url=AUTH,verify=True,trust_env=False,follow_redirects=False,timeout=20) as auth:
        public_key=getpass.getpass('Clé publique Supabase dédiée à cette session locale (masquée) : ')
        auth.headers['apikey']=public_key
        def subject(token):
            r=auth.get('/auth/v1/user',headers={'Authorization':'Bearer '+token})
            if r.status_code!=200: raise Stop('auth_identity_verification_failed')
            return r.json()['id']
        sessions={}; refresh={}
        for role,uid in plan['accounts'].items():
            email=getpass.getpass('Email du compte synthétique '+role+' (masqué) : ')
            password=getpass.getpass('Mot de passe local masqué : ')
            response=auth.post('/auth/v1/token',params={'grant_type':'password'},json={'email':email,'password':password})
            if response.status_code!=200: raise Stop('test_login_failed')
            values=response.json(); sessions[uid]=values['access_token']; refresh[uid]=values['refresh_token']
        with httpx.Client(base_url=API,trust_env=False,follow_redirects=False,timeout=30) as api:
            runner=Runner(api,plan,sessions,subject)
            try:
                result=runner.smoke()
                for uid in sessions:
                    response=auth.post('/auth/v1/token',params={'grant_type':'refresh_token'},json={'refresh_token':refresh[uid]})
                    if response.status_code!=200: raise Stop('test_refresh_failed')
                    runner.replace_session(uid,response.json()['access_token'])
                    runner.request(uid,'GET','/me/playlists',{200})
            finally:
                cleanup=runner.cleanup()
                print(json.dumps(dict(cleanup=cleanup)))
            result['cleanup_requires_human']=cleanup['requires_human']
            result['ok']=result['ok'] and not cleanup['requires_human']
            print(json.dumps(result))


if __name__=='__main__':
    try: main()
    except Exception as error:
        # Never echo exceptions/responses containing emails, JWT, or HTTP URLs.
        print(json.dumps(dict(ok=False,error_type=type(error).__name__,diagnostic='V stopped; private details withheld')))
        raise SystemExit(1)
