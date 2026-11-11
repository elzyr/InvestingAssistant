import datetime
import json
import math
import sqlite3
import unittest
from unittest.mock import MagicMock, patch

import numpy as np
import pandas as pd


class _FakeRedis:
    def __init__(self):
        self.store = {}
        self.mget_calls = 0
        self.pipeline_calls = 0

    def get(self, key):
        return self.store.get(key)

    def set(self, key, value, ex=None):
        self.store[key] = value

    def mget(self, keys):
        self.mget_calls += 1
        return [self.store.get(k) for k in keys]

    def delete(self, key):
        self.store.pop(key, None)

    def pipeline(self, transaction=False):
        self.pipeline_calls += 1
        parent = self

        class _Pipe:
            def __init__(self):
                self.ops = []

            def setex(self, k, ex, val):
                self.ops.append((k, val))

            def execute(self):
                for k, val in self.ops:
                    parent.store[k] = val
                return [True] * len(self.ops)

        return _Pipe()


class TestCacheServiceFixes(unittest.TestCase):
    def test_get_lock_never_evicts_held_lock(self):
        from app.services.cache_service import CacheService, MAX_CACHE_ENTRIES

        cs = CacheService()
        held_lock = cs.get_lock("held-key")
        held_lock.acquire()
        try:
            for i in range(MAX_CACHE_ENTRIES + 10):
                cs.get_lock(f"key-{i}")
            self.assertIs(cs.get_lock("held-key"), held_lock)
        finally:
            held_lock.release()

    def test_mget_and_mset_pipeline_with_redis_and_fallback(self):
        from app.services.cache_service import CacheService

        cs = CacheService()
        fake = _FakeRedis()
        cs.redis_client = fake

        cs.mset_pipeline({"k1": {"p": 1.5}, "k2": {"p": 2.5}}, expire=60)
        self.assertEqual(fake.pipeline_calls, 1)
        got = cs.mget(["k1", "k2", "k3"])
        self.assertEqual(fake.mget_calls, 1)
        self.assertEqual(got, {"k1": {"p": 1.5}, "k2": {"p": 2.5}})

        # Simulate Redis outage -> memory fallback
        broken = MagicMock()
        broken.setex.side_effect = ConnectionError("down")
        broken.pipeline.side_effect = ConnectionError("down")
        broken.mget.side_effect = ConnectionError("down")
        broken.get.side_effect = ConnectionError("down")
        broken.set.side_effect = ConnectionError("down")
        cs.redis_client = broken

        cs.mset_pipeline({"fb1": {"p": 9.9}}, expire=60)
        self.assertEqual(cs.mget(["fb1", "missing"]), {"fb1": {"p": 9.9}})

        # Recover Redis and overwrite fb1 -> stale memory cache entry is cleared
        cs.redis_client = fake
        cs.set("fb1", {"p": 10.1}, expire=60)
        self.assertNotIn("fb1", cs._memory_cache)


class TestPricesRouterFixes(unittest.TestCase):
    def setUp(self):
        from app.services.cache_service import cache_service

        self.fake_redis = _FakeRedis()
        self.orig_redis = cache_service.redis_client
        cache_service.redis_client = self.fake_redis
        with cache_service._mem_lock:
            cache_service._memory_cache.clear()

    def tearDown(self):
        from app.services.cache_service import cache_service

        cache_service.redis_client = self.orig_redis

    def test_batch_prices_deduplicates_uses_mget_and_pipelines_misses(self):
        from app.routers.prices_router import BatchPricesRequest, get_batch_prices

        self.fake_redis.store["price:AAPL:USD"] = json.dumps({
            "price": 150.0,
            "previousClose": 149.0,
            "change": 1.0,
            "changePercent": 0.67,
            "currency": "USD",
        })

        with patch("app.services.yahoo_service.YahooService.get_current_price") as mock_price:
            mock_price.return_value = {
                "price": 200.0,
                "previousClose": 198.0,
                "change": 2.0,
                "changePercent": 1.01,
                "currency": "USD",
            }
            req = BatchPricesRequest(symbols=["aapl", "MSFT", "AAPL", "msft"], currency="USD")
            res = get_batch_prices(req)

            self.assertEqual(set(res.keys()), {"AAPL", "MSFT"})
            self.assertEqual(res["AAPL"]["source"], "cache")
            self.assertEqual(res["MSFT"]["source"], "yahoo_finance")
            mock_price.assert_called_once_with("MSFT", "USD")
            self.assertEqual(self.fake_redis.mget_calls, 1)
            self.assertEqual(self.fake_redis.pipeline_calls, 1)
            self.assertIn("price:MSFT:USD", self.fake_redis.store)

    def test_batch_prices_validation_rejects_oversized_or_invalid_symbols(self):
        from pydantic import ValidationError
        from app.routers.prices_router import BatchPricesRequest

        with self.assertRaises(ValidationError):
            BatchPricesRequest(symbols=[f"SYM{i}" for i in range(101)])

        with self.assertRaises(ValidationError):
            BatchPricesRequest(symbols=["INVALID SYMBOL!"])

    def test_treasury_bond_exact_regex_and_currency_conversion(self):
        from app.routers.prices_router import _is_treasury_bond, _fetch_single_price

        self.assertTrue(_is_treasury_bond("EDO1034"))
        self.assertTrue(_is_treasury_bond("coi0528"))
        self.assertFalse(_is_treasury_bond("EDOX"))
        self.assertFalse(_is_treasury_bond("TOST"))
        self.assertFalse(_is_treasury_bond("EDO10345"))

        with patch("app.services.yahoo_service.YahooService.get_exchange_rate", return_value=0.25) as mock_fx:
            quote = _fetch_single_price("EDO1034", "EUR")
            self.assertEqual(quote["price"], 25.0)
            self.assertEqual(quote["currency"], "EUR")
            self.assertEqual(quote["source"], "treasury_bond")
            mock_fx.assert_called_once_with("PLN", "EUR")

    def test_history_start_date_db_hit_converts_currency_and_skips_incomplete_db(self):
        from app.routers.prices_router import get_history

        today = datetime.date.today()
        d1 = (today - datetime.timedelta(days=5)).isoformat()
        d2 = (today - datetime.timedelta(days=1)).isoformat()

        complete_rows = [
            {"symbol": "VWCE.DE", "date": d1, "closePrice": 100.0, "close": 100.0, "currency": "EUR", "volume": 1000},
            {"symbol": "VWCE.DE", "date": d2, "closePrice": 110.0, "close": 110.0, "currency": "EUR", "volume": 1200},
        ]

        with patch("app.services.market_history_repo.market_history_repo.get_historical_prices", return_value=complete_rows):
            with patch(
                "app.services.yahoo_service.YahooService.get_historical_fx_rates",
                return_value={d1: 4.2, d2: 4.3},
            ):
                res = get_history(symbol="VWCE.DE", period="1mo", interval="1d", currency="PLN", start_date=d1)
                self.assertEqual(res["source"], "database")
                self.assertEqual(res["currency"], "PLN")
                self.assertAlmostEqual(res["history"][0]["closePrice"], 420.0)
                self.assertAlmostEqual(res["history"][1]["closePrice"], 473.0)

        # Incomplete DB (stale end date) should NOT short-circuit; must fetch from Yahoo
        stale_start = (today - datetime.timedelta(days=60)).isoformat()
        stale_end = (today - datetime.timedelta(days=30)).isoformat()
        incomplete_rows = [
            {"symbol": "AAPL", "date": stale_start, "closePrice": 150.0, "close": 150.0, "currency": "USD", "volume": 1000},
            {"symbol": "AAPL", "date": stale_end, "closePrice": 155.0, "close": 155.0, "currency": "USD", "volume": 1000},
        ]
        with patch("app.services.market_history_repo.market_history_repo.get_historical_prices", return_value=incomplete_rows):
            with patch("app.services.yahoo_service.YahooService.get_historical_data") as mock_hist:
                mock_hist.return_value = {
                    "currency": "USD",
                    "history": [{"date": d2, "open": 160.0, "high": 162.0, "low": 159.0, "close": 161.0, "volume": 500}],
                }
                with patch("app.services.market_history_repo.market_history_repo.save_eod_prices_bulk"):
                    res = get_history(symbol="AAPL", period="3mo", interval="1d", currency=None, start_date=stale_start)
                    self.assertEqual(res["source"], "yahoo_finance")
                    mock_hist.assert_called_once()

    def test_history_db_fallback_converts_currency(self):
        from app.routers.prices_router import get_history

        d1 = (datetime.date.today() - datetime.timedelta(days=20)).isoformat()
        db_rows = [
            {"symbol": "VOD.L", "date": d1, "closePrice": 100.0, "close": 100.0, "currency": "", "volume": 500},
        ]
        with patch("app.services.yahoo_service.YahooService.get_historical_data", side_effect=RuntimeError("boom")):
            with patch("app.services.market_history_repo.market_history_repo.get_historical_prices", return_value=db_rows):
                with patch("app.services.yahoo_service.YahooService.get_historical_fx_rates", return_value={d1: 5.0}):
                    res = get_history(symbol="VOD.L", period="1mo", interval="1d", currency="PLN", start_date=None)
                    self.assertEqual(res["source"], "db_fallback")
                    self.assertEqual(res["currency"], "PLN")
                    self.assertAlmostEqual(res["history"][0]["close"], 500.0)


class TestYahooServiceFixes(unittest.TestCase):
    def test_intraday_interval_preserves_timestamps_and_handles_inf_volume(self):
        from app.services.yahoo_service import YahooService

        idx = pd.to_datetime(["2025-02-03 10:00:00", "2025-02-03 11:00:00"])
        df = pd.DataFrame(
            {
                "Open": [10.0, 11.0],
                "High": [10.5, 11.5],
                "Low": [9.5, 10.5],
                "Close": [10.2, 11.2],
                "Volume": [100.0, float("inf")],
            },
            index=idx,
        )
        mock_ticker = MagicMock()
        mock_ticker.history.return_value = df
        mock_ticker.fast_info = MagicMock(currency="USD")

        with patch("app.services.yahoo_service.yf.Ticker", return_value=mock_ticker):
            res = YahooService.get_historical_data("AAPL", period="5d", interval="1h")
            self.assertEqual(res["history"][0]["date"], "2025-02-03T10:00:00")
            self.assertEqual(res["history"][1]["date"], "2025-02-03T11:00:00")
            self.assertEqual(res["history"][1]["volume"], 0)


class TestAnalyticsServiceFixes(unittest.TestCase):
    def setUp(self):
        from app.services.cache_service import cache_service

        self.fake_redis = _FakeRedis()
        self.orig_redis = cache_service.redis_client
        cache_service.redis_client = self.fake_redis

    def tearDown(self):
        from app.services.cache_service import cache_service

        cache_service.redis_client = self.orig_redis

    def test_zero_close_price_does_not_cause_div_by_zero_or_nan(self):
        from app.services.analytics_service import AnalyticsService

        d1 = (datetime.date.today() - datetime.timedelta(days=3)).isoformat()
        d2 = (datetime.date.today() - datetime.timedelta(days=2)).isoformat()
        d3 = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()

        prices = [
            {"date": d1, "closePrice": 0.0, "currency": "USD"},
            {"date": d2, "closePrice": 100.0, "currency": "USD"},
            {"date": d3, "closePrice": 105.0, "currency": "USD"},
        ]

        with patch("app.services.market_history_repo.market_history_repo.get_max_date", return_value=d3):
            with patch(
                "app.services.market_history_repo.market_history_repo.get_profile_metrics",
                return_value={"ter": None, "trailingPe": None, "dividendYield": None, "beta": None, "lastUpdated": datetime.date.today().isoformat()},
            ):
                with patch("app.services.market_history_repo.market_history_repo.get_historical_prices", return_value=prices):
                    metrics = AnalyticsService.fetch_etf_market_metrics("TEST")
                    self.assertTrue(math.isfinite(metrics["annualReturn"]))
                    self.assertNotIn(d2, metrics["dailyReturns"])
                    self.assertAlmostEqual(metrics["dailyReturns"][d3], 0.05)

    def test_profile_metrics_keep_none_instead_of_poisoning_with_zeros_and_caches(self):
        from app.services.analytics_service import AnalyticsService
        from app.services.market_history_repo import MarketHistoryRepository

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn)
        repo.init_db()

        d1 = (datetime.date.today() - datetime.timedelta(days=2)).isoformat()
        d2 = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
        idx = pd.to_datetime([d1, d2])
        hist_df = pd.DataFrame({"Close": [100.0, 102.0], "Volume": [1000, 1100]}, index=idx)

        mock_ticker = MagicMock()
        mock_ticker.history.return_value = hist_df
        mock_ticker.fast_info = MagicMock(currency="USD")
        mock_ticker.info = {"trailingPE": 18.5}  # ter, dividendYield, beta missing -> must stay None

        with patch("app.services.analytics_service.market_history_repo", repo):
            with patch("app.services.analytics_service.yf.Ticker", return_value=mock_ticker) as mock_yf:
                m1 = AnalyticsService.fetch_etf_market_metrics("UNKNOWN.US")
                self.assertEqual(m1["trailingPe"], 18.5)
                self.assertIsNone(m1["ter"])
                self.assertIsNone(m1["dividendYield"])
                self.assertIsNone(m1["beta"])

                saved_profile = repo.get_profile_metrics("UNKNOWN.US")
                self.assertIsNone(saved_profile["ter"])
                self.assertIsNone(saved_profile["dividendYield"])
                self.assertIsNone(saved_profile["beta"])
                self.assertEqual(saved_profile["trailingPe"], 18.5)

                # Second call must hit Redis cache and not call yfinance again
                m2 = AnalyticsService.fetch_etf_market_metrics("UNKNOWN.US")
                self.assertEqual(m2["trailingPe"], 18.5)
                mock_yf.assert_called_once()


class TestInflationServiceFixes(unittest.TestCase):
    def setUp(self):
        from app.services.cache_service import cache_service

        self.fake_redis = _FakeRedis()
        self.orig_redis = cache_service.redis_client
        cache_service.redis_client = self.fake_redis

    def tearDown(self):
        from app.services.cache_service import cache_service

        cache_service.redis_client = self.orig_redis

    def test_monthly_cpi_uses_redis_and_does_not_rescrape_on_missing_year(self):
        from app.services.inflation_service import InflationService

        sample_rows = [
            {"year": 2025, "month": 1, "rate": 4.9, "source": "GUS"},
            {"year": 2025, "month": 2, "rate": 4.9, "source": "GUS"},
        ]
        with patch("app.services.market_history_repo.market_history_repo.get_cpi_records", return_value=sample_rows) as mock_db:
            with patch("app.services.inflation_service.InflationService.sync_inflation_from_upstream") as mock_sync:
                res_missing_year = InflationService.get_monthly_cpi(year=1900)
                self.assertEqual(res_missing_year, [])
                mock_sync.assert_not_called()

                res_2025 = InflationService.get_monthly_cpi(year=2025)
                self.assertEqual(len(res_2025), 2)
                mock_db.assert_called_once()

    def test_get_cpi_sync_cooldown_prevents_repeated_upstream_scrapes(self):
        from app.services.inflation_service import InflationService

        old_latest = {"year": 2020, "month": 12, "rate": 2.4, "source": "GUS"}
        with patch("app.services.market_history_repo.market_history_repo.get_latest_cpi", return_value=old_latest):
            with patch("app.services.market_history_repo.market_history_repo.get_cpi_records", return_value=[old_latest]):
                with patch("app.services.inflation_service._http_session.get", side_effect=RuntimeError("offline")) as mock_get:
                    InflationService.get_cpi()
                    first_count = mock_get.call_count
                    self.assertGreater(first_count, 0)

                    # Invalidate summary cache only, keep cooldown -> must NOT re-scrape upstream
                    self.fake_redis.delete("macro:inflation:cpi")
                    InflationService.get_cpi()
                    self.assertEqual(mock_get.call_count, first_count)


if __name__ == "__main__":
    unittest.main()
