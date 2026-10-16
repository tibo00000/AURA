from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "AURA API"
    app_env: str = "local"
    app_debug: bool = True
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    database_url: str = "postgresql://postgres:postgres@localhost:5432/aura"
    supabase_url: str = ""
    supabase_anon_key: str = ""
    supabase_service_role_key: str = ""
    supabase_jwt_secret: str = ""
    deezer_api_base_url: str = "https://api.deezer.com"
    log_level: str = "INFO"
    sync_files_dir: str = "sync_files"
    updates_dir: str = "updates"
    cors_allowed_origins: str = "http://localhost:8080,http://127.0.0.1:8080,http://localhost:3000"
    enable_startup_recovery: bool = True
    enable_cache_backfill: bool = True

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    def model_post_init(self, __context) -> None:
        import os
        if os.getenv("TESTING") == "1" and "ENABLE_STARTUP_RECOVERY" not in os.environ:
            object.__setattr__(self, "enable_startup_recovery", False)


@lru_cache
def get_settings() -> Settings:
    return Settings()
