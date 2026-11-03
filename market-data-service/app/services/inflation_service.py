import datetime
import logging
from typing import Optional
import requests
from app.services.cache_service import cache_service
from app.services.market_history_repo import market_history_repo

class InflationService:

    @staticmethod
    def sync_inflation_from_upstream() -> None:
        try:
            resp = requests.get(
                "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/prc_hicp_manr?geo=PL&coicop=CP00&unit=RCH_A&lastTimePeriod=60",
                timeout=6,
            )
            if resp.status_code == 200:
                data = resp.json()
                time_idx = data.get("dimension", {}).get("time", {}).get("category", {}).get("index", {})
                values = data.get("value", {})
                records = []
                for ym_str, idx in time_idx.items():
                    val = values.get(str(idx))
                    if val is not None and "-" in ym_str:
                        parts = ym_str.split("-")
                        if len(parts) == 2 and parts[0].isdigit() and parts[1].isdigit():
                            records.append({
                                "year": int(parts[0]),
                                "month": int(parts[1]),
                                "rate": float(val),
                                "source": "Eurostat",
                            })
                if records:
                    market_history_repo.save_cpi_records(records)
        except Exception as e:
            logging.warning(f"Failed to sync inflation from Eurostat: {e}")

    @staticmethod
    def get_cpi() -> dict:
        cache_key = "macro:inflation:cpi"
        cached = cache_service.get(cache_key)
        if cached is not None and isinstance(cached, dict):
            return cached

        latest = market_history_repo.get_latest_cpi()
        if not latest:
            InflationService.sync_inflation_from_upstream()
            latest = market_history_repo.get_latest_cpi()

        all_records = market_history_repo.get_cpi_records()
        last_per_year: dict[int, float] = {}
        for r in all_records:
            last_per_year[r["year"]] = r["rate"]

        summary = {
            "currentCpi": latest["rate"] if latest else None,
            "source": latest["source"] if latest else "NONE",
            "period": f"{latest['year']}-{latest['month']:02d}" if latest else "",
            "history": [{"year": str(y), "cpi": rate} for y, rate in sorted(last_per_year.items())],
        }

        if latest:
            cache_service.set(cache_key, summary, expire=86400)
        return summary

    @staticmethod
    def get_monthly_cpi(year: Optional[int] = None) -> list[dict]:
        records = market_history_repo.get_cpi_records(year)
        if not records:
            InflationService.sync_inflation_from_upstream()
            records = market_history_repo.get_cpi_records(year)
        return records
