# Spec: dashboard-etf-forecast

## Problem Statement
Currently, the portfolio forecast on the main dashboard (`PortfolioChart.tsx` via `generateUnifiedPortfolioTimeline`) uses hardcoded assumptions: a static 8.5% expected ETF return, an arbitrary 15.0% volatility, and guessed expense ratios via string-matching heuristics (`detectEtfProfile`).
Furthermore, the previous iteration added a separate, disconnected tab on `MonteCarloPage.tsx` with AI emojis (`⚡`, `🎲`) and an unintegrated chart, rather than enhancing the user's primary workflow: the portfolio valuation history and forecast directly on the Dashboard.

## Solution
Implement a minimal, seamless integration of empirical ETF metrics into the main dashboard's forecast:
1. **Clean up `MonteCarloPage.tsx`:**
   - Remove tab switcher, remove all emojis, delete `EmpiricalMonteCarloView.tsx` (-782 lines).
   - Restore `MonteCarloPage.tsx` to cleanly render `StressTestingView` directly without unnecessary chrome.
2. **Feed Real ETF Statistics into Dashboard Forecast:**
   - In `DashboardPage.tsx`: when positions load and active ETF/equity holdings exist, query `marketService.getPortfolioStats`.
   - Scale decimal rates from backend to percentage units (`* 100`) required by the frontend engine:
     - `empiricalReturn = Math.round(data.weightedReturn * 1000) / 10`
     - `empiricalVol = Math.round(data.weightedVolatility * 1000) / 10`
     - `empiricalTer = Math.round(data.weightedTer * 10000) / 100`
   - Auto-populate `expectedEtfReturn` with `empiricalReturn` once per active portfolio (using a ref so user manual changes are preserved).
   - In `portfolioUnifiedEngine.ts`: respect caller-supplied empirical volatility and TER when provided instead of recalculating from string heuristics.
3. **Refine `PortfolioChart.tsx` UI (Strict Minimalism & No Emojis):**
   - Eliminate all emojis (replace `🎯` with clean typography).
   - Add dynamic option in the `CAGR ETF` dropdown for the empirical rate (e.g. `<option value={empiricalReturn}>{empiricalReturn}% (Portfel)</option>`) so custom percentages never render blank.
   - Display a compact, monochrome badge in the projection bar:
     `ETF: TER X.XX% · σ XX.X% · P/E XX.X`.
   - Preserve existing floating HUD tooltip (Hossa P90 / Bessa P10).

## User Stories
1. **As an investor viewing my portfolio valuation history**, I want the projection horizon (+3L, +5L, +10L, +20L) to automatically use the empirical volatility, return, and TER of my actual ETFs, so that I see realistic forecasts without navigating away from the dashboard.
2. **As a user who values professional UI**, I want the interface to be free of AI emojis and cluttered sub-views, with clean typography matching Koyfin and Ghostfolio.

## Implementation Decisions
- **Minimalist Seam (`ponytail`):**
  - No new backend endpoints or database changes needed—the existing, tested `/api/market/analytics/portfolio-stats` endpoint in `market-data-service` is reused directly.
  - Diff is strictly confined to frontend components: `DashboardPage.tsx`, `PortfolioChart.tsx`, `portfolioUnifiedEngine.ts`, and cleaning `MonteCarloPage.tsx`.
- **Zero Dependencies:**
  - Uses existing Lucide icons, existing lightweight-charts, and existing Redux state.

## Testing Decisions
- Frontend typecheck: `npm --prefix frontend run build` must pass with 0 errors.
- Backend unit tests: `docker exec investassist-market-data python -m unittest discover -s tests` remains 100% green.
- Verify in browser that clicking `+3L` / `+5L` on Dashboard renders the forecast seamlessly with the empirical ETF metrics badge.

## Out of Scope
- Separate subpages or standalone charting engines for Monte Carlo.
- Changing backend calculation math (already verified and signed off).
