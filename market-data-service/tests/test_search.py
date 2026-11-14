import unittest
from unittest.mock import patch, MagicMock

class _InMemoryRedis:
    def __init__(self):
        self.store = {}

    def get(self, key):
        return self.store.get(key)

    def set(self, key, value, ex=None):
        self.store[key] = value

    def delete(self, key):
        self.store.pop(key, None)


class TestInstrumentSearch(unittest.TestCase):
    def setUp(self):
        from app.services.cache_service import cache_service
        self._cache_service = cache_service
        self._orig_redis = cache_service.redis_client
        cache_service.redis_client = _InMemoryRedis()
        with cache_service._mem_lock:
            cache_service._memory_cache.clear()

    def tearDown(self):
        self._cache_service.redis_client = self._orig_redis
        with self._cache_service._mem_lock:
            self._cache_service._memory_cache.clear()

    def test_search_instruments_returns_stocks_and_etfs_with_mapped_types(self):
        from app.services.yahoo_service import YahooService

        mock_search_instance = MagicMock()
        mock_search_instance.quotes = [
            {
                "symbol": "AMZN",
                "shortname": "Amazon.com, Inc.",
                "quoteType": "EQUITY",
                "exchange": "NMS",
                "currency": "USD",
            },
            {
                "symbol": "VWCE.DE",
                "shortname": "Vanguard FTSE All-World",
                "quoteType": "ETF",
                "exchange": "GER",
                "currency": "EUR",
            },
            {
                "symbol": "CL=F",
                "shortname": "Crude Oil",
                "quoteType": "FUTURE",
                "exchange": "NYM",
                "currency": "USD",
            },
            {
                "symbol": "AAPL240119C00150000",
                "shortname": "AAPL Call Option",
                "quoteType": "OPTION",
                "exchange": "OPR",
                "currency": "USD",
            },
        ]

        with patch("app.services.yahoo_service.yf.Search", return_value=mock_search_instance):
            results = YahooService.search_instruments("amazon")

            self.assertEqual(len(results), 2)
            self.assertEqual(results[0]["symbol"], "AMZN")
            self.assertEqual(results[0]["name"], "Amazon.com, Inc.")
            self.assertEqual(results[0]["type"], "STOCK")
            self.assertEqual(results[0]["currency"], "USD")

            self.assertEqual(results[1]["symbol"], "VWCE.DE")
            self.assertEqual(results[1]["type"], "ETF")
            self.assertEqual(results[1]["currency"], "EUR")

    def test_search_instruments_ignores_non_stock_and_non_etf_quotes(self):
        from app.services.yahoo_service import YahooService

        mock_search_instance = MagicMock()
        mock_search_instance.quotes = [
            {"symbol": "GC=F", "shortname": "Gold", "quoteType": "FUTURE", "currency": "USD"},
            {"symbol": "SPY240621P00450000", "shortname": "Put Option", "quoteType": "OPTION", "currency": "USD"},
            {"symbol": "BTC-USD", "shortname": "Bitcoin", "quoteType": "CRYPTOCURRENCY", "currency": "USD"},
        ]

        with patch("app.services.yahoo_service.yf.Search", return_value=mock_search_instance):
            results = YahooService.search_instruments("gold")
            self.assertEqual(len(results), 0)

    def test_search_instruments_endpoint_returns_matching_items(self):
        from app.routers.prices_router import search_instruments

        mock_search_instance = MagicMock()
        mock_search_instance.quotes = [
            {
                "symbol": "AMZN",
                "shortname": "Amazon.com, Inc.",
                "quoteType": "EQUITY",
                "exchange": "NMS",
                "currency": "USD",
            }
        ]

        with patch("app.services.yahoo_service.yf.Search", return_value=mock_search_instance):
            response = search_instruments(query="amazon")
            self.assertIsInstance(response, list)
            self.assertEqual(len(response), 1)
            self.assertEqual(response[0]["symbol"], "AMZN")
            self.assertEqual(response[0]["type"], "STOCK")

    def test_search_instruments_normalizes_gbx_and_gbp_pence_to_gbp(self):
        from app.services.yahoo_service import YahooService

        mock_search_instance = MagicMock()
        mock_search_instance.quotes = [
            {
                "symbol": "VWRL.L",
                "shortname": "Vanguard FTSE All-World UCITS ETF",
                "quoteType": "ETF",
                "exchange": "LSE",
                "currency": "GBp",
            },
            {
                "symbol": "AZN.L",
                "shortname": "AstraZeneca PLC",
                "quoteType": "EQUITY",
                "exchange": "LSE",
                "currency": "GBX",
            },
        ]

        with patch("app.services.yahoo_service.yf.Search", return_value=mock_search_instance):
            results = YahooService.search_instruments("lse")
            self.assertEqual(len(results), 2)
            self.assertEqual(results[0]["currency"], "GBP")
            self.assertEqual(results[1]["currency"], "GBP")

if __name__ == "__main__":
    unittest.main()
