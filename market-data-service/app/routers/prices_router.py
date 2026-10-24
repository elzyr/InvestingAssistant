import logging
from typing import Optional
from fastapi import APIRouter, HTTPException, Path, Query
from app.services.cache_service import cache_service
from app.services.yahoo_service import YahooService

router = APIRouter(prefix="/api/prices", tags=["Prices"])

@router.get("/{symbol}")
def get_price(
    symbol: str = Path(..., pattern=r"^[A-Za-z0-9.\-^=_]+$", max_length=20),
    currency: Optional[str] = Query(None, pattern=r"^[A-Za-z]{3}$"),
):
    symbol = symbol.upper()
    target_currency = currency.upper() if currency else None

    if symbol.startswith("PL_BOND"):
        return {"symbol": symbol, "price": 100.0, "previousClose": 100.0, "change": 0.0, "changePercent": 0.0, "currency": "PLN", "source": "treasury_bond"}

    try:
        cache_key = f"price:{symbol}:{target_currency or 'base'}"

        cached_data = cache_service.get(cache_key)
        if cached_data is not None:
            return {"symbol": symbol, **cached_data, "source": "cache"}

        result = YahooService.get_current_price(symbol, target_currency)
        cache_service.set(cache_key, result, expire=300)

        return {"symbol": symbol, **result, "source": "yahoo_finance"}
    except Exception as e:
        if isinstance(e, HTTPException):
            raise e
        logging.error(f"Error fetching price: {e}")
        raise HTTPException(status_code=400, detail="Error fetching price")

@router.get("/{symbol}/history")
def get_history(
    symbol: str = Path(..., pattern=r"^[A-Za-z0-9.\-^=_]+$", max_length=20),
    period: str = "1mo",
    interval: str = "1d",
    currency: Optional[str] = Query(None, pattern=r"^[A-Za-z]{3}$"),
):
    symbol = symbol.upper()
    target_currency = currency.upper() if currency else None

    if symbol.startswith("PL_BOND"):
        return {"symbol": symbol, "currency": "PLN", "history": [], "source": "treasury_bond"}

    try:
        cache_key = f"history:{symbol}:{period}:{interval}:{target_currency or 'base'}"

        cached_data = cache_service.get(cache_key)
        if cached_data is not None:
            return {"symbol": symbol, **cached_data, "source": "cache"}

        result = YahooService.get_historical_data(symbol, period, interval, target_currency)
        cache_service.set(cache_key, result, expire=3600)

        return {"symbol": symbol, **result, "source": "yahoo_finance"}
    except Exception as e:
        if isinstance(e, HTTPException):
            raise e
        logging.error(f"Error fetching history: {e}")
        raise HTTPException(status_code=400, detail="Error fetching history")
