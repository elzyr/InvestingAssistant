import atexit
from concurrent.futures import ThreadPoolExecutor, as_completed
import math
import re
import logging
from bisect import bisect_right
from datetime import date, datetime, timedelta
from typing import Any, Callable, Dict, Iterable, List, Optional, TypeVar
import numpy as np
import pandas as pd
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
import yfinance as yf
from fastapi import HTTPException
from app.services.cache_service import cache_service
from app.services.market_utils import validate_currency_code, warsaw_today


def finite_float(
    value: Any,
    default: Optional[float] = None,
    *,
    positive_only: bool = False,
) -> Optional[float]:
    if value is None or isinstance(value, bool):
        return default
    try:
        num = float(value)
    except (TypeError, ValueError):
        return default
    if not math.isfinite(num):
        return default
    if positive_only and num <= 0.0:
        return default
    return num


_shared_executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="mds-batch")
atexit.register(_shared_executor.shutdown, wait=False)

_K = TypeVar("_K")
_V = TypeVar("_V")


def run_batch(
    items: List[_K],
    fn: Callable[[_K], _V],
    *,
    error_label: Optional[str] = None,
    reraise: bool = False,
) -> Dict[_K, _V]:
    if not items:
        return {}
    if len(items) == 1:
        item = items[0]
        try:
            return {item: fn(item)}
        except Exception as e:
            if reraise:
                raise
            logging.warning(f"{error_label or 'Batch task'} failed for {item}: {e}")
            return {}

    results: Dict[_K, _V] = {}
    futures = {_shared_executor.submit(fn, item): item for item in items}
    for future in as_completed(futures):
        item = futures[future]
        try:
            results[item] = future.result()
        except Exception as e:
            if reraise:
                raise
            logging.warning(f"{error_label or 'Batch task'} failed for {item}: {e}")
    return results


def create_retry_session(pool_connections: int = 10, pool_maxsize: int = 20) -> requests.Session:
    session = requests.Session()
    adapter = HTTPAdapter(
        pool_connections=pool_connections,
        pool_maxsize=pool_maxsize,
        max_retries=Retry(total=2, backoff_factor=0.5),
    )
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session


_session = create_retry_session(pool_connections=10, pool_maxsize=20)

SUFFIX_MAP = {
    ".WA": "PLN",
    ".DE": "EUR",
    ".F": "EUR",
    ".PA": "EUR",
    ".AS": "EUR",
    ".MC": "EUR",
    ".MI": "EUR",
    ".SW": "CHF",
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


def infer_base_currency(symbol: str) -> Optional[str]:
    sym_upper = (symbol or "").upper()
    for suffix, currency in SUFFIX_MAP.items():
        if sym_upper.endswith(suffix):
            return currency
    return None


def normalize_currency_code(raw_currency: Optional[str]) -> Optional[str]:
    raw = (raw_currency or "").strip()
    if not raw:
        return None
    if raw == "GBp" or raw.upper() == "GBX":
        return "GBP"
    return raw.upper()


def normalize_quote_currency(raw_currency: Optional[str], symbol: str) -> tuple[str, float]:
    raw = (raw_currency or "").strip()
    if raw == "GBp" or raw.upper() == "GBX":
        return "GBP", 0.01
    if raw:
        return raw.upper(), 1.0
    inferred = infer_base_currency(symbol)
    if inferred:
        return inferred, 1.0
    raise HTTPException(status_code=503, detail=f"Instrument currency unavailable for {symbol}")


def extract_date_col(df: pd.DataFrame) -> str:
    if "Date" in df.columns:
        return "Date"
    if "Datetime" in df.columns:
        return "Datetime"
    return df.columns[0]


def _is_intraday_interval(interval: str) -> bool:
    return (interval or "").endswith(("m", "h"))


def _safe_fast_info(stock) -> object:
    try:
        return getattr(stock, "fast_info", None)
    except Exception:
        return None


def _read_fast_info_field(info: object, key: str):
    if info is None:
        return None
    try:
        if isinstance(info, dict):
            return info.get(key)
        return getattr(info, key, None)
    except Exception:
        return None


def download_clean_history(
    stock,
    *,
    period: Optional[str] = None,
    interval: Optional[str] = None,
    start: Optional[str] = None,
    end: Optional[str] = None,
    reset_index: bool = False,
) -> pd.DataFrame:
    kwargs: dict = {}
    if start is not None:
        kwargs["start"] = start
        if end is not None:
            kwargs["end"] = end
    elif period is not None:
        kwargs["period"] = period
    if interval is not None:
        kwargs["interval"] = interval

    hist = stock.history(**kwargs)
    if hist is None or hist.empty or "Close" not in hist.columns:
        return pd.DataFrame()
    clean = hist.dropna(subset=["Close"])
    clean = clean[np.isfinite(clean["Close"])]
    if reset_index and not clean.empty:
        clean = clean.reset_index()
    return clean


def _extract_fx_rate_map(fx_hist: pd.DataFrame) -> Dict[str, float]:
    if fx_hist.empty:
        return {}
    fx_dates = fx_hist.index
    fx_date_strs = (
        fx_dates.strftime("%Y-%m-%d")
        if hasattr(fx_dates, "strftime")
        else fx_dates.astype(str).str[:10]
    )
    out: Dict[str, float] = {}
    for d, c in zip(fx_date_strs, fx_hist["Close"].values):
        rate = finite_float(c, positive_only=True)
        if rate is not None:
            out[str(d)] = rate
    return out


def align_fx_rates(target_dates: Iterable[str], fx_rates: Dict[str, float]) -> Optional[List[float]]:
    if not fx_rates:
        return None
    sorted_dates = sorted(fx_rates.keys())
    if not sorted_dates:
        return None

    result: List[float] = []
    for raw_date in target_dates:
        d = str(raw_date)[:10]
        exact = fx_rates.get(d)
        if exact is not None and math.isfinite(exact) and exact > 0:
            result.append(float(exact))
            continue
        idx = bisect_right(sorted_dates, d) - 1
        if idx < 0:
            return None
        rate = fx_rates[sorted_dates[idx]]
        if not math.isfinite(rate) or rate <= 0:
            return None
        result.append(float(rate))
    return result


def _parse_iso_date(value: str) -> datetime:
    try:
        return datetime.strptime(value[:10], "%Y-%m-%d")
    except ValueError:
        raise HTTPException(status_code=400, detail=f"Invalid date format: {value}. Expected YYYY-MM-DD")


def _fetch_nbp_rate(from_cur: str, to_cur: str, clean_date: Optional[str] = None) -> tuple[Optional[float], Optional[str]]:
    if from_cur != "PLN" and to_cur != "PLN":
        return None, None
    foreign_code = to_cur if from_cur == "PLN" else from_cur
    try:
        if clean_date:
            target_dt = datetime.strptime(clean_date, "%Y-%m-%d")
            start_str = (target_dt - timedelta(days=14)).strftime("%Y-%m-%d")
            url = f"https://api.nbp.pl/api/exchangerates/rates/a/{foreign_code.lower()}/{start_str}/{clean_date}/?format=json"
        else:
            url = f"https://api.nbp.pl/api/exchangerates/rates/a/{foreign_code.lower()}/?format=json"
        resp = _session.get(url, timeout=5)
        if resp.status_code == 200:
            rates = resp.json().get("rates", [])
            if rates:
                latest = rates[-1]
                mid = finite_float(latest.get("mid"), positive_only=True)
                if mid is None:
                    return None, None
                eff_date = latest.get("effectiveDate")
                return float(1.0 / mid if from_cur == "PLN" else mid), str(eff_date)[:10] if eff_date else None
    except Exception as e:
        logging.warning(f"NBP rate fetch failed for {foreign_code} on {clean_date or 'spot'}: {e}")
    return None, None


def _fetch_strict_prior_nbp_in_pln(foreign_code: str, clean_date: str) -> tuple[float, str, str]:
    event_d = datetime.strptime(clean_date, "%Y-%m-%d").date()
    prev_end = (event_d - timedelta(days=1)).isoformat()
    prev_start = (event_d - timedelta(days=14)).isoformat()
    for table in ("a", "b"):
        url = (
            f"https://api.nbp.pl/api/exchangerates/rates/{table}/{foreign_code.lower()}/"
            f"{prev_start}/{prev_end}/?format=json"
        )
        try:
            resp = _session.get(url, timeout=5)
        except Exception as e:
            logging.warning(f"NBP table {table.upper()} tax-rate fetch failed for {foreign_code} before {clean_date}: {e}")
            continue
        if resp.status_code != 200:
            continue
        rates = resp.json().get("rates", []) or []
        valid = []
        for entry in rates:
            eff = str(entry.get("effectiveDate") or "")[:10]
            mid = finite_float(entry.get("mid"), positive_only=True)
            if eff and eff < clean_date and mid is not None:
                valid.append((eff, mid))
        if valid:
            valid.sort(key=lambda pair: pair[0])
            eff_date, mid = valid[-1]
            return float(mid), eff_date, table.upper()
    raise HTTPException(
        status_code=503,
        detail=f"NBP prior-day tax rate unavailable for {foreign_code} before {clean_date}",
    )


class YahooService:
    @staticmethod
    def get_exchange_rate(from_currency: str, to_currency: str, date_str: str = None) -> float:
        from_cur = validate_currency_code(from_currency)
        to_cur = validate_currency_code(to_currency)
        if not from_cur or not to_cur:
            raise HTTPException(status_code=400, detail="Currency code must be a 3-letter ISO code")

        today_d = warsaw_today()
        today_str = today_d.isoformat()

        clean_date = None
        if date_str:
            parsed_d = _parse_iso_date(date_str).date()
            if parsed_d > today_d:
                raise HTTPException(status_code=400, detail=f"Future FX date is not allowed: {parsed_d.isoformat()}")
            clean_date = parsed_d.isoformat()

        if from_cur == to_cur:
            return 1.0

        cache_key = f"rate:{from_cur}:{to_cur}:{clean_date}" if clean_date else f"rate:{from_cur}:{to_cur}"
        cache_ttl = 0

        def _build() -> Dict[str, float]:
            nonlocal cache_ttl

            rate, _nbp_effective_date = _fetch_nbp_rate(from_cur, to_cur, clean_date)
            is_spot_fallback = False

            if clean_date and rate is None:
                try:
                    ticker = f"{from_cur}{to_cur}=X"
                    target_dt = datetime.strptime(clean_date, "%Y-%m-%d")
                    end_dt = target_dt + timedelta(days=1)
                    start_dt = target_dt - timedelta(days=14)
                    hist = download_clean_history(
                        yf.Ticker(ticker),
                        start=start_dt.strftime("%Y-%m-%d"),
                        end=end_dt.strftime("%Y-%m-%d"),
                    )
                    if not hist.empty:
                        hist = hist[(hist.index.strftime("%Y-%m-%d") <= clean_date) & (hist["Close"] > 0)]
                        if not hist.empty:
                            rate = float(hist["Close"].iloc[-1])
                except Exception as e:
                    logging.warning(f"Yahoo historical FX rate fetch failed for {from_cur}->{to_cur} on {clean_date}: {e}")

            if rate is None:
                if clean_date and clean_date < today_str:
                    raise HTTPException(
                        status_code=503,
                        detail=f"Historical exchange rate {from_cur} to {to_cur} unavailable for {clean_date}",
                    )
                if clean_date:
                    is_spot_fallback = True
                ticker = f"{from_cur}{to_cur}=X"
                stock = yf.Ticker(ticker)
                info = _safe_fast_info(stock)
                price = finite_float(_read_fast_info_field(info, "last_price"), positive_only=True)
                if price is None:
                    hist = download_clean_history(stock, period="5d")
                    if not hist.empty:
                        hist = hist[hist["Close"] > 0]
                    if hist.empty:
                        raise HTTPException(status_code=404, detail=f"Exchange rate {from_currency} to {to_currency} not found")
                    rate = float(hist["Close"].iloc[-1])
                else:
                    rate = price

            if is_spot_fallback:
                cache_ttl = 0
            elif not clean_date:
                cache_ttl = 300
            elif clean_date < today_str:
                cache_ttl = 2592000
            else:
                cache_ttl = 900

            return {"rate": rate}

        cached_data = cache_service.get_or_set(
            cache_key,
            _build,
            ttl=lambda _: cache_ttl,
            validator=lambda c: isinstance(c, dict) and "rate" in c,
        )
        return float(cached_data["rate"])

    @staticmethod
    def get_nbp_tax_rate(from_currency: str, to_currency: str, event_date: str) -> Dict[str, Any]:
        from_cur = validate_currency_code(from_currency)
        to_cur = validate_currency_code(to_currency)
        if not from_cur or not to_cur:
            raise HTTPException(status_code=400, detail="Currency code must be a 3-letter ISO code")

        today_d = warsaw_today()
        event_d = _parse_iso_date(event_date).date()
        if event_d > today_d:
            raise HTTPException(status_code=400, detail=f"Future tax event date is not allowed: {event_d.isoformat()}")
        clean_date = event_d.isoformat()

        if from_cur == to_cur:
            return {
                "from": from_cur,
                "to": to_cur,
                "rate": 1.0,
                "effectiveDate": clean_date,
                "table": None,
                "source": "identity",
            }

        cache_key = f"nbp_tax:{from_cur}:{to_cur}:{clean_date}"
        cache_ttl = 2592000 if clean_date < today_d.isoformat() else 900

        def _build() -> Dict[str, Any]:
            if to_cur == "PLN":
                rate, eff_date, table = _fetch_strict_prior_nbp_in_pln(from_cur, clean_date)
            elif from_cur == "PLN":
                mid, eff_date, table = _fetch_strict_prior_nbp_in_pln(to_cur, clean_date)
                rate = float(1.0 / mid)
            else:
                mid_from, eff_from, tbl_from = _fetch_strict_prior_nbp_in_pln(from_cur, clean_date)
                mid_to, eff_to, tbl_to = _fetch_strict_prior_nbp_in_pln(to_cur, clean_date)
                rate = float(mid_from / mid_to)
                eff_date = min(eff_from, eff_to)
                table = f"{tbl_from}/{tbl_to}"
            return {
                "from": from_cur,
                "to": to_cur,
                "rate": rate,
                "effectiveDate": eff_date,
                "table": table,
                "source": "nbp",
            }

        return cache_service.get_or_set(
            cache_key,
            _build,
            ttl=lambda _: cache_ttl,
            validator=lambda c: isinstance(c, dict) and "rate" in c,
        )

    @staticmethod
    def get_historical_fx_rates(
        from_currency: str,
        to_currency: str,
        start_date: str,
        end_date: Optional[str] = None,
    ) -> Dict[str, float]:
        from_cur = validate_currency_code(from_currency)
        to_cur = validate_currency_code(to_currency)
        if not from_cur or not to_cur:
            raise HTTPException(status_code=400, detail="Currency code must be a 3-letter ISO code")
        today_d = warsaw_today()
        start_dt = _parse_iso_date(start_date)
        if start_dt.date() > today_d:
            raise HTTPException(status_code=400, detail=f"Future FX start_date is not allowed: {start_dt.date().isoformat()}")
        clean_start = start_dt.strftime("%Y-%m-%d")
        clean_end = ""
        end_bound = None
        if end_date:
            end_dt = _parse_iso_date(end_date)
            if end_dt.date() > today_d:
                raise HTTPException(status_code=400, detail=f"Future FX end_date is not allowed: {end_dt.date().isoformat()}")
            if end_dt < start_dt:
                raise HTTPException(status_code=400, detail="end_date must be greater than or equal to start_date")
            clean_end = end_dt.strftime("%Y-%m-%d")
            end_bound = (end_dt + timedelta(days=1)).strftime("%Y-%m-%d")
        if from_cur == to_cur:
            return {}

        padded_start = (start_dt - timedelta(days=10)).strftime("%Y-%m-%d")
        cache_key = f"fx_hist:{from_cur}:{to_cur}:{clean_start}:{clean_end}"

        def _build() -> Dict[str, float]:

            fx_ticker = f"{from_cur}{to_cur}=X"
            rates: Dict[str, float] = {}
            try:
                fx_hist = download_clean_history(
                    yf.Ticker(fx_ticker),
                    start=padded_start,
                    end=end_bound,
                    interval="1d",
                )
                rates = _extract_fx_rate_map(fx_hist)
            except Exception as e:
                logging.warning(f"Historical FX series fetch failed for {fx_ticker}: {e}")

            return rates

        cached = cache_service.get_or_set(
            cache_key,
            _build,
            ttl=lambda r: 3600 if r else None,
            validator=lambda c: isinstance(c, dict),
        )
        return {str(k): float(v) for k, v in cached.items()}

    @staticmethod
    def get_current_price(symbol: str) -> dict:
        try:
            stock = yf.Ticker(symbol)
            info = _safe_fast_info(stock)

            price = finite_float(_read_fast_info_field(info, "last_price"), positive_only=True)
            prev_close = finite_float(_read_fast_info_field(info, "previous_close"), positive_only=True)

            if price is None or prev_close is None:
                hist = download_clean_history(stock, period="5d")
                if not hist.empty:
                    hist = hist[hist["Close"] > 0]
                if hist.empty:
                    raise HTTPException(status_code=404, detail=f"Symbol {symbol} not found")
                if price is None:
                    price = float(hist["Close"].iloc[-1])
                if prev_close is None:
                    prev_close = float(hist["Close"].iloc[-2]) if len(hist) >= 2 else float(price)

            base_currency, price_scale = normalize_quote_currency(_read_fast_info_field(info, "currency"), symbol)
            price = float(price * price_scale)
            prev_close = float(prev_close * price_scale)
            change = float(price - prev_close)
            change_percent = float((change / prev_close) * 100) if prev_close else 0.0

            return {
                "price": round(price, 4),
                "previousClose": round(prev_close, 4),
                "change": round(change, 4),
                "changePercent": round(change_percent, 2),
                "currency": base_currency,
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
        start_date: Optional[str] = None,
    ) -> dict:
        try:
            stock = yf.Ticker(symbol)
            hist = download_clean_history(
                stock,
                period=period,
                interval=interval,
                start=start_date,
                reset_index=True,
            )
            if hist.empty:
                raise HTTPException(status_code=404, detail=f"No historical data for symbol {symbol}")

            info = _safe_fast_info(stock)
            base_currency, price_scale = normalize_quote_currency(_read_fast_info_field(info, "currency"), symbol)

            date_col = extract_date_col(hist)
            dates = hist[date_col]
            intraday = _is_intraday_interval(interval)
            date_fmt = "%Y-%m-%dT%H:%M:%S" if intraday else "%Y-%m-%d"
            if hasattr(dates, "dt") and hasattr(dates.dt, "strftime"):
                hist["date"] = dates.dt.strftime(date_fmt)
            else:
                hist["date"] = dates.astype(str).str[:19] if intraday else dates.astype(str).str[:10]

            close_series = (hist["Close"] * price_scale).round(4).astype(float)
            hist["close"] = close_series
            for out_col, src_col in (("open", "Open"), ("high", "High"), ("low", "Low")):
                if src_col in hist.columns:
                    col_vals = (hist[src_col] * price_scale).round(4).to_numpy(dtype=np.float64)
                    hist[out_col] = [float(v) if np.isfinite(v) else None for v in col_vals]
                else:
                    hist[out_col] = None

            if "Volume" in hist.columns:
                vol_vals = pd.to_numeric(hist["Volume"], errors="coerce")
                hist["volume"] = vol_vals.replace([np.inf, -np.inf], np.nan).fillna(0).astype(int)
            else:
                hist["volume"] = 0

            data = hist[["date", "open", "high", "low", "close", "volume"]].to_dict("records")
            for row in data:
                for ohlc_key in ("open", "high", "low"):
                    val = row.get(ohlc_key)
                    if val is not None and isinstance(val, float) and not math.isfinite(val):
                        row[ohlc_key] = None
            return {"history": data, "currency": base_currency}
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
        cache_ttl = 0

        def _build() -> List[Dict[str, str]]:
            nonlocal cache_ttl

            try:
                search_obj = yf.Search(clean_q)
                quotes = getattr(search_obj, "quotes", []) or []
            except Exception as e:
                logging.warning(f"Yahoo search failed for query '{clean_q}': {e}")
                cache_ttl = 0
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

                currency = normalize_currency_code(item.get("currency"))
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

            cache_ttl = 3600 if results else 300
            return results

        return cache_service.get_or_set(
            cache_key,
            _build,
            ttl=lambda _: cache_ttl,
            validator=lambda c: isinstance(c, list),
        )
