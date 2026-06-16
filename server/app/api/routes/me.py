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
    LikeRequest,
    HistoryItemResponse,
    HistoryResponseData,
    PlaylistCreate,
    PlaylistItemCreate,
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


def ensure_profile(user_id: str):
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
):
    """
    Like a track for the authenticated user.
    """
    user_id = current_user.id
    ensure_profile(user_id)
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
            detail=f"Database error: {str(e)}",
        )


@router.delete(
    "/tracks/{trackId}/like",
    response_model=ResponseEnvelope[LikeResponse],
)
async def unlike_track(
    trackId: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Unlike a track for the authenticated user.
    """
    user_id = current_user.id
    ensure_profile(user_id)
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
            detail=f"Database error: {str(e)}",
        )


@router.get(
    "/history",
    response_model=ResponseEnvelope[HistoryResponseData],
)
async def get_history(
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Get listen history for the authenticated user.
    """
    user_id = current_user.id
    ensure_profile(user_id)
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
            detail=f"Database error: {str(e)}",
        )


@router.post(
    "/playlists",
    response_model=ResponseEnvelope[PlaylistResponse],
)
async def create_playlist(
    payload: PlaylistCreate,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Create a new playlist.
    """
    user_id = current_user.id
    ensure_profile(user_id)
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
            detail=f"Database error: {str(e)}",
        )


@router.delete(
    "/playlists/{id}",
    response_model=ResponseEnvelope[PlaylistResponse],
)
async def delete_playlist(
    id: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Delete a playlist.
    """
    user_id = current_user.id
    ensure_profile(user_id)
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
            detail=f"Database error: {str(e)}",
        )


@router.post(
    "/playlists/{id}/tracks",
    response_model=ResponseEnvelope[PlaylistItemResponse],
)
async def append_track_to_playlist(
    id: str,
    payload: PlaylistItemCreate,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Append a track to a playlist.
    """
    user_id = current_user.id
    ensure_profile(user_id)
    try:
        pl_res = supabase.table("playlists").select("*").eq("user_id", user_id).eq("id", id).execute()
        if not pl_res.data:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Playlist with id {id} not found"
            )
        
        now = datetime.now(timezone.utc).isoformat()
        item_data = {
            "id": payload.id,
            "playlist_id": id,
            "track_id": payload.track_id,
            "position": payload.position,
            "added_at": now,
            "added_from_context_type": payload.added_from_context_type,
            "added_from_context_id": payload.added_from_context_id,
        }
        supabase.table("playlist_items").insert(item_data).execute()
        
        # Update playlist updated_at
        supabase.table("playlists").update({"updated_at": now}).eq("id", id).execute()
        
        return ResponseEnvelope(data=PlaylistItemResponse(
            id=payload.id,
            playlist_id=id,
            track_id=payload.track_id,
            position=payload.position,
            added_at=now,
            added_from_context_type=payload.added_from_context_type,
            added_from_context_id=payload.added_from_context_id,
        ))
    except HTTPException:
        raise
    except Exception as e:
        logger.error("Failed to append track %s to playlist %s for user %s: %s", payload.track_id, id, user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Database error: {str(e)}",
        )


@router.delete(
    "/playlists/{id}/tracks/{trackId}",
    response_model=ResponseEnvelope[List[PlaylistItemResponse]],
)
async def remove_track_from_playlist(
    id: str,
    trackId: str,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Remove all occurrences of a track from a playlist.
    """
    user_id = current_user.id
    ensure_profile(user_id)
    try:
        pl_res = supabase.table("playlists").select("*").eq("user_id", user_id).eq("id", id).execute()
        if not pl_res.data:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Playlist with id {id} not found"
            )
        
        items_res = supabase.table("playlist_items").select("*").eq("playlist_id", id).eq("track_id", trackId).execute()
        items_list = items_res.data or []
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
            for item in items_list
        ]
        
        supabase.table("playlist_items").delete().eq("playlist_id", id).eq("track_id", trackId).execute()
        
        now = datetime.now(timezone.utc).isoformat()
        supabase.table("playlists").update({"updated_at": now}).eq("id", id).execute()
        
        return ResponseEnvelope(data=removed_items)
    except HTTPException:
        raise
    except Exception as e:
        logger.error("Failed to remove track %s from playlist %s for user %s: %s", trackId, id, user_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Database error: {str(e)}",
        )

