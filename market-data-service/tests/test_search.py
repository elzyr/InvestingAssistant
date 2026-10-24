import unittest
from unittest.mock import patch, MagicMock

class TestInstrumentSearch(unittest.TestCase):
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

    def test_search_instruments_route_is_registered_on_prices_router(self):
        from app.routers.prices_router import router

        route = next((r for r in router.routes if getattr(r, "path", "").endswith("/search")), None)
        self.assertIsNotNone(route)
        self.assertIn("GET", route.methods)


if __name__ == "__main__":
    unittest.main()
