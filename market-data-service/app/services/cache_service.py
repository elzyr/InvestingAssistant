import redis
import json
import logging
import threading
import time
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
        self._locks = {}
        self._meta_lock = threading.Lock()
        self._memory_cache = {}
        self._mem_lock = threading.Lock()

    def get_lock(self, key: str) -> threading.Lock:
        with self._meta_lock:
            if key not in self._locks:
                self._locks[key] = threading.Lock()
            return self._locks[key]

    def get(self, key: str):
        try:
            data = self.redis_client.get(key)
            if data:
                return json.loads(data)
            return None
        except Exception as e:
            logging.warning(f"Redis get error: {e}, checking in-memory fallback")
            with self._mem_lock:
                item = self._memory_cache.get(key)
                if item is not None:
                    value, expires_at = item
                    if time.time() < expires_at:
                        return value
                    del self._memory_cache[key]
            return None

    def set(self, key: str, value: object, expire: int = 300):
        try:
            self.redis_client.setex(
                key,
                expire,
                json.dumps(value)
            )
        except Exception as e:
            logging.warning(f"Redis set error: {e}, storing in in-memory fallback")
            with self._mem_lock:
                expires_at = time.time() + expire
                self._memory_cache[key] = (value, expires_at)

    def delete(self, key: str):
        try:
            self.redis_client.delete(key)
        except Exception:
            pass
        with self._mem_lock:
            self._memory_cache.pop(key, None)

cache_service = CacheService()
