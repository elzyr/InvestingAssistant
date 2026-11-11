import atexit
import datetime
import logging
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Optional, List, Dict, Any, Literal
from pydantic import BaseModel, Field, field_validator
from fastapi import APIRouter, HTTPException, Path, Query
from app.services.cache_service import cache_service
from app.services.yahoo_service import YahooService, infer_base_currency
from app.services.market_history_repo import market_history_repo

router = APIRouter(prefix="/api/prices", tags=["Prices"])

_SYMBOL_RE = re.compile(r"^[A-Za-z0-9.\-^=_]{1,20}$")
TREASURY_BOND_RE = re.compile(r"^(EDO|COI|ROS|ROD|TOS|ROR|DOR|OTS)\d{4}$", re.IGNORECASE)

ValidPeriod = Literal["1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "10y", "ytd", "max"]
ValidInterval = Literal["1m", "2m", "5m", "15m", "30m", "60m", "90m", "1h", "1d", "5d", "1wk", "1mo", "3mo"]

_batch_executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="price-batch")
atexit.register(_batch_executor.shutdown, wait=False)


class BatchPricesRequest(BaseModel):
    symbols: List[str] = Field(default_factory=list, max_length=100)
    currency: Optional[str] = Field(None, pattern=r"^[A-Za-z]{3}$")

    @field_validator("symbols")
    @classmethod
    def validate_symbols(cls, values: List[str]) -> List[str]:
        cleaned: List[str] = []
        for raw in values:
            sym = (raw or "").strip().upper()
            if not sym:
                continue
            if not _SYMBOL_RE.match(sym):
                raise ValueError(f"Invalid symbol format: {raw}")
            cleaned.append(sym)
        return list(dict.fromkeys(cleaned))


def _is_treasury_bond(symbol: str) -> bool:
    return bool(TREASURY_BOND_RE.match(symbol or ""))


def _treasury_bond_quote(symbol: str, target_currency: Optional[str]) -> dict:
    out_price = 100.0
    out_cur = "PLN"
    if target_currency and target_currency != "PLN":
        try:
            rate = YahooService.get_exchange_rate("PLN", target_currency)
            out_price = round(100.0 * rate, 4)
            out_cur = target_currency
        except Exception as e:
            logging.warning(f"FX conversion failed for treasury bond {symbol} PLN->{target_currency}: {e}")
    return {
        "symbol": symbol,
        "price": out_price,
        "previousClose": out_price,
        "change": 0.0,
        "changePercent": 0.0,
        "currency": out_cur,
        "source": "treasury_bond",
    }


def _build_db_price_fallback(symbol: str, target_currency: Optional[str]) -> Optional[dict]:
    try:
        max_d = market_history_repo.get_max_date(symbol)
        if not max_d:
            return None
        rows = market_history_repo.get_historical_prices(symbol, start_date=max_d, end_date=max_d)
        if rows:
            p = rows[-1]["closePrice"]
            base_cur = (rows[-1].get("currency") or infer_base_currency(symbol)).upper()
        else:
            p = market_history_repo.get_price_on_date(symbol, max_d)
            base_cur = infer_base_currency(symbol)
        if p is None:
            return None
        out_price = p
        out_cur = base_cur
        if target_currency and target_currency != base_cur:
            try:
                fx = YahooService.get_exchange_rate(base_cur, target_currency)
                if fx:
                    out_price = round(p * fx, 4)
                    out_cur = target_currency
            except Exception as fx_err:
                logging.warning(f"DB fallback FX conversion failed for {symbol} {base_cur}->{target_currency}: {fx_err}")
        return {
            "symbol": symbol,
            "price": out_price,
            "previousClose": out_price,
            "change": 0.0,
            "changePercent": 0.0,
            "currency": out_cur,
            "source": "db_fallback",
        }
    except Exception as db_err:
        logging.warning(f"DB price fallback failed for {symbol}: {db_err}")
        return None


def _fetch_uncached_price(
    symbol: str,
    target_currency: Optional[str],
    raise_404: bool = False,
) -> tuple[Optional[dict], Optional[dict]]:
    cache_key = f"price:{symbol}:{target_currency or 'base'}"
    with cache_service.get_lock(cache_key):
        cached_data = cache_service.get(cache_key)
        if cached_data is not None:
            return {"symbol": symbol, **cached_data, "source": "cache"}, None

        try:
            result = YahooService.get_current_price(symbol, target_currency)
            return {"symbol": symbol, **result, "source": "yahoo_finance"}, result
        except Exception as e:
            if isinstance(e, HTTPException) and e.status_code == 404:
                if raise_404:
                    raise e
                return None, None
            logging.error(f"Error fetching price for {symbol}: {e}")
            return _build_db_price_fallback(symbol, target_currency), None


def _fetch_single_price(symbol: str, target_currency: Optional[str], raise_404: bool = False) -> Optional[dict]:
    symbol = symbol.upper()
    if _is_treasury_bond(symbol):
        return _treasury_bond_quote(symbol, target_currency)

    payload, cache_value = _fetch_uncached_price(symbol, target_currency, raise_404=raise_404)
    if cache_value is not None:
        cache_key = f"price:{symbol}:{target_currency or 'base'}"
        cache_service.set(cache_key, cache_value, expire=300)
    return payload


def _convert_db_history_currency(
    db_hist: List[Dict[str, Any]],
    symbol: str,
    target_currency: Optional[str],
) -> tuple[List[Dict[str, Any]], str]:
    base_cur = (db_hist[0].get("currency") or infer_base_currency(symbol)).upper()
    if not target_currency or target_currency == base_cur:
        return db_hist, base_cur

    start_d = db_hist[0]["date"][:10]
    end_d = db_hist[-1]["date"][:10]
    fx_rates = YahooService.get_historical_fx_rates(base_cur, target_currency, start_d, end_d)
    sorted_fx_dates = sorted(fx_rates.keys())

    spot_rate: Optional[float] = None
    if not sorted_fx_dates:
        try:
            spot_rate = YahooService.get_exchange_rate(base_cur, target_currency)
        except Exception as e:
            logging.warning(f"History DB currency conversion failed for {symbol} {base_cur}->{target_currency}: {e}")
            return db_hist, base_cur

    converted: List[Dict[str, Any]] = []
    last_rate = fx_rates[sorted_fx_dates[0]] if sorted_fx_dates else spot_rate
    fx_idx = 0

    for row in db_hist:
        row_date = str(row.get("date", ""))[:10]
        if sorted_fx_dates:
            while fx_idx + 1 < len(sorted_fx_dates) and sorted_fx_dates[fx_idx + 1] <= row_date:
                fx_idx += 1
            rate = fx_rates.get(row_date)
            if rate is None:
                rate = fx_rates[sorted_fx_dates[fx_idx]] if sorted_fx_dates[fx_idx] <= row_date else last_rate
            last_rate = rate
        else:
            rate = spot_rate

        new_row = dict(row)
        for field in ("closePrice", "close", "open", "high", "low"):
            if field in new_row and new_row[field] is not None:
                new_row[field] = round(float(new_row[field]) * rate, 4)
        new_row["currency"] = target_currency
        converted.append(new_row)

    return converted, target_currency


def _is_db_history_complete(db_hist: List[Dict[str, Any]], start_date: str) -> bool:
    if not db_hist:
        return False
    try:
        req_start = datetime.date.fromisoformat(start_date[:10])
        db_first = datetime.date.fromisoformat(str(db_hist[0]["date"])[:10])
        db_last = datetime.date.fromisoformat(str(db_hist[-1]["date"])[:10])
    except ValueError:
        return False
    today = datetime.date.today()
    start_covered = db_first <= (req_start + datetime.timedelta(days=7))
    end_covered = db_last >= (today - datetime.timedelta(days=4))
    return start_covered and end_covered


@router.get("/search")
def search_instruments(query: str = Query(..., min_length=1)):
    return YahooService.search_instruments(query)


@router.post("/batch")
def get_batch_prices(req: BatchPricesRequest):
    target_currency = req.currency.upper() if req.currency else None
    symbols = req.symbols
    if not symbols:
        return {}

    results: Dict[str, Any] = {}
    market_symbols: List[str] = []
    for sym in symbols:
        if _is_treasury_bond(sym):
            results[sym] = _treasury_bond_quote(sym, target_currency)
        else:
            market_symbols.append(sym)

    if not market_symbols:
        return results

    key_by_symbol = {sym: f"price:{sym}:{target_currency or 'base'}" for sym in market_symbols}
    cached_by_key = cache_service.mget(key_by_symbol.values())

    misses: List[str] = []
    for sym in market_symbols:
        cached = cached_by_key.get(key_by_symbol[sym])
        if cached is not None:
            results[sym] = {"symbol": sym, **cached, "source": "cache"}
        else:
            misses.append(sym)

    if not misses:
        return results

    to_cache: Dict[str, Any] = {}
    if len(misses) == 1:
        sym = misses[0]
        payload, cache_value = _fetch_uncached_price(sym, target_currency)
        if payload is not None:
            results[sym] = payload
        if cache_value is not None:
            to_cache[key_by_symbol[sym]] = cache_value
    else:
        futures = {
            _batch_executor.submit(_fetch_uncached_price, sym, target_currency): sym
            for sym in misses
        }
        for future in as_completed(futures):
            sym = futures[future]
            try:
                payload, cache_value = future.result()
                if payload is not None:
                    results[sym] = payload
                if cache_value is not None:
                    to_cache[key_by_symbol[sym]] = cache_value
            except Exception as e:
                logging.warning(f"Error fetching price in batch for {sym}: {e}")

    if to_cache:
        cache_service.mset_pipeline(to_cache, expire=300)

    return results


@router.get("/{symbol}")
def get_price(
    symbol: str = Path(..., pattern=r"^[A-Za-z0-9.\-^=_]+$", max_length=20),
    currency: Optional[str] = Query(None, pattern=r"^[A-Za-z]{3}$"),
):
    symbol = symbol.upper()
    target_currency = currency.upper() if isinstance(currency, str) else None
    price_data = _fetch_single_price(symbol, target_currency, raise_404=True)
    if price_data is not None:
        return price_data
    raise HTTPException(status_code=503, detail="Service Unavailable: market data provider failure")


@router.get("/{symbol}/history")
def get_history(
    symbol: str = Path(..., pattern=r"^[A-Za-z0-9.\-^=_]+$", max_length=20),
    period: ValidPeriod = Query("1mo"),
    interval: ValidInterval = Query("1d"),
    currency: Optional[str] = Query(None, pattern=r"^[A-Za-z]{3}$"),
    start_date: Optional[str] = Query(None, pattern=r"^\d{4}-\d{2}-\d{2}$"),
):
    symbol = symbol.upper()
    target_currency = currency.upper() if isinstance(currency, str) else None

    if _is_treasury_bond(symbol):
        return {
            "symbol": symbol,
            "currency": target_currency or "PLN",
            "history": [],
            "source": "treasury_bond",
        }

    cache_key = f"history:{symbol}:{period}:{interval}:{target_currency or 'base'}:{start_date or ''}"
    with cache_service.get_lock(cache_key):
        cached_data = cache_service.get(cache_key)
        if cached_data is not None:
            return {"symbol": symbol, **cached_data, "source": "cache"}

        try:
            if start_date and interval == "1d":
                db_hist = market_history_repo.get_historical_prices(symbol, start_date=start_date)
                if _is_db_history_complete(db_hist, start_date):
                    converted_hist, out_cur = _convert_db_history_currency(db_hist, symbol, target_currency)
                    payload = {"currency": out_cur, "history": converted_hist}
                    cache_service.set(cache_key, payload, expire=3600)
                    return {"symbol": symbol, **payload, "source": "database"}

            result = YahooService.get_historical_data(
                symbol,
                period=period,
                interval=interval,
                target_currency=target_currency,
                start_date=start_date,
            )
            cache_service.set(cache_key, result, expire=3600)

            if interval == "1d":
                try:
                    base_cur = result.get("currency")
                    if result.get("history") and (not target_currency or target_currency == base_cur):
                        market_history_repo.save_eod_prices_bulk(symbol, result["history"], base_cur or infer_base_currency(symbol))
                except Exception as save_err:
                    logging.warning(f"Failed to cache candles in DB for {symbol}: {save_err}")

            return {"symbol": symbol, **result, "source": "yahoo_finance"}
        except Exception as e:
            if isinstance(e, HTTPException) and e.status_code == 404:
                raise e
            logging.error(f"Error fetching history: {e}")
            if interval == "1d":
                try:
                    db_hist = market_history_repo.get_historical_prices(symbol, start_date=start_date)
                    if db_hist:
                        converted_hist, out_cur = _convert_db_history_currency(db_hist, symbol, target_currency)
                        return {"symbol": symbol, "currency": out_cur, "history": converted_hist, "source": "db_fallback"}
                except Exception as db_err:
                    logging.warning(f"DB history fallback failed for {symbol}: {db_err}")
            raise HTTPException(status_code=503, detail="Service Unavailable: market data provider failure")
