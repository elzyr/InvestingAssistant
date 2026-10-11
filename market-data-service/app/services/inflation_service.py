from app.services.cache_service import cache_service

CACHE_KEY_INFLATION = "macro:inflation:cpi"

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
]

def _to_record(row: tuple) -> dict:
    return {"year": row[0], "month": row[1], "rate": row[2], "source": row[3]}

ALL_RECORDS = [_to_record(r) for r in INFLATION_HISTORY]

class InflationService:

    @staticmethod
    def get_cpi() -> dict:
        cached = cache_service.get(CACHE_KEY_INFLATION)
        if cached:
            return cached

        latest = ALL_RECORDS[-1]
        last_per_year = {r["year"]: r["rate"] for r in ALL_RECORDS}

        result = {
            "currentCpi": latest["rate"],
            "source": latest["source"],
            "period": f"{latest['year']}-{latest['month']:02d}",
            "history": [{"year": str(y), "cpi": rate} for y, rate in sorted(last_per_year.items())],
        }
        cache_service.set(CACHE_KEY_INFLATION, result, expire=86400)
        return result

    @staticmethod
    def get_monthly_cpi(year: int | None = None) -> list[dict]:
        if year is not None:
            return [r for r in ALL_RECORDS if r["year"] == year]
        return ALL_RECORDS
