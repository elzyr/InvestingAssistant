import logging
import requests
from app.services.cache_service import cache_service

INFLATION_HISTORY = [
    (2024, 12, 4.7, "GUS"),
    (2025, 12, 3.8, "GUS"),
    (2026, 1, 3.5, "GUS"),
    (2026, 2, 3.2, "GUS"),
    (2026, 3, 3.0, "NBP"),
    (2026, 4, 3.2, "NBP"),
    (2026, 5, 3.1, "NBP"),
    (2026, 6, 2.5, "NBP"),
    (2026, 7, 3.0, "NBP"),
    (2026, 8, 3.4, "NBP"),
    (2026, 9, 4.9, "GUS"),
    (2026, 10, 5.0, "GUS"),
]

def _to_record(row: tuple) -> dict:
    return {"year": row[0], "month": row[1], "rate": row[2], "source": row[3]}

ALL_RECORDS = [_to_record(r) for r in INFLATION_HISTORY]

_latest = ALL_RECORDS[-1]
_last_per_year = {r["year"]: r["rate"] for r in ALL_RECORDS}
_CPI_SUMMARY = {
    "currentCpi": _latest["rate"],
    "source": _latest["source"],
    "period": f"{_latest['year']}-{_latest['month']:02d}",
    "history": [{"year": str(y), "cpi": rate} for y, rate in sorted(_last_per_year.items())],
}

class InflationService:

    @staticmethod
    def get_cpi() -> dict:
        cache_key = "macro:inflation:cpi"
        cached = cache_service.get(cache_key)
        if cached is not None and isinstance(cached, dict):
            return cached

        summary = dict(_CPI_SUMMARY)
        try:
            resp = requests.get(
                "https://api-dbw.stat.gov.pl/api/1.1.0/variable/variable-data-section?id-zmienna=305&id-przekroj=736&id-rok=2026&id-okres=281&ile-na-stronie=10",
                timeout=3,
            )
            if resp.status_code == 200:
                data = resp.json()
                if isinstance(data, dict) and "data" in data and isinstance(data["data"], list) and len(data["data"]) > 0:
                    val = data["data"][0].get("wartosc")
                    if isinstance(val, (int, float)):
                        summary["currentCpi"] = round(float(val), 2)
        except Exception as e:
            logging.info(f"GUS DBW API unavailable, falling back to static history: {e}")

        cache_service.set(cache_key, summary, expire=86400)
        return summary

    @staticmethod
    def get_monthly_cpi(year: int | None = None) -> list[dict]:
        if year is not None:
            return [r for r in ALL_RECORDS if r["year"] == year]
        return ALL_RECORDS
