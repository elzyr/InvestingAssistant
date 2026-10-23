from typing import List, Optional, Dict, Any
from fastapi import APIRouter
from pydantic import BaseModel, Field
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

class MonteCarloRequest(BaseModel):
    initialAmount: float = 50000.0
    monthlyContribution: float = 0.0
    years: int = 10
    simulationsCount: int = 1000
    equityWeight: float = 1.0
    equityReturn: float = 0.08
    equityVolatility: float = 0.15
    equityTer: float = 0.002
    bondWeight: float = 0.0
    bondRate: float = 0.06
    isTaxExempt: bool = True
    targetGoal: Optional[float] = None

class QuantileSummary(BaseModel):
    p10Final: float
    p50Final: float
    p90Final: float
    totalInvested: float
    totalFeeDrag: float
    successRate: float

class TimelinePoint(BaseModel):
    year: int
    p10: float
    p50: float
    p90: float
    feeDrag: float
    invested: float

class MonteCarloResponse(BaseModel):
    summary: QuantileSummary
    timeline: List[TimelinePoint]

@router.post("/portfolio-stats", response_model=PortfolioStatsResponse)
async def calculate_portfolio_stats(request: PortfolioStatsRequest) -> PortfolioStatsResponse:
    metrics = AnalyticsService.calculate_portfolio_metrics(
        holdings=request.holdings,
        benchmark_symbol=request.benchmarkSymbol or "SPY"
    )
    return PortfolioStatsResponse(**metrics)

@router.post("/monte-carlo", response_model=MonteCarloResponse)
async def run_monte_carlo(request: MonteCarloRequest) -> MonteCarloResponse:
    result = AnalyticsService.simulate_monte_carlo(
        initial_amount=request.initialAmount,
        monthly_contribution=request.monthlyContribution,
        years=request.years,
        simulations_count=request.simulationsCount,
        equity_weight=request.equityWeight,
        equity_return=request.equityReturn,
        equity_volatility=request.equityVolatility,
        equity_ter=request.equityTer,
        bond_weight=request.bondWeight,
        bond_rate=request.bondRate,
        is_tax_exempt=request.isTaxExempt,
        target_goal=request.targetGoal
    )
    return MonteCarloResponse(**result)
