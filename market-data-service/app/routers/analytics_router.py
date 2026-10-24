from typing import List, Optional
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
    annualReturn: float
    annualVolatility: float
    ter: float
    trailingPe: float
    dividendYield: float
    beta: float

class PortfolioStatsResponse(BaseModel):
    weightedReturn: float
    weightedVolatility: float
    weightedTer: float
    weightedPe: float
    weightedDividendYield: float = 0.0
    portfolioBeta: float
    instruments: List[InstrumentMetricItem] = []

@router.post("/portfolio-stats", response_model=PortfolioStatsResponse)
async def calculate_portfolio_stats(request: PortfolioStatsRequest) -> PortfolioStatsResponse:
    metrics = await run_in_threadpool(
        AnalyticsService.calculate_portfolio_metrics,
        holdings=request.holdings,
        benchmark_symbol=request.benchmarkSymbol or "SPY",
    )
    return PortfolioStatsResponse(**metrics)
