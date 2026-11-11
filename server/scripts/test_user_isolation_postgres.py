"""SEC-002: real PostgreSQL tests, local Docker only; no remote credentials or ports."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "supabase/migrations/20261017200822_secure_user_data_isolation.sql"


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, timeout=120, **kwargs)
    if result.returncode:
        # All data in these containers is synthetic; no production env/files mounted.
        raise RuntimeError(result.stderr.decode(errors="replace")[-4000:])
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="postgres:16-alpine")
    parser.add_argument("--download-cancellation", action="store_true",
                        help="Also execute the local SEC-003 download CAS/rollback assertions")
    args = parser.parse_args()
    endpoint = run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]).decode().strip()
    if not endpoint.startswith(("npipe://", "unix://")) or os.environ.get("DOCKER_HOST", endpoint) != endpoint:
        raise RuntimeError("A local Docker socket is required; remote Docker is forbidden")
    nonce = uuid.uuid4().hex
    container = run(["docker", "run", "--rm", "-d", "--network", "none", "--label", "aura.sec002="+nonce,
                     "--tmpfs", "/var/lib/postgresql/data:rw", "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
                     args.image, "-c", "listen_addresses="]).decode().strip()
    assert re.fullmatch(r"[0-9a-f]{64}", container)
    try:
        for _ in range(50):
            ready = subprocess.run(["docker","exec",container,"pg_isready","-U","postgres"], capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(0.2)
        else:
            raise RuntimeError("Postgres did not become ready")

        def sql(body, database="postgres", check=True):
            proc = subprocess.run(["docker","exec","-i",container,"psql","-X","-At","-v","ON_ERROR_STOP=1","-U","postgres","-d",database],
                                  input=body.encode(), capture_output=True, timeout=90)
            if check and proc.returncode:
                raise RuntimeError(proc.stderr.decode(errors="replace")[-5000:])
            return proc

        sql("CREATE ROLE authenticated NOLOGIN NOBYPASSRLS; CREATE ROLE anon NOLOGIN NOBYPASSRLS; CREATE ROLE service_role NOLOGIN BYPASSRLS;")
        setup = """
CREATE SCHEMA auth;
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS
$$SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
GRANT USAGE ON SCHEMA public,auth TO anon,authenticated,service_role;
"""
        for file in ("server/app/db/migrations/004_create_sync_tables.sql", "server/app/db/migrations/003_create_download_jobs.sql",
                     "server/app/db/migrations/001_create_catalog_tables.sql", "server/app/db/migrations/002_create_mapping_tables.sql",
                     "server/migrations/001_enable_rls_policies.sql"):
            setup += (ROOT/file).read_text(encoding="utf-8-sig") + "\n"
        setup += """
CREATE TABLE public.user_track_stats(id text PRIMARY KEY,user_id uuid NOT NULL);
CREATE TABLE public.recent_searches(id text PRIMARY KEY,user_id uuid NOT NULL);
GRANT ALL ON ALL TABLES IN SCHEMA public TO anon,authenticated,service_role;
"""
        setup += (ROOT/"supabase/migrations/20261016214500_enable_catalog_rls.sql").read_text(encoding="utf-8-sig")
        sql(setup)
        # Migration rollback on corrupt legacy rows: no automatic deletion/reassignment.
        sql("CREATE DATABASE sec002_corrupt")
        sql(setup,"sec002_corrupt")
        sql("""INSERT INTO public.profiles(id) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
INSERT INTO public.listening_sessions(id,user_id) VALUES('sb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
INSERT INTO public.history_items(id,user_id,track_id,listening_session_id) VALUES('bad','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','synthetic','sb');""", "sec002_corrupt")
        failed = sql(MIGRATION.read_text(), "sec002_corrupt", check=False)
        assert failed.returncode != 0 and b"SEC-002 preflight failed" in failed.stderr
        remaining = sql("SELECT count(*) FROM public.history_items WHERE id='bad'; SELECT count(*) FROM pg_policies WHERE policyname LIKE 'sec002_%';", "sec002_corrupt").stdout.decode().splitlines()
        assert remaining == ["1","0"]
        # Exposed privileged functions block the migration for explicit review.
        sql("CREATE DATABASE sec002_privileged")
        sql(setup,"sec002_privileged")
        sql("CREATE FUNCTION public.synthetic_privileged() RETURNS integer LANGUAGE sql SECURITY DEFINER AS $$SELECT 1$$;", "sec002_privileged")
        failed = sql(MIGRATION.read_text(),"sec002_privileged",check=False)
        assert failed.returncode != 0 and b"privileged code requires review" in failed.stderr
        sql("ALTER ROLE authenticated BYPASSRLS")
        failed = sql(MIGRATION.read_text(),check=False)
        assert failed.returncode != 0 and b"unsafe client role privileges" in failed.stderr
        sql("ALTER ROLE authenticated NOBYPASSRLS")
        sql(MIGRATION.read_text())
        output = sql((ROOT/"supabase/tests/sec002_user_isolation.sql").read_text()).stdout.decode()
        count = int(re.search(r"SEC002_SQL_PASSED=(\d+)",output).group(1))
        download_count = 0
        if args.download_cancellation:
            extra = sql((ROOT/"supabase/tests/sec003_download_cancellation.sql").read_text()).stdout.decode()
            download_count = int(re.search(r"SEC003_SQL_PASSED=(\d+)",extra).group(1))
        info = json.loads(run(["docker","inspect",container]))[0]
        assert not info["HostConfig"]["PortBindings"] and info["HostConfig"]["NetworkMode"] == "none"
        version = sql("SHOW server_version").stdout.decode().strip()
        print(json.dumps({"postgres_version":version,"sql_assertions_passed":count,"download_cancellation_assertions_passed":download_count,"preflight_rollback_checks_passed":3,
                          "failed":0,"skipped":0,"network":"none","ports_published":False,
                          "auth_uid_source":"synthetic local claim setting, not Supabase Auth/PostgREST","container_cleanup":"finally"}))
    finally:
        # Remove only the newly created container with the exact per-run ownership label.
        info = json.loads(run(["docker","inspect",container]))[0]
        assert info["Config"]["Labels"].get("aura.sec002") == nonce
        run(["docker","rm","-f",container])


if __name__ == "__main__":
    main()
