"""Local synthetic only. Two blank PG17.6 clusters, age and PostgreSQL tools.

No secrets or connections from environment files are loaded. Never run against
an existing container, network Docker daemon or real Supabase project.
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import uuid

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('sec0025_proof', ROOT/'server/ops/sec0025/recovery_proof.py')
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)
spec_app = importlib.util.spec_from_file_location('sec0025_app', ROOT/'server/ops/sec0025/application_backup.py')
application = importlib.util.module_from_spec(spec_app)
spec_app.loader.exec_module(application)
AGE = ROOT/'scratch/sec0025/tools/age/age/age.exe'
KEYGEN = AGE.with_name('age-keygen.exe')


def run(args, data=None, ok=True):
    r = subprocess.run([str(a) for a in args], input=data, capture_output=True, timeout=120)
    if ok:
        assert r.returncode == 0, r.stderr.decode(errors='replace')[-1800:]
    return r


@pytest.fixture
def clusters(tmp_path_factory):
    endpoint = run(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).stdout.decode().strip()
    assert endpoint.startswith(('npipe://', 'unix://'))
    assert AGE.is_file() and KEYGEN.is_file(), 'Verified local age1.3.2 tools required'
    nonce = uuid.uuid4().hex
    containers = []
    startup = (ROOT/'server/ops/sec0025/private_cluster.sh').read_bytes().replace(b'\r\n', b'\n')
    def sql(cid, query, user=None, db='postgres', ok=True):
        if user is None:
            user = 'postgres' if cid == containers[0] else 'sec0025_restore_admin'
        return run(['docker', 'exec', '-i', cid, 'psql', '-h', '/tmp', '-X', '-qAt', '-v',
                    'ON_ERROR_STOP=1', '-U', user, '-d', db], query.encode(), ok=ok)
    try:
        for user in ('supabase_admin', 'sec0025_restore_admin'):
            cid = run(['docker', 'run', '--rm', '-d', '-i', '--pull', 'never', '--network', 'none',
                '--label', 'aura.sec0025='+nonce, '--read-only', '--cap-drop', 'ALL',
                '--security-opt', 'no-new-privileges', '--pids-limit', '256', '--user', 'postgres',
                '--memory', '1g', '--memory-swap', '1g', '--tmpfs', '/restore:rw,uid=101,gid=102,size=512m',
                '--tmpfs', '/tmp:rw,uid=101,gid=102,size=64m', '-e', 'RESTORE_BOOTSTRAP_ROLE='+user,
                '--entrypoint', '/bin/sh', proof.IMAGE, '-c', startup.decode()]).stdout.decode().strip()
            containers.append(cid)
            for _ in range(80):
                if sql(cid, 'SELECT 1', user=user, ok=False).returncode == 0:
                    break
                time.sleep(.15)
            else:
                raise AssertionError('Private synthetic cluster not ready')
            info = json.loads(run(['docker', 'inspect', cid]).stdout)[0]
            assert info['HostConfig']['NetworkMode'] == 'none'
            assert not info['HostConfig']['PortBindings']
            assert info['HostConfig']['Memory'] == info['HostConfig']['MemorySwap'] > 0
            assert all(m['Type'] == 'tmpfs' for m in info['Mounts'])
        a, b = containers
        sql(a, 'CREATE ROLE postgres LOGIN SUPERUSER', user='supabase_admin')
        sql(a, '''CREATE ROLE anon NOLOGIN NOBYPASSRLS; CREATE ROLE authenticated NOLOGIN NOBYPASSRLS;
CREATE ROLE service_role NOLOGIN BYPASSRLS;
CREATE ROLE authenticator LOGIN NOINHERIT; SET ROLE supabase_admin;
GRANT authenticated TO authenticator WITH INHERIT FALSE; RESET ROLE;
CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY, encrypted_password text);
CREATE TABLE auth.identities(id uuid PRIMARY KEY, user_id uuid REFERENCES auth.users(id), provider text);
CREATE SCHEMA supabase_migrations; CREATE TABLE supabase_migrations.schema_migrations(version text PRIMARY KEY);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
CREATE TABLE public.profiles(id uuid PRIMARY KEY REFERENCES auth.users(id));
CREATE TABLE public.playlists(id text PRIMARY KEY, user_id uuid REFERENCES public.profiles(id), name text NOT NULL);
CREATE TABLE public.items(id int GENERATED ALWAYS AS IDENTITY PRIMARY KEY, playlist_id text REFERENCES public.playlists(id), position int CHECK(position>=0), UNIQUE(playlist_id, position) DEFERRABLE INITIALLY DEFERRED);
ALTER TABLE public.playlists ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON public.playlists TO authenticated USING (user_id=auth.uid()) WITH CHECK(user_id=auth.uid());
GRANT USAGE ON SCHEMA public,auth TO authenticated; GRANT SELECT,INSERT,UPDATE,DELETE ON public.playlists TO authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO authenticated;
CREATE SCHEMA extensions; CREATE EXTENSION "uuid-ossp" WITH SCHEMA extensions;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions;
INSERT INTO auth.users VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','SYNTHETIC_A'),('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','SYNTHETIC_B');
INSERT INTO auth.identities VALUES ('aaaaaaaa-1111-4111-8111-111111111111','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','synthetic'),('bbbbbbbb-1111-4111-8111-111111111111','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','synthetic');
INSERT INTO public.profiles SELECT id FROM auth.users;
INSERT INTO public.playlists VALUES ('synthetic','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','before'),('owner-b','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','B');
INSERT INTO supabase_migrations.schema_migrations VALUES ('synthetic-pre-lot2');
INSERT INTO public.items(playlist_id,position) VALUES ('synthetic',0),('synthetic',1);
ALTER ROLE postgres NOSUPERUSER BYPASSRLS;''')
        yield a, b, sql, tmp_path_factory.mktemp('sec0025-synthetic')
    finally:
        for cid in containers:
            info = json.loads(run(['docker', 'inspect', cid]).stdout)[0]
            assert info['Config']['Labels']['aura.sec0025'] == nonce
            run(['docker', 'rm', '-f', cid])


def test_actual_snapshot_encryption_roles_and_restore(clusters):
    a, b, sql, folder = clusters
    session = subprocess.Popen(['docker', 'exec', '-i', a, 'psql', '-h', '/tmp', '-X', '-qAt',
                               '-v', 'ON_ERROR_STOP=1', '-U', 'postgres'], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        session.stdin.write(b'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT pg_export_snapshot();\n')
        session.stdin.flush()
        snapshot = session.stdout.readline().decode().strip()
        assert snapshot and all(c in '0123456789ABCDEF-' for c in snapshot)
        tables = json.loads(sql(a, "SELECT json_agg(schemaname||'.'||tablename ORDER BY schemaname,tablename) FROM pg_tables WHERE schemaname IN ('public','auth','supabase_migrations')").stdout)
        def table_proof(table):
            qualified = '.'.join('"'+v+'"' for v in table.split('.'))
            return "SELECT count(*)::text||':'||encode(sha256(coalesce(string_agg(sha256(convert_to(to_jsonb(t)::text,'UTF8')), ''::bytea ORDER BY sha256(convert_to(to_jsonb(t)::text,'UTF8'))),''::bytea)),'hex') FROM "+qualified+' t;'
        expected = {t:sql(a, 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;SET TRANSACTION SNAPSHOT '
                         +"'"+snapshot+"';"+table_proof(t)+'ROLLBACK;').stdout for t in tables}
        sql(a, "UPDATE public.playlists SET name='after' WHERE id='synthetic'; INSERT INTO public.items(playlist_id,position) VALUES('synthetic',2)")
        archive = run(['docker','exec',a,'pg_dump','-h','/tmp','-U','postgres','-d','postgres',
                       *application.dump_flags(snapshot)]).stdout
        assert archive.startswith(b'PGDMP')
    finally:
        session.communicate(b'ROLLBACK;\n', timeout=10)
        assert session.returncode == 0
    roles = json.loads(sql(a,"SELECT json_agg(json_build_object('name',rolname,'bypass_rls',rolbypassrls)) FROM pg_roles WHERE rolname NOT LIKE 'pg_%'").stdout)
    key = folder/'synthetic-only.key'
    run([KEYGEN, '-o', key])
    recipient = run([KEYGEN, '-y', key]).stdout.decode().strip()
    cipher = folder/'synthetic.dump.age'
    run([AGE, '-r', recipient, '-o', cipher], archive)
    decrypted = run([AGE, '-d', '-i', key, cipher]).stdout
    assert hashlib.sha256(decrypted).digest() == hashlib.sha256(archive).digest()
    # Only referenced application roles; no pg_dumpall/internal cluster membership replay.
    sql(b, application.clone_roles_sql(roles), user='sec0025_restore_admin')
    sql(b, 'CREATE DATABASE aura_recovery', user='sec0025_restore_admin')
    sql(b, 'DROP SCHEMA public;'+application.extension_bootstrap_sql(), db='aura_recovery')
    result = run(['docker','exec','-i',b,'pg_restore','-h','/tmp','-U','sec0025_restore_admin',
                  '--exit-on-error','--single-transaction','--dbname=aura_recovery'], decrypted)
    assert result.returncode == 0
    def restored(query):return sql(b,query,db='aura_recovery')
    for table in tables:
        assert restored(table_proof(table)).stdout == expected[table]
    assert restored("SELECT name FROM public.playlists WHERE id='synthetic'").stdout.strip() == b'before'
    assert restored('SELECT count(*) FROM public.items').stdout.strip() == b'2'
    assert sql(a, 'SELECT count(*) FROM public.items').stdout.strip() == b'3'
    assert restored('SELECT count(*) FROM auth.users').stdout.strip() == b'2'
    assert restored('SELECT count(*) FROM auth.identities i JOIN public.profiles p ON i.user_id=p.id').stdout.strip() == b'2'
    assert restored("SELECT count(*) FROM pg_constraint WHERE conrelid='public.items'::regclass AND condeferrable AND condeferred").stdout.strip() == b'1'
    assert restored("SELECT has_table_privilege('authenticated','public.playlists','SELECT') AND NOT has_table_privilege('anon','public.playlists','SELECT')").stdout.strip() == b't'
    assert restored("BEGIN;SET LOCAL ROLE authenticated;SELECT count(*) FROM public.playlists;ROLLBACK").stdout.strip() == b'0'
    for subject, expected_count in [('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',b'1'),('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',b'1'),('cccccccc-cccc-4ccc-8ccc-cccccccccccc',b'0')]:
        assert restored("BEGIN;SET LOCAL ROLE authenticated;SET LOCAL request.jwt.claim.sub='"+subject+"';SELECT count(*) FROM public.playlists;ROLLBACK").stdout.strip() == expected_count
    assert restored("SELECT has_function_privilege('authenticated','auth.uid()','EXECUTE')").stdout.strip() == b't'
    assert restored("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='playlists'").stdout.strip() == b'postgres'
    assert restored("SELECT bool_and(convalidated) FROM pg_constraint WHERE conrelid IN ('public.profiles'::regclass,'public.playlists'::regclass,'public.items'::regclass,'auth.identities'::regclass)").stdout.strip() == b't'
    assert restored("SELECT count(*) FROM auth.identities i LEFT JOIN auth.users u ON u.id=i.user_id WHERE u.id IS NULL").stdout.strip() == b'0'
    assert restored("SELECT has_table_privilege('authenticated','public.playlists','INSERT') AND has_table_privilege('authenticated','public.playlists','UPDATE') AND has_table_privilege('authenticated','public.playlists','DELETE')").stdout.strip() == b't'
    assert restored("SELECT (SELECT last_value FROM public.items_id_seq)>=(SELECT max(id) FROM public.items)").stdout.strip() == b't'
    assert restored("SELECT count(*) FROM pg_default_acl WHERE defaclrole='postgres'::regrole").stdout.strip() == b'1'
    assert restored("SELECT count(*) FROM pg_extension WHERE (extname,extversion) IN (('pgcrypto','1.3'),('uuid-ossp','1.1'))").stdout.strip() == b'2'
    # Corruption must fail authenticated decryption; never hand partial stdout to pg_restore.
    bad = bytearray(cipher.read_bytes()); bad[-1] ^= 1
    failed = run([AGE, '-d', '-i', key], bytes(bad), ok=False)
    assert failed.returncode != 0
    other_key = folder/'other-synthetic.key';run([KEYGEN,'-o',other_key])
    assert run([AGE,'-d','-i',other_key,cipher], ok=False).returncode != 0


def evidence():
    data = {'format':'aura-sec0025-evidence-v1', 'source':proof.SOURCE, 'archive_sha256':'a'*64,
        'snapshot':'synthetic-snapshot', 'table_names':['auth.users','public.playlists'],
        'complete':True, 'age_exit_code':0, 'roles_exit_code':0, 'restore_exit_code':0,
        'network_mode':'none','ports':[],'host_mounts':[],'swap_disabled':True,
        'unresolved':[], 'sequence_check_passed':True, 'role_probes_passed':True}
    for k in proof.CATEGORIES:
        data[k] = {'synthetic':'private-evidence'}
    return data


def test_receipt_accepts_only_complete_evidence_and_redacts_values():
    a = evidence();r = proof.compare(a, copy.deepcopy(a), archive_sha256='a'*64)
    assert r['verdict'] == 'PASS_SQL_RECOVERY'
    assert 'private-evidence' not in json.dumps(r) and 'auth.users' not in json.dumps(r)
    assert not r['production_maintenance_authorized']


@pytest.mark.parametrize('change', ['archive_sha256','snapshot','table_names','data','schema','rls',
                                  'constraints','roles','privileges','extensions','complete',
                                  'age_exit_code','restore_exit_code','unresolved','swap_disabled'])
def test_receipt_rejects_stale_partial_or_mismatching_restore(change):
    a = evidence();b = copy.deepcopy(a);b[change] = 'mismatch'
    assert proof.compare(a, b, archive_sha256='a'*64)['verdict'] == 'NO_GO'


def test_data_proof_detects_same_count_change_duplicates_and_null():
    a = [b'{"v":null}', b'{"v":1}', b'{"v":1}']
    assert proof.fingerprint_rows(a) == proof.fingerprint_rows(list(reversed(a)))
    assert proof.fingerprint_rows(a) != proof.fingerprint_rows([b'{"v":null}', b'{"v":1}', b'{"v":2}'])
    assert proof.fingerprint_rows(a) != proof.fingerprint_rows(a[:-1])


def test_default_mode_cannot_export_or_connect():
    result = run([__import__('sys').executable, '-B', ROOT/'server/ops/sec0025/recovery_proof.py'])
    assert json.loads(result.stdout)['network_accessed'] is False


@pytest.mark.parametrize('defect', ['missing_role','unsafe_client','bootstrap_collision'])
def test_application_clone_refuses_unsafe_or_incomplete_roles(defect):
    roles=[{'name':n,'bypass_rls':n in ('postgres','service_role')} for n in ('postgres','anon','authenticated','service_role')]
    if defect=='missing_role':roles.pop()
    if defect=='unsafe_client':roles[2]['bypass_rls']=True
    if defect=='bootstrap_collision':roles.append({'name':'sec0025_restore_admin','bypass_rls':True})
    with pytest.raises(ValueError):
        application.clone_roles_sql(roles)


def test_application_backup_scope_has_one_snapshot_and_no_destructive_flags():
    flags=application.dump_flags('00000001-00000002-1')
    assert {f for f in flags if f.startswith('--schema=')}=={'--schema='+s for s in application.SCHEMAS}
    assert '--clean' not in flags and '--no-owner' not in flags and '--no-acl' not in flags
    assert not any('vault' in f or 'storage' in f or 'realtime' in f for f in flags)
    with pytest.raises(ValueError):application.dump_flags('invalid')


def test_actual_coordinator_encrypts_and_restores_synthetic_scope(clusters):
    import sys
    sys.path.insert(0,str(ROOT/'server/ops/sec0025'))
    import run_recovery as coordinator
    a,b,sql,folder=clusters
    sql(a,'ALTER TABLE auth.users ADD COLUMN removed_synthetic text; ALTER TABLE auth.users ADD COLUMN retained_synthetic text; ALTER TABLE auth.users DROP COLUMN removed_synthetic;')
    def source_query(body):
        return json.loads(sql(a,"BEGIN READ ONLY;SET LOCAL search_path=pg_catalog;SET LOCAL timezone='UTC';SET LOCAL extra_float_digits=3;"+body+'ROLLBACK;').stdout)
    source=coordinator.collect(source_query)
    roles=json.loads(sql(a,"SELECT json_agg(json_build_object('name',rolname,'bypass_rls',rolbypassrls)) FROM pg_roles WHERE rolname NOT LIKE 'pg_%'").stdout)
    key=folder/'coordinator-synthetic-only.key';run([KEYGEN,'-o',key])
    recipient=run([KEYGEN,'-y',key]).stdout.decode().strip()
    cipher=folder/'coordinator-synthetic.dump.age'
    args=['docker','exec',a,'pg_dump','-h','/tmp','-U','postgres','-d','postgres','--format=custom',
          '--schema=public','--schema=auth','--schema=supabase_migrations','--no-publications','--no-subscriptions']
    digest=coordinator.export_encrypted(args,cipher,recipient)
    assert digest==hashlib.sha256(cipher.read_bytes()).hexdigest()
    # Each restore test gets an independent empty target cluster.
    try:
        validation=coordinator.restore_and_check(b,cipher,key.read_bytes(),source,roles)
    except coordinator.PrivateError as exc:
        # This fixture is synthetic only. Do not use this diagnostic on private data.
        d=json.loads(exc.details)
        differences={}
        for k,ok in d.get('checks',{}).items():
            if not ok and k in d['source']['metadata']:
                left,right=d['source']['metadata'][k],d['restored']['metadata'][k]
                if isinstance(left,list) and isinstance(right,list):
                    differences[k]=next(((x,y) for x,y in zip(left,right) if x!=y),(len(left),len(right)))
                else:differences[k]=(left,right)
        pytest.fail(json.dumps(differences))
    assert validation['table_count']==6 and all(validation['checks'].values())
    assert validation['owner_foreign_anon_probes'] is True and validation['probe_rollback_verified'] is True
    # No overwrite, including a previously sealed valid backup.
    with pytest.raises(coordinator.Stop):coordinator.export_encrypted(args,cipher,recipient)


def test_column_ordinals_ignore_only_dropped_holes():
    import sys
    sys.path.insert(0,str(ROOT/'server/ops/sec0025'))
    import run_recovery as coordinator
    source=[{'schema':'auth','table':'users','name':'id','position':1},
            {'schema':'auth','table':'users','name':'kept','position':3}]
    restored=[dict(source[0]),dict(source[1],position=2)]
    assert coordinator.logical_columns(source)==coordinator.logical_columns(restored)
    assert coordinator.logical_columns(source)!=coordinator.logical_columns([restored[0],dict(restored[1],name='changed')])
    with pytest.raises(coordinator.Stop):coordinator.logical_columns([source[1],source[0]])

def test_coordinator_icu_locale_is_real_and_rejects_version_mismatch():
    import sys
    sys.path.insert(0,str(ROOT/'server/ops/sec0025'))
    import run_recovery as coordinator
    database={'encoding':'UTF8','collate':'en_US.UTF-8','ctype':'en_US.UTF-8',
              'provider':'i','locale':'en-US','collation_version':'153.120',
              'actual_version':'153.120','icu_rules':None}
    containers=coordinator.Containers()
    try:
        cid=containers.start(True)
        coordinator.create_recovery_database(cid,database)
        assert json.loads(coordinator.local_sql(cid,coordinator.DATABASE_METADATA_SQL))==database
        bad=dict(database,actual_version='0')
        with pytest.raises(coordinator.Stop,match='source_collation_version_mismatch'):
            coordinator.create_recovery_database(cid,bad)
    finally:containers.close()
