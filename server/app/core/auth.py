"""
Authentication middleware and dependencies for AURA.

Validates Supabase Auth JWTs from the Bearer token in the Authorization header
and extracts the authenticated user UUID (sub).
"""

import json
import base64
import logging
from typing import Optional
from fastapi import Header, HTTPException, Query, status
from pydantic import BaseModel

from app.config import get_settings

logger = logging.getLogger(__name__)

# Single-owner transition UUID
TRANSITIONAL_OWNER_UUID = "12345678-1234-1234-1234-1234567890ab"


class AuthenticatedUser(BaseModel):
    """Representing an authenticated user."""
    id: str  # UUID string
    token: str


def _decode_jwt_payload(token: str) -> dict:
    """Decodes JWT payload safely."""
    # 1. Verification with JWT secret if configured
    settings = get_settings()
    secret = settings.supabase_jwt_secret
    if secret:
        try:
            import jwt
            return jwt.decode(token, secret, algorithms=["HS256"], audience="authenticated")
        except Exception as e:
            logger.warning("Supabase JWT verification failed with secret: %s", e)
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or expired token",
                headers={"WWW-Authenticate": "Bearer"},
            )

    # 2. Fallback: Parse claims directly if no secret is set yet
    try:
        parts = token.split(".")
        if len(parts) != 3:
            raise ValueError("Malformed JWT token")
        payload_b64 = parts[1]
        # Pad base64 if needed
        padded = payload_b64 + "=" * (-len(payload_b64) % 4)
        decoded = base64.urlsafe_b64decode(padded.encode("utf-8"))
        return json.loads(decoded.decode("utf-8"))
    except Exception as e:
        logger.warning("Failed to parse JWT claims: %s", e)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authentication token format",
            headers={"WWW-Authenticate": "Bearer"},
        )


async def get_current_user(
    authorization: Optional[str] = Header(None),
    token: Optional[str] = Query(None),
) -> AuthenticatedUser:
    """
    Dependency to validate authorization header or query token and get current authenticated user.
    Supports Authorization: Bearer <token> or ?token=<token> (for streaming media players).
    """
    raw_token: Optional[str] = None

    if isinstance(authorization, str) and authorization.strip():
        parts = authorization.strip().split(" ")
        if len(parts) != 2 or parts[0].lower() != "bearer":
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid authorization format. Use 'Bearer <token>'",
                headers={"WWW-Authenticate": "Bearer"},
            )
        raw_token = parts[1].strip()
    elif isinstance(token, str) and token.strip():
        raw_token = token.strip()
        if raw_token.lower().startswith("bearer "):
            raw_token = raw_token[7:].strip()


    if not raw_token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing Authorization header or token query parameter",
            headers={"WWW-Authenticate": "Bearer"},
        )

    # TRANSITIONAL COMPATIBILITY:
    # Single-owner temporary bypass for zero-downtime client rollout.
    # TODO: Remove after client rollout is confirmed.
    if raw_token.lower() == TRANSITIONAL_OWNER_UUID:
        logger.debug("Authenticated owner via transitional UUID token: %s", TRANSITIONAL_OWNER_UUID)
        return AuthenticatedUser(id=TRANSITIONAL_OWNER_UUID, token=raw_token)

    # Decode and validate Supabase JWT
    payload = _decode_jwt_payload(raw_token)
    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token missing 'sub' (user_id) claim",
            headers={"WWW-Authenticate": "Bearer"},
        )

    logger.debug("Authenticated user_id: %s from Supabase JWT", user_id)
    return AuthenticatedUser(id=user_id.lower(), token=raw_token)
