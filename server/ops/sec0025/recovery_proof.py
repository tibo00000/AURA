"""Evidence comparison only. No network, credentials, export, decryption or writes.

Detailed evidence is PRIVATE and must be encrypted by the authorized coordinator.
Only the returned receipt is intended for an expurgated report. This is not an
attestation: the operator must establish how both evidence files were produced.
SEC0025 simplified scope: roles/extension evidence means required application
permissions/dependencies, not a reconstruction of Supabase cluster internals.
"""
import hashlib
import json
import re

IMAGE = 'supabase/postgres@sha256:c54d587019323cb7a4264dca3148888fb8697c6b950d82638f64bb952fac8b00'
SOURCE = 'neosgkuccazvgkzwzjzh'
CATEGORIES = ('data', 'schema', 'rls', 'constraints', 'roles', 'privileges', 'extensions')


def fingerprint_rows(rows):
    """Synthetic test helper: canonical rows as supplied by PostgreSQL to_jsonb.

    Production coordinator must hash PostgreSQL UTF8 to_jsonb(t)::text per row,
    sort fixed-length digests with COLLATE C and hash their concatenation. Never
    print or persist these unencrypted. Client JSON encoders are not interchangeable.
    """
    h = hashlib.sha256()
    for digest in sorted(hashlib.sha256(row).digest() for row in rows):
        h.update(digest)
    return {'count': len(rows), 'sha256': h.hexdigest()}


def compare(source, restored, *, archive_sha256):
    """Fail closed; emit no IDs, SQL, row fingerprints or detailed differences."""
    if not re.fullmatch(r'[a-f0-9]{64}', archive_sha256):
        raise ValueError('invalid_archive_identity')
    checks = {
        'format': source.get('format') == restored.get('format') == 'aura-sec0025-evidence-v1',
        'source': source.get('source') == restored.get('source') == SOURCE,
        'archive_bound': source.get('archive_sha256') == restored.get('archive_sha256') == archive_sha256,
        'snapshot_bound': bool(source.get('snapshot')) and source.get('snapshot') == restored.get('snapshot'),
        'scope': bool(source.get('table_names')) and source.get('table_names') == restored.get('table_names'),
        'complete': source.get('complete') is True and restored.get('complete') is True,
        'authenticated_decryption': restored.get('age_exit_code') == 0,
        'restore_success': restored.get('roles_exit_code') == restored.get('restore_exit_code') == 0,
        'private_target': restored.get('network_mode') == 'none' and restored.get('ports') == []
            and restored.get('host_mounts') == [] and restored.get('swap_disabled') is True,
        'exceptions': source.get('unresolved') == restored.get('unresolved') == [],
        'sequences': restored.get('sequence_check_passed') is True,
        'local_role_probes': restored.get('role_probes_passed') is True,
    }
    for name in CATEGORIES:
        checks[name] = name in source and name in restored and source[name] == restored[name]
    return {'format': 'aura-sec0025-receipt-v1', 'archive_sha256': archive_sha256,
            'checks': checks, 'verdict': 'PASS_SQL_RECOVERY' if all(checks.values()) else 'NO_GO',
            'production_maintenance_authorized': False, 'private_values_in_report': False}


if __name__ == '__main__':
    # Intentionally no execution mode. Private files may only be handled after M2 approval.
    print(json.dumps({'mode': 'offline_preparation', 'private_files_read': False,
                      'network_accessed': False, 'production_written': False,
                      'verdict': 'NO_GO_PENDING_AUTHORIZED_REAL_RESTORE'}))
