import datetime
import logging
from typing import Dict, Any, List, Optional
import numpy as np
import yfinance as yf
from app.services.market_history_repo import market_history_repo

POLISH_BELKA_TAX_RATE = 0.19

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

        # Skip Yahoo fetch on weekends or if already refreshed today
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
            except Exception as e:
                logging.warning(f"Failed to fetch market data from Yahoo for {sym}: {e}")

        profile = market_history_repo.get_profile_metrics(sym)
        if not profile:
            profile = AnalyticsService.enrich_with_ucits_fallback(sym, {})

        prices = market_history_repo.get_historical_prices(sym)
        if len(prices) >= 2:
            closes = np.array([p["closePrice"] for p in prices], dtype=np.float64)
            daily_returns = np.diff(closes) / closes[:-1]
            annual_return = float(np.mean(daily_returns) * 252)
            annual_volatility = float(np.std(daily_returns, ddof=1) * np.sqrt(252)) if len(daily_returns) > 1 else 0.15
            daily_returns_list = daily_returns.tolist()
        else:
            annual_return = 0.08
            annual_volatility = 0.15
            daily_returns_list = []

        return {
            "symbol": sym,
            "annualReturn": annual_return,
            "annualVolatility": annual_volatility,
            "ter": profile["ter"],
            "trailingPe": profile["trailingPe"],
            "dividendYield": profile["dividendYield"],
            "beta": profile["beta"],
            "dailyReturns": daily_returns_list,
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
        for i, h in enumerate(holdings):
            sym = h.symbol
            w = calc_weights[i]
            normalized_weights.append(w)
            m = AnalyticsService.fetch_etf_market_metrics(sym)
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
                "dailyReturns": m.get("dailyReturns", []),
            })

        w_vec = np.array(normalized_weights, dtype=np.float64)
        weighted_return = float(sum(w * m["annualReturn"] for w, m in zip(w_vec, instruments_metrics)))
        weighted_ter = float(sum(w * m["ter"] for w, m in zip(w_vec, instruments_metrics)))
        weighted_pe = float(sum(w * m["trailingPe"] for w, m in zip(w_vec, instruments_metrics)))
        weighted_dividend_yield = float(sum(w * m["dividendYield"] for w, m in zip(w_vec, instruments_metrics)))
        portfolio_beta = float(sum(w * m["beta"] for w, m in zip(w_vec, instruments_metrics)))

        return_series = [m["dailyReturns"] for m in instruments_metrics if len(m.get("dailyReturns", [])) > 1]
        if len(return_series) == len(instruments_metrics) and len(return_series) > 0:
            min_len = min(len(s) for s in return_series)
            if min_len > 1:
                aligned_returns = np.column_stack([np.array(s[-min_len:], dtype=np.float64) for s in return_series])
                port_daily = np.dot(aligned_returns, w_vec)
                weighted_volatility = float(np.std(port_daily, ddof=1) * np.sqrt(252))
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

    @staticmethod
    def simulate_monte_carlo(
        initial_amount: float,
        monthly_contribution: float,
        years: int,
        simulations_count: int,
        equity_weight: float,
        equity_return: float,
        equity_volatility: float,
        equity_ter: float,
        bond_weight: float,
        bond_rate: float,
        is_tax_exempt: bool,
        target_goal: Optional[float]
    ) -> Dict[str, Any]:
        simulations_count = max(100, min(simulations_count, 20000))
        years = max(1, min(years, 50))
        total_months = years * 12
        dt = 1.0 / 12.0

        total_alloc = equity_weight + bond_weight
        if total_alloc > 0:
            eq_w = equity_weight / total_alloc
            bd_w = bond_weight / total_alloc
        else:
            eq_w, bd_w = 1.0, 0.0

        mu_net = equity_return - equity_ter
        sigma = equity_volatility

        rng = np.random.default_rng(42)
        Z = rng.standard_normal((simulations_count, total_months))

        drift_term = (mu_net - 0.5 * (sigma ** 2)) * dt
        drift_term_nofee = (equity_return - 0.5 * (sigma ** 2)) * dt
        diff_term = sigma * np.sqrt(dt)

        equity_factors = np.exp(drift_term + diff_term * Z)
        equity_factors_nofee = np.exp(drift_term_nofee + diff_term * Z)
        bond_factor = 1.0 + (bond_rate * dt)

        equity_val = np.full(simulations_count, initial_amount * eq_w, dtype=np.float64)
        bond_val = np.full(simulations_count, initial_amount * bd_w, dtype=np.float64)
        equity_val_nofee = np.full(simulations_count, initial_amount * eq_w, dtype=np.float64)
        bond_val_nofee = np.full(simulations_count, initial_amount * bd_w, dtype=np.float64)

        monthly_equity = monthly_contribution * eq_w
        monthly_bond = monthly_contribution * bd_w

        timeline = []
        invested = initial_amount

        for m in range(1, total_months + 1):
            equity_val = equity_val * equity_factors[:, m - 1] + monthly_equity
            bond_val = bond_val * bond_factor + monthly_bond

            equity_val_nofee = equity_val_nofee * equity_factors_nofee[:, m - 1] + monthly_equity
            bond_val_nofee = bond_val_nofee * bond_factor + monthly_bond

            invested += monthly_contribution

            if m % 12 == 0:
                current_year = m // 12
                total_current = equity_val + bond_val
                total_current_nofee = equity_val_nofee + bond_val_nofee

                if not is_tax_exempt:
                    gain = np.maximum(0.0, total_current - invested)
                    val_for_p = total_current - POLISH_BELKA_TAX_RATE * gain
                else:
                    val_for_p = total_current

                drag = np.maximum(0.0, total_current_nofee - total_current)
                timeline.append({
                    "year": current_year,
                    "p10": round(float(np.percentile(val_for_p, 10)), 2),
                    "p50": round(float(np.percentile(val_for_p, 50)), 2),
                    "p90": round(float(np.percentile(val_for_p, 90)), 2),
                    "feeDrag": round(float(np.percentile(drag, 50)), 2),
                    "invested": round(float(invested), 2),
                })

        final_val = equity_val + bond_val
        final_val_nofee = equity_val_nofee + bond_val_nofee

        if not is_tax_exempt:
            final_gain = np.maximum(0.0, final_val - invested)
            final_val_net = final_val - POLISH_BELKA_TAX_RATE * final_gain
        else:
            final_val_net = final_val

        final_fee_drag = np.maximum(0.0, final_val_nofee - final_val)
        success_rate = (
            float(np.mean(final_val_net >= target_goal))
            if target_goal and target_goal > 0
            else float(np.mean(final_val_net >= invested))
        )

        summary = {
            "p10Final": round(float(np.percentile(final_val_net, 10)), 2),
            "p50Final": round(float(np.percentile(final_val_net, 50)), 2),
            "p90Final": round(float(np.percentile(final_val_net, 90)), 2),
            "totalInvested": round(float(invested), 2),
            "totalFeeDrag": round(float(np.percentile(final_fee_drag, 50)), 2),
            "successRate": round(success_rate, 4),
        }

        return {
            "summary": summary,
            "timeline": timeline,
        }
