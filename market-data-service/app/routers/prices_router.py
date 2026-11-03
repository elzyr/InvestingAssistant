import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Optional, List, Dict, Any
from pydantic import BaseModel
from fastapi import APIRouter, HTTPException, Path, Query
from app.services.cache_service import cache_service
from app.services.yahoo_service import YahooService
from app.services.market_history_repo import market_history_repo

router = APIRouter(prefix="/api/prices", tags=["Prices"])

class BatchPricesRequest(BaseModel):
    symbols: List[str]
    currency: Optional[str] = None

TREASURY_BOND_PREFIXES = ("EDO", "COI", "TOS", "ROR", "DOR", "OTS", "ROS", "ROD", "PL_BOND")

def _is_treasury_bond(symbol: str) -> bool:
    return any(symbol.startswith(prefix) for prefix in TREASURY_BOND_PREFIXES)

def _infer_base_currency(symbol: str) -> str:
    if symbol.endswith(".WA"):
        return "PLN"
    if any(symbol.endswith(s) for s in (".DE", ".F", ".PA", ".AS", ".MC", ".MI")):
        return "EUR"
    return "USD"

def _fetch_single_price(symbol: str, target_currency: Optional[str], raise_404: bool = False) -> Optional[dict]:
    symbol = symbol.upper()
    if _is_treasury_bond(symbol):
        return {
            "symbol": symbol,
            "price": 100.0,
            "previousClose": 100.0,
            "change": 0.0,
            "changePercent": 0.0,
            "currency": "PLN",
            "source": "treasury_bond",
        }

    cache_key = f"price:{symbol}:{target_currency or 'base'}"
    with cache_service.get_lock(cache_key):
        cached_data = cache_service.get(cache_key)
        if cached_data is not None:
            return {"symbol": symbol, **cached_data, "source": "cache"}

        try:
            result = YahooService.get_current_price(symbol, target_currency)
            cache_service.set(cache_key, result, expire=300)
            return {"symbol": symbol, **result, "source": "yahoo_finance"}
        except Exception as e:
            if isinstance(e, HTTPException) and e.status_code == 404:
                if raise_404:
                    raise e
                return None
            logging.error(f"Error fetching price: {e}")
            try:
                max_d = market_history_repo.get_max_date(symbol)
                if max_d:
                    p = market_history_repo.get_price_on_date(symbol, max_d)
                    if p is not None:
                        base_cur = _infer_base_currency(symbol)
                        out_price = p
                        out_cur = base_cur
                        if target_currency and target_currency != base_cur:
                            try:
                                fx = YahooService.get_exchange_rate(base_cur, target_currency)
                                if fx:
                                    out_price = round(p * fx, 4)
                                    out_cur = target_currency
                            except Exception:
                                pass
                        return {
                            "symbol": symbol,
                            "price": out_price,
                            "previousClose": out_price,
                            "change": 0.0,
                            "changePercent": 0.0,
                            "currency": out_cur,
                            "source": "db_fallback",
                        }
            except Exception:
                pass
    return None

@router.get("/search")
def search_instruments(query: str = Query(..., min_length=1)):
    return YahooService.search_instruments(query)

@router.post("/batch")
def get_batch_prices(req: BatchPricesRequest):
    target_currency = req.currency.upper() if req.currency else None
    symbols = [s.strip().upper() for s in req.symbols if s.strip()]
    if not symbols:
        return {}

    results: Dict[str, Any] = {}
    if len(symbols) == 1:
        price_data = _fetch_single_price(symbols[0], target_currency)
        if price_data is not None:
            results[symbols[0]] = price_data
        return results

    with ThreadPoolExecutor(max_workers=min(8, len(symbols))) as executor:
        futures = {executor.submit(_fetch_single_price, sym, target_currency): sym for sym in symbols}
        for future in as_completed(futures):
            sym = futures[future]
            try:
                data = future.result()
                if data is not None:
                    results[sym] = data
            except Exception as e:
                logging.warning(f"Error fetching price in batch for {sym}: {e}")
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
    period: str = "1mo",
    interval: str = "1d",
    currency: Optional[str] = Query(None, pattern=r"^[A-Za-z]{3}$"),
    start_date: Optional[str] = Query(None, pattern=r"^\d{4}-\d{2}-\d{2}$"),
):
    symbol = symbol.upper()
    target_currency = currency.upper() if isinstance(currency, str) else None

    if _is_treasury_bond(symbol):
        return {"symbol": symbol, "currency": "PLN", "history": [], "source": "treasury_bond"}

    cache_key = f"history:{symbol}:{period}:{interval}:{target_currency or 'base'}:{start_date or ''}"
    with cache_service.get_lock(cache_key):
        cached_data = cache_service.get(cache_key)
        if cached_data is not None:
            return {"symbol": symbol, **cached_data, "source": "cache"}

        try:
            if start_date:
                db_hist = market_history_repo.get_historical_prices(symbol, start_date=start_date)
                if db_hist:
                    base_cur = _infer_base_currency(symbol)
                    currency_code = target_currency or base_cur
                    return {"symbol": symbol, "currency": currency_code, "history": db_hist, "source": "database"}

            result = YahooService.get_historical_data(symbol, period, interval, target_currency)
            cache_service.set(cache_key, result, expire=3600)

            try:
                base_cur = result.get("currency")
                if result.get("history") and (not target_currency or target_currency == base_cur):
                    market_history_repo.save_eod_prices_bulk(symbol, result["history"], base_cur or "USD")
            except Exception as save_err:
                logging.warning(f"Failed to cache candles in DB for {symbol}: {save_err}")

            return {"symbol": symbol, **result, "source": "yahoo_finance"}
        except Exception as e:
            if isinstance(e, HTTPException) and e.status_code == 404:
                raise e
            logging.error(f"Error fetching history: {e}")
            try:
                db_hist = market_history_repo.get_historical_prices(symbol, start_date=start_date)
                if db_hist:
                    base_cur = _infer_base_currency(symbol)
                    currency_code = target_currency or base_cur
                    return {"symbol": symbol, "currency": currency_code, "history": db_hist, "source": "db_fallback"}
            except Exception:
                pass
            raise HTTPException(status_code=503, detail="Service Unavailable: market data provider failure")
