from fastapi import APIRouter, Depends, HTTPException
import logging
from app.services.cache_service import CacheService
from app.services.yahoo_service import YahooService
from app.dependencies import get_cache_service

router = APIRouter(prefix="/api/currencies", tags=["Currencies"])

@router.get("/rate")
def get_exchange_rate(
    from_currency: str, 
    to_currency: str, 
    cache: CacheService = Depends(get_cache_service)
):
    from_currency = from_currency.upper()
    to_currency = to_currency.upper()
    
    if from_currency == to_currency:
        return {"from": from_currency, "to": to_currency, "rate": 1.0}
        
    cache_key = f"rate:{from_currency}:{to_currency}"
    
    cached_data = cache.get(cache_key)
    if cached_data is not None:
        return {"from": from_currency, "to": to_currency, "rate": cached_data["rate"]}
        
    try:
        rate = YahooService.get_exchange_rate(from_currency, to_currency)
        result = {"rate": rate}
        cache.set(cache_key, result, expire=300)
        
        return {"from": from_currency, "to": to_currency, "rate": rate}
    except Exception as e:
        if isinstance(e, HTTPException):
            raise e
        logging.error(f"Error fetching exchange rate: {e}")
        raise HTTPException(status_code=400, detail="Error fetching exchange rate")
