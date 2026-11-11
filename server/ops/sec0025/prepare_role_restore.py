"""Prepare an in-memory restore wrapper; no SQL is removed from the original.

PG17 GRANTED BY requires running with the source grantor's effective authority.
The original encrypted pg_dumpall output must be kept unchanged. This wrapper is
only for a new isolated cluster after catalog validation of one superuser grantor.
"""
import hashlib


def wrap_roles(raw, *, verified_grantors, verified_superusers):
    if len(verified_grantors) != 1:
        raise ValueError('multiple_or_missing_grantors_require_review')
    grantor = next(iter(verified_grantors))
    if grantor not in ('supabase_admin', 'postgres') or grantor not in verified_superusers:
        raise ValueError('unverified_superuser_grantor')
    marker = b'-- Role memberships\n'
    if b'\r' in raw or raw.count(marker) != 1:
        raise ValueError('unexpected_pg_dumpall_layout')
    before, after = raw.split(marker)
    prepared = before + ('SET ROLE "'+grantor+'";\n').encode() + marker + after + b'\nRESET ROLE;\n'
    return prepared, {'original_roles_sha256':hashlib.sha256(raw).hexdigest(),
                      'wrapped_roles_sha256':hashlib.sha256(prepared).hexdigest(),
                      'grantor':grantor, 'sql_removed':False}
