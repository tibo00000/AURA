import os
from functools import lru_cache

from pydantic import Field
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
    enable_startup_recovery: bool = Field(
        default_factory=lambda: os.getenv("TESTING") != "1"
    )
    enable_cache_backfill: bool = True
    enable_playlist_acquisition_worker: bool = False
    playlist_authorized_audio_manifest: str = ""
    upload_max_file_bytes: int = Field(default=50 * 1024**2, gt=0)
    upload_min_free_bytes: int = Field(default=10 * 1024**3, ge=0)
    upload_min_free_ratio: float = Field(default=0.10, ge=0, le=1)

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
