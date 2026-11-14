import datetime
from types import SimpleNamespace
from typing import Any, Dict, List
import numpy as np
from app.services.analytics_service import AnalyticsService
from app.services.market_utils import nearest_psd, warsaw_today

_CATEGORY_PROXY_VOL: Dict[str, float] = {
    "SP500": 0.16,
    "MSCI_WORLD": 0.15,
    "MSCI_ACWI": 0.15,
    "NASDAQ_100": 0.21,
    "EMERGING_MARKETS": 0.19,
    "PL_STOCKS": 0.22,
}

_CATEGORY_PROXY_RET: Dict[str, float] = {
    "SP500": 0.09,
    "MSCI_WORLD": 0.08,
    "MSCI_ACWI": 0.08,
    "NASDAQ_100": 0.105,
    "EMERGING_MARKETS": 0.075,
    "PL_STOCKS": 0.08,
}


def run_monte_carlo(req: Dict[str, Any]) -> Dict[str, Any]:
    horizon_years = int(req.get("horizonYears") or 10)
    n_months = max(1, horizon_years * 12)
    n_paths = 10_000
    half_paths = n_paths // 2
    seed = int(req.get("seed") if req.get("seed") is not None else 42)
    rng = np.random.Generator(np.random.PCG64(seed))

    start_date_str = req.get("startDate")
    if start_date_str:
        start_date = datetime.date.fromisoformat(str(start_date_str)[:10])
    else:
        start_date = warsaw_today()

    equity_holdings: List[Dict[str, Any]] = req.get("equityHoldings") or []
    bond_seed: Dict[str, Any] = req.get("bondSeed") or {}
    start_invested = float(req.get("startInvestedCapital") or 0.0)
    monthly_dca = max(0.0, float(req.get("monthlyDca") or 0.0))
    dca_equity_share = float(np.clip(float(req.get("dcaEquityShare") if req.get("dcaEquityShare") is not None else 0.5), 0.0, 1.0))
    tax_mode = str(req.get("taxMode") or "NETTO").upper()
    apply_belka = tax_mode != "BRUTTO"
    raw_belka = req.get("belkaTaxRate")
    belka_rate = float(raw_belka) if (apply_belka and raw_belka is not None) else (0.19 if apply_belka else 0.0)
    real_mode = bool(req.get("realMode"))
    bond_rollover = bool(req.get("bondRollover") if req.get("bondRollover") is not None else True)
    cpi_pct = float(req.get("cpiPct") if req.get("cpiPct") is not None else 3.0)
    target_goal = req.get("targetGoal")
    etf_override_pct = req.get("etfReturnOverridePct")

    proxy_warnings: List[str] = []
    n_eq = len(equity_holdings)
    eq_init_vals = np.array([max(0.0, float(h.get("valueBase") or 0.0)) for h in equity_holdings], dtype=np.float64)
    total_eq_init = float(eq_init_vals.sum())
    if total_eq_init > 0.0:
        eq_weights = eq_init_vals / total_eq_init
    elif n_eq > 0:
        raw_w = np.array([max(0.0, float(h.get("weight") or 0.0)) for h in equity_holdings], dtype=np.float64)
        eq_weights = raw_w / raw_w.sum() if raw_w.sum() > 0 else np.full(n_eq, 1.0 / n_eq)
    else:
        eq_weights = np.zeros(0, dtype=np.float64)

    if n_eq > 0:
        stats = AnalyticsService.calculate_portfolio_metrics([
            SimpleNamespace(
                symbol=str(h["symbol"]).upper(),
                value=float(h.get("valueBase") or 0.0),
                weight=h.get("weight"),
            )
            for h in equity_holdings
        ])
        by_sym = {m["symbol"]: m for m in stats.get("instruments", [])}
        cov_dict = stats.get("covarianceMatrix") or {}

        mu = np.zeros(n_eq, dtype=np.float64)
        vols = np.zeros(n_eq, dtype=np.float64)
        for i, h in enumerate(equity_holdings):
            sym = str(h["symbol"]).upper()
            cat = str(h.get("categoryCode") or "OTHER").upper()
            m = by_sym.get(sym, {})
            ter_dec = float(m.get("ter") or 0.0)
            if etf_override_pct is not None:
                mu[i] = max(-0.95, float(etf_override_pct) / 100.0 - ter_dec)
            elif m.get("annualReturn") is not None:
                mu[i] = float(m["annualReturn"])
            else:
                proxy_r = _CATEGORY_PROXY_RET.get(cat, 0.075)
                mu[i] = proxy_r - ter_dec
                proxy_warnings.append(f"{sym}: <52w history, using {cat} proxy return ({proxy_r*100:.1f}%)")

            if m.get("annualVolatility") is not None:
                vols[i] = max(0.01, float(m["annualVolatility"]))
            else:
                proxy_v = _CATEGORY_PROXY_VOL.get(cat, 0.17)
                vols[i] = proxy_v
                proxy_warnings.append(f"{sym}: <26w history, using {cat} proxy volatility ({proxy_v*100:.0f}%)")

        cov = np.zeros((n_eq, n_eq), dtype=np.float64)
        for i, hi in enumerate(equity_holdings):
            si = str(hi["symbol"]).upper()
            for j, hj in enumerate(equity_holdings):
                sj = str(hj["symbol"]).upper()
                if i == j:
                    cov[i, j] = vols[i] ** 2
                elif si in cov_dict and sj in cov_dict[si]:
                    cov[i, j] = float(cov_dict[si][sj])
        cov = nearest_psd(cov)
        dt = 1.0 / 12.0
        L = np.linalg.cholesky(cov * dt)
        drift = (mu - 0.5 * np.diag(cov)) * dt

        z_half = rng.standard_normal((n_months, half_paths, n_eq))
        z = np.concatenate([z_half, -z_half], axis=1)
        log_ret = drift[None, None, :] + z @ L.T
        eq_factors = np.exp(log_ret)
    else:
        eq_factors = np.ones((n_months, n_paths, 0), dtype=np.float64)

    fixed_bond_val = max(0.0, float(bond_seed.get("startFixedValue") or 0.0))
    indexed_bond_val = max(0.0, float(bond_seed.get("startIndexedValue") or 0.0))
    fixed_rate_pct = max(0.0, float(bond_seed.get("fixedRatePct") if bond_seed.get("fixedRatePct") is not None else 5.5))
    indexed_rate_pct = max(0.0, float(bond_seed.get("indexedRatePct") if bond_seed.get("indexedRatePct") is not None else (max(0.0, cpi_pct) + 1.5)))
    avg_rem_months = int(bond_seed.get("avgRemainingMonths") or 60)

    total_init_cap = total_eq_init + fixed_bond_val + indexed_bond_val
    eq_cost_init = start_invested * (total_eq_init / total_init_cap) if total_init_cap > 0 else 0.0
    bond_cost_init = start_invested - eq_cost_init

    eq_vals = np.broadcast_to(eq_init_vals, (n_paths, n_eq)).copy()
    fixed_vals = np.full(n_paths, fixed_bond_val, dtype=np.float64)
    indexed_vals = np.full(n_paths, indexed_bond_val, dtype=np.float64)
    cash_vals = np.zeros(n_paths, dtype=np.float64)

    eq_cost = np.full(n_paths, eq_cost_init, dtype=np.float64)
    bond_cost = np.full(n_paths, bond_cost_init, dtype=np.float64)
    cumulative_invested = start_invested

    fixed_monthly_factor = (1.0 + fixed_rate_pct / 100.0) ** (1.0 / 12.0)
    indexed_monthly_factor = (1.0 + indexed_rate_pct / 100.0) ** (1.0 / 12.0)
    dca_eq = monthly_dca * dca_equity_share
    dca_bond = monthly_dca * (1.0 - dca_equity_share)
    cpi_dec = max(-0.99, cpi_pct / 100.0)

    points: List[Dict[str, Any]] = []
    terminal_vals = np.zeros(n_paths, dtype=np.float64)

    for m in range(1, n_months + 1):
        if n_eq > 0:
            eq_vals *= eq_factors[m - 1]
            eq_vals += dca_eq * eq_weights[None, :]
            eq_cost += dca_eq

        fixed_vals *= fixed_monthly_factor
        indexed_vals *= indexed_monthly_factor
        indexed_vals += dca_bond
        bond_cost += dca_bond

        if avg_rem_months > 0 and m % avg_rem_months == 0:
            total_b = fixed_vals + indexed_vals
            b_gain = np.maximum(0.0, total_b - bond_cost)
            b_tax = belka_rate * b_gain
            matured_net = total_b - b_tax
            if bond_rollover:
                fixed_vals.fill(0.0)
                indexed_vals = matured_net
                bond_cost = matured_net.copy()
            else:
                fixed_vals.fill(0.0)
                indexed_vals.fill(0.0)
                cash_vals += matured_net
                bond_cost.fill(0.0)

        cumulative_invested += monthly_dca

        nom_eq = eq_vals.sum(axis=1) if n_eq > 0 else np.zeros(n_paths, dtype=np.float64)
        nom_bond = fixed_vals + indexed_vals
        nom_gross = nom_eq + nom_bond + cash_vals
        eq_tax = belka_rate * np.maximum(0.0, nom_eq - eq_cost)
        bond_tax = belka_rate * np.maximum(0.0, nom_bond - bond_cost)
        nom_net = nom_gross - eq_tax - bond_tax

        if real_mode:
            deflator = (1.0 + cpi_dec) ** (m / 12.0)
            val_m = nom_net / deflator
            inv_m = cumulative_invested / deflator
        else:
            val_m = nom_net
            inv_m = cumulative_invested

        if m == n_months:
            terminal_vals = val_m

        step_date = _add_months(start_date, m)
        p10, p50, p90 = np.percentile(val_m, [10.0, 50.0, 90.0])
        points.append({
            "monthOffset": m,
            "date": step_date.isoformat(),
            "p10": round(float(p10), 2),
            "p50": round(float(p50), 2),
            "p90": round(float(p90), 2),
            "invested": round(float(inv_m), 2),
        })

    goal_prob: Optional[int] = None
    if target_goal is not None and float(target_goal) > 0:
        goal_prob = int(round(100.0 * float(np.mean(terminal_vals >= float(target_goal)))))

    last_pt = points[-1] if points else {"p10": 0.0, "p50": 0.0, "p90": 0.0, "invested": round(start_invested, 2)}
    return {
        "points": points,
        "proxyWarnings": list(dict.fromkeys(proxy_warnings)),
        "summary": {
            "terminalP10": last_pt["p10"],
            "terminalP50": last_pt["p50"],
            "terminalP90": last_pt["p90"],
            "totalInvested": last_pt["invested"],
            "goalProbability": goal_prob,
        },
    }


def _add_months(d: datetime.date, months: int) -> datetime.date:
    y = d.year + (d.month - 1 + months) // 12
    m = (d.month - 1 + months) % 12 + 1
    import calendar
    day = min(d.day, calendar.monthrange(y, m)[1])
    return datetime.date(y, m, day)
