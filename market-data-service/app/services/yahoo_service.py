import re
import logging
from datetime import datetime, timedelta
from typing import Optional
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
                price = getattr(info, "last_price", None) if hasattr(info, "last_price") else (info.get("last_price") if isinstance(info, dict) else None)
                if price is None:
                    hist = stock.history(period="1d")
                    if hist.empty:
                        hist = stock.history(period="5d")
                    if hist.empty:
                        raise HTTPException(status_code=404, detail=f"Exchange rate {from_currency} to {to_currency} not found")
                    rate = float(hist["Close"].iloc[-1])
                else:
                    rate = float(price)

            if not is_spot_fallback:
                expire = 2592000 if clean_date else 300
                cache_service.set(cache_key, {"rate": rate}, expire=expire)

            return rate

    @staticmethod
    def get_current_price(symbol: str, target_currency: str = None) -> dict:
        try:
            stock = yf.Ticker(symbol)
            info = stock.fast_info

            price = getattr(info, "last_price", None)
            prev_close = getattr(info, "previous_close", None)

            if price is None or prev_close is None:
                hist = stock.history(period="5d")
                if hist.empty:
                    raise HTTPException(status_code=404, detail=f"Symbol {symbol} not found")
                if price is None:
                    price = float(hist["Close"].iloc[-1])
                if prev_close is None:
                    prev_close = float(hist["Close"].iloc[-2]) if len(hist) >= 2 else float(price)

            price = float(price)
            prev_close = float(prev_close)
            change = float(price - prev_close)
            change_percent = float((change / prev_close) * 100) if prev_close else 0.0

            base_currency = (getattr(info, "currency", None) or "USD").upper()

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
    def get_historical_data(symbol: str, period: str = "1mo", interval: str = "1d", target_currency: str = None) -> dict:
        try:
            stock = yf.Ticker(symbol)
            hist = stock.history(period=period, interval=interval)
            if hist.empty:
                raise HTTPException(status_code=404, detail=f"No historical data for symbol {symbol}")

            info = stock.fast_info
            base_currency = (getattr(info, "currency", None) or "USD").upper()

            hist = hist.reset_index().dropna(subset=["Close"])
            date_col = "Date" if "Date" in hist.columns else ("Datetime" if "Datetime" in hist.columns else hist.columns[0])
            dates = hist[date_col]
            if hasattr(dates, "dt") and hasattr(dates.dt, "strftime"):
                hist["date"] = dates.dt.strftime("%Y-%m-%d")
            else:
                hist["date"] = dates.astype(str).str[:10]

            actual_currency = base_currency
            rate_multiplier = 1.0

            if target_currency and target_currency.upper() != base_currency:
                target_cur = target_currency.upper()
                fx_ticker = f"{base_currency}{target_cur}=X"
                try:
                    fx_stock = yf.Ticker(fx_ticker)
                    fx_hist = fx_stock.history(period=period, interval=interval)
                    if not fx_hist.empty and "Close" in fx_hist.columns:
                        fx_hist = fx_hist.dropna(subset=["Close"])
                        fx_dates = fx_hist.index
                        if hasattr(fx_dates, "strftime"):
                            fx_date_str = fx_dates.strftime("%Y-%m-%d")
                        else:
                            fx_date_str = fx_dates.astype(str).str[:10]
                        fx_df = pd.DataFrame({"fx_date": fx_date_str, "fx_rate": fx_hist["Close"].values})
                        merged = hist[["date"]].merge(fx_df, left_on="date", right_on="fx_date", how="left")
                        merged["fx_rate"] = merged["fx_rate"].ffill().bfill()
                        if merged["fx_rate"].notna().all():
                            rate_multiplier = merged["fx_rate"].values
                            actual_currency = target_cur
                except Exception as e:
                    logging.warning(f"Vector FX fetch failed for {fx_ticker}: {e}")

            close_series = (hist["Close"] * rate_multiplier).round(4).astype(float)
            hist["close"] = close_series
            hist["open"] = (hist["Open"] * rate_multiplier).round(4).fillna(close_series).astype(float) if "Open" in hist.columns else close_series
            hist["high"] = (hist["High"] * rate_multiplier).round(4).fillna(close_series).astype(float) if "High" in hist.columns else close_series
            hist["low"] = (hist["Low"] * rate_multiplier).round(4).fillna(close_series).astype(float) if "Low" in hist.columns else close_series
            hist["volume"] = hist["Volume"].fillna(0).astype(int) if "Volume" in hist.columns else 0

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
