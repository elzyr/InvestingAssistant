from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    APP_NAME: str = "Market Data Service"
    APP_PORT: int = 8083
    REDIS_HOST: str = "localhost"
    REDIS_PORT: int = 6379
    MARKET_DB_PATH: str = "data/market_history.db"

    DB_HOST: Optional[str] = None
    DB_PORT: int = 5432
    DB_NAME: Optional[str] = None
    DB_USER: Optional[str] = None
    DB_PASSWORD: Optional[str] = None
    DATABASE_URL: Optional[str] = None

settings = Settings()
