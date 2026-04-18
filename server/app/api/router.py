from fastapi import APIRouter

from app.api.routes.health import router as health_router
from app.api.routes.search import router as search_router
from app.api.routes.artists import router as artists_router
from app.api.routes.albums import router as albums_router

api_router = APIRouter()
api_router.include_router(health_router)
api_router.include_router(search_router)
api_router.include_router(artists_router)
api_router.include_router(albums_router)

