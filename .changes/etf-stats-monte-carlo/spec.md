# Spec: etf-stats-monte-carlo

## Problem Statement
Currently, the portfolio simulation and stress testing module in `InvestingAssistant` relies on generic, hardcoded multipliers and simplified estimates (e.g. constant hypothetical stock drawdowns and growth rates). Investors holding specific exchange-traded funds (ETFs) such as VWCE, VUAA, or sector/regional funds have access to rich market metrics—including historical prices, empirical return ($\mu$), annualized volatility ($\sigma$), total expense ratio (TER), trailing price-to-earnings (P/E), dividend yield, and beta.
Furthermore, repeatedly querying external financial APIs (Yahoo Finance) on the fly introduces high latency, risk of HTTP 429 rate limits, and fragile rendering.
Without persisting daily end-of-day (EOD) prices and fund profile metrics in a local database, portfolio analytics cannot run in sub-second time, nor can they accurately account for real asset volatility, fee drag (lost compound growth due to TER), and valuation multiples.

## Solution
Implement an end-to-end historical market data repository and vector-optimized ETF analytics engine:
1. **Database Persistence in `market-data-service` (PostgreSQL `market_data_db`):**
   - Configure a dedicated PostgreSQL database `market_data_db` in the existing `postgres` container.
   - Tables: `daily_price_history` (symbol, date, close_price, currency, volume with unique constraint on `(symbol, date)`) and `etf_profile_metrics` (symbol, ter, trailing_pe, dividend_yield, beta, last_updated).
   - Implement an incremental delta-backfill mechanism: check `MAX(date)` for any requested ticker; fetch only missing days from `yfinance` for completed sessions ($T < \text{today}$); save in bulk with `ON CONFLICT (symbol, date) DO NOTHING`; subsequent reads hit local DB in < 10ms.
   - Self-healing database bootstrap on startup: `market-data-service` connects to Postgres and ensures `market_data_db` and tables exist idempotently, avoiding issues with existing Docker volumes.
2. **Curated UCITS ETF Fallback & Data Quality:**
   - Because Yahoo Finance frequently omits `netExpenseRatio` or `trailingPE` on European UCITS tickers (`.DE`, `.L`, `.PA`), provide a curated deterministic fallback dictionary for common instruments (e.g. VWCE.DE $\to$ TER 0.22%, VUAA.DE $\to$ TER 0.07%, etc.) so that fee drag and valuation metrics never degrade to 0.
3. **Vectorized Financial Analytics & Monte Carlo Engine (Python / NumPy):**
   - Provide REST endpoint `POST /api/market/analytics/portfolio-stats`:
     Accepts equity ETF holdings and computes weighted empirical returns ($\mu$), portfolio volatility ($\sigma$) incorporating covariance, weighted TER, weighted P/E, weighted dividend yield, and portfolio Beta.
   - Provide REST endpoint `POST /api/market/analytics/monte-carlo`:
     Runs 10,000 vectorized Geometric Brownian Motion (GBM) simulation paths with exact Itô drift correction:
     $$S_{t+\Delta t} = S_t \exp\left( \left(\mu - TER - \frac{1}{2}\sigma^2\right)\Delta t + \sigma \sqrt{\Delta t} Z \right)$$
     incorporating monthly DCA contributions, bond allocations (EDO/TOS yield), tax status (IKE tax exemption vs 19% Belka tax), fee drag calculation (capital lost to TER), and quantile extraction (P10, P50, P90 timeline).
4. **API Gateway & Security Alignment:**
   - Support authenticated requests via frontend `apiClient` (JWT Bearer token) and ensure `api-gateway` routes `/api/market/**` cleanly without 401 blocks.
5. **Frontend Integration (React 19 + TypeScript):**
   - Add a tab switcher in `MonteCarloPage.tsx` (`/stress-testing`):
     `[⚡ Testy Warunków Skrajnych (Kryzysy) | 🎲 Empiryczne Monte Carlo ETF]`.
   - In the Empirical Monte Carlo tab:
     - Show KPI summary cards: Portfolio TER (and total PLN fee drag over time), Weighted P/E, Weighted Volatility $\sigma$, Annualized Expected Return $\mu$.
     - Interactive fan chart rendering the P10/P50/P90 projection timeline based on user's real holdings.

## User Stories
1. **As an investor with ETFs**, I want the system to compute my portfolio's true empirical volatility, return, and TER from actual market data, so that I can see realistic projections rather than generic guesses.
2. **As a cost-conscious investor**, I want to see how much money the management fee (TER) of my ETFs will cost me in lost compounded returns over 10–30 years (Fee Drag), so that I understand the impact of fund selection.
3. **As an active user**, I want simulations and statistics to load instantaneously (< 200ms) without hitting Yahoo Finance rate limits, so that the app remains responsive and resilient offline or under heavy use.

## Implementation Decisions
- **Deep Module Architecture:**
  - `MarketAnalyticsService`: single deep interface in `market-data-service` exposing `get_portfolio_stats(holdings)` and `simulate_monte_carlo(params)`. Hides DB connection, delta-fetching, covariance calculation, and vectorized NumPy array generation behind a clean contract.
  - `MarketHistoryRepository`: handles `psycopg[binary]` / SQLAlchemy storage for `daily_price_history` and `etf_profile_metrics` with upsert / batch insert and `ON CONFLICT DO NOTHING`.
- **Optimization Standards (YAGNI & Performance):**
  - Use NumPy vectorized matrix operations for 10,000 paths (zero Python for-loops over paths).
  - Database connection pool configured with SQLAlchemy / psycopg.
  - Delta queries use `MAX(date)` indexed queries to fetch only the incremental difference since last cached date.
  - Concurrency safety: `ON CONFLICT (symbol, date) DO NOTHING` prevents duplicate insertion errors.
  - Only persist closed trading sessions ($T < \text{today}$) to avoid caching partial intraday candles as EOD closing prices.
  - Return compact quantile timeline points (~15 KB JSON) instead of raw 10,000 simulation matrices (~28 MB).
- **Environment & Docker:**
  - Add `market_data_db` to `postgres-init/init.sql`.
  - Pass clean environment variables (`DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `DB_NAME`) to `market-data-service` in `docker-compose.yml`, with `depends_on: [postgres, redis]`.

## Testing Decisions
- **Public Seams for Verification:**
  - Backend integration test seam:
    - FastAPI HTTP test client testing `POST /api/market/analytics/portfolio-stats` and `POST /api/market/analytics/monte-carlo`.
    - Unit tests for repository delta backfill logic (verify that existing dates are not re-fetched and only new dates are inserted).
    - Unit tests for financial math: verify that weighted volatility, weighted TER, and fee drag match analytical formulas.
  - Frontend test seam:
    - Typecheck (`npx tsc --noEmit`) passes with 0 errors.
    - Production build (`npm run build`) passes cleanly.

## Out of Scope
- Full sector and geographical look-through breakdown (deferred to subsequent iteration as agreed with user).
- Real-time intraday tick streaming (EOD daily data is sufficient for portfolio analytics and Monte Carlo).
- Intraday order execution or broker API integrations.

## Further Notes
- Clean git workflow: work on branch `feat/etf-stats-monte-carlo`.
- All code changes strictly follow `.agents/AGENTS.md` (no unnecessary comments, minimal boilerplate, high performance).
