from fastapi import APIRouter
from datetime import datetime, timezone

from ...schemas.responses import ResponseEnvelope, HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=ResponseEnvelope[HealthResponse])
def healthcheck() -> ResponseEnvelope[HealthResponse]:
    """Health check endpoint."""
    health_data = HealthResponse(
        status="ok",
        service="aura-api",
        time=datetime.now(timezone.utc),
    )
    return ResponseEnvelope(data=health_data)

