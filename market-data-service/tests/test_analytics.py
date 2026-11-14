import unittest
from unittest.mock import patch
import datetime
import asyncio

class TestPortfolioStatsSeam(unittest.TestCase):
    def test_portfolio_stats_endpoint_returns_expected_metrics(self):
        from app.routers.analytics_router import calculate_portfolio_stats, PortfolioStatsRequest, HoldingItem

        request = PortfolioStatsRequest(
            holdings=[
                HoldingItem(symbol="VWCE.DE", weight=0.6, value=60000.0),
                HoldingItem(symbol="VUAA.DE", weight=0.4, value=40000.0)
            ],
        )

        with patch("app.services.analytics_service.AnalyticsService.fetch_etf_market_metrics") as mock_fetch:
            mock_fetch.side_effect = lambda symbol: {
                "VWCE.DE": {
                    "symbol": "VWCE.DE",
                    "annualReturn": 0.09,
                    "annualVolatility": 0.15,
                    "ter": 0.0022,
                    "trailingPe": 21.0,
                    "dividendYield": 0.015,
                    "beta": 1.0,
                    "weeklyReturns": {},
                },
                "VUAA.DE": {
                    "symbol": "VUAA.DE",
                    "annualReturn": 0.11,
                    "annualVolatility": 0.16,
                    "ter": 0.0007,
                    "trailingPe": 24.0,
                    "dividendYield": 0.012,
                    "beta": 1.05,
                    "weeklyReturns": {},
                }
            }[symbol]

            response = asyncio.run(calculate_portfolio_stats(request))
            res = response.model_dump() if hasattr(response, "model_dump") else response.dict()

            self.assertIn("weightedReturn", res)
            self.assertIn("weightedVolatility", res)
            self.assertIn("weightedTer", res)
            self.assertNotIn("weightedPe", res)
            self.assertNotIn("portfolioBeta", res)
            self.assertNotIn("currencyBasis", res)
            self.assertIn("covarianceMatrix", res)
            self.assertIn("symbols", res)

            self.assertAlmostEqual(res["weightedTer"], 0.0016, places=4)
            self.assertAlmostEqual(res["weightedReturn"], 0.098, places=4)

    def test_missing_yahoo_metadata_returns_none_without_hardcoded_fallback(self):
        from app.services.analytics_service import AnalyticsService

        raw_info = {"symbol": "VWCE.DE", "shortName": "Vanguard FTSE All-World"}
        metrics = AnalyticsService.extract_fund_metrics(raw_info)

        self.assertIsNone(metrics["ter"])

    def test_ter_normalized_by_field_name(self):
        from app.services.analytics_service import AnalyticsService

        self.assertAlmostEqual(AnalyticsService.extract_fund_metrics({"annualReportExpenseRatio": 0.0022})["ter"], 0.0022, places=6)
        self.assertAlmostEqual(AnalyticsService.extract_fund_metrics({"netExpenseRatio": 0.22})["ter"], 0.0022, places=6)
        self.assertAlmostEqual(AnalyticsService.extract_fund_metrics({"expenseRatio": 0.15})["ter"], 0.0015, places=6)

    def test_portfolio_stats_request_enforces_max_holdings(self):
        from pydantic import ValidationError
        from app.routers.analytics_router import PortfolioStatsRequest, HoldingItem

        with self.assertRaises(ValidationError):
            PortfolioStatsRequest(
                holdings=[HoldingItem(symbol="VWCE.DE", weight=1.0) for _ in range(51)]
            )

    def test_holding_item_rejects_invalid_symbol_format(self):
        from pydantic import ValidationError
        from app.routers.analytics_router import HoldingItem

        with self.assertRaises(ValidationError):
            HoldingItem(symbol="INVALID SYMBOL!@#", weight=1.0)

    def test_portfolio_volatility_aligns_dates_across_exchanges(self):
        from app.routers.analytics_router import HoldingItem
        from app.services.analytics_service import AnalyticsService

        holdings = [
            HoldingItem(symbol="H1", weight=0.5),
            HoldingItem(symbol="H2", weight=0.5),
        ]

        weeks = {f"2024-W{w:02d}": (0.01 if w % 2 == 0 else -0.01) for w in range(1, 53)}
        opp_weeks = {k: -v for k, v in weeks.items()}
        with patch("app.services.analytics_service.AnalyticsService.fetch_etf_market_metrics") as mock_fetch:
            mock_fetch.side_effect = lambda symbol: {
                "H1": {
                    "symbol": "H1",
                    "annualReturn": 0.08,
                    "annualVolatility": 0.15,
                    "ter": 0.002,
                    "weeklyReturns": weeks,
                },
                "H2": {
                    "symbol": "H2",
                    "annualReturn": 0.08,
                    "annualVolatility": 0.15,
                    "ter": 0.002,
                    "weeklyReturns": opp_weeks,
                },
            }[symbol]

            res = AnalyticsService.calculate_portfolio_metrics(holdings)
            self.assertLess(res["weightedVolatility"], 0.05)

    def test_currency_code_regex_validation_in_yahoo_service(self):
        from fastapi import HTTPException
        from app.services.yahoo_service import YahooService

        with self.assertRaises(HTTPException) as ctx:
            YahooService.get_exchange_rate("US1", "PLN")
        self.assertEqual(ctx.exception.status_code, 400)

        with self.assertRaises(HTTPException) as ctx:
            YahooService.get_exchange_rate("USD", "POLISH")
        self.assertEqual(ctx.exception.status_code, 400)

        with self.assertRaises(HTTPException) as ctx:
            YahooService.get_exchange_rate("", "PLN")
        self.assertEqual(ctx.exception.status_code, 400)

        self.assertEqual(YahooService.get_exchange_rate("usd", "USD"), 1.0)


class TestSqliteRepositorySeam(unittest.TestCase):
    def test_repository_idempotent_creation_and_conflict_handling(self):
        import sqlite3
        from app.services.market_history_repo import MarketHistoryRepository

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn)
        repo.init_db()

        yesterday = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
        row = [{"date": yesterday, "close_price": 120.5, "volume": 50000}]
        repo.save_eod_prices_bulk("VWCE.DE", row, currency="EUR")
        repo.save_eod_prices_bulk("VWCE.DE", row, currency="EUR")

        prices = repo.get_historical_prices("VWCE.DE")
        self.assertEqual(len(prices), 1)
        self.assertEqual(prices[0]["closePrice"], 120.5)

    def test_repository_ignores_today_unclosed_session(self):
        import sqlite3
        from app.services.market_history_repo import MarketHistoryRepository

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn)
        repo.init_db()

        today = datetime.date.today().isoformat()
        saved = repo.save_eod_prices_bulk(
            "VUAA.DE", [{"date": today, "close_price": 95.0, "volume": 1000}], currency="EUR"
        )
        self.assertEqual(saved, 0)
        self.assertEqual(len(repo.get_historical_prices("VUAA.DE")), 0)

    def test_repository_save_eod_prices_bulk_handles_batch(self):
        import sqlite3
        from app.services.market_history_repo import MarketHistoryRepository

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn)
        repo.init_db()

        day1 = (datetime.date.today() - datetime.timedelta(days=3)).isoformat()
        day2 = (datetime.date.today() - datetime.timedelta(days=2)).isoformat()
        today = datetime.date.today().isoformat()

        batch = [
            {"date": day1, "close_price": 118.5, "volume": 10000},
            {"date": day2, "close_price": 119.2, "volume": 12000},
            {"date": today, "close_price": 120.0, "volume": 15000},
        ]

        count = repo.save_eod_prices_bulk("VWCE.DE", batch, currency="EUR")
        self.assertEqual(count, 2)

        prices = repo.get_historical_prices("VWCE.DE")
        self.assertEqual(len(prices), 2)
        self.assertEqual(prices[0]["closePrice"], 118.5)
        self.assertEqual(prices[1]["closePrice"], 119.2)


class TestPostgresRepositorySeam(unittest.TestCase):
    def test_repository_initialization_with_database_url_sets_postgres_mode(self):
        from unittest.mock import MagicMock, patch
        from app.services.market_history_repo import MarketHistoryRepository

        mock_psycopg2 = MagicMock()
        mock_pool = MagicMock()
        mock_psycopg2.pool = mock_pool

        with patch.dict("sys.modules", {"psycopg2": mock_psycopg2, "psycopg2.pool": mock_pool}):
            repo = MarketHistoryRepository(
                database_url="postgresql://postgres:postgres@localhost:5432/investing_assistant"
            )
            self.assertTrue(getattr(repo, "is_postgres", False))

    def test_repository_postgres_bulk_upsert_uses_on_conflict_update(self):
        from unittest.mock import MagicMock
        from app.services.market_history_repo import MarketHistoryRepository

        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.cursor.return_value = mock_cursor

        repo = MarketHistoryRepository(
            connection=mock_conn,
            dialect="postgres"
        )
        yesterday = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
        batch = [
            {"date": yesterday, "close_price": 120.5, "volume": 50000}
        ]
        repo.save_eod_prices_bulk("VWCE.DE", batch, currency="EUR")

        mock_cursor.executemany.assert_called()
        query = mock_cursor.executemany.call_args[0][0]

        self.assertIn("ON CONFLICT", query.upper())
        self.assertNotIn("INSERT OR REPLACE", query.upper())
        self.assertIn("%s", query)
        self.assertNotIn("?", query)

    def test_nbp_spot_rate_priority(self):
        from unittest.mock import MagicMock
        from app.services.yahoo_service import YahooService, _session
        from app.services.cache_service import cache_service

        cache_service.delete("rate:USD:PLN")
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"rates": [{"mid": 4.15}]}
        with patch.object(_session, "get", return_value=mock_resp) as mock_get:
            rate = YahooService.get_exchange_rate("USD", "PLN")
            self.assertEqual(rate, 4.15)
            mock_get.assert_called_once()
            self.assertIn("api.nbp.pl", mock_get.call_args[0][0])

    def test_single_price_raises_404_on_missing_symbol(self):
        from fastapi import HTTPException
        from app.routers.prices_router import get_price
        from app.services.cache_service import cache_service

        cache_service.delete("price:NONEXISTENT:base")
        with patch("app.services.yahoo_service.YahooService.get_current_price", side_effect=HTTPException(status_code=404, detail="Not Found")):
            with patch("app.services.market_history_repo.market_history_repo.get_max_date", return_value=None):
                with self.assertRaises(HTTPException) as ctx:
                    get_price("NONEXISTENT")
                self.assertEqual(ctx.exception.status_code, 404)

    def test_holding_item_validation_and_duplicate_symbol_consolidation(self):
        from pydantic import ValidationError
        from app.routers.analytics_router import HoldingItem
        from app.services.analytics_service import AnalyticsService

        trimmed = HoldingItem(symbol="  vwce.de ", weight=0.5, value=1000.0)
        self.assertEqual(trimmed.symbol, "VWCE.DE")

        with self.assertRaises(ValidationError):
            HoldingItem(symbol="VWCE.DE", weight=-0.1)
        with self.assertRaises(ValidationError):
            HoldingItem(symbol="VWCE.DE", value=float("nan"))

        holdings = [
            HoldingItem(symbol="VWCE.DE", weight=0.3, value=3000.0),
            HoldingItem(symbol="vwce.de", weight=0.2, value=2000.0),
            HoldingItem(symbol="VUAA.DE", weight=0.5, value=5000.0),
        ]
        mock_data = {
            "VWCE.DE": {
                "symbol": "VWCE.DE",
                "annualReturn": 0.1,
                "annualVolatility": 0.15,
                "ter": 0.0022,
                "trailingPe": 20.0,
                "dividendYield": 0.015,
                "beta": 1.0,
                "currency": None,
                "weeklyReturns": {},
            },
            "VUAA.DE": {
                "symbol": "VUAA.DE",
                "annualReturn": 0.12,
                "annualVolatility": 0.16,
                "ter": 0.0007,
                "trailingPe": 24.0,
                "dividendYield": 0.012,
                "beta": 1.05,
                "currency": "EUR",
                "weeklyReturns": {},
            },
        }
        with patch("app.services.analytics_service.AnalyticsService.fetch_etf_market_metrics", side_effect=lambda s: mock_data[s]):
            res = AnalyticsService.calculate_portfolio_metrics(holdings)
            self.assertEqual(len(res["instruments"]), 2)
            self.assertEqual(res["instruments"][0]["weight"], 0.5)
            self.assertEqual(res["instruments"][0]["value"], 5000.0)
            self.assertIsNone(res["instruments"][0]["currency"])
            self.assertAlmostEqual(res["weightedReturn"], 0.11, places=4)

    def test_ter_normalization_for_report_ratio_and_young_etf_history_checked_at(self):
        import sqlite3
        from unittest.mock import MagicMock
        import pandas as pd
        from app.services.analytics_service import AnalyticsService
        from app.services.cache_service import cache_service
        from app.services.market_history_repo import MarketHistoryRepository

        report_pct = AnalyticsService.extract_fund_metrics({"netExpenseRatio": 0.22})
        self.assertAlmostEqual(report_pct["ter"], 0.0022, places=6)

        with cache_service._mem_lock:
            cache_service._memory_cache.clear()
        cache_service.delete("analytics:etf:SHALLOW.DE")

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn)
        repo.init_db()
        d_old = (datetime.date.today() - datetime.timedelta(days=10)).isoformat()
        d_prev = (datetime.date.today() - datetime.timedelta(days=3)).isoformat()
        d_recent = (datetime.date.today() - datetime.timedelta(days=2)).isoformat()
        repo.save_eod_prices_bulk("SHALLOW.DE", [{"date": d_prev, "close_price": 100.0}], currency="EUR")
        repo.save_profile_metrics("SHALLOW.DE", 0.002, last_updated=d_old)

        start_dt = datetime.date.today() - datetime.timedelta(days=365 * 2)
        dates = pd.date_range(start=start_dt, end=d_recent, freq="W-FRI")
        idx = pd.to_datetime(dates)
        closes = [100.0 * (1.10 ** (i / 52.0)) for i in range(len(idx))]
        mock_ticker = MagicMock()
        mock_ticker.history.return_value = pd.DataFrame({"Close": closes, "Volume": [10] * len(idx)}, index=idx)
        mock_ticker.fast_info = MagicMock(currency="EUR")
        mock_ticker.info = {"netExpenseRatio": 0.25}

        fx_map = {d.strftime("%Y-%m-%d"): 4.0 for d in idx}
        with patch("app.services.analytics_service.market_history_repo", repo):
            with patch("app.services.analytics_service.YahooService.get_historical_fx_rates", return_value=fx_map):
                with patch("app.services.analytics_service.yf.Ticker", return_value=mock_ticker):
                    metrics = AnalyticsService.fetch_etf_market_metrics("SHALLOW.DE")
                    self.assertEqual(mock_ticker.history.call_args.kwargs.get("period"), "5y")
                    self.assertAlmostEqual(metrics["ter"], 0.0025, places=6)
                    self.assertAlmostEqual(metrics["annualReturn"], 0.1, delta=0.02)
                    self.assertIsNotNone(repo.get_profile_metrics("SHALLOW.DE")["historyCheckedAt"])

                    # Next day, young ETF must NOT re-fetch full 5y history
                    cache_service.delete("analytics:etf:SHALLOW.DE")
                    mock_ticker.history.reset_mock()
                    repo.save_profile_metrics("SHALLOW.DE", 0.0025, last_updated=d_old, history_checked_at=d_old)
                    AnalyticsService.fetch_etf_market_metrics("SHALLOW.DE")
                    if mock_ticker.history.called:
                        self.assertNotEqual(mock_ticker.history.call_args.kwargs.get("period"), "5y")

    def test_mixed_weight_and_value_holdings_do_not_zero_value_positions(self):
        from app.routers.analytics_router import HoldingItem
        from app.services.analytics_service import AnalyticsService

        mock_data = {
            "A": {
                "symbol": "A",
                "annualReturn": 0.10,
                "annualVolatility": 0.15,
                "ter": 0.002,
                "trailingPe": 20.0,
                "dividendYield": 0.01,
                "beta": 1.0,
                "currency": "USD",
                "weeklyReturns": {},
            },
            "B": {
                "symbol": "B",
                "annualReturn": 0.20,
                "annualVolatility": 0.20,
                "ter": 0.004,
                "trailingPe": 10.0,
                "dividendYield": 0.02,
                "beta": 1.2,
                "currency": "USD",
                "weeklyReturns": {},
            },
            "C": {
                "symbol": "C",
                "annualReturn": 0.30,
                "annualVolatility": 0.25,
                "ter": 0.006,
                "trailingPe": 15.0,
                "dividendYield": 0.03,
                "beta": 0.8,
                "currency": "USD",
                "weeklyReturns": {},
            },
        }
        with patch("app.services.analytics_service.AnalyticsService.fetch_etf_market_metrics", side_effect=lambda s: mock_data[s]):
            partial = AnalyticsService.calculate_portfolio_metrics([
                HoldingItem(symbol="A", weight=0.4),
                HoldingItem(symbol="B", value=3000.0),
                HoldingItem(symbol="C", value=3000.0),
            ])
            weights = {inst["symbol"]: inst["weight"] for inst in partial["instruments"]}
            self.assertAlmostEqual(weights["A"], 0.4, places=6)
            self.assertAlmostEqual(weights["B"], 0.3, places=6)
            self.assertAlmostEqual(weights["C"], 0.3, places=6)

            full_with_value_extra = AnalyticsService.calculate_portfolio_metrics([
                HoldingItem(symbol="A", weight=1.0, value=5000.0),
                HoldingItem(symbol="B", value=5000.0),
            ])
            w2 = {inst["symbol"]: inst["weight"] for inst in full_with_value_extra["instruments"]}
            self.assertAlmostEqual(w2["A"], 0.5, places=6)
            self.assertAlmostEqual(w2["B"], 0.5, places=6)

    def test_cache_get_or_set_validates_cached_entries(self):
        from app.services.cache_service import CacheService

        cache = CacheService()
        cache.redis_client = None
        cache.set("k:bad", "not-a-dict", expire=60)
        calls = 0

        def builder():
            nonlocal calls
            calls += 1
            return {"ok": True}

        val, hit = cache.get_or_set_with_hit(
            "k:bad",
            builder,
            ttl=60,
            validator=lambda c: isinstance(c, dict),
        )
        self.assertEqual(val, {"ok": True})
        self.assertFalse(hit)
        self.assertEqual(calls, 1)

        val2, hit2 = cache.get_or_set_with_hit(
            "k:bad",
            builder,
            ttl=60,
            validator=lambda c: isinstance(c, dict),
        )
        self.assertEqual(val2, {"ok": True})
        self.assertTrue(hit2)
        self.assertEqual(calls, 1)

    def test_partial_unquoted_instrument_preserves_quoted_correlations_and_zero_variance_safe(self):
        from app.routers.analytics_router import HoldingItem
        from app.services.analytics_service import AnalyticsService

        w_a = {f"2024-W{w:02d}": (0.02 if w % 2 == 0 else -0.01) for w in range(1, 55)}
        w_b = {f"2024-W{w:02d}": (0.04 if w % 2 == 0 else -0.02) for w in range(1, 55)}
        mock_data = {
            "A": {"symbol": "A", "annualReturn": 0.10, "annualVolatility": 0.15, "ter": 0.002, "currency": "USD", "weeklyReturns": w_a},
            "B": {"symbol": "B", "annualReturn": 0.12, "annualVolatility": 0.18, "ter": 0.001, "currency": "USD", "weeklyReturns": w_b},
            "UNQUOTED": {"symbol": "UNQUOTED", "annualReturn": None, "annualVolatility": None, "ter": None, "currency": "PLN", "weeklyReturns": {}},
            "FLAT": {"symbol": "FLAT", "annualReturn": 0.0, "annualVolatility": 0.0, "ter": None, "currency": "USD", "weeklyReturns": {k: 0.0 for k in w_a}},
        }
        with patch("app.services.analytics_service.AnalyticsService.fetch_etf_market_metrics", side_effect=lambda s: mock_data[s]):
            res = AnalyticsService.calculate_portfolio_metrics([
                HoldingItem(symbol="A", weight=0.4),
                HoldingItem(symbol="B", weight=0.4),
                HoldingItem(symbol="UNQUOTED", weight=0.1),
                HoldingItem(symbol="FLAT", weight=0.1),
            ])
            corr = res["correlationMatrix"]
            self.assertGreater(corr["A"]["B"], 0.9)
            self.assertEqual(corr["A"]["UNQUOTED"], 0.0)
            self.assertEqual(corr["UNQUOTED"]["UNQUOTED"], 1.0)
            self.assertEqual(corr["A"]["FLAT"], 0.0)
            self.assertEqual(corr["FLAT"]["FLAT"], 1.0)
            self.assertIsNotNone(res["weightedVolatility"])

    def test_explicit_zero_weight_and_short_history_returns_none(self):
        from app.services.analytics_service import AnalyticsService, _build_weekly_pln_log_returns, _compute_weekly_return_and_volatility

        weights = AnalyticsService._resolve_portfolio_weights([
            {"symbol": "A", "weight": 0.0, "has_weight": True, "value": None},
            {"symbol": "B", "weight": 0.0, "has_weight": False, "value": None},
        ])
        self.assertEqual(weights, [0.0, 1.0])

        short_weekly = _build_weekly_pln_log_returns([
            {"date": "2025-01-03", "closePrice": 100.0},
            {"date": "2025-01-10", "closePrice": 110.0},
        ], "PLN")
        ann_ret, ann_vol = _compute_weekly_return_and_volatility(short_weekly)
        self.assertIsNone(ann_ret)
        self.assertIsNone(ann_vol)

    def test_fx_variance_included_in_pln_weekly_series_and_psd_covariance(self):
        import numpy as np
        from app.services.analytics_service import _build_weekly_pln_log_returns, _compute_portfolio_risk

        start = datetime.date(2024, 1, 5)
        prices = []
        fx_map = {}
        for w in range(60):
            d = (start + datetime.timedelta(weeks=w)).isoformat()
            prices.append({"date": d, "closePrice": 100.0})
            fx_map[d] = 4.0 * (1.0 + (0.03 if w % 2 == 0 else -0.02))

        with patch("app.services.analytics_service.YahooService.get_historical_fx_rates", return_value=fx_map):
            weekly_pln = _build_weekly_pln_log_returns(prices, "USD")
        self.assertGreater(len(weekly_pln), 50)
        self.assertGreater(float(np.std(list(weekly_pln.values()), ddof=1)), 0.01)

        vol, corr, cov = _compute_portfolio_risk(
            [{"symbol": "A", "annualVolatility": 0.2}, {"symbol": "B", "annualVolatility": 0.2}],
            [weekly_pln, weekly_pln],
            [0.5, 0.5],
        )
        self.assertIsNotNone(vol)
        eigvals = np.linalg.eigvalsh(np.array([[cov["A"]["A"], cov["A"]["B"]], [cov["B"]["A"], cov["B"]["B"]]]))
        self.assertTrue(np.all(eigvals >= -1e-9))

    def test_monte_carlo_deterministic_proxy_warnings_and_tax_real_ordering(self):
        import asyncio
        from app.routers.analytics_router import MonteCarloRequest, calculate_monte_carlo
        from app.services.monte_carlo_service import run_monte_carlo

        w_iwda = {f"2025-W{w:02d}": (0.015 if w % 2 == 0 else -0.011) for w in range(1, 55)}
        mock_metrics = {
            "IWDA.AS": {
                "symbol": "IWDA.AS",
                "annualReturn": 0.09,
                "annualVolatility": 0.15,
                "ter": 0.002,
                "currency": "PLN",
                "weeklyReturns": w_iwda,
            },
            "SHORT.DE": {
                "symbol": "SHORT.DE",
                "annualReturn": None,
                "annualVolatility": None,
                "ter": 0.001,
                "currency": "PLN",
                "weeklyReturns": {},
            },
        }
        base_req = {
            "horizonYears": 5,
            "startDate": "2026-01-01",
            "equityHoldings": [
                {"symbol": "IWDA.AS", "categoryCode": "MSCI_WORLD", "valueBase": 20000.0},
                {"symbol": "SHORT.DE", "categoryCode": "SP500", "valueBase": 10000.0},
            ],
            "bondSeed": {
                "startFixedValue": 10000.0,
                "startIndexedValue": 10000.0,
                "fixedRatePct": 6.0,
                "indexedRatePct": 5.5,
                "avgRemainingMonths": 24,
            },
            "startInvestedCapital": 40000.0,
            "monthlyDca": 1000.0,
            "dcaEquityShare": 0.7,
            "taxMode": "BRUTTO",
            "realMode": False,
            "bondRollover": True,
            "cpiPct": 3.5,
            "targetGoal": 120000.0,
            "seed": 42,
        }

        with patch("app.services.analytics_service.AnalyticsService.fetch_etf_market_metrics", side_effect=lambda s: mock_metrics[s]):
            brutto_nom_1 = run_monte_carlo(base_req)
            brutto_nom_2 = run_monte_carlo(base_req)
            netto_nom = run_monte_carlo({**base_req, "taxMode": "NETTO"})
            brutto_real = run_monte_carlo({**base_req, "realMode": True})
            netto_real = run_monte_carlo({**base_req, "taxMode": "NETTO", "realMode": True})
            no_rollover = run_monte_carlo({**base_req, "bondRollover": False, "equityHoldings": [], "dcaEquityShare": 0.0, "monthlyDca": 0.0})
            with_rollover = run_monte_carlo({**base_req, "bondRollover": True, "equityHoldings": [], "dcaEquityShare": 0.0, "monthlyDca": 0.0})

            http_resp = asyncio.run(calculate_monte_carlo(MonteCarloRequest(**base_req)))

        self.assertEqual(http_resp["summary"], brutto_nom_1["summary"])
        self.assertEqual(brutto_nom_1, brutto_nom_2)
        self.assertEqual(len(brutto_nom_1["points"]), 60)
        self.assertTrue(any("SHORT.DE" in w for w in brutto_nom_1["proxyWarnings"]))

        for p in brutto_nom_1["points"]:
            self.assertLessEqual(p["p10"], p["p50"])
            self.assertLessEqual(p["p50"], p["p90"])

        self.assertIsInstance(brutto_nom_1["summary"]["goalProbability"], int)
        self.assertGreaterEqual(brutto_nom_1["summary"]["goalProbability"], 0)
        self.assertLessEqual(brutto_nom_1["summary"]["goalProbability"], 100)

        self.assertLess(netto_nom["summary"]["terminalP50"], brutto_nom_1["summary"]["terminalP50"])
        self.assertLess(brutto_real["summary"]["terminalP50"], brutto_nom_1["summary"]["terminalP50"])
        self.assertLess(netto_real["summary"]["terminalP50"], brutto_real["summary"]["terminalP50"])
        self.assertLess(no_rollover["summary"]["terminalP50"], with_rollover["summary"]["terminalP50"])


if __name__ == "__main__":
    unittest.main()
