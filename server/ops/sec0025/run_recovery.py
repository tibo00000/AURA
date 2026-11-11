"""One authorized M2-S backup + private restore. No production write/maintenance.

All process stderr and detailed SQL evidence are private and encrypted, never
printed. Source credentials only enter tmpfs/stdin. Restore has no network/mounts.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
from zoneinfo import ZoneInfo

from application_backup import SCHEMAS, clone_roles_sql, dump_flags, extension_bootstrap_sql
from recovery_proof import IMAGE
from secure_access import BASE, ROOT, TOOLS, Stop, connect_readonly, load_access, preflight, safe_error, unprotect


def qident(name):
    return '"'+name.replace('"','""')+'"'


def qliteral(value):
    return "'"+value.replace("'","''")+"'"


def digest_file(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()


def run(args, *, data=None, timeout=120):
    result = subprocess.run([str(v) for v in args],input=data,capture_output=True,timeout=timeout)
    if result.returncode:
        raise PrivateError('local_command_failed',result.stderr)
    return result.stdout


class PrivateError(Stop):
    def __init__(self, code, details=b''):
        super().__init__(code)
        self.details = details


def pipe_binary(producer_args, consumer_args, *, producer_input=None, consumer_output=subprocess.DEVNULL, timeout=240):
    """Binary OS pipes only. Return private stderr; never log it or raw output."""
    p = subprocess.Popen([str(v) for v in producer_args],stdin=subprocess.PIPE if producer_input is not None else subprocess.DEVNULL,
                         stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    c = None
    with ThreadPoolExecutor(max_workers=2) as threads:
        pe = threads.submit(p.stderr.read)
        try:
            c = subprocess.Popen([str(v) for v in consumer_args],stdin=p.stdout,stdout=consumer_output,stderr=subprocess.PIPE)
            p.stdout.close()
            ce = threads.submit(c.stderr.read)
            if producer_input is not None:
                p.stdin.write(producer_input);p.stdin.close()
            c.wait(timeout=timeout)
            p.wait(timeout=15)
            return p.returncode,c.returncode,pe.result(timeout=10),ce.result(timeout=10)
        finally:
            for proc in (p,c):
                if proc is not None and proc.poll() is None:
                    proc.kill();proc.wait(timeout=10)


def encrypt_bytes(payload, path, recipient):
    path = Path(path);partial = path.with_suffix(path.suffix+'.partial')
    if path.exists() or partial.exists():
        raise Stop('refusing_existing_ciphertext')
    with partial.open('xb') as output:
        result = subprocess.run([str(TOOLS/'age.exe'),'-r',recipient],input=payload,stdout=output,stderr=subprocess.PIPE,timeout=30)
        if result.returncode:
            raise PrivateError('evidence_encryption_failed',result.stderr)
        output.flush();os.fsync(output.fileno())
    partial.rename(path)
    return digest_file(path)


def export_encrypted(producer, path, recipient):
    path = Path(path);partial = path.with_suffix(path.suffix+'.partial')
    if path.exists() or partial.exists():
        raise Stop('refusing_existing_backup')
    with partial.open('xb') as output:
        pr,cr,pe,ce = pipe_binary(producer,[TOOLS/'age.exe','-r',recipient],consumer_output=output)
        output.flush();os.fsync(output.fileno())
    if pr or cr or pe or ce:
        raise PrivateError('dump_or_encryption_failed_or_warned',pe+b'\n'+ce)
    partial.rename(path)
    return digest_file(path)


class Containers:
    def __init__(self):
        endpoint = run(['docker','context','inspect','--format','{{.Endpoints.docker.Host}}']).decode().strip()
        if not endpoint.startswith(('npipe://','unix://')):
            raise Stop('nonlocal_docker_context')
        self.nonce = uuid.uuid4().hex
        self.ids = {}

    def start(self, restore):
        args = ['docker','run','--rm','-d','--pull','never','--network','none' if restore else 'bridge',
                '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user','postgres',
                '--pids-limit','256','--memory','4g','--memory-swap','4g','--log-driver','none',
                '--tmpfs','/tmp:rw,uid=101,gid=102,size=128m','--label','aura.sec0025.actual='+self.nonce]
        if restore:
            args += ['--tmpfs','/restore:rw,uid=101,gid=102,size=3g','-e','RESTORE_BOOTSTRAP_ROLE=sec0025_restore_admin']
            program = (ROOT/'server/ops/sec0025/private_cluster.sh').read_text().replace('\r\n','\n')
        else:
            program = 'exec sleep 1800'
        cid = run(args+['--entrypoint','/bin/sh',IMAGE,'-c',program]).decode().strip()
        self.ids[cid] = restore
        details = json.loads(run(['docker','inspect',cid]))[0]
        host = details['HostConfig']
        if host['Memory'] != 4294967296 or host['MemorySwap'] != 4294967296 or host['PortBindings']:
            raise Stop('container_limits_or_ports_unsafe')
        if restore and (host['NetworkMode'] != 'none' or any(v['Type'] != 'tmpfs' for v in details['Mounts'])):
            raise Stop('restore_isolation_unsafe')
        memory = run(['docker','exec',cid,'/bin/sh','-c',
            'cat /sys/fs/cgroup/memory/memory.limit_in_bytes /sys/fs/cgroup/memory/memory.memsw.limit_in_bytes']).split()
        if memory != [b'4294967296',b'4294967296']:
            raise Stop('effective_swap_limit_not_demonstrated')
        if restore:
            # pg_isready retries are bounded; no SQL or data is read here.
            import time
            for _ in range(100):
                r = subprocess.run(['docker','exec',cid,'pg_isready','-h','/tmp','-U','sec0025_restore_admin'],capture_output=True)
                if r.returncode == 0:
                    break
                time.sleep(.15)
            else:
                raise Stop('private_postgres_not_ready')
        return cid

    def remove(self,cid):
        info = json.loads(run(['docker','inspect',cid]))[0]
        if info['Config']['Labels'].get('aura.sec0025.actual') != self.nonce:
            raise Stop('cleanup_label_mismatch')
        run(['docker','rm','-f',cid])
        del self.ids[cid]

    def close(self):
        for cid in list(self.ids):
            self.remove(cid)


def local_sql(cid, body, *, db='aura_recovery'):
    return run(['docker','exec','-i',cid,'psql','-h','/tmp','-U','sec0025_restore_admin','-d',db,
                '-X','-qAt','-v','ON_ERROR_STOP=1'],data=body.encode())


def source_tmpfs_file(cid,path,content):
    if path not in ('/tmp/pgpass','/tmp/database-ca.crt'):
        raise Stop('unapproved_source_tmpfs_path')
    # docker cp addresses the readonly rootfs, not this process's live tmpfs.
    # stdin writes as UID101 into its RAM mount, retaining readonly/cap limits.
    run(['docker','exec','-i',cid,'/bin/sh','-c','umask 077; cat > '+path],data=content)


def source_query(conn, body):
    with conn.cursor() as cursor:
        cursor.execute(body)
        return cursor.fetchone()[0]


def table_proof_sql(schema, name):
    return "SELECT jsonb_build_object('count',count(*),'sha256',encode(sha256(coalesce(string_agg(sha256(convert_to(to_jsonb(t)::text,'UTF8')), ''::bytea ORDER BY sha256(convert_to(to_jsonb(t)::text,'UTF8'))),''::bytea)),'hex')) FROM "+qident(schema)+'.'+qident(name)+' t;'


def collect(query):
    metadata = query((ROOT/'server/ops/sec0025/recovery_metadata.sql').read_text())
    tables = [(r['schema'],r['name']) for r in metadata['relations'] if r['kind']=='r']
    # Partitioned/inherited/foreign tables require a distinct proof plan, never skip silently.
    if any(r['kind']=='p' for r in metadata['relations']):
        raise Stop('partitioned_scope_requires_review')
    data = {s+'.'+n:query(table_proof_sql(s,n)) for s,n in tables}
    return {'metadata':metadata,'data':data,'table_names':sorted(data)}


def fk_checks(cid):
    body = """SELECT coalesce(jsonb_agg(jsonb_build_object('schema',n.nspname,'table',c.relname,'parent_schema',pn.nspname,'parent',p.relname,'match',f.confmatchtype,
'columns',(SELECT jsonb_agg(a.attname ORDER BY k.ord) FROM unnest(f.conkey) WITH ORDINALITY k(num,ord) JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=k.num),
'parent_columns',(SELECT jsonb_agg(a.attname ORDER BY k.ord) FROM unnest(f.confkey) WITH ORDINALITY k(num,ord) JOIN pg_attribute a ON a.attrelid=p.oid AND a.attnum=k.num))),'[]'::jsonb)
FROM pg_constraint f JOIN pg_class c ON c.oid=f.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
JOIN pg_class p ON p.oid=f.confrelid JOIN pg_namespace pn ON pn.oid=p.relnamespace
WHERE f.contype='f' AND n.nspname IN('public','auth','supabase_migrations');"""
    keys = json.loads(local_sql(cid,body))
    for key in keys:
        cols = ['c.'+qident(v) for v in key['columns']]
        joins = ' AND '.join('p.'+qident(v)+'='+col for col,v in zip(cols,key['parent_columns']))
        exists = 'NOT EXISTS(SELECT 1 FROM '+qident(key['parent_schema'])+'.'+qident(key['parent'])+' p WHERE '+joins+')'
        if key['match']=='f':
            condition = '('+' OR '.join(v+' IS NOT NULL' for v in cols)+') AND (('+' OR '.join(v+' IS NULL' for v in cols)+') OR '+exists+')'
        else:
            condition = ' AND '.join(v+' IS NOT NULL' for v in cols)+' AND '+exists
        check = 'SELECT NOT EXISTS(SELECT 1 FROM '+qident(key['schema'])+'.'+qident(key['table'])+' c WHERE '+condition+');'
        if local_sql(cid,check).strip() != b't':
            raise Stop('restored_foreign_key_data_incoherent')
    return len(keys)


def sequence_checks(cid):
    body = """SELECT coalesce(jsonb_agg(jsonb_build_object('schema',sn.nspname,'sequence',s.relname,'table_schema',tn.nspname,'table',t.relname,'column',a.attname,'increment',q.seqincrement,'cycle',q.seqcycle)),'[]'::jsonb)
FROM pg_class s JOIN pg_namespace sn ON sn.oid=s.relnamespace JOIN pg_sequence q ON q.seqrelid=s.oid
JOIN pg_depend d ON d.classid='pg_class'::regclass AND d.objid=s.oid AND d.deptype IN('a','i')
JOIN pg_class t ON t.oid=d.refobjid JOIN pg_namespace tn ON tn.oid=t.relnamespace
JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=d.refobjsubid
WHERE sn.nspname IN('public','auth','supabase_migrations');"""
    seqs = json.loads(local_sql(cid,body))
    for seq in seqs:
        if seq['increment'] <= 0 or seq['cycle']:
            raise Stop('nonstandard_sequence_requires_review')
        sql = 'SELECT coalesce((SELECT last_value FROM '+qident(seq['schema'])+'.'+qident(seq['sequence'])+') >= max('+qident(seq['column'])+'),true) FROM '+qident(seq['table_schema'])+'.'+qident(seq['table'])+';'
        if local_sql(cid,sql).strip() != b't':
            raise Stop('restored_sequence_unsafe')
    return len(seqs)


def role_probes(cid):
    a,b = str(uuid.uuid4()),str(uuid.uuid4())
    pa,pb = 'sec0025-'+uuid.uuid4().hex,'sec0025-'+uuid.uuid4().hex
    statements = ["BEGIN; SET LOCAL search_path=pg_catalog;",
        "INSERT INTO auth.users(id) VALUES ('"+a+"'),('"+b+"');",
        "INSERT INTO public.profiles(id) SELECT v.id::uuid FROM (VALUES ('"+a+"'),('"+b+"')) v(id) WHERE NOT EXISTS(SELECT 1 FROM public.profiles p WHERE p.id=v.id::uuid);",
        "INSERT INTO public.playlists(id,user_id,name) VALUES ('"+pa+"','"+a+"','SEC0025 synthetic local A'),('"+pb+"','"+b+"','SEC0025 synthetic local B');"]
    checks=[]
    for subject,own,foreign in [(a,pa,pb),(b,pb,pa)]:
        statements += ["SET LOCAL ROLE authenticated; SET LOCAL request.jwt.claim.sub='"+subject+"'; SET LOCAL request.jwt.claims='"+json.dumps({'sub':subject,'role':'authenticated'})+"';",
            "SELECT jsonb_build_array(EXISTS(SELECT 1 FROM public.playlists WHERE id='"+own+"'),NOT EXISTS(SELECT 1 FROM public.playlists WHERE id='"+foreign+"')); RESET ROLE;"]
    anon_select=local_sql(cid,"SELECT has_table_privilege('anon','public.playlists','SELECT');").strip()==b't'
    if anon_select:
        statements += ["SET LOCAL ROLE anon; SET LOCAL request.jwt.claim.sub=''; SET LOCAL request.jwt.claims='{}';",
            "SELECT jsonb_build_array(NOT EXISTS(SELECT 1 FROM public.playlists WHERE id IN ('"+pa+"','"+pb+"'))); RESET ROLE; ROLLBACK;"]
    else:
        statements += ["SELECT jsonb_build_array(NOT has_table_privilege('anon','public.playlists','SELECT')); ROLLBACK;"]
    for line in local_sql(cid,'\n'.join(statements)).splitlines():
        checks.extend(json.loads(line))
    if len(checks)!=5 or not all(v is True for v in checks):
        raise Stop('restored_role_policy_probes_failed')
    return True


def create_recovery_database(cid, database):
    provider=database['provider']
    if provider not in ('c','i') or database.get('icu_rules') is not None:
        raise Stop('unsupported_database_locale_requires_review')
    if database['collation_version'] != database['actual_version']:
        raise Stop('source_collation_version_mismatch')
    sql='CREATE DATABASE aura_recovery WITH TEMPLATE template0 ENCODING '+qliteral(database['encoding'])
    sql+=' LOCALE_PROVIDER '+('icu' if provider=='i' else 'libc')
    sql+=' LC_COLLATE '+qliteral(database['collate'])+' LC_CTYPE '+qliteral(database['ctype'])
    if provider=='i':
        sql+=' ICU_LOCALE '+qliteral(database['locale'])
    local_sql(cid,sql+';',db='postgres')
    # Do not force COLLATION_VERSION: compare the library's real version instead.
    actual=json.loads(local_sql(cid,DATABASE_METADATA_SQL))
    if database != actual:
        raise Stop('private_database_locale_mismatch')


DATABASE_METADATA_SQL="""SELECT jsonb_build_object('encoding',pg_encoding_to_char(encoding),
'collate',datcollate,'ctype',datctype,'provider',datlocprovider,'locale',datlocale,
'collation_version',datcollversion,'actual_version',pg_database_collation_actual_version(oid),
'icu_rules',daticurules) FROM pg_database WHERE datname=current_database();"""


def logical_columns(columns):
    # pg_dump does not recreate dropped-column attnum holes. Preserve every field
    # and the complete per-table column order; compare live ordinals, not holes.
    previous={};result=[]
    for column in columns:
        group=(column['schema'],column['table'])
        ordinal,last=previous.get(group,(0,0))
        if column['position'] <= last:
            raise Stop('unordered_or_duplicate_column_positions')
        result.append(dict(column,position=ordinal+1))
        previous[group]=(ordinal+1,column['position'])
    return result


def restore_and_check(cid, ciphertext, identity, source, roles):
    local_sql(cid,clone_roles_sql(roles),db='postgres')
    database=source['metadata']['database']
    create_recovery_database(cid,database)
    # Keep the empty initdb public schema: pg_dump stores ACL deltas against its
    # original initdb privileges, including PUBLIC USAGE. Recreating it loses that
    # baseline. Only its redundant CREATE entry is skipped; owners and ALL ACL/data
    # entries remain and are compared exactly/effectively after the restore.
    public_owner=next(v['owner'] for v in source['metadata']['schemas'] if v['name']=='public')
    local_sql(cid,'ALTER SCHEMA public OWNER TO '+qident(public_owner)+';'+extension_bootstrap_sql())
    pr,cr,pe,ce = pipe_binary([TOOLS/'age.exe','-d','-i','-',ciphertext],
        ['docker','exec','-i',cid,'/bin/sh','-c','umask 077; cat > /restore/database.dump'],producer_input=identity)
    if pr or cr or pe or ce:
        raise PrivateError('authenticated_decryption_failed',pe+b'\n'+ce)
    listing=run(['docker','exec',cid,'pg_restore','--list','/restore/database.dump']).decode()
    lines=listing.splitlines(keepends=True)
    positions=[i for i,line in enumerate(lines) if re.match(r'^\d+;\s+\d+\s+\d+\s+SCHEMA - public ',line)]
    if len(positions)!=1:
        raise Stop('unexpected_public_schema_archive_entry')
    lines[positions[0]]='; '+lines[positions[0]]
    run(['docker','exec','-i',cid,'/bin/sh','-c','umask 077; cat > /restore/restore.list'],data=''.join(lines).encode())
    run(['docker','exec',cid,'pg_restore','-h','/tmp','-U','sec0025_restore_admin','-d','aura_recovery',
         '--exit-on-error','--single-transaction','--use-list=/restore/restore.list','/restore/database.dump'])
    def query(body):
        prefix="BEGIN READ ONLY;SET LOCAL search_path=pg_catalog;SET LOCAL timezone='UTC';SET LOCAL extra_float_digits=3;"
        raw = local_sql(cid,prefix+body+'ROLLBACK;')
        return json.loads(raw)
    restored = collect(query)
    checks = {k:source['metadata'][k]==restored['metadata'][k] for k in source['metadata']}
    checks['columns']=logical_columns(source['metadata']['columns'])==logical_columns(restored['metadata']['columns'])
    checks['all_table_data'] = source['data']==restored['data']
    checks['table_set'] = source['table_names']==restored['table_names']
    if not all(checks.values()):
        raise PrivateError('restored_evidence_mismatch',json.dumps({'source':source,'restored':restored,'checks':checks}).encode())
    count_fk = fk_checks(cid);count_seq = sequence_checks(cid)
    role_probes(cid)
    # Recheck every row after rollback of the synthetic probes.
    final = collect(query)
    if restored != final:
        raise Stop('local_probe_rollback_incomplete')
    return {'checks':checks,'table_count':len(source['table_names']),'fk_checked':count_fk,
            'sequence_checked':count_seq,'owner_foreign_anon_probes':True,'probe_rollback_verified':True}


def execute(directory):
    directory = Path(directory)
    result = preflight(directory)
    cfg,manifest = load_access(directory)
    recipient = manifest['age_recipient']
    if any((directory/name).exists() for name in ('aura.dump.age','aura.dump.age.partial','evidence.json.age')):
        raise Stop('existing_backup_or_attempt_requires_review')
    report = {'format':'aura-sec0025-m2s-result-v1','source_project':manifest['project_ref'],
        'private_export_started':False,'production_written':False,'VPS_accessed':False,
        'restore_performed':False,'backup_time_utc':None,'backup_sha256':None}
    containers = Containers()
    report['attempt_id']=containers.nonce
    try:
        report['stage']='export_container_preflight'
        src = containers.start(False)
        def escape(v):
            if '\n' in v or '\r' in v:
                raise Stop('multiline_pgpass_refused')
            return v.replace('\\','\\\\').replace(':','\\:')
        passline = ':'.join(escape(str(v)) for v in (cfg['host'],5432,'postgres',cfg['user'],cfg['password']))+'\n'
        report['stage']='source_credentials_and_public_ca_in_tmpfs'
        source_tmpfs_file(src,'/tmp/pgpass',passline.encode())
        source_tmpfs_file(src,'/tmp/database-ca.crt',(directory/'database-ca.crt').read_bytes())
        env = ['-e','PGSSLMODE=verify-full','-e','PGSSLROOTCERT=/tmp/database-ca.crt',
               '-e','PGPASSFILE=/tmp/pgpass','-e','PGCONNECT_TIMEOUT=10']
        client = ['--host',cfg['host'],'--port','5432','--username',cfg['user'],'--dbname','postgres']
        report['stage']='export_client_tls_readonly_check'
        probe = run(['docker','exec',*env,'-i',src,'psql',*client,'-X','-qAt','-v','ON_ERROR_STOP=1'],
            data=b"BEGIN READ ONLY;SELECT current_database()='postgres' AND current_user='postgres' AND current_setting('transaction_read_only')='on';ROLLBACK;\n")
        if probe.strip()!=b't':
            raise Stop('export_client_tls_or_identity_failed')
        with connect_readonly(cfg) as conn:
            import psycopg
            conn.isolation_level=psycopg.IsolationLevel.REPEATABLE_READ
            with conn.cursor() as cursor:
                cursor.execute("SET LOCAL statement_timeout='60s';SET LOCAL lock_timeout='5s';SET LOCAL search_path=pg_catalog;SET LOCAL timezone='UTC';SET LOCAL extra_float_digits=3")
                report['stage']='source_locale_preflight'
                cursor.execute(DATABASE_METADATA_SQL)
                database=cursor.fetchone()[0]
                empty=containers.start(True)
                create_recovery_database(empty,database)
                containers.remove(empty)
                report['locale_preflight_verified']=True
                cursor.execute("SELECT sum(pg_total_relation_size(c.oid)) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','auth','supabase_migrations') AND c.relkind IN('r','p')")
                size=cursor.fetchone()[0] or 0
                if size>512*1024*1024 or shutil.disk_usage(directory).free < size*5+256*1024*1024:
                    raise Stop('memory_or_disk_capacity_not_sufficient')
                cursor.execute('SELECT pg_export_snapshot(),clock_timestamp()')
                snapshot,instant=cursor.fetchone()
            report['backup_time_utc']=instant.isoformat()
            report['backup_time_paris']=instant.astimezone(ZoneInfo('Europe/Paris')).isoformat()
            report['retention_until_utc']=(instant+timedelta(days=7)).isoformat()
            report['stage']='snapshot_integrity_evidence'
            source=collect(lambda sql:source_query(conn,sql))
            roles=source_query(conn,"SELECT jsonb_agg(jsonb_build_object('name',rolname,'bypass_rls',rolbypassrls) ORDER BY rolname) FROM pg_roles WHERE rolname NOT LIKE 'pg_%'")
            report['private_export_started']=True
            print(json.dumps({'phase':'snapshot_and_integrity_proof_ready','backup_time_paris':report['backup_time_paris']}),flush=True)
            report['stage']='encrypted_pg_dump_stream'
            report['backup_sha256']=export_encrypted(['docker','exec',*env,src,'pg_dump',*client,*dump_flags(snapshot)],directory/'aura.dump.age',recipient)
            private={'source_project':manifest['project_ref'],'snapshot':snapshot,'time_utc':instant.isoformat(),
                'backup_sha256':report['backup_sha256'],'source':source,'roles':roles,
                'adaptation':'private NOLOGIN owner/ACL stubs; no managed cluster membership replay'}
            report['evidence_sha256']=encrypt_bytes(json.dumps(private,ensure_ascii=False).encode(),directory/'evidence.json.age',recipient)
            conn.rollback()
        containers.remove(src)
        print(json.dumps({'phase':'encrypted_backup_sealed','backup_sha256':report['backup_sha256']}),flush=True)
        dst=containers.start(True)
        report['stage']='private_restore_and_comparison'
        identity=unprotect((directory/'age-identity.dpapi').read_bytes())
        report['restore_validation']=restore_and_check(dst,directory/'aura.dump.age',identity,source,roles)
        identity=None
        report['restore_performed']=True
        containers.remove(dst)
        report['cleanup_verified']=True
        report['verdict']='GO_M2_SQL_RECOVERY_ONLY'
        report['stage']='completed'
        report['maintenance_or_migration_authorized']=False
    except Exception as exc:
        report['verdict']='NO_GO_M2'
        report['reason']=str(exc) if isinstance(exc,Stop) else safe_error(exc)
        if isinstance(exc,PrivateError) and exc.details:
            report['encrypted_diagnostic_file']='private-diagnostic-'+containers.nonce+'.txt.age'
            report['encrypted_diagnostic_sha256']=encrypt_bytes(exc.details,directory/report['encrypted_diagnostic_file'],recipient)
    finally:
        containers.close()
        report['cleanup_verified']=not containers.ids
        report['public_receipt_file']='m2-s-result-'+containers.nonce+'.json'
        with (directory/report['public_receipt_file']).open('x',encoding='utf-8') as stream:
            json.dump(report,stream,ensure_ascii=False,indent=2);stream.write('\n');stream.flush();os.fsync(stream.fileno())
    return report


def verify_existing(directory):
    """Retry only the private restore of S; never connect to or export the source."""
    directory=Path(directory)
    _,manifest=load_access(directory)
    cipher=directory/'aura.dump.age';evidence=directory/'evidence.json.age'
    digest=digest_file(cipher);proof_digest=digest_file(evidence)
    receipts=[json.loads(p.read_text()) for p in directory.glob('m2-s-result-*.json')]
    matches=[r for r in receipts if r.get('backup_sha256')==digest and r.get('evidence_sha256')==proof_digest]
    if not matches:raise Stop('sealed_backup_receipt_missing')
    identity=unprotect((directory/'age-identity.dpapi').read_bytes())
    private=json.loads(run([TOOLS/'age.exe','-d','-i','-',evidence],data=identity))
    if private['backup_sha256']!=digest or private['source_project']!=manifest['project_ref']:
        raise Stop('sealed_backup_identity_mismatch')
    containers=Containers()
    report={k:v for k,v in matches[0].items() if k not in ('reason','encrypted_diagnostic_file','encrypted_diagnostic_sha256','public_receipt_file','attempt_id')}
    report.update(attempt_id=containers.nonce,source_contacted_this_attempt=False,
                  new_export_this_attempt=False,stage='retry_private_restore_same_sealed_backup')
    try:
        dst=containers.start(True)
        report['restore_validation']=restore_and_check(dst,cipher,identity,private['source'],private['roles'])
        report.update(restore_performed=True,verdict='GO_M2_SQL_RECOVERY_ONLY',stage='completed',maintenance_or_migration_authorized=False)
    except Exception as exc:
        report.update(verdict='NO_GO_M2',reason=str(exc) if isinstance(exc,Stop) else safe_error(exc))
        if isinstance(exc,PrivateError) and exc.details:
            report['encrypted_diagnostic_file']='private-diagnostic-'+containers.nonce+'.txt.age'
            report['encrypted_diagnostic_sha256']=encrypt_bytes(exc.details,directory/report['encrypted_diagnostic_file'],manifest['age_recipient'])
    finally:
        identity=None
        containers.close()
        report['cleanup_verified']=not containers.ids
        report['public_receipt_file']='m2-s-result-'+containers.nonce+'.json'
        with (directory/report['public_receipt_file']).open('x',encoding='utf-8') as stream:
            json.dump(report,stream,ensure_ascii=False,indent=2);stream.write('\n');stream.flush();os.fsync(stream.fileno())
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--run-directory');parser.add_argument('--execute-m2s',action='store_true')
    parser.add_argument('--restore-existing',action='store_true')
    args=parser.parse_args()
    if not args.execute_m2s and not args.restore_existing:
        print(json.dumps({'mode':'offline','credentials_read':False,'network_accessed':False,'private_export_started':False}))
    else:
        try:
            if not args.run_directory:raise Stop('run_directory_required')
            if args.execute_m2s and args.restore_existing:raise Stop('mutually_exclusive_execution_modes')
            report=verify_existing(args.run_directory) if args.restore_existing else execute(args.run_directory)
            print(json.dumps(report));sys.exit(0 if report['verdict']=='GO_M2_SQL_RECOVERY_ONLY' else 1)
        except Exception as exc:
            print(json.dumps({'verdict':'NO_GO_M2','reason':str(exc) if isinstance(exc,Stop) else safe_error(exc),
                              'details_withheld':True}));sys.exit(1)
