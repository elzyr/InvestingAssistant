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

            def set(self, k, val, ex=None):
                self.ops.append((k, val, ex))

            def execute(self):
                for k, val, _ in self.ops:
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

        self.fake_redis.store["price:AAPL"] = json.dumps({
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
            req = BatchPricesRequest(symbols=["aapl", "MSFT", "AAPL", "msft"])
            res = get_batch_prices(req)

            self.assertEqual(set(res.keys()), {"AAPL", "MSFT"})
            self.assertEqual(res["AAPL"]["source"], "cache")
            self.assertEqual(res["MSFT"]["source"], "yahoo_finance")
            mock_price.assert_called_once_with("MSFT")
            self.assertEqual(self.fake_redis.mget_calls, 1)
            self.assertEqual(self.fake_redis.pipeline_calls, 1)
            self.assertIn("price:MSFT", self.fake_redis.store)

    def test_batch_prices_validation_rejects_oversized_or_invalid_symbols(self):
        from pydantic import ValidationError
        from app.routers.prices_router import BatchPricesRequest

        with self.assertRaises(ValidationError):
            BatchPricesRequest(symbols=[f"SYM{i}" for i in range(101)])

        with self.assertRaises(ValidationError):
            BatchPricesRequest(symbols=["INVALID SYMBOL!"])

    def test_batch_prices_marks_failed_symbol_unavailable(self):
        from app.routers.prices_router import BatchPricesRequest, get_batch_prices

        with patch("app.services.yahoo_service.YahooService.get_current_price", side_effect=RuntimeError("down")):
            with patch("app.services.market_history_repo.market_history_repo.get_max_date", return_value=None):
                res = get_batch_prices(BatchPricesRequest(symbols=["AAPL"]))
                self.assertTrue(res["AAPL"]["isUnavailable"])
                self.assertIsNone(res["AAPL"]["price"])
                self.assertIsNone(res["AAPL"]["currency"])
                self.assertNotIn("price:AAPL", self.fake_redis.store)

    def test_history_start_date_db_hit_returns_native_and_skips_incomplete_db(self):
        from app.routers.prices_router import _fetch_symbol_history

        today = datetime.date.today()
        d1 = (today - datetime.timedelta(days=5)).isoformat()
        d2 = (today - datetime.timedelta(days=1)).isoformat()

        complete_rows = [
            {"symbol": "VWCE.DE", "date": d1, "closePrice": 100.0, "close": 100.0, "currency": "EUR", "volume": 1000},
            {"symbol": "VWCE.DE", "date": d2, "closePrice": 110.0, "close": 110.0, "currency": "EUR", "volume": 1200},
        ]

        with patch("app.services.market_history_repo.market_history_repo.get_historical_prices", return_value=complete_rows):
            res = _fetch_symbol_history(
                symbol="VWCE.DE", period="1mo", interval="1d", start_date=d1
            )
            self.assertEqual(res["source"], "database")
            self.assertEqual(res["currency"], "EUR")
            self.assertAlmostEqual(res["history"][0]["closePrice"], 100.0)
            self.assertAlmostEqual(res["history"][1]["closePrice"], 110.0)

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
                with patch("app.services.market_history_repo.market_history_repo.save_eod_prices_bulk") as mock_save:
                    res = _fetch_symbol_history(
                        symbol="AAPL", period="3mo", interval="1d", start_date=stale_start
                    )
                    self.assertEqual(res["source"], "yahoo_finance")
                    mock_hist.assert_called_once()
                    mock_save.assert_called_once_with("AAPL", res["history"], "USD")

    def test_history_db_fallback_preserves_native_currency_and_fails_on_missing_currency(self):
        from fastapi import HTTPException
        from app.routers.prices_router import _fetch_symbol_history

        d1 = (datetime.date.today() - datetime.timedelta(days=20)).isoformat()
        db_rows = [
            {"symbol": "VOD.L", "date": d1, "closePrice": 1.0, "close": 1.0, "currency": "GBP", "volume": 500},
        ]
        with patch("app.services.yahoo_service.YahooService.get_historical_data", side_effect=RuntimeError("boom")):
            with patch("app.services.market_history_repo.market_history_repo.get_historical_prices", return_value=db_rows):
                res = _fetch_symbol_history(
                    symbol="VOD.L", period="1mo", interval="1d", start_date=None
                )
                self.assertEqual(res["source"], "db_fallback")
                self.assertEqual(res["currency"], "GBP")
                self.assertAlmostEqual(res["history"][0]["close"], 1.0)
                self.assertIsNone(res["history"][0]["open"])

            with patch(
                "app.services.market_history_repo.market_history_repo.get_historical_prices",
                return_value=[{"symbol": "VOD.L", "date": d1, "closePrice": 100.0, "close": 100.0, "currency": "", "volume": 500}],
            ):
                with self.assertRaises(HTTPException) as ctx:
                    _fetch_symbol_history(symbol="VOD.L", period="1mo", interval="1d", start_date=None)
                self.assertEqual(ctx.exception.status_code, 503)


class TestYahooServiceFixes(unittest.TestCase):
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

    def test_gbp_pence_currency_divides_prices_by_100_in_spot_and_history(self):
        from app.services.yahoo_service import YahooService

        idx = pd.to_datetime(["2025-02-03", "2025-02-04"])
        df = pd.DataFrame(
            {
                "Open": [8500.0, 8600.0],
                "High": [8550.0, 8650.0],
                "Low": [8450.0, 8550.0],
                "Close": [8520.0, 8620.0],
                "Volume": [100, 120],
            },
            index=idx,
        )
        mock_ticker = MagicMock()
        mock_ticker.fast_info = MagicMock(currency="GBp", last_price=8620.0, previous_close=8520.0)
        mock_ticker.history.return_value = df

        with patch("app.services.yahoo_service.yf.Ticker", return_value=mock_ticker):
            spot = YahooService.get_current_price("VWRL.L")
            self.assertEqual(spot["currency"], "GBP")
            self.assertAlmostEqual(spot["price"], 86.2)
            self.assertAlmostEqual(spot["previousClose"], 85.2)

            hist = YahooService.get_historical_data("VWRL.L", period="5d", interval="1d")
            self.assertEqual(hist["currency"], "GBP")
            self.assertAlmostEqual(hist["history"][0]["close"], 85.2)
            self.assertAlmostEqual(hist["history"][1]["close"], 86.2)

    def test_batch_history_and_batch_fx_endpoints(self):
        from app.routers.prices_router import BatchHistoryRequest, get_batch_history
        from app.routers.currencies_router import BatchFxHistoryRequest, CurrencyPairItem, get_batch_fx_history

        req = BatchHistoryRequest(symbols=["VWCE.DE", "FAIL"], start_date="2025-01-01")

        def fake_fetch(sym, period, interval, start_date):
            if sym == "FAIL":
                raise RuntimeError("boom")
            return {"symbol": sym, "currency": "EUR", "history": [{"date": "2025-01-02", "close": 120.5}], "source": "cache"}

        with patch("app.routers.prices_router._fetch_symbol_history", side_effect=fake_fetch):
            res = get_batch_history(req)
            self.assertEqual(set(res.keys()), {"VWCE.DE"})
            self.assertEqual(res["VWCE.DE"]["history"][0]["close"], 120.5)

        fx_req = BatchFxHistoryRequest(
            pairs=[
                CurrencyPairItem(from_currency="eur", to_currency="pln"),
                CurrencyPairItem(from_currency="EUR", to_currency="PLN"),
                CurrencyPairItem(from_currency="PLN", to_currency="PLN"),
            ],
            start_date="2025-01-01",
        )
        with patch("app.routers.currencies_router.YahooService.get_exchange_rate", return_value=4.28):
            with patch("app.routers.currencies_router.YahooService.get_historical_fx_rates", return_value={"2025-01-02": 4.26}):
                fx_res = get_batch_fx_history(fx_req)
                self.assertEqual(set(fx_res.keys()), {"EUR:PLN", "PLN:PLN"})
                self.assertAlmostEqual(fx_res["EUR:PLN"]["spotRate"], 4.28)
                self.assertAlmostEqual(fx_res["EUR:PLN"]["rates"]["2025-01-02"], 4.26)
                self.assertAlmostEqual(fx_res["PLN:PLN"]["spotRate"], 1.0)


    def test_gbx_currency_and_db_fallback_scale_by_100(self):
        from app.services.yahoo_service import normalize_quote_currency
        from app.routers.prices_router import _build_db_price_fallback

        self.assertEqual(normalize_quote_currency("GBx", "VWRL.L"), ("GBP", 0.01))
        self.assertEqual(normalize_quote_currency("GBp", "VWRL.L"), ("GBP", 0.01))

        d1 = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
        with patch("app.services.market_history_repo.market_history_repo.get_max_date", return_value=d1):
            with patch(
                "app.services.market_history_repo.market_history_repo.get_historical_prices",
                return_value=[{"date": d1, "closePrice": 8500.0, "close": 8500.0, "currency": "GBp"}],
            ):
                quote = _build_db_price_fallback("VWRL.L")
                self.assertEqual(quote["currency"], "GBP")
                self.assertAlmostEqual(quote["price"], 85.0)

    def test_db_history_completeness_rejects_large_mid_range_gaps_and_fallback_respects_period(self):
        from app.routers.prices_router import _fetch_symbol_history, _is_db_history_complete

        today = datetime.date.today()
        req_start = (today - datetime.timedelta(days=40)).isoformat()
        gapped = [
            {"date": (today - datetime.timedelta(days=39)).isoformat(), "closePrice": 100.0},
            {"date": (today - datetime.timedelta(days=2)).isoformat(), "closePrice": 110.0},
        ]
        self.assertFalse(_is_db_history_complete(gapped, req_start))

        recent_d = (today - datetime.timedelta(days=5)).isoformat()
        with patch("app.services.yahoo_service.YahooService.get_historical_data", side_effect=RuntimeError("down")):
            with patch(
                "app.services.market_history_repo.market_history_repo.get_historical_prices",
                return_value=[{"date": recent_d, "closePrice": 50.0, "close": 50.0, "currency": "USD", "volume": 10}],
            ) as mock_repo_hist:
                res = _fetch_symbol_history(
                    symbol="AAPL", period="1mo", interval="1d", start_date=None
                )
                mock_repo_hist.assert_called_once()
                self.assertIsNotNone(mock_repo_hist.call_args.kwargs.get("start_date"))
                self.assertIsNone(res["history"][0]["open"])
                self.assertIsNone(res["history"][0]["high"])
                self.assertIsNone(res["history"][0]["low"])

    def test_search_transient_error_is_not_negative_cached(self):
        from app.services.cache_service import cache_service
        from app.services.yahoo_service import YahooService

        fake_redis = _FakeRedis()
        with patch.object(cache_service, "redis_client", fake_redis):
            with patch("app.services.yahoo_service.yf.Search", side_effect=RuntimeError("429")):
                self.assertEqual(YahooService.search_instruments("apple"), [])
        self.assertNotIn("search:APPLE", fake_redis.store)

    def test_historical_fx_invalid_date_and_future_dates_raise_400(self):
        from fastapi import HTTPException
        from app.services.market_utils import warsaw_today
        from app.services.yahoo_service import YahooService

        with self.assertRaises(HTTPException) as bad_req:
            YahooService.get_historical_fx_rates("USD", "PLN", "not-a-date")
        self.assertEqual(bad_req.exception.status_code, 400)

        tomorrow_str = (warsaw_today() + datetime.timedelta(days=1)).isoformat()
        with self.assertRaises(HTTPException) as fut_spot:
            YahooService.get_exchange_rate("USD", "PLN", tomorrow_str)
        self.assertEqual(fut_spot.exception.status_code, 400)

        with self.assertRaises(HTTPException) as fut_hist:
            YahooService.get_historical_fx_rates("USD", "PLN", tomorrow_str)
        self.assertEqual(fut_hist.exception.status_code, 400)

    def test_today_prefixed_nbp_rate_uses_short_ttl_even_if_effective_today(self):
        from app.services.market_utils import warsaw_today
        from app.services.yahoo_service import YahooService, _session
        from app.services.cache_service import cache_service

        today_str = warsaw_today().isoformat()
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"rates": [{"mid": 4.10, "effectiveDate": today_str}]}

        with patch.object(_session, "get", return_value=mock_resp):
            with patch.object(cache_service, "set", wraps=cache_service.set) as spy_set:
                rate = YahooService.get_exchange_rate("EUR", "PLN", today_str)
                self.assertEqual(rate, 4.10)
                self.assertEqual(spy_set.call_args.kwargs.get("expire"), 900)


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
        from app.services.market_utils import warsaw_today

        today = warsaw_today()
        d1 = (today - datetime.timedelta(days=3)).isoformat()
        d2 = (today - datetime.timedelta(days=2)).isoformat()
        d3 = (today - datetime.timedelta(days=1)).isoformat()

        prices = [
            {"date": d1, "closePrice": 0.0, "currency": "USD"},
            {"date": d2, "closePrice": 100.0, "currency": "USD"},
            {"date": d3, "closePrice": 105.0, "currency": "USD"},
        ]

        with patch("app.services.market_history_repo.market_history_repo.get_max_date", return_value=d3):
            with patch(
                "app.services.market_history_repo.market_history_repo.get_profile_metrics",
                return_value={"ter": None, "lastUpdated": today.isoformat()},
            ):
                with patch("app.services.market_history_repo.market_history_repo.get_historical_prices", return_value=prices):
                    with patch("app.services.yahoo_service.YahooService.get_historical_fx_rates", return_value={d1: 4.0, d2: 4.0, d3: 4.0}):
                        metrics = AnalyticsService.fetch_etf_market_metrics("TEST")
                        self.assertIsNone(metrics["annualReturn"])
                        self.assertIsNone(metrics["annualVolatility"])
                        self.assertNotIn("dailyReturns", metrics)

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
        mock_ticker.info = {}

        with patch("app.services.analytics_service.market_history_repo", repo):
            with patch("app.services.yahoo_service.YahooService.get_historical_fx_rates", return_value={d1: 4.0, d2: 4.0}):
                with patch("app.services.analytics_service.yf.Ticker", return_value=mock_ticker) as mock_yf:
                    m1 = AnalyticsService.fetch_etf_market_metrics("UNKNOWN.US")
                    self.assertIsNone(m1["ter"])
                    self.assertNotIn("trailingPe", m1)

                    saved_profile = repo.get_profile_metrics("UNKNOWN.US")
                    self.assertIsNone(saved_profile["ter"])

                    m2 = AnalyticsService.fetch_etf_market_metrics("UNKNOWN.US")
                    self.assertIsNone(m2["ter"])
                    mock_yf.assert_called_once()

    def test_ter_scaling_falsy_zero_profile_backfill_and_partial_weighted_metrics(self):
        from app.routers.analytics_router import HoldingItem
        from app.services.analytics_service import AnalyticsService
        from app.services.market_history_repo import MarketHistoryRepository

        zero_ter = AnalyticsService.extract_fund_metrics({"annualReportExpenseRatio": 0.0})
        self.assertEqual(zero_ter["ter"], 0.0)

        pct_ter = AnalyticsService.extract_fund_metrics({"netExpenseRatio": 0.22})
        self.assertAlmostEqual(pct_ter["ter"], 0.0022, places=6)

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn)
        repo.init_db()
        d_recent = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
        repo.save_eod_prices_bulk("PARTIAL.US", [{"date": d_recent, "close_price": 100.0}], currency="USD")

        idx = pd.to_datetime([
            (datetime.date.today() - datetime.timedelta(days=10)).isoformat(),
            d_recent,
        ])
        full_df = pd.DataFrame({"Close": [95.0, 100.0], "Volume": [100, 100]}, index=idx)
        mock_ticker = MagicMock()
        mock_ticker.history.return_value = full_df
        mock_ticker.fast_info = MagicMock(currency="USD")
        mock_ticker.info = {"netExpenseRatio": 0.15}

        with patch("app.services.analytics_service.market_history_repo", repo):
            with patch("app.services.yahoo_service.YahooService.get_historical_fx_rates", return_value={d_recent: 4.0}):
                with patch("app.services.analytics_service.yf.Ticker", return_value=mock_ticker):
                    metrics = AnalyticsService.fetch_etf_market_metrics("PARTIAL.US")
                    self.assertAlmostEqual(metrics["ter"], 0.0015, places=6)
                    self.assertEqual(len(repo.get_historical_prices("PARTIAL.US")), 2)

        holdings = [HoldingItem(symbol="A", weight=0.6), HoldingItem(symbol="B", weight=0.4)]
        metrics_by_sym = {
            "A": {"symbol": "A", "annualReturn": 0.1, "annualVolatility": 0.2, "ter": 0.002, "weeklyReturns": {}},
            "B": {"symbol": "B", "annualReturn": 0.05, "annualVolatility": 0.1, "ter": None, "weeklyReturns": {}},
        }
        with patch(
            "app.services.analytics_service.AnalyticsService.fetch_etf_market_metrics",
            side_effect=lambda sym: metrics_by_sym[sym],
        ):
            res = AnalyticsService.calculate_portfolio_metrics(holdings)
            self.assertEqual(res["weightedTer"], 0.002)

    def test_repo_rejects_bad_schema_ignores_null_closes_and_updates_sqlite_eurostat(self):
        from app.config import settings
        from app.services.market_history_repo import MarketHistoryRepository

        with patch.object(settings, "DB_SCHEMA", "bad;schema"):
            with self.assertRaises(ValueError):
                MarketHistoryRepository(db_path=":memory:")

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn)
        repo.init_db()
        d1 = (datetime.date.today() - datetime.timedelta(days=2)).isoformat()
        d2 = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
        self.assertEqual(repo.save_eod_prices_bulk("ERR", [{"date": d1, "close": 12.34}]), 0)
        saved = repo.save_eod_prices_bulk("ERR", [
            {"date": d1, "close": None},
            {"date": d2, "close": 0.0},
            {"date": d2, "close": float("nan")},
            {"date": d1, "close": 12.34},
        ], currency="USD")
        self.assertEqual(saved, 1)
        cov = repo.get_price_coverage("ERR")
        self.assertEqual(cov, {"minDate": d1, "maxDate": d1, "count": 1})

        repo.save_cpi_records([
            {"year": 2025, "month": 1, "rate": 4.0, "source": "Eurostat"},
            {"year": 2025, "month": 2, "rate": 4.9, "source": "GUS"},
        ])
        repo.save_cpi_records([
            {"year": 2025, "month": 1, "rate": 4.2, "source": "Eurostat"},
            {"year": 2025, "month": 2, "rate": 5.5, "source": "Eurostat"},
        ], allow_overwrite_authoritative=False)
        rows = {r["month"]: r for r in repo.get_cpi_records(2025)}
        self.assertEqual(rows[1]["rate"], 4.2)
        self.assertEqual(rows[2]["rate"], 4.9)
        self.assertEqual(rows[2]["source"], "GUS")


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
        from app.services.inflation_service import InflationService, _expected_latest_cpi_month

        exp_year, exp_month = _expected_latest_cpi_month()
        sample_rows = [
            {"year": exp_year - 1, "month": 12, "rate": 4.7, "source": "GUS"},
            {"year": exp_year, "month": exp_month, "rate": 4.9, "source": "GUS"},
        ]
        with patch("app.services.market_history_repo.market_history_repo.get_cpi_records", return_value=sample_rows) as mock_db:
            with patch("app.services.inflation_service.InflationService.sync_inflation_from_upstream") as mock_sync:
                res_missing_year = InflationService.get_monthly_cpi(year=1900)
                self.assertEqual(res_missing_year, [])
                mock_sync.assert_not_called()

                res_current = InflationService.get_monthly_cpi(year=exp_year)
                self.assertEqual(len(res_current), 1)
                mock_db.assert_called_once()

    def test_monthly_cpi_rescrapes_when_latest_month_is_stale_and_handles_flash_decline(self):
        from app.services.inflation_service import InflationService

        stale_rows = [{"year": 2025, "month": 1, "rate": 4.9, "source": "GUS"}]
        with patch("app.services.market_history_repo.market_history_repo.get_cpi_records", return_value=stale_rows):
            with patch("app.services.inflation_service.InflationService.sync_inflation_from_upstream") as mock_sync:
                InflationService.get_monthly_cpi()
                mock_sync.assert_called_once()

        sdp_rr = MagicMock(status_code=200, json=lambda: {"data": [{"rok": 2025, "id-okres": 1, "wartosc": 104.9}]})
        sdp_mm = MagicMock(status_code=200, json=lambda: {"data": [{"rok": 2025, "id-okres": 1, "wartosc": 101.0}]})
        hub_resp = MagicMock(
            status_code=200,
            text='<a href="/obszary-tematyczne/szybki-szacunek-wskaznika-cen-towarow-i-uslug-konsumpcyjnych-w-marcu-2025">flash</a>',
        )
        flash_resp = MagicMock(status_code=200, text="Ceny towarów spadły o 0,3% r/r.")

        with patch("app.services.inflation_service._http_session.get", side_effect=[sdp_rr, sdp_mm, hub_resp, flash_resp]):
            with patch("app.services.market_history_repo.market_history_repo.save_cpi_records") as mock_save:
                InflationService.sync_inflation_from_upstream()
                saved_gus = mock_save.call_args_list[0][0][0]
                self.assertIn({"year": 2025, "month": 1, "rate": 4.9, "rateMm": 1.0, "source": "GUS"}, saved_gus)
                self.assertIn({"year": 2025, "month": 3, "rate": -0.3, "source": "GUS (szacunek)"}, saved_gus)

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

    def test_align_fx_rates_supports_unsorted_and_sparse_dates(self):
        from app.services.yahoo_service import align_fx_rates, _extract_fx_rate_map
        import pandas as pd

        rates = {"2025-01-02": 4.20, "2025-01-06": 4.30}
        aligned = align_fx_rates(["2025-01-07", "2025-01-03", "2025-01-02"], rates)
        self.assertEqual(aligned, [4.30, 4.20, 4.20])
        self.assertIsNone(align_fx_rates(["2025-01-01"], rates))

        df = pd.DataFrame(
            {"Close": [4.21, -1.0, float("nan"), 4.25]},
            index=pd.to_datetime(["2025-01-02", "2025-01-03", "2025-01-04", "2025-01-05"]),
        )
        self.assertEqual(_extract_fx_rate_map(df), {"2025-01-02": 4.21, "2025-01-05": 4.25})

    def test_cache_service_handles_nan_without_crashing_and_preserves_valid_batch_items(self):
        from app.services.cache_service import CacheService

        cs = CacheService()
        fake = _FakeRedis()
        cs.redis_client = fake

        cs.set("bad-nan", {"p": float("nan")}, expire=60)
        self.assertNotIn("bad-nan", fake.store)

        cs.mset_pipeline({"bad": {"p": float("inf")}, "good": {"p": 12.5}}, expire=60)
        self.assertNotIn("bad", fake.store)
        self.assertEqual(cs.get("good"), {"p": 12.5})

    def test_historical_fx_does_not_fallback_to_spot_and_validates_date_order(self):
        from fastapi import HTTPException
        from app.services.yahoo_service import YahooService, _session

        past_date = (datetime.date.today() - datetime.timedelta(days=30)).isoformat()
        nbp_404 = MagicMock(status_code=404)
        mock_ticker = MagicMock()
        mock_ticker.history.return_value = pd.DataFrame()
        mock_ticker.fast_info = MagicMock(last_price=4.99)

        with patch.object(_session, "get", return_value=nbp_404):
            with patch("app.services.yahoo_service.yf.Ticker", return_value=mock_ticker):
                with self.assertRaises(HTTPException) as ctx:
                    YahooService.get_exchange_rate("EUR", "PLN", past_date)
                self.assertEqual(ctx.exception.status_code, 503)

        with self.assertRaises(HTTPException) as bad_range:
            YahooService.get_historical_fx_rates("USD", "PLN", "2025-02-10", "2025-02-01")
        self.assertEqual(bad_range.exception.status_code, 400)

    def test_zero_fast_info_price_falls_back_to_history_and_missing_ohlc_stays_none(self):
        from app.services.yahoo_service import YahooService

        idx_daily = pd.to_datetime(["2025-02-03", "2025-02-04"])
        stock_zero = MagicMock()
        stock_zero.fast_info = MagicMock(currency="USD", last_price=0.0, previous_close=0.0)
        stock_zero.history.return_value = pd.DataFrame(
            {"Close": [100.0, 105.0], "Volume": [100, 110]},
            index=idx_daily,
        )
        with patch("app.services.yahoo_service.yf.Ticker", return_value=stock_zero):
            spot = YahooService.get_current_price("AAPL")
            self.assertAlmostEqual(spot["price"], 105.0)
            self.assertAlmostEqual(spot["previousClose"], 100.0)

            hist = YahooService.get_historical_data("AAPL", period="5d", interval="1d")
            self.assertEqual(hist["currency"], "USD")
            self.assertIsNone(hist["history"][0]["open"])
            self.assertIsNone(hist["history"][0]["high"])
            self.assertIsNone(hist["history"][0]["low"])

    def test_db_history_completeness_freshness_and_period_history_uses_db_cache(self):
        from app.routers.prices_router import (
            _fetch_symbol_history,
            _is_db_history_complete,
        )
        from app.services.market_utils import warsaw_today

        today = warsaw_today()
        d1 = (today - datetime.timedelta(days=31)).isoformat()
        stale_end = (today - datetime.timedelta(days=6)).isoformat()
        stale_rows = [
            {"symbol": "VWCE.DE", "date": d1, "closePrice": 100.0, "close": 100.0, "currency": "EUR", "volume": 10},
            {"symbol": "VWCE.DE", "date": stale_end, "closePrice": 103.0, "close": 103.0, "currency": "EUR", "volume": 10},
        ]
        self.assertFalse(_is_db_history_complete(stale_rows, d1))

        fresh_end = (today - datetime.timedelta(days=1)).isoformat()
        rows = [
            {"symbol": "VWCE.DE", "date": d1, "closePrice": 100.0, "close": 100.0, "currency": "EUR", "volume": 10},
            {"symbol": "VWCE.DE", "date": (today - datetime.timedelta(days=23)).isoformat(), "closePrice": 101.0, "close": 101.0, "currency": "EUR", "volume": 10},
            {"symbol": "VWCE.DE", "date": (today - datetime.timedelta(days=15)).isoformat(), "closePrice": 102.0, "close": 102.0, "currency": "EUR", "volume": 10},
            {"symbol": "VWCE.DE", "date": (today - datetime.timedelta(days=7)).isoformat(), "closePrice": 102.5, "close": 102.5, "currency": "EUR", "volume": 10},
            {"symbol": "VWCE.DE", "date": fresh_end, "closePrice": 103.0, "close": 103.0, "currency": "EUR", "volume": 10},
        ]
        self.assertTrue(_is_db_history_complete(rows, d1))
        with patch("app.services.market_history_repo.market_history_repo.get_historical_prices", return_value=rows):
            with patch("app.services.yahoo_service.YahooService.get_historical_data") as mock_yf:
                hist = _fetch_symbol_history("VWCE.DE", "1mo", "1d", None)
                self.assertEqual(hist["source"], "database")
                mock_yf.assert_not_called()

    def test_currency_inference_no_usd_or_l_fallback_and_nbp_tax_rate_t_minus_1(self):
        from fastapi import HTTPException
        from app.routers.currencies_router import get_nbp_tax_rate
        from app.services.market_utils import warsaw_today
        from app.services.yahoo_service import YahooService, _session, infer_base_currency, normalize_quote_currency

        self.assertEqual(infer_base_currency("NESN.SW"), "CHF")
        self.assertIsNone(infer_base_currency("VWRL.L"))
        self.assertIsNone(infer_base_currency("AAPL"))
        with self.assertRaises(HTTPException) as miss_cur:
            normalize_quote_currency(None, "VWRL.L")
        self.assertEqual(miss_cur.exception.status_code, 503)

        table_a_404 = MagicMock(status_code=404)
        table_b_200 = MagicMock(status_code=200)
        table_b_200.json.return_value = {
            "rates": [
                {"effectiveDate": "2025-05-07", "mid": 4.21},
                {"effectiveDate": "2025-05-08", "mid": 4.25},
                {"effectiveDate": "2025-05-09", "mid": 4.99},
            ]
        }
        with patch.object(_session, "get", side_effect=[table_a_404, table_b_200]) as spy_get:
            res = get_nbp_tax_rate(from_currency="USD", to_currency="PLN", date="2025-05-09")
            self.assertEqual(res["rate"], 4.25)
            self.assertEqual(res["effectiveDate"], "2025-05-08")
            self.assertEqual(res["table"], "B")
            self.assertNotIn("2025-05-09", spy_get.call_args_list[0][0][0])

        with patch.object(_session, "get", return_value=table_a_404):
            with patch("app.services.yahoo_service.yf.Ticker") as spy_yf:
                with self.assertRaises(HTTPException) as nbp_miss:
                    YahooService.get_nbp_tax_rate("USD", "PLN", "2025-05-06")
                self.assertEqual(nbp_miss.exception.status_code, 503)
                spy_yf.assert_not_called()

        future_str = (warsaw_today() + datetime.timedelta(days=1)).isoformat()
        with self.assertRaises(HTTPException) as fut_err:
            YahooService.get_nbp_tax_rate("USD", "PLN", future_str)
        self.assertEqual(fut_err.exception.status_code, 400)

    def test_inflation_skips_flash_when_csv_has_month_and_uses_short_cooldown_when_empty(self):
        from app.services.inflation_service import InflationService
        from app.services.cache_service import cache_service

        cur_yr = datetime.date.today().year
        csv_bytes = (
            "Kategoria;Zmienna;Sposób prezentacji;Rok;Miesiąc;Wartość\r\n"
            f"CPI;;Analogiczny miesiąc poprzedniego roku = 100;{cur_yr};3;104,5\r\n"
            f"CPI;;Poprzedni miesiąc = 100;{cur_yr};3;100,4\r\n"
        ).encode("utf-8-sig")
        sdp_fail = MagicMock(status_code=503)
        index_page_resp = MagicMock(
            status_code=200,
            text='<a href="/download/gfx/miesieczne_wskazniki_cen_latest.csv">CSV</a>',
        )
        csv_resp = MagicMock(status_code=200, content=csv_bytes)
        hub_resp = MagicMock(
            status_code=200,
            text=(
                f'<a href="/obszary-tematyczne/szybki-szacunek-wskaznika-cen-towarow-i-uslug-konsumpcyjnych-w-marcu-{cur_yr}">current</a>'
                '<a href="/obszary-tematyczne/szybki-szacunek-wskaznika-cen-towarow-i-uslug-konsumpcyjnych-w-marcu-2021">old</a>'
            ),
        )

        with patch("app.services.inflation_service._http_session.get", side_effect=[sdp_fail, sdp_fail, index_page_resp, csv_resp, hub_resp]) as mock_get:
            with patch("app.services.market_history_repo.market_history_repo.save_cpi_records") as mock_save:
                with patch("app.services.market_history_repo.market_history_repo.get_latest_cpi", return_value={"year": cur_yr, "month": 3}):
                    InflationService.sync_inflation_from_upstream()
                    self.assertEqual(mock_get.call_count, 5)
                    saved_records = mock_save.call_args[0][0]
                    self.assertEqual(saved_records, [{"year": cur_yr, "month": 3, "rate": 4.5, "rateMm": 0.4, "source": "GUS"}])

        with cache_service._mem_lock:
            cache_service._memory_cache.clear()
        self.fake_redis.delete("macro:inflation:sync_cooldown")

        with patch("app.services.inflation_service._http_session.get", side_effect=RuntimeError("offline")):
            with patch("app.services.market_history_repo.market_history_repo.get_latest_cpi", return_value=None):
                with patch.object(cache_service, "set", wraps=cache_service.set) as spy_set:
                    InflationService.sync_inflation_from_upstream()
                    cooldown_calls = [c for c in spy_set.call_args_list if c[0][0] == "macro:inflation:sync_cooldown"]
                    self.assertEqual(len(cooldown_calls), 1)
                    self.assertEqual(cooldown_calls[0].kwargs.get("expire"), 60)

    def test_sqlite_wal_set_in_init_db_and_save_cpi_postgres_uses_excluded(self):
        from unittest.mock import ANY
        from app.services.market_history_repo import MarketHistoryRepository
        import tempfile
        import os

        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = os.path.join(tmpdir, "test.db")
            repo = MarketHistoryRepository(db_path=db_path)
            conn = sqlite3.connect(db_path)
            mode = conn.execute("PRAGMA journal_mode;").fetchone()[0]
            conn.close()
            self.assertEqual(mode.lower(), "wal")

        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.cursor.return_value = mock_cursor
        pg_repo = MarketHistoryRepository(connection=mock_conn, dialect="postgres")
        pg_repo.save_cpi_records([{"year": 2026, "month": 1, "rate": 3.2, "source": "GUS"}])
        query = mock_cursor.executemany.call_args[0][0]
        self.assertIn("excluded.rate", query.lower())
        self.assertIn("%s", query)

    def test_repo_guards_null_prices_atomic_replace_and_cpi_tier_priority(self):
        from app.services.market_history_repo import MarketHistoryRepository

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn)
        repo.init_db()

        d1 = (datetime.date.today() - datetime.timedelta(days=3)).isoformat()
        d2 = (datetime.date.today() - datetime.timedelta(days=2)).isoformat()
        d3 = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()

        conn.execute(
            "INSERT INTO daily_price_history (symbol, date, close_price, currency, volume) VALUES (?, ?, ?, ?, ?)",
            ("MIX", d1, None, "USD", None),
        )
        conn.execute(
            "INSERT INTO daily_price_history (symbol, date, close_price, currency, volume) VALUES (?, ?, ?, ?, ?)",
            ("MIX", d2, 100.0, "USD", 10),
        )
        conn.commit()
        rows = repo.get_historical_prices("MIX")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["date"], d2)

        self.assertEqual(repo.replace_symbol_prices("MIX", [], currency="USD"), 0)
        self.assertEqual(len(repo.get_historical_prices("MIX")), 1)

        replaced = repo.replace_symbol_prices("MIX", [{"date": d3, "close_price": 50.0}], currency="USD")
        self.assertEqual(replaced, 1)
        new_rows = repo.get_historical_prices("MIX")
        self.assertEqual([r["date"] for r in new_rows], [d3])

        repo.save_cpi_records([
            {"year": 2025, "month": 1, "rate": 4.9, "source": "GUS"},
            {"year": 2025, "month": 2, "rate": 4.5, "source": "GUS (szacunek)"},
        ])
        repo.save_cpi_records([
            {"year": 2025, "month": 1, "rate": 4.1, "source": "GUS (szacunek)"},
            {"year": 2025, "month": 2, "rate": 4.0, "source": "Eurostat"},
        ], allow_overwrite_authoritative=False)
        by_month = {r["month"]: r for r in repo.get_cpi_records(2025)}
        self.assertEqual(by_month[1]["source"], "GUS")
        self.assertEqual(by_month[1]["rate"], 4.9)
        self.assertEqual(by_month[2]["source"], "GUS (szacunek)")
        self.assertEqual(by_month[2]["rate"], 4.5)

        repo.save_cpi_records([{"year": 2025, "month": 2, "rate": 4.7, "source": "GUS"}])
        by_month_after = {r["month"]: r for r in repo.get_cpi_records(2025)}
        self.assertEqual(by_month_after[2]["source"], "GUS")
        self.assertEqual(by_month_after[2]["rate"], 4.7)

    def test_calendar_date_validation_and_nbp_positive_mid_guard(self):
        from pydantic import ValidationError
        from app.routers.currencies_router import BatchFxHistoryRequest
        from app.routers.prices_router import BatchHistoryRequest
        from app.services.yahoo_service import _fetch_nbp_rate, _session

        with self.assertRaises(ValidationError):
            BatchFxHistoryRequest(pairs=[], start_date="2025-02-30")
        with self.assertRaises(ValidationError):
            BatchFxHistoryRequest(pairs=[], start_date="2025-03-10", end_date="2025-03-01")
        with self.assertRaises(ValidationError):
            BatchHistoryRequest(symbols=["VWCE.DE"], start_date="2025-02-30")

        bad_nbp = MagicMock(status_code=200)
        bad_nbp.json.return_value = {"rates": [{"mid": 0.0, "effectiveDate": "2025-01-02"}]}
        with patch.object(_session, "get", return_value=bad_nbp):
            rate, eff = _fetch_nbp_rate("EUR", "PLN")
            self.assertIsNone(rate)
            self.assertIsNone(eff)

    def test_pythonRepo_noImportSideEffectsAndUrlEncodedDsn(self):
        import gc
        import os
        import subprocess
        import sys
        import weakref
        from app.services import cache_service, market_history_repo as repo_mod

        env = os.environ.copy()
        for key in ("DATABASE_URL", "DB_HOST", "DB_USER", "DB_PASSWORD", "DB_NAME", "MARKET_DB_PATH"):
            env.pop(key, None)
        proc = subprocess.run(
            [
                sys.executable,
                "-c",
                (
                    "import os; "
                    "from app.services.market_history_repo import market_history_repo; "
                    "assert not market_history_repo._initialized; "
                    "assert market_history_repo.pool is None; "
                    "print('COLD_IMPORT_OK')"
                ),
            ],
            cwd=os.path.abspath(os.path.join(os.path.dirname(__file__), "..")),
            env=env,
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertIn("COLD_IMPORT_OK", proc.stdout)

        with patch.object(repo_mod, "settings") as mock_settings:
            mock_settings.DATABASE_URL = None
            mock_settings.DB_HOST = "pg.internal"
            mock_settings.DB_PORT = 5432
            mock_settings.DB_NAME = "invest_db"
            mock_settings.DB_USER = "user@domain"
            mock_settings.DB_PASSWORD = "p@ss:w/ord#1"
            mock_settings.DB_SCHEMA = "market_data"
            with patch.object(repo_mod.MarketHistoryRepository, "init_db", return_value=None):
                repo = repo_mod.MarketHistoryRepository(connection=MagicMock(), dialect="postgres")
                repo.conn = None
                repo.database_url = None
                repo._configure_postgres_dsn()
                self.assertEqual(
                    repo.database_url,
                    "postgresql://user%40domain:p%40ss%3Aw%2Ford%231@pg.internal:5432/invest_db",
                )

            mock_settings.DB_HOST = None
            mock_settings.DB_USER = None
            mock_settings.DB_PASSWORD = None
            mock_settings.DB_NAME = None
            with self.assertRaises(ValueError):
                repo_mod.MarketHistoryRepository()

        cs = cache_service.CacheService()
        ref = weakref.ref(cs.get_lock("ephemeral-key"))
        gc.collect()
        self.assertIsNone(ref())

    def test_nbp_reference_rates_xml_parsing(self):
        from app.services.inflation_service import InflationService, _http_session
        from app.services import cache_service

        xml_arch = b'<?xml version="1.0" encoding="utf-8"?><stopy_procentowe_archiwum><pozycje obowiazuje_od="2023-10-05"><pozycja id="ref" oprocentowanie="5,75"/></pozycje></stopy_procentowe_archiwum>'
        xml_curr = b'<?xml version="1.0" encoding="utf-8"?><stopy_procentowe><tabela id="stoproc"><pozycja id="ref" oprocentowanie="5,25" obowiazuje_od="2025-05-08"/></tabela></stopy_procentowe>'

        def fake_get(url, timeout=8):
            resp = MagicMock()
            resp.status_code = 200
            resp.content = xml_arch if "archiwum" in url else xml_curr
            return resp

        with patch.object(cache_service.cache_service, "get_or_set", side_effect=lambda k, fn, **kw: fn()):
            with patch.object(_http_session, "get", side_effect=fake_get):
                rows = InflationService.get_nbp_reference_rates()

        self.assertEqual(
            rows,
            [
                {"effectiveFrom": "2023-10-05", "rate": 5.75},
                {"effectiveFrom": "2025-05-08", "rate": 5.25},
            ],
        )

    def test_get_cpi_filters_null_rate_records(self):
        from app.services.inflation_service import InflationService
        from app.services import cache_service, market_history_repo

        today = datetime.date.today()
        records = [
            {"year": today.year - 1, "month": 12, "rate": 3.8, "rateMm": 0.2, "source": "GUS"},
            {"year": today.year, "month": today.month, "rate": 4.2, "rateMm": 0.1, "source": "GUS"},
            {"year": today.year + 1, "month": 1, "rate": None, "rateMm": 0.4, "source": "GUS"},
        ]

        with patch.object(cache_service.cache_service, "get_or_set", side_effect=lambda k, fn, **kw: fn()):
            with patch.object(market_history_repo.market_history_repo, "get_cpi_records", return_value=records):
                with patch.object(InflationService, "sync_inflation_from_upstream"):
                    result = InflationService.get_cpi()

        self.assertEqual(result["currentCpi"], 4.2)
        self.assertEqual(result["period"], f"{today.year}-{today.month:02d}")
        self.assertTrue(all(item["cpi"] is not None for item in result["history"]))

    def test_fetch_etf_market_metrics_preserves_older_than_5y_db_history(self):
        from app.services.analytics_service import AnalyticsService
        from app.services.market_history_repo import MarketHistoryRepository
        from app.services import analytics_service as analytics_mod
        from app.services import cache_service

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn, dialect="sqlite")
        repo.save_eod_prices_bulk(
            "OLD.DE",
            [
                {"date": "2018-01-05", "close_price": 50.0, "currency": "EUR", "volume": 10},
                {"date": "2025-12-01", "close_price": 100.0, "currency": "EUR", "volume": 10},
            ],
            "EUR",
        )

        dates = pd.date_range("2022-01-07", periods=10, freq="7D")
        yf_hist = pd.DataFrame(
            {
                "Date": dates,
                "Close": [110.0 + i for i in range(len(dates))],
                "Volume": [100] * len(dates),
            }
        )
        fake_stock = MagicMock()
        fake_stock.fast_info = {"currency": "EUR"}
        fake_stock.info = {}

        with patch.object(analytics_mod, "market_history_repo", repo):
            with patch.object(cache_service.cache_service, "get_or_set", side_effect=lambda k, fn, **kw: fn()):
                with patch.object(analytics_mod.yf, "Ticker", return_value=fake_stock):
                    with patch.object(analytics_mod, "download_clean_history", return_value=yf_hist):
                        with patch.object(analytics_mod.YahooService, "get_historical_fx_rates", return_value={}):
                            AnalyticsService.fetch_etf_market_metrics("OLD.DE")

        saved_dates = {row["date"] for row in repo.get_historical_prices("OLD.DE")}
        self.assertIn("2018-01-05", saved_dates)
        conn.close()

    def test_fetch_etf_market_metrics_stores_exchange_local_dates(self):
        from app.services.analytics_service import AnalyticsService
        from app.services.market_history_repo import MarketHistoryRepository
        from app.services import analytics_service as analytics_mod
        from app.services import cache_service

        conn = sqlite3.connect(":memory:")
        repo = MarketHistoryRepository(connection=conn, dialect="sqlite")
        yf_hist = pd.DataFrame(
            {
                "Date": pd.date_range("2024-06-03", periods=2, freq="D", tz="Europe/Warsaw"),
                "Close": [100.0, 101.0],
                "Volume": [1, 1],
            }
        )
        fake_stock = MagicMock()
        fake_stock.fast_info = {"currency": "PLN"}
        fake_stock.info = {}

        with patch.object(analytics_mod, "market_history_repo", repo):
            with patch.object(cache_service.cache_service, "get_or_set", side_effect=lambda k, fn, **kw: fn()):
                with patch.object(analytics_mod.yf, "Ticker", return_value=fake_stock):
                    with patch.object(analytics_mod, "download_clean_history", return_value=yf_hist):
                        AnalyticsService.fetch_etf_market_metrics("CDR.WA")

        saved_dates = sorted(row["date"] for row in repo.get_historical_prices("CDR.WA"))
        self.assertEqual(saved_dates, ["2024-06-03", "2024-06-04"])
        conn.close()

    def test_market_history_repo_synchronizes_pool_across_get_connection_and_close(self):
        import threading
        import time
        from app.services.market_history_repo import MarketHistoryRepository

        repo = MarketHistoryRepository(connection=MagicMock(), dialect="postgres")
        repo.conn = None
        repo._initialized = True
        repo.database_url = "postgresql://u:p@localhost:5432/db"

        def make_fake_pool():
            pool = MagicMock()
            first = {"done": False}

            def getconn():
                closed_conn = MagicMock()
                if not first["done"]:
                    first["done"] = True
                    closed_conn.closed = 1
                    time.sleep(0.005)
                else:
                    closed_conn.closed = 0
                    closed_conn.cursor.return_value = MagicMock()
                return closed_conn

            pool.getconn.side_effect = getconn
            pool.putconn.side_effect = lambda c, close=False: None
            pool.closeall.side_effect = lambda: None
            return pool

        errors = []
        for _ in range(15):
            repo.pool = make_fake_pool()
            barrier = threading.Barrier(2)

            def reader():
                try:
                    barrier.wait(timeout=2)
                    with repo._cursor(ensure_init=False):
                        pass
                except AttributeError as err:
                    errors.append(err)
                except Exception:
                    pass

            def closer():
                try:
                    barrier.wait(timeout=2)
                    time.sleep(0.002)
                    repo.close()
                except Exception as err:
                    errors.append(err)

            t1 = threading.Thread(target=reader)
            t2 = threading.Thread(target=closer)
            t1.start()
            t2.start()
            t1.join(timeout=3)
            t2.join(timeout=3)

        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
