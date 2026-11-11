"""Verify Supabase access tokens before exposing an authenticated identity."""

import math
from functools import lru_cache
from typing import Optional
from urllib.parse import urlsplit
from uuid import UUID

import anyio
import jwt
from fastapi import Header, HTTPException, Query, status
from pydantic import BaseModel

from app.config import get_settings


class AuthenticatedUser(BaseModel):
    id: str
    token: str


def _unauthorized() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid or expired authentication token",
        headers={"WWW-Authenticate": "Bearer"},
    )


@lru_cache(maxsize=8)
def _jwks_client(issuer: str) -> jwt.PyJWKClient:
    # Cache the set, not individual keys indefinitely, so revoked keys expire.
    # The library serializes refreshes and bounds unknown-kid refreshes to 30s.
    return jwt.PyJWKClient(
        issuer + "/.well-known/jwks.json",
        cache_keys=False, lifespan=600, timeout=5, cooldown_duration=30,
    )


def _decode_jwt_payload(token: str) -> dict:
    """Return claims only after signature, access-token claims and UUID validation."""
    settings = get_settings()
    try:
        base_url = settings.supabase_url.rstrip("/")
        parsed = urlsplit(base_url)
        if (
            parsed.scheme != "https" or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment
        ):
            raise ValueError("Missing or invalid trusted Supabase URL")
        issuer = base_url + "/auth/v1"

        # The untrusted header selects only among our allowlisted verifiers.
        # No key URL, issuer, or key material from the token establishes trust.
        header = jwt.get_unverified_header(token)
        algorithm = header.get("alg")
        if algorithm == "HS256" and settings.supabase_jwt_secret:
            key = settings.supabase_jwt_secret
        elif algorithm in ("ES256", "RS256"):
            if not isinstance(header.get("kid"), str) or not header["kid"]:
                raise ValueError("Missing signing key identifier")
            signing_key = _jwks_client(issuer).get_signing_key(header["kid"])
            if signing_key.algorithm_name != algorithm:
                raise ValueError("Signing key algorithm mismatch")
            key = signing_key.key
        else:
            raise ValueError("No trusted verifier for this algorithm")

        payload = jwt.decode(
            token, key, algorithms=[algorithm], audience="authenticated", issuer=issuer,
            options={"require": ["exp", "aud", "iss", "sub", "role"]},
        )
        expiry = payload["exp"]
        if (
            isinstance(expiry, bool) or not isinstance(expiry, (int, float))
            or not math.isfinite(expiry) or payload["role"] != "authenticated"
            or not isinstance(payload["sub"], str)
        ):
            raise ValueError("Invalid user access-token claims")
        payload["sub"] = str(UUID(payload["sub"]))
        return payload
    except (jwt.PyJWTError, ValueError, OverflowError) as exc:
        # Never return unverified claims or include token/key material in errors.
        raise _unauthorized() from exc


async def get_current_user(
    authorization: Optional[str] = Header(None),
    token: Optional[str] = Query(None),
) -> AuthenticatedUser:
    """Accept a Bearer header or the existing audio-stream query transport."""
    raw_token = None
    if isinstance(authorization, str):
        parts = authorization.strip().split(" ")
        if len(parts) != 2 or parts[0].lower() != "bearer":
            raise _unauthorized()
        raw_token = parts[1].strip()
    elif isinstance(token, str):
        raw_token = token.strip()
        if raw_token.lower().startswith("bearer "):
            raw_token = raw_token[7:].strip()
    if not raw_token:
        raise _unauthorized()

    # JWKS discovery and cryptographic work must not block the async API loop.
    payload = await anyio.to_thread.run_sync(_decode_jwt_payload, raw_token)
    return AuthenticatedUser(id=payload["sub"], token=raw_token)
