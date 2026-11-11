"""Execute the staged setup on local disposable PostgreSQL, never on Supabase."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess

from test_sec002_operational import postgres

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("prepared_env",ROOT/"server/scripts/prepare_sec002_environment.py")
prepare=importlib.util.module_from_spec(spec);spec.loader.exec_module(prepare)


def test_staged_schema_migration_fixtures_and_replay_stop(postgres):
    postgres.sql("CREATE DATABASE sec0023_preparation;")
    def sql(body,ok=True):
        result=subprocess.run(["docker","exec","-i",postgres.cid,"psql","-X","-qAt","-v","ON_ERROR_STOP=1",
                              "-U","postgres","-d","sec0023_preparation"],input=body.encode(),capture_output=True,timeout=60)
        if ok:assert result.returncode == 0,result.stderr.decode()[-2000:]
        return result
    sql("""CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS
$$SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
GRANT USAGE ON SCHEMA public,auth TO anon,authenticated,service_role;""")
    bundle=prepare.sql_bundle()
    for name,body in bundle.items():
        result=sql(body.decode("utf-8-sig"))
        if name == "05-verify.sql":
            verified=json.loads(result.stdout)
            assert verified["owner_guards"] == verified["private_rls"] == 11
            assert verified["rpc_invoker"] and verified["rpc_auth"] and not verified["rpc_anon"]
    assert sql("SELECT count(*) FROM public.tracks WHERE id='sec0023-track';").stdout.strip() == b"1"
    replay=sql(bundle["01-schema.sql"].decode(),ok=False)
    assert replay.returncode != 0 and b"Fresh empty isolated project required" in replay.stderr
    assert sql("SELECT count(*) FROM public.tracks;").stdout.strip() == b"1"


def test_non_exposed_invoker_trigger_runs_for_authenticated_role_and_rolls_back(postgres):
    # Prove the permission model used by the prepared psycopg controls, without installing
    # any privileged helper into an exposed schema or using a real Supabase credential.
    postgres.sql("""CREATE SCHEMA sec0023_local_control;
REVOKE ALL ON SCHEMA sec0023_local_control FROM PUBLIC,anon,authenticated;
CREATE FUNCTION sec0023_local_control.touch_failure() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER
 SET search_path='' AS $$BEGIN RAISE EXCEPTION 'synthetic SEC0023 parent failure'; END$$;
INSERT INTO public.profiles(id) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
INSERT INTO public.playlists(id,user_id,name) VALUES('sec0023-p','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','A');
CREATE TRIGGER sec0023_touch_failure BEFORE UPDATE ON public.playlists FOR EACH ROW
 WHEN (OLD.id='sec0023-p') EXECUTE FUNCTION sec0023_local_control.touch_failure();""")
    try:
        result=subprocess.run(["docker","exec","-i",postgres.cid,"psql","-X","-qAt","-v","ON_ERROR_STOP=1",
            "-v","VERBOSITY=sqlstate","-U","postgres"],input=b"""BEGIN; SET LOCAL ROLE authenticated;
SET LOCAL request.jwt.claim.sub='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
SELECT public.aura_mutate_playlist_items('sec0023-p','sec0023-item','rest_insert','{"track_id":"synthetic","position":0}');
COMMIT;""",capture_output=True,timeout=30)
        assert result.returncode != 0 and b"P0001" in result.stderr
        assert postgres.sql("SELECT count(*) FROM public.playlist_items WHERE playlist_id='sec0023-p';").strip() == "0"
    finally:
        postgres.sql("DROP TRIGGER sec0023_touch_failure ON public.playlists; DROP FUNCTION sec0023_local_control.touch_failure(); DROP SCHEMA sec0023_local_control;")


def test_rendered_compose_is_standalone_loopback_tmpfs_and_secure_logging(tmp_path):
    output=tmp_path/"isolated"
    prepare.stage(output,"abcdefghijklmnopqrst",["production.invalid"])
    env={**os.environ,"AURA_SEC0023_IMAGE":"sha256:5c541f103b62672bc5551aa093f840c878c7129797665ed63d2f0db5a874e16e"}
    result=subprocess.run(["docker","compose","-f",str(output/"compose.yml"),"config","--format","json"],
                          cwd=output,env=env,capture_output=True,timeout=30)
    assert result.returncode == 0,result.stderr.decode()[-2000:]
    value=json.loads(result.stdout)
    assert list(value["services"]) == ["backend"]
    backend=value["services"]["backend"]
    assert backend["ports"][0]["host_ip"] == "127.0.0.1" and backend["ports"][0]["published"] == "18443"
    assert backend["read_only"] and backend["pull_policy"] == "never"
    assert backend["environment"]["APP_DEBUG"] == "false"
    assert backend["environment"]["ENABLE_STARTUP_RECOVERY"] == "false"
    assert backend["environment"]["ENABLE_CACHE_BACKFILL"] == "false"
    assert backend["logging"]["options"] == {"max-size":"10m","max-file":"3"}
    assert "app.core.operational_logging:create_app" in backend["command"]
    assert not value.get("volumes") and not any("docker.sock" in str(v) for v in backend["volumes"])
