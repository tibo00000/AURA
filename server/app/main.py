from contextlib import asynccontextmanager
import asyncio
import logging
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.router import api_router
from app.config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    from app.api.routes.downloads import download_service
    from app.services.playlist_acquisition import PlaylistAcquisitionWorker

    worker = None
    worker_task = None
    if settings.enable_playlist_acquisition_worker and settings.app_env != "test":
        worker = PlaylistAcquisitionWorker(download_service, settings.playlist_authorized_audio_manifest,
            automatic_sources=settings.playlist_audio_source_mode == "automatic")
        worker_task = asyncio.create_task(worker.run())
    if settings.enable_startup_recovery and worker is None and settings.app_env != "test":
        try:
            from app.api.routes.downloads import download_service
            download_service._recover_stale_jobs_on_startup()
        except Exception as e:
            logger.warning("Startup stale jobs recovery failed: %s", e)
    if settings.enable_cache_backfill and settings.app_env != "test":
        try:
            from app.api.routes.downloads import download_service
            download_service._backfill_global_cache()
        except Exception as e:
            logger.warning("Startup global cache backfill failed: %s", e)
    try:
        yield
    finally:
        try:
            if worker is not None:
                worker.stopping.set()
                await worker_task
        finally:
            await download_service.shutdown()


app = FastAPI(
    title=settings.app_name,
    debug=settings.app_debug,
    lifespan=lifespan,
)

# Configuration CORS pour le client Web (Kotlin/Wasm) et streaming partiel (HTTP 206)
allowed_origins = [
    origin.strip()
    for origin in settings.cors_allowed_origins.split(",")
    if origin.strip()
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=[
        "Content-Range",
        "Accept-Ranges",
        "Content-Length",
        "ETag",
        "Content-Type",
        "X-Aura-Upload-Warnings",
    ],
)

app.include_router(api_router)

