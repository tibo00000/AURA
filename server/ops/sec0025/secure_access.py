"""Windows DPAPI access and READ ONLY M2-S preflight. No private-row export.

Never print an exception, DSN, credential dict, age identity, or process stderr.
Default CLI performs no file/credential read or network access.
"""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[3]
BASE = Path('C:/Users/thiba/AURA-private-recovery/SEC-002.5')
REFERENCE = 'neosgkuccazvgkzwzjzh'
ENTROPY = b'AURA-SEC0025-M2S-v1'
TOOLS = ROOT/'scratch/sec0025/tools/age/age'


class Stop(Exception):
    pass


def guard_access(directory):
    directory = Path(directory)
    if directory.parent != BASE or not re.fullmatch(r'm2s-\d{8}T\d{6}Z-[a-f0-9]{8}', directory.name):
        raise Stop('unapproved_storage')
    if directory.resolve() != directory.absolute():
        raise Stop('storage_redirection')
    script = r'''param([string]$Target)
$ErrorActionPreference='Stop'
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$paths=@((Split-Path -Parent $Target),$Target)
foreach($name in @('connection.dpapi','age-identity.dpapi','database-ca.crt','access-manifest.json')){$paths+=Join-Path $Target $name}
foreach($p in $paths){
 $item=Get-Item -LiteralPath $p
 if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint)-ne 0){exit 2}
 $acl=if($item.PSIsContainer){[IO.Directory]::GetAccessControl($p)}else{[IO.File]::GetAccessControl($p)}
 if($item.PSIsContainer -and -not $acl.AreAccessRulesProtected){exit 3}
 foreach($a in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){
  if($a.IdentityReference.Value -notin @($sid,'S-1-5-18')){exit 4}
 }
}
Write-Output 'private_acl_ok'
'''
    # Only a validated local path is embedded; never credentials.
    command = '& { '+script+' } -Target '+"'"+str(directory)+"'"
    result = subprocess.run(['powershell.exe','-NoProfile','-Command',command],capture_output=True,timeout=30)
    if result.returncode or result.stdout.decode(errors='ignore').strip() != 'private_acl_ok':
        raise Stop('storage_acl_or_shape_failed')
    manifest = json.loads((directory/'access-manifest.json').read_text(encoding='utf-8-sig'))
    if manifest.get('project_ref') != REFERENCE or manifest.get('format') != 'aura-sec0025-access-v1':
        raise Stop('manifest_target_mismatch')
    if not all(manifest.get(k) is True for k in ('key_backup_confirmed_by_operator',
        'key_backup_reentry_matches','key_backup_independent_confirmed')):
        raise Stop('independent_key_backup_unconfirmed')
    return manifest


def unprotect(raw):
    if sys.platform != 'win32':
        raise Stop('windows_dpapi_required')
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]
    private_buffer = ctypes.create_string_buffer(raw)
    entropy_buffer = ctypes.create_string_buffer(ENTROPY)
    source = Blob(len(raw),ctypes.cast(private_buffer,ctypes.POINTER(ctypes.c_ubyte)))
    entropy = Blob(len(ENTROPY),ctypes.cast(entropy_buffer,ctypes.POINTER(ctypes.c_ubyte)))
    output = Blob()
    crypt = ctypes.WinDLL('crypt32',use_last_error=True)
    crypt.CryptUnprotectData.argtypes = [ctypes.POINTER(Blob),ctypes.c_void_p,
        ctypes.POINTER(Blob),ctypes.c_void_p,ctypes.c_void_p,wintypes.DWORD,ctypes.POINTER(Blob)]
    crypt.CryptUnprotectData.restype = wintypes.BOOL
    kernel = ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    if not crypt.CryptUnprotectData(ctypes.byref(source),None,ctypes.byref(entropy),None,None,1,ctypes.byref(output)):
        raise Stop('dpapi_unprotect_failed')
    try:
        return ctypes.string_at(output.data,output.size)
    finally:
        ctypes.memset(output.data,0,output.size)
        kernel.LocalFree(output.data)


def verify_age_tools():
    archive = ROOT/'scratch/sec0025/tools/age-v1.3.2-windows-arm64.zip'
    if hashlib.sha256(archive.read_bytes()).hexdigest() != 'fae351336c8d5f30f93cc3ccdec16d1ed7851d68cdd6656d41955cc31a447c9d':
        raise Stop('age_archive_mismatch')
    with zipfile.ZipFile(archive) as bundle:
        for name in ('age.exe','age-keygen.exe'):
            if hashlib.sha256(bundle.read('age/'+name)).digest() != hashlib.sha256((TOOLS/name).read_bytes()).digest():
                raise Stop('age_binary_mismatch')


def load_access(directory):
    directory = Path(directory)
    manifest = guard_access(directory)
    verify_age_tools()
    cfg = json.loads(unprotect((directory/'connection.dpapi').read_bytes()))
    direct = cfg.get('host') == 'db.'+REFERENCE+'.supabase.co' and cfg.get('user') == 'postgres'
    pool = bool(re.fullmatch(r'aws-\d+-[a-z0-9-]+\.pooler\.supabase\.com',cfg.get('host',''))) and cfg.get('user') == 'postgres.'+REFERENCE
    if not (direct or pool) or cfg.get('project_ref') != REFERENCE or cfg.get('dbname') != 'postgres' or cfg.get('port') != 5432:
        raise Stop('sql_target_mismatch')
    if cfg.get('sslmode') != 'verify-full' or Path(cfg.get('sslrootcert','')) != directory/'database-ca.crt' or not cfg.get('password'):
        raise Stop('sql_tls_configuration_incomplete')
    identity = unprotect((directory/'age-identity.dpapi').read_bytes())
    derived = subprocess.run([str(TOOLS/'age-keygen.exe'),'-y'],input=identity,capture_output=True,timeout=10)
    if derived.returncode or derived.stdout.decode().strip() != manifest.get('age_recipient'):
        raise Stop('age_identity_recipient_mismatch')
    return cfg, manifest


def connect_readonly(cfg):
    import psycopg
    conn = psycopg.connect(host=cfg['host'],port=5432,dbname='postgres',user=cfg['user'],
        password=cfg['password'],sslmode='verify-full',sslrootcert=cfg['sslrootcert'],
        connect_timeout=10,application_name='aura-sec0025-m2s-readonly',
        options='-c default_transaction_read_only=on -c statement_timeout=20000 -c lock_timeout=3000 -c timezone=UTC')
    # The session pooler may ignore startup options. Enforce BEGIN READ ONLY via
    # libpq client's transaction characteristics rather than trusting defaults.
    conn.read_only = True
    return conn


def preflight(directory):
    cfg, manifest = load_access(directory)
    report = {'format':'aura-sec0025-readonly-connection-v1','private_rows_read':False,
        'backup_snapshot_exported':False,'private_export_started':False,
        'storage_acl_checked':True,'key_matches_recipient':True,'key_backup_operator_attested':True}
    with connect_readonly(cfg) as conn:
        report['tls_verify_full'] = bool(conn.pgconn.ssl_in_use)
        with conn.cursor() as cur:
            cur.execute("SELECT current_database(),current_user,current_setting('server_version'),current_setting('transaction_read_only')")
            database, role, version, readonly = cur.fetchone()
            if database != 'postgres' or role != 'postgres' or readonly != 'on' or not version.startswith('17.') or not report['tls_verify_full']:
                raise Stop('connection_identity_or_readonly_failed')
            report.update({'database_matches':True,'role_matches':True,'server_version':version,'transaction_read_only':True})
            cur.execute('''SELECT n.nspname,
count(*) FILTER(WHERE c.relkind IN('r','p')) AS tables,
count(*) FILTER(WHERE c.relkind IN('r','p') AND NOT has_table_privilege(c.oid,'SELECT')) AS missing_select,
count(*) FILTER(WHERE CASE WHEN c.relkind IN('r','p') THEN row_security_active(c.oid) ELSE false END) AS active_rls,
count(*) FILTER(WHERE CASE WHEN c.relkind='S' THEN NOT has_sequence_privilege(c.oid,'SELECT') ELSE false END) AS unreadable_sequences
FROM pg_namespace n LEFT JOIN pg_class c ON c.relnamespace=n.oid
WHERE n.nspname IN('public','auth','supabase_migrations') GROUP BY n.nspname ORDER BY n.nspname''')
            rows = cur.fetchall()
            if {r[0] for r in rows} != {'public','auth','supabase_migrations'} or any(any(v for v in r[2:]) for r in rows):
                raise Stop('incomplete_or_rls_filtered_read_permissions')
            report['schemas'] = [{'name':r[0],'tables':r[1],'missing_select':r[2],'active_rls':r[3],'unreadable_sequences':r[4]} for r in rows]
        conn.rollback()
    report['verdict'] = 'PASS_READONLY_CONNECTION_PREFLIGHT'
    return report


def safe_error(exc):
    if isinstance(exc,Stop):
        return str(exc)
    if isinstance(exc,AttributeError):
        return 'local_library_api_error'
    text = str(exc).lower()  # Classify in memory, never return the raw libpq message.
    if 'certificate verify failed' in text or 'certificate verification failed' in text:
        return 'tls_verification_failed'
    if 'root certificate' in text or 'no certificate or crl found' in text:
        return 'tls_root_certificate_unreadable_or_invalid'
    if 'ssl' in text:
        return 'tls_connection_failed'
    if 'password authentication' in text or getattr(exc,'sqlstate',None) == '28P01':
        return 'database_authentication_failed'
    if 'timeout' in text or 'network is unreachable' in text or 'could not translate host' in text:
        return 'database_endpoint_unreachable'
    return 'preflight_failed_details_withheld'


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-directory')
    parser.add_argument('--execute-readonly',action='store_true')
    args = parser.parse_args()
    if not args.execute_readonly:
        print(json.dumps({'mode':'offline','credentials_read':False,'network_accessed':False}))
    else:
        try:
            if not args.run_directory:
                raise Stop('run_directory_required')
            print(json.dumps(preflight(args.run_directory)))
        except Exception as exc:
            print(json.dumps({'verdict':'NO_GO','stage':'before_private_export','reason':safe_error(exc),
                              'private_export_started':False,'backup_snapshot_exported':False}))
            sys.exit(1)
