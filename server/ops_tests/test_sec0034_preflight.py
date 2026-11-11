"""Offline prepared-command contract; no SSH, Docker, app imports or production."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT/'server/ops/sec0034/vps_readonly.py'
spec = importlib.util.spec_from_file_location('sec0034_preflight', PATH)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
SENTINEL = 'SYNTHETIC_PRIVATE_CONFIGURATION_MUST_NEVER_LEAVE'


def service():
    return dict(image=probe.ACTIVE_IMAGE,command=probe.OLD_CMD,build=None,pull_policy='never',
        ports=[],environment={**dict.fromkeys(probe.FLAGS,'false'),'SUPABASE_SERVICE_ROLE_KEY':SENTINEL},
        volumes=[dict(type='volume',source='aura-downloads',target='/app/downloads')],
        logging=dict(driver='json-file',options={'max-size':'10m','max-file':'3'}))


def test_service_allowlist_discards_private_environment_and_unknown_fields():
    value=service()
    value['credentials']=SENTINEL
    result=probe.service_summary(value,probe.ACTIVE_IMAGE,probe.OLD_CMD)
    assert all(result.values())
    assert SENTINEL not in json.dumps(result)


@pytest.mark.parametrize('change', ['port','mount','command','flag'])
def test_security_override_drift_is_not_accepted(change):
    value=service()
    if change=='port': value['ports']=[{'published':8000}]
    if change=='mount': value['volumes'].append(dict(type='bind',source='/private',target='/app/scripts'))
    if change=='command': value['command']=['uvicorn','app.main:app','--workers','2']
    if change=='flag': value['environment']['APP_DEBUG']='true'
    assert not all(probe.service_summary(value,probe.ACTIVE_IMAGE,probe.OLD_CMD).values())


def test_future_projection_overrides_the_compose_command_without_starting_services():
    service=json.loads(probe.FUTURE_OVERRIDE)['services']['aura-api']
    assert service['image']==probe.CANDIDATE_IMAGE
    assert service['command']==probe.NEW_CMD
    assert service['command'][service['command'].index('--workers')+1]=='1'
    assert service['pull_policy']=='never'


def test_script_without_execution_switch_performs_no_inventory():
    result=subprocess.run([sys.executable,'-B',str(PATH)],capture_output=True,text=True,check=True)
    assert json.loads(result.stdout)==dict(prepared_only=True,remote_accessed=False)


def test_inner_probe_is_valid_python_and_never_imports_application():
    compile(probe.PROCESS_PROBE,'prepared-process-probe','exec')
    assert 'from app' not in probe.PROCESS_PROBE and 'import app' not in probe.PROCESS_PROBE
    assert 'read_text' in probe.PROCESS_PROBE and 'write_text' not in probe.PROCESS_PROBE


def test_full_inventory_uses_only_prepared_read_commands_and_redacts(tmp_path,monkeypatch):
    files=tuple('compose'+str(i)+'.yml' for i in range(5))
    monkeypatch.setattr(probe,'ROOT',tmp_path)
    monkeypatch.setattr(probe,'FILES',files)
    for path in files: (tmp_path/path).write_text('SYNTHETIC')
    (tmp_path/'infra/caddy').mkdir(parents=True)
    (tmp_path/'infra/security-lot1').mkdir(parents=True)
    for path in ('infra/caddy/Caddyfile','infra/security-lot1/Caddyfile.release'):
        (tmp_path/path).write_text('SYNTHETIC')
    monkeypatch.setenv('DOCKER_HOST','')
    monkeypatch.setattr(probe,'host_python_count',lambda:0)
    monkeypatch.setattr(probe,'storage',lambda _:dict(available_bytes=10**10,available_inodes=10**5))
    rotation=dict(Type='json-file',Config={'max-size':'10m','max-file':'3'})
    api=dict(Id='synthetic-api-id',Image=probe.ACTIVE_IMAGE,State=dict(Running=True,Health=dict(Status='healthy')),
        Config=dict(Cmd=probe.OLD_CMD,Env=['SUPABASE_URL='+probe.ORIGIN,'SUPABASE_SERVICE_ROLE_KEY='+SENTINEL,
               *[name+'=false' for name in probe.FLAGS]],Labels={
               'com.docker.compose.project':'infra',
               'com.docker.compose.project.config_files':','.join(str(tmp_path/name) for name in files)}),
        HostConfig=dict(PortBindings={},LogConfig=rotation,NetworkMode='infra_default'),
        Mounts=[dict(Type='volume',Name='infra_aura-downloads',Destination='/app/downloads',RW=True)])
    dev=copy.deepcopy(api);dev['State']=dict(Running=False,Status='exited')
    caddy=copy.deepcopy(api)
    cfg=dict(services={'aura-api':service(),'caddy':{'depends_on':{'aura-api':{}}}})
    calls=[]
    def run(argv,body=None,env=None):
        calls.append((argv,body))
        if argv[:3]==['docker','context','inspect']: return 'unix:///var/run/docker.sock'
        if argv[:2]==['docker','inspect']:
            return json.dumps([dict({'aura-api':api,'aura-api-dev':dev,'aura-caddy':caddy}).get(argv[2],api)])
        if argv[:2]==['docker','compose']:
            assert [argv[argv.index('-f')+1]]==[str(tmp_path/files[0])]
            assert sum(part=='-f' for part in argv) in (5,6)
            assert 'config' in argv and not any(part in argv for part in ('up','stop','down','restart','pull','build'))
            if '--quiet' in argv:return ''
            result=copy.deepcopy(cfg)
            if '--profile' in argv:result['services']['aura-api-dev']={'profiles':['disabled-dev']}
            if body:result['services']['aura-api'].update(json.loads(body)['services']['aura-api'])
            return json.dumps(result)
        if argv[:3]==['docker','ps','-q']:return 'synthetic-api-id'
        if argv[:3]==['docker','volume','inspect']:return json.dumps([dict(Mountpoint='/synthetic-volume')])
        if argv[:2]==['docker','info']:return '/synthetic-docker'
        if argv[:3]==['docker','exec','aura-caddy']:
            if 'sha256sum' in argv:return 'syntheticdigest /etc/caddy/Caddyfile'
            assert 'adapt' in argv or 'wget' in argv
            return '{}'
        if argv[:3]==['docker','exec','-i']:
            assert argv[3:]==['aura-api','python','-B','-'] and body==probe.PROCESS_PROBE
            return json.dumps(dict(process_counts={'python':1,'uvicorn':1},native_threads_not_observable=True))
        if argv[0]=='du':return '1234 /synthetic-volume'
        if argv[0]=='ss':return ''
        if argv[0]=='timedatectl':return 'yes'
        raise AssertionError('Unexpected command')
    monkeypatch.setattr(probe,'run',run)
    result=probe.inventory()
    assert SENTINEL not in json.dumps(result)
    assert result['active']['audio_only_mount'] and result['proposed_compose']['command_matches']
    assert result['other_running_mutator_candidates']==0
    assert result['external_or_administrative_writers_not_observable']
    assert not result['compose_hashes_match'] # Fixture cannot masquerade as reviewed files.
    assert not result['caddy']['matches_release']
    assert any('disabled-dev' in argv for argv,_ in calls)


def test_sql_is_readonly_counts_only_and_privilege_gated():
    sql=(PATH.parent/'download_activity_readonly.sql').read_text(encoding='utf-8')
    assert 'BEGIN TRANSACTION READ ONLY;' in sql and 'ROLLBACK;' in sql
    assert 'rolsuper OR rolbypassrls' in sql
    assert 'count(*)' in sql and 'public.download_jobs' in sql
    for forbidden in ('INSERT INTO','UPDATE public','DELETE FROM','CREATE ','ALTER ','GRANT ','user_id','track_id'):
        assert forbidden not in sql
