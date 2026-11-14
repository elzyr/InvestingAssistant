import bisect
import datetime
import re
from typing import Any, List, Optional
from zoneinfo import ZoneInfo
import numpy as np

SYMBOL_PATTERN = r"^[A-Za-z0-9.\-^=_]{1,20}$"
CURRENCY_PATTERN = r"^[A-Za-z]{3}$"
_SYMBOL_RE = re.compile(SYMBOL_PATTERN)
_CURRENCY_RE = re.compile(CURRENCY_PATTERN)
WARSAW_TZ = ZoneInfo("Europe/Warsaw")


def warsaw_today() -> datetime.date:
    return datetime.datetime.now(WARSAW_TZ).date()


def normalize_symbol(raw: str) -> str:
    sym = (raw or "").strip().upper()
    if not _SYMBOL_RE.match(sym):
        raise ValueError(f"Invalid symbol format: {raw}")
    return sym


def normalize_symbol_list(values: List[str]) -> List[str]:
    cleaned: List[str] = []
    for raw in values:
        raw_stripped = (raw or "").strip()
        if not raw_stripped:
            continue
        cleaned.append(normalize_symbol(raw_stripped))
    return list(dict.fromkeys(cleaned))


def validate_currency_code(code: Optional[str]) -> Optional[str]:
    if not code:
        return None
    cleaned = str(code).strip().upper()
    return cleaned if _CURRENCY_RE.match(cleaned) else None


def resolve_fx_on_or_before(
    fx_dates: List[str],
    fx_rates: List[float],
    target_date: str,
) -> Optional[float]:
    idx = bisect.bisect_right(fx_dates, target_date) - 1
    return fx_rates[idx] if idx >= 0 else None


def nearest_psd(mat: np.ndarray, floor: float = 1e-8) -> np.ndarray:
    sym = 0.5 * (mat + mat.T)
    vals, vecs = np.linalg.eigh(sym)
    clipped = np.maximum(vals, floor)
    psd = (vecs * clipped) @ vecs.T
    return 0.5 * (psd + psd.T)
