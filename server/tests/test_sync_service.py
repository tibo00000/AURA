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
        self._rows.sort(key=lambda r: r.get(key) or "", reverse=desc)
        return self

    def limit(self, n: int):
        self._rows = self._rows[:n]
        return self

    def execute(self):
        matched = [r for r in self._rows if all(f(r) for f in self._filters)]
        resp = MagicMock()
        resp.data = matched
        return resp


class InMemorySupabase:
    """Lightweight in-memory table store for deterministic unit testing of SyncService."""

    def __init__(self, initial_tables: dict[str, list[dict]] | None = None):
        self.tables: dict[str, list[dict]] = {k: list(v) for k, v in (initial_tables or {}).items()}
        self.mutations: list[tuple[str, str, object]] = []

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

        with patch("app.services.sync_service.supabase", fake_db):
            service = SyncService()
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
                "processed_operations": [{"operation_id": "op_already_done"}],
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

        with patch("app.services.sync_service.supabase", fake_db):
            service = SyncService()
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

        with patch("app.services.sync_service.supabase", fake_db):
            service = SyncService()

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
                        "liked_at": "2026-10-16T11:30:00+00:00",
                    },
                ],
                "playback_snapshots": [],
            }
        )

        with patch("app.services.sync_service.supabase", fake_db):
            service = SyncService()
            resp = service.pull_batch(
                user_id="user_1",
                since_token=valid_since_token,
                limit=1,
            )
            self.assertTrue(resp["has_more"])
            self.assertEqual(len(resp["changes"]), 1)
            self.assertEqual(resp["changes"][0]["entity_id"], "trk_new_1")


class TestSyncFastApiRoutes(unittest.TestCase):

    def setUp(self):
        app.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(
            id="11111111-2222-3333-4444-555555555555",
            token="test_jwt",
        )
        self.client = TestClient(app)

    def tearDown(self):
        app.dependency_overrides.clear()

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
        with patch("app.services.sync_service.supabase", fake_db):
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
