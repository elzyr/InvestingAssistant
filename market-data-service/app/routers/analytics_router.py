from typing import List, Optional, Dict
from fastapi import APIRouter
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field
from app.services.analytics_service import AnalyticsService

router = APIRouter()

class HoldingItem(BaseModel):
    symbol: str = Field(..., pattern=r"^[A-Za-z0-9.\-^=]+$", max_length=20)
    weight: Optional[float] = None
    value: Optional[float] = None

class PortfolioStatsRequest(BaseModel):
    holdings: List[HoldingItem] = Field(..., max_length=50)
    benchmarkSymbol: Optional[str] = Field("SPY", pattern=r"^[A-Za-z0-9.\-^=]+$", max_length=20)

class InstrumentMetricItem(BaseModel):
    symbol: str
    weight: float
    value: Optional[float] = None
    annualReturn: Optional[float] = None
    annualVolatility: Optional[float] = None
    ter: Optional[float] = None
    trailingPe: Optional[float] = None
    dividendYield: Optional[float] = None
    beta: Optional[float] = None
    currency: Optional[str] = "USD"

class PortfolioStatsResponse(BaseModel):
    weightedReturn: Optional[float] = None
    weightedVolatility: Optional[float] = None
    weightedTer: Optional[float] = None
    weightedPe: Optional[float] = None
    weightedDividendYield: Optional[float] = None
    portfolioBeta: Optional[float] = None
    currencyBasis: Optional[str] = "LOCAL"
    instruments: List[InstrumentMetricItem] = []
    correlationMatrix: Dict[str, Dict[str, float]] = {}

@router.post("/portfolio-stats", response_model=PortfolioStatsResponse)
async def calculate_portfolio_stats(request: PortfolioStatsRequest) -> PortfolioStatsResponse:
    metrics = await run_in_threadpool(
        AnalyticsService.calculate_portfolio_metrics,
        holdings=request.holdings,
        benchmark_symbol=request.benchmarkSymbol or "SPY",
    )
    return PortfolioStatsResponse(**metrics)
