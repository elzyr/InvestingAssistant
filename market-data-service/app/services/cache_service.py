import json
import logging
import threading
import time
import redis
from app.config import settings

_redis_client = redis.Redis(
    host=settings.REDIS_HOST,
    port=settings.REDIS_PORT,
    decode_responses=True,
    socket_connect_timeout=1.0,
    socket_timeout=1.0
)

MAX_CACHE_ENTRIES = 1024

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
                if len(self._locks) >= MAX_CACHE_ENTRIES:
                    self._locks.pop(next(iter(self._locks)), None)
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
            self.redis_client.set(
                key,
                json.dumps(value),
                ex=expire
            )
        except Exception as e:
            logging.warning(f"Redis set error: {e}, storing in in-memory fallback")
            with self._mem_lock:
                now = time.time()
                if len(self._memory_cache) >= MAX_CACHE_ENTRIES:
                    expired = [k for k, (_, exp) in self._memory_cache.items() if exp <= now]
                    for k in expired:
                        del self._memory_cache[k]
                    if len(self._memory_cache) >= MAX_CACHE_ENTRIES:
                        self._memory_cache.pop(next(iter(self._memory_cache)), None)
                self._memory_cache[key] = (value, now + expire)

    def delete(self, key: str):
        try:
            self.redis_client.delete(key)
        except Exception:
            pass
        with self._mem_lock:
            self._memory_cache.pop(key, None)

cache_service = CacheService()
