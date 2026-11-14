from typing import Any, List, Optional, Dict
from fastapi import APIRouter, Query
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field, field_validator
from app.services.analytics_service import AnalyticsService
from app.services.inflation_service import InflationService
from app.services.market_utils import SYMBOL_PATTERN, normalize_symbol
from app.services.monte_carlo_service import run_monte_carlo

router = APIRouter(prefix="/api/market", tags=["Market"])

class HoldingItem(BaseModel):
    symbol: str = Field(..., pattern=SYMBOL_PATTERN, max_length=20)
    weight: Optional[float] = Field(None, ge=0.0, allow_inf_nan=False)
    value: Optional[float] = Field(None, ge=0.0, allow_inf_nan=False)

    @field_validator("symbol", mode="before")
    @classmethod
    def validate_and_normalize_symbol(cls, val: Any) -> str:
        if not isinstance(val, str):
            raise ValueError("Symbol must be a string")
        return normalize_symbol(val)

class PortfolioStatsRequest(BaseModel):
    holdings: List[HoldingItem] = Field(..., max_length=50)

class InstrumentMetricItem(BaseModel):
    symbol: str
    weight: float
    value: Optional[float] = None
    annualReturn: Optional[float] = None
    annualVolatility: Optional[float] = None
    ter: Optional[float] = None
    currency: Optional[str] = None

class PortfolioStatsResponse(BaseModel):
    weightedReturn: Optional[float] = None
    weightedVolatility: Optional[float] = None
    weightedTer: Optional[float] = None
    instruments: List[InstrumentMetricItem] = []
    symbols: List[str] = []
    correlationMatrix: Dict[str, Dict[str, float]] = {}
    covarianceMatrix: Dict[str, Dict[str, float]] = {}

@router.post("/analytics/portfolio-stats", response_model=PortfolioStatsResponse)
async def calculate_portfolio_stats(request: PortfolioStatsRequest) -> PortfolioStatsResponse:
    metrics = await run_in_threadpool(
        AnalyticsService.calculate_portfolio_metrics,
        holdings=request.holdings,
    )
    return PortfolioStatsResponse(**metrics)


@router.get("/inflation")
def get_inflation():
    return InflationService.get_cpi()


@router.get("/inflation/monthly")
def get_monthly_inflation(year: Optional[int] = Query(None, ge=2000, le=2100)):
    return InflationService.get_monthly_cpi(year)


class MonteCarloEquityHolding(BaseModel):
    symbol: str = Field(..., pattern=SYMBOL_PATTERN, max_length=20)
    categoryCode: Optional[str] = None
    valueBase: float = Field(0.0, ge=0.0, allow_inf_nan=False)
    weight: Optional[float] = Field(None, ge=0.0, allow_inf_nan=False)

    @field_validator("symbol", mode="before")
    @classmethod
    def validate_and_normalize_symbol(cls, val: Any) -> str:
        if not isinstance(val, str):
            raise ValueError("Symbol must be a string")
        return normalize_symbol(val)


class MonteCarloBondSeed(BaseModel):
    startFixedValue: float = Field(0.0, ge=0.0, allow_inf_nan=False)
    startIndexedValue: float = Field(0.0, ge=0.0, allow_inf_nan=False)
    fixedRatePct: Optional[float] = Field(None, ge=0.0, allow_inf_nan=False)
    indexedRatePct: Optional[float] = Field(None, ge=0.0, allow_inf_nan=False)
    avgRemainingMonths: Optional[int] = Field(None, ge=1, le=240)


class MonteCarloRequest(BaseModel):
    horizonYears: int = Field(..., ge=1, le=40)
    startDate: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}-\d{2}$")
    equityHoldings: List[MonteCarloEquityHolding] = Field(default_factory=list, max_length=50)
    bondSeed: MonteCarloBondSeed = Field(default_factory=MonteCarloBondSeed)
    startInvestedCapital: float = Field(0.0, ge=0.0, allow_inf_nan=False)
    monthlyDca: float = Field(0.0, ge=0.0, allow_inf_nan=False)
    dcaEquityShare: float = Field(0.5, ge=0.0, le=1.0, allow_inf_nan=False)
    taxMode: str = Field("NETTO", pattern=r"^(NETTO|BRUTTO)$")
    belkaTaxRate: Optional[float] = Field(None, ge=0.0, le=1.0, allow_inf_nan=False)
    realMode: bool = False
    bondRollover: bool = True
    cpiPct: Optional[float] = Field(None, ge=-20.0, le=100.0, allow_inf_nan=False)
    targetGoal: Optional[float] = Field(None, gt=0.0, allow_inf_nan=False)
    etfReturnOverridePct: Optional[float] = Field(None, ge=-50.0, le=100.0, allow_inf_nan=False)
    seed: Optional[int] = 42


@router.post("/analytics/monte-carlo")
async def calculate_monte_carlo(request: MonteCarloRequest):
    return await run_in_threadpool(run_monte_carlo, request.model_dump())
