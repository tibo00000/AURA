"""Run via SSH python3 -B stdin only after read-only authorization. No remote writes."""
import hashlib
import base64
import json
import os
from pathlib import Path
import subprocess
import time

ROOT=Path('/opt/aura')
def run(args):
    return subprocess.check_output(args,text=True,stderr=subprocess.DEVNULL,timeout=30)
def inspect(name):
    return json.loads(run(['docker','inspect',name]))[0]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()

result={'format':'aura-sec0024-vps-readonly-v1','utc_epoch':time.time(),
        'ntp_synchronized':run(['timedatectl','show','--property=NTPSynchronized','--value']).strip()=='yes'}
api,dev,caddy=[inspect(n) for n in ['aura-api','aura-api-dev','aura-caddy']]
env=dict(v.split('=',1) for v in api['Config']['Env'] if '=' in v)
public=env.get('SUPABASE_ANON_KEY','')
if public.startswith('sb_publishable_'):
    key_kind='publishable';key_shape_valid=True
else:
    key_kind='legacy_anon'
    try:
        payload=public.split('.')[1];claims=json.loads(base64.urlsafe_b64decode(payload+'='*(-len(payload)%4)))
        key_shape_valid=claims.get('role')=='anon' and claims.get('ref')=='neosgkuccazvgkzwzjzh'
    except Exception:key_shape_valid=False
result['api']={'image':api['Image'],'running':api['State']['Running'],
 'health':api['State'].get('Health',{}).get('Status'),
 'lot1_flags_safe':all(env.get(k)=='false' for k in ['APP_DEBUG','ENABLE_STARTUP_RECOVERY','ENABLE_CACHE_BACKFILL']),
 'app_env_production':env.get('APP_ENV')=='production',
 'supabase_target_matches':env.get('SUPABASE_URL','').rstrip('/')=='https://neosgkuccazvgkzwzjzh.supabase.co',
 'public_key_present':bool(env.get('SUPABASE_ANON_KEY')),
 'public_key_kind':key_kind,'public_key_config_valid':key_shape_valid,
 'port_bindings_empty':not api['HostConfig']['PortBindings'],
 'command_safe':api['Config']['Cmd']==['uvicorn','app.core.operational_logging:create_app','--factory','--host','0.0.0.0','--port','8000','--log-config','/app/app/core/security-logging.json'],
 'log_config':api['HostConfig']['LogConfig'],
 'mounts':[{'type':m['Type'],'destination':m['Destination'],'rw':m['RW'],'name':m.get('Name')} for m in api['Mounts']]}
result['dev']={'running':dev['State']['Running'],'status':dev['State']['Status'],'port_bindings_empty':not dev['HostConfig']['PortBindings']}
result['caddy']={'image':caddy['Image'],'running':caddy['State']['Running'],'log_config':caddy['HostConfig']['LogConfig']}
host=ROOT/'infra/caddy/Caddyfile'
mounted=run(['docker','exec','aura-caddy','sha256sum','/etc/caddy/Caddyfile']).split()[0]
expected=json.loads(run(['docker','exec','aura-caddy','caddy','adapt','--config','/etc/caddy/Caddyfile','--adapter','caddyfile']))
active=json.loads(run(['docker','exec','aura-caddy','wget','-qO-','http://127.0.0.1:2019/config/']))
result['caddy'].update({'host_sha256':sha(host),'bind_matches_host':mounted==sha(host),
 'active_matches_file':active==expected,'matches_reviewed_release':sha(host)==sha(ROOT/'infra/security-lot1/Caddyfile.release'),
 'inode':str(host.stat().st_dev)+':'+str(host.stat().st_ino)})
os.environ['AURA_SECURITY_IMAGE']=api['Config']['Image']
dc=['docker','compose','-p','infra']
configs=['infra/docker-compose.vps.yml','infra/docker-compose.vps.caddy.yml',
         'infra/security-lot1/compose.containment.yml','infra/security-lot1/compose.release.yml']
for p in configs:dc+=['-f',str(ROOT/p)]
result['compose_files']={p:sha(ROOT/p) for p in configs}
run(dc+['config','--quiet'])
cfg=json.loads(run(dc+['config','--format','json']))
candidate=cfg['services']['aura-api']
profile_cfg=json.loads(run(dc+['--profile','disabled-dev','config','--format','json']))
result['compose']={'valid':True,'no_api_ports':not candidate.get('ports'),
 'dev_absent_by_default':'aura-api-dev' not in cfg['services'],
 'dev_profile_disabled':'disabled-dev' in profile_cfg['services']['aura-api-dev'].get('profiles',[]),
 'caddy_only_depends_prod':set(cfg['services']['caddy'].get('depends_on',{}))=={'aura-api'}}
sockets=run(['ss','-ltnH']).splitlines()
result['host_api_sockets_absent']=not any(line.split()[3].rsplit(':',1)[-1] in ['8000','8001'] for line in sockets)
result['disk_free_bytes']=os.statvfs(ROOT).f_bavail*os.statvfs(ROOT).f_frsize
result['remote_files_written']=False
print(json.dumps(result,sort_keys=True))
