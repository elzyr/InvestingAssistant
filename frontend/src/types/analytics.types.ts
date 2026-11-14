export interface HoldingItem {
  symbol: string;
  weight?: number;
  value?: number;
}

export interface PortfolioStatsRequest {
  holdings: HoldingItem[];
}

export interface InstrumentMetric {
  symbol: string;
  weight: number;
  value?: number;
  annualReturn: number | null;
  annualVolatility: number | null;
  ter: number | null;
  currency?: string | null;
}

export interface PortfolioStatsResponse {
  weightedReturn: number | null;
  weightedVolatility: number | null;
  weightedTer: number | null;
  instruments: InstrumentMetric[];
  symbols?: string[];
  correlationMatrix?: Record<string, Record<string, number>>;
  covarianceMatrix?: Record<string, Record<string, number>>;
}
