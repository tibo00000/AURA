"""SEC-002: real PostgreSQL tests, local Docker only; no remote credentials or ports."""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "supabase/migrations/20261017200822_secure_user_data_isolation.sql"
RESTART_PROVIDER_TRACK = "track:deezer:900000000001"


def run(args, *, timeout=120, **kwargs):
    result = subprocess.run(args, capture_output=True, timeout=timeout, **kwargs)
    if result.returncode:
        # All data in these containers is synthetic; no production env/files mounted.
        raise RuntimeError(result.stderr.decode(errors="replace")[-4000:])
    return result.stdout


def verify_playlist_supabase_http(container, nonce, sql, backend_image):
    """Share the isolated PostgreSQL loopback namespace with the HTTP services."""
    jwt_secret = secrets.token_urlsafe(48)
    issuer = "https://localhost:8443/auth/v1"
    sql("""
CREATE ROLE authenticator LOGIN NOINHERIT;
GRANT anon, authenticated, service_role TO authenticator;
CREATE ROLE supabase_auth_admin LOGIN NOINHERIT CREATEROLE;
ALTER ROLE supabase_auth_admin SET search_path = auth;
GRANT CREATE ON DATABASE postgres TO supabase_auth_admin;
ALTER SCHEMA auth OWNER TO supabase_auth_admin;
ALTER FUNCTION auth.uid() OWNER TO supabase_auth_admin;
""")
    sidecars = []
    common = ["docker", "run", "--pull", "never", "--network", "container:" + container,
              "--label", "aura.sec002=" + nonce]

    def docker_env(values):
        options = [arg for key in values for arg in ("-e", key)]
        return options, {**os.environ, **values}

    def start(image, values, *, options=(), command=()):
        flags, env = docker_env(values)
        service = run([*common, "-d", *flags, *options, image, *command], env=env).decode().strip()
        assert re.fullmatch(r"[0-9a-f]{64}", service)
        sidecars.append(service)
        return service

    try:
        start("supabase/gotrue:v2.197.0", {
            "API_EXTERNAL_URL": issuer, "GOTRUE_SITE_URL": "https://localhost:8443",
            "GOTRUE_API_HOST": "127.0.0.1", "GOTRUE_API_PORT": "9999",
            "GOTRUE_DB_DRIVER": "postgres",
            "GOTRUE_DB_DATABASE_URL": "postgres://supabase_auth_admin@127.0.0.1:5432/postgres?sslmode=disable",
            "GOTRUE_JWT_SECRET": jwt_secret, "GOTRUE_JWT_ISSUER": issuer,
            "GOTRUE_JWT_AUD": "authenticated", "GOTRUE_JWT_DEFAULT_GROUP_NAME": "authenticated",
            "GOTRUE_EXTERNAL_EMAIL_ENABLED": "true", "GOTRUE_MAILER_AUTOCONFIRM": "true",
        })
        start("postgrest/postgrest:v14.18", {
            "PGRST_DB_URI": "postgres://authenticator@127.0.0.1:5432/postgres?sslmode=disable",
            "PGRST_DB_SCHEMAS": "public", "PGRST_DB_ANON_ROLE": "anon", "PGRST_JWT_SECRET": jwt_secret,
            "PGRST_SERVER_HOST": "127.0.0.1",
        })
        gateway = start("caddy:2.8-alpine", {"AURA_FIXTURE_CADDYFILE": """{
    admin off
    auto_https disable_redirects
    servers {
        protocols h1
    }
}
https://localhost:8443 {
    bind 127.0.0.1
    tls internal
    handle_path /auth/v1/* {
        reverse_proxy 127.0.0.1:9999
    }
    handle_path /rest/v1/* {
        reverse_proxy 127.0.0.1:3000
    }
}
"""}, options=["--entrypoint", "/bin/sh"], command=[
            "-ec", "printf '%s\\n' \"$AURA_FIXTURE_CADDYFILE\" > /tmp/Caddyfile; exec caddy run --config /tmp/Caddyfile",
        ])
        for _ in range(40):
            ca = subprocess.run(["docker", "exec", gateway, "cat", "/data/caddy/pki/authorities/local/root.crt"],
                                capture_output=True, timeout=10)
            if ca.returncode == 0:
                break
            time.sleep(0.25)
        else:
            raise RuntimeError("Local TLS gateway CA did not become ready")
        flags, env = docker_env({"SUPABASE_JWT_SECRET": jwt_secret})
        result = run([*common, "--rm", "-i", *flags, "--platform", "linux/amd64",
                      "--mount", f"type=bind,source={ROOT / 'server/app'},target=/repo/app,readonly",
                      "--mount", f"type=bind,source={ROOT / 'server/scripts/playlist_supabase_http_fixture.py'},target=/fixture.py,readonly",
                      "--mount", f"type=bind,source={ROOT / 'server/scripts/playlist_worker_restart_fixture.py'},target=/playlist_worker_restart_fixture.py,readonly",
                      "--workdir", "/tmp", "-e", "PYTHONPATH=/repo", "--entrypoint", "python",
                      backend_image, "-B", "/fixture.py"],
                     env=env, input=ca.stdout, timeout=180)
        return json.loads(result)
    except RuntimeError:
        for service in sidecars[:2]:
            diagnostics = subprocess.run(["docker", "logs", "--tail", "8", service], capture_output=True, timeout=10)
            output = (diagnostics.stdout + diagnostics.stderr).decode(errors="replace")
            print(output.replace(jwt_secret, "[fixture secret]"), file=sys.stderr)
        raise
    finally:
        for service in reversed(sidecars):
            inspected = subprocess.run(["docker", "inspect", service], capture_output=True, timeout=10)
            if inspected.returncode == 0:
                labels = json.loads(inspected.stdout)[0]["Config"]["Labels"]
                assert labels.get("aura.sec002") == nonce
                run(["docker", "rm", "-f", service])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="postgres:16-alpine")
    parser.add_argument("--download-cancellation", action="store_true",
                        help="Also execute the local SEC-003 download CAS/rollback assertions")
    parser.add_argument("--playlist-sync", action="store_true",
                        help="Also apply and verify the playlist candidate on the disposable database")
    parser.add_argument("--playlist-worker-restart", action="store_true",
                        help="Run the playlist worker across a process crash with local backend dependencies")
    parser.add_argument("--playlist-supabase-http", action="store_true",
                        help="Verify the user API via real Supabase Auth and PostgREST on isolated loopback")
    parser.add_argument("--playlist-backend-image", default="aura-security-lot5:sec0052-20261023-amd64",
                        help="Local Linux amd64 image with Aura backend Python dependencies")
    args = parser.parse_args()
    if args.playlist_worker_restart and not args.playlist_sync:
        parser.error("--playlist-worker-restart requires --playlist-sync")
    if args.playlist_supabase_http and not args.playlist_sync:
        parser.error("--playlist-supabase-http requires --playlist-sync")
    endpoint = run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]).decode().strip()
    if not endpoint.startswith(("npipe://", "unix://")) or os.environ.get("DOCKER_HOST", endpoint) != endpoint:
        raise RuntimeError("A local Docker socket is required; remote Docker is forbidden")
    nonce = uuid.uuid4().hex
    container = run(["docker", "run", "--rm", "-d", "--network", "none", "--label", "aura.sec002="+nonce,
                     "--tmpfs", "/var/lib/postgresql/data:rw", "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
                     args.image, "-c", "listen_addresses=" + ("127.0.0.1" if args.playlist_supabase_http else "")]).decode().strip()
    assert re.fullmatch(r"[0-9a-f]{64}", container)
    try:
        for _ in range(50):
            ready = subprocess.run(["docker","exec",container,"pg_isready","-U","postgres"], capture_output=True)
            if ready.returncode == 0:
                # The entrypoint briefly starts a separate server to initialize the data directory.
                logs = run(["docker", "logs", container])
                if b"PostgreSQL init process complete; ready for start up" in logs:
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
        playlist_scenarios = []
        if args.playlist_sync:
            playlist_migrations = (
                "20261114120903_playlist_sync_snapshot.sql",
                "20261114122131_playlist_sync_mutations.sql",
                "20261114125746_playlist_source_association.sql",
                "20261114181513_playlist_acquisition_intents.sql",
                "20261114181653_playlist_refresh_and_exclusions.sql",
                "20261114183022_playlist_acquisition_leases.sql",
                "20261114184616_playlist_playback_occurrence.sql",
                "20261116223507_playlist_confirmed_exclusions.sql",
                "20261117115916_playlist_linked_audio.sql",
                "20261119005133_playlist_confirmed_occurrence_reuse.sql",
            )
            sql((ROOT/"server/app/db/migrations/005_add_download_job_operation_type.sql").read_text(encoding="utf-8-sig"))
            for filename in playlist_migrations:
                sql((ROOT/"supabase/migrations"/filename).read_text(encoding="utf-8-sig"))
            for test in sorted((ROOT/"supabase/tests").glob("playlist_*.sql")):
                sql(test.read_text(encoding="utf-8-sig"))
                playlist_scenarios.append(test.name)
            sql((ROOT/"supabase/tests/sec002_user_isolation.sql").read_text())
            if args.download_cancellation:
                sql((ROOT/"supabase/tests/sec003_download_cancellation.sql").read_text())
            restart_track_id = "trk_" + base64.urlsafe_b64encode(f"v1:{RESTART_PROVIDER_TRACK}".encode()).decode().rstrip("=")
            sql(f"""
INSERT INTO public.profiles(id) VALUES ('c0000000-0000-0000-0000-000000000061');
INSERT INTO public.playlists(id,user_id,name)
VALUES ('restart-fixture','c0000000-0000-0000-0000-000000000061','Mix');
INSERT INTO public.playlist_items(id,playlist_id,track_id,position) VALUES
('restart-entry','restart-fixture','{restart_track_id}',0),
('repeated-entry','restart-fixture','{restart_track_id}',2);
""")
            inventory = (ROOT/"supabase/sql/playlist_sync_inventory.sql").read_text(encoding="utf-8-sig")
            result = sql("\\set owner_id 'c0000000-0000-0000-0000-000000000061'\n" + inventory).stdout.decode()
            assert "restart-fixture|2|1|1|0|0" in result.splitlines(), result
            if args.playlist_worker_restart:
                sql("""
BEGIN;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','c0000000-0000-0000-0000-000000000061',true);
SELECT public.aura_get_playlist_acquisitions('restart-fixture', true);
COMMIT;
UPDATE public.playlist_acquisition_intents
SET metadata = '{"title":"Fixture title","artist":"Fixture artist","album":"Fixture album","duration_ms":100}';
""")
                with tempfile.TemporaryDirectory(prefix="aura-playlist-worker-") as workspace:
                    env = {
                        **os.environ, "PYTHONPATH": str(ROOT/"server"), "APP_ENV": "test",
                        "SUPABASE_URL": "https://synthetic.supabase.invalid",
                        "SUPABASE_ANON_KEY": "SYNTHETIC_PUBLIC", "SUPABASE_SERVICE_ROLE_KEY": "SYNTHETIC_ADMIN",
                        "SUPABASE_JWT_SECRET": secrets.token_urlsafe(48),
                        "ENABLE_STARTUP_RECOVERY": "false", "ENABLE_CACHE_BACKFILL": "false",
                        "DOWNLOADS_DIR": str(Path(workspace)/"downloads"), "SYNC_FILES_DIR": str(Path(workspace)/"sync"),
                    }
                    command = [sys.executable, "-B", str(ROOT/"server/scripts/playlist_worker_restart_fixture.py"),
                               container, restart_track_id, RESTART_PROVIDER_TRACK]
                    first = subprocess.run([*command, "interrupt"], cwd=workspace, env=env, timeout=90)
                    assert first.returncode == 42, first.returncode
                    original_job = sql("""
SELECT j.id FROM public.download_jobs j
JOIN public.playlist_acquisition_intents i ON i.job_id = j.id AND i.reserved_job_id = j.id
WHERE j.playlist_acquisition AND j.status = 'running' AND j.attempt_count = 1;
""").stdout.decode().strip()
                    assert original_job.startswith("job_"), original_job
                    sql("UPDATE public.playlist_acquisition_intents SET lease_until = CASE WHEN lease_token IS NOT NULL THEN now() - interval '1 second' END, next_attempt_at = now();")
                    subprocess.run([*command, "resume"], cwd=workspace, env=env, timeout=90, check=True)
                    final_job = sql("""
SELECT j.id FROM public.download_jobs j
JOIN public.playlist_acquisition_intents i ON i.job_id = j.id
WHERE i.status = 'available' AND j.status = 'succeeded' AND j.attempt_count = 2;
SELECT count(*) FROM public.download_jobs;
""").stdout.decode().splitlines()
                    assert final_job == [original_job, "1"], final_job
        supabase_http = verify_playlist_supabase_http(
            container, nonce, sql, args.playlist_backend_image,
        ) if args.playlist_supabase_http else None
        info = json.loads(run(["docker","inspect",container]))[0]
        assert not info["HostConfig"]["PortBindings"] and info["HostConfig"]["NetworkMode"] == "none"
        version = sql("SHOW server_version").stdout.decode().strip()
        print(json.dumps({"postgres_version":version,"sql_assertions_passed":count,"download_cancellation_assertions_passed":download_count,"preflight_rollback_checks_passed":3,
                          "playlist_scenarios":playlist_scenarios,
                          "playlist_inventory_fixture_checked":args.playlist_sync,
                          "playlist_worker_restart_checked":args.playlist_worker_restart,
                          "playlist_supabase_http":supabase_http,
                          "failed":0,"skipped":0,"network":"none","ports_published":False,
                          "auth_uid_source":"synthetic local claim setting for SQL-only tests",
                          "container_cleanup":"finally"}))
    finally:
        # Remove only the newly created container with the exact per-run ownership label.
        info = json.loads(run(["docker","inspect",container]))[0]
        assert info["Config"]["Labels"].get("aura.sec002") == nonce
        run(["docker","rm","-f",container])


if __name__ == "__main__":
    main()
