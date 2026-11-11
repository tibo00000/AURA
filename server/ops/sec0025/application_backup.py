"""Scope and bootstrap of an AURA SQL recovery copy. Offline: no export/connect.

Source managed cluster roles are not reinstalled. Local NOLOGIN placeholders
resolve object ownership/ACL while preserving client RLS boundaries. The source
role attributes and this declared adaptation belong in the encrypted evidence.
"""
SCHEMAS = ('public', 'auth', 'supabase_migrations')


def dump_flags(snapshot):
    import re
    if not re.fullmatch(r'[0-9A-F]+-[0-9A-F]+-[0-9]+', snapshot):
        raise ValueError('invalid_snapshot')
    return ['--no-password', '--format=custom', '--snapshot='+snapshot, '--strict-names',
            *('--schema='+s for s in SCHEMAS), '--no-publications', '--no-subscriptions']


def clone_roles_sql(roles):
    statements = []
    seen = set()
    for role in roles:
        name = role['name']
        if not isinstance(name, str) or not name or '\x00' in name or name in seen:
            raise ValueError('invalid_or_duplicate_role')
        seen.add(name)
        if name.startswith('pg_'):
            continue  # PG17 built-ins already exist in the new empty cluster.
        if name in ('sec0025_restore_admin', 'supabase_admin_bootstrap'):
            raise ValueError('bootstrap_role_collision')
        bypass = role['bypass_rls']
        if type(bypass) is not bool:
            raise ValueError('invalid_bypass_attribute')
        if name in ('anon', 'authenticated') and bypass:
            raise ValueError('unsafe_client_role')
        quoted = '"'+name.replace('"', '""')+'"'
        statements.append('CREATE ROLE '+quoted+' NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION '
                          + ('BYPASSRLS' if bypass else 'NOBYPASSRLS') + ';')
    if not {'postgres', 'anon', 'authenticated', 'service_role'} <= seen:
        raise ValueError('missing_application_roles')
    return '\n'.join(statements)+'\n'


def extension_bootstrap_sql():
    # Available source versions recorded by the authorized read-only catalog inventory.
    # No Vault/GraphQL/Realtime service or extension is reconstructed.
    return '''CREATE SCHEMA extensions;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions VERSION '1.3';
CREATE EXTENSION "uuid-ossp" WITH SCHEMA extensions VERSION '1.1';
GRANT USAGE ON SCHEMA extensions TO anon, authenticated, service_role;
'''
