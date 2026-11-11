"""Validate a decrypted archive stream before isolated extraction. Aggregate output only."""
import json
from pathlib import PurePosixPath
import sys
import tarfile


def normalize(name):
    value = PurePosixPath(name)
    if value.is_absolute() or '..' in value.parts or '\\' in name or ':' in name:
        raise ValueError('Unsafe archive path')
    return value.as_posix()


def validate(stream):
    entries = {}
    total = 0
    with tarfile.open(fileobj=stream, mode='r|') as archive:
        for member in archive:
            name = normalize(member.name)
            if name in entries:
                raise ValueError('Duplicate archive path')
            if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise ValueError('Special archive entry')
            entries[name] = member
            if member.isfile():
                total += member.size
                payload = archive.extractfile(member)
                while payload.read(1024 * 1024):
                    pass
        # Consume the full age plaintext pipe, including tar padding, so age can
        # authenticate to EOF without a consumer-induced broken pipe. Reject a
        # concatenated second archive or any other non-zero trailing payload.
        while chunk := archive.fileobj.read(1024 * 1024):
            if chunk.strip(b'\0'):
                raise ValueError('Unexpected trailing archive data')
    for name, member in entries.items():
        if name == '.' and not member.isdir():
            raise ValueError('Unsafe root entry')
        for parent in PurePosixPath(name).parents:
            if parent.as_posix() in entries and not entries[parent.as_posix()].isdir():
                raise ValueError('Archive parent is not a directory')
        if member.issym():
            # Conservative: reject even internal ../ links for this restoration protocol.
            target = normalize(member.linkname)
            normalize(str(PurePosixPath(name).parent / target))
        if member.islnk():
            target = normalize(member.linkname)
            if target not in entries or not entries[target].isfile():
                raise ValueError('Hardlink target not a regular archived file')
    return dict(safe=True, entries=len(entries), logical_file_bytes=total)


if __name__ == '__main__':
    try:
        print(json.dumps(validate(sys.stdin.buffer), sort_keys=True))
    except Exception as error:
        print(json.dumps(dict(safe=False, error_type=type(error).__name__,
                              diagnostic='Archive rejected; private detail withheld')), file=sys.stderr)
        raise SystemExit(1)
