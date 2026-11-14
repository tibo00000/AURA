"""
Core Synchronization Service for AURA.

Implements SRV-007 sync batch logic and conflict resolution rules
as defined in docs/server/sync-conflict-resolution.md.
"""

import base64
import hashlib
import json
import logging
from datetime import datetime, timezone
from typing import Any, Dict, List, Tuple

from supabase import Client
from app.services.exceptions import BadRequest

logger = logging.getLogger(__name__)


def generate_sync_token() -> str:
    """Generate a SyncToken encoding the current UTC timestamp in Base64."""
    now_iso = datetime.now(timezone.utc).isoformat()
    encoded = base64.b64encode(now_iso.encode("utf-8")).decode("utf-8")
    return f"st_{encoded}"


def parse_sync_token(token: str) -> datetime:
    """Parse a SyncToken and extract its UTC datetime. Fallbacks to EPOCH on error."""
    if not token or not token.startswith("st_"):
        return datetime.fromtimestamp(0, tz=timezone.utc)
    try:
        encoded_part = token[3:]
        decoded_bytes = base64.b64decode(encoded_part.encode("utf-8"))
        decoded_str = decoded_bytes.decode("utf-8")
        return datetime.fromisoformat(decoded_str)
    except Exception as e:
        logger.warning("Failed to parse SyncToken %r: %s. Falling back to epoch.", token, e)
        return datetime.fromtimestamp(0, tz=timezone.utc)


def compute_order_token(playlist_updated_at: str) -> str:
    """Compute a deterministic hash token representing the current order state of a playlist."""
    if not playlist_updated_at:
        playlist_updated_at = datetime.now(timezone.utc).isoformat()
    hasher = hashlib.md5(playlist_updated_at.encode("utf-8"))
    return f"ord_{hasher.hexdigest()[:8]}"


class SyncService:
    """Service handling synchronization logic, idempotency, bootstrap, push-batch and pull-batch."""

    def __init__(self, client: Client):
        self.db = client

    def bootstrap(self, user_id: str, device_id: str, use_playlist_snapshot: bool = False) -> dict:
        """
        Compile initial client hydration snapshot containing user settings,
        playlists (with items), track likes, and the last playback snapshot.
        """
        logger.info("Sync Bootstrap requested for user %s on device %s", user_id, device_id)
        started_at = datetime.now(timezone.utc)
        token_val = "st_" + base64.b64encode(started_at.isoformat().encode()).decode()
        
        # Ensure user profile exists in profiles table to satisfy Foreign Key constraints
        try:
            prof_res = self.db.table("profiles").select("*").eq("id", user_id).execute()
            if not prof_res.data:
                logger.info("Initializing missing user profile for %s in profiles table", user_id)
                self.db.table("profiles").insert({
                    "id": user_id,
                    "display_name": f"User {user_id[:8]}",
                    "avatar_uri": None,
                }).execute()
        except Exception as pe:
            logger.warning("Failed to verify/insert user profile %s: %s. Continuing anyway.", user_id, pe)

        try:
            # 1. User Settings
            settings_res = self.db.table("user_settings").select("*").eq("user_id", user_id).execute()
            settings = None
            if settings_res.data:
                s = settings_res.data[0]
                settings = {
                    "sync_enabled": s["sync_enabled"],
                    "online_search_enabled": s["online_search_enabled"],
                    "online_search_network_policy": s["online_search_network_policy"],
                    "stats_sync_network_policy": s["stats_sync_network_policy"],
                    "updated_at": s.get("updated_at"),
                }
            else:
                # Fallback default settings
                now = datetime.now(timezone.utc).isoformat()
                settings = {
                    "sync_enabled": True,
                    "online_search_enabled": True,
                    "online_search_network_policy": "any_network",
                    "stats_sync_network_policy": "wifi_only",
                    "updated_at": now,
                }

            # 2. Playlists & Playlist Items
            playlists_list = []
            if not use_playlist_snapshot:
                playlists_list = self.db.table("playlists").select("*").eq("user_id", user_id).execute().data or []
            playlists = []
            
            for pl in playlists_list:
                pl_id = pl["id"]
                items_res = self.db.table("playlist_items").select("*").eq("playlist_id", pl_id).order("position").execute()
                items_list = items_res.data or []
                
                playlists.append({
                    "id": pl_id,
                    "name": pl["name"],
                    "cover_uri": pl.get("cover_uri"),
                    "is_pinned": pl["is_pinned"],
                    "created_at": pl["created_at"],
                    "updated_at": pl["updated_at"],
                    "items": [
                        {
                            "id": item["id"],
                            "track_id": item["track_id"],
                            "position": item["position"],
                            "added_at": item["added_at"],
                            "added_from_context_type": item.get("added_from_context_type"),
                            "added_from_context_id": item.get("added_from_context_id"),
                        }
                        for item in items_list
                    ]
                })

            # 3. Track Likes (Favorites)
            likes_list = []
            last_track_id = None
            while True:
                query = self.db.table("likes").select("*").eq("user_id", user_id).order("track_id").limit(200)
                if last_track_id is not None:
                    query = query.gt("track_id", last_track_id)
                page = query.execute().data or []
                likes_list.extend(page)
                if len(page) < 200:
                    break
                last_track_id = page[-1]["track_id"]
            track_likes = [
                {
                    "track_id": lk["track_id"],
                    "liked_at": lk["liked_at"],
                    "source_context_type": lk.get("source_context_type"),
                    "source_context_id": lk.get("source_context_id"),
                }
                for lk in likes_list
            ]

            # 4. Playback Snapshot
            snapshot_res = self.db.table("playback_snapshots").select("*").eq("user_id", user_id).execute()
            playback_snapshot = None
            if snapshot_res.data:
                snap = snapshot_res.data[0]
                playback_snapshot = {
                    "current_track_id": snap.get("current_track_id"),
                    "playback_context_type": snap.get("playback_context_type"),
                    "playback_context_id": snap.get("playback_context_id"),
                    "playback_context_index": snap.get("playback_context_index"),
                    "playlist_occurrence_id": snap.get("playlist_occurrence_id"),
                    "position_ms": snap["position_ms"],
                    "shuffle_enabled": snap["shuffle_enabled"],
                    "repeat_mode": snap["repeat_mode"],
                    "updated_at": snap.get("updated_at"),
                }

            return {
                "sync_token": {
                    "value": token_val,
                    "issued_at": started_at.isoformat(),
                },
                "snapshot": {
                    "user_settings": settings,
                    "playlists": playlists,
                    "track_likes": track_likes,
                    "playback_snapshot": playback_snapshot,
                }
            }
        except Exception as e:
            logger.error("Bootstrap execution failed for user %s: %s", user_id, e)
            raise BadRequest("Bootstrap execution failed") from None

    def push_batch(self, user_id: str, device_id: str, batch_id: str, operations: List[dict]) -> dict:
        """
        Process a batch of sync operations sequentially.
        Guarantees idempotency at both batch and operation levels and resolves concurrency conflicts based on LWW or order tokens.
        """
        logger.info("Sync Push Batch %s received with %d operations for user %s", batch_id, len(operations), user_id)
        
        def is_playlist_v2(op):
            return (
                op.get("entity_type") in ("playlist", "playlist_item", "playlist_reorder")
                and (op.get("payload") or {}).get("playlist_sync_version") == 2
            )

        # Playlist v2 replays must fetch the original per-operation revision.
        if batch_id:
            try:
                batch_res = self.db.table("processed_batches").select("*").eq("batch_id", batch_id).eq("user_id", user_id).execute()
                if batch_res.data and not any(is_playlist_v2(op) for op in operations):
                    logger.info("Batch %s already processed for user %s. Returning idempotent duplicate response.", batch_id, user_id)
                    return {
                        "batch_id": batch_id,
                        "results": [
                            {
                                "operation_id": op.get("operation_id", "unknown"),
                                "entity_type": op.get("entity_type", "unknown"),
                                "entity_id": op.get("entity_id", "unknown"),
                                "status": "ignored_duplicate",
                                "server_updated_at": datetime.now(timezone.utc).isoformat(),
                                "resolved_entity": None,
                                "conflict": None,
                            }
                            for op in operations
                        ],
                        "next_pull_token": {
                            "value": generate_sync_token(),
                            "issued_at": datetime.now(timezone.utc).isoformat(),
                        }
                    }
            except Exception as be:
                raise BadRequest("Unable to verify batch idempotency") from None

        # Ensure user profile exists in profiles table to satisfy Foreign Key constraints
        try:
            prof_res = self.db.table("profiles").select("*").eq("id", user_id).execute()
            if not prof_res.data:
                logger.info("Initializing missing user profile for %s in profiles table during push", user_id)
                self.db.table("profiles").insert({
                    "id": user_id,
                    "display_name": f"User {user_id[:8]}",
                    "avatar_uri": None,
                }).execute()
        except Exception as pe:
            logger.warning("Failed to verify/insert user profile %s during push: %s. Continuing anyway.", user_id, pe)

        results = []
        ops_to_record = []

        # Bulk idempotency pre-fetch to avoid N round-trips to Supabase
        all_op_ids = [op["operation_id"] for op in operations if op.get("operation_id") and not is_playlist_v2(op)]
        already_processed_ids = set()
        if all_op_ids:
            try:
                for i in range(0, len(all_op_ids), 100):
                    chunk_ids = all_op_ids[i:i + 100]
                    dup_batch = self.db.table("processed_operations").select("operation_id").in_("operation_id", chunk_ids).eq("user_id", user_id).execute()
                    if dup_batch.data:
                        already_processed_ids.update(r["operation_id"] for r in dup_batch.data)
            except Exception as e:
                raise BadRequest("Unable to verify operation idempotency") from None

        for op in operations:
            op_id = op.get("operation_id")
            entity_type = op.get("entity_type")
            entity_id = op.get("entity_id")
            op_type = op.get("operation_type")
            occurred_at_str = op.get("occurred_at")
            payload = op.get("payload") or {}
            playlist_v2 = is_playlist_v2(op)

            if not op_id or not entity_type or not entity_id or not op_type:
                logger.warning("Malformed operation omitted: %r", op)
                continue

            # 1. Idempotency Check (Fast in-memory set)
            if not playlist_v2 and op_id in already_processed_ids:
                results.append({
                    "operation_id": op_id,
                    "entity_type": entity_type,
                    "entity_id": entity_id,
                    "status": "ignored_duplicate",
                    "server_updated_at": datetime.now(timezone.utc).isoformat(),
                    "resolved_entity": None,
                    "conflict": None,
                })
                continue

            # 2. Run sequential routing & resolving rules
            status = "applied"
            conflict = None
            resolved_entity = None
            playlist_revision = None

            try:
                if playlist_v2:
                    result = self.db.rpc("aura_apply_playlist_operation", {
                        "p_operation_id": op_id,
                        "p_entity_type": entity_type,
                        "p_entity_id": entity_id,
                        "p_operation_type": op_type,
                        "p_payload": payload,
                    }).execute().data
                    status = result["status"]
                    if status == "conflict":
                        conflict = {"reason": "playlist_changed", "retryable": False}
                    else:
                        playlist_revision = result["playlist_revision"]
                elif entity_type == "user_settings":
                    status, resolved_entity = self._handle_user_settings_sync(user_id, occurred_at_str, payload)
                
                elif entity_type == "playlist":
                    status, resolved_entity, conflict = self._handle_playlist_sync(user_id, entity_id, op_type, occurred_at_str, payload)
                
                elif entity_type == "playlist_item":
                    status, resolved_entity = self._handle_playlist_item_sync(user_id, entity_id, op_type, payload)
                
                elif entity_type == "playlist_reorder":
                    status, resolved_entity, conflict = self._handle_playlist_reorder_sync(user_id, entity_id, payload)
                
                elif entity_type == "track_like":
                    status, resolved_entity = self._handle_track_like_sync(user_id, entity_id, payload)
                
                elif entity_type == "playback_snapshot":
                    status, resolved_entity = self._handle_playback_snapshot_sync(user_id, payload)
                
                elif entity_type == "history_item":
                    status, resolved_entity = self._handle_history_item_sync(user_id, entity_id, payload)
                
                elif entity_type == "listening_session":
                    status, resolved_entity = self._handle_listening_session_sync(user_id, entity_id, payload)
                
                elif entity_type == "playback_event":
                    status, resolved_entity = self._handle_playback_event_sync(user_id, entity_id, payload)
                
                else:
                    logger.warning("Unsupported entity type in sync push: %s", entity_type)
                    status = "conflict"
                    conflict = {
                        "reason": "unsupported_entity",
                        "server_entity": None,
                        "client_payload": payload,
                        "retryable": False,
                    }

                # 3. Stage operation for bulk idempotency recording
                if status in ("applied", "merged", "ignored_duplicate"):
                    already_processed_ids.add(op_id)
                    if not playlist_v2:
                        ops_to_record.append({
                            "operation_id": op_id,
                            "user_id": user_id,
                        })

            except Exception as e:
                logger.error("Error processing operation %s (%s): %s", op_id, entity_type, e, exc_info=True)
                status = "conflict"
                conflict = {
                    "reason": "database_error",
                    "server_entity": None,
                    "client_payload": payload,
                    "retryable": True,
                }

            results.append({
                "operation_id": op_id,
                "entity_type": entity_type,
                "entity_id": entity_id,
                "status": status,
                "server_updated_at": datetime.now(timezone.utc).isoformat(),
                "resolved_entity": resolved_entity,
                "conflict": conflict,
                "playlist_revision": playlist_revision,
            })

        # Bulk record all successful operations in idempotency table
        if ops_to_record:
            try:
                for i in range(0, len(ops_to_record), 100):
                    self.db.table("processed_operations").upsert(ops_to_record[i:i + 100], on_conflict="user_id,operation_id", ignore_duplicates=True).execute()
            except Exception as e:
                raise BadRequest("Unable to persist operation idempotency") from None

        # Record completed batch for future idempotency checks
        if batch_id and len(results) == len(operations) and all(r["status"] != "conflict" for r in results):
            try:
                self.db.table("processed_batches").upsert({
                    "batch_id": batch_id,
                    "user_id": user_id,
                    "processed_at": datetime.now(timezone.utc).isoformat(),
                }, on_conflict="user_id,batch_id", ignore_duplicates=True).execute()
            except Exception as be:
                raise BadRequest("Unable to persist batch idempotency") from None

        return {
            "batch_id": batch_id,
            "results": results,
            "next_pull_token": {
                "value": generate_sync_token(),
                "issued_at": datetime.now(timezone.utc).isoformat(),
            }
        }

    def pull_batch(self, user_id: str, since_token: str, limit: int = 200, entity_types: List[str] = None) -> dict:
        """
        Pull all server-side changes (upsert or delete) occurred since the SyncToken cursor timestamp.
        """
        logger.info("Sync Pull Batch requested since token %r for user %s", since_token, user_id)
        if limit is None:
            limit = 200
        if not 1 <= limit <= 200:
            raise BadRequest("Sync page size must be between 1 and 200")
        since_id = None
        if since_token.startswith("st_v2."):
            try:
                stamp, since_id = json.loads(base64.urlsafe_b64decode(since_token.removeprefix("st_v2.")))
                since_time = datetime.fromisoformat(stamp)
                if since_time.tzinfo is None or not isinstance(since_id, str) or not since_id:
                    raise ValueError("Invalid cursor")
            except (TypeError, ValueError) as exc:
                raise BadRequest("Invalid sync cursor") from exc
        else:
            since_time = parse_sync_token(since_token)
        since_iso = since_time.isoformat()

        def changes_since(query, column, key, prefix):
            """Fetch only the candidates needed to merge each entity into the next page."""
            candidates = []
            if since_id is not None and (since_id.startswith(prefix) or prefix > since_id):
                tied = query().eq(column, since_iso)
                if since_id.startswith(prefix):
                    tied = tied.gt(key, since_id[len(prefix):])
                candidates = tied.order(key).limit(limit + 1).execute().data or []
            slots = limit + 1 - len(candidates)
            if slots:
                later = query().gt(column, since_iso).order(column).order(key).limit(slots).execute()
                candidates += later.data or []
            return candidates

        changes = []
        try:
            # 1. user_settings
            if not entity_types or "user_settings" in entity_types:
                rows = changes_since(lambda: self.db.table("user_settings").select("*").eq("user_id", user_id),
                                     "updated_at", "user_id", "chg_set_")
                for item in rows:
                    changes.append({
                        "change_id": f"chg_set_{item['user_id']}",
                        "entity_type": "user_settings",
                        "entity_id": "default",
                        "change_type": "upsert",
                        "server_updated_at": item["updated_at"],
                        "payload": {
                            "sync_enabled": item["sync_enabled"],
                            "online_search_enabled": item["online_search_enabled"],
                            "online_search_network_policy": item["online_search_network_policy"],
                            "stats_sync_network_policy": item["stats_sync_network_policy"],
                            "updated_at": item["updated_at"],
                        }
                    })

            # 2. playlists
            if not entity_types or "playlist" in entity_types:
                rows = changes_since(lambda: self.db.table("playlists").select("*").eq("user_id", user_id),
                                     "updated_at", "id", "chg_pl_")
                for item in rows:
                    changes.append({
                        "change_id": f"chg_pl_{item['id']}",
                        "entity_type": "playlist",
                        "entity_id": item["id"],
                        "change_type": "upsert",
                        "server_updated_at": item["updated_at"],
                        "payload": {
                            "id": item["id"],
                            "name": item["name"],
                            "cover_uri": item.get("cover_uri"),
                            "is_pinned": item["is_pinned"],
                            "updated_at": item["updated_at"],
                        }
                    })

            # 3. playlist_items
            if not entity_types or "playlist_item" in entity_types:
                rows = changes_since(
                    lambda: self.db.table("playlist_items").select("*,playlists!inner(user_id)")
                    .eq("playlists.user_id", user_id),
                    "added_at", "id", "chg_pli_",
                )
                for item in rows:
                    changes.append({
                        "change_id": f"chg_pli_{item['id']}",
                        "entity_type": "playlist_item",
                        "entity_id": item["id"],
                        "change_type": "upsert",
                        "server_updated_at": item["added_at"],
                        "payload": {
                            "id": item["id"],
                            "playlist_id": item["playlist_id"],
                            "track_id": item["track_id"],
                            "position": item["position"],
                            "added_at": item["added_at"],
                            "added_from_context_type": item.get("added_from_context_type"),
                            "added_from_context_id": item.get("added_from_context_id"),
                        }
                    })

            # 4. track_like (likes)
            if not entity_types or "track_like" in entity_types:
                rows = changes_since(lambda: self.db.table("likes").select("*").eq("user_id", user_id),
                                     "liked_at", "track_id", "chg_lk_")
                for item in rows:
                    changes.append({
                        "change_id": f"chg_lk_{item['track_id']}",
                        "entity_type": "track_like",
                        "entity_id": item["track_id"],
                        "change_type": "upsert",
                        "server_updated_at": item["liked_at"],
                        "payload": {
                            "track_id": item["track_id"],
                            "is_liked": True,
                            "liked_at": item["liked_at"],
                            "source_context_type": item.get("source_context_type"),
                            "source_context_id": item.get("source_context_id"),
                        }
                    })

            # 5. playback_snapshot
            if not entity_types or "playback_snapshot" in entity_types:
                rows = changes_since(lambda: self.db.table("playback_snapshots").select("*").eq("user_id", user_id),
                                     "updated_at", "user_id", "chg_snap_")
                for item in rows:
                    changes.append({
                        "change_id": f"chg_snap_{item['user_id']}",
                        "entity_type": "playback_snapshot",
                        "entity_id": "default",
                        "change_type": "upsert",
                        "server_updated_at": item["updated_at"],
                        "payload": {
                            "current_track_id": item.get("current_track_id"),
                            "playback_context_type": item.get("playback_context_type"),
                            "playback_context_id": item.get("playback_context_id"),
                            "playback_context_index": item.get("playback_context_index"),
                            "playlist_occurrence_id": item.get("playlist_occurrence_id"),
                            "position_ms": item["position_ms"],
                            "shuffle_enabled": item["shuffle_enabled"],
                            "repeat_mode": item["repeat_mode"],
                            "updated_at": item["updated_at"],
                        }
                    })

            # Sort all changes chronologically
            def change_key(change):
                return datetime.fromisoformat(change["server_updated_at"]), change["change_id"]

            if since_id is not None:
                changes = [change for change in changes if change_key(change) > (since_time, since_id)]
            changes.sort(key=change_key)

            # Paginate according to limit
            has_more = len(changes) > limit
            paginated_changes = changes[:limit]

            next_token_time = since_iso
            next_token = since_token
            if paginated_changes:
                last = paginated_changes[-1]
                next_token_time = last["server_updated_at"]
                cursor = json.dumps([next_token_time, last["change_id"]]).encode("utf-8")
                next_token = "st_v2." + base64.urlsafe_b64encode(cursor).decode("ascii")
            
            return {
                "changes": paginated_changes,
                "next_pull_token": {
                    "value": next_token,
                    "issued_at": next_token_time,
                },
                "has_more": has_more,
            }
        except Exception as e:
            logger.error("Pull batch execution failed for user %s: %s", user_id, e)
            raise BadRequest("Pull batch failed") from None

    # --- PRIVATE ENTITY HANDLERS ---

    def _handle_user_settings_sync(self, user_id: str, occurred_at: str, payload: dict) -> Tuple[str, dict]:
        """Process LWW user settings patch sync."""
        db_res = self.db.table("user_settings").select("*").eq("user_id", user_id).execute()
        
        if not db_res.data:
            # Creation
            now = datetime.now(timezone.utc).isoformat()
            insert_data = {
                "user_id": user_id,
                "sync_enabled": payload.get("sync_enabled", True),
                "online_search_enabled": payload.get("online_search_enabled", True),
                "online_search_network_policy": payload.get("online_search_network_policy", "any_network"),
                "stats_sync_network_policy": payload.get("stats_sync_network_policy", "wifi_only"),
                "updated_at": now,
            }
            self.db.table("user_settings").insert(insert_data).execute()
            return "applied", insert_data

        server_data = db_res.data[0]
        server_updated = server_data.get("updated_at")
        
        # LWW: Apply changes only if occurred_at is greater than database update timestamp
        client_occurred = occurred_at or datetime.now(timezone.utc).isoformat()
        if server_updated and client_occurred < server_updated:
            # Ignored due to stale client state
            return "ignored_duplicate", server_data

        # Update
        updates = {
            "sync_enabled": payload.get("sync_enabled", server_data["sync_enabled"]),
            "online_search_enabled": payload.get("online_search_enabled", server_data["online_search_enabled"]),
            "online_search_network_policy": payload.get("online_search_network_policy", server_data["online_search_network_policy"]),
            "stats_sync_network_policy": payload.get("stats_sync_network_policy", server_data["stats_sync_network_policy"]),
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        self.db.table("user_settings").update(updates).eq("user_id", user_id).execute()
        return "merged", updates

    def _handle_playlist_sync(self, user_id: str, playlist_id: str, op_type: str, occurred_at: str, payload: dict) -> Tuple[str, dict, dict]:
        """Process playlist creation, LWW update or deletion."""
        db_res = self.db.table("playlists").select("*").eq("id", playlist_id).eq("user_id", user_id).execute()
        
        if op_type == "delete":
            if db_res.data:
                self.db.table("playlists").delete().eq("id", playlist_id).eq("user_id", user_id).execute()
                return "applied", None, None
            return "ignored_duplicate", None, None

        if not db_res.data:
            # Creation
            now = datetime.now(timezone.utc).isoformat()
            insert_data = {
                "id": playlist_id,
                "user_id": user_id,
                "name": payload.get("name", "Unnamed Playlist"),
                "cover_uri": payload.get("cover_uri"),
                "is_pinned": payload.get("is_pinned", False),
                "created_at": now,
                "updated_at": now,
            }
            self.db.table("playlists").insert(insert_data).execute()
            return "applied", insert_data, None

        server_data = db_res.data[0]
        
        # LWW: Apply updates only if client occurred_at > database updated_at
        client_occurred = occurred_at or datetime.now(timezone.utc).isoformat()
        if server_data["updated_at"] and client_occurred < server_data["updated_at"]:
            return "ignored_duplicate", server_data, None

        # Update
        updates = {
            "name": payload.get("name", server_data["name"]),
            "cover_uri": payload.get("cover_uri", server_data["cover_uri"]),
            "is_pinned": payload.get("is_pinned", server_data["is_pinned"]),
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        self.db.table("playlists").update(updates).eq("id", playlist_id).eq("user_id", user_id).execute()
        return "merged", updates, None

    def _owned_parent(self, table: str, parent_id: str, user_id: str) -> dict:
        rows = self.db.table(table).select("*").eq("id", parent_id).eq("user_id", user_id).execute().data
        if not rows:
            raise BadRequest("Resource unavailable")
        return rows[0]

    def _handle_playlist_item_sync(self, user_id: str, item_id: str, op_type: str, payload: dict) -> Tuple[str, dict]:
        """Authorize the parent; shift/insert/delete atomically under its SQL row lock."""
        playlist_id = payload.get("playlist_id")
        if not playlist_id:
            raise BadRequest("playlist_id is required")
        self._owned_parent("playlists", playlist_id, user_id)
        result = self.db.rpc("aura_mutate_playlist_items", {
            "p_playlist_id": playlist_id,
            "p_item_id": item_id,
            "p_action": "delete" if op_type == "delete" else "insert",
            "p_payload": payload,
        }).execute().data
        return result["status"], result.get("entity")

    def _handle_playlist_reorder_sync(self, user_id: str, playlist_id: str, payload: dict) -> Tuple[str, dict, dict]:
        rows = self.db.table("playlists").select("*").eq("id", playlist_id).eq("user_id", user_id).execute().data
        if not rows:
            return "conflict", None, {
                "reason": "playlist_not_found", "server_entity": None,
                "client_payload": payload, "retryable": False,
            }
        current = rows[0]
        server_token = compute_order_token(current["updated_at"])
        if payload.get("base_order_token") != server_token:
            return "conflict", None, {
                "reason": "base_outdated",
                "server_entity": {"playlist_id": playlist_id, "order_token": server_token},
                "client_payload": {"base_order_token": payload.get("base_order_token")},
                "retryable": True,
            }
        items = payload.get("items", [])
        if not isinstance(items, list) or any(
            not isinstance(item, dict)
            or not isinstance(item.get("playlist_item_id"), str)
            or type(item.get("position")) is not int or item["position"] < 0
            for item in items
        ):
            raise BadRequest("Invalid playlist order")
        ids = [item["playlist_item_id"] for item in items]
        positions = [item["position"] for item in items]
        if len(set(ids)) != len(ids) or len(set(positions)) != len(positions):
            raise BadRequest("Invalid playlist order")
        if ids:
            members = self.db.table("playlist_items").select("id").eq("playlist_id", playlist_id).in_("id", ids).execute().data
            if {row["id"] for row in (members or [])} != set(ids):
                raise BadRequest("Resource unavailable")
        result = self.db.rpc("aura_mutate_playlist_items", {
            "p_playlist_id": playlist_id, "p_item_id": None, "p_action": "reorder",
            "p_payload": {"items": items},
            "p_expected_updated_at": current["updated_at"],
        }).execute().data
        order = {"playlist_id": playlist_id, "order_token": compute_order_token(result["updated_at"])}
        if result["status"] == "conflict":
            return "conflict", None, {
                "reason": "base_outdated", "server_entity": order,
                "client_payload": {"base_order_token": payload.get("base_order_token")},
                "retryable": True,
            }
        return result["status"], order, None

    def _handle_track_like_sync(self, user_id: str, track_id: str, payload: dict) -> Tuple[str, dict]:
        """Process track like/favorite state sync."""
        is_liked = payload.get("is_liked", True)
        
        db_res = self.db.table("likes").select("*").eq("user_id", user_id).eq("track_id", track_id).execute()

        if is_liked:
            if db_res.data:
                return "ignored_duplicate", db_res.data[0]
            # Insert like
            insert_data = {
                "user_id": user_id,
                "track_id": track_id,
                "liked_at": payload.get("liked_at") or datetime.now(timezone.utc).isoformat(),
                "source_context_type": payload.get("source_context_type"),
                "source_context_id": payload.get("source_context_id"),
            }
            self.db.table("likes").insert(insert_data).execute()
            return "applied", insert_data
        else:
            if not db_res.data:
                return "ignored_duplicate", None
            # Delete like
            self.db.table("likes").delete().eq("user_id", user_id).eq("track_id", track_id).execute()
            return "applied", None

    def _handle_playback_snapshot_sync(self, user_id: str, payload: dict) -> Tuple[str, dict]:
        """Process LWW playback snapshot synchronization."""
        now = datetime.now(timezone.utc).isoformat()
        snapshot_data = {
            "user_id": user_id,
            "current_track_id": payload.get("current_track_id"),
            "playback_context_type": payload.get("playback_context_type"),
            "playback_context_id": payload.get("playback_context_id"),
            "playback_context_index": payload.get("playback_context_index"),
            "playlist_occurrence_id": payload.get("playlist_occurrence_id"),
            "position_ms": payload.get("position_ms", 0),
            "shuffle_enabled": payload.get("shuffle_enabled", False),
            "repeat_mode": payload.get("repeat_mode", "none"),
            "updated_at": now,
        }
        self.db.table("playback_snapshots").upsert(snapshot_data).execute()
        return "applied", snapshot_data

    def _handle_history_item_sync(self, user_id: str, item_id: str, payload: dict) -> Tuple[str, dict]:
        """Process additive history item sync."""
        insert_data = {
            "id": item_id,
            "user_id": user_id,
            "track_id": payload["track_id"],
            "listening_session_id": payload.get("listening_session_id"),
            "played_at": payload.get("played_at") or datetime.now(timezone.utc).isoformat(),
            "completion_percent": payload.get("completion_percent"),
            "was_skipped": payload.get("was_skipped", False),
            "source_context_type": payload.get("source_context_type"),
            "source_context_id": payload.get("source_context_id"),
        }
        existing = self.db.table("history_items").select("*").eq("id", item_id).eq("user_id", user_id).execute()
        if existing.data:
            return "ignored_duplicate", existing.data[0]
        if insert_data["listening_session_id"]:
            self._owned_parent("listening_sessions", insert_data["listening_session_id"], user_id)
        # A hidden/global ID collision must fail INSERT, never overwrite another owner.
        self.db.table("history_items").insert(insert_data).execute()
        return "applied", insert_data

    def _handle_listening_session_sync(self, user_id: str, session_id: str, payload: dict) -> Tuple[str, dict]:
        """Process session upsert/patch synchronization."""
        db_res = self.db.table("listening_sessions").select("*").eq("id", session_id).eq("user_id", user_id).execute()
        
        insert_data = {
            "id": session_id,
            "user_id": user_id,
            "started_at": payload.get("started_at") or datetime.now(timezone.utc).isoformat(),
            "ended_at": payload.get("ended_at"),
            "source_type": payload.get("source_type"),
            "source_id": payload.get("source_id"),
            "device_type": payload.get("device_type"),
            "network_type": payload.get("network_type"),
            "total_listening_ms": payload.get("total_listening_ms", 0),
        }

        if not db_res.data:
            self.db.table("listening_sessions").insert(insert_data).execute()
            return "applied", insert_data
        
        # Merge/Upsert
        server_data = db_res.data[0]
        # Keep original started_at
        insert_data["started_at"] = server_data["started_at"]
        self.db.table("listening_sessions").update(insert_data).eq("id", session_id).eq("user_id", user_id).execute()
        return "merged", insert_data

    def _handle_playback_event_sync(self, user_id: str, event_id: str, payload: dict) -> Tuple[str, dict]:
        """Process additive playback event sync."""
        db_res = self.db.table("playback_events").select("*").eq("id", event_id).eq("user_id", user_id).execute()
        if db_res.data:
            return "ignored_duplicate", db_res.data[0]

        session_id = payload.get("session_id")
        if not session_id:
            raise BadRequest("session_id is required to create a playback event")
        self._owned_parent("listening_sessions", session_id, user_id)

        insert_data = {
            "id": event_id,
            "session_id": session_id,
            "user_id": user_id,
            "track_id": payload["track_id"],
            "event_type": payload["event_type"],
            "occurred_at": payload.get("occurred_at") or datetime.now(timezone.utc).isoformat(),
            "position_start_ms": payload.get("position_start_ms"),
            "position_end_ms": payload.get("position_end_ms"),
            "completion_percent": payload.get("completion_percent"),
            "skip_reason": payload.get("skip_reason"),
            "liked_during_playback": payload.get("liked_during_playback", False),
        }
        self.db.table("playback_events").insert(insert_data).execute()
        return "applied", insert_data
