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

# Instantiate the privileged client
supabase: Client = create_client(
    settings.supabase_url or "https://placeholder.supabase.co",
    settings.supabase_service_role_key or "placeholder_key"
)
