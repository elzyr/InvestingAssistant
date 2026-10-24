import sqlite3
import os
import datetime
from typing import Optional, List, Dict, Any
from app.config import settings

class MarketHistoryRepository:
    def __init__(self, db_path: Optional[str] = None, connection: Optional[sqlite3.Connection] = None):
        self.db_path = db_path or getattr(settings, "MARKET_DB_PATH", "data/market_history.db")
        self.conn = connection
        self.init_db()

    def _get_connection(self) -> tuple[sqlite3.Connection, bool]:
        if self.conn is not None:
            return self.conn, True
        db_dir = os.path.dirname(self.db_path)
        if db_dir:
            os.makedirs(db_dir, exist_ok=True)
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA busy_timeout=5000;")
        return conn, False

    def init_db(self) -> None:
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
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
            if not is_external:
                conn.close()

    def save_eod_price(self, symbol: str, date: str, close_price: float, currency: str = "USD", volume: int = 0) -> bool:
        clean_date = date[:10]
        today_str = datetime.date.today().isoformat()
        if clean_date >= today_str:
            return False

        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute(
                "INSERT OR IGNORE INTO daily_price_history (symbol, date, close_price, currency, volume) VALUES (?, ?, ?, ?, ?)",
                (symbol, clean_date, float(close_price), currency, int(volume or 0))
            )
            conn.commit()
            return True
        finally:
            if not is_external:
                conn.close()

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
            cursor.executemany(
                "INSERT OR REPLACE INTO daily_price_history (symbol, date, close_price, currency, volume) VALUES (?, ?, ?, ?, ?)",
                valid_rows
            )
            conn.commit()
            return len(valid_rows)
        finally:
            if not is_external:
                conn.close()

    def delete_symbol_prices(self, symbol: str) -> None:
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute("DELETE FROM daily_price_history WHERE symbol = ?", (symbol,))
            conn.commit()
        finally:
            if not is_external:
                conn.close()

    def get_price_on_date(self, symbol: str, date: str) -> Optional[float]:
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute(
                "SELECT close_price FROM daily_price_history WHERE symbol = ? AND date = ?",
                (symbol, date[:10])
            )
            row = cursor.fetchone()
            return float(row[0]) if row and row[0] is not None else None
        finally:
            if not is_external:
                conn.close()

    def get_historical_prices(self, symbol: str, start_date: Optional[str] = None, end_date: Optional[str] = None) -> List[Dict[str, Any]]:
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            query = "SELECT symbol, date, close_price, currency, volume FROM daily_price_history WHERE symbol = ?"
            params: list = [symbol]
            if start_date:
                query += " AND date >= ?"
                params.append(start_date)
            if end_date:
                query += " AND date <= ?"
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
            if not is_external:
                conn.close()

    def get_max_date(self, symbol: str) -> Optional[str]:
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute("SELECT MAX(date) FROM daily_price_history WHERE symbol = ?", (symbol,))
            row = cursor.fetchone()
            return row[0] if row and row[0] else None
        finally:
            if not is_external:
                conn.close()

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
            cursor.execute("""
                INSERT OR REPLACE INTO etf_profile_metrics (symbol, ter, trailing_pe, dividend_yield, beta, last_updated)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (symbol, float(ter or 0.0), float(trailing_pe or 0.0), float(dividend_yield or 0.0), float(beta or 1.0), updated))
            conn.commit()
        finally:
            if not is_external:
                conn.close()

    def get_profile_metrics(self, symbol: str) -> Optional[Dict[str, Any]]:
        conn, is_external = self._get_connection()
        try:
            cursor = conn.cursor()
            cursor.execute("SELECT ter, trailing_pe, dividend_yield, beta, last_updated FROM etf_profile_metrics WHERE symbol = ?", (symbol,))
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
            if not is_external:
                conn.close()

market_history_repo = MarketHistoryRepository()
