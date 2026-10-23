export interface HoldingItem {
  symbol: string;
  weight?: number;
  value?: number;
}

export interface PortfolioStatsRequest {
  holdings: HoldingItem[];
  benchmarkSymbol?: string;
}

export interface InstrumentMetric {
  symbol: string;
  weight: number;
  value?: number;
  annualReturn: number;
  annualVolatility: number;
  ter: number;
  trailingPe: number;
  dividendYield: number;
  beta: number;
  dailyReturns?: number[];
}

export interface PortfolioStatsResponse {
  weightedReturn: number;
  weightedVolatility: number;
  weightedTer: number;
  weightedPe: number;
  weightedDividendYield: number;
  portfolioBeta: number;
  instruments: InstrumentMetric[];
}

export interface MonteCarloRequest {
  initialAmount: number;
  monthlyContribution: number;
  years: number;
  simulationsCount?: number;
  equityWeight: number;
  equityReturn: number;
  equityVolatility: number;
  equityTer: number;
  bondWeight: number;
  bondRate: number;
  isTaxExempt: boolean;
  targetGoal?: number;
}

export interface QuantileSummary {
  p10Final: number;
  p50Final: number;
  p90Final: number;
  totalInvested: number;
  totalFeeDrag: number;
  successRate: number;
}

export interface TimelinePoint {
  year: number;
  p10: number;
  p50: number;
  p90: number;
  feeDrag: number;
  invested: number;
}

export interface MonteCarloResponse {
  summary: QuantileSummary;
  timeline: TimelinePoint[];
}
