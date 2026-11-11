"""Authorized SSH stdin only: versions/presence/permissions, never secret contents."""
import json
from pathlib import Path
import shutil
import socket
import stat
import subprocess

result = {'format':'aura-sec0025-host-prerequisites-v1','remote_files_written':False}
for name in ('pg_dump','pg_dumpall','pg_restore','psql','age'):
    path = shutil.which(name)
    item = {'available': bool(path)}
    if path:
        p = subprocess.run([path,'--version'],capture_output=True,text=True,timeout=10)
        item.update({'version':p.stdout.strip()[:120],'exit_code':p.returncode})
    result[name] = item
result['protected_sql_files'] = {}
for name in ('pg_service.conf','pgpass'):
    path = Path('/root/.config/aura-sec0024')/name
    item = {'present':path.is_file()}
    if path.exists():
        s = path.lstat()
        item.update({'regular_not_symlink':stat.S_ISREG(s.st_mode),'owner_root':s.st_uid==0,
                     'mode_private':s.st_mode & 0o077 == 0})
    result['protected_sql_files'][name] = item
result['direct_db_dns_families'] = sorted({
    'ipv6' if x[0]==socket.AF_INET6 else 'ipv4' for x in socket.getaddrinfo(
        'db.neosgkuccazvgkzwzjzh.supabase.co',5432,type=socket.SOCK_STREAM)})
result['free_bytes'] = shutil.disk_usage('/root').free
print(json.dumps(result,sort_keys=True))
