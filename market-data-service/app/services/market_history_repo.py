import sqlite3
import os
import datetime
from typing import Optional, List, Dict, Any
from app.config import settings

class MarketHistoryRepository:
    def __init__(
        self,
        db_path: Optional[str] = None,
        connection: Any = None,
        database_url: Optional[str] = None,
        dialect: Optional[str] = None,
    ):
        self.db_path = db_path or getattr(settings, "MARKET_DB_PATH", "data/market_history.db")
        self.conn = connection
        self.database_url = database_url or getattr(settings, "DATABASE_URL", None)

        db_host = getattr(settings, "DB_HOST", None)
        if dialect:
            self.dialect = dialect
            self.is_postgres = (dialect == "postgres")
        elif self.database_url or db_host:
            self.dialect = "postgres"
            self.is_postgres = True
        else:
            self.dialect = "sqlite"
            self.is_postgres = False

        self.pool = None
        if self.is_postgres and self.conn is None:
            if not self.database_url and db_host:
                user = getattr(settings, "DB_USER", "postgres") or "postgres"
                password = getattr(settings, "DB_PASSWORD", "postgres") or "postgres"
                port = getattr(settings, "DB_PORT", 5432) or 5432
                dbname = getattr(settings, "DB_NAME", "investing_assistant") or "investing_assistant"
                self.database_url = f"postgresql://{user}:{password}@{db_host}:{port}/{dbname}"
            self._ensure_pool()

        try:
            self.init_db()
        except Exception:
            pass

    def _ensure_pool(self) -> None:
        if self.is_postgres and self.conn is None and self.pool is None and self.database_url:
            try:
                import psycopg2
                from psycopg2 import pool
                self.pool = pool.ThreadedConnectionPool(1, 10, dsn=self.database_url)
                self.init_db()
            except Exception:
                pass

    def _get_connection(self) -> tuple[Any, bool]:
        if self.conn is not None:
            return self.conn, True
        if self.is_postgres:
            self._ensure_pool()
            if self.pool is not None:
                conn = self.pool.getconn()
                return conn, False
            import psycopg2
            conn = psycopg2.connect(self.database_url)
            return conn, False
        db_dir = os.path.dirname(self.db_path)
        if db_dir:
            os.makedirs(db_dir, exist_ok=True)
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA busy_timeout=5000;")
        return conn, False

    def _release_connection(self, conn: Any, is_external: bool) -> None:
        if is_external:
            return
        if self.is_postgres:
            if self.pool is not None:
                try:
                    if not getattr(conn, "closed", True):
                        conn.rollback()
                    self.pool.putconn(conn)
                    return
                except Exception:
                    pass
            try:
                conn.close()
            except Exception:
                pass
        else:
            try:
                conn.close()
            except Exception:
                pass

    def init_db(self) -> None:
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            if self.is_postgres:
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS daily_price_history (
                        symbol TEXT,
                        date TEXT,
                        close_price REAL,
                        currency TEXT,
                        volume BIGINT,
                        PRIMARY KEY (symbol, date)
                    );
                """)
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS etf_profile_metrics (
                        symbol TEXT PRIMARY KEY,
                        ter REAL,
                        trailing_pe REAL,
                        dividend_yield REAL,
                        beta REAL,
                        last_updated TEXT
                    );
                """)
            else:
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS daily_price_history (
                        symbol TEXT,
                        date TEXT,
                        close_price REAL,
                        currency TEXT,
                        volume INTEGER,
                        PRIMARY KEY (symbol, date)
                    );
                """)
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS etf_profile_metrics (
                        symbol TEXT PRIMARY KEY,
                        ter REAL,
                        trailing_pe REAL,
                        dividend_yield REAL,
                        beta REAL,
                        last_updated TEXT
                    );
                """)
            conn.commit()
        finally:
            self._release_connection(conn, is_external)

    def save_eod_price(self, symbol: str, date: str, close_price: float, currency: str = "USD", volume: int = 0) -> bool:
        clean_date = date[:10]
        today_str = datetime.date.today().isoformat()
        if clean_date >= today_str:
            return False

        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            if self.is_postgres:
                cursor.execute(
                    "INSERT INTO daily_price_history (symbol, date, close_price, currency, volume) VALUES (%s, %s, %s, %s, %s) ON CONFLICT (symbol, date) DO NOTHING",
                    (symbol, clean_date, float(close_price), currency, int(volume or 0))
                )
            else:
                cursor.execute(
                    "INSERT OR IGNORE INTO daily_price_history (symbol, date, close_price, currency, volume) VALUES (?, ?, ?, ?, ?)",
                    (symbol, clean_date, float(close_price), currency, int(volume or 0))
                )
            conn.commit()
            return True
        finally:
            self._release_connection(conn, is_external)

    def save_eod_prices_bulk(
        self,
        symbol: str,
        records: List[Dict[str, Any]],
        currency: str = "USD"
    ) -> int:
        if not symbol or not records:
            return 0

        today_str = datetime.date.today().isoformat()
        valid_rows = []
        for r in records:
            if not isinstance(r, dict):
                continue
            dt = str(r.get("date", ""))[:10]
            close = r.get("close_price") or r.get("closePrice") or r.get("close", 0.0)
            curr = r.get("currency") or currency
            vol = r.get("volume", 0) or 0

            if dt and dt < today_str:
                valid_rows.append((
                    symbol,
                    dt,
                    float(close),
                    curr,
                    int(vol or 0)
                ))

        if not valid_rows:
            return 0

        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            if self.is_postgres:
                cursor.executemany(
                    "INSERT INTO daily_price_history (symbol, date, close_price, currency, volume) VALUES (%s, %s, %s, %s, %s) ON CONFLICT (symbol, date) DO UPDATE SET close_price = EXCLUDED.close_price, volume = EXCLUDED.volume",
                    valid_rows
                )
            else:
                cursor.executemany(
                    "INSERT OR REPLACE INTO daily_price_history (symbol, date, close_price, currency, volume) VALUES (?, ?, ?, ?, ?)",
                    valid_rows
                )
            conn.commit()
            return len(valid_rows)
        finally:
            self._release_connection(conn, is_external)

    def delete_symbol_prices(self, symbol: str) -> None:
        ph = "%s" if self.is_postgres else "?"
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute(f"DELETE FROM daily_price_history WHERE symbol = {ph}", (symbol,))
            conn.commit()
        finally:
            self._release_connection(conn, is_external)

    def get_price_on_date(self, symbol: str, date: str) -> Optional[float]:
        ph = "%s" if self.is_postgres else "?"
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute(
                f"SELECT close_price FROM daily_price_history WHERE symbol = {ph} AND date = {ph}",
                (symbol, date[:10])
            )
            row = cursor.fetchone()
            return float(row[0]) if row and row[0] is not None else None
        finally:
            self._release_connection(conn, is_external)

    def get_historical_prices(self, symbol: str, start_date: Optional[str] = None, end_date: Optional[str] = None) -> List[Dict[str, Any]]:
        ph = "%s" if self.is_postgres else "?"
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            query = f"SELECT symbol, date, close_price, currency, volume FROM daily_price_history WHERE symbol = {ph}"
            params: list = [symbol]
            if start_date:
                query += f" AND date >= {ph}"
                params.append(start_date)
            if end_date:
                query += f" AND date <= {ph}"
                params.append(end_date)
            query += " ORDER BY date ASC"
            cursor.execute(query, params)
            rows = cursor.fetchall()
            return [
                {
                    "symbol": r[0],
                    "date": r[1],
                    "closePrice": float(r[2]),
                    "close": float(r[2]),
                    "currency": r[3],
                    "volume": int(r[4] or 0),
                }
                for r in rows
            ]
        finally:
            self._release_connection(conn, is_external)

    def get_max_date(self, symbol: str) -> Optional[str]:
        ph = "%s" if self.is_postgres else "?"
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute(f"SELECT MAX(date) FROM daily_price_history WHERE symbol = {ph}", (symbol,))
            row = cursor.fetchone()
            return row[0] if row and row[0] else None
        finally:
            self._release_connection(conn, is_external)

    def save_profile_metrics(
        self,
        symbol: str,
        ter: float,
        trailing_pe: float,
        dividend_yield: float,
        beta: float,
        last_updated: Optional[str] = None
    ) -> None:
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            updated = last_updated or datetime.date.today().isoformat()
            if self.is_postgres:
                cursor.execute("""
                    INSERT INTO etf_profile_metrics (symbol, ter, trailing_pe, dividend_yield, beta, last_updated)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    ON CONFLICT (symbol) DO UPDATE SET
                        ter = EXCLUDED.ter,
                        trailing_pe = EXCLUDED.trailing_pe,
                        dividend_yield = EXCLUDED.dividend_yield,
                        beta = EXCLUDED.beta,
                        last_updated = EXCLUDED.last_updated
                """, (symbol, float(ter or 0.0), float(trailing_pe or 0.0), float(dividend_yield or 0.0), float(beta or 1.0), updated))
            else:
                cursor.execute("""
                    INSERT OR REPLACE INTO etf_profile_metrics (symbol, ter, trailing_pe, dividend_yield, beta, last_updated)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, (symbol, float(ter or 0.0), float(trailing_pe or 0.0), float(dividend_yield or 0.0), float(beta or 1.0), updated))
            conn.commit()
        finally:
            self._release_connection(conn, is_external)

    def get_profile_metrics(self, symbol: str) -> Optional[Dict[str, Any]]:
        ph = "%s" if self.is_postgres else "?"
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute(f"SELECT ter, trailing_pe, dividend_yield, beta, last_updated FROM etf_profile_metrics WHERE symbol = {ph}", (symbol,))
            row = cursor.fetchone()
            if not row:
                return None
            return {
                "symbol": symbol,
                "ter": float(row[0]),
                "trailingPe": float(row[1]),
                "dividendYield": float(row[2]),
                "beta": float(row[3]),
                "lastUpdated": row[4],
            }
        finally:
            self._release_connection(conn, is_external)

market_history_repo = MarketHistoryRepository()
