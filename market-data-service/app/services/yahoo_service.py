import yfinance as yf
from fastapi import HTTPException

class YahooService:
    @staticmethod
    def get_current_price(symbol: str, target_currency: str = None) -> dict:
        try:
            stock = yf.Ticker(symbol)
            info = stock.fast_info

            price = info.get("last_price")
            if price is None:
                hist = stock.history(period="1d")
                if hist.empty:
                    raise HTTPException(status_code=404, detail=f"Symbol {symbol} not found")
                price = float(hist["Close"].iloc[-1])
            
            base_currency = (info.get("currency") or "USD").upper()

            if not target_currency or target_currency.upper() == base_currency:
                return {"price": float(price), "currency": base_currency}

            target_currency = target_currency.upper()
            rate_ticker = f"{base_currency}{target_currency}=X"
            rate_stock = yf.Ticker(rate_ticker)
            rate_info = rate_stock.fast_info
            rate = rate_info.get("last_price")
            if rate is None:
                rate_hist = rate_stock.history(period="1d")
                if rate_hist.empty:
                    raise HTTPException(status_code=400, detail=f"Cannot convert {base_currency} to {target_currency}")
                rate = float(rate_hist["Close"].iloc[-1])
            
            return {"price": float(price * rate), "currency": target_currency}
        except Exception as e:
            if isinstance(e, HTTPException):
                raise e
            raise HTTPException(status_code=400, detail=f"Error fetching data: {str(e)}")

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
                rate_ticker = f"{base_currency}{target_currency}=X"
                rate_stock = yf.Ticker(rate_ticker)
                rate_info = rate_stock.fast_info
                rate_val = rate_info.get("last_price")
                if rate_val is None:
                    rate_hist = rate_stock.history(period="1d")
                    if not rate_hist.empty:
                        rate_val = float(rate_hist["Close"].iloc[-1])
                if rate_val is not None:
                    rate = float(rate_val)
                    actual_currency = target_currency
            
            hist = hist.reset_index()
            date_col = "Date" if "Date" in hist.columns else ("Datetime" if "Datetime" in hist.columns else hist.columns[0])
            data = []
            for _, row in hist.iterrows():
                dt = row[date_col]
                data.append({
                    "date": dt.strftime("%Y-%m-%d") if hasattr(dt, "strftime") else str(dt),
                    "open": float(row["Open"] * rate),
                    "high": float(row["High"] * rate),
                    "low": float(row["Low"] * rate),
                    "close": float(row["Close"] * rate),
                    "volume": int(row["Volume"])
                })
            return {"history": data, "currency": actual_currency}
        except Exception as e:
            if isinstance(e, HTTPException):
                raise e
            raise HTTPException(status_code=400, detail=f"Error fetching historical data: {str(e)}")

