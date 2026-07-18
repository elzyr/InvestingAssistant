from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env")

    APP_NAME: str = "Market Data Service"
    APP_PORT: int = 8083
    REDIS_HOST: str = "localhost"
    REDIS_PORT: int = 6379

settings = Settings()
