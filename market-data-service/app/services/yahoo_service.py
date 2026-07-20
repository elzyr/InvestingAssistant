import yfinance as yf
from fastapi import HTTPException
import logging

class YahooService:
    @staticmethod
    def get_exchange_rate(from_currency: str, to_currency: str) -> float:
        if from_currency.upper() == to_currency.upper():
            return 1.0
        
        ticker = f"{from_currency.upper()}{to_currency.upper()}=X"
        stock = yf.Ticker(ticker)
        info = stock.fast_info
        rate = info.get("last_price")
        if rate is None:
            hist = stock.history(period="1d")
            if hist.empty:
                raise HTTPException(status_code=404, detail=f"Exchange rate {from_currency} to {to_currency} not found")
            rate = float(hist["Close"].iloc[-1])
        return float(rate)

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
            rate = YahooService.get_exchange_rate(base_currency, target_currency)
            
            return {"price": float(price * rate), "currency": target_currency}
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
            logging.error(f"Error fetching historical data: {e}")
            raise HTTPException(status_code=400, detail="Error fetching historical data")

