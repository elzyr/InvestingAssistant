import redis
import json
import logging
from app.config import settings

_redis_client = redis.Redis(
    host=settings.REDIS_HOST,
    port=settings.REDIS_PORT,
    decode_responses=True,
    socket_connect_timeout=1.0,
    socket_timeout=1.0
)

class CacheService:
    def __init__(self):
        self.redis_client = _redis_client

    def get(self, key: str):
        try:
            data = self.redis_client.get(key)
            if data:
                return json.loads(data)
            return None
        except Exception as e:
            logging.warning(f"Cache get error: {e}")
            return None

    def set(self, key: str, value: object, expire: int = 300):
        try:
            self.redis_client.setex(
                key,
                expire,
                json.dumps(value)
            )
        except Exception as e:
            logging.warning(f"Cache set error: {e}")

cache_service = CacheService()
