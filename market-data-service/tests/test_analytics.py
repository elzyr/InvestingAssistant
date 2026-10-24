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
            benchmarkSymbol="SPY"
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
                    "dailyReturns": [0.01, -0.005, 0.002, 0.004, -0.001]
                },
                "VUAA.DE": {
                    "symbol": "VUAA.DE",
                    "annualReturn": 0.11,
                    "annualVolatility": 0.16,
                    "ter": 0.0007,
                    "trailingPe": 24.0,
                    "dividendYield": 0.012,
                    "beta": 1.05,
                    "dailyReturns": [0.012, -0.006, 0.003, 0.005, -0.002]
                }
            }[symbol]

            response = asyncio.run(calculate_portfolio_stats(request))
            res = response.model_dump() if hasattr(response, "model_dump") else response.dict()

            self.assertIn("weightedReturn", res)
            self.assertIn("weightedVolatility", res)
            self.assertIn("weightedTer", res)
            self.assertIn("weightedPe", res)
            self.assertIn("portfolioBeta", res)

            # Weighted TER: 0.6 * 0.0022 + 0.4 * 0.0007 = 0.0016
            self.assertAlmostEqual(res["weightedTer"], 0.0016, places=4)
            # Weighted Return: 0.6 * 0.09 + 0.4 * 0.11 = 0.098
            self.assertAlmostEqual(res["weightedReturn"], 0.098, places=4)
            # Weighted P/E: 0.6 * 21.0 + 0.4 * 24.0 = 22.2
            self.assertAlmostEqual(res["weightedPe"], 22.2, places=2)

    def test_ucits_fallback_when_yahoo_metadata_missing(self):
        from app.services.analytics_service import AnalyticsService

        raw_info = {"symbol": "VWCE.DE", "shortName": "Vanguard FTSE All-World"}
        metrics = AnalyticsService.enrich_with_ucits_fallback("VWCE.DE", raw_info)

        self.assertEqual(metrics["ter"], 0.0022)
        self.assertGreater(metrics["trailingPe"], 0)


class TestSqliteRepositorySeam(unittest.TestCase):
    def test_repository_idempotent_creation_and_conflict_handling(self):
        import sqlite3
        from app.services.market_history_repo import MarketHistoryRepository

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn)
        repo.init_db()

        yesterday = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
        repo.save_eod_price("VWCE.DE", yesterday, 120.5, "EUR", 50000)
        # Duplicate row must not crash
        repo.save_eod_price("VWCE.DE", yesterday, 120.5, "EUR", 50000)

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
        saved = repo.save_eod_price("VUAA.DE", today, 95.0, "EUR", 1000)
        self.assertFalse(saved)
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
            {"date": today, "close_price": 120.0, "volume": 15000}, # Unclosed session, should be filtered
        ]

        count = repo.save_eod_prices_bulk("VWCE.DE", batch, currency="EUR")
        self.assertEqual(count, 2)

        prices = repo.get_historical_prices("VWCE.DE")
        self.assertEqual(len(prices), 2)
        self.assertEqual(prices[0]["closePrice"], 118.5)
        self.assertEqual(prices[1]["closePrice"], 119.2)


if __name__ == "__main__":
    unittest.main()
