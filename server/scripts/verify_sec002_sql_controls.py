"""Prepared SQL controls for the explicitly authorized ISOLATED Supabase project only.

No CLI, no network at import. Called by verify_sec002_integrated after preflight.
Requires tool-only psycopg[binary]; never install it into/change production dependencies.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
from pathlib import Path
import re
import time


def connection_options(config):
    ref=config["isolated_project_ref"]
    assert re.fullmatch("[a-z]{20}",ref)
    assert config["expected_project_host"] == ref+".supabase.co"
    value=json.loads(Path(config["sql_control_file"]).read_text(encoding="utf-8-sig"))
    host=value["host"]
    assert host not in config["production_deny_hosts"]
    assert ((host == "db."+ref+".supabase.co" and value["user"] == "postgres") or
            (re.fullmatch(r"[a-z0-9-]+\.pooler\.supabase\.com",host) and value["user"] == "postgres."+ref))
    assert int(value["port"]) == 5432 and value["dbname"] == "postgres" and value["password"]
    assert Path(value["sslrootcert"]).is_file()
    return {k:value[k] for k in ("host","port","dbname","user","password","sslrootcert")} | {
        "sslmode":"verify-full","connect_timeout":10,"options":"-c statement_timeout=20000 -c lock_timeout=10000"}


def verify_database(config):
    """Live metadata-only check BEFORE logins/fixtures. No privileged probe function created."""
    import psycopg
    with psycopg.connect(**connection_options(config)) as connection:
        connection.execute("SET TRANSACTION READ ONLY")
        assert int(connection.execute("SHOW server_version_num").fetchone()[0]) >= 150000
        assert connection.execute("""SELECT has_database_privilege(current_database(),'CREATE')
            AND has_table_privilege('public.playlists','TRIGGER')""").fetchone()[0]
        assert connection.execute("""SELECT count(*) FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND p.polname='sec002_owner_guard'
            AND NOT p.polpermissive AND c.relrowsecurity""").fetchone()[0] == 11
        assert connection.execute("""SELECT NOT p.prosecdef AND has_function_privilege('authenticated',p.oid,'EXECUTE')
            AND NOT has_function_privilege('anon',p.oid,'EXECUTE')
            FROM pg_proc p WHERE p.oid='public.aura_mutate_playlist_items(text,text,text,jsonb,timestamptz)'::regprocedure""").fetchone()[0]
        assert connection.execute("SELECT count(*) FROM pg_roles WHERE rolname IN ('anon','authenticated') AND (rolsuper OR rolbypassrls)").fetchone()[0] == 0


def verify_controls(config,api,client,playlist_id,item_id,track,marker):
    import psycopg
    from psycopg import sql
    options=connection_options(config)
    schema="sec0023_"+marker
    assert re.fullmatch("sec0023_[a-f0-9]{32}",schema)
    owner=config["users"][0]["expected_id"]
    with psycopg.connect(**options,autocommit=True) as admin:
        # Check real catalog properties without returning definitions, claims or private rows.
        secure=admin.execute("""SELECT NOT p.prosecdef AND has_function_privilege('authenticated',p.oid,'EXECUTE')
            AND NOT has_function_privilege('anon',p.oid,'EXECUTE')
            FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
            WHERE n.nspname='public' AND p.proname='aura_mutate_playlist_items'""").fetchall()
        assert secure == [(True,)]
        count=admin.execute("""SELECT count(*) FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'
            AND p.polname='sec002_owner_guard' AND NOT p.polpermissive AND c.relrowsecurity""").fetchone()[0]
        assert count == 11
        assert admin.execute("SELECT user_id::text FROM public.playlists WHERE id=%s",(playlist_id,)).fetchone() == (owner,)
        # Two real requests, one REST/FastAPI and one SDK/PostgREST RPC, must wait on our parent lock.
        with psycopg.connect(**options) as locker:
            locker.execute("SELECT id FROM public.playlists WHERE id=%s AND user_id=%s FOR UPDATE",(playlist_id,owner))
            pid=locker.execute("SELECT pg_backend_pid()").fetchone()[0]
            stamp=locker.execute("SELECT updated_at::text FROM public.playlists WHERE id=%s",(playlist_id,)).fetchone()[0]
            with ThreadPoolExecutor(max_workers=2) as pool:
                rest=pool.submit(api,0,"POST","/me/playlists/"+playlist_id+"/tracks",{
                    "id":"lock-"+marker,"track_id":track,"position":30})
                reorder=pool.submit(lambda:client.rpc("aura_mutate_playlist_items",{
                    "p_playlist_id":playlist_id,"p_item_id":None,"p_action":"reorder",
                    "p_payload":{"items":[{"playlist_item_id":item_id,"position":0}]},
                    "p_expected_updated_at":stamp}).execute().data)
                observed=False
                try:
                    deadline=time.monotonic()+8
                    while time.monotonic()<deadline:
                        if admin.execute("""WITH RECURSIVE waiting(pid) AS (
                            SELECT a.pid FROM pg_stat_activity a WHERE %s=ANY(pg_blocking_pids(a.pid))
                            UNION SELECT a.pid FROM pg_stat_activity a JOIN waiting w ON w.pid=ANY(pg_blocking_pids(a.pid)))
                            SELECT count(*) FROM waiting""",(pid,)).fetchone()[0] >= 2:
                            observed=True;break
                        time.sleep(.1)
                    assert observed and not rest.done() and not reorder.done()
                finally:
                    locker.rollback()  # Always release before joining HTTP requests, even on failed observation.
                assert rest.result(timeout=30)["id"] == "lock-"+marker
                result=reorder.result(timeout=30)
                assert result["status"] in ("applied","conflict")
                final=admin.execute("SELECT updated_at FROM public.playlists WHERE id=%s",(playlist_id,)).fetchone()[0]
                assert final >= datetime.fromisoformat(result["updated_at"])
        # Remove exactly the additional item made above; leave the original occurrence.
        client.rpc("aura_mutate_playlist_items",{"p_playlist_id":playlist_id,"p_item_id":"lock-"+marker,
                    "p_action":"delete","p_payload":{}}).execute()
        before=api(0,"GET","/me/playlists")
        # A per-run, NON-EXPOSED schema; trigger is invoker and only fails this fixture parent.
        with admin.transaction():
            admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
            admin.execute(sql.SQL("REVOKE ALL ON SCHEMA {} FROM PUBLIC,anon,authenticated").format(sql.Identifier(schema)))
            admin.execute(sql.SQL("""CREATE FUNCTION {}.touch_failure() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER
                SET search_path='' AS $fn$ BEGIN RAISE EXCEPTION 'synthetic SEC0023 parent failure'; END $fn$""").format(sql.Identifier(schema)))
            admin.execute(sql.SQL("CREATE TRIGGER sec0023_touch_failure BEFORE UPDATE ON public.playlists FOR EACH ROW WHEN (OLD.id={}) EXECUTE FUNCTION {}.touch_failure()").format(sql.Literal(playlist_id),sql.Identifier(schema)))
        try:
            from postgrest.exceptions import APIError
            try:
                client.rpc("aura_mutate_playlist_items",{"p_playlist_id":playlist_id,"p_item_id":None,
                    "p_action":"rest_patch","p_payload":{}}).execute()
            except APIError as error:
                assert str(error.code) == "P0001"  # Confirm the intended trigger ran, not a missing grant.
            else:
                raise AssertionError("Synthetic trigger did not reject update")
            api(0,"POST","/me/playlists/"+playlist_id+"/tracks",{"id":"atomic-"+marker,"track_id":track,"position":99},500)
            assert api(0,"GET","/me/playlists") == before
            api(0,"DELETE","/me/playlists/"+playlist_id+"/tracks/"+track,None,500)
            assert api(0,"GET","/me/playlists") == before
            api(0,"PATCH","/me/playlists/"+playlist_id,{"name":"must rollback"},500)
            assert api(0,"GET","/me/playlists") == before
        finally:
            with admin.transaction():
                admin.execute("DROP TRIGGER sec0023_touch_failure ON public.playlists")
                admin.execute(sql.SQL("DROP FUNCTION {}.touch_failure()").format(sql.Identifier(schema)))
                admin.execute(sql.SQL("DROP SCHEMA {}").format(sql.Identifier(schema)))
