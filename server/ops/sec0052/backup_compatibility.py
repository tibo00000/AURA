"""Metadata-only pre-M compatibility gate. Never read payloads or follow symlinks."""
import hashlib
import inspect
import json
import os
from pathlib import Path
import posixpath
import stat
import sys
from check_tar import normalize


def check_tree(root):
    root=Path(root)
    flags=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
    entries=0; links={}; inode_counts={}; failures=[]
    def reject(relative,reason):
        failures.append(dict(entry_sha256=hashlib.sha256(relative.encode()).hexdigest(),reason=reason))
    fd=os.open(root,flags)
    root_stat=os.fstat(fd); device=root_stat.st_dev
    def walk(directory,relative='.'):
        nonlocal entries
        before=os.fstat(directory)
        for name in sorted(os.listdir(directory)):
            rel=name if relative=='.' else relative+'/'+name
            item=os.stat(name,dir_fd=directory,follow_symlinks=False); entries+=1
            try: normalize(rel)
            except ValueError: reject(rel,'archive_entry_name_not_supported')
            if item.st_dev!=device:
                reject(rel,'nested_filesystem'); continue
            if stat.S_ISLNK(item.st_mode):
                target=os.readlink(name,dir_fd=directory); links[rel]=target
                lexical=posixpath.normpath(posixpath.join(posixpath.dirname(rel),target))
                if target.startswith('/') or lexical=='..' or lexical.startswith('../'):
                    reject(rel,'external_symlink')
                else:
                    try: normalize(target)
                    except ValueError: reject(rel,'symlink_target_not_supported_by_archive_validator')
            elif stat.S_ISDIR(item.st_mode):
                child=os.open(name,flags,dir_fd=directory)
                try: walk(child,rel)
                finally: os.close(child)
            elif stat.S_ISREG(item.st_mode):
                key=(item.st_dev,item.st_ino)
                row=inode_counts.setdefault(key,[0,item.st_nlink]); row[0]+=1
            else: reject(rel,'special_file_not_supported')
            after=os.stat(name,dir_fd=directory,follow_symlinks=False)
            if any(getattr(item,k)!=getattr(after,k) for k in ('st_dev','st_ino','st_mode','st_mtime_ns','st_ctime_ns','st_nlink')):
                reject(rel,'entry_changed_during_preflight')
        after=os.fstat(directory)
        if (before.st_mtime_ns,before.st_ctime_ns)!=(after.st_mtime_ns,after.st_ctime_ns):
            reject(relative,'directory_changed_during_preflight')
    try: walk(fd)
    finally: os.close(fd)
    # Resolve only the virtual in-volume link graph, never a real target outside it.
    for link in links:
        components=link.split('/'); seen=set()
        while True:
            changed=False
            for n in range(1,len(components)+1):
                prefix='/'.join(components[:n])
                if prefix not in links: continue
                if prefix in seen:
                    reject(link,'symlink_cycle'); changed=False; break
                seen.add(prefix)
                try: target=normalize(links[prefix])
                except ValueError: changed=False; break
                components=components[:n-1]+target.split('/')+components[n:]
                changed=True; break
            if not changed: break
    external_hardlink_groups=sum(observed!=expected for observed,expected in inode_counts.values())
    if external_hardlink_groups: failures.append(dict(reason='hardlinks_outside_tree',groups=external_hardlink_groups))
    counts={}
    for item in failures: counts[item['reason']]=counts.get(item['reason'],0)+1
    return dict(ok=not failures,format='sec00511-backup-compatibility-v1',entries=entries,
                symlinks=len(links),failure_counts=counts,failures=failures,
                file_payloads_read=False,symlinks_followed=False,private_paths_exposed=False)


def remote_source():
    # Local preparation only. Embed the exact frozen normalize function; do not
    # duplicate/relax the archive policy or install files on the target host.
    header='import hashlib,json,os,posixpath,stat,subprocess\nfrom pathlib import Path,PurePosixPath\n'
    program=header+inspect.getsource(normalize)+'\n'+inspect.getsource(check_tree)+'\n'
    program+="def remote_main():\n"
    program+=" d=['docker','--host','unix:///var/run/docker.sock']\n"
    program+=" info=subprocess.run(d+['info','--format','{{.ID}}'],capture_output=True,check=True)\n"
    program+=" assert info.stdout.decode().strip()=='006c91c4-e411-4e7b-95e8-af395e990ef5'\n"
    program+=" r=subprocess.run(d+['inspect','--type','container','aura-api'],capture_output=True,check=True)\n"
    program+=" c=json.loads(r.stdout)[0]; mounts=[m for m in c['Mounts'] if m['Destination']=='/app/downloads']\n"
    program+=" assert len(mounts)==1 and mounts[0]['Type']=='volume' and mounts[0]['RW'] is True\n"
    program+=" result=check_tree(mounts[0]['Source']); print(json.dumps(result,sort_keys=True)); return 0 if result['ok'] else 1\n"
    program+="try:\n raise SystemExit(remote_main())\nexcept Exception as e:\n print(json.dumps(dict(ok=False,error_type=type(e).__name__,private_details_retained=False))); raise SystemExit(1)\n"
    compile(program,'<backup-compatibility-readonly>','exec')
    return program


if __name__=='__main__':
    try:
        if sys.argv[1:]==['--emit-readonly-stdin']:
            print(remote_source(),end='')
        elif len(sys.argv)==3 and sys.argv[1]=='--root':
            report=check_tree(sys.argv[2]); print(json.dumps(report,sort_keys=True)); raise SystemExit(0 if report['ok'] else 1)
        else: raise ValueError('Use --root or --emit-readonly-stdin')
    except Exception as error:
        print(json.dumps(dict(ok=False,error_type=type(error).__name__,diagnostic='Compatibility gate incomplete; private detail withheld')))
        raise SystemExit(1)
