import csv
import datetime
import io
import logging
import re
from typing import Optional
import requests
from app.services.cache_service import cache_service
from app.services.market_history_repo import market_history_repo

class InflationService:

    @staticmethod
    def sync_inflation_from_upstream() -> None:
        gus_records = []
        try:
            csv_url = "https://stat.gov.pl/download/gfx/portalinformacyjny/pl/defaultstronaopisowa/4741/1/1/miesiecznewskaznikicentowarowiuslugkonsumpcyjnychod1982roku_9.csv"
            resp = requests.get(csv_url, timeout=10)
            if resp.status_code == 200:
                reader = csv.reader(io.StringIO(resp.text), delimiter=";")
                next(reader, None)
                for row in reader:
                    if len(row) > 5 and row[2] == "Analogiczny miesiąc poprzedniego roku = 100":
                        val_str = row[5].replace(",", ".")
                        try:
                            rate = round(float(val_str) - 100.0, 2)
                            gus_records.append({
                                "year": int(row[3]),
                                "month": int(row[4]),
                                "rate": rate,
                                "source": "GUS",
                            })
                        except ValueError:
                            pass
        except Exception as e:
            logging.warning(f"Failed to fetch GUS CPI dataset: {e}")

        try:
            hub = requests.get("https://stat.gov.pl/obszary-tematyczne/ceny-handel/wskazniki-cen/", timeout=8)
            if hub.status_code == 200:
                months_pl = {
                    "styczniu": 1, "lutym": 2, "marcu": 3, "kwietniu": 4,
                    "maju": 5, "czerwcu": 6, "lipcu": 7, "sierpniu": 8,
                    "wrzesniu": 9, "wrześniu": 9, "pazdzierniku": 10, "październiku": 10,
                    "listopadzie": 11, "grudniu": 12
                }
                links = set(re.findall(r'href=[\x22\x27]([^\x22\x27]*szybki-szacunek[^\x22\x27]*)[\x22\x27]', hub.text))
                for link in links:
                    m_match = re.search(r'szybki-szacunek-wskaznika-cen-towarow-i-uslug-konsumpcyjnych-w-([a-z]+)-([0-9]{4})', link)
                    if m_match:
                        month_name = m_match.group(1).lower()
                        year = int(m_match.group(2))
                        month = months_pl.get(month_name)
                        if month and year >= 2026:
                            page = requests.get(link, timeout=8)
                            if page.status_code == 200:
                                rate_m = re.search(r'wzrosły o ([0-9]+[,\.][0-9]+)%', page.text)
                                if rate_m:
                                    rate = float(rate_m.group(1).replace(",", "."))
                                    gus_records.append({
                                        "year": year,
                                        "month": month,
                                        "rate": rate,
                                        "source": "GUS (szacunek)",
                                    })
        except Exception as e:
            logging.warning(f"Failed to fetch GUS flash estimate: {e}")

        if gus_records:
            market_history_repo.save_cpi_records(gus_records, allow_overwrite_authoritative=True)

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
                    market_history_repo.save_cpi_records(records, allow_overwrite_authoritative=False)
        except Exception as e:
            logging.warning(f"Failed to sync inflation from Eurostat: {e}")

        cache_service.delete("macro:inflation:cpi")

    @staticmethod
    def get_cpi() -> dict:
        cache_key = "macro:inflation:cpi"
        cached = cache_service.get(cache_key)
        if cached is not None and isinstance(cached, dict):
            return cached

        latest = market_history_repo.get_latest_cpi()
        if not latest or latest.get("year", 0) < datetime.date.today().year:
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
