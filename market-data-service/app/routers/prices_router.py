import datetime
import logging
from typing import Optional, List, Dict, Any, Literal
from pydantic import BaseModel, Field, field_validator
from fastapi import APIRouter, HTTPException, Path, Query
from app.services.cache_service import cache_service
from app.services.market_utils import SYMBOL_PATTERN, normalize_symbol_list, warsaw_today
from app.services.yahoo_service import YahooService, finite_float, normalize_quote_currency, run_batch
from app.services.market_history_repo import market_history_repo

router = APIRouter(prefix="/api/prices", tags=["Prices"])

ValidPeriod = Literal["1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "10y", "ytd", "max"]
ValidInterval = Literal["1m", "2m", "5m", "15m", "30m", "60m", "90m", "1h", "1d", "5d", "1wk", "1mo", "3mo"]


class BatchPricesRequest(BaseModel):
    symbols: List[str] = Field(default_factory=list, max_length=100)

    @field_validator("symbols")
    @classmethod
    def validate_symbols(cls, values: List[str]) -> List[str]:
        return normalize_symbol_list(values)


class BatchHistoryRequest(BaseModel):
    symbols: List[str] = Field(default_factory=list, max_length=100)
    period: ValidPeriod = "1y"
    interval: ValidInterval = "1d"
    start_date: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}-\d{2}$")

    @field_validator("symbols")
    @classmethod
    def validate_symbols(cls, values: List[str]) -> List[str]:
        return normalize_symbol_list(values)

    @field_validator("start_date")
    @classmethod
    def validate_start_date(cls, value: Optional[str]) -> Optional[str]:
        if value is not None:
            datetime.date.fromisoformat(value)
        return value


def _unavailable_quote(symbol: str) -> dict:
    return {
        "symbol": symbol,
        "price": None,
        "previousClose": None,
        "change": None,
        "changePercent": None,
        "currency": None,
        "source": "unavailable",
        "isUnavailable": True,
    }


def _build_flat_quote(
    symbol: str,
    base_price: float,
    base_cur: str,
    source: str,
) -> dict:
    out_price = round(base_price, 4)
    return {
        "symbol": symbol,
        "price": out_price,
        "previousClose": out_price,
        "change": 0.0,
        "changePercent": 0.0,
        "currency": base_cur,
        "source": source,
        "isUnavailable": False,
    }


def _build_db_price_fallback(symbol: str) -> Optional[dict]:
    try:
        max_d = market_history_repo.get_max_date(symbol)
        if not max_d:
            return None
        rows = market_history_repo.get_historical_prices(symbol, start_date=max_d, end_date=max_d)
        if not rows:
            return None
        p = finite_float(rows[-1].get("closePrice"), positive_only=True)
        if p is None:
            return None
        base_cur, price_scale = normalize_quote_currency(rows[-1].get("currency"), symbol)
        return _build_flat_quote(symbol, p * price_scale, base_cur, "db_fallback")
    except Exception as db_err:
        logging.warning(f"DB price fallback failed for {symbol}: {db_err}")
        return None


def _fetch_uncached_price(
    symbol: str,
    raise_404: bool = False,
    write_cache: bool = False,
) -> tuple[Optional[dict], Optional[dict]]:
    cache_key = f"price:{symbol}"
    cache_ttl = 0
    build_result: Dict[str, Optional[dict]] = {"payload": None, "cache_value": None}

    def _build() -> Optional[dict]:
        nonlocal cache_ttl
        try:
            result = YahooService.get_current_price(symbol)
            build_result["payload"] = {"symbol": symbol, **result, "source": "yahoo_finance", "isUnavailable": False}
            if write_cache:
                cache_ttl = 300
                return result
            build_result["cache_value"] = result
            return None
        except Exception as e:
            if isinstance(e, HTTPException) and e.status_code == 404:
                if raise_404:
                    raise e
                return None
            logging.error(f"Error fetching price for {symbol}: {e}")
            build_result["payload"] = _build_db_price_fallback(symbol)
            return None

    cached_data, hit = cache_service.get_or_set_with_hit(
        cache_key,
        _build,
        ttl=lambda _: cache_ttl,
        validator=lambda c: isinstance(c, dict),
    )
    if hit:
        return {"symbol": symbol, **cached_data, "source": "cache", "isUnavailable": False}, None
    return build_result["payload"], build_result["cache_value"]


def _fetch_single_price(symbol: str, raise_404: bool = False) -> Optional[dict]:
    payload, _ = _fetch_uncached_price(symbol.upper(), raise_404=raise_404, write_cache=True)
    return payload


def _format_db_history(
    db_hist: List[Dict[str, Any]],
    symbol: str,
) -> tuple[List[Dict[str, Any]], str]:
    base_cur, price_scale = normalize_quote_currency(db_hist[0].get("currency"), symbol)
    converted: List[Dict[str, Any]] = []
    for row in db_hist:
        close_raw = row.get("close") if row.get("close") is not None else row.get("closePrice")
        scaled_close = round(float(close_raw) * price_scale, 4) if close_raw is not None else None
        new_row = {
            "date": str(row.get("date", ""))[:10],
            "close": scaled_close,
            "closePrice": scaled_close,
            "volume": int(row.get("volume") or 0),
            "currency": base_cur,
        }
        for field in ("open", "high", "low"):
            raw_val = row.get(field)
            new_row[field] = round(float(raw_val) * price_scale, 4) if raw_val is not None else None
        converted.append(new_row)

    return converted, base_cur


def _last_expected_trading_day(today: datetime.date) -> datetime.date:
    candidate = today - datetime.timedelta(days=1)
    while candidate.weekday() >= 5:
        candidate -= datetime.timedelta(days=1)
    return candidate


def _is_db_history_complete(db_hist: List[Dict[str, Any]], start_date: str) -> bool:
    if not db_hist:
        return False
    try:
        req_start = datetime.date.fromisoformat(start_date[:10])
        dates = [datetime.date.fromisoformat(str(row["date"])[:10]) for row in db_hist]
    except ValueError:
        return False
    today = warsaw_today()
    if dates[0] > (req_start + datetime.timedelta(days=7)):
        return False
    min_fresh_date = _last_expected_trading_day(today) - datetime.timedelta(days=2)
    if dates[-1] < min_fresh_date:
        return False
    for prev_dt, cur_dt in zip(dates, dates[1:]):
        if (cur_dt - prev_dt).days > 10:
            return False
    return True


_PERIOD_DAYS = {
    "1d": 4,
    "5d": 7,
    "1mo": 31,
    "3mo": 93,
    "6mo": 186,
    "1y": 366,
    "2y": 731,
    "5y": 1827,
    "10y": 3653,
}


def _period_start_date(period: ValidPeriod) -> Optional[str]:
    if period == "max":
        return None
    today = warsaw_today()
    if period == "ytd":
        return datetime.date(today.year, 1, 1).isoformat()
    days = _PERIOD_DAYS.get(period)
    if days is None:
        return None
    return (today - datetime.timedelta(days=days)).isoformat()


@router.get("/search")
def search_instruments(query: str = Query(..., min_length=1, max_length=100)):
    return YahooService.search_instruments(query)


@router.post("/batch")
def get_batch_prices(req: BatchPricesRequest):
    symbols = req.symbols
    if not symbols:
        return {}

    results: Dict[str, Any] = {}
    key_by_symbol = {sym: f"price:{sym}" for sym in symbols}
    cached_by_key = cache_service.mget(key_by_symbol.values())

    misses: List[str] = []
    for sym in symbols:
        cached = cached_by_key.get(key_by_symbol[sym])
        if isinstance(cached, dict):
            results[sym] = {"symbol": sym, **cached, "source": "cache", "isUnavailable": False}
        else:
            misses.append(sym)

    if misses:
        to_cache: Dict[str, Any] = {}
        fetched = run_batch(
            misses,
            lambda sym: _fetch_uncached_price(sym),
            error_label="Batch price fetch",
        )
        for sym in misses:
            item = fetched.get(sym)
            if item and item[0] is not None:
                results[sym] = item[0]
            else:
                results[sym] = _unavailable_quote(sym)
            if item and item[1] is not None:
                to_cache[key_by_symbol[sym]] = item[1]

        if to_cache:
            cache_service.mset_pipeline(to_cache, expire=300)

    return results


@router.get("/{symbol}")
def get_price(
    symbol: str = Path(..., pattern=SYMBOL_PATTERN, max_length=20),
):
    symbol = symbol.upper()
    price_data = _fetch_single_price(symbol, raise_404=True)
    if price_data is not None:
        return price_data
    raise HTTPException(status_code=503, detail="Service Unavailable: market data provider failure")


def _fetch_symbol_history(
    symbol: str,
    period: ValidPeriod,
    interval: ValidInterval,
    start_date: Optional[str],
) -> dict:
    symbol = symbol.upper()
    range_key = f"start:{start_date}" if start_date else f"period:{period}"
    cache_key = f"history:{symbol}:{range_key}:{interval}"
    effective_start = start_date or _period_start_date(period)
    source = "yahoo_finance"
    should_cache = False

    def _build() -> dict:
        nonlocal source, should_cache
        db_hist: Optional[List[Dict[str, Any]]] = None
        try:
            if effective_start and interval == "1d":
                db_hist = market_history_repo.get_historical_prices(symbol, start_date=effective_start)
                if _is_db_history_complete(db_hist, effective_start):
                    formatted_hist, out_cur = _format_db_history(db_hist, symbol)
                    source = "database"
                    should_cache = True
                    return {"currency": out_cur, "history": formatted_hist}

            result = YahooService.get_historical_data(
                symbol,
                period=period,
                interval=interval,
                start_date=start_date,
            )
            should_cache = True

            if interval == "1d":
                try:
                    raw_cur = result.get("currency")
                    base_cur, _ = normalize_quote_currency(raw_cur, symbol)
                    if result.get("history"):
                        market_history_repo.save_eod_prices_bulk(symbol, result["history"], base_cur)
                except Exception as save_err:
                    logging.warning(f"Failed to cache candles in DB for {symbol}: {save_err}")

            return result
        except Exception as e:
            if isinstance(e, HTTPException) and e.status_code in (400, 404, 503):
                raise e
            logging.error(f"Error fetching history: {e}")
            if interval == "1d":
                try:
                    if db_hist is None:
                        db_hist = market_history_repo.get_historical_prices(symbol, start_date=effective_start)
                    if db_hist:
                        formatted_hist, out_cur = _format_db_history(db_hist, symbol)
                        source = "db_fallback"
                        return {"currency": out_cur, "history": formatted_hist}
                except HTTPException:
                    raise
                except Exception as db_err:
                    logging.warning(f"DB history fallback failed for {symbol}: {db_err}")
            raise HTTPException(status_code=503, detail="Service Unavailable: market data provider failure")

    cached_data, hit = cache_service.get_or_set_with_hit(
        cache_key,
        _build,
        ttl=lambda _: 3600 if should_cache else 0,
        validator=lambda c: isinstance(c, dict) and isinstance(c.get("history"), list),
    )
    return {"symbol": symbol, **cached_data, "source": "cache" if hit else source}


@router.post("/history/batch")
def get_batch_history(req: BatchHistoryRequest):
    symbols = req.symbols
    if not symbols:
        return {}
    return run_batch(
        symbols,
        lambda sym: _fetch_symbol_history(sym, req.period, req.interval, req.start_date),
        error_label="Batch history fetch",
    )
