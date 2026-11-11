"""Local PostgreSQL17 only: backup/restore and guarded migration, synthetic data, no network."""
import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
from types import SimpleNamespace
import uuid
import pytest

ROOT=Path(__file__).resolve().parents[2]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,ROOT/path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
guard=module('sec0024_guard','server/ops/sec0024/prepare_guarded_migration.py')
prepare=module('sec0024_env','server/scripts/prepare_sec002_environment.py')
IMAGE='postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24'

@pytest.fixture(scope='module')
def database():
    def run(args,**kw):
        r=subprocess.run(args,capture_output=True,timeout=90,**kw)
        assert r.returncode==0,r.stderr.decode()[-2000:]
        return r.stdout
    endpoint=run(['docker','context','inspect','--format','{{.Endpoints.docker.Host}}']).decode().strip()
    assert endpoint.startswith(('npipe://','unix://'))
    nonce=uuid.uuid4().hex
    cid=run(['docker','run','--rm','-d','--network','none','--label','aura.sec0024='+nonce,
        '--tmpfs','/var/lib/postgresql/data:rw','-e','POSTGRES_HOST_AUTH_METHOD=trust',IMAGE,
        '-c','listen_addresses=']).decode().strip()
    def sql(body,db='postgres',ok=True):
        r=subprocess.run(['docker','exec','-i',cid,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','postgres','-d',db],input=body.encode(),capture_output=True,timeout=90)
        if ok:assert r.returncode==0,r.stderr.decode()[-2000:]
        return r
    try:
        for _ in range(60):
            if subprocess.run(['docker','exec',cid,'pg_isready','-U','postgres'],capture_output=True).returncode==0:break
            time.sleep(.2)
        else:raise AssertionError('PostgreSQL startup failed')
        sql("""CREATE ROLE anon NOLOGIN NOBYPASSRLS;CREATE ROLE authenticated NOLOGIN NOBYPASSRLS;
CREATE ROLE service_role NOLOGIN BYPASSRLS;CREATE SCHEMA auth;CREATE TABLE auth.users(id uuid PRIMARY KEY);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS
$$SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
GRANT USAGE ON SCHEMA public,auth TO anon,authenticated,service_role;""")
        bundle=prepare.sql_bundle(ROOT)
        for name in ['01-schema.sql','02-catalog-rls.sql','04-fixtures.sql']:sql(bundle[name].decode('utf-8-sig'))
        sql("""INSERT INTO auth.users VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
INSERT INTO public.profiles(id) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
INSERT INTO public.playlists(id,user_id,name) VALUES('preexisting','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','Synthetic existing data');
INSERT INTO public.playlist_items(id,playlist_id,track_id,position) VALUES('existing-item','preexisting','sec0023-track',0);
INSERT INTO public.processed_operations(operation_id,user_id) VALUES('existing-op','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');""")
        run(['docker','exec',cid,'pg_dump','-U','postgres','-d','postgres','--format=custom','--schema=public','--schema=auth','--file=/tmp/pre.dump'])
        toc=run(['docker','exec',cid,'pg_restore','--list','/tmp/pre.dump'])
        assert b'TABLE DATA public playlists' in toc and b'ACL' in toc
        for db in ['restored','corrupt']:
            sql('CREATE DATABASE '+db)
            sql('DROP SCHEMA public',db)  # Newly created LOCAL database only; no CASCADE.
            run(['docker','exec',cid,'pg_restore','-U','postgres','--exit-on-error','-d',db,'/tmp/pre.dump'])
        yield SimpleNamespace(sql=sql,cid=cid,run=run)
    finally:
        info=json.loads(run(['docker','inspect',cid]))[0]
        assert info['Config']['Labels']['aura.sec0024']==nonce
        run(['docker','rm','-f',cid])

def test_restored_backup_then_guarded_migration_preserves_existing_data(database):
    assert database.sql('SELECT count(*) FROM auth.users','restored').stdout.strip()==b'1'
    result=database.sql(guard.guarded_sql(),'restored')
    receipt=json.loads(result.stdout)
    assert receipt['existing_tables_unchanged']>=11
    assert database.sql("SELECT name FROM public.playlists WHERE id='preexisting'",'restored').stdout.strip()==b'Synthetic existing data'
    result=database.sql(prepare.sql_bundle(ROOT)['05-verify.sql'].decode(),'restored')
    assert json.loads(result.stdout)['owner_guards']==11

def test_guard_detects_mutation_and_rolls_back_all_schema_changes(database):
    injected=guard.guarded_sql().replace('-- END exact migration body.',"UPDATE public.playlists SET name='synthetic accidental mutation';\n-- END exact migration body.")
    result=database.sql(injected,'corrupt',ok=False)
    assert result.returncode!=0 and b'existing data changed' in result.stderr
    assert database.sql("SELECT name FROM public.playlists WHERE id='preexisting'",'corrupt').stdout.strip()==b'Synthetic existing data'
    assert database.sql("SELECT count(*) FROM pg_proc WHERE proname='aura_mutate_playlist_items'",'corrupt').stdout.strip()==b'0'

def test_guard_refuses_changed_migration(tmp_path,monkeypatch):
    changed=tmp_path/'changed.sql';changed.write_text('BEGIN; COMMIT;')
    monkeypatch.setattr(guard,'SOURCE',changed)
    with pytest.raises(AssertionError):guard.guarded_sql()

def test_postcheck_detects_one_missing_crud_grant(database):
    database.sql('REVOKE UPDATE ON public.playlists FROM authenticated','restored')
    try:
        result=database.sql((ROOT/'server/ops/sec0024/postcheck.sql').read_text(),'restored')
        assert json.loads(result.stdout)['client_privileges_safe'] is False
    finally:database.sql('GRANT UPDATE ON public.playlists TO authenticated','restored')

def test_production_probe_default_never_reads_credentials_or_connects(monkeypatch,capsys):
    probe=module('prepared_prod_probe','server/ops/sec0024/production_probe.py')
    monkeypatch.setattr(probe,'legacy_module',lambda:object())
    monkeypatch.setattr(probe,'target_program',lambda _: 'pass')
    monkeypatch.setattr(sys,'argv',['probe','--route','internal'])
    assert probe.main()==0
    report=json.loads(capsys.readouterr().out)
    assert report['credentials_read'] is False and report['network_accessed'] is False and report['writes'] is False

def test_production_probe_generated_program_pins_new_image():
    probe=module('prepared_prod_probe_pin','server/ops/sec0024/production_probe.py')
    program=probe.target_program(probe.legacy_module())
    compile(program,'prepared-remote-probe','exec')
    assert probe.IMAGE in program and 'sha256:845680a8' not in program

@pytest.mark.parametrize('mismatch',[False,True])
def test_caddy_gate_requires_exact_active_json_and_preserved_bind_inode(tmp_path,mismatch):
    gate=module('prepared_caddy_gate','server/ops/sec0024/caddy_state.py')
    current=tmp_path/'infra/caddy/Caddyfile';current.parent.mkdir(parents=True);current.write_text('SYNTHETIC_SAFE')
    expected=tmp_path/'infra/security-lot1/Caddyfile.containment';expected.parent.mkdir(parents=True);expected.write_bytes(current.read_bytes())
    inode=tmp_path/'inode';info=current.stat();inode.write_text(str(info.st_dev)+':'+str(info.st_ino))
    def run(args):
        if 'sha256sum' in args:return hashlib.sha256(current.read_bytes()).hexdigest()+' /etc/caddy/Caddyfile'
        return json.dumps({'safe':False if mismatch and 'wget' in args else True})
    if mismatch:
        with pytest.raises(AssertionError):gate.verify('containment',inode,tmp_path,run)
    else:assert gate.verify('containment',inode,tmp_path,run)['active_json_exact'] is True
    assert current.read_text()=='SYNTHETIC_SAFE'

@pytest.mark.parametrize('name',['freeze_candidate.py','verify_image.py','prepare_guarded_migration.py',
                                'archive_candidate.py','production_probe.py','caddy_state.py'])
def test_operator_tools_refuse_optimized_mode_before_any_action(name):
    result=subprocess.run([sys.executable,'-B','-O',str(ROOT/'server/ops/sec0024'/name)],capture_output=True,text=True)
    assert result.returncode!=0 and 'optimized_mode_refused' in result.stderr
