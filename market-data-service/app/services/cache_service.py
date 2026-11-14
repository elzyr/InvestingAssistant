from collections import OrderedDict
import json
import logging
import threading
import time
from typing import Any, Callable, Dict, Iterable, List, Optional, TypeVar
from weakref import WeakValueDictionary
import redis
from app.config import settings

T = TypeVar("T")

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


class _KeyLock:
    def __init__(self) -> None:
        self._lock = threading.Lock()

    def acquire(self, *args: Any, **kwargs: Any) -> bool:
        return self._lock.acquire(*args, **kwargs)

    def release(self) -> None:
        self._lock.release()

    def __enter__(self) -> "_KeyLock":
        self._lock.acquire()
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        self._lock.release()


class CacheService:
    def __init__(self):
        self.redis_client = _redis_client
        self._locks: "WeakValueDictionary[str, _KeyLock]" = WeakValueDictionary()
        self._meta_lock = threading.Lock()
        self._memory_cache: "OrderedDict[str, tuple[Any, float]]" = OrderedDict()
        self._mem_lock = threading.Lock()

    def get_lock(self, key: str) -> _KeyLock:
        with self._meta_lock:
            lock = self._locks.get(key)
            if lock is None:
                lock = _KeyLock()
                self._locks[key] = lock
            return lock

    def _mem_get(self, key: str) -> Any:
        with self._mem_lock:
            return self._mem_get_unlocked(key, time.time())

    def _mem_get_unlocked(self, key: str, now: float) -> Any:
        item = self._memory_cache.get(key)
        if item is None:
            return None
        raw_json, expires_at = item
        if now < expires_at:
            self._memory_cache.move_to_end(key)
            return json.loads(raw_json)
        del self._memory_cache[key]
        return None

    def _mem_set_unlocked(self, key: str, raw_json: str, expire: int, now: float) -> None:
        if len(self._memory_cache) >= MAX_CACHE_ENTRIES:
            expired = [k for k, (_, exp) in self._memory_cache.items() if exp <= now]
            for k in expired:
                del self._memory_cache[k]
            while len(self._memory_cache) >= MAX_CACHE_ENTRIES:
                self._memory_cache.popitem(last=False)
        self._memory_cache[key] = (raw_json, now + expire)
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
            raw_json = json.dumps(value, allow_nan=False)
        except (TypeError, ValueError) as e:
            logging.error(f"Cache serialize error for {key}: {e}")
            return
        try:
            self.redis_client.set(
                key,
                raw_json,
                ex=expire,
            )
            self._mem_delete(key)
        except Exception as e:
            logging.warning(f"Redis set error: {e}, storing in in-memory fallback")
            now = time.time()
            with self._mem_lock:
                self._mem_set_unlocked(key, raw_json, expire, now)

    def mset_pipeline(self, items: Dict[str, Any], expire: int = 300) -> None:
        if not items:
            return
        serialized: Dict[str, str] = {}
        for key, value in items.items():
            try:
                serialized[key] = json.dumps(value, allow_nan=False)
            except (TypeError, ValueError) as e:
                logging.error(f"Cache serialize error for {key}: {e}")
        if not serialized:
            return
        try:
            pipe = self.redis_client.pipeline(transaction=False)
            for key, raw_json in serialized.items():
                pipe.set(key, raw_json, ex=expire)
            pipe.execute()
            self._mem_delete(*serialized.keys())
        except Exception as e:
            logging.warning(f"Redis mset_pipeline error: {e}, storing in in-memory fallback")
            now = time.time()
            with self._mem_lock:
                for key, raw_json in serialized.items():
                    self._mem_set_unlocked(key, raw_json, expire, now)

    def delete(self, key: str):
        try:
            self.redis_client.delete(key)
        except Exception as e:
            logging.warning(f"Redis delete error for {key}: {e}")
        self._mem_delete(key)

    def get_or_set_with_hit(
        self,
        key: str,
        builder: Callable[[], T],
        ttl: int | Callable[[T], Optional[int]] = 300,
        validator: Optional[Callable[[Any], bool]] = None,
    ) -> tuple[T, bool]:
        cached = self.get(key)
        if cached is not None and (validator is None or validator(cached)):
            return cached, True
        with self.get_lock(key):
            cached = self.get(key)
            if cached is not None and (validator is None or validator(cached)):
                return cached, True
            value = builder()
            expire: Optional[int] = ttl(value) if callable(ttl) else ttl
            if value is not None and expire is not None and expire > 0:
                self.set(key, value, expire=expire)
            return value, False

    def get_or_set(
        self,
        key: str,
        builder: Callable[[], T],
        ttl: int | Callable[[T], Optional[int]] = 300,
        validator: Optional[Callable[[Any], bool]] = None,
    ) -> T:
        value, _ = self.get_or_set_with_hit(key, builder, ttl=ttl, validator=validator)
        return value


cache_service = CacheService()
