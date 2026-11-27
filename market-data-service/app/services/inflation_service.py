import csv
import datetime
import io
import logging
import re
import xml.etree.ElementTree as ET
from typing import Any, Optional
from urllib.parse import urljoin
from app.services.cache_service import cache_service
from app.services.market_history_repo import market_history_repo
from app.services.yahoo_service import create_retry_session, finite_float

_http_session = create_retry_session(pool_connections=4, pool_maxsize=8)

_CPI_CACHE_KEY = "macro:inflation:cpi"
_MONTHLY_CACHE_KEY = "macro:inflation:monthly:all"
_SYNC_COOLDOWN_KEY = "macro:inflation:sync_cooldown"
_SYNC_LOCK_KEY = "macro:inflation:sync"
_SYNC_COOLDOWN_SECONDS = 3600
_GUS_HUB_URL = "https://stat.gov.pl/obszary-tematyczne/ceny-handel/wskazniki-cen/"
_GUS_CPI_INDEX_URL = "https://stat.gov.pl/obszary-tematyczne/ceny-handel/wskazniki-cen/wskazniki-cen-towarow-i-uslug-konsumpcyjnych-pot-inflacja-/miesieczne-wskazniki-cen-towarow-i-uslug-konsumpcyjnych-od-1982-roku/"
_GUS_SDP_BASE = "https://api-sdp.stat.gov.pl/api/1.0.0/indicators/indicator-data-data"


def _decode_csv_bytes(content: bytes) -> str:
    try:
        return content.decode("utf-8-sig")
    except UnicodeDecodeError:
        return content.decode("cp1250", errors="replace")


def _parse_gus_sdp_payload(payload: Any) -> dict[tuple[int, int], float]:
    items = payload if isinstance(payload, list) else (payload.get("data") or payload.get("results") or []) if isinstance(payload, dict) else []
    out: dict[tuple[int, int], float] = {}
    for row in items:
        if not isinstance(row, dict):
            continue
        raw_val = finite_float(row.get("wartosc") if "wartosc" in row else row.get("val"))
        if raw_val is None:
            continue
        try:
            year = int(row.get("rok") or row.get("year") or row.get("id-okres-rok"))
            raw_month = row.get("id-okres") or row.get("miesiac") or row.get("month")
            month = int(raw_month)
            if month > 12:
                m_str = str(raw_month)
                month = int(m_str[-2:])
        except (TypeError, ValueError):
            continue
        if not (1980 <= year <= 2100 and 1 <= month <= 12):
            continue
        rate = round(raw_val - 100.0, 2) if raw_val > 50.0 else round(raw_val, 2)
        out[(year, month)] = rate
    return out


def _fetch_gus_sdp_series() -> dict[tuple[int, int], dict]:
    merged: dict[tuple[int, int], dict] = {}
    for indicator_id, section_id, field in ((2728, 1832, "rate"), (2874, 639, "rateMm")):
        try:
            resp = _http_session.get(
                _GUS_SDP_BASE,
                params={"id-wskaznik": indicator_id, "id-przekroj": section_id, "lang": "pl"},
                timeout=8,
            )
            if resp.status_code == 200:
                parsed = _parse_gus_sdp_payload(resp.json())
                for ym, val in parsed.items():
                    rec = merged.setdefault(ym, {"year": ym[0], "month": ym[1], "source": "GUS"})
                    rec[field] = val
        except Exception as e:
            logging.warning(f"Failed GUS SDP fetch for {indicator_id}/{section_id}: {e}")
    return merged


def _discover_gus_csv_url() -> Optional[str]:
    for page_url in (_GUS_CPI_INDEX_URL, _GUS_HUB_URL):
        try:
            resp = _http_session.get(page_url, timeout=8)
            if resp.status_code != 200:
                continue
            matches = re.findall(
                r'href=[\x22\x27]([^\x22\x27]*miesieczn[^\x22\x27]*wskaznik[^\x22\x27]*\.csv)[\x22\x27]',
                resp.text,
                re.IGNORECASE,
            )
            if matches:
                return urljoin(page_url, matches[0])
        except Exception as e:
            logging.warning(f"Failed discovering GUS CSV link from {page_url}: {e}")
    return None


def _parse_gus_csv_records(csv_text: str) -> dict[tuple[int, int], dict]:
    merged: dict[tuple[int, int], dict] = {}
    reader = csv.reader(io.StringIO(csv_text), delimiter=";")
    next(reader, None)
    for row in reader:
        if len(row) <= 5:
            continue
        desc = row[2].strip()
        if desc == "Analogiczny miesiąc poprzedniego roku = 100":
            field = "rate"
        elif desc == "Poprzedni miesiąc = 100":
            field = "rateMm"
        else:
            continue
        raw_val = finite_float(row[5].replace(",", "."))
        if raw_val is None:
            continue
        try:
            year = int(row[3])
            month = int(row[4])
        except ValueError:
            continue
        rec = merged.setdefault((year, month), {"year": year, "month": month, "source": "GUS"})
        rec[field] = round(raw_val - 100.0, 2)
    return merged


def _expected_latest_cpi_month(today: Optional[datetime.date] = None) -> tuple[int, int]:
    ref = today or datetime.date.today()
    return (ref.year, ref.month - 1) if ref.month > 1 else (ref.year - 1, 12)


def _is_cpi_stale(latest: Optional[dict], today: Optional[datetime.date] = None) -> bool:
    if not latest:
        return True
    try:
        ym = (int(latest["year"]), int(latest["month"]))
    except (KeyError, TypeError, ValueError):
        return True
    return ym < _expected_latest_cpi_month(today)


class InflationService:

    @staticmethod
    def sync_inflation_from_upstream() -> None:
        with cache_service.get_lock(_SYNC_LOCK_KEY):
            if cache_service.get(_SYNC_COOLDOWN_KEY) is not None:
                return

            gus_by_ym = _fetch_gus_sdp_series()
            if not gus_by_ym:
                try:
                    csv_url = _discover_gus_csv_url()
                    if csv_url:
                        resp = _http_session.get(csv_url, timeout=10)
                        if resp.status_code == 200:
                            raw_content = getattr(resp, "content", None)
                            csv_text = _decode_csv_bytes(raw_content) if isinstance(raw_content, (bytes, bytearray)) else resp.text
                            gus_by_ym = _parse_gus_csv_records(csv_text)
                except Exception as e:
                    logging.warning(f"Failed to fetch GUS CPI CSV dataset: {e}")

            gus_records = list(gus_by_ym.values())
            authoritative_months = set(gus_by_ym.keys())
            current_year = datetime.date.today().year
            try:
                hub = _http_session.get(_GUS_HUB_URL, timeout=8)
                if hub.status_code == 200:
                    months_pl = {
                        "styczniu": 1, "lutym": 2, "marcu": 3, "kwietniu": 4,
                        "maju": 5, "czerwcu": 6, "lipcu": 7, "sierpniu": 8,
                        "wrzesniu": 9, "wrześniu": 9, "pazdzierniku": 10, "październiku": 10,
                        "listopadzie": 11, "grudniu": 12
                    }
                    links = set(re.findall(r'href=[\x22\x27]([^\x22\x27]*szybki-szacunek[^\x22\x27]*)[\x22\x27]', hub.text))
                    for link in links:
                        full_link = urljoin(_GUS_HUB_URL, link)
                        m_match = re.search(r'szybki-szacunek-wskaznika-cen-towarow-i-uslug-konsumpcyjnych-w-([a-zA-ZąćęłńóśźżĄĆĘŁŃÓŚŹŻ]+)-([0-9]{4})', full_link)
                        if m_match:
                            month_name = m_match.group(1).lower()
                            year = int(m_match.group(2))
                            month = months_pl.get(month_name)
                            if month and year >= current_year - 1 and (year, month) not in authoritative_months:
                                page = _http_session.get(full_link, timeout=8)
                                if page.status_code == 200:
                                    rate_m = re.search(r'(wzrosły|spadły)\s+o\s+(\d+(?:[.,]\d+)?)\s*%', page.text, re.IGNORECASE)
                                    if rate_m:
                                        rate = finite_float(rate_m.group(2).replace(",", "."))
                                        if rate is not None:
                                            if rate_m.group(1).lower() == "spadły":
                                                rate = -rate
                                            gus_records.append({
                                                "year": year,
                                                "month": month,
                                                "rate": rate,
                                                "source": "GUS (szacunek)",
                                            })
            except Exception as e:
                logging.warning(f"Failed to fetch GUS flash estimate: {e}")

            if gus_records:
                market_history_repo.save_cpi_records(gus_records, allow_overwrite_authoritative=False)

            has_data = bool(gus_records)
            if not has_data:
                try:
                    has_data = market_history_repo.get_latest_cpi() is not None
                except Exception:
                    has_data = False
            cooldown = _SYNC_COOLDOWN_SECONDS if has_data else 60
            cache_service.set(_SYNC_COOLDOWN_KEY, 1, expire=cooldown)
            cache_service.delete(_CPI_CACHE_KEY)
            cache_service.delete(_MONTHLY_CACHE_KEY)

    @staticmethod
    def _load_fresh_cpi_records() -> list[dict]:
        records = market_history_repo.get_cpi_records()
        latest = max(records, key=lambda r: (r["year"], r["month"])) if records else None
        if _is_cpi_stale(latest):
            InflationService.sync_inflation_from_upstream()
            records = market_history_repo.get_cpi_records()
        return records

    @staticmethod
    def get_cpi() -> dict:
        cache_ttl = 300

        def _build() -> dict:
            nonlocal cache_ttl
            all_records = InflationService._load_fresh_cpi_records()
            rate_records = [r for r in all_records if r.get("rate") is not None]
            latest = max(rate_records, key=lambda r: (r["year"], r["month"])) if rate_records else None
            last_per_year = {r["year"]: r["rate"] for r in rate_records}

            cache_ttl = 86400 if latest else 300
            return {
                "currentCpi": latest["rate"] if latest else None,
                "source": latest["source"] if latest else "NONE",
                "period": f"{latest['year']}-{latest['month']:02d}" if latest else "",
                "history": [{"year": str(y), "cpi": rate} for y, rate in sorted(last_per_year.items())],
            }

        return cache_service.get_or_set(
            _CPI_CACHE_KEY,
            _build,
            ttl=lambda _: cache_ttl,
            validator=lambda c: isinstance(c, dict),
        )

    @staticmethod
    def get_monthly_cpi(year: Optional[int] = None) -> list[dict]:
        cached = cache_service.get_or_set(
            _MONTHLY_CACHE_KEY,
            InflationService._load_fresh_cpi_records,
            ttl=lambda records: 86400 if records else 300,
            validator=lambda c: isinstance(c, list),
        )

        if year is not None:
            return [r for r in cached if r.get("year") == year]
        return cached

    @staticmethod
    def get_nbp_reference_rates() -> list[dict]:
        def _build() -> list[dict]:
            by_date: dict[str, float] = {}
            for url in (
                "https://static.nbp.pl/dane/stopy/stopy_procentowe_archiwum.xml",
                "https://static.nbp.pl/dane/stopy/stopy_procentowe.xml",
            ):
                try:
                    resp = _http_session.get(url, timeout=8)
                    if resp.status_code != 200:
                        continue
                    root = ET.fromstring(resp.content if isinstance(resp.content, (bytes, bytearray)) else resp.text.encode("utf-8"))
                    for container in (*root.findall(".//tabela"), *root.findall(".//pozycje")):
                        parent_eff = (container.attrib.get("obowiazuje_od") or "").strip()
                        for pos in container.findall("pozycja"):
                            if (pos.attrib.get("id") or "").strip().lower() != "ref":
                                continue
                            eff = (pos.attrib.get("obowiazuje_od") or parent_eff).strip()
                            if not eff:
                                continue
                            raw_rate = (pos.attrib.get("oprocentowanie") or "").replace(",", ".")
                            parsed = finite_float(raw_rate)
                            if parsed is not None:
                                by_date[eff] = parsed
                except Exception as e:
                    logging.warning(f"Failed fetching NBP reference rates from {url}: {e}")
            return [{"effectiveFrom": d, "rate": r} for d, r in sorted(by_date.items())]

        return cache_service.get_or_set(
            "macro:nbp:reference_rates",
            _build,
            ttl=lambda rows: 86400 if rows else 300,
            validator=lambda c: isinstance(c, list),
        )
