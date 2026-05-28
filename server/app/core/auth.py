"""
Authentication middleware and dependencies for AURA.

Extracts user_id from the Bearer token in the Authorization header.
For now (before SRV-004 Supabase Auth integration), this middleware
validates the token format and extracts/simulates a user UUID so that
the mobile client and tests can work in a multi-user environment.
"""

import re
import logging
from typing import Optional
from fastapi import Header, HTTPException, status
from pydantic import BaseModel

logger = logging.getLogger(__name__)

# Basic UUID validation regex
UUID_REGEX = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.IGNORECASE)


class AuthenticatedUser(BaseModel):
    """Representing an authenticated user."""
    id: str  # UUID string
    token: str


async def get_current_user(authorization: Optional[str] = Header(None)) -> AuthenticatedUser:
    """
    Dependency to validate authorization header and get current user.

    Expects: 'Bearer <user_uuid>' or a JWT containing user UUID.
    For this architectural step, we extract any valid UUID from the token
    or fallback to a deterministic UUID from the token string to allow
    multi-user simulation.
    """
    if not authorization:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing Authorization header",
            headers={"WWW-Authenticate": "Bearer"},
        )

    parts = authorization.split(" ")
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authorization format. Use 'Bearer <token>'",
            headers={"WWW-Authenticate": "Bearer"},
        )

    token = parts[1]

    # Clean up token
    token = token.strip()

    # Extract user ID
    # If the token is a direct UUID, use it
    if UUID_REGEX.match(token):
        user_id = token.lower()
    else:
        # For JWT, we'd decode it. For simulation, if we can't parse it as UUID,
        # we generate a deterministic user_id for this token (e.g. from its hash)
        # to ensure different users get different jobs.
        import hashlib
        hasher = hashlib.md5(token.encode("utf-8"))
        digest = hasher.hexdigest()
        user_id = f"{digest[:8]}-{digest[8:12]}-{digest[12:16]}-{digest[16:20]}-{digest[20:32]}"

    logger.debug("Authenticated user_id: %s from token", user_id)
    return AuthenticatedUser(id=user_id, token=token)
