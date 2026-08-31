"""
Core Synchronization Service for AURA.

Implements SRV-007 sync batch logic and conflict resolution rules
as defined in docs/server/sync-conflict-resolution.md.
"""

import base64
import hashlib
import logging
from datetime import datetime, timezone
from typing import Any, Dict, List, Tuple

from app.db.supabase import supabase
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

    def bootstrap(self, user_id: str, device_id: str) -> dict:
        """
        Compile initial client hydration snapshot containing user settings,
        playlists (with items), track likes, and the last playback snapshot.
        """
        logger.info("Sync Bootstrap requested for user %s on device %s", user_id, device_id)
        
        # Ensure user profile exists in profiles table to satisfy Foreign Key constraints
        try:
            prof_res = supabase.table("profiles").select("*").eq("id", user_id).execute()
            if not prof_res.data:
                logger.info("Initializing missing user profile for %s in profiles table", user_id)
                supabase.table("profiles").insert({
                    "id": user_id,
                    "display_name": f"User {user_id[:8]}",
                    "avatar_uri": None,
                }).execute()
        except Exception as pe:
            logger.warning("Failed to verify/insert user profile %s: %s. Continuing anyway.", user_id, pe)

        try:
            # 1. User Settings
            settings_res = supabase.table("user_settings").select("*").eq("user_id", user_id).execute()
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
            playlists_res = supabase.table("playlists").select("*").eq("user_id", user_id).execute()
            playlists_list = playlists_res.data or []
            playlists = []
            
            for pl in playlists_list:
                pl_id = pl["id"]
                items_res = supabase.table("playlist_items").select("*").eq("playlist_id", pl_id).order("position").execute()
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
            likes_res = supabase.table("likes").select("*").eq("user_id", user_id).execute()
            likes_list = likes_res.data or []
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
            snapshot_res = supabase.table("playback_snapshots").select("*").eq("user_id", user_id).execute()
            playback_snapshot = None
            if snapshot_res.data:
                snap = snapshot_res.data[0]
                playback_snapshot = {
                    "current_track_id": snap.get("current_track_id"),
                    "playback_context_type": snap.get("playback_context_type"),
                    "playback_context_id": snap.get("playback_context_id"),
                    "playback_context_index": snap.get("playback_context_index"),
                    "position_ms": snap["position_ms"],
                    "shuffle_enabled": snap["shuffle_enabled"],
                    "repeat_mode": snap["repeat_mode"],
                    "updated_at": snap.get("updated_at"),
                }

            token_val = generate_sync_token()
            return {
                "sync_token": {
                    "value": token_val,
                    "issued_at": datetime.now(timezone.utc).isoformat(),
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
            raise BadRequest(f"Bootstrap execution failed: {str(e)}")

    def push_batch(self, user_id: str, device_id: str, batch_id: str, operations: List[dict]) -> dict:
        """
        Process a batch of sync operations sequentially.
        Guarantees idempotency at both batch and operation levels and resolves concurrency conflicts based on LWW or order tokens.
        """
        logger.info("Sync Push Batch %s received with %d operations for user %s", batch_id, len(operations), user_id)
        
        # 0. Batch-Level Idempotency Check
        if batch_id:
            try:
                batch_res = supabase.table("processed_batches").select("*").eq("batch_id", batch_id).eq("user_id", user_id).execute()
                if batch_res.data:
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
                logger.debug("processed_batches lookup skipped/failed: %s", be)

        # Ensure user profile exists in profiles table to satisfy Foreign Key constraints
        try:
            prof_res = supabase.table("profiles").select("*").eq("id", user_id).execute()
            if not prof_res.data:
                logger.info("Initializing missing user profile for %s in profiles table during push", user_id)
                supabase.table("profiles").insert({
                    "id": user_id,
                    "display_name": f"User {user_id[:8]}",
                    "avatar_uri": None,
                }).execute()
        except Exception as pe:
            logger.warning("Failed to verify/insert user profile %s during push: %s. Continuing anyway.", user_id, pe)

        results = []
        
        for op in operations:
            op_id = op.get("operation_id")
            entity_type = op.get("entity_type")
            entity_id = op.get("entity_id")
            op_type = op.get("operation_type")
            occurred_at_str = op.get("occurred_at")
            payload = op.get("payload") or {}

            if not op_id or not entity_type or not entity_id or not op_type:
                logger.warning("Malformed operation omitted: %r", op)
                continue

            # 1. Idempotency Check
            try:
                dup_res = supabase.table("processed_operations").select("*").eq("operation_id", op_id).execute()
                if dup_res.data:
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
            except Exception as e:
                logger.error("Idempotency check failed for operation %s: %s", op_id, e)
                continue

            # 2. Run sequential routing & resolving rules
            status = "applied"
            conflict = None
            resolved_entity = None

            try:
                if entity_type == "user_settings":
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

                # 3. Mark operation as completed in idempotency table if applied/merged successfully
                if status in ("applied", "merged", "ignored_duplicate"):
                    supabase.table("processed_operations").insert({
                        "operation_id": op_id,
                        "user_id": user_id,
                    }).execute()

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
            })

        # Record completed batch for future idempotency checks
        if batch_id:
            try:
                supabase.table("processed_batches").insert({
                    "batch_id": batch_id,
                    "user_id": user_id,
                    "processed_at": datetime.now(timezone.utc).isoformat(),
                }).execute()
            except Exception as be:
                logger.debug("Failed to record processed_batches for %s: %s", batch_id, be)

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
        since_time = parse_sync_token(since_token)
        since_iso = since_time.isoformat()

        changes = []
        try:
            # 1. user_settings
            if not entity_types or "user_settings" in entity_types:
                res = supabase.table("user_settings").select("*").eq("user_id", user_id).gt("updated_at", since_iso).execute()
                for item in (res.data or []):
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
                res = supabase.table("playlists").select("*").eq("user_id", user_id).gt("updated_at", since_iso).execute()
                for item in (res.data or []):
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
                # Find modifications using joined query or items whose playlist belongs to user
                playlists_res = supabase.table("playlists").select("id").eq("user_id", user_id).execute()
                pl_ids = [p["id"] for p in (playlists_res.data or [])]
                if pl_ids:
                    res = supabase.table("playlist_items").select("*").in_("playlist_id", pl_ids).gt("added_at", since_iso).execute()
                    for item in (res.data or []):
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
                res = supabase.table("likes").select("*").eq("user_id", user_id).gt("liked_at", since_iso).execute()
                for item in (res.data or []):
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
                res = supabase.table("playback_snapshots").select("*").eq("user_id", user_id).gt("updated_at", since_iso).execute()
                for item in (res.data or []):
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
                            "position_ms": item["position_ms"],
                            "shuffle_enabled": item["shuffle_enabled"],
                            "repeat_mode": item["repeat_mode"],
                            "updated_at": item["updated_at"],
                        }
                    })

            # Sort all changes chronologically
            changes.sort(key=lambda c: c["server_updated_at"])

            # Paginate according to limit
            has_more = len(changes) > limit
            paginated_changes = changes[:limit]

            next_token_time = paginated_changes[-1]["server_updated_at"] if paginated_changes else datetime.now(timezone.utc).isoformat()
            encoded_next = base64.b64encode(next_token_time.encode("utf-8")).decode("utf-8")
            
            return {
                "changes": paginated_changes,
                "next_pull_token": {
                    "value": f"st_{encoded_next}",
                    "issued_at": next_token_time,
                },
                "has_more": has_more,
            }
        except Exception as e:
            logger.error("Pull batch execution failed for user %s: %s", user_id, e)
            raise BadRequest(f"Pull batch failed: {str(e)}")

    # --- PRIVATE ENTITY HANDLERS ---

    def _handle_user_settings_sync(self, user_id: str, occurred_at: str, payload: dict) -> Tuple[str, dict]:
        """Process LWW user settings patch sync."""
        db_res = supabase.table("user_settings").select("*").eq("user_id", user_id).execute()
        
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
            supabase.table("user_settings").insert(insert_data).execute()
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
        supabase.table("user_settings").update(updates).eq("user_id", user_id).execute()
        return "merged", updates

    def _handle_playlist_sync(self, user_id: str, playlist_id: str, op_type: str, occurred_at: str, payload: dict) -> Tuple[str, dict, dict]:
        """Process playlist creation, LWW update or deletion."""
        db_res = supabase.table("playlists").select("*").eq("id", playlist_id).execute()
        
        if op_type == "delete":
            if db_res.data:
                supabase.table("playlists").delete().eq("id", playlist_id).execute()
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
            supabase.table("playlists").insert(insert_data).execute()
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
        supabase.table("playlists").update(updates).eq("id", playlist_id).execute()
        return "merged", updates, None

    def _handle_playlist_item_sync(self, user_id: str, item_id: str, op_type: str, payload: dict) -> Tuple[str, dict]:
        """Process playlist item insertion (with position compaction/shifting) or deletion."""
        db_res = supabase.table("playlist_items").select("*").eq("id", item_id).execute()

        if op_type == "delete":
            if db_res.data:
                supabase.table("playlist_items").delete().eq("id", item_id).execute()
                return "applied", None
            return "ignored_duplicate", None

        if db_res.data:
            return "ignored_duplicate", db_res.data[0]

        # Insert (Add) playlist item
        playlist_id = payload.get("playlist_id")
        position = payload.get("position", 0)
        track_id = payload.get("track_id")

        if not playlist_id or not track_id:
            raise BadRequest("playlist_id and track_id are required to add a playlist item")

        # Compaction rule: if position is already taken, shift positions >= requested position
        dup_pos = supabase.table("playlist_items").select("*").eq("playlist_id", playlist_id).eq("position", position).execute()
        if dup_pos.data:
            logger.info("Position %d taken in playlist %s. Shifting items in DB.", position, playlist_id)
            # Fetch all items to avoid unique constraint violations on shift (shift from end to front)
            all_items = supabase.table("playlist_items").select("id", "position").eq("playlist_id", playlist_id).ge("position", position).order("position", desc=True).execute()
            for it in (all_items.data or []):
                supabase.table("playlist_items").update({"position": it["position"] + 1}).eq("id", it["id"]).execute()

        # Insert the new item
        now = datetime.now(timezone.utc).isoformat()
        insert_data = {
            "id": item_id,
            "playlist_id": playlist_id,
            "track_id": track_id,
            "position": position,
            "added_at": payload.get("added_at") or now,
            "added_from_context_type": payload.get("added_from_context_type"),
            "added_from_context_id": payload.get("added_from_context_id"),
        }
        supabase.table("playlist_items").insert(insert_data).execute()
        
        # Touch playlist's updated_at to invalidate order token
        supabase.table("playlists").update({"updated_at": now}).eq("id", playlist_id).execute()
        
        return "applied", insert_data

    def _handle_playlist_reorder_sync(self, user_id: str, playlist_id: str, payload: dict) -> Tuple[str, dict, dict]:
        """Process playlist full reordering using order tokens to prevent concurrent merge conflicts."""
        db_res = supabase.table("playlists").select("updated_at").eq("id", playlist_id).execute()
        if not db_res.data:
            conflict = {
                "reason": "playlist_not_found",
                "server_entity": None,
                "client_payload": payload,
                "retryable": False,
            }
            return "conflict", None, conflict

        playlist_updated = db_res.data[0]["updated_at"]
        server_token = compute_order_token(playlist_updated)
        client_token = payload.get("base_order_token")

        if server_token != client_token:
            logger.warning("Order token mismatch on playlist %s. Server: %s, Client: %s", playlist_id, server_token, client_token)
            conflict = {
                "reason": "base_outdated",
                "server_entity": {
                    "playlist_id": playlist_id,
                    "order_token": server_token,
                },
                "client_payload": {
                    "base_order_token": client_token,
                },
                "retryable": True,
            }
            return "conflict", None, conflict

        # Apply reorder
        items_reorder = payload.get("items") or []
        for it in items_reorder:
            item_id = it.get("playlist_item_id")
            pos = it.get("position")
            if item_id is not None and pos is not None:
                # Update positions
                supabase.table("playlist_items").update({"position": pos}).eq("id", item_id).execute()

        # Touch playlist to rotate token
        now = datetime.now(timezone.utc).isoformat()
        supabase.table("playlists").update({"updated_at": now}).eq("id", playlist_id).execute()
        
        return "applied", {"playlist_id": playlist_id, "order_token": compute_order_token(now)}, None

    def _handle_track_like_sync(self, user_id: str, track_id: str, payload: dict) -> Tuple[str, dict]:
        """Process track like/favorite state sync."""
        is_liked = payload.get("is_liked", True)
        
        db_res = supabase.table("likes").select("*").eq("user_id", user_id).eq("track_id", track_id).execute()

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
            supabase.table("likes").insert(insert_data).execute()
            return "applied", insert_data
        else:
            if not db_res.data:
                return "ignored_duplicate", None
            # Delete like
            supabase.table("likes").delete().eq("user_id", user_id).eq("track_id", track_id).execute()
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
            "position_ms": payload.get("position_ms", 0),
            "shuffle_enabled": payload.get("shuffle_enabled", False),
            "repeat_mode": payload.get("repeat_mode", "none"),
            "updated_at": now,
        }
        supabase.table("playback_snapshots").upsert(snapshot_data).execute()
        return "applied", snapshot_data

    def _handle_history_item_sync(self, user_id: str, item_id: str, payload: dict) -> Tuple[str, dict]:
        """Process additive history item sync."""
        db_res = supabase.table("history_items").select("*").eq("id", item_id).execute()
        if db_res.data:
            return "ignored_duplicate", db_res.data[0]

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
        supabase.table("history_items").insert(insert_data).execute()
        return "applied", insert_data

    def _handle_listening_session_sync(self, user_id: str, session_id: str, payload: dict) -> Tuple[str, dict]:
        """Process session upsert/patch synchronization."""
        db_res = supabase.table("listening_sessions").select("*").eq("id", session_id).execute()
        
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
            supabase.table("listening_sessions").insert(insert_data).execute()
            return "applied", insert_data
        
        # Merge/Upsert
        server_data = db_res.data[0]
        # Keep original started_at
        insert_data["started_at"] = server_data["started_at"]
        supabase.table("listening_sessions").update(insert_data).eq("id", session_id).execute()
        return "merged", insert_data

    def _handle_playback_event_sync(self, user_id: str, event_id: str, payload: dict) -> Tuple[str, dict]:
        """Process additive playback event sync."""
        db_res = supabase.table("playback_events").select("*").eq("id", event_id).execute()
        if db_res.data:
            return "ignored_duplicate", db_res.data[0]

        session_id = payload.get("session_id")
        if not session_id:
            raise BadRequest("session_id is required to create a playback event")

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
        supabase.table("playback_events").insert(insert_data).execute()
        return "applied", insert_data
