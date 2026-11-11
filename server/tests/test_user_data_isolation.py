"""SEC-002 service/API decisions with A/B data; this double does NOT validate SQL RLS."""

import copy
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from threading import Barrier
from unittest.mock import MagicMock

import httpx
import pytest
from fastapi import Request
from fastapi.testclient import TestClient

from app.config import Settings
from app.core.auth import AuthenticatedUser, get_current_user
from app.db import supabase as clients
from app.db.user_client import get_current_user_client
from app.main import app
from app.services.sync_service import SyncService, compute_order_token, generate_sync_token
from tests.test_sync_service import InMemorySupabase

A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
STAMP = "2026-10-17T00:00:00+00:00"


class StrictDatabase(InMemorySupabase):
    """Unscoped reads deliberately expose missing service filters; real key collisions enforced."""

    def __init__(self, tables=None, actor=A):
        super().__init__(tables)
        self.user_id = actor

    def table(self, name):
        query = super().table(name)
        original = query.execute
        parent = self
        conflict_keys = None
        ignore_duplicates = False

        def upsert(payload, **kwargs):
            nonlocal conflict_keys, ignore_duplicates
            query._op, query._payload = "upsert", payload
            conflict_keys = kwargs.get("on_conflict")
            ignore_duplicates = kwargs.get("ignore_duplicates", False)
            return query

        def execute():
            if query._op not in ("insert", "upsert"):
                return original()
            keys = (conflict_keys or {
                "likes": "user_id,track_id", "playback_snapshots": "user_id",
                "user_settings": "user_id", "processed_operations": "user_id,operation_id",
                "processed_batches": "user_id,batch_id",
            }.get(name, "id")).split(",")
            rows = parent.tables.setdefault(name, [])
            items = query._payload if isinstance(query._payload, list) else [query._payload]
            before = copy.deepcopy(rows)
            try:
                for item in items:
                    existing = next((r for r in rows if all(r.get(k) == item.get(k) for k in keys)), None)
                    if existing is not None:
                        if query._op == "insert":
                            raise ValueError("Unique collision")
                        if not ignore_duplicates:
                            existing.update(item)
                    else:
                        rows.append(dict(item))
            except Exception:
                rows[:] = before
                raise
            parent.mutations.append((name, query._op, copy.deepcopy(items)))
            return MagicMock(data=copy.deepcopy(items))

        query.upsert = upsert
        query.execute = execute
        return query


@pytest.fixture
def db():
    return StrictDatabase({
        "profiles": [{"id": A}, {"id": B}],
        "playlists": [dict(id=p, user_id=u, name=name, cover_uri=None, is_pinned=False,
                           created_at=STAMP, updated_at=STAMP)
                      for p, u, name in (("pa", A, "A"), ("pa2", A, "A2"), ("pb", B, "B_PRIVATE_PLAYLIST"))],
        "playlist_items": [dict(id=i, playlist_id=p, track_id=t, position=pos, added_at=STAMP)
                           for i, p, t, pos in (("ia", "pa", "ta", 0), ("ia2", "pa", "ta2", 1),
                                               ("ia-other", "pa2", "ta3", 0), ("ib", "pb", "B_PRIVATE_TRACK", 0))],
        "listening_sessions": [dict(id="s"+x, user_id=u, started_at=STAMP, total_listening_ms=5)
                               for x, u in (("a", A), ("b", B))],
        "history_items": [dict(id="h"+x, user_id=u, track_id=t, played_at=STAMP, was_skipped=False)
                          for x, u, t in (("a", A, "ta"), ("b", B, "B_PRIVATE_HISTORY"))],
        "playback_events": [dict(id="eb", user_id=B, session_id="sb", track_id="B_PRIVATE_EVENT", event_type="play")],
        "likes": [dict(user_id=u, track_id=t, liked_at=STAMP) for u, t in ((A,"ta"),(B,"B_PRIVATE_LIKE"))],
        "playback_snapshots": [dict(user_id=u, current_track_id=t, position_ms=1, shuffle_enabled=False,
                                    repeat_mode="none", updated_at=STAMP)
                               for u, t in ((A,"ta"),(B,"B_PRIVATE_SNAPSHOT"))],
        "user_settings": [], "processed_operations": [], "processed_batches": [],
    })


def push(db, entity, entity_id, payload=None, operation="create", batch="batch", op="op", actor=A):
    return SyncService(db).push_batch(actor, "synthetic-device", batch, [{
        "operation_id": op, "entity_type": entity, "entity_id": entity_id,
        "operation_type": operation, "occurred_at": "2030-01-01T00:00:00+00:00", "payload": payload or {},
    }])


def victim_rows(db):
    return {table: copy.deepcopy([r for r in rows if r.get("user_id") == B or r.get("playlist_id") == "pb"])
            for table, rows in db.tables.items() if table not in ("processed_operations", "processed_batches")}


@pytest.mark.parametrize("entity,entity_id,operation,payload", [
    ("playlist", "pb", "update", {"name": "takeover"}),
    ("playlist", "pb", "delete", {}),
    ("playlist_item", "ib", "insert", {"playlist_id": "pa", "track_id": "ta", "position": 0}),
    ("playlist_item", "ib", "delete", {"playlist_id": "pa"}),
    ("playlist_item", "new", "insert", {"playlist_id": "pb", "track_id": "ta", "position": 0}),
    ("playlist_item", "ib", "delete", {"playlist_id": "pb"}),
    ("listening_session", "sb", "update", {"total_listening_ms": 99}),
    ("history_item", "hb", "create", {"track_id": "ta"}),
    ("history_item", "new", "create", {"track_id": "ta", "listening_session_id": "sb"}),
    ("playback_event", "eb", "create", {"track_id": "ta", "session_id": "sa", "event_type": "play"}),
    ("playback_event", "new", "create", {"track_id": "ta", "session_id": "sb", "event_type": "play"}),
])
def test_foreign_ids_never_change_or_disclose_victim(db, entity, entity_id, operation, payload):
    before = victim_rows(db)
    result = push(db, entity, entity_id, payload, operation)
    assert victim_rows(db) == before
    assert "B_PRIVATE" not in json.dumps(result)
    assert result["results"][0]["status"] in ("conflict", "ignored_duplicate")


@pytest.mark.parametrize("item_id", ["ib", "ia-other", "missing"])
def test_reorder_valid_parent_and_token_does_not_authorize_other_item(db, item_id):
    before = copy.deepcopy(db.tables["playlist_items"])
    result = push(db, "playlist_reorder", "pa", {
        "base_order_token": compute_order_token(STAMP),
        "items": [{"playlist_item_id": "ia", "position": 1}, {"playlist_item_id": item_id, "position": 0}],
    }, "reorder")
    assert result["results"][0]["status"] == "conflict"
    assert db.tables["playlist_items"] == before


@pytest.mark.parametrize("items", [
    [{"playlist_item_id":"ia","position":0},{"playlist_item_id":"ia","position":1}],
    [{"playlist_item_id":"ia","position":0},{"playlist_item_id":"ia2","position":0}],
    [{"playlist_item_id":"ia","position":True}],
    [{"playlist_item_id":"ia","position":-1}],
    [{"playlist_item_id":"ia","position":"1"}],
    "invalid",
])
def test_invalid_order_rejected_before_any_mutation(db, items):
    before = copy.deepcopy(db.tables["playlist_items"])
    result = push(db, "playlist_reorder", "pa", {"base_order_token":compute_order_token(STAMP),"items":items})
    assert result["results"][0]["status"] == "conflict"
    assert db.tables["playlist_items"] == before


@pytest.mark.parametrize("actor,playlist", [(A,"pa"),(B,"pb")])
def test_owner_can_create_update_add_remove_and_delete(db, actor, playlist):
    db.user_id = actor
    own = "own-new-" + actor
    assert push(db,"playlist",own,{"name":"own"},actor=actor)["results"][0]["status"] == "applied"
    assert push(db,"playlist",own,{"name":"changed"},batch="b2",op="o2",actor=actor)["results"][0]["status"] == "merged"
    assert push(db,"playlist_item","own-item",{"playlist_id":playlist,"track_id":"same-shared-track","position":0},batch="b3",op="o3",actor=actor)["results"][0]["status"] == "applied"
    assert push(db,"playlist_item","own-item",{"playlist_id":playlist},"delete",batch="b4",op="o4",actor=actor)["results"][0]["status"] == "applied"
    assert push(db,"playlist",own,operation="delete",batch="b5",op="o5",actor=actor)["results"][0]["status"] == "applied"


def test_valid_swap_stale_token_and_additive_history(db):
    payload={"base_order_token":compute_order_token(STAMP),"items":[{"playlist_item_id":"ia","position":1},{"playlist_item_id":"ia2","position":0}]}
    assert push(db,"playlist_reorder","pa",payload)["results"][0]["status"] == "applied"
    assert push(db,"playlist_reorder","pa",payload,batch="new",op="new")["results"][0]["conflict"]["reason"] == "base_outdated"
    original=copy.deepcopy(db.tables["history_items"][0])
    result=push(db,"history_item","ha",{"track_id":"replacement"},batch="h",op="h")
    assert result["results"][0]["status"] == "ignored_duplicate"
    assert db.tables["history_items"][0] == original


def test_bootstrap_pull_and_payload_owner_injection(db):
    assert "B_PRIVATE" not in json.dumps(SyncService(db).bootstrap(A,"device"))
    assert "B_PRIVATE" not in json.dumps(SyncService(db).pull_batch(A,"st_aW52YWxpZA=="))
    push(db,"playback_snapshot","default",{"user_id":B,"position_ms":33})
    assert next(r for r in db.tables["playback_snapshots"] if r["user_id"]==B)["position_ms"] == 1
    assert next(r for r in db.tables["playback_snapshots"] if r["user_id"]==A)["position_ms"] == 33


def test_colliding_markers_are_account_scoped_and_failed_batch_is_not_complete(db):
    db.tables["processed_batches"]=[{"batch_id":"batch","user_id":B}]
    db.tables["processed_operations"]=[{"operation_id":"op","user_id":B}]
    result=push(db,"track_like","shared-track",{"is_liked":True})
    assert result["results"][0]["status"] == "applied"
    assert len(db.tables["processed_batches"]) == len(db.tables["processed_operations"]) == 2
    again=push(db,"track_like","shared-track",{"is_liked":True})
    assert again["results"][0]["status"] == "ignored_duplicate"
    push(db,"playlist_reorder","pa",{"base_order_token":"bad"},batch="failed",op="failed")
    assert not any(r["batch_id"]=="failed" for r in db.tables["processed_batches"])


def test_mixed_batch_has_individual_results_and_does_not_mark_conflict_complete(db):
    operations=[dict(operation_id="good",entity_type="track_like",entity_id="new",operation_type="set",payload={"is_liked":True}),
                dict(operation_id="bad",entity_type="history_item",entity_id="hb",operation_type="create",payload={"track_id":"ta"})]
    result=SyncService(db).push_batch(A,"d","mixed",operations)
    assert [r["status"] for r in result["results"]] == ["applied","conflict"]
    assert not db.tables["processed_batches"]
    assert [r["operation_id"] for r in db.tables["processed_operations"]] == ["good"]


@pytest.mark.parametrize("table", ["processed_batches","processed_operations"])
def test_failed_idempotency_lookup_does_not_execute_mutations(db, monkeypatch, table):
    original=db.table
    def fail(name):
        q=original(name)
        if name==table:
            q.execute=lambda: (_ for _ in ()).throw(ValueError("unavailable"))
        return q
    monkeypatch.setattr(db,"table",fail)
    with pytest.raises(Exception,match="idempotency"):
        push(db,"playlist","new",{"name":"must not exist"})
    assert not any(r["id"]=="new" for r in db.tables["playlists"])


@pytest.mark.parametrize("method,path,payload", [
    ("patch","/me/playlists/pb",{"name":"takeover"}),
    ("delete","/me/playlists/pb",None),
    ("post","/me/playlists/pb/tracks",{"id":"new","track_id":"ta","position":0}),
    ("delete","/me/playlists/pb/tracks/B_PRIVATE_TRACK",None),
])
def test_rest_foreign_parent_matches_missing_object(db, method, path, payload):
    app.dependency_overrides[get_current_user]=lambda: AuthenticatedUser(id=A,token="SYNTHETIC_A")
    app.dependency_overrides[get_current_user_client]=lambda: db
    before=victim_rows(db)
    try:
        response=TestClient(app).request(method,path,**({"json":payload} if payload is not None else {}))
        assert response.status_code==404
        assert victim_rows(db)==before
    finally:
        app.dependency_overrides.clear()


def test_real_sdk_clients_keep_headers_isolated_under_overlap(monkeypatch):
    monkeypatch.setattr(clients,"settings",Settings(_env_file=None,supabase_url="https://synthetic.supabase.invalid",supabase_anon_key="SYNTHETIC_PUBLIC"))
    barrier=Barrier(2)
    observed=[]
    def request(actor):
        token="SYNTHETIC_"+actor
        def respond(req):
            barrier.wait(timeout=5)
            observed.append((actor,req.headers["authorization"],req.headers["apikey"]))
            return httpx.Response(200,json=[{"user_id":actor}])
        with httpx.Client(transport=httpx.MockTransport(respond)) as transport:
            client=clients.get_user_supabase_client(token,httpx_client=transport)
            return client.table("playlists").select("*").eq("user_id",actor).execute().data
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(request,[A,B]))
    assert results==[[{"user_id":A}],[{"user_id":B}]]
    assert all(header=="Bearer SYNTHETIC_"+actor and key=="SYNTHETIC_PUBLIC" for actor,header,key in observed)


@pytest.mark.parametrize("token", [None,""," "])
def test_user_client_never_falls_back_to_admin(monkeypatch, token):
    create=MagicMock()
    monkeypatch.setattr(clients,"create_client",create)
    with pytest.raises(ValueError): clients.get_user_supabase_client(token)
    create.assert_not_called()


def test_user_client_requires_public_key_even_if_service_role_exists(monkeypatch):
    monkeypatch.setattr(clients,"settings",Settings(_env_file=None,supabase_url="https://synthetic.supabase.invalid",supabase_anon_key="",supabase_service_role_key="SYNTHETIC_ADMIN"))
    create=MagicMock();monkeypatch.setattr(clients,"create_client",create)
    with pytest.raises(ValueError): clients.get_user_supabase_client("SYNTHETIC_USER")
    create.assert_not_called()


@pytest.mark.parametrize("actor,session", [(A,"sa"),(B,"sb")])
def test_own_session_history_and_events_preserve_legitimate_additive_flow(db, actor, session):
    db.user_id=actor
    started=next(r["started_at"] for r in db.tables["listening_sessions"] if r["id"]==session)
    result=push(db,"listening_session",session,{"started_at":"2099-01-01Z","total_listening_ms":99},actor=actor)
    assert result["results"][0]["status"]=="merged"
    assert next(r["started_at"] for r in db.tables["listening_sessions"] if r["id"]==session)==started
    h=push(db,"history_item","history-new",{"track_id":"shared","listening_session_id":session,"user_id":B if actor==A else A},batch="h",op="h",actor=actor)
    assert h["results"][0]["status"]=="applied"
    assert next(r["user_id"] for r in db.tables["history_items"] if r["id"]=="history-new")==actor
    e=push(db,"playback_event","event-new",{"track_id":"shared","session_id":session,"event_type":"play"},batch="e",op="e",actor=actor)
    assert e["results"][0]["status"]=="applied"
    again=push(db,"playback_event","event-new",{"track_id":"shared","session_id":session,"event_type":"play"},batch="again",op="again",actor=actor)
    assert again["results"][0]["status"]=="ignored_duplicate"


def test_repeated_operation_within_batch_does_not_apply_second_payload(db):
    operations=[dict(operation_id="same",entity_type="track_like",entity_id="one",operation_type="set",payload={"is_liked":True}),
                dict(operation_id="same",entity_type="track_like",entity_id="two",operation_type="set",payload={"is_liked":True})]
    result=SyncService(db).push_batch(A,"d","same-op",operations)
    assert [r["status"] for r in result["results"]]==["applied","ignored_duplicate"]
    assert not any(r["track_id"]=="two" for r in db.tables["likes"])


def test_failed_marker_write_does_not_mark_batch_complete_and_retry_is_safe(db, monkeypatch):
    original=db.table
    def fail(name):
        q=original(name)
        execute=q.execute
        def call():
            if name=="processed_operations" and q._op=="upsert":raise ValueError("storage down")
            return execute()
        q.execute=call
        return q
    monkeypatch.setattr(db,"table",fail)
    with pytest.raises(Exception,match="persist operation idempotency"):
        push(db,"history_item","new",{"track_id":"ta"})
    assert db.tables["processed_batches"]==[]
    monkeypatch.setattr(db,"table",original)
    result=push(db,"history_item","new",{"track_id":"ta"})
    assert result["results"][0]["status"]=="ignored_duplicate"
    assert sum(r["id"]=="new" for r in db.tables["history_items"])==1


def test_two_overlapping_api_requests_keep_their_verified_identity_and_close_transports(db, monkeypatch):
    import app.db.user_client as context
    barrier=Barrier(2)
    transports=[]
    def identity(request: Request):
        actor=request.headers["x-synthetic-actor"]
        return AuthenticatedUser(id=actor,token="SYNTHETIC_"+actor)
    def factory(token, *, httpx_client):
        actor=token[len("SYNTHETIC_"):]
        transports.append(httpx_client)
        view=StrictDatabase(db.tables,actor=actor)
        barrier.wait(timeout=10)
        return view
    app.dependency_overrides[get_current_user]=identity
    monkeypatch.setattr(context,"get_user_supabase_client",factory)
    try:
        def request(actor):
            response=TestClient(app).post("/me/sync/bootstrap",headers={"X-Synthetic-Actor":actor},json={"device_id":"synthetic"})
            assert response.status_code==200
            return {p["id"] for p in response.json()["data"]["snapshot"]["playlists"]}
        with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(request,[A,B]))
        assert results==[{"pa","pa2"},{"pb"}]
        assert len(transports)==2 and transports[0] is not transports[1]
        assert all(t.is_closed for t in transports)
    finally:app.dependency_overrides.clear()


def test_job_cursor_is_filtered_by_the_request_owner():
    from app.services.download_service import DownloadService
    calls=[]
    class Query:
        def __init__(self):self.filters=[]
        def select(self,*_):return self
        def eq(self,key,value):self.filters.append((key,value));return self
        def order(self,*_,**__):return self
        def limit(self,*_):return self
        def execute(self):calls.append(self.filters);return MagicMock(data=[])
    client=MagicMock();client.table.side_effect=lambda _:Query()
    service=DownloadService(supabase_client=client,auto_recover_stale_jobs=False)
    assert service.list_jobs(A,cursor="foreign-id")==([],None)
    cursor=next(filters for filters in calls if ("id","foreign-id") in filters)
    assert ("user_id",A) in cursor
