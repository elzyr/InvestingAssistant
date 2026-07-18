from fastapi import APIRouter, Depends
from app.services.yahoo_service import YahooService
from app.services.cache_service import CacheService

router = APIRouter(prefix="/api/v1/market/prices", tags=["Prices"])

def get_cache_service():
    return CacheService()

@router.get("/{symbol}")
def get_price(symbol: str, currency: str = None, cache: CacheService = Depends(get_cache_service)):
    symbol = symbol.upper()
    target_currency = currency.upper() if currency else None

    cache_key = f"price:{symbol}:{target_currency or 'base'}"
    
    cached_data = cache.get(cache_key)
    if cached_data is not None:
        return {"symbol": symbol, **cached_data, "source": "cache"}
        
    result = YahooService.get_current_price(symbol, target_currency)
    cache.set(cache_key, result, expire=300)
    
    return {"symbol": symbol, **result, "source": "yahoo_finance"}

@router.get("/{symbol}/history")
def get_history(
    symbol: str, 
    period: str = "1mo", 
    interval: str = "1d",
    currency: str = None,
    cache: CacheService = Depends(get_cache_service)
):
    symbol = symbol.upper()
    target_currency = currency.upper() if currency else None
    
    cache_key = f"history:{symbol}:{period}:{interval}:{target_currency or 'base'}"
    
    cached_data = cache.get(cache_key)
    if cached_data is not None:
        return {"symbol": symbol, **cached_data, "source": "cache"}
        
    result = YahooService.get_historical_data(symbol, period, interval, target_currency)
    cache.set(cache_key, result, expire=3600)
    
    return {"symbol": symbol, **result, "source": "yahoo_finance"}