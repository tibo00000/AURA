"""
API routes for batch synchronization.

Matches the API contracts defined in docs/server/sync-batch-api.md.
All endpoints require authentication and use standard envelope responses.
"""

import logging
from fastapi import APIRouter, Depends, HTTPException, status

from app.core.auth import AuthenticatedUser, get_current_user
from app.schemas.responses import ResponseEnvelope
from app.schemas.sync import (
    BootstrapRequest,
    BootstrapResponse,
    PullBatchRequest,
    PullBatchResponse,
    PushBatchRequest,
    PushBatchResponse,
)
from app.services.sync_service import SyncService
from app.services.exceptions import BadRequest

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/me/sync", tags=["sync"])
sync_service = SyncService()


@router.post(
    "/bootstrap",
    response_model=ResponseEnvelope[BootstrapResponse],
)
async def bootstrap(
    request: BootstrapRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Hydrate a device after connection or token loss.
    Returns a snapshot of essential user-scoped data.
    """
    try:
        res = sync_service.bootstrap(
            user_id=current_user.id,
            device_id=request.device_id,
        )
        return ResponseEnvelope(data=BootstrapResponse(
            sync_token=res["sync_token"],
            snapshot=res["snapshot"],
        ))
    except BadRequest as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )


@router.post(
    "/push-batch",
    response_model=ResponseEnvelope[PushBatchResponse],
)
async def push_batch(
    request: PushBatchRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Push a batch of local outbox mutations from the client.
    Guarantees idempotency and resolves conflicts.
    """
    try:
        # Convert Pydantic models to dict for the service layer
        operations_dict = [op.model_dump() for op in request.operations]
        res = sync_service.push_batch(
            user_id=current_user.id,
            device_id=request.device_id,
            batch_id=request.batch_id,
            operations=operations_dict,
        )
        return ResponseEnvelope(data=PushBatchResponse(
            batch_id=res["batch_id"],
            results=res["results"],
            next_pull_token=res["next_pull_token"],
        ))
    except BadRequest as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )


@router.post(
    "/pull-batch",
    response_model=ResponseEnvelope[PullBatchResponse],
)
async def pull_batch(
    request: PullBatchRequest,
    current_user: AuthenticatedUser = Depends(get_current_user),
):
    """
    Pull all server-side changes occurred since the client's last SyncToken.
    """
    try:
        # Validate that the since_token format is valid
        if not request.since_token or not request.since_token.startswith("st_"):
            # Return strict canon conflict error payload for stale or invalid token
            error_payload = {
                "code": "conflict",
                "message": "Sync token is invalid or expired. Bootstrap is required.",
                "retryable": True,
                "details": {
                    "reason": "stale_sync_token"
                }
            }
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=error_payload,
            )

        res = sync_service.pull_batch(
            user_id=current_user.id,
            since_token=request.since_token,
            limit=request.limit,
            entity_types=request.entity_types,
        )
        return ResponseEnvelope(data=PullBatchResponse(
            changes=res["changes"],
            next_pull_token=res["next_pull_token"],
            has_more=res["has_more"],
        ))
    except HTTPException:
        raise
    except BadRequest as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )
    except Exception as e:
        logger.error("Pull batch endpoint failed: %s", e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Internal sync error: {str(e)}",
        )
