"""Private, offline manifest for a stopped storage tree. Never log its contents.

manifest ROOT emits private JSON, intended only for an age encryption pipe.
verify ROOT reads decrypted private JSON on stdin; prints aggregate proof only.
No app imports, no SQL/network, no filesystem mutation. Fail closed on unsafe trees.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import sys


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def snapshot(root):
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Unsafe root')
    root = root.resolve()
    device = root.stat().st_dev
    entries, groups, links = [], {}, {}

    def visit(path):
        before = path.lstat()
        if before.st_dev != device:
            raise ValueError('Nested filesystem')
        mode = before.st_mode
        item = dict(path=path.relative_to(root).as_posix(), mode=stat.S_IMODE(mode),
                    uid=before.st_uid, gid=before.st_gid, mtime_ns=before.st_mtime_ns)
        if stat.S_ISLNK(mode):
            target = os.readlink(path)
            if os.path.isabs(target) or not (path.parent / target).resolve().is_relative_to(root):
                raise ValueError('External symlink')
            item.update(kind='symlink', target=target)
        elif stat.S_ISDIR(mode):
            item['kind'] = 'directory'
        elif stat.S_ISREG(mode):
            digest = hashlib.sha256()
            fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
            with os.fdopen(fd, 'rb') as stream:
                opened = os.fstat(stream.fileno())
                if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
                    raise ValueError('File changed while opening')
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            item.update(kind='file', size=before.st_size, sha256=digest.hexdigest())
            key = (before.st_dev, before.st_ino)
            groups.setdefault(key, []).append(item['path'])
            links[key] = before.st_nlink
        else:
            raise ValueError('Special file')
        if hasattr(os, 'listxattr'):
            item['xattrs'] = {
                name: base64.b64encode(os.getxattr(path, name, follow_symlinks=False)).decode()
                for name in sorted(os.listxattr(path, follow_symlinks=False))
            }
        after = path.lstat()
        fields = ('st_dev', 'st_ino', 'st_mode', 'st_uid', 'st_gid', 'st_size', 'st_mtime_ns', 'st_ctime_ns', 'st_nlink')
        if any(getattr(before, key) != getattr(after, key) for key in fields):
            raise ValueError('Tree changed during manifest')
        entries.append(item)
        if item['kind'] == 'directory':
            for child in sorted(path.iterdir(), key=lambda p: p.name):
                visit(child)
            final = path.lstat()
            if (final.st_mtime_ns, final.st_ctime_ns) != (before.st_mtime_ns, before.st_ctime_ns):
                raise ValueError('Directory changed during traversal')

    visit(root)
    if any(links[key] != len(names) for key, names in groups.items()):
        raise ValueError('Hardlink outside backup tree')
    return dict(format='aura-stopped-storage-v1', entries=entries,
                hardlinks=sorted(sorted(names) for names in groups.values() if len(names) > 1))


def verify(root, expected):
    observed = snapshot(root)
    if observed != expected:
        raise ValueError('Restored manifest differs; private detail withheld')
    return dict(equal=True, entries=len(observed['entries']),
                files=sum(e['kind'] == 'file' for e in observed['entries']),
                hardlink_groups=len(observed['hardlinks']),
                manifest_sha256=hashlib.sha256(encoded(observed)).hexdigest())


if __name__ == '__main__':
    try:
        if len(sys.argv) != 3 or sys.argv[1] not in ('manifest', 'verify'):
            raise ValueError('Usage: manifest|verify ROOT')
        if sys.argv[1] == 'manifest':
            sys.stdout.buffer.write(encoded(snapshot(sys.argv[2])) + b'\n')
        else:
            print(json.dumps(verify(sys.argv[2], json.load(sys.stdin)), sort_keys=True))
    except Exception as error:
        # No path, file name, xattr or decrypted record in diagnostic output.
        print(json.dumps(dict(ok=False, error_type=type(error).__name__,
                              diagnostic='Stopped-storage verification failed; private details withheld')), file=sys.stderr)
        raise SystemExit(1)
