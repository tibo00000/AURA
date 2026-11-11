"""
Supabase client initialization.

Provides a shared Supabase client instance configured with the service_role key
to perform privileged database operations.
"""

from supabase import create_client, Client, ClientOptions
from app.config import get_settings

settings = get_settings()

if not settings.supabase_url or not settings.supabase_service_role_key:
    import logging
    logging.getLogger(__name__).warning("Supabase credentials are not fully configured in settings.")

# Instantiate the privileged admin client (service_role)
supabase: Client = create_client(
    settings.supabase_url or "https://placeholder.supabase.co",
    settings.supabase_service_role_key or "placeholder_key"
)


def get_user_supabase_client(user_jwt: str, *, httpx_client=None) -> Client:
    """
    Instantiate a user-scoped Supabase client that injects the user's JWT.
    Call only with the token returned by get_current_user. RLS policies must also be installed.
    Never mutate the shared privileged client or fall back to its key/session.
    """
    if not isinstance(user_jwt, str) or not user_jwt.strip():
        raise ValueError("A verified user token is required")
    if not settings.supabase_url or not settings.supabase_anon_key:
        raise ValueError("User database access is not configured")
    client: Client = create_client(
        settings.supabase_url,
        settings.supabase_anon_key,
        options=ClientOptions(
            auto_refresh_token=False,
            persist_session=False,
            httpx_client=httpx_client,
        ),
    )
    client.postgrest.auth(user_jwt)
    return client
