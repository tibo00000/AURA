"""
API routes for /me user-scoped resources.

Implements SRV-003 REST endpoints.
All endpoints require authentication and interact directly with Supabase.
"""

import logging
from datetime import datetime, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.auth import AuthenticatedUser, get_current_user
from app.db.supabase import supabase
from app.schemas.me import (
    LikeResponse,
    PlaybackSnapshotResponse,
    PlaylistResponse,
    PlaylistItemResponse,
    UserSettingsPatch,
    UserSettingsResponse,
)
from app.schemas.responses import ResponseEnvelope

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/me", tags=["user"])


@router.get(
    "/settings",
    response_model=ResponseEnvelope[UserSettingsResponse],
)
async def get_user_settings(
    current_user: AuthenticatedUser = Depends(get_current_user),
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
            detail=f"Database error: {str(e)}",
        )


@router.patch(
    "/settings",
    response_model=ResponseEnvelope[UserSettingsResponse],
)
async def patch_user_settings(
    payload: UserSettingsPatch,
    current_user: AuthenticatedUser = Depends(get_current_user),
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
            detail=f"Database error: {str(e)}",
        )


@router.get(
    "/playback-snapshot",
    response_model=ResponseEnvelope[Optional[PlaybackSnapshotResponse]],
)
async def get_playback_snapshot(
    current_user: AuthenticatedUser = Depends(get_current_user),
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
            position_ms=data["position_ms"],
            shuffle_enabled=data["shuffle_enabled"],
            repeat_mode=data["repeat_mode"],
            updated_at=data.get("updated_at"),
        ))
    except Exception as e:
        logger.error("Failed to get playback snapshot for user %s: %s", user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Database error: {str(e)}",
        )


@router.put(
    "/playback-snapshot",
    response_model=ResponseEnvelope[PlaybackSnapshotResponse],
)
async def update_playback_snapshot(
    payload: PlaybackSnapshotResponse,
    current_user: AuthenticatedUser = Depends(get_current_user),
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
            position_ms=payload.position_ms,
            shuffle_enabled=payload.shuffle_enabled,
            repeat_mode=payload.repeat_mode,
            updated_at=now,
        ))
    except Exception as e:
        logger.error("Failed to update playback snapshot for user %s: %s", user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Database error: {str(e)}",
        )


@router.get(
    "/playlists",
    response_model=ResponseEnvelope[List[PlaylistResponse]],
)
async def get_user_playlists(
    current_user: AuthenticatedUser = Depends(get_current_user),
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
            detail=f"Database error: {str(e)}",
        )


@router.get(
    "/likes",
    response_model=ResponseEnvelope[List[LikeResponse]],
)
async def get_user_likes(
    current_user: AuthenticatedUser = Depends(get_current_user),
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
            detail=f"Database error: {str(e)}",
        )
