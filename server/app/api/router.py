from fastapi import APIRouter

from app.api.routes.health import router as health_router
from app.api.routes.search import router as search_router
from app.api.routes.artists import router as artists_router
from app.api.routes.albums import router as albums_router
from app.api.routes.resolve import router as resolve_router
from app.api.routes.test_download import router as test_download_router
from app.api.routes.downloads import router as downloads_router
from app.api.routes.jobs import router as jobs_router
from app.api.routes.me import router as me_router

api_router = APIRouter()
api_router.include_router(health_router)
api_router.include_router(search_router)
api_router.include_router(artists_router)
api_router.include_router(albums_router)
api_router.include_router(resolve_router)
api_router.include_router(test_download_router)
api_router.include_router(downloads_router)
api_router.include_router(jobs_router)
api_router.include_router(me_router)


