import math
import re
import logging
from datetime import datetime, timedelta
from typing import Dict, Optional
import numpy as np
import pandas as pd
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
import yfinance as yf
from fastapi import HTTPException
from app.services.cache_service import cache_service

_session = requests.Session()
_adapter = HTTPAdapter(pool_connections=10, pool_maxsize=20, max_retries=Retry(total=2, backoff_factor=0.5))
_session.mount("https://", _adapter)
_session.mount("http://", _adapter)

_CURRENCY_RE = re.compile(r"^[A-Za-z]{3}$")

SUFFIX_MAP = {
    ".WA": "PLN",
    ".DE": "EUR",
    ".F": "EUR",
    ".PA": "EUR",
    ".AS": "EUR",
    ".MC": "EUR",
    ".MI": "EUR",
    ".L": "GBP",
}

EXCHANGE_MAP = {
    "NMS": "USD",
    "NYQ": "USD",
    "NGM": "USD",
    "PCX": "USD",
    "BTS": "USD",
    "BATS": "USD",
    "ASE": "USD",
    "NAS": "USD",
    "PNK": "USD",
    "GER": "EUR",
    "FRA": "EUR",
    "STU": "EUR",
    "XETRA": "EUR",
    "PAR": "EUR",
    "AMS": "EUR",
    "WSE": "PLN",
    "WAR": "PLN",
}

SUPPORTED_SEARCH_CURRENCIES = {"USD", "EUR", "PLN", "GBP", "CHF"}


def infer_base_currency(symbol: str) -> str:
    sym_upper = (symbol or "").upper()
    for suffix, currency in SUFFIX_MAP.items():
        if sym_upper.endswith(suffix):
            return currency
    return "USD"


def extract_date_col(df: pd.DataFrame) -> str:
    if "Date" in df.columns:
        return "Date"
    if "Datetime" in df.columns:
        return "Datetime"
    return df.columns[0]


def _is_intraday_interval(interval: str) -> bool:
    return (interval or "").endswith(("m", "h"))


def _finite_float(val) -> Optional[float]:
    if val is None:
        return None
    try:
        num = float(val)
    except (TypeError, ValueError):
        return None
    return num if math.isfinite(num) else None

def _fetch_nbp_rate(from_cur: str, to_cur: str, clean_date: Optional[str] = None) -> Optional[float]:
    if from_cur != "PLN" and to_cur != "PLN":
        return None
    foreign_code = to_cur if from_cur == "PLN" else from_cur
    try:
        if clean_date:
            target_dt = datetime.strptime(clean_date, "%Y-%m-%d")
            start_str = (target_dt - timedelta(days=7)).strftime("%Y-%m-%d")
            url = f"https://api.nbp.pl/api/exchangerates/rates/a/{foreign_code.lower()}/{start_str}/{clean_date}/?format=json"
        else:
            url = f"https://api.nbp.pl/api/exchangerates/rates/a/{foreign_code.lower()}/?format=json"
        resp = _session.get(url, timeout=5)
        if resp.status_code == 200:
            rates = resp.json().get("rates", [])
            if rates:
                mid = float(rates[-1]["mid"])
                return float(1.0 / mid if from_cur == "PLN" else mid)
    except Exception as e:
        logging.warning(f"NBP rate fetch failed for {foreign_code} on {clean_date or 'spot'}: {e}")
    return None

class YahooService:
    @staticmethod
    def get_exchange_rate(from_currency: str, to_currency: str, date_str: str = None) -> float:
        if not _CURRENCY_RE.match(from_currency or "") or not _CURRENCY_RE.match(to_currency or ""):
            raise HTTPException(status_code=400, detail="Currency code must be a 3-letter ISO code")

        from_cur = from_currency.upper()
        to_cur = to_currency.upper()
        if from_cur == to_cur:
            return 1.0

        clean_date = None
        if date_str:
            clean_date = date_str[:10]
            try:
                datetime.strptime(clean_date, "%Y-%m-%d")
            except ValueError:
                raise HTTPException(status_code=400, detail=f"Invalid date format: {date_str}. Expected YYYY-MM-DD")

        cache_key = f"rate:{from_cur}:{to_cur}:{clean_date}" if clean_date else f"rate:{from_cur}:{to_cur}"
        with cache_service.get_lock(cache_key):
            cached_data = cache_service.get(cache_key)
            if cached_data is not None and "rate" in cached_data:
                return float(cached_data["rate"])

            rate = _fetch_nbp_rate(from_cur, to_cur, clean_date)
            is_spot_fallback = False

            if clean_date and rate is None:
                try:
                    ticker = f"{from_cur}{to_cur}=X"
                    target_dt = datetime.strptime(clean_date, "%Y-%m-%d")
                    end_dt = target_dt + timedelta(days=1)
                    start_dt = target_dt - timedelta(days=7)
                    stock = yf.Ticker(ticker)
                    hist = stock.history(start=start_dt.strftime("%Y-%m-%d"), end=end_dt.strftime("%Y-%m-%d"))
                    if not hist.empty:
                        hist = hist.dropna(subset=["Close"])
                        hist = hist[hist.index.strftime("%Y-%m-%d") <= clean_date]
                        if not hist.empty:
                            rate = float(hist["Close"].iloc[-1])
                except Exception as e:
                    logging.warning(f"Yahoo historical FX rate fetch failed for {from_cur}->{to_cur} on {clean_date}: {e}")

            if rate is None:
                if clean_date:
                    is_spot_fallback = True
                ticker = f"{from_cur}{to_cur}=X"
                stock = yf.Ticker(ticker)
                info = stock.fast_info
                price = _finite_float(
                    getattr(info, "last_price", None)
                    if hasattr(info, "last_price")
                    else (info.get("last_price") if isinstance(info, dict) else None)
                )
                if price is None:
                    hist = stock.history(period="1d")
                    if hist.empty:
                        hist = stock.history(period="5d")
                    if not hist.empty and "Close" in hist.columns:
                        hist = hist.dropna(subset=["Close"])
                        hist = hist[np.isfinite(hist["Close"])]
                    if hist.empty:
                        raise HTTPException(status_code=404, detail=f"Exchange rate {from_currency} to {to_currency} not found")
                    rate = float(hist["Close"].iloc[-1])
                else:
                    rate = price

            if not is_spot_fallback:
                expire = 2592000 if clean_date else 300
                cache_service.set(cache_key, {"rate": rate}, expire=expire)

            return rate

    @staticmethod
    def get_historical_fx_rates(
        from_currency: str,
        to_currency: str,
        start_date: str,
        end_date: Optional[str] = None,
    ) -> Dict[str, float]:
        from_cur = from_currency.upper()
        to_cur = to_currency.upper()
        if from_cur == to_cur:
            return {}
        fx_ticker = f"{from_cur}{to_cur}=X"
        try:
            fx_stock = yf.Ticker(fx_ticker)
            if end_date:
                end_dt = (datetime.strptime(end_date[:10], "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")
                fx_hist = fx_stock.history(start=start_date[:10], end=end_dt, interval="1d")
            else:
                fx_hist = fx_stock.history(start=start_date[:10], interval="1d")
            if fx_hist.empty or "Close" not in fx_hist.columns:
                return {}
            fx_hist = fx_hist.dropna(subset=["Close"])
            fx_hist = fx_hist[np.isfinite(fx_hist["Close"])]
            if fx_hist.empty:
                return {}
            fx_dates = fx_hist.index
            fx_date_strs = (
                fx_dates.strftime("%Y-%m-%d")
                if hasattr(fx_dates, "strftime")
                else fx_dates.astype(str).str[:10]
            )
            return {
                str(d): float(c)
                for d, c in zip(fx_date_strs, fx_hist["Close"].values)
                if math.isfinite(float(c)) and float(c) > 0
            }
        except Exception as e:
            logging.warning(f"Historical FX series fetch failed for {fx_ticker}: {e}")
            return {}

    @staticmethod
    def get_current_price(symbol: str, target_currency: str = None) -> dict:
        try:
            stock = yf.Ticker(symbol)
            info = stock.fast_info

            price = _finite_float(getattr(info, "last_price", None))
            prev_close = _finite_float(getattr(info, "previous_close", None))

            if price is None or prev_close is None:
                hist = stock.history(period="5d")
                if not hist.empty and "Close" in hist.columns:
                    hist = hist.dropna(subset=["Close"])
                    hist = hist[np.isfinite(hist["Close"])]
                if hist.empty:
                    raise HTTPException(status_code=404, detail=f"Symbol {symbol} not found")
                if price is None:
                    price = float(hist["Close"].iloc[-1])
                if prev_close is None:
                    prev_close = float(hist["Close"].iloc[-2]) if len(hist) >= 2 else float(price)

            change = float(price - prev_close)
            change_percent = float((change / prev_close) * 100) if prev_close else 0.0

            base_currency = (getattr(info, "currency", None) or infer_base_currency(symbol)).upper()

            if not target_currency or target_currency.upper() == base_currency:
                return {
                    "price": round(price, 4),
                    "previousClose": round(prev_close, 4),
                    "change": round(change, 4),
                    "changePercent": round(change_percent, 2),
                    "currency": base_currency,
                }

            target_currency = target_currency.upper()
            rate = YahooService.get_exchange_rate(base_currency, target_currency)

            return {
                "price": round(float(price * rate), 4),
                "previousClose": round(float(prev_close * rate), 4),
                "change": round(float(change * rate), 4),
                "changePercent": round(change_percent, 2),
                "currency": target_currency,
            }
        except Exception as e:
            if isinstance(e, HTTPException):
                raise e
            logging.error(f"Error fetching data: {e}")
            raise HTTPException(status_code=502, detail="Upstream market provider failure")

    @staticmethod
    def get_historical_data(
        symbol: str,
        period: str = "1mo",
        interval: str = "1d",
        target_currency: str = None,
        start_date: Optional[str] = None,
    ) -> dict:
        try:
            stock = yf.Ticker(symbol)
            hist = (
                stock.history(start=start_date, interval=interval)
                if start_date
                else stock.history(period=period, interval=interval)
            )
            if hist.empty:
                raise HTTPException(status_code=404, detail=f"No historical data for symbol {symbol}")

            info = stock.fast_info
            base_currency = (getattr(info, "currency", None) or infer_base_currency(symbol)).upper()

            hist = hist.reset_index().dropna(subset=["Close"])
            hist = hist[np.isfinite(hist["Close"])]
            if hist.empty:
                raise HTTPException(status_code=404, detail=f"No valid historical data for symbol {symbol}")

            date_col = extract_date_col(hist)
            dates = hist[date_col]
            intraday = _is_intraday_interval(interval)
            date_fmt = "%Y-%m-%dT%H:%M:%S" if intraday else "%Y-%m-%d"
            if hasattr(dates, "dt") and hasattr(dates.dt, "strftime"):
                hist["date"] = dates.dt.strftime(date_fmt)
            else:
                hist["date"] = dates.astype(str).str[:19] if intraday else dates.astype(str).str[:10]

            actual_currency = base_currency
            rate_multiplier = 1.0

            if target_currency and target_currency.upper() != base_currency:
                target_cur = target_currency.upper()
                fx_ticker = f"{base_currency}{target_cur}=X"
                try:
                    fx_stock = yf.Ticker(fx_ticker)
                    fx_hist = (
                        fx_stock.history(start=start_date, interval=interval)
                        if start_date
                        else fx_stock.history(period=period, interval=interval)
                    )
                    if not fx_hist.empty and "Close" in fx_hist.columns:
                        fx_hist = fx_hist.dropna(subset=["Close"])
                        fx_hist = fx_hist[np.isfinite(fx_hist["Close"])]
                    if not fx_hist.empty and "Close" in fx_hist.columns:
                        fx_dates = fx_hist.index
                        fx_date_str = (
                            fx_dates.strftime("%Y-%m-%d")
                            if hasattr(fx_dates, "strftime")
                            else fx_dates.astype(str).str[:10]
                        )
                        fx_df = pd.DataFrame({"fx_date": fx_date_str, "fx_rate": fx_hist["Close"].values})
                        fx_df = fx_df.drop_duplicates(subset=["fx_date"], keep="last")
                        merge_keys = pd.DataFrame({"_merge_date": hist["date"].str[:10]})
                        merged = merge_keys.merge(fx_df, left_on="_merge_date", right_on="fx_date", how="left")
                        merged["fx_rate"] = merged["fx_rate"].ffill().bfill()
                        if merged["fx_rate"].notna().all():
                            rate_multiplier = merged["fx_rate"].values
                            actual_currency = target_cur
                except Exception as e:
                    logging.warning(f"Vector FX fetch failed for {fx_ticker}: {e}")

            close_series = (hist["Close"] * rate_multiplier).round(4).astype(float)
            hist["close"] = close_series
            for out_col, src_col in (("open", "Open"), ("high", "High"), ("low", "Low")):
                if src_col in hist.columns:
                    col_vals = (hist[src_col] * rate_multiplier).round(4)
                    col_vals = col_vals.replace([np.inf, -np.inf], np.nan).fillna(close_series)
                    hist[out_col] = col_vals.astype(float)
                else:
                    hist[out_col] = close_series

            if "Volume" in hist.columns:
                vol_vals = pd.to_numeric(hist["Volume"], errors="coerce")
                hist["volume"] = vol_vals.replace([np.inf, -np.inf], np.nan).fillna(0).astype(int)
            else:
                hist["volume"] = 0

            data = hist[["date", "open", "high", "low", "close", "volume"]].to_dict("records")
            return {"history": data, "currency": actual_currency}
        except Exception as e:
            if isinstance(e, HTTPException):
                raise e
            logging.error(f"Error fetching historical data: {e}")
            raise HTTPException(status_code=502, detail="Upstream market provider failure")

    @staticmethod
    def search_instruments(query: str) -> list[dict]:
        clean_q = (query or "").strip()
        if not clean_q:
            return []
        cache_key = f"search:{clean_q.upper()}"
        lock = cache_service.get_lock(cache_key)
        with lock:
            cached = cache_service.get(cache_key)
            if cached is not None and isinstance(cached, list):
                return cached

            try:
                search_obj = yf.Search(clean_q)
                quotes = getattr(search_obj, "quotes", []) or []
            except Exception as e:
                logging.warning(f"Yahoo search failed for query '{clean_q}': {e}")
                cache_service.set(cache_key, [], expire=300)
                return []

            results = []
            seen = set()
            for item in quotes:
                if not isinstance(item, dict):
                    continue
                symbol = (item.get("symbol") or "").strip()
                if not symbol or symbol in seen:
                    continue
                qtype = (item.get("quoteType") or "").upper()
                if qtype not in ("EQUITY", "ETF"):
                    continue
                mapped_type = "STOCK" if qtype == "EQUITY" else "ETF"

                currency = (item.get("currency") or "").upper() or None
                if not currency:
                    sym_upper = symbol.upper()
                    for sfx, cur in SUFFIX_MAP.items():
                        if sym_upper.endswith(sfx):
                            currency = cur
                            break
                if not currency:
                    exch = (item.get("exchange") or "").upper()
                    currency = EXCHANGE_MAP.get(exch)

                if currency not in SUPPORTED_SEARCH_CURRENCIES:
                    continue

                seen.add(symbol)
                name = item.get("shortname") or item.get("longname") or symbol
                results.append({
                    "symbol": symbol,
                    "name": name,
                    "type": mapped_type,
                    "currency": currency,
                })

            if results:
                cache_service.set(cache_key, results, expire=3600)
            else:
                cache_service.set(cache_key, [], expire=300)
            return results
