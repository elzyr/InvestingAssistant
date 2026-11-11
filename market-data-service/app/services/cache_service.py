from collections import OrderedDict
import json
import logging
import threading
import time
from typing import Any, Dict, Iterable, List
import redis
from app.config import settings

_redis_pool = redis.ConnectionPool(
    host=settings.REDIS_HOST,
    port=settings.REDIS_PORT,
    decode_responses=True,
    max_connections=32,
    health_check_interval=30,
    socket_connect_timeout=1.0,
    socket_timeout=1.0,
)
_redis_client = redis.Redis(connection_pool=_redis_pool)

MAX_CACHE_ENTRIES = 1024


class CacheService:
    def __init__(self):
        self.redis_client = _redis_client
        self._locks: "OrderedDict[str, threading.Lock]" = OrderedDict()
        self._meta_lock = threading.Lock()
        self._memory_cache: "OrderedDict[str, tuple[Any, float]]" = OrderedDict()
        self._mem_lock = threading.Lock()

    def get_lock(self, key: str) -> threading.Lock:
        with self._meta_lock:
            lock = self._locks.get(key)
            if lock is not None:
                self._locks.move_to_end(key)
                return lock
            if len(self._locks) >= MAX_CACHE_ENTRIES:
                for candidate_key in list(self._locks.keys()):
                    if not self._locks[candidate_key].locked():
                        del self._locks[candidate_key]
                        break
            lock = threading.Lock()
            self._locks[key] = lock
            return lock

    def _mem_get(self, key: str) -> Any:
        with self._mem_lock:
            return self._mem_get_unlocked(key, time.time())

    def _mem_get_unlocked(self, key: str, now: float) -> Any:
        item = self._memory_cache.get(key)
        if item is None:
            return None
        value, expires_at = item
        if now < expires_at:
            self._memory_cache.move_to_end(key)
            return value
        del self._memory_cache[key]
        return None

    def _mem_set_unlocked(self, key: str, value: object, expire: int, now: float) -> None:
        if len(self._memory_cache) >= MAX_CACHE_ENTRIES:
            expired = [k for k, (_, exp) in self._memory_cache.items() if exp <= now]
            for k in expired:
                del self._memory_cache[k]
            while len(self._memory_cache) >= MAX_CACHE_ENTRIES:
                self._memory_cache.popitem(last=False)
        self._memory_cache[key] = (value, now + expire)
        self._memory_cache.move_to_end(key)

    def _mem_delete(self, *keys: str) -> None:
        if not keys:
            return
        with self._mem_lock:
            for k in keys:
                self._memory_cache.pop(k, None)

    def get(self, key: str):
        try:
            data = self.redis_client.get(key)
            if data is not None:
                return json.loads(data)
            return None
        except Exception as e:
            logging.warning(f"Redis get error: {e}, checking in-memory fallback")
            return self._mem_get(key)

    def mget(self, keys: Iterable[str]) -> Dict[str, Any]:
        key_list: List[str] = list(keys)
        if not key_list:
            return {}
        try:
            raw_values = self.redis_client.mget(key_list)
            results: Dict[str, Any] = {}
            for key, raw in zip(key_list, raw_values):
                if raw is not None:
                    try:
                        results[key] = json.loads(raw)
                    except (TypeError, ValueError) as parse_err:
                        logging.warning(f"Redis mget JSON decode error for {key}: {parse_err}")
            return results
        except Exception as e:
            logging.warning(f"Redis mget error: {e}, checking in-memory fallback")
            now = time.time()
            with self._mem_lock:
                results = {}
                for key in key_list:
                    val = self._mem_get_unlocked(key, now)
                    if val is not None:
                        results[key] = val
                return results

    def set(self, key: str, value: object, expire: int = 300):
        try:
            self.redis_client.set(
                key,
                json.dumps(value),
                ex=expire,
            )
            self._mem_delete(key)
        except Exception as e:
            logging.warning(f"Redis set error: {e}, storing in in-memory fallback")
            now = time.time()
            with self._mem_lock:
                self._mem_set_unlocked(key, value, expire, now)

    def mset_pipeline(self, items: Dict[str, Any], expire: int = 300) -> None:
        if not items:
            return
        try:
            pipe = self.redis_client.pipeline(transaction=False)
            for key, value in items.items():
                pipe.setex(key, expire, json.dumps(value))
            pipe.execute()
            self._mem_delete(*items.keys())
        except Exception as e:
            logging.warning(f"Redis mset_pipeline error: {e}, storing in in-memory fallback")
            now = time.time()
            with self._mem_lock:
                for key, value in items.items():
                    self._mem_set_unlocked(key, value, expire, now)

    def delete(self, key: str):
        try:
            self.redis_client.delete(key)
        except Exception as e:
            logging.warning(f"Redis delete error for {key}: {e}")
        self._mem_delete(key)


cache_service = CacheService()
