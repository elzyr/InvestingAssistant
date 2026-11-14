from contextlib import asynccontextmanager
from fastapi import FastAPI
from app.config import settings
from app.routers.prices_router import router as prices_router
from app.routers.currencies_router import router as currencies_router
from app.routers.analytics_router import router as market_router
from app.services.market_history_repo import market_history_repo


@asynccontextmanager
async def lifespan(_app: FastAPI):
    market_history_repo.init_db()
    try:
        yield
    finally:
        market_history_repo.close()


app = FastAPI(title=settings.APP_NAME, lifespan=lifespan)

app.include_router(prices_router)
app.include_router(currencies_router)
app.include_router(market_router)

@app.get("/health")
def health_check():
    return {
        "status": "UP",
        "app_name": settings.APP_NAME
    }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host="0.0.0.0", port=settings.APP_PORT, reload=True)
