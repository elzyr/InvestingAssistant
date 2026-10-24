import datetime
import logging
from typing import Dict, Any, List
import numpy as np
import yfinance as yf
from app.services.market_history_repo import market_history_repo

UCITS_FALLBACK_MAP = {
    "VWCE.DE": {"ter": 0.0022, "trailingPe": 21.0, "dividendYield": 0.015, "beta": 1.0},
    "VUAA.DE": {"ter": 0.0007, "trailingPe": 24.0, "dividendYield": 0.012, "beta": 1.05},
    "IUSQ.DE": {"ter": 0.0020, "trailingPe": 21.0, "dividendYield": 0.015, "beta": 1.0},
    "SXR8.DE": {"ter": 0.0007, "trailingPe": 24.0, "dividendYield": 0.012, "beta": 1.05},
    "EUNL.DE": {"ter": 0.0020, "trailingPe": 20.5, "dividendYield": 0.016, "beta": 1.0},
    "IS3N.DE": {"ter": 0.0018, "trailingPe": 14.0, "dividendYield": 0.024, "beta": 0.95},
}

def _safe_float(val: Any, default: float) -> float:
    try:
        return float(val) if val is not None else default
    except (ValueError, TypeError):
        return default

class AnalyticsService:
    @staticmethod
    def enrich_with_ucits_fallback(symbol: str, raw_info: Dict[str, Any]) -> Dict[str, float]:
        sym = symbol.upper()
        fallback = UCITS_FALLBACK_MAP.get(sym, {"ter": 0.0020, "trailingPe": 20.0, "dividendYield": 0.015, "beta": 1.0})

        raw_ter = raw_info.get("netExpenseRatio") or raw_info.get("annualReportExpenseRatio") or raw_info.get("expenseRatio")
        ter = _safe_float(raw_ter, fallback["ter"]) if raw_ter is not None else fallback["ter"]

        raw_pe = raw_info.get("trailingPE") or raw_info.get("forwardPE")
        trailing_pe = _safe_float(raw_pe, fallback["trailingPe"]) if raw_pe is not None else fallback["trailingPe"]

        raw_yield = raw_info.get("dividendYield") or raw_info.get("yield")
        dividend_yield = _safe_float(raw_yield, fallback["dividendYield"]) if raw_yield is not None else fallback["dividendYield"]
        if dividend_yield > 1.0:
            dividend_yield /= 100.0

        raw_beta = raw_info.get("beta") or raw_info.get("beta3Year")
        beta = _safe_float(raw_beta, fallback["beta"]) if raw_beta is not None else fallback["beta"]

        return {
            "ter": float(ter),
            "trailingPe": float(trailing_pe),
            "dividendYield": float(dividend_yield),
            "beta": float(beta),
        }

    @staticmethod
    def fetch_etf_market_metrics(symbol: str) -> Dict[str, Any]:
        sym = symbol.upper()
        today_str = datetime.date.today().isoformat()
        yesterday_str = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
        max_date = market_history_repo.get_max_date(sym)
        existing_profile = market_history_repo.get_profile_metrics(sym)
        profile = existing_profile

        already_updated_today = existing_profile and existing_profile.get("lastUpdated") == today_str
        needs_fetch = not max_date or (max_date < yesterday_str and not already_updated_today)

        if needs_fetch:
            try:
                stock = yf.Ticker(sym)
                hist = stock.history(start=max_date) if max_date else stock.history(period="5y")
                if not hist.empty:
                    hist = hist.reset_index().dropna(subset=["Close"])
                    date_col = "Date" if "Date" in hist.columns else ("Datetime" if "Datetime" in hist.columns else hist.columns[0])
                    currency = getattr(stock.fast_info, "currency", "USD") or "USD"

                    if max_date:
                        matching_row = hist[hist[date_col].astype(str).str[:10] == max_date]
                        if not matching_row.empty:
                            fetched_close = float(matching_row.iloc[0]["Close"])
                            stored_close = market_history_repo.get_price_on_date(sym, max_date)
                            if stored_close is not None and stored_close > 0 and abs(fetched_close - stored_close) / stored_close > 0.001:
                                hist = stock.history(period="5y")
                                if not hist.empty:
                                    hist = hist.reset_index().dropna(subset=["Close"])
                                    date_col = "Date" if "Date" in hist.columns else ("Datetime" if "Datetime" in hist.columns else hist.columns[0])
                                market_history_repo.delete_symbol_prices(sym)

                    records = [
                        {
                            "date": str(row[date_col])[:10],
                            "close_price": float(row["Close"]),
                            "currency": currency,
                            "volume": int(row.get("Volume", 0) or 0)
                        }
                        for _, row in hist.iterrows()
                    ]
                    market_history_repo.save_eod_prices_bulk(sym, records, currency)

                    info = {}
                    try:
                        info = stock.info or {}
                    except Exception:
                        pass

                    enriched = AnalyticsService.enrich_with_ucits_fallback(sym, info)
                    market_history_repo.save_profile_metrics(
                        symbol=sym,
                        ter=enriched["ter"],
                        trailing_pe=enriched["trailingPe"],
                        dividend_yield=enriched["dividendYield"],
                        beta=enriched["beta"],
                        last_updated=today_str,
                    )
                    profile = enriched
            except Exception as e:
                logging.warning(f"Failed to fetch market data from Yahoo for {sym}: {e}")

        if not profile:
            profile = AnalyticsService.enrich_with_ucits_fallback(sym, {})

        prices = market_history_repo.get_historical_prices(sym)
        if len(prices) >= 2:
            closes = np.array([p["closePrice"] for p in prices], dtype=np.float64)
            daily_returns = np.diff(closes) / closes[:-1]
            growth_factor = float(np.prod(1.0 + daily_returns))
            annual_return = float(growth_factor ** (252.0 / len(daily_returns)) - 1.0) if growth_factor > 0 else 0.0
            annual_volatility = float(np.std(daily_returns, ddof=1) * np.sqrt(252)) if len(daily_returns) > 1 else 0.15
            daily_returns_dict = {
                prices[i + 1]["date"]: float(daily_returns[i])
                for i in range(len(daily_returns))
            }
        else:
            annual_return = 0.08
            annual_volatility = 0.15
            daily_returns_dict = {}

        return {
            "symbol": sym,
            "annualReturn": annual_return,
            "annualVolatility": annual_volatility,
            "ter": profile["ter"],
            "trailingPe": profile["trailingPe"],
            "dividendYield": profile["dividendYield"],
            "beta": profile["beta"],
            "dailyReturns": daily_returns_dict,
        }

    @staticmethod
    def calculate_portfolio_metrics(holdings: List[Any], benchmark_symbol: str = "SPY") -> Dict[str, Any]:
        if not holdings:
            return {
                "weightedReturn": 0.0,
                "weightedVolatility": 0.0,
                "weightedTer": 0.0,
                "weightedPe": 0.0,
                "weightedDividendYield": 0.0,
                "portfolioBeta": 1.0,
                "instruments": [],
            }

        total_weight = sum(getattr(h, "weight", 0.0) or 0.0 for h in holdings)
        calc_weights = []
        if total_weight <= 0:
            total_val = sum(getattr(h, "value", 0.0) or 0.0 for h in holdings)
            if total_val > 0:
                calc_weights = [(getattr(h, "value", 0.0) or 0.0) / total_val for h in holdings]
            else:
                eq_w = 1.0 / len(holdings)
                calc_weights = [eq_w for _ in holdings]
            total_weight = 1.0
        else:
            calc_weights = [(getattr(h, "weight", 0.0) or 0.0) / total_weight for h in holdings]

        instruments_metrics = []
        normalized_weights = []
        daily_returns_series = []
        for i, h in enumerate(holdings):
            sym = h.symbol
            w = calc_weights[i]
            normalized_weights.append(w)
            m = AnalyticsService.fetch_etf_market_metrics(sym)
            daily_returns_series.append(m.get("dailyReturns"))
            instruments_metrics.append({
                "symbol": sym,
                "weight": round(w, 4),
                "value": getattr(h, "value", None),
                "annualReturn": m["annualReturn"],
                "annualVolatility": m["annualVolatility"],
                "ter": m["ter"],
                "trailingPe": m["trailingPe"],
                "dividendYield": m["dividendYield"],
                "beta": m["beta"],
            })

        w_vec = np.array(normalized_weights, dtype=np.float64)
        weighted_return = float(sum(w * m["annualReturn"] for w, m in zip(w_vec, instruments_metrics)))
        weighted_ter = float(sum(w * m["ter"] for w, m in zip(w_vec, instruments_metrics)))
        weighted_pe = float(sum(w * m["trailingPe"] for w, m in zip(w_vec, instruments_metrics)))
        weighted_dividend_yield = float(sum(w * m["dividendYield"] for w, m in zip(w_vec, instruments_metrics)))
        portfolio_beta = float(sum(w * m["beta"] for w, m in zip(w_vec, instruments_metrics)))

        valid_series = [s for s in daily_returns_series if s]
        if len(valid_series) == len(instruments_metrics) and len(valid_series) > 0:
            if all(isinstance(s, dict) for s in valid_series):
                common_dates = sorted(set.intersection(*(set(s.keys()) for s in valid_series)))
                if len(common_dates) > 1:
                    aligned_returns = np.column_stack([
                        np.array([s[d] for d in common_dates], dtype=np.float64)
                        for s in valid_series
                    ])
                    port_daily = np.dot(aligned_returns, w_vec)
                    weighted_volatility = float(np.std(port_daily, ddof=1) * np.sqrt(252))
                else:
                    weighted_volatility = float(sum(w * m["annualVolatility"] for w, m in zip(w_vec, instruments_metrics)))
            elif all(isinstance(s, list) for s in valid_series):
                min_len = min(len(s) for s in valid_series)
                if min_len > 1:
                    aligned_returns = np.column_stack([np.array(s[-min_len:], dtype=np.float64) for s in valid_series])
                    port_daily = np.dot(aligned_returns, w_vec)
                    weighted_volatility = float(np.std(port_daily, ddof=1) * np.sqrt(252))
                else:
                    weighted_volatility = float(sum(w * m["annualVolatility"] for w, m in zip(w_vec, instruments_metrics)))
            else:
                weighted_volatility = float(sum(w * m["annualVolatility"] for w, m in zip(w_vec, instruments_metrics)))
        else:
            weighted_volatility = float(sum(w * m["annualVolatility"] for w, m in zip(w_vec, instruments_metrics)))

        return {
            "weightedReturn": round(weighted_return, 4),
            "weightedVolatility": round(weighted_volatility, 4),
            "weightedTer": round(weighted_ter, 4),
            "weightedPe": round(weighted_pe, 2),
            "weightedDividendYield": round(weighted_dividend_yield, 4),
            "portfolioBeta": round(portfolio_beta, 2),
            "instruments": instruments_metrics,
        }
