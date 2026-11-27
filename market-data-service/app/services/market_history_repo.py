import atexit
from contextlib import contextmanager
import datetime
import logging
import os
import re
import sqlite3
import threading
from typing import Any, Dict, Iterator, List, Optional
from app.config import settings
from app.services.yahoo_service import finite_float

_SCHEMA_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_MIGRATABLE_REAL_COLUMNS = {
    ("daily_price_history", "close_price"),
    ("etf_profile_metrics", "ter"),
    ("etf_profile_metrics", "trailing_pe"),
    ("etf_profile_metrics", "dividend_yield"),
    ("etf_profile_metrics", "beta"),
    ("macro_cpi", "rate"),
}


def _is_conn_closed(conn: Any) -> bool:
    closed = getattr(conn, "closed", 0)
    return bool(closed) if isinstance(closed, (int, bool)) else False


def _parse_volume(raw: Any) -> int:
    val = finite_float(raw)
    return max(0, int(val)) if val is not None else 0


class MarketHistoryRepository:
    def __init__(
        self,
        db_path: Optional[str] = None,
        connection: Any = None,
        database_url: Optional[str] = None,
        dialect: Optional[str] = None,
        lazy_init: bool = False,
    ):
        self.db_path = db_path
        self.conn = connection
        self.database_url = database_url or getattr(settings, "DATABASE_URL", None)

        if dialect:
            self.dialect = dialect
            self.is_postgres = (dialect == "postgres")
        elif self.conn is not None and isinstance(self.conn, sqlite3.Connection):
            self.dialect = "sqlite"
            self.is_postgres = False
        elif db_path is not None and not database_url:
            self.dialect = "sqlite"
            self.is_postgres = False
        else:
            self.dialect = "postgres"
            self.is_postgres = True

        raw_schema = getattr(settings, "DB_SCHEMA", "market_data") or "market_data"
        if not _SCHEMA_RE.fullmatch(raw_schema):
            raise ValueError(f"Invalid DB_SCHEMA: {raw_schema!r}")
        self.schema = raw_schema
        self.pool = None
        self._pool_lock = threading.Lock()
        self._init_lock = threading.Lock()
        self._initialized = False

        if not lazy_init:
            self._configure_postgres_dsn()
            if self.is_postgres and self.conn is None:
                self._ensure_pool()
            self.init_db()

    def _configure_postgres_dsn(self) -> None:
        if not self.is_postgres or self.conn is not None:
            return
        if self.database_url:
            return
        env_url = getattr(settings, "DATABASE_URL", None)
        if env_url:
            self.database_url = env_url
            return
        db_host = getattr(settings, "DB_HOST", None)
        env_user = getattr(settings, "DB_USER", None)
        env_password = getattr(settings, "DB_PASSWORD", None)
        dbname = getattr(settings, "DB_NAME", None)
        missing = [
            k
            for k, v in (
                ("DB_HOST", db_host),
                ("DB_USER", env_user),
                ("DB_PASSWORD", env_password),
                ("DB_NAME", dbname),
            )
            if not v
        ]
        if missing:
            raise ValueError(f"Missing PostgreSQL config: {', '.join(missing)}")
        from urllib.parse import quote_plus
        port = getattr(settings, "DB_PORT", 5432) or 5432
        self.database_url = (
            f"postgresql://{quote_plus(str(env_user))}:{quote_plus(str(env_password))}@{db_host}:{port}/{dbname}"
        )

    def close(self) -> None:
        with self._pool_lock:
            pool = self.pool
            self.pool = None
        if pool is not None:
            try:
                pool.closeall()
            except Exception:
                pass

    def _ensure_initialized(self) -> None:
        if self._initialized or self.conn is not None:
            return
        with self._init_lock:
            if not self._initialized:
                self._configure_postgres_dsn()
                self.init_db()

    @contextmanager
    def _cursor(self, commit: bool = False, ensure_init: bool = True) -> Iterator[Any]:
        if ensure_init:
            self._ensure_initialized()
        conn, is_external, origin_pool = self._acquire_connection()
        try:
            cursor = conn.cursor()
            try:
                yield cursor
                if commit:
                    conn.commit()
            finally:
                try:
                    cursor.close()
                except Exception:
                    pass
        finally:
            self._release_connection(conn, is_external, origin_pool)

    @property
    def _ph(self) -> str:
        return "%s" if self.is_postgres else "?"

    def _ensure_pool(self) -> None:
        if not (self.is_postgres and self.conn is None and self.pool is None and self.database_url):
            return
        with self._pool_lock:
            if self.pool is not None:
                return
            try:
                from psycopg2 import pool
                self.pool = pool.ThreadedConnectionPool(
                    1,
                    24,
                    dsn=self.database_url,
                    connect_timeout=5,
                    options=f"-c search_path={self.schema},public",
                )
            except Exception as e:
                logging.warning(f"Postgres connection pool initialization failed: {e}")

    def _acquire_connection(self) -> tuple[Any, bool, Any]:
        if self.conn is not None:
            return self.conn, True, None
        if self.is_postgres:
            self._ensure_pool()
            with self._pool_lock:
                origin_pool = self.pool
                if origin_pool is not None:
                    conn = origin_pool.getconn()
                    if _is_conn_closed(conn):
                        try:
                            origin_pool.putconn(conn, close=True)
                        except Exception:
                            pass
                        conn = origin_pool.getconn()
                    return conn, False, origin_pool
            import psycopg2
            conn = psycopg2.connect(
                self.database_url,
                connect_timeout=5,
                options=f"-c search_path={self.schema},public",
            )
            return conn, False, None
        db_dir = os.path.dirname(self.db_path)
        if db_dir:
            os.makedirs(db_dir, exist_ok=True)
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.execute("PRAGMA busy_timeout=5000;")
        return conn, False, None

    def _get_connection(self) -> tuple[Any, bool]:
        conn, is_external, _ = self._acquire_connection()
        return conn, is_external

    def _release_connection(self, conn: Any, is_external: bool, origin_pool: Any = None) -> None:
        if is_external:
            return
        if self.is_postgres:
            with self._pool_lock:
                target_pool = origin_pool if origin_pool is not None else self.pool
                if target_pool is not None and target_pool is self.pool:
                    try:
                        if _is_conn_closed(conn):
                            target_pool.putconn(conn, close=True)
                            return
                        conn.rollback()
                        target_pool.putconn(conn)
                        return
                    except Exception as e:
                        logging.warning(f"Failed to return Postgres connection to pool: {e}")
                        try:
                            target_pool.putconn(conn, close=True)
                            return
                        except Exception:
                            pass
            try:
                conn.close()
            except Exception as e:
                logging.warning(f"Failed to close Postgres connection: {e}")
        else:
            try:
                conn.close()
            except Exception as e:
                logging.warning(f"Failed to close SQLite connection: {e}")

    def init_db(self) -> None:
        self._configure_postgres_dsn()
        with self._cursor(commit=True, ensure_init=False) as cursor:
            vol_type = "BIGINT" if self.is_postgres else "INTEGER"
            float_type = "DOUBLE PRECISION" if self.is_postgres else "REAL"
            if self.is_postgres:
                cursor.execute(f"CREATE SCHEMA IF NOT EXISTS {self.schema};")
                if self.conn is not None:
                    cursor.execute(f"SET search_path TO {self.schema}, public;")
            else:
                cursor.execute("PRAGMA journal_mode=WAL;")

            cursor.execute(f"""
                CREATE TABLE IF NOT EXISTS daily_price_history (
                    symbol TEXT,
                    date TEXT,
                    close_price {float_type},
                    currency TEXT,
                    volume {vol_type},
                    PRIMARY KEY (symbol, date)
                );
            """)
            cursor.execute(f"""
                CREATE TABLE IF NOT EXISTS etf_profile_metrics (
                    symbol TEXT PRIMARY KEY,
                    ter {float_type},
                    trailing_pe {float_type},
                    dividend_yield {float_type},
                    beta {float_type},
                    last_updated TEXT,
                    history_checked_at TEXT
                );
            """)
            cursor.execute(f"""
                CREATE TABLE IF NOT EXISTS macro_cpi (
                    year INTEGER,
                    month INTEGER,
                    rate {float_type},
                    rate_mm {float_type},
                    source TEXT,
                    PRIMARY KEY (year, month)
                );
            """)
            if self.is_postgres:
                cursor.execute(f"ALTER TABLE {self.schema}.etf_profile_metrics ADD COLUMN IF NOT EXISTS history_checked_at TEXT;")
                cursor.execute(f"ALTER TABLE {self.schema}.macro_cpi ADD COLUMN IF NOT EXISTS rate_mm {float_type};")
            else:
                cursor.execute("PRAGMA table_info(etf_profile_metrics);")
                if "history_checked_at" not in {r[1] for r in cursor.fetchall()}:
                    cursor.execute("ALTER TABLE etf_profile_metrics ADD COLUMN history_checked_at TEXT;")
                cursor.execute("PRAGMA table_info(macro_cpi);")
                if "rate_mm" not in {r[1] for r in cursor.fetchall()}:
                    cursor.execute(f"ALTER TABLE macro_cpi ADD COLUMN rate_mm {float_type};")
            if self.is_postgres:
                cursor.execute(
                    "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = %s AND data_type = 'real'",
                    (self.schema,),
                )
                for table_name, column_name in cursor.fetchall() or ():
                    if (table_name, column_name) in _MIGRATABLE_REAL_COLUMNS:
                        cursor.execute(
                            f"ALTER TABLE {self.schema}.{table_name} ALTER COLUMN {column_name} TYPE DOUBLE PRECISION"
                        )
            self._initialized = True

    def _prepare_eod_rows(
        self,
        symbol: str,
        records: List[Dict[str, Any]],
        currency: Optional[str] = None,
    ) -> List[tuple]:
        if not symbol or not records:
            return []

        today_str = datetime.date.today().isoformat()
        valid_rows: List[tuple] = []
        for r in records:
            if not isinstance(r, dict):
                continue
            dt = str(r.get("date", ""))[:10]
            raw_close = None
            for key in ("close_price", "closePrice", "close"):
                if r.get(key) is not None:
                    raw_close = r[key]
                    break
            close_val = finite_float(raw_close, positive_only=True)
            curr = (r.get("currency") or currency or "").strip().upper()
            if dt and dt < today_str and close_val is not None and curr:
                valid_rows.append((
                    symbol,
                    dt,
                    close_val,
                    curr,
                    _parse_volume(r.get("volume")),
                ))
        return valid_rows

    def _upsert_eod_rows(self, cursor: Any, valid_rows: List[tuple]) -> None:
        ph = self._ph
        cursor.executemany(
            f"""
            INSERT INTO daily_price_history (symbol, date, close_price, currency, volume)
            VALUES ({ph}, {ph}, {ph}, {ph}, {ph})
            ON CONFLICT (symbol, date) DO UPDATE SET
                close_price = EXCLUDED.close_price,
                currency = EXCLUDED.currency,
                volume = EXCLUDED.volume
            """,
            valid_rows,
        )

    def save_eod_prices_bulk(
        self,
        symbol: str,
        records: List[Dict[str, Any]],
        currency: Optional[str] = None,
    ) -> int:
        valid_rows = self._prepare_eod_rows(symbol, records, currency)
        if not valid_rows:
            return 0
        with self._cursor(commit=True) as cursor:
            self._upsert_eod_rows(cursor, valid_rows)
            return len(valid_rows)

    def replace_symbol_prices(
        self,
        symbol: str,
        records: List[Dict[str, Any]],
        currency: Optional[str] = None,
        preserve_older: bool = False,
    ) -> int:
        valid_rows = self._prepare_eod_rows(symbol, records, currency)
        if not valid_rows:
            return 0
        with self._cursor(commit=True) as cursor:
            if preserve_older:
                min_date = min(row[1] for row in valid_rows)
                cursor.execute(
                    f"DELETE FROM daily_price_history WHERE symbol = {self._ph} AND date >= {self._ph}",
                    (symbol, min_date),
                )
            else:
                cursor.execute(f"DELETE FROM daily_price_history WHERE symbol = {self._ph}", (symbol,))
            self._upsert_eod_rows(cursor, valid_rows)
            return len(valid_rows)

    def get_price_on_date(self, symbol: str, date: str) -> Optional[float]:
        with self._cursor() as cursor:
            cursor.execute(
                f"SELECT close_price FROM daily_price_history WHERE symbol = {self._ph} AND date = {self._ph}",
                (symbol, date[:10])
            )
            row = cursor.fetchone()
            return finite_float(row[0], positive_only=True) if row else None

    def get_historical_prices(self, symbol: str, start_date: Optional[str] = None, end_date: Optional[str] = None) -> List[Dict[str, Any]]:
        with self._cursor() as cursor:
            query = f"SELECT symbol, date, close_price, currency, volume FROM daily_price_history WHERE symbol = {self._ph}"
            params: list = [symbol]
            if start_date:
                query += f" AND date >= {self._ph}"
                params.append(start_date)
            if end_date:
                query += f" AND date <= {self._ph}"
                params.append(end_date)
            query += " ORDER BY date ASC"
            cursor.execute(query, params)
            rows = cursor.fetchall()
            result: List[Dict[str, Any]] = []
            for r in rows:
                price = finite_float(r[2], positive_only=True)
                if price is None:
                    continue
                result.append({
                    "symbol": r[0],
                    "date": r[1],
                    "closePrice": price,
                    "close": price,
                    "currency": r[3],
                    "volume": _parse_volume(r[4]),
                })
            return result

    def get_max_date(self, symbol: str) -> Optional[str]:
        with self._cursor() as cursor:
            cursor.execute(f"SELECT MAX(date) FROM daily_price_history WHERE symbol = {self._ph}", (symbol,))
            row = cursor.fetchone()
            return row[0] if row and row[0] else None

    def get_price_coverage(self, symbol: str) -> Optional[Dict[str, Any]]:
        with self._cursor() as cursor:
            cursor.execute(
                f"SELECT MIN(date), MAX(date), COUNT(*) FROM daily_price_history WHERE symbol = {self._ph}",
                (symbol,),
            )
            row = cursor.fetchone()
            if not row or not row[0]:
                return None
            return {
                "minDate": row[0],
                "maxDate": row[1],
                "count": int(row[2] or 0),
            }

    def save_profile_metrics(
        self,
        symbol: str,
        ter: Optional[float],
        trailing_pe: Optional[float] = None,
        dividend_yield: Optional[float] = None,
        beta: Optional[float] = None,
        last_updated: Optional[str] = None,
        history_checked_at: Optional[str] = None,
    ) -> None:
        with self._cursor(commit=True) as cursor:
            updated = last_updated or datetime.date.today().isoformat()
            ph = self._ph
            params = (
                symbol,
                finite_float(ter),
                finite_float(trailing_pe),
                finite_float(dividend_yield),
                finite_float(beta),
                updated,
                history_checked_at,
            )
            cursor.execute(
                f"""
                INSERT INTO etf_profile_metrics (symbol, ter, trailing_pe, dividend_yield, beta, last_updated, history_checked_at)
                VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph})
                ON CONFLICT (symbol) DO UPDATE SET
                    ter = EXCLUDED.ter,
                    trailing_pe = EXCLUDED.trailing_pe,
                    dividend_yield = EXCLUDED.dividend_yield,
                    beta = EXCLUDED.beta,
                    last_updated = EXCLUDED.last_updated,
                    history_checked_at = COALESCE(EXCLUDED.history_checked_at, etf_profile_metrics.history_checked_at)
                """,
                params,
            )

    def get_profile_metrics(self, symbol: str) -> Optional[Dict[str, Any]]:
        with self._cursor() as cursor:
            cursor.execute(
                f"SELECT ter, trailing_pe, dividend_yield, beta, last_updated, history_checked_at FROM etf_profile_metrics WHERE symbol = {self._ph}",
                (symbol,),
            )
            row = cursor.fetchone()
            if not row:
                return None
            return {
                "symbol": symbol,
                "ter": finite_float(row[0]),
                "trailingPe": finite_float(row[1]),
                "dividendYield": finite_float(row[2]),
                "beta": finite_float(row[3]),
                "lastUpdated": row[4],
                "historyCheckedAt": row[5],
            }

    def save_cpi_records(self, records: List[Dict[str, Any]], allow_overwrite_authoritative: bool = False) -> int:
        if not records:
            return 0
        data = []
        for r in records:
            rate = finite_float(r.get("rate"))
            rate_mm = finite_float(r.get("rateMm") if "rateMm" in r else r.get("rate_mm"))
            if rate is None and rate_mm is None:
                continue
            try:
                year = int(r["year"])
                month = int(r["month"])
            except (KeyError, TypeError, ValueError):
                continue
            data.append((year, month, rate, rate_mm, str(r.get("source", "GUS"))))
        if not data:
            return 0

        with self._cursor(commit=True) as cursor:
            ph = self._ph
            where_clause = (
                ""
                if allow_overwrite_authoritative
                else "\n                        WHERE (macro_cpi.source NOT IN ('GUS', 'GUS (szacunek)') OR excluded.source = 'GUS' OR (macro_cpi.source = 'GUS (szacunek)' AND excluded.source = 'GUS (szacunek)'))"
            )
            query = f"""
                        INSERT INTO macro_cpi (year, month, rate, rate_mm, source)
                        VALUES ({ph}, {ph}, {ph}, {ph}, {ph})
                        ON CONFLICT (year, month) DO UPDATE SET
                            rate = COALESCE(excluded.rate, macro_cpi.rate),
                            rate_mm = COALESCE(excluded.rate_mm, macro_cpi.rate_mm),
                            source = excluded.source{where_clause}
                    """
            cursor.executemany(query, data)
            return len(data)

    def get_cpi_records(self, year: Optional[int] = None) -> List[Dict[str, Any]]:
        with self._cursor() as cursor:
            if year is not None:
                cursor.execute(f"SELECT year, month, rate, rate_mm, source FROM macro_cpi WHERE year = {self._ph} ORDER BY year ASC, month ASC", (int(year),))
            else:
                cursor.execute("SELECT year, month, rate, rate_mm, source FROM macro_cpi ORDER BY year ASC, month ASC")
            rows = cursor.fetchall()
            out: List[Dict[str, Any]] = []
            for r in rows:
                rate = finite_float(r[2])
                rate_mm = finite_float(r[3])
                if rate is not None or rate_mm is not None:
                    out.append({"year": int(r[0]), "month": int(r[1]), "rate": rate, "rateMm": rate_mm, "source": r[4]})
            return out

    def get_latest_cpi(self) -> Optional[Dict[str, Any]]:
        with self._cursor() as cursor:
            cursor.execute("SELECT year, month, rate, rate_mm, source FROM macro_cpi WHERE rate IS NOT NULL ORDER BY year DESC, month DESC LIMIT 1")
            row = cursor.fetchone()
            if not row:
                return None
            rate = finite_float(row[2])
            if rate is None:
                return None
            return {"year": int(row[0]), "month": int(row[1]), "rate": rate, "rateMm": finite_float(row[3]), "source": row[4]}


market_history_repo = MarketHistoryRepository(lazy_init=True)
atexit.register(market_history_repo.close)
