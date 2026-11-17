"""
API routes for /me user-scoped resources.

Implements SRV-003 REST endpoints.
All endpoints require authentication and interact directly with Supabase.
"""

import logging
from datetime import datetime, timezone
from typing import List, Optional

import anyio
from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status

from app.core.auth import AuthenticatedUser, get_current_user
from app.db.user_client import get_current_user_client
from app.schemas.me import (
    LikeResponse,
    PlaybackSnapshotResponse,
    PlaylistResponse,
    PlaylistSnapshotResponse,
    PlaylistSourceRequest,
    PlaylistSourceResponse,
    PlaylistRefreshRequest,
    PlaylistRefreshResult,
    PlaylistItemResponse,
    UserSettingsPatch,
    UserSettingsResponse,
    LikeRequest,
    HistoryItemResponse,
    HistoryResponseData,
    PlaylistCreate,
    PlaylistItemCreate,
    PlaylistUpdate,
)
from app.schemas.responses import ResponseEnvelope
from app.services.sync_service import compute_order_token
from app.core.aura_id_codec import build_aura_id
from app.api.routes.sync_files import find_playable_sync_file

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/me", tags=["user"])


@router.get("/playlists/snapshot", response_model=ResponseEnvelope[PlaylistSnapshotResponse])
async def get_playlist_snapshot(
    response: Response,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    try:
        raw = await anyio.to_thread.run_sync(
            lambda: supabase.rpc("aura_playlist_snapshot", {}).execute().data
        )
        if raw["user_id"] != current_user.id:
            raise ValueError("Unexpected snapshot owner")
        playlists = [
            {**playlist, "order_token": compute_order_token(playlist["updated_at"])}
            for playlist in raw["playlists"]
        ]
        snapshot = PlaylistSnapshotResponse.model_validate({**raw, "playlists": playlists})
        if snapshot.playlist_count != len(playlists) or snapshot.item_count != sum(
            len(playlist.items) for playlist in snapshot.playlists
        ):
            raise ValueError("Incomplete playlist snapshot")
        playlist_ids = {playlist.id for playlist in snapshot.playlists}
        item_ids: set[str] = set()
        if len(playlist_ids) != len(playlists):
            raise ValueError("Repeated playlist ID")
        for playlist in snapshot.playlists:
            if playlist.user_id != current_user.id:
                raise ValueError("Unexpected playlist owner")
            if playlist.item_count != len(playlist.items):
                raise ValueError("Incomplete playlist entries")
            positions = [item.position for item in playlist.items]
            if positions != sorted(set(positions)) or any(position < 0 for position in positions):
                raise ValueError("Repeated playlist position")
            for item in playlist.items:
                if item.playlist_id != playlist.id or item.id in item_ids:
                    raise ValueError("Invalid playlist item identity")
                item_ids.add(item.id)
        response.headers["Cache-Control"] = "private, no-store"
        return ResponseEnvelope(data=snapshot)
    except Exception as exc:
        logger.warning("Playlist snapshot unavailable (%s)", type(exc).__name__)
        raise HTTPException(status_code=503, detail="Playlist snapshot unavailable") from None


@router.put("/playlists/{playlist_id}/source", response_model=ResponseEnvelope[PlaylistSourceResponse])
async def set_playlist_source(
    playlist_id: str,
    payload: PlaylistSourceRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    try:
        result = await anyio.to_thread.run_sync(lambda: supabase.rpc("aura_set_playlist_source", {
            "p_playlist_id": playlist_id,
            "p_provider": payload.provider,
            "p_external_id": payload.external_id,
            "p_expected_revision": payload.expected_revision,
        }).execute().data)
        if result["status"] == "applied":
            return ResponseEnvelope(data=PlaylistSourceResponse.model_validate(result))
        if result["status"] == "not_found":
            raise HTTPException(status_code=404, detail="Playlist unavailable")
        if result["status"] == "conflict":
            raise HTTPException(status_code=409, detail="Playlist source changed on another device")
        raise ValueError("Unexpected source mutation outcome")
    except HTTPException:
        raise
    except Exception as exc:
        logger.warning("Playlist association unavailable (%s)", type(exc).__name__)
        raise HTTPException(status_code=503, detail="Playlist association unavailable") from None


@router.post("/playlists/{playlist_id}/refresh", response_model=ResponseEnvelope[PlaylistRefreshResult])
async def commit_playlist_refresh(
    playlist_id: str,
    payload: PlaylistRefreshRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    tracks = [
        track.model_dump(exclude_none=True) | {
            "track_id": build_aura_id("track", track.provider, track.provider_id),
        }
        for track in payload.tracks
    ]
    try:
        result = await anyio.to_thread.run_sync(lambda: supabase.rpc("aura_commit_playlist_refresh", {
            "p_playlist_id": playlist_id,
            "p_request_id": str(payload.request_id),
            "p_source_revision": payload.source_revision,
            "p_tracks": tracks,
            "p_confirmed_distinct": payload.confirmed_distinct,
            "p_confirmed_links": payload.confirmed_links,
        }).execute().data)
        if result["status"] == "applied":
            return ResponseEnvelope(data=PlaylistRefreshResult.model_validate(result))
        if result["status"] == "not_found":
            raise HTTPException(status_code=404, detail="Playlist unavailable")
        if result["status"] == "conflict":
            raise HTTPException(status_code=409, detail="Playlist source changed on another device")
        raise ValueError("Unexpected refresh outcome")
    except HTTPException:
        raise
    except Exception as exc:
        if getattr(exc, "code", None) == "22023":
            raise HTTPException(status_code=422, detail="Invalid source inventory") from None
        logger.warning("Playlist refresh unavailable (%s)", type(exc).__name__)
        raise HTTPException(status_code=503, detail="Playlist refresh unavailable") from None


@router.get("/playlists/{playlist_id}/acquisitions", response_model=ResponseEnvelope[list[dict]])
@router.post("/playlists/{playlist_id}/acquisitions", response_model=ResponseEnvelope[list[dict]])
async def playlist_acquisitions(
    playlist_id: str,
    request: Request,
    retry_track_id: str | None = Query(default=None, max_length=200),
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    def get_status():
        def get_rows(*, enqueue=False, retry=None, existing=()):
            arguments = {"p_playlist_id": playlist_id, "p_enqueue": enqueue, "p_retry_track_id": retry}
            if existing:
                arguments["p_existing_track_ids"] = existing
            result = supabase.rpc("aura_get_playlist_acquisitions", arguments).execute().data
            if result is None:
                raise HTTPException(status_code=404, detail="Playlist unavailable")
            if not isinstance(result, list):
                raise ValueError("Invalid playlist acquisition snapshot")
            return result

        files = {}

        def playable(track_id):
            if track_id not in files:
                source, _ = find_playable_sync_file(current_user.id, track_id)
                files[track_id] = source is not None
            return files[track_id]

        rows = get_rows()
        if request.method == "POST":
            existing = [row["track_id"] for row in rows if row.get("audio_track_id") and playable(row["track_id"])]
            rows = get_rows(enqueue=retry_track_id is None, retry=retry_track_id, existing=existing)
        for row in rows:
            audio_track_id = row.get("audio_track_id")
            if playable(row["track_id"]):
                row.pop("audio_track_id", None)
                row["availability"] = "available"
            elif audio_track_id and playable(audio_track_id):
                row["availability"] = "available"
            else:
                row["availability"] = "missing"
                if row["status"] == "available" or row["job_status"] == "succeeded":
                    row["status"] = "failed"
                    row["error_code"] = "cloud_file_missing"
        return rows

    try:
        return ResponseEnvelope(data=await anyio.to_thread.run_sync(get_status))
    except HTTPException:
        raise
    except Exception as exc:
        logger.warning("Playlist acquisition status unavailable (%s)", type(exc).__name__)
        raise HTTPException(status_code=503, detail="Playlist acquisition status unavailable") from None


@router.get(
    "/settings",
    response_model=ResponseEnvelope[UserSettingsResponse],
)
async def get_user_settings(
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Retrieve settings for the authenticated user.
    If no settings exist, creates them dynamically with default values.
    """
    user_id = current_user.id
    try:
        response = supabase.table("user_settings").select("*").eq("user_id", user_id).execute()
        if response.data:
            data = response.data[0]
            return ResponseEnvelope(data=UserSettingsResponse(
                sync_enabled=data["sync_enabled"],
                online_search_enabled=data["online_search_enabled"],
                online_search_network_policy=data["online_search_network_policy"],
                stats_sync_network_policy=data["stats_sync_network_policy"],
                updated_at=data.get("updated_at"),
            ))

        # Default settings initialization
        now = datetime.now(timezone.utc).isoformat()
        default_settings = {
            "user_id": user_id,
            "sync_enabled": True,
            "online_search_enabled": True,
            "online_search_network_policy": "any_network",
            "stats_sync_network_policy": "wifi_only",
            "updated_at": now,
        }
        supabase.table("user_settings").insert(default_settings).execute()
        
        return ResponseEnvelope(data=UserSettingsResponse(
            sync_enabled=True,
            online_search_enabled=True,
            online_search_network_policy="any_network",
            stats_sync_network_policy="wifi_only",
            updated_at=now,
        ))
    except Exception as e:
        logger.error("Failed to get/init settings for user %s: %s", user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.patch(
    "/settings",
    response_model=ResponseEnvelope[UserSettingsResponse],
)
async def patch_user_settings(
    payload: UserSettingsPatch,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Update partial fields of settings for the authenticated user.
    """
    user_id = current_user.id
    try:
        # First ensure settings exist
        response = supabase.table("user_settings").select("*").eq("user_id", user_id).execute()
        if not response.data:
            # Init default settings
            now = datetime.now(timezone.utc).isoformat()
            supabase.table("user_settings").insert({
                "user_id": user_id,
                "sync_enabled": True,
                "online_search_enabled": True,
                "online_search_network_policy": "any_network",
                "stats_sync_network_policy": "wifi_only",
                "updated_at": now,
            }).execute()

        # Update fields
        updates = payload.model_dump(exclude_unset=True)
        if updates:
            updates["updated_at"] = datetime.now(timezone.utc).isoformat()
            response = supabase.table("user_settings").update(updates).eq("user_id", user_id).execute()
            
        # Refetch fresh
        response = supabase.table("user_settings").select("*").eq("user_id", user_id).execute()
        data = response.data[0]
        return ResponseEnvelope(data=UserSettingsResponse(
            sync_enabled=data["sync_enabled"],
            online_search_enabled=data["online_search_enabled"],
            online_search_network_policy=data["online_search_network_policy"],
            stats_sync_network_policy=data["stats_sync_network_policy"],
            updated_at=data.get("updated_at"),
        ))
    except Exception as e:
        logger.error("Failed to update settings for user %s: %s", user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.get(
    "/playback-snapshot",
    response_model=ResponseEnvelope[Optional[PlaybackSnapshotResponse]],
)
async def get_playback_snapshot(
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Retrieve the last playback snapshot for the user.
    """
    user_id = current_user.id
    try:
        response = supabase.table("playback_snapshots").select("*").eq("user_id", user_id).execute()
        if not response.data:
            return ResponseEnvelope(data=None)
            
        data = response.data[0]
        return ResponseEnvelope(data=PlaybackSnapshotResponse(
            current_track_id=data.get("current_track_id"),
            playback_context_type=data.get("playback_context_type"),
            playback_context_id=data.get("playback_context_id"),
            playback_context_index=data.get("playback_context_index"),
            playlist_occurrence_id=data.get("playlist_occurrence_id"),
            position_ms=data["position_ms"],
            shuffle_enabled=data["shuffle_enabled"],
            repeat_mode=data["repeat_mode"],
            updated_at=data.get("updated_at"),
        ))
    except Exception as e:
        logger.error("Failed to get playback snapshot for user %s: %s", user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.put(
    "/playback-snapshot",
    response_model=ResponseEnvelope[PlaybackSnapshotResponse],
)
async def update_playback_snapshot(
    payload: PlaybackSnapshotResponse,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Upsert the playback snapshot for the user.
    """
    user_id = current_user.id
    try:
        now = datetime.now(timezone.utc).isoformat()
        snapshot_data = {
            "user_id": user_id,
            "current_track_id": payload.current_track_id,
            "playback_context_type": payload.playback_context_type,
            "playback_context_id": payload.playback_context_id,
            "playback_context_index": payload.playback_context_index,
            "playlist_occurrence_id": payload.playlist_occurrence_id,
            "position_ms": payload.position_ms,
            "shuffle_enabled": payload.shuffle_enabled,
            "repeat_mode": payload.repeat_mode,
            "updated_at": now,
        }
        supabase.table("playback_snapshots").upsert(snapshot_data).execute()
        
        return ResponseEnvelope(data=PlaybackSnapshotResponse(
            current_track_id=payload.current_track_id,
            playback_context_type=payload.playback_context_type,
            playback_context_id=payload.playback_context_id,
            playback_context_index=payload.playback_context_index,
            playlist_occurrence_id=payload.playlist_occurrence_id,
            position_ms=payload.position_ms,
            shuffle_enabled=payload.shuffle_enabled,
            repeat_mode=payload.repeat_mode,
            updated_at=now,
        ))
    except Exception as e:
        logger.error("Failed to update playback snapshot for user %s: %s", user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.get(
    "/playlists",
    response_model=ResponseEnvelope[List[PlaylistResponse]],
)
async def get_user_playlists(
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Get all playlists for the authenticated user, including their items.
    """
    user_id = current_user.id
    try:
        playlists_response = supabase.table("playlists").select("*").eq("user_id", user_id).order("created_at").execute()
        playlists_list = playlists_response.data or []
        
        results = []
        for pl in playlists_list:
            pl_id = pl["id"]
            # Fetch items
            items_response = supabase.table("playlist_items").select("*").eq("playlist_id", pl_id).order("position").execute()
            items_list = items_response.data or []
            
            items = [
                PlaylistItemResponse(
                    id=item["id"],
                    playlist_id=item["playlist_id"],
                    track_id=item["track_id"],
                    position=item["position"],
                    added_at=item["added_at"],
                    added_from_context_type=item.get("added_from_context_type"),
                    added_from_context_id=item.get("added_from_context_id"),
                )
                for item in items_list
            ]
            
            results.append(PlaylistResponse(
                id=pl_id,
                user_id=pl["user_id"],
                name=pl["name"],
                cover_uri=pl.get("cover_uri"),
                is_pinned=pl["is_pinned"],
                created_at=pl["created_at"],
                updated_at=pl["updated_at"],
                items=items,
            ))
            
        return ResponseEnvelope(data=results)
    except Exception as e:
        logger.error("Failed to get playlists for user %s: %s", user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.get(
    "/likes",
    response_model=ResponseEnvelope[List[LikeResponse]],
)
async def get_user_likes(
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Get all liked tracks (favorites) for the authenticated user.
    """
    user_id = current_user.id
    try:
        response = supabase.table("likes").select("*").eq("user_id", user_id).order("liked_at", desc=True).execute()
        likes_list = response.data or []
        
        results = [
            LikeResponse(
                track_id=lk["track_id"],
                liked_at=lk["liked_at"],
                source_context_type=lk.get("source_context_type"),
                source_context_id=lk.get("source_context_id"),
            )
            for lk in likes_list
        ]
        return ResponseEnvelope(data=results)
    except Exception as e:
        logger.error("Failed to get likes for user %s: %s", user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


def ensure_profile(user_id: str, supabase):
    """Ensure user profile exists in profiles table to satisfy Foreign Key constraints."""
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
        logger.warning("Failed to verify/insert user profile %s: %s", user_id, pe)


@router.put(
    "/tracks/{trackId}/like",
    response_model=ResponseEnvelope[LikeResponse],
)
async def like_track(
    trackId: str,
    payload: Optional[LikeRequest] = None,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Like a track for the authenticated user.
    """
    user_id = current_user.id
    ensure_profile(user_id, supabase)
    try:
        now = datetime.now(timezone.utc).isoformat()
        source_context_type = payload.source_context_type if payload else None
        source_context_id = payload.source_context_id if payload else None
        
        like_data = {
            "user_id": user_id,
            "track_id": trackId,
            "liked_at": now,
            "source_context_type": source_context_type,
            "source_context_id": source_context_id,
        }
        supabase.table("likes").upsert(like_data).execute()
        
        return ResponseEnvelope(data=LikeResponse(
            track_id=trackId,
            liked_at=now,
            source_context_type=source_context_type,
            source_context_id=source_context_id,
        ))
    except Exception as e:
        logger.error("Failed to like track %s for user %s: %s", trackId, user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.delete(
    "/tracks/{trackId}/like",
    response_model=ResponseEnvelope[LikeResponse],
)
async def unlike_track(
    trackId: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Unlike a track for the authenticated user.
    """
    user_id = current_user.id
    ensure_profile(user_id, supabase)
    try:
        now = datetime.now(timezone.utc).isoformat()
        res = supabase.table("likes").select("*").eq("user_id", user_id).eq("track_id", trackId).execute()
        
        source_context_type = None
        source_context_id = None
        if res.data:
            liked_at = res.data[0].get("liked_at", now)
            source_context_type = res.data[0].get("source_context_type")
            source_context_id = res.data[0].get("source_context_id")
        else:
            liked_at = now

        supabase.table("likes").delete().eq("user_id", user_id).eq("track_id", trackId).execute()
        
        return ResponseEnvelope(data=LikeResponse(
            track_id=trackId,
            liked_at=liked_at,
            source_context_type=source_context_type,
            source_context_id=source_context_id,
        ))
    except Exception as e:
        logger.error("Failed to unlike track %s for user %s: %s", trackId, user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.get(
    "/history",
    response_model=ResponseEnvelope[HistoryResponseData],
)
async def get_history(
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Get listen history for the authenticated user.
    """
    user_id = current_user.id
    ensure_profile(user_id, supabase)
    try:
        response = supabase.table("history_items").select("*").eq("user_id", user_id).order("played_at", desc=True).execute()
        items_list = response.data or []
        
        results = [
            HistoryItemResponse(
                id=item["id"],
                track_id=item["track_id"],
                played_at=item["played_at"],
                completion_percent=item.get("completion_percent"),
                was_skipped=item["was_skipped"],
                source_context_type=item.get("source_context_type"),
                source_context_id=item.get("source_context_id"),
            )
            for item in items_list
        ]
        return ResponseEnvelope(data=HistoryResponseData(items=results))
    except Exception as e:
        logger.error("Failed to get history for user %s: %s", user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.post(
    "/playlists",
    response_model=ResponseEnvelope[PlaylistResponse],
)
async def create_playlist(
    payload: PlaylistCreate,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Create a new playlist.
    """
    user_id = current_user.id
    ensure_profile(user_id, supabase)
    try:
        now = datetime.now(timezone.utc).isoformat()
        playlist_data = {
            "id": payload.id,
            "user_id": user_id,
            "name": payload.name,
            "cover_uri": payload.cover_uri,
            "is_pinned": payload.is_pinned,
            "created_at": now,
            "updated_at": now,
        }
        supabase.table("playlists").insert(playlist_data).execute()
        
        return ResponseEnvelope(data=PlaylistResponse(
            id=payload.id,
            user_id=user_id,
            name=payload.name,
            cover_uri=payload.cover_uri,
            is_pinned=payload.is_pinned,
            created_at=now,
            updated_at=now,
            items=[]
        ))
    except Exception as e:
        logger.error("Failed to create playlist %s for user %s: %s", payload.name, user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.patch(
    "/playlists/{id}",
    response_model=ResponseEnvelope[PlaylistResponse],
)
async def patch_playlist(
    id: str,
    payload: PlaylistUpdate,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Update a playlist (name, cover, is_pinned).
    """
    user_id = current_user.id
    ensure_profile(user_id, supabase)
    try:
        pl_res = supabase.table("playlists").select("*").eq("user_id", user_id).eq("id", id).execute()
        if not pl_res.data:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Playlist with id {id} not found"
            )
        update_data = {}
        if payload.name is not None:
            update_data["name"] = payload.name
        if payload.cover_uri is not None:
            update_data["cover_uri"] = payload.cover_uri
        if payload.is_pinned is not None:
            update_data["is_pinned"] = payload.is_pinned

        result = supabase.rpc("aura_mutate_playlist_items", {
            "p_playlist_id": id, "p_item_id": None,
            "p_action": "rest_patch", "p_payload": update_data,
        }).execute().data
        updated_pl = result["entity"]
        items = [
            PlaylistItemResponse(
                id=item["id"],
                playlist_id=item["playlist_id"],
                track_id=item["track_id"],
                position=item["position"],
                added_at=item["added_at"],
                added_from_context_type=item.get("added_from_context_type"),
                added_from_context_id=item.get("added_from_context_id"),
            )
            for item in result["items"]
        ]
        return ResponseEnvelope(data=PlaylistResponse(
            id=id,
            user_id=user_id,
            name=updated_pl["name"],
            cover_uri=updated_pl.get("cover_uri"),
            is_pinned=updated_pl.get("is_pinned", False),
            created_at=updated_pl["created_at"],
            updated_at=updated_pl["updated_at"],
            items=items
        ))
    except HTTPException:
        raise
    except Exception as e:
        logger.error("Failed to update playlist %s for user %s: %s", id, user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.delete(
    "/playlists/{id}",
    response_model=ResponseEnvelope[PlaylistResponse],
)
async def delete_playlist(
    id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Delete a playlist.
    """
    user_id = current_user.id
    ensure_profile(user_id, supabase)
    try:
        pl_res = supabase.table("playlists").select("*").eq("user_id", user_id).eq("id", id).execute()
        if not pl_res.data:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Playlist with id {id} not found"
            )
        
        pl = pl_res.data[0]
        items_res = supabase.table("playlist_items").select("*").eq("playlist_id", id).order("position").execute()
        items_list = items_res.data or []
        items = [
            PlaylistItemResponse(
                id=item["id"],
                playlist_id=item["playlist_id"],
                track_id=item["track_id"],
                position=item["position"],
                added_at=item["added_at"],
                added_from_context_type=item.get("added_from_context_type"),
                added_from_context_id=item.get("added_from_context_id"),
            )
            for item in items_list
        ]
        
        supabase.table("playlists").delete().eq("user_id", user_id).eq("id", id).execute()
        
        return ResponseEnvelope(data=PlaylistResponse(
            id=id,
            user_id=user_id,
            name=pl["name"],
            cover_uri=pl.get("cover_uri"),
            is_pinned=pl["is_pinned"],
            created_at=pl["created_at"],
            updated_at=pl["updated_at"],
            items=items
        ))
    except HTTPException:
        raise
    except Exception as e:
        logger.error("Failed to delete playlist %s for user %s: %s", id, user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.post(
    "/playlists/{id}/tracks",
    response_model=ResponseEnvelope[PlaylistItemResponse],
)
async def append_track_to_playlist(
    id: str,
    payload: PlaylistItemCreate,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Append a track to a playlist.
    """
    user_id = current_user.id
    ensure_profile(user_id, supabase)
    try:
        pl_res = supabase.table("playlists").select("*").eq("user_id", user_id).eq("id", id).execute()
        if not pl_res.data:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Playlist with id {id} not found"
            )
        
        item_data = {
            "track_id": payload.track_id,
            "position": payload.position,
            "added_from_context_type": payload.added_from_context_type,
            "added_from_context_id": payload.added_from_context_id,
        }
        result = supabase.rpc("aura_mutate_playlist_items", {
            "p_playlist_id": id, "p_item_id": payload.id,
            "p_action": "rest_insert", "p_payload": item_data,
        }).execute().data
        return ResponseEnvelope(data=PlaylistItemResponse(**result["entity"]))
    except HTTPException:
        raise
    except Exception as e:
        logger.error("Failed to append track %s to playlist %s for user %s: %s", payload.track_id, id, user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )


@router.delete(
    "/playlists/{id}/tracks/{trackId}",
    response_model=ResponseEnvelope[List[PlaylistItemResponse]],
)
async def remove_track_from_playlist(
    id: str,
    trackId: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
    supabase=Depends(get_current_user_client),
):
    """
    Remove all occurrences of a track from a playlist.
    """
    user_id = current_user.id
    ensure_profile(user_id, supabase)
    try:
        pl_res = supabase.table("playlists").select("*").eq("user_id", user_id).eq("id", id).execute()
        if not pl_res.data:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Playlist with id {id} not found"
            )
        
        result = supabase.rpc("aura_mutate_playlist_items", {
            "p_playlist_id": id, "p_item_id": trackId,
            "p_action": "rest_delete_track", "p_payload": {},
        }).execute().data
        removed_items = [
            PlaylistItemResponse(
                id=item["id"],
                playlist_id=item["playlist_id"],
                track_id=item["track_id"],
                position=item["position"],
                added_at=item["added_at"],
                added_from_context_type=item.get("added_from_context_type"),
                added_from_context_id=item.get("added_from_context_id"),
            )
            for item in result["entities"]
        ]
        
        return ResponseEnvelope(data=removed_items)
    except HTTPException:
        raise
    except Exception as e:
        logger.error("Failed to remove track %s from playlist %s for user %s: %s", trackId, id, user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unable to complete user database request",
        )

