"""Request-local database context for a cryptographically authenticated user."""

import httpx
from fastapi import Depends, HTTPException

from app.core.auth import AuthenticatedUser, get_current_user
from app.db.supabase import get_user_supabase_client


def get_current_user_client(user: AuthenticatedUser = Depends(get_current_user)):
    # All SDK HTTP resources share this request-owned transport and close after the request.
    with httpx.Client(timeout=120) as transport:
        try:
            client = get_user_supabase_client(user.token, httpx_client=transport)
        except ValueError:
            raise HTTPException(503, "User database access is unavailable") from None
        yield client
