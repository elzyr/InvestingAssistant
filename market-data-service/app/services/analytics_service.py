import datetime
import logging
import math
from typing import Any, Dict, List, Optional
import numpy as np
import yfinance as yf
from app.services.cache_service import cache_service
from app.services.market_history_repo import market_history_repo
from app.services.market_utils import resolve_fx_on_or_before, warsaw_today
from app.services.yahoo_service import (
    YahooService,
    _read_fast_info_field,
    _safe_fast_info,
    download_clean_history,
    extract_date_col,
    finite_float,
    infer_base_currency,
    normalize_quote_currency,
    run_batch,
)

ANALYTICS_CACHE_TTL_SECONDS = 21600
MIN_WEEKLY_VOL_OBSERVATIONS = 26
MIN_WEEKLY_RETURN_OBSERVATIONS = 52
MIN_PAIRWISE_CORR_WEEKS = 52


def _parse_date(raw_date: Any) -> Optional[datetime.date]:
    try:
        return datetime.date.fromisoformat(str(raw_date)[:10])
    except ValueError:
        return None


def _build_weekly_pln_log_returns(
    valid_prices: List[Dict[str, Any]],
    currency: Optional[str],
) -> Dict[str, float]:
    if len(valid_prices) < 2:
        return {}

    fx_dates: List[str] = []
    fx_rates: List[float] = []
    if currency and currency != "PLN":
        start_d = str(valid_prices[0]["date"])[:10]
        end_d = str(valid_prices[-1]["date"])[:10]
        try:
            fx_map = YahooService.get_historical_fx_rates(currency, "PLN", start_d, end_d)
            for d_str, raw_rate in sorted(fx_map.items()):
                rate = finite_float(raw_rate, positive_only=True)
                if rate is not None:
                    fx_dates.append(str(d_str)[:10])
                    fx_rates.append(rate)
        except Exception as e:
            logging.warning(f"Failed FX history for {currency}->PLN ({start_d}..{end_d}): {e}")
            return {}
        if not fx_dates:
            return {}

    weekly_closes: Dict[tuple[int, int], tuple[str, float]] = {}
    for p in valid_prices:
        d_str = str(p["date"])[:10]
        dt = _parse_date(d_str)
        close = finite_float(p.get("closePrice"), positive_only=True)
        if dt is None or close is None:
            continue
        if currency and currency != "PLN":
            fx = resolve_fx_on_or_before(fx_dates, fx_rates, d_str)
            if fx is None:
                continue
            close *= fx
        iso = dt.isocalendar()
        key = (iso.year, iso.week)
        prev = weekly_closes.get(key)
        if prev is None or d_str > prev[0]:
            weekly_closes[key] = (d_str, close)

    ordered = sorted(weekly_closes.items(), key=lambda kv: kv[0])
    if len(ordered) < 2:
        return {}

    weekly_returns: Dict[str, float] = {}
    for i in range(1, len(ordered)):
        prev_close = ordered[i - 1][1][1]
        cur_date, cur_close = ordered[i][1]
        if prev_close > 0.0 and cur_close > 0.0:
            ret = math.log(cur_close / prev_close)
            if math.isfinite(ret):
                weekly_returns[f"{ordered[i][0][0]}-W{ordered[i][0][1]:02d}"] = ret
    return weekly_returns


def _compute_weekly_return_and_volatility(
    weekly_returns: Dict[str, float],
) -> tuple[Optional[float], Optional[float]]:
    if not weekly_returns:
        return None, None
    vals = np.array(list(weekly_returns.values()), dtype=np.float64)
    n = len(vals)

    annual_volatility: Optional[float] = None
    if n >= MIN_WEEKLY_VOL_OBSERVATIONS:
        raw_vol = float(np.std(vals, ddof=1) * math.sqrt(52.0))
        if math.isfinite(raw_vol):
            annual_volatility = round(raw_vol, 6)

    annual_return: Optional[float] = None
    if n >= MIN_WEEKLY_RETURN_OBSERVATIONS:
        try:
            raw_ret = math.exp(float(np.mean(vals)) * 52.0) - 1.0
            if math.isfinite(raw_ret):
                annual_return = round(raw_ret, 6)
        except OverflowError:
            annual_return = None

    return annual_return, annual_volatility


def _nearest_psd_correlation(mat: np.ndarray, max_iter: int = 15) -> np.ndarray:
    n = mat.shape[0]
    if n <= 1:
        return np.ones((n, n), dtype=np.float64)
    y = mat.copy()
    ds = np.zeros_like(mat)
    for _ in range(max_iter):
        r = y - ds
        vals, vecs = np.linalg.eigh(0.5 * (r + r.T))
        vals_clipped = np.maximum(vals, 1e-8)
        x = (vecs * vals_clipped) @ vecs.T
        ds = x - r
        y = 0.5 * (x + x.T)
        np.fill_diagonal(y, 1.0)
    vals, vecs = np.linalg.eigh(0.5 * (y + y.T))
    vals = np.maximum(vals, 1e-8)
    psd = (vecs * vals) @ vecs.T
    d = np.sqrt(np.maximum(np.diag(psd), 1e-12))
    psd = psd / np.outer(d, d)
    np.fill_diagonal(psd, 1.0)
    return np.clip(psd, -1.0, 1.0)


def _compute_portfolio_risk(
    instruments_metrics: List[Dict[str, Any]],
    weekly_returns_series: List[Dict[str, float]],
    normalized_weights: List[float],
) -> tuple[Optional[float], Dict[str, Dict[str, float]], Dict[str, Dict[str, float]]]:
    n = len(instruments_metrics)
    symbols = [m["symbol"] for m in instruments_metrics]
    vols = [
        float(m["annualVolatility"])
        if m.get("annualVolatility") is not None and math.isfinite(float(m["annualVolatility"]))
        else None
        for m in instruments_metrics
    ]

    sample_corr = np.eye(n, dtype=np.float64)
    var_sum = 0.0
    sq_sum = 0.0
    for i in range(n):
        for j in range(i + 1, n):
            si = weekly_returns_series[i]
            sj = weekly_returns_series[j]
            common = sorted(si.keys() & sj.keys())
            if len(common) >= MIN_PAIRWISE_CORR_WEEKS:
                a = np.array([si[w] for w in common], dtype=np.float64)
                b = np.array([sj[w] for w in common], dtype=np.float64)
                sa = float(np.std(a, ddof=1))
                sb = float(np.std(b, ddof=1))
                if sa > 0.0 and sb > 0.0:
                    za = (a - np.mean(a)) / sa
                    zb = (b - np.mean(b)) / sb
                    prod = za * zb
                    r_ij = float(np.mean(prod))
                    if math.isfinite(r_ij):
                        r_ij = max(-1.0, min(1.0, r_ij))
                        sample_corr[i, j] = r_ij
                        sample_corr[j, i] = r_ij
                        m_len = len(common)
                        w_var = float(np.sum((prod - r_ij) ** 2) * (m_len / ((m_len - 1) ** 3)))
                        var_sum += w_var
                        sq_sum += r_ij * r_ij

    lam = min(1.0, max(0.0, var_sum / sq_sum)) if sq_sum > 1e-12 else 0.0
    shrunk = (1.0 - lam) * sample_corr + lam * np.eye(n, dtype=np.float64)
    psd_corr = _nearest_psd_correlation(shrunk)

    corr_matrix: Dict[str, Dict[str, float]] = {s: {} for s in symbols}
    cov_matrix: Dict[str, Dict[str, float]] = {s: {} for s in symbols}
    for i, sym_i in enumerate(symbols):
        for j, sym_j in enumerate(symbols):
            c_val = round(float(psd_corr[i, j]), 4)
            corr_matrix[sym_i][sym_j] = c_val
            if vols[i] is not None and vols[j] is not None:
                cov_matrix[sym_i][sym_j] = round(float(psd_corr[i, j]) * vols[i] * vols[j], 6)

    covered = [(i, normalized_weights[i]) for i in range(n) if vols[i] is not None and normalized_weights[i] > 0.0]
    covered_weight = sum(w for _, w in covered)
    weighted_volatility: Optional[float] = None
    if covered and covered_weight >= 0.5:
        idx = [i for i, _ in covered]
        w_sub = np.array([w / covered_weight for _, w in covered], dtype=np.float64)
        sig_sub = np.array([vols[i] for i in idx], dtype=np.float64)
        corr_sub = psd_corr[np.ix_(idx, idx)]
        cov_sub = np.outer(sig_sub, sig_sub) * corr_sub
        port_var = float(w_sub @ cov_sub @ w_sub)
        if port_var >= 0.0 and math.isfinite(port_var):
            weighted_volatility = round(math.sqrt(port_var), 4)

    return weighted_volatility, corr_matrix, cov_matrix


def _distribute_mixed_weights(
    items: List[Dict[str, Any]],
    unweighted_items: List[Dict[str, Any]],
    weight_factor: float,
    unweighted_share: float,
) -> List[float]:
    unweighted_val = sum((it["value"] or 0.0) for it in unweighted_items)
    resolved: List[float] = []
    for it in items:
        if it["has_weight"]:
            resolved.append(it["weight"] * weight_factor)
        elif unweighted_val > 0.0:
            resolved.append(unweighted_share * ((it["value"] or 0.0) / unweighted_val))
        else:
            resolved.append(unweighted_share / len(unweighted_items))
    return resolved


def _weighted_metric(
    weights: List[float],
    instruments: List[Dict[str, Any]],
    field: str,
    precision: int,
    min_coverage: float = 0.5,
) -> Optional[float]:
    valid = [
        (w, float(m[field]))
        for w, m in zip(weights, instruments)
        if m.get(field) is not None and math.isfinite(float(m[field]))
    ]
    if not valid:
        return None
    covered_weight = sum(w for w, _ in valid)
    if covered_weight < min_coverage or covered_weight <= 0.0:
        return None
    return round(sum(w * v for w, v in valid) / covered_weight, precision)


class AnalyticsService:
    @staticmethod
    def extract_fund_metrics(raw_info: Dict[str, Any]) -> Dict[str, Optional[float]]:
        ter: Optional[float] = None
        if (val := finite_float(raw_info.get("annualReportExpenseRatio"))) is not None:
            ter = round(val, 6)
        else:
            for pct_key in ("netExpenseRatio", "expenseRatio"):
                if (val := finite_float(raw_info.get(pct_key))) is not None:
                    ter = round(val / 100.0, 6)
                    break
        return {"ter": ter}

    @staticmethod
    def _build_etf_metrics(sym: str) -> tuple[Dict[str, Any], int]:
        today = warsaw_today()
        today_str = today.isoformat()
        yesterday_str = (today - datetime.timedelta(days=1)).isoformat()
        max_date = market_history_repo.get_max_date(sym)
        existing_profile = market_history_repo.get_profile_metrics(sym)
        profile = existing_profile

        already_updated_today = bool(existing_profile and existing_profile.get("lastUpdated") == today_str)
        history_checked_at = existing_profile.get("historyCheckedAt") if existing_profile else None
        shallow_history = False
        if max_date and existing_profile is not None and not already_updated_today and history_checked_at is None:
            coverage = market_history_repo.get_price_coverage(sym)
            expected_5y_cutoff = (today - datetime.timedelta(days=365 * 5 - 90)).isoformat()
            shallow_history = (
                coverage is None
                or coverage.get("count", 0) == 0
                or coverage["minDate"] > expected_5y_cutoff
                or coverage.get("count", 0) < 200
            )
        fetch_full_history = (not max_date) or (existing_profile is None) or (history_checked_at is None and shallow_history)
        needs_fetch = fetch_full_history or (max_date < yesterday_str and not already_updated_today)

        if needs_fetch:
            try:
                stock = yf.Ticker(sym)
                hist = (
                    download_clean_history(stock, period="5y", reset_index=True)
                    if fetch_full_history
                    else download_clean_history(stock, start=max_date, reset_index=True)
                )
                if not hist.empty:
                    date_col = extract_date_col(hist)
                    fast_info = _safe_fast_info(stock)
                    currency, price_scale = normalize_quote_currency(_read_fast_info_field(fast_info, "currency"), sym)

                    replace_history = bool(max_date and fetch_full_history)
                    if max_date and not fetch_full_history:
                        matching_row = hist[hist[date_col].astype(str).str[:10] == max_date]
                        if not matching_row.empty:
                            fetched_close = float(matching_row.iloc[0]["Close"]) * price_scale
                            stored_close = market_history_repo.get_price_on_date(sym, max_date)
                            if stored_close is not None and stored_close > 0 and abs(fetched_close - stored_close) / stored_close > 0.001:
                                full_hist = download_clean_history(stock, period="5y", reset_index=True)
                                if not full_hist.empty:
                                    hist = full_hist
                                    date_col = extract_date_col(hist)
                                    replace_history = True

                    vols = hist["Volume"].values if "Volume" in hist.columns else [0] * len(hist)
                    records = [
                        {
                            "date": str(d)[:10],
                            "close_price": round(float(c) * price_scale, 4),
                            "currency": currency,
                            "volume": max(0, int(finite_float(v, 0.0) or 0.0)),
                        }
                        for d, c, v in zip(hist[date_col].values, hist["Close"].values, vols)
                    ]
                    if replace_history:
                        market_history_repo.replace_symbol_prices(sym, records, currency)
                    else:
                        market_history_repo.save_eod_prices_bulk(sym, records, currency)

                    info: Dict[str, Any] = {}
                    try:
                        raw_info = getattr(stock, "info", None)
                        if isinstance(raw_info, dict):
                            info = raw_info
                    except Exception as info_err:
                        logging.warning(f"Failed to fetch Yahoo stock.info for {sym}: {info_err}")

                    enriched = AnalyticsService.extract_fund_metrics(info)
                    checked_at = today_str if (fetch_full_history or replace_history) else history_checked_at
                    market_history_repo.save_profile_metrics(
                        symbol=sym,
                        ter=enriched["ter"],
                        last_updated=today_str,
                        history_checked_at=checked_at,
                    )
                    profile = {**enriched, "lastUpdated": today_str, "historyCheckedAt": checked_at}
            except Exception as e:
                logging.warning(f"Failed to fetch market data from Yahoo for {sym}: {e}")

        if not profile:
            profile = AnalyticsService.extract_fund_metrics({})

        prices = market_history_repo.get_historical_prices(sym)
        valid_prices = [
            p for p in prices
            if (cp := finite_float(p.get("closePrice"), positive_only=True)) is not None and cp > 0.0
        ]
        currency = (
            valid_prices[-1].get("currency")
            if valid_prices
            else (prices[-1].get("currency") if prices else None)
        ) or infer_base_currency(sym)

        weekly_returns = _build_weekly_pln_log_returns(valid_prices, currency)
        annual_return, annual_volatility = _compute_weekly_return_and_volatility(weekly_returns)
        result = {
            "symbol": sym,
            "annualReturn": annual_return,
            "annualVolatility": annual_volatility,
            "ter": profile.get("ter"),
            "currency": currency,
        }
        ttl = ANALYTICS_CACHE_TTL_SECONDS if valid_prices else 300
        return result, ttl

    @staticmethod
    def fetch_etf_market_metrics(symbol: str) -> Dict[str, Any]:
        sym = symbol.upper()
        cache_key = f"analytics:etf:{sym}"
        ttl_holder = [300]

        def _build() -> Dict[str, Any]:
            result, ttl = AnalyticsService._build_etf_metrics(sym)
            ttl_holder[0] = ttl
            return result

        return cache_service.get_or_set(
            cache_key,
            _build,
            ttl=lambda _: ttl_holder[0],
            validator=lambda c: isinstance(c, dict),
        )

    @staticmethod
    def _load_weekly_returns(sym: str, metric: Dict[str, Any]) -> Dict[str, float]:
        if isinstance(metric.get("weeklyReturns"), dict):
            return metric["weeklyReturns"]
        prices = market_history_repo.get_historical_prices(sym)
        valid_prices = [
            p for p in prices
            if (cp := finite_float(p.get("closePrice"), positive_only=True)) is not None and cp > 0.0
        ]
        if not valid_prices:
            return {}
        currency = metric.get("currency") or valid_prices[-1].get("currency") or infer_base_currency(sym)
        return _build_weekly_pln_log_returns(valid_prices, currency)

    @staticmethod
    def _resolve_portfolio_weights(items: List[Dict[str, Any]]) -> List[float]:
        total_weight = sum(it["weight"] for it in items)
        unweighted_items = [it for it in items if not it["has_weight"]]
        if total_weight <= 0.0:
            total_val = sum((it["value"] or 0.0) for it in items)
            if total_val > 0.0:
                return [(it["value"] or 0.0) / total_val for it in items]
            if unweighted_items and len(unweighted_items) < len(items):
                eq_u = 1.0 / len(unweighted_items)
                return [0.0 if it["has_weight"] else eq_u for it in items]
            eq_w = 1.0 / len(items)
            return [eq_w for _ in items]

        weighted_items = [it for it in items if it["has_weight"]]
        if not unweighted_items:
            return [it["weight"] / total_weight for it in items]

        scale = 100.0 if any(it["weight"] > 1.0 + 1e-9 for it in weighted_items) else 1.0
        if total_weight < scale - 1e-9:
            return _distribute_mixed_weights(
                items,
                unweighted_items,
                weight_factor=1.0 / scale,
                unweighted_share=1.0 - (total_weight / scale),
            )

        total_val = sum((it["value"] or 0.0) for it in items)
        if all((it["value"] or 0.0) > 0.0 for it in items) and total_val > 0.0:
            return [(it["value"] or 0.0) / total_val for it in items]

        weighted_share = len(weighted_items) / len(items)
        return _distribute_mixed_weights(
            items,
            unweighted_items,
            weight_factor=weighted_share / total_weight,
            unweighted_share=1.0 - weighted_share,
        )

    @staticmethod
    def calculate_portfolio_metrics(holdings: List[Any]) -> Dict[str, Any]:
        if not holdings:
            return {
                "weightedReturn": None,
                "weightedVolatility": None,
                "weightedTer": None,
                "instruments": [],
                "symbols": [],
                "correlationMatrix": {},
                "covarianceMatrix": {},
            }

        consolidated: Dict[str, Dict[str, Any]] = {}
        for h in holdings:
            sym = h.symbol.upper()
            raw_w = getattr(h, "weight", None)
            raw_v = getattr(h, "value", None)
            entry = consolidated.get(sym)
            if entry is None:
                consolidated[sym] = {
                    "symbol": sym,
                    "weight": float(raw_w) if raw_w is not None else 0.0,
                    "has_weight": raw_w is not None,
                    "value": float(raw_v) if raw_v is not None else None,
                }
            else:
                if raw_w is not None:
                    entry["weight"] += float(raw_w)
                    entry["has_weight"] = True
                if raw_v is not None:
                    entry["value"] = (entry["value"] or 0.0) + float(raw_v)

        items = list(consolidated.values())
        calc_weights = AnalyticsService._resolve_portfolio_weights(items)

        unique_symbols = [it["symbol"] for it in items]
        metrics_by_symbol = run_batch(
            unique_symbols,
            AnalyticsService.fetch_etf_market_metrics,
            error_label="Analytics fetch",
            reraise=True,
        )

        instruments_metrics = []
        normalized_weights = []
        weekly_returns_series = []
        for i, item in enumerate(items):
            sym = item["symbol"]
            w = calc_weights[i]
            normalized_weights.append(w)
            m = metrics_by_symbol[sym]
            weekly_returns_series.append(AnalyticsService._load_weekly_returns(sym, m))
            instruments_metrics.append({
                "symbol": sym,
                "weight": round(w, 4),
                "value": item["value"],
                "annualReturn": m["annualReturn"],
                "annualVolatility": m["annualVolatility"],
                "ter": m["ter"],
                "currency": m.get("currency"),
            })

        weighted_return = _weighted_metric(normalized_weights, instruments_metrics, "annualReturn", 4)
        weighted_ter = _weighted_metric(normalized_weights, instruments_metrics, "ter", 4)

        weighted_volatility, correlation_matrix, covariance_matrix = _compute_portfolio_risk(
            instruments_metrics,
            weekly_returns_series,
            normalized_weights,
        )

        return {
            "weightedReturn": weighted_return,
            "weightedVolatility": weighted_volatility,
            "weightedTer": weighted_ter,
            "instruments": instruments_metrics,
            "symbols": unique_symbols,
            "correlationMatrix": correlation_matrix,
            "covarianceMatrix": covariance_matrix,
        }
