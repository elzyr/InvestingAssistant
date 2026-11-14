import datetime
from typing import Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field, model_validator
import logging
from app.services.inflation_service import InflationService
from app.services.market_utils import CURRENCY_PATTERN, warsaw_today
from app.services.yahoo_service import YahooService, run_batch

router = APIRouter(prefix="/api/currencies", tags=["Currencies"])


class CurrencyPairItem(BaseModel):
    from_currency: str = Field(..., pattern=CURRENCY_PATTERN)
    to_currency: str = Field(..., pattern=CURRENCY_PATTERN)


class BatchFxHistoryRequest(BaseModel):
    pairs: List[CurrencyPairItem] = Field(default_factory=list, max_length=50)
    start_date: str = Field(..., pattern=r"^\d{4}-\d{2}-\d{2}$")
    end_date: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}-\d{2}$")

    @model_validator(mode="after")
    def validate_dates(self) -> "BatchFxHistoryRequest":
        today_d = warsaw_today()
        start_dt = datetime.date.fromisoformat(self.start_date)
        if start_dt > today_d:
            raise ValueError(f"Future FX start_date is not allowed: {self.start_date}")
        if self.end_date is not None:
            end_dt = datetime.date.fromisoformat(self.end_date)
            if end_dt > today_d:
                raise ValueError(f"Future FX end_date is not allowed: {self.end_date}")
            if end_dt < start_dt:
                raise ValueError("end_date must be greater than or equal to start_date")
        return self


def _fetch_pair_history(
    from_cur: str,
    to_cur: str,
    start_date: str,
    end_date: Optional[str],
) -> dict:
    if from_cur == to_cur:
        return {"from": from_cur, "to": to_cur, "spotRate": 1.0, "rates": {}}

    spot = YahooService.get_exchange_rate(from_cur, to_cur)
    rates = YahooService.get_historical_fx_rates(from_cur, to_cur, start_date, end_date)
    return {
        "from": from_cur,
        "to": to_cur,
        "spotRate": spot,
        "rates": rates,
    }


@router.get("/rate")
def get_exchange_rate(
    from_currency: str = Query(..., pattern=CURRENCY_PATTERN),
    to_currency: str = Query(..., pattern=CURRENCY_PATTERN),
    date: Optional[str] = Query(None, pattern=r"^\d{4}-\d{2}-\d{2}$"),
):
    from_currency = from_currency.upper()
    to_currency = to_currency.upper()

    if from_currency == to_currency and date is None:
        return {"from": from_currency, "to": to_currency, "rate": 1.0}

    try:
        date_val = date if isinstance(date, str) else None
        rate = YahooService.get_exchange_rate(from_currency, to_currency, date_val)
        return {"from": from_currency, "to": to_currency, "rate": rate}
    except Exception as e:
        if isinstance(e, HTTPException):
            raise e
        logging.error(f"Error fetching exchange rate: {e}")
        raise HTTPException(status_code=502, detail="Upstream market provider failure")


@router.get("/nbp-tax-rate")
def get_nbp_tax_rate(
    from_currency: str = Query(..., pattern=CURRENCY_PATTERN),
    to_currency: str = Query(..., pattern=CURRENCY_PATTERN),
    date: str = Query(..., pattern=r"^\d{4}-\d{2}-\d{2}$"),
):
    try:
        return YahooService.get_nbp_tax_rate(from_currency, to_currency, date)
    except Exception as e:
        if isinstance(e, HTTPException):
            raise e
        logging.error(f"Error fetching NBP tax exchange rate: {e}")
        raise HTTPException(status_code=502, detail="Upstream NBP tax rate failure")


@router.get("/nbp-reference-rates")
def get_nbp_reference_rates():
    return InflationService.get_nbp_reference_rates()


@router.post("/history/batch")
def get_batch_fx_history(req: BatchFxHistoryRequest):
    seen: set[tuple[str, str]] = set()
    unique_pairs: List[tuple[str, str]] = []
    results: Dict[str, dict] = {}
    for item in req.pairs:
        pair = (item.from_currency.upper(), item.to_currency.upper())
        if pair in seen:
            continue
        seen.add(pair)
        if pair[0] == pair[1]:
            results[f"{pair[0]}:{pair[1]}"] = {
                "from": pair[0],
                "to": pair[1],
                "spotRate": 1.0,
                "rates": {},
            }
        else:
            unique_pairs.append(pair)

    if not unique_pairs:
        return results

    fetched = run_batch(
        unique_pairs,
        lambda pair: _fetch_pair_history(pair[0], pair[1], req.start_date, req.end_date),
        error_label="Batch FX history",
    )
    for (fc, tc), val in fetched.items():
        results[f"{fc}:{tc}"] = val
    return results
