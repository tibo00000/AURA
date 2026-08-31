"""
Supabase client initialization.

Provides a shared Supabase client instance configured with the service_role key
to perform privileged database operations.
"""

from supabase import create_client, Client
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


def get_user_supabase_client(user_jwt: str) -> Client:
    """
    Instantiate a user-scoped Supabase client that injects the user's JWT.
    This guarantees that PostgreSQL evaluates auth.uid() and applies Row Level Security (RLS).
    """
    client: Client = create_client(
        settings.supabase_url or "https://placeholder.supabase.co",
        settings.supabase_anon_key or settings.supabase_service_role_key or "placeholder_key"
    )
    if user_jwt:
        client.postgrest.auth(user_jwt)
    return client
