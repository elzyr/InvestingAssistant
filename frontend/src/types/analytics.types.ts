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
