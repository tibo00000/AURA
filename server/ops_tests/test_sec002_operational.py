"""Local regression tests; real PostgreSQL, real REST handlers, SQL SDK adapter.

Run separately from unit tests. No network/ports/production data.
The adapter models one transaction per SDK call, not real PostgREST or real JWT validation.
"""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
from types import SimpleNamespace
import uuid

import pytest

ROOT = Path(__file__).resolve().parents[2]
A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
os.environ.update(TESTING="1", SUPABASE_URL="https://synthetic.supabase.invalid",
                  SUPABASE_ANON_KEY="SYNTHETIC_PUBLIC", SUPABASE_SERVICE_ROLE_KEY="SYNTHETIC_ADMIN",
                  ENABLE_STARTUP_RECOVERY="false", ENABLE_CACHE_BACKFILL="false")
sys.path.insert(0, str(ROOT / "server"))
from app.api.routes import me
from app.core.auth import AuthenticatedUser
from app.schemas.me import PlaylistItemCreate, PlaylistUpdate


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


@pytest.fixture(scope="module")
def postgres():
    def run(args, **kw):
        p = subprocess.run(args, capture_output=True, timeout=90, **kw)
        assert p.returncode == 0, p.stderr.decode()[-2000:]
        return p.stdout.decode()
    endpoint = run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]).strip()
    assert endpoint.startswith(("npipe://", "unix://"))
    assert os.environ.get("DOCKER_HOST", endpoint) == endpoint
    nonce = uuid.uuid4().hex
    cid = run(["docker", "run", "--rm", "-d", "--network", "none", "--label", "aura.sec0021="+nonce,
               "--tmpfs", "/var/lib/postgresql/data:rw", "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
               "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea",
               "-c", "listen_addresses="]).strip()
    assert re.fullmatch("[0-9a-f]{64}", cid)
    def sql(body, owner=False):
        if owner:
            body = "BEGIN; SET LOCAL ROLE authenticated; SET LOCAL request.jwt.claim.sub="+literal(A)+";\n"+body+"\nCOMMIT;"
        return run(["docker", "exec", "-i", cid, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres"], input=body.encode())
    try:
        for _ in range(60):
            p = subprocess.run(["docker", "exec", cid, "pg_isready", "-U", "postgres"], capture_output=True)
            if p.returncode == 0:
                break
            time.sleep(.2)
        else:
            raise AssertionError("local PostgreSQL startup failed")
        setup = """CREATE ROLE anon NOLOGIN NOBYPASSRLS; CREATE ROLE authenticated NOLOGIN NOBYPASSRLS;
CREATE ROLE service_role NOLOGIN BYPASSRLS; CREATE SCHEMA auth;
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS
$$SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
GRANT USAGE ON SCHEMA public,auth TO anon,authenticated,service_role;
"""
        for rel in ["server/app/db/migrations/004_create_sync_tables.sql", "server/app/db/migrations/003_create_download_jobs.sql",
                    "server/app/db/migrations/001_create_catalog_tables.sql", "server/app/db/migrations/002_create_mapping_tables.sql",
                    "server/migrations/001_enable_rls_policies.sql"]:
            setup += (ROOT/rel).read_text(encoding="utf-8-sig")+"\n"
        setup += "GRANT ALL ON ALL TABLES IN SCHEMA public TO anon,authenticated,service_role;\n"
        setup += (ROOT/"supabase/migrations/20261016214500_enable_catalog_rls.sql").read_text()
        sql(setup)
        # Validate the inventory before migration on a schema reconstructed from tracked SQL.
        before = json.loads(sql((ROOT/"supabase/sql/sec002_inventory_metadata.sql").read_text()))
        assert before["transaction_read_only"] == "on" and before["server_version_num"] >= 150000
        assert any(c["name"] == "unique_playlist_position" for c in before["constraints"])
        sql((ROOT/"supabase/migrations/20261017200822_secure_user_data_isolation.sql").read_text())
        yield SimpleNamespace(sql=sql, cid=cid, before=before)
    finally:
        info = json.loads(run(["docker", "inspect", cid]))[0]
        assert info["Config"]["Labels"].get("aura.sec0021") == nonce
        run(["docker", "rm", "-f", cid])


@pytest.fixture
def db(postgres):
    postgres.sql("DELETE FROM public.playlists; DELETE FROM public.profiles; INSERT INTO public.profiles(id) VALUES("+literal(A)+"); "
                 "INSERT INTO public.playlists(id,user_id,name,updated_at) VALUES('pa',"+literal(A)+",'A','2000-01-01T00:00:00Z'); "
                 "INSERT INTO public.playlist_items(id,playlist_id,track_id,position) VALUES('i0','pa','t0',0),('i1','pa','t1',1);")
    return postgres


class Query:
    def __init__(self, client, table):
        assert table in ("profiles", "playlists", "playlist_items")
        self.client, self.table, self.kind, self.data = client, table, "select", None
        self.filters, self.sort = [], ""
    def select(self, columns):
        assert columns == "*"
        return self
    def eq(self, key, value):
        assert re.fullmatch("[a-z_]+", key)
        self.filters.append(key+"="+literal(value))
        return self
    def order(self, key):
        assert key in ("position","created_at")
        self.sort = " ORDER BY "+key
        return self
    def update(self, data):
        self.kind, self.data = "update", data
        return self
    def insert(self, data):
        self.kind, self.data = "insert", data
        return self
    def delete(self):
        self.kind = "delete"
        return self
    def execute(self):
        if self.table == "playlists" and self.kind == "update":
            if self.client.fail_touch:
                raise RuntimeError("synthetic failure between REST transactions")
            if self.client.pause:
                self.client.arrived.set()
                assert self.client.release.wait(15)
        where = " WHERE "+" AND ".join(self.filters) if self.filters else ""
        if self.kind == "select":
            sql = "SELECT * FROM public."+self.table+where+self.sort
        elif self.kind == "insert":
            sql = "INSERT INTO public."+self.table+"("+",".join(self.data)+") VALUES("+",".join(map(literal,self.data.values()))+") RETURNING *"
        elif self.kind == "delete":
            sql = "DELETE FROM public."+self.table+where+" RETURNING *"
        else:
            sql = "UPDATE public."+self.table+" SET "+",".join(k+"="+literal(v) for k,v in self.data.items())+where+" RETURNING *"
        query = ("WITH rows AS ("+sql+") SELECT coalesce(json_agg(rows),'[]') FROM rows;" if self.kind != "select"
                 else "SELECT coalesce(json_agg(rows),'[]') FROM ("+sql+") rows;")
        return SimpleNamespace(data=json.loads(self.client.db.sql(query, owner=True)))


class Client:
    def __init__(self, db, pause=False, fail_touch=False):
        self.db, self.pause, self.fail_touch = db, pause, fail_touch
        self.arrived, self.release = threading.Event(), threading.Event()
    def table(self, name):
        return Query(self, name)
    def rpc(self, name, params):
        assert name == "aura_mutate_playlist_items"
        def execute():
            if self.pause:
                self.arrived.set()
                assert self.release.wait(15)
            values = [literal(params["p_playlist_id"]),literal(params.get("p_item_id")),literal(params["p_action"]),
                      literal(json.dumps(params["p_payload"]))+"::jsonb",literal(params.get("p_expected_updated_at"))]
            data = self.db.sql("SELECT public.aura_mutate_playlist_items("+",".join(values)+");",owner=True)
            return SimpleNamespace(data=json.loads(data))
        return SimpleNamespace(execute=execute)


def rpc(db, items):
    return json.loads(db.sql("SELECT public.aura_mutate_playlist_items('pa',NULL,'reorder',"+
                            literal(json.dumps({"items":items}))+"::jsonb,'2000-01-01T00:00:00Z');", owner=True))


@pytest.mark.parametrize("operation", ["append", "remove", "patch"])
def test_rest_timestamp_follows_concurrent_rpc(db, operation):
    client = Client(db, pause=True)
    user = AuthenticatedUser(id=A, token="SYNTHETIC_NOT_A_JWT")
    def rest():
        if operation == "append":
            return asyncio.run(me.append_track_to_playlist("pa",PlaylistItemCreate(id="i2",track_id="t2",position=2),user,client))
        if operation == "remove":
            return asyncio.run(me.remove_track_from_playlist("pa","t0",user,client))
        return asyncio.run(me.patch_playlist("pa",PlaylistUpdate(name="renamed"),user,client))
    with ThreadPoolExecutor(max_workers=1) as pool:
        job = pool.submit(rest)
        try:
            assert client.arrived.wait(15)
            result = rpc(db,[{"playlist_item_id":"i1","position":3}])
            assert result["status"] == "applied"
        finally:
            client.release.set()
        job.result(timeout=15)
    stamp = db.sql("SELECT updated_at FROM public.playlists WHERE id='pa';").strip()
    assert datetime.fromisoformat(stamp) > datetime.fromisoformat(result["updated_at"])


@pytest.mark.parametrize("operation", ["append", "remove", "patch"])
def test_rest_failure_during_parent_update_rolls_back(db, operation):
    # A real PostgreSQL error AFTER child DML. No fake transaction/rollback semantics.
    db.sql("""CREATE FUNCTION public.synthetic_touch_failure() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER
AS $$BEGIN RAISE EXCEPTION 'synthetic parent update failure'; END$$;
CREATE TRIGGER synthetic_touch_failure BEFORE UPDATE ON public.playlists
FOR EACH ROW EXECUTE FUNCTION public.synthetic_touch_failure();""")
    try:
        user=AuthenticatedUser(id=A,token="SYNTHETIC_NOT_A_JWT")
        with pytest.raises(me.HTTPException) as caught:
            if operation == "append":
                asyncio.run(me.append_track_to_playlist("pa",PlaylistItemCreate(id="i2",track_id="t2",position=2),user,Client(db)))
            elif operation == "remove":
                asyncio.run(me.remove_track_from_playlist("pa","t0",user,Client(db)))
            else:
                asyncio.run(me.patch_playlist("pa",PlaylistUpdate(name="must rollback"),user,Client(db)))
        assert caught.value.status_code == 500
        assert db.sql("SELECT string_agg(id,',' ORDER BY id) FROM public.playlist_items;").strip() == "i0,i1"
        assert db.sql("SELECT updated_at='2000-01-01T00:00:00Z' AND name='A' FROM public.playlists WHERE id='pa';").strip() == "t"
    finally:
        db.sql("DROP TRIGGER synthetic_touch_failure ON public.playlists; DROP FUNCTION public.synthetic_touch_failure();")


def test_rest_occupied_position_is_rejected_not_silently_shifted(db):
    with pytest.raises(me.HTTPException) as caught:
        asyncio.run(me.append_track_to_playlist("pa",PlaylistItemCreate(id="i2",track_id="t2",position=0),
                    AuthenticatedUser(id=A,token="SYNTHETIC_NOT_A_JWT"),Client(db)))
    assert caught.value.status_code == 500
    assert db.sql("SELECT count(*) FROM public.playlist_items;").strip() == "2"
    # Deferrable does not mean initially deferred, and REST has no multi-call transaction.
    assert db.sql("SELECT condeferrable AND NOT condeferred FROM pg_constraint WHERE conname='unique_playlist_position';").strip() == "t"


def test_rpc_reorder_valid_swap_and_stale_version_conflict(db):
    first = rpc(db,[{"playlist_item_id":"i0","position":1},{"playlist_item_id":"i1","position":0}])
    second = rpc(db,[{"playlist_item_id":"i0","position":0},{"playlist_item_id":"i1","position":1}])
    assert first["status"] == "applied" and second["status"] == "conflict"
    assert db.sql("SELECT position FROM public.playlist_items WHERE id='i0';").strip() == "1"


def test_inventory_read_only_output_and_aggregate_counts(db):
    from scripts.check_sec002_inventory import assess
    meta = json.loads(db.sql((ROOT/"supabase/sql/sec002_inventory_metadata.sql").read_text()))
    counts = json.loads(db.sql((ROOT/"supabase/sql/sec002_inventory_counts.sql").read_text()))
    batches = json.loads(db.sql((ROOT/"supabase/sql/sec002_inventory_batches.sql").read_text()))
    assert meta["transaction_read_only"] == "on"
    assert counts["events_invalid_session"] == counts["history_invalid_session"] == 0
    assert batches["batch_orphan_owner"] == 0
    assert all("body" not in f and "definition" not in f for f in meta["functions"])
    assert not any(key in json.dumps(meta) for key in ("rolpassword", "prosrc", "tgargs"))
    assert len([p for p in meta["policies"] if p["name"] == "sec002_owner_guard"]) == 11
    assert any(c["name"] == "unique_playlist_position" and c["deferrable"] for c in meta["constraints"])
    assert assess(db.before,counts,batches)["status"] == "MANUAL_REVIEW_REQUIRED"
    assert any(b.startswith("migration_may_already_be_applied") for b in assess(meta,counts,batches)["blocks"])


def test_rest_removes_all_track_occurrences_without_compacting_positions(db):
    db.sql("INSERT INTO public.playlist_items(id,playlist_id,track_id,position) VALUES('repeat','pa','t0',2);")
    result=asyncio.run(me.remove_track_from_playlist("pa","t0",AuthenticatedUser(id=A,token="SYNTHETIC"),Client(db)))
    assert {i.id for i in result.data} == {"i0","repeat"}
    assert db.sql("SELECT id||':'||position FROM public.playlist_items;").strip() == "i1:1"
    assert db.sql("SELECT updated_at>'2000-01-01T00:00:00Z' FROM public.playlists;").strip() == "t"


@pytest.mark.parametrize("collision",["own","foreign"])
def test_rest_item_id_collision_rolls_back_parent_and_positions(db,collision):
    if collision == "foreign":
        db.sql("INSERT INTO public.profiles(id) VALUES('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'); "
               "INSERT INTO public.playlists(id,user_id,name) VALUES('pb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','B_PRIVATE'); "
               "INSERT INTO public.playlist_items(id,playlist_id,track_id,position) VALUES('ib','pb','private',0);")
    target="ib" if collision == "foreign" else "i0"
    with pytest.raises(me.HTTPException) as error:
        asyncio.run(me.append_track_to_playlist("pa",PlaylistItemCreate(id=target,track_id="t2",position=2),
                                               AuthenticatedUser(id=A,token="SYNTHETIC"),Client(db)))
    assert error.value.status_code == 500 and error.value.detail == "Unable to complete user database request"
    assert db.sql("SELECT updated_at='2000-01-01T00:00:00Z' FROM public.playlists WHERE id='pa';").strip() == "t"
    assert db.sql("SELECT string_agg(id||':'||position,',' ORDER BY id) FROM public.playlist_items WHERE playlist_id='pa';").strip() == "i0:0,i1:1"
    if collision == "foreign":
        assert db.sql("SELECT playlist_id FROM public.playlist_items WHERE id='ib';").strip() == "pb"


@pytest.mark.parametrize("operation",["append","remove","patch"])
def test_foreign_rest_parent_is_404_without_mutation(db,operation):
    db.sql("INSERT INTO public.profiles(id) VALUES('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'); "
           "INSERT INTO public.playlists(id,user_id,name) VALUES('pb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','B_PRIVATE');")
    user=AuthenticatedUser(id=A,token="SYNTHETIC")
    with pytest.raises(me.HTTPException) as error:
        if operation == "append":
            asyncio.run(me.append_track_to_playlist("pb",PlaylistItemCreate(id="bad",track_id="t0",position=0),user,Client(db)))
        elif operation == "remove":
            asyncio.run(me.remove_track_from_playlist("pb","t0",user,Client(db)))
        else:
            asyncio.run(me.patch_playlist("pb",PlaylistUpdate(name="bad"),user,Client(db)))
    assert error.value.status_code == 404
    assert db.sql("SELECT name FROM public.playlists WHERE id='pb';").strip() == "B_PRIVATE"


@pytest.mark.parametrize("action",["rest_insert","rest_delete_track","rest_patch"])
def test_direct_rest_rpc_rejects_foreign_parent_even_with_payload_owner(db,action):
    db.sql("INSERT INTO public.profiles(id) VALUES('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'); "
           "INSERT INTO public.playlists(id,user_id,name) VALUES('pb','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','B_PRIVATE');")
    with pytest.raises(AssertionError):
        Client(db).rpc("aura_mutate_playlist_items",{"p_playlist_id":"pb","p_item_id":"t0","p_action":action,
                      "p_payload":{"user_id":A,"track_id":"t0","position":2,"name":"bad"}}).execute()
    assert db.sql("SELECT name FROM public.playlists WHERE id='pb';").strip() == "B_PRIVATE"


@pytest.mark.parametrize("action",["rest_insert","rest_delete_track","rest_patch","reorder"])
def test_parent_clock_cannot_regress_even_if_previous_timestamp_is_in_future(db,action):
    future="2100-01-01T00:00:00+00:00"
    db.sql("UPDATE public.playlists SET updated_at="+literal(future)+" WHERE id='pa';")
    payload={"track_id":"t2","position":2,"items":[{"playlist_item_id":"i1","position":3}],
             "user_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","id":"foreign"}
    result=Client(db).rpc("aura_mutate_playlist_items",{"p_playlist_id":"pa","p_item_id":"new",
                           "p_action":action,"p_payload":payload,"p_expected_updated_at":future}).execute().data
    assert datetime.fromisoformat(db.sql("SELECT updated_at FROM public.playlists WHERE id='pa';").strip()) > datetime.fromisoformat(future)
    assert db.sql("SELECT user_id FROM public.playlists WHERE id='pa';").strip() == A


def test_real_http_rest_shapes_and_metadata_null_semantics(db):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.core.auth import get_current_user
    from app.db.user_client import get_current_user_client
    app=FastAPI()
    app.include_router(me.router)
    app.dependency_overrides[get_current_user]=lambda:AuthenticatedUser(id=A,token="SYNTHETIC")
    app.dependency_overrides[get_current_user_client]=lambda:Client(db)
    with TestClient(app) as http:
        result=http.post("/me/playlists/pa/tracks",json={"id":"i2","track_id":"t0","position":2})
        assert result.status_code == 200
        assert result.json()["data"]["position"] == 2
        assert set(result.json()) == {"data","error","meta"}
        listed=http.get("/me/playlists")
        assert listed.status_code == 200 and [p["id"] for p in listed.json()["data"]] == ["pa"]
        assert http.get("/me/playlists/pa").status_code == 405
        updated=http.patch("/me/playlists/pa",json={"name":"new","cover_uri":"synthetic://cover","is_pinned":True})
        assert updated.status_code == 200 and updated.json()["data"]["name"] == "new"
        updated=http.patch("/me/playlists/pa",json={"cover_uri":None,"is_pinned":False})
        assert updated.status_code == 200
        assert updated.json()["data"]["cover_uri"] == "synthetic://cover"
        assert updated.json()["data"]["is_pinned"] is False
        removed=http.delete("/me/playlists/pa/tracks/t0")
        assert removed.status_code == 200 and {i["id"] for i in removed.json()["data"]} == {"i0","i2"}


def test_rest_mutation_waits_on_the_same_parent_lock_as_reorder(db):
    command=["docker","exec","-i",db.cid,"psql","-X","-qAt","-v","ON_ERROR_STOP=1","-U","postgres"]
    lease=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    lease.stdin.write(b"BEGIN; SELECT id FROM public.playlists WHERE id='pa' FOR UPDATE; SELECT 'READY'; SELECT pg_sleep(3); COMMIT;\n")
    lease.stdin.close()
    try:
        assert lease.stdout.readline().strip() == b"pa"
        assert lease.stdout.readline().strip() == b"READY"
        with ThreadPoolExecutor(max_workers=1) as pool:
            job=pool.submit(lambda:asyncio.run(me.append_track_to_playlist("pa",PlaylistItemCreate(id="i2",track_id="t2",position=2),
                                         AuthenticatedUser(id=A,token="SYNTHETIC"),Client(db))))
            waiting=False
            for _ in range(12):
                waiting=db.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%rest_insert%';").strip() != "0"
                if waiting:
                    break
                time.sleep(.05)
            assert waiting and not job.done()
            assert db.sql("SELECT count(*) FROM public.playlist_items WHERE id='i2';").strip() == "0"
            lease.wait(timeout=10)
            assert job.result(timeout=10).data.id == "i2"
    finally:
        lease.wait(timeout=10)
