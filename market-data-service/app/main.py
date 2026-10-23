from fastapi import FastAPI
from app.config import settings
from app.routers.prices_router import router as prices_router
from app.routers.currencies_router import router as currencies_router
from app.routers.inflation_router import router as inflation_router
from app.routers.analytics_router import router as analytics_router

app = FastAPI(title=settings.APP_NAME)

app.include_router(prices_router)
app.include_router(currencies_router)
app.include_router(inflation_router)
app.include_router(analytics_router, prefix="/api/market/analytics", tags=["analytics"])

@app.get("/health")
def health_check():
    return {
        "status": "UP",
        "app_name": settings.APP_NAME
    }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host="0.0.0.0", port=settings.APP_PORT, reload=True)
