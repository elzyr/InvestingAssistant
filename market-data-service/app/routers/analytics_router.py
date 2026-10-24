from typing import List, Optional
from fastapi import APIRouter
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel
from app.services.analytics_service import AnalyticsService

router = APIRouter()

class HoldingItem(BaseModel):
    symbol: str
    weight: Optional[float] = None
    value: Optional[float] = None

class PortfolioStatsRequest(BaseModel):
    holdings: List[HoldingItem]
    benchmarkSymbol: Optional[str] = "SPY"

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
    dailyReturns: Optional[List[float]] = []

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
