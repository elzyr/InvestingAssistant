from typing import Optional
from fastapi import APIRouter, Query
from app.services.inflation_service import InflationService

router = APIRouter(prefix="/api/market/inflation", tags=["Inflation"])

@router.get("")
def get_inflation():
    return InflationService.get_cpi()

@router.get("/monthly")
def get_monthly_inflation(year: Optional[int] = Query(None)):
    return InflationService.get_monthly_cpi(year)
