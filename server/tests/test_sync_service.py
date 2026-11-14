"""
Unit and FastAPI integration tests for SyncService (sync_service.py) and /me/sync/* routes.

Covers Engineering Rule #8 (multi-level idempotency on batch_id and operation_id),
Last-Write-Wins (LWW) conflict resolution, playlist order_token verification,
and pull_batch cursor pagination / 409 Conflict handling.
"""

import base64
import sys
import unittest
from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

for mod in ("yt_dlp", "ytmusicapi"):
    if mod not in sys.modules:
        sys.modules[mod] = MagicMock()

from fastapi.testclient import TestClient
from app.main import app
from app.core.auth import AuthenticatedUser, get_current_user
from app.db.user_client import get_current_user_client
from app.services.sync_service import (
    SyncService,
    compute_order_token,
    generate_sync_token,
    parse_sync_token,
)


class FakeQueryChain:
    """Composable in-memory chainable query builder simulating the Supabase table API."""

    def __init__(self, rows: list[dict]):
        self._rows = [dict(r) for r in rows]
        self._filters = []
        self._op = "select"
        self._payload = None
        self._orders = []
        self._limit = 1000

    def select(self, *_args, **_kwargs):
        self._op = "select"
        return self

    def insert(self, payload):
        self._op = "insert"
        self._payload = payload
        return self

    def upsert(self, payload, **_kwargs):
        self._op = "upsert"
        self._payload = payload
        return self

    def update(self, payload):
        self._op = "update"
        self._payload = payload
        return self

    def delete(self):
        self._op = "delete"
        return self

    def eq(self, key: str, val):
        self._filters.append(lambda r: r.get(key) == val)
        return self

    def neq(self, key: str, val):
        self._filters.append(lambda r: r.get(key) != val)
        return self

    def gt(self, key: str, val):
        self._filters.append(lambda r: (r.get(key) or "") > val)
        return self

    def ge(self, key: str, val):
        self._filters.append(lambda r: (r.get(key) or 0) >= val)
        return self

    def gte(self, key: str, val):
        self._filters.append(lambda r: (r.get(key) or 0) >= val)
        return self

    def in_(self, key: str, vals):
        s = set(vals or [])
        self._filters.append(lambda r: r.get(key) in s)
        return self

    def order(self, key: str, desc: bool = False):
        self._orders.append((key, desc))
        return self

    def limit(self, n: int):
        self._limit = min(n, 1000)
        return self

    def execute(self):
        matched = [r for r in self._rows if all(f(r) for f in self._filters)]
        for key, desc in reversed(self._orders):
            matched.sort(key=lambda r: (r.get(key) is not None, r.get(key)), reverse=desc)
        resp = MagicMock()
        resp.data = matched[:self._limit]
        return resp


class InMemorySupabase:
    """Lightweight in-memory table store for deterministic unit testing of SyncService."""

    def __init__(self, initial_tables: dict[str, list[dict]] | None = None):
        self.tables: dict[str, list[dict]] = {k: list(v) for k, v in (initial_tables or {}).items()}
        self.mutations: list[tuple[str, str, object]] = []
        self.user_id = "user_1"

    def rpc(self, name, args):
        from copy import deepcopy
        from app.services.sync_service import compute_order_token
        assert name == "aura_mutate_playlist_items"
        parent = self
        class Call:
            def execute(inner):
                before = deepcopy(parent.tables)
                try:
                    playlists = parent.tables.get("playlists", [])
                    pl = next((r for r in playlists if r["id"] == args["p_playlist_id"] and r["user_id"] == parent.user_id), None)
                    if pl is None:
                        raise ValueError("Resource unavailable")
                    rows = parent.tables.setdefault("playlist_items", [])
                    action = args["p_action"]
                    payload = args["p_payload"]
                    stamp = datetime.now(timezone.utc).isoformat()
                    if action == "reorder":
                        if pl["updated_at"] != args["p_expected_updated_at"]:
                            return MagicMock(data={"status": "conflict", "updated_at": pl["updated_at"]})
                        items = payload["items"]
                        ids = [x["playlist_item_id"] for x in items]
                        positions = [x["position"] for x in items]
                        assert len(set(ids)) == len(ids) and len(set(positions)) == len(positions)
                        assert all(any(r["id"] == i and r["playlist_id"] == pl["id"] for r in rows) for i in ids)
                        for item in items:
                            next(r for r in rows if r["id"] == item["playlist_item_id"] and r["playlist_id"] == pl["id"])["position"] = item["position"]
                        all_positions = [r["position"] for r in rows if r["playlist_id"] == pl["id"]]
                        assert len(all_positions) == len(set(all_positions))
                        pl["updated_at"] = stamp
                        return MagicMock(data={"status": "applied", "updated_at": stamp})
                    existing = next((r for r in rows if r["id"] == args["p_item_id"] and r["playlist_id"] == pl["id"]), None)
                    if action == "delete":
                        if existing:
                            rows.remove(existing)
                            pl["updated_at"] = stamp
                        return MagicMock(data={"status": "applied" if existing else "ignored_duplicate", "entity": None})
                    if existing:
                        return MagicMock(data={"status": "ignored_duplicate", "entity": dict(existing)})
                    if any(r["id"] == args["p_item_id"] for r in rows):
                        raise ValueError("Unique collision")
                    position = payload.get("position", 0)
                    if any(r["playlist_id"] == pl["id"] and r["position"] == position for r in rows):
                        for r in rows:
                            if r["playlist_id"] == pl["id"] and r["position"] >= position:
                                r["position"] += 1
                    row = dict(id=args["p_item_id"], playlist_id=pl["id"], track_id=payload["track_id"], position=position, added_at=payload.get("added_at") or stamp)
                    rows.append(row)
                    pl["updated_at"] = stamp
                    return MagicMock(data={"status": "applied", "entity": dict(row)})
                except Exception:
                    parent.tables.clear()
                    parent.tables.update(before)
                    raise
        return Call()

    def table(self, name: str):
        rows = self.tables.setdefault(name, [])
        parent = self

        class BoundChain(FakeQueryChain):
            def execute(self_inner):
                if self_inner._op == "insert":
                    items = self_inner._payload if isinstance(self_inner._payload, list) else [self_inner._payload]
                    for item in items:
                        rows.append(dict(item))
                    parent.mutations.append((name, "insert", self_inner._payload))
                    resp = MagicMock()
                    resp.data = items
                    return resp
                if self_inner._op == "upsert":
                    items = self_inner._payload if isinstance(self_inner._payload, list) else [self_inner._payload]
                    for item in items:
                        rows.append(dict(item))
                    parent.mutations.append((name, "upsert", self_inner._payload))
                    resp = MagicMock()
                    resp.data = items
                    return resp
                if self_inner._op == "update":
                    matched = [r for r in rows if all(f(r) for f in self_inner._filters)]
                    for r in matched:
                        r.update(self_inner._payload)
                    parent.mutations.append((name, "update", self_inner._payload))
                    resp = MagicMock()
                    resp.data = matched
                    return resp
                if self_inner._op == "delete":
                    kept = [r for r in rows if not all(f(r) for f in self_inner._filters)]
                    rows[:] = kept
                    parent.mutations.append((name, "delete", None))
                    resp = MagicMock()
                    resp.data = []
                    return resp
                return super().execute()

        return BoundChain(rows)


class TestSyncTokensAndOrderToken(unittest.TestCase):

    def test_sync_token_roundtrip_and_invalid_fallback(self):
        token = generate_sync_token()
        self.assertTrue(token.startswith("st_"))

        parsed = parse_sync_token(token)
        self.assertIsNotNone(parsed.tzinfo)

        # Invalid tokens safely fallback to UTC epoch (1970-01-01)
        epoch = datetime.fromtimestamp(0, tz=timezone.utc)
        self.assertEqual(parse_sync_token("invalid_prefix"), epoch)
        self.assertEqual(parse_sync_token("st_not_valid_base64!!!"), epoch)

    def test_compute_order_token_deterministic_and_timestamp_sensitive(self):
        token_a = compute_order_token("2026-10-16T10:00:00+00:00")
        token_b = compute_order_token("2026-10-16T10:00:00+00:00")
        token_updated = compute_order_token("2026-10-16T10:05:00+00:00")

        self.assertTrue(token_a.startswith("ord_"))
        self.assertEqual(token_a, token_b)
        self.assertNotEqual(token_a, token_updated)


class TestSyncServiceLogic(unittest.TestCase):

    def test_push_batch_returns_duplicate_status_on_already_processed_batch_id(self):
        fake_db = InMemorySupabase(
            {
                "processed_batches": [
                    {
                        "batch_id": "batch_dup_1",
                        "user_id": "user_1",
                    }
                ]
            }
        )

        with patch("app.api.routes.sync.get_current_user_client", return_value=fake_db):
            service = SyncService(fake_db)
            resp = service.push_batch(
                user_id="user_1",
                device_id="dev_1",
                batch_id="batch_dup_1",
                operations=[
                    {
                        "operation_id": "op_1",
                        "entity_type": "track_like",
                        "entity_id": "trk_1",
                        "operation_type": "upsert",
                        "occurred_at": "2026-10-16T10:00:00+00:00",
                        "payload": {"is_liked": True},
                    }
                ],
            )
            self.assertEqual(resp["batch_id"], "batch_dup_1")
            self.assertEqual(len(resp["results"]), 1)
            self.assertEqual(resp["results"][0]["operation_id"], "op_1")
            self.assertEqual(resp["results"][0]["status"], "ignored_duplicate")
            # No new mutations executed
            self.assertEqual(len(fake_db.mutations), 0)

    def test_push_batch_operation_idempotency_and_lww_conflict_resolution(self):
        fake_db = InMemorySupabase(
            {
                "profiles": [{"id": "user_1", "display_name": "User 1"}],
                "processed_batches": [],
                "processed_operations": [{"operation_id": "op_already_done", "user_id": "user_1"}],
                "user_settings": [
                    {
                        "user_id": "user_1",
                        "sync_enabled": True,
                        "online_search_enabled": True,
                        "online_search_network_policy": "any_network",
                        "stats_sync_network_policy": "wifi_only",
                        "updated_at": "2026-10-16T12:00:00+00:00",
                    }
                ],
            }
        )

        with patch("app.api.routes.sync.get_current_user_client", return_value=fake_db):
            service = SyncService(fake_db)
            resp = service.push_batch(
                user_id="user_1",
                device_id="dev_1",
                batch_id="batch_new_1",
                operations=[
                    {
                        "operation_id": "op_already_done",
                        "entity_type": "track_like",
                        "entity_id": "trk_1",
                        "operation_type": "upsert",
                        "occurred_at": "2026-10-16T11:00:00+00:00",
                        "payload": {"is_liked": True},
                    },
                    # Stale client timestamp (10:00 < 12:00 server) -> LWW ignored_duplicate with server snapshot
                    {
                        "operation_id": "op_stale_settings",
                        "entity_type": "user_settings",
                        "entity_id": "default",
                        "operation_type": "patch",
                        "occurred_at": "2026-10-16T10:00:00+00:00",
                        "payload": {"sync_enabled": False},
                    },
                    # Newer client timestamp (13:00 > 12:00 server) -> LWW merged
                    {
                        "operation_id": "op_fresh_settings",
                        "entity_type": "user_settings",
                        "entity_id": "default",
                        "operation_type": "patch",
                        "occurred_at": "2026-10-16T13:00:00+00:00",
                        "payload": {"sync_enabled": False},
                    },
                ],
            )
            self.assertEqual(resp["results"][0]["status"], "ignored_duplicate")
            self.assertEqual(resp["results"][1]["status"], "ignored_duplicate")
            self.assertTrue(resp["results"][1]["resolved_entity"]["sync_enabled"])
            self.assertEqual(resp["results"][2]["status"], "merged")
            self.assertFalse(resp["results"][2]["resolved_entity"]["sync_enabled"])

    def test_playlist_reorder_validates_base_order_token(self):
        playlist_updated_at = "2026-10-16T08:00:00+00:00"
        valid_token = compute_order_token(playlist_updated_at)

        fake_db = InMemorySupabase(
            {
                "profiles": [{"id": "user_1", "display_name": "User 1"}],
                "processed_batches": [],
                "processed_operations": [],
                "playlists": [
                    {
                        "id": "pl_1",
                        "user_id": "user_1",
                        "name": "Mix",
                        "is_pinned": False,
                        "created_at": playlist_updated_at,
                        "updated_at": playlist_updated_at,
                    }
                ],
                "playlist_items": [
                    {"id": "item_1", "playlist_id": "pl_1", "track_id": "trk_1", "position": 0},
                    {"id": "item_2", "playlist_id": "pl_1", "track_id": "trk_2", "position": 1},
                ],
            }
        )

        with patch("app.api.routes.sync.get_current_user_client", return_value=fake_db):
            service = SyncService(fake_db)

            # 1. Mismatched base_order_token -> conflict (base_outdated)
            resp_conflict = service.push_batch(
                user_id="user_1",
                device_id="dev_1",
                batch_id="batch_reorder_conflict",
                operations=[
                    {
                        "operation_id": "op_reorder_bad",
                        "entity_type": "playlist_reorder",
                        "entity_id": "pl_1",
                        "operation_type": "reorder",
                        "occurred_at": "2026-10-16T12:00:00+00:00",
                        "payload": {
                            "base_order_token": "ord_wrong_token",
                            "items": [
                                {"playlist_item_id": "item_2", "position": 0},
                                {"playlist_item_id": "item_1", "position": 1},
                            ],
                        },
                    }
                ],
            )
            self.assertEqual(resp_conflict["results"][0]["status"], "conflict")
            self.assertEqual(resp_conflict["results"][0]["conflict"]["reason"], "base_outdated")

            # 2. Matching base_order_token -> applied and new rotated order_token returned
            resp_ok = service.push_batch(
                user_id="user_1",
                device_id="dev_1",
                batch_id="batch_reorder_ok",
                operations=[
                    {
                        "operation_id": "op_reorder_ok",
                        "entity_type": "playlist_reorder",
                        "entity_id": "pl_1",
                        "operation_type": "reorder",
                        "occurred_at": "2026-10-16T12:05:00+00:00",
                        "payload": {
                            "base_order_token": valid_token,
                            "items": [
                                {"playlist_item_id": "item_2", "position": 0},
                                {"playlist_item_id": "item_1", "position": 1},
                            ],
                        },
                    }
                ],
            )
            self.assertEqual(resp_ok["results"][0]["status"], "applied")
            new_token = resp_ok["results"][0]["resolved_entity"]["order_token"]
            self.assertTrue(new_token.startswith("ord_"))
            self.assertNotEqual(new_token, valid_token)

    def test_pull_batch_filters_changes_since_cursor_and_paginates(self):
        since_iso = "2026-10-16T10:00:00+00:00"
        valid_since_token = "st_" + base64.b64encode(since_iso.encode("utf-8")).decode("utf-8")

        fake_db = InMemorySupabase(
            {
                "user_settings": [],
                "playlists": [],
                "playlist_items": [],
                "likes": [
                    {
                        "user_id": "user_1",
                        "track_id": "trk_old",
                        "liked_at": "2026-10-16T09:00:00+00:00",
                    },
                    {
                        "user_id": "user_1",
                        "track_id": "trk_new_1",
                        "liked_at": "2026-10-16T11:00:00+00:00",
                    },
                    {
                        "user_id": "user_1",
                        "track_id": "trk_new_2",
                        "liked_at": "2026-10-16T11:00:00+00:00",
                    },
                ],
                "playback_snapshots": [],
            }
        )

        with patch("app.api.routes.sync.get_current_user_client", return_value=fake_db):
            service = SyncService(fake_db)
            resp = service.pull_batch(
                user_id="user_1",
                since_token=valid_since_token,
                limit=1,
            )
            self.assertTrue(resp["has_more"])
            self.assertEqual(len(resp["changes"]), 1)
            self.assertEqual(resp["changes"][0]["entity_id"], "trk_new_1")
            second = service.pull_batch("user_1", resp["next_pull_token"]["value"], limit=1)
            self.assertFalse(second["has_more"])
            self.assertEqual(second["changes"][0]["entity_id"], "trk_new_2")
            empty = service.pull_batch("user_1", second["next_pull_token"]["value"], limit=1)
            self.assertEqual(empty["changes"], [])
            self.assertEqual(empty["next_pull_token"], second["next_pull_token"])

    def test_pull_passes_data_api_cap_with_equal_timestamps_and_unsorted_rows(self):
        rows = [
            {"user_id": "user_1", "track_id": f"trk_{index:04d}",
             "liked_at": "2026-10-16T11:00:00+00:00"}
            for index in reversed(range(1120))
        ]
        db = InMemorySupabase({"likes": rows})
        cursor = "st_" + base64.b64encode(b"2026-10-16T10:00:00+00:00").decode()
        collected = []
        for _ in range(6):
            page = SyncService(db).pull_batch("user_1", cursor, 200, ["track_like"])
            collected.extend(change["entity_id"] for change in page["changes"])
            cursor = page["next_pull_token"]["value"]
        self.assertFalse(page["has_more"])
        self.assertEqual(collected, [f"trk_{index:04d}" for index in range(1120)])


class TestSyncFastApiRoutes(unittest.TestCase):

    def setUp(self):
        app.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(
            id="11111111-2222-3333-4444-555555555555",
            token="test_jwt",
        )
        app.dependency_overrides[get_current_user_client] = lambda: InMemorySupabase()
        self.client = TestClient(app)

    def tearDown(self):
        app.dependency_overrides.clear()

    def test_v2_playlist_replay_returns_original_revision_even_when_batch_is_processed(self):
        db = InMemorySupabase()
        calls = []

        def playlist_rpc(name, args):
            calls.append((name, args["p_operation_id"]))
            result = {"status": "applied" if len(calls) == 1 else "ignored_duplicate", "playlist_revision": 12}
            return MagicMock(execute=lambda: MagicMock(data=result))

        db.rpc = playlist_rpc
        app.dependency_overrides[get_current_user_client] = lambda: db
        stamp = "2026-11-14T10:00:00+00:00"
        request = {"device_id": "desktop", "batch_id": "batch-v2", "sent_at": stamp, "operations": [{
            "operation_id": "stable-op", "entity_type": "playlist_item", "entity_id": "occurrence",
            "operation_type": "create", "device_id": "desktop", "occurred_at": stamp,
            "payload": {"playlist_sync_version": 2, "playlist_id": "playlist", "track_id": "title"},
        }]}
        for status in ("applied", "ignored_duplicate"):
            response = self.client.post("/me/sync/push-batch", json=request)
            self.assertEqual(response.status_code, 200)
            outcome = response.json()["data"]["results"][0]
            self.assertEqual((outcome["status"], outcome["playlist_revision"]), (status, 12))
        self.assertEqual(calls, [("aura_apply_playlist_operation", "stable-op")] * 2)

    def test_health_endpoint(self):
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["data"]["status"], "ok")
        self.assertIsNone(body["error"])

    def test_pull_batch_route_returns_409_on_invalid_sync_token(self):
        response = self.client.post(
            "/me/sync/pull-batch",
            json={"device_id": "dev_test", "since_token": "corrupted_token", "limit": 50},
        )
        self.assertEqual(response.status_code, 409)
        body = response.json()
        self.assertEqual(body["detail"]["code"], "conflict")
        self.assertEqual(body["detail"]["details"]["reason"], "stale_sync_token")

    def test_bootstrap_route_returns_structured_payload(self):
        fake_db = InMemorySupabase(
            {
                "profiles": [{"id": "11111111-2222-3333-4444-555555555555", "display_name": "Tester"}],
                "user_settings": [
                    {
                        "user_id": "11111111-2222-3333-4444-555555555555",
                        "sync_enabled": True,
                        "online_search_enabled": True,
                        "online_search_network_policy": "any_network",
                        "stats_sync_network_policy": "wifi_only",
                        "updated_at": "2026-10-16T10:00:00+00:00",
                    }
                ],
                "playlists": [],
                "playlist_items": [],
                "likes": [],
                "playback_snapshots": [],
            }
        )
        app.dependency_overrides[get_current_user_client] = lambda: fake_db
        with patch("app.api.routes.sync.get_current_user_client", return_value=fake_db):
            response = self.client.post(
                "/me/sync/bootstrap",
                json={
                    "device_id": "dev_test",
                    "app_version": "0.4.0",
                },
            )
            self.assertEqual(response.status_code, 200)
            data = response.json()["data"]
            self.assertTrue(data["sync_token"]["value"].startswith("st_"))
            self.assertTrue(data["snapshot"]["user_settings"]["sync_enabled"])


if __name__ == "__main__":
    unittest.main()
