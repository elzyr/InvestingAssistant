import yfinance as yf
from fastapi import HTTPException
import logging
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from datetime import datetime, timedelta
from app.services.cache_service import cache_service

_session = requests.Session()
_adapter = HTTPAdapter(pool_connections=10, pool_maxsize=20, max_retries=Retry(total=2, backoff_factor=0.5))
_session.mount("https://", _adapter)
_session.mount("http://", _adapter)

class YahooService:
    @staticmethod
    def get_exchange_rate(from_currency: str, to_currency: str, date_str: str = None) -> float:
        from_cur = from_currency.upper()
        to_cur = to_currency.upper()
        if from_cur == to_cur:
            return 1.0

        cache_key = f"rate:{from_cur}:{to_cur}:{date_str}" if date_str else f"rate:{from_cur}:{to_cur}"
        cached_data = cache_service.get(cache_key)
        if cached_data is not None and "rate" in cached_data:
            return float(cached_data["rate"])

        rate = None
        if date_str:
            clean_date = date_str[:10]
            if from_cur == "PLN" or to_cur == "PLN":
                foreign_code = to_cur if from_cur == "PLN" else from_cur
                try:
                    target_dt = datetime.strptime(clean_date, "%Y-%m-%d")
                    start_dt = target_dt - timedelta(days=7)
                    start_str = start_dt.strftime("%Y-%m-%d")
                    url = f"https://api.nbp.pl/api/exchangerates/rates/a/{foreign_code.lower()}/{start_str}/{clean_date}/?format=json"
                    resp = _session.get(url, timeout=5)
                    if resp.status_code == 200:
                        data = resp.json()
                        rates = data.get("rates", [])
                        if rates:
                            mid = float(rates[-1]["mid"])
                            rate = float(1.0 / mid if from_cur == "PLN" else mid)
                except Exception as e:
                    logging.warning(f"NBP rate fetch failed for {foreign_code} on {clean_date}: {e}")

            if rate is None:
                try:
                    ticker = f"{from_cur}{to_cur}=X"
                    target_dt = datetime.strptime(clean_date, "%Y-%m-%d")
                    end_dt = target_dt + timedelta(days=2)
                    start_dt = target_dt - timedelta(days=5)
                    stock = yf.Ticker(ticker)
                    hist = stock.history(start=start_dt.strftime("%Y-%m-%d"), end=end_dt.strftime("%Y-%m-%d"))
                    if not hist.empty:
                        hist = hist.dropna(subset=["Close"])
                        if not hist.empty:
                            rate = float(hist["Close"].iloc[-1])
                except Exception as e:
                    logging.warning(f"Yahoo historical FX rate fetch failed for {from_cur}->{to_cur} on {clean_date}: {e}")

        if rate is None:
            ticker = f"{from_cur}{to_cur}=X"
            stock = yf.Ticker(ticker)
            info = stock.fast_info
            price = info.get("last_price")
            if price is None:
                hist = stock.history(period="1d")
                if hist.empty:
                    raise HTTPException(status_code=404, detail=f"Exchange rate {from_currency} to {to_currency} not found")
                rate = float(hist["Close"].iloc[-1])
            else:
                rate = float(price)

        expire = 86400 if date_str else 300
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
            raise HTTPException(status_code=400, detail="Error fetching data")

    @staticmethod
    def get_historical_data(symbol: str, period: str = "1mo", interval: str = "1d", target_currency: str = None) -> dict:
        try:
            stock = yf.Ticker(symbol)
            hist = stock.history(period=period, interval=interval)
            if hist.empty:
                raise HTTPException(status_code=404, detail=f"No historical data for symbol {symbol}")
            
            info = stock.fast_info
            base_currency = (info.get("currency") or "USD").upper()

            rate = 1.0
            actual_currency = base_currency
            if target_currency and target_currency.upper() != base_currency:
                target_currency = target_currency.upper()
                rate = YahooService.get_exchange_rate(base_currency, target_currency)
                actual_currency = target_currency
            
            hist = hist.reset_index().dropna(subset=["Close"])
            date_col = "Date" if "Date" in hist.columns else ("Datetime" if "Datetime" in hist.columns else hist.columns[0])

            close_series = (hist["Close"] * rate).round(4).astype(float)
            hist["close"] = close_series
            hist["open"] = (hist["Open"] * rate).round(4).fillna(close_series).astype(float) if "Open" in hist.columns else close_series
            hist["high"] = (hist["High"] * rate).round(4).fillna(close_series).astype(float) if "High" in hist.columns else close_series
            hist["low"] = (hist["Low"] * rate).round(4).fillna(close_series).astype(float) if "Low" in hist.columns else close_series
            hist["volume"] = hist["Volume"].fillna(0).astype(int) if "Volume" in hist.columns else 0

            dates = hist[date_col]
            if hasattr(dates.dt, "strftime"):
                hist["date"] = dates.dt.strftime("%Y-%m-%d")
            else:
                hist["date"] = dates.astype(str)

            data = hist[["date", "open", "high", "low", "close", "volume"]].to_dict("records")
            return {"history": data, "currency": actual_currency}
        except Exception as e:
            if isinstance(e, HTTPException):
                raise e
            logging.error(f"Error fetching historical data: {e}")
            raise HTTPException(status_code=400, detail="Error fetching historical data")
