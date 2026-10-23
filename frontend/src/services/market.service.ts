import apiClient from '../api/client.ts';
import type {
  PortfolioStatsRequest,
  PortfolioStatsResponse,
  MonteCarloRequest,
  MonteCarloResponse,
} from '../types/analytics.types.ts';

export interface PriceQuote {
  symbol: string;
  price: number;
  previousClose?: number;
  change?: number;
  changePercent?: number;
  currency: string;
  source: string;
}

export interface ExchangeRate {
  from: string;
  to: string;
  rate: number;
}

export interface InflationData {
  currentCpi: number | null;
  source: string;
  history?: Array<{ year: string; cpi: number }>;
}

export interface MonthlyInflationPoint {
  year: number;
  month: number;
  rate: number;
  source: string;
}

export const marketService = {
  async getPrice(symbol: string, currency?: string): Promise<PriceQuote> {
    const params: Record<string, string> = {};
    if (currency) params.currency = currency;
    const response = await apiClient.get<PriceQuote>(`/api/prices/${symbol}`, {
      params,
    });
    return response.data;
  },

  async getExchangeRate(
    fromCurrency: string,
    toCurrency: string
  ): Promise<ExchangeRate> {
    const response = await apiClient.get<ExchangeRate>('/api/currencies/rate', {
      params: {
        from_currency: fromCurrency,
        to_currency: toCurrency,
      },
    });
    return response.data;
  },

  async getInflation(): Promise<InflationData> {
    const response = await apiClient.get<InflationData>('/api/market/inflation');
    return response.data;
  },

  async getMonthlyInflation(year?: number): Promise<MonthlyInflationPoint[]> {
    const response = await apiClient.get<MonthlyInflationPoint[]>('/api/market/inflation/monthly', {
      params: year ? { year } : {},
    });
    return response.data;
  },

  async getHistoricalPrices(
    symbol: string,
    period = '5y',
    interval = '1d',
    currency?: string
  ): Promise<HistoricalPriceResponse> {
    const params: Record<string, string> = { period, interval };
    if (currency) params.currency = currency;
    const response = await apiClient.get<HistoricalPriceResponse>(
      `/api/prices/${symbol}/history`,
      { params }
    );
    return response.data;
  },

  async getPortfolioStats(request: PortfolioStatsRequest): Promise<PortfolioStatsResponse> {
    const response = await apiClient.post<PortfolioStatsResponse>(
      '/api/market/analytics/portfolio-stats',
      request
    );
    return response.data;
  },

  async runMonteCarlo(request: MonteCarloRequest): Promise<MonteCarloResponse> {
    const response = await apiClient.post<MonteCarloResponse>(
      '/api/market/analytics/monte-carlo',
      request
    );
    return response.data;
  },
};

export interface HistoricalPricePoint {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface HistoricalPriceResponse {
  symbol: string;
  currency: string;
  history: HistoricalPricePoint[];
  source: string;
}
