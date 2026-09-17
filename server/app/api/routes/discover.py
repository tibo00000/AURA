"""
API routes for /me/discover — Discovery Engine endpoints.

Implements:
- POST /me/discover/generate  — Trigger batch generation + pre-downloads
- GET  /me/discover/feed      — Get current discovery feed
- POST /me/discover/feedback/{item_id} — Record user feedback
- GET  /me/discover/weights    — Get adaptive strategy weights (debug)

All endpoints require authentication.
"""

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.auth import AuthenticatedUser, get_current_user
from app.schemas.discover import (
    DiscoveryFeedResponse,
    DiscoveryItemResponse,
    FeedbackRequest,
    GenerateBatchResponse,
    StrategyWeightsResponse,
)
from app.schemas.responses import ResponseEnvelope
from app.services.discovery_service import DiscoveryService

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/me/discover", tags=["discovery"])

# Singleton service instance (lazy-initialized on first use)
_discovery_service: DiscoveryService | None = None


def _get_service() -> DiscoveryService:
    """Lazy-init DiscoveryService singleton with optional DownloadService."""
    global _discovery_service
    if _discovery_service is None:
        try:
            from app.services.download_service import DownloadService
            download_svc = DownloadService()
        except Exception:
            download_svc = None
            logger.warning("DownloadService unavailable, pre-downloads disabled")
        _discovery_service = DiscoveryService(download_service=download_svc)
    return _discovery_service


@router.post(
    "/generate",
    response_model=ResponseEnvelope[GenerateBatchResponse],
)
async def generate_discovery_batch(
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Trigger generation of a new discovery batch.

    Runs 4 strategies in parallel (artist_radar, genre_drift, artist_radio, wildcard),
    mixes results according to adaptive weights, and triggers pre-downloads for top items.

    If a recent batch is still fresh (< 24h), returns it without regenerating.
    If a generation is already in progress for this user, returns immediately.
    """
    service = _get_service()
    try:
        result = await service.generate_batch(user_id=current_user.id)

        if result.get("skipped"):
            return ResponseEnvelope(data=GenerateBatchResponse(
                batch_id=result.get("batch_id", ""),
                items_generated=0,
                predownloads_triggered=0,
                is_cold_start=result.get("is_cold_start", False),
            ))

        return ResponseEnvelope(data=GenerateBatchResponse(
            batch_id=result["batch_id"],
            items_generated=result["items_generated"],
            predownloads_triggered=result["predownloads_triggered"],
            is_cold_start=result.get("is_cold_start", False),
        ))
    except Exception as e:
        logger.exception("Failed to generate discovery batch for user %s", current_user.id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Discovery generation failed: {e}",
        )


@router.get(
    "/feed",
    response_model=ResponseEnvelope[DiscoveryFeedResponse],
)
async def get_discovery_feed(
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Get the current discovery feed for the authenticated user.

    Returns non-expired discovery items ordered by score.
    Each item includes its source_strategy and reason_text for attribution.

    If is_stale is true, the client should call POST /generate to refresh.
    """
    service = _get_service()
    try:
        result = await service.get_discovery_feed(user_id=current_user.id)

        items = [
            DiscoveryItemResponse(
                id=item["id"],
                source_strategy=item["source_strategy"],
                reason_text=item.get("reason_text"),
                source_artist_name=item.get("source_artist_name"),
                deezer_track_id=item["deezer_track_id"],
                track_title=item["track_title"],
                artist_name=item["artist_name"],
                album_title=item.get("album_title"),
                cover_url=item.get("cover_url"),
                duration_ms=item.get("duration_ms"),
                release_date=item.get("release_date"),
                record_type=item.get("record_type"),
                preview_url=item.get("preview_url"),
                download_status=item.get("download_status", "pending"),
                aura_track_id=item.get("aura_track_id"),
                score=item.get("score", 0.0),
                created_at=item.get("created_at"),
            )
            for item in result["items"]
        ]

        return ResponseEnvelope(data=DiscoveryFeedResponse(
            items=items,
            batch_id=result.get("batch_id"),
            is_stale=result.get("is_stale", False),
            total_count=result.get("total_count", len(items)),
        ))
    except Exception as e:
        logger.exception("Failed to get discovery feed for user %s", current_user.id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to load discovery feed: {e}",
        )


@router.post(
    "/feedback/{item_id}",
    response_model=ResponseEnvelope[dict],
)
async def submit_feedback(
    item_id: str,
    body: FeedbackRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Record a user action on a discovery item.

    Actions:
    - seen: Item appeared in the user's feed
    - played: User started playing the track
    - completed: User listened to > 80% of the track
    - skipped: User skipped the track
    - liked: User liked the track
    - playlist_add: User added the track to a playlist
    - downloaded: User explicitly downloaded the track

    After recording, triggers weight recomputation if enough feedback.
    """
    service = _get_service()
    try:
        await service.record_feedback(
            user_id=current_user.id,
            item_id=item_id,
            action=body.action,
        )

        # Recompute weights periodically (not on every feedback — debounce)
        if body.action in ("completed", "liked", "downloaded", "skipped"):
            await service.recompute_weights(user_id=current_user.id)

        return ResponseEnvelope(data={"status": "ok", "action": body.action})
    except Exception as e:
        logger.exception("Failed to record feedback for item %s", item_id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to record feedback: {e}",
        )


@router.get(
    "/weights",
    response_model=ResponseEnvelope[StrategyWeightsResponse],
)
async def get_strategy_weights(
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Get the current adaptive strategy weights for the authenticated user.

    Weights sum to 1.0 and determine the proportion of each strategy in
    future batches. Each weight has a floor of 0.05 to maintain exploration.
    """
    service = _get_service()
    try:
        weights = await service._get_strategy_weights(user_id=current_user.id)
        # Get total feedback count
        try:
            resp = service.deezer  # just to get supabase reference
            from app.db.supabase import supabase
            w_resp = supabase.table("discovery_strategy_weights") \
                .select("total_feedback_count") \
                .eq("user_id", current_user.id) \
                .execute()
            total = w_resp.data[0]["total_feedback_count"] if w_resp.data else 0
        except Exception:
            total = 0

        return ResponseEnvelope(data=StrategyWeightsResponse(
            artist_radar_weight=weights["artist_radar"],
            genre_drift_weight=weights["genre_drift"],
            artist_radio_weight=weights["artist_radio"],
            wildcard_weight=weights["wildcard"],
            total_feedback_count=total,
        ))
    except Exception as e:
        logger.exception("Failed to get strategy weights for user %s", current_user.id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to load strategy weights: {e}",
        )
