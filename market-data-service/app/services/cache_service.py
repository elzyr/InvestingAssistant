import redis
import json
from app.config import settings

class CacheService:
    def __init__(self):
        self.redis_client = redis.Redis(
            host=settings.REDIS_HOST,
            port=settings.REDIS_PORT,
            decode_responses=True
        )

    def get(self, key: str):
        try:
            data = self.redis_client.get(key)
            if data:
                return json.loads(data)
            return None
        except Exception:
            return None

    def set(self, key: str, value: object, expire: int = 300):
        try:
            self.redis_client.setex(
                key,
                expire,
                json.dumps(value)
            )
        except Exception:
            pass
