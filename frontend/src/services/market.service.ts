import apiClient from '../api/client.ts';
import type {
  PortfolioStatsRequest,
  PortfolioStatsResponse,
} from '../types/analytics.types.ts';

export interface PriceQuote {
  symbol: string;
  price: number | null;
  previousClose?: number;
  change?: number;
  changePercent?: number;
  currency: string;
  source: string;
  isUnavailable?: boolean;
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

export const marketService = {
  async getBatchPrices(
    symbols: string[],
    targetCurrency?: string,
    signal?: AbortSignal
  ): Promise<Record<string, PriceQuote>> {
    if (!symbols || symbols.length === 0) {
      return {};
    }
    const response = await apiClient.post<Record<string, PriceQuote>>(
      '/api/prices/batch',
      {
        symbols,
        currency: targetCurrency,
      },
      { signal }
    );
    return response.data;
  },

  async getPrice(symbol: string, currency?: string, signal?: AbortSignal): Promise<PriceQuote> {
    const params: Record<string, string> = {};
    if (currency) params.currency = currency;
    const response = await apiClient.get<PriceQuote>(`/api/prices/${symbol}`, {
      params,
      signal,
    });
    return response.data;
  },

  async getExchangeRate(
    fromCurrency: string,
    toCurrency: string,
    signal?: AbortSignal
  ): Promise<ExchangeRate> {
    const response = await apiClient.get<ExchangeRate>('/api/currencies/rate', {
      params: {
        from_currency: fromCurrency,
        to_currency: toCurrency,
      },
      signal,
    });
    return response.data;
  },

  async getInflation(signal?: AbortSignal): Promise<InflationData> {
    const response = await apiClient.get<InflationData>('/api/market/inflation', { signal });
    return response.data;
  },

  async getMonthlyInflation(year?: number, signal?: AbortSignal): Promise<MonthlyInflationPoint[]> {
    const response = await apiClient.get<MonthlyInflationPoint[]>('/api/market/inflation/monthly', {
      params: year ? { year } : {},
      signal,
    });
    return response.data;
  },

  async getHistoricalPrices(
    symbol: string,
    period = '5y',
    interval = '1d',
    currency?: string,
    signal?: AbortSignal
  ): Promise<HistoricalPriceResponse> {
    const params: Record<string, string> = { period, interval };
    if (currency) params.currency = currency;
    const response = await apiClient.get<HistoricalPriceResponse>(
      `/api/prices/${symbol}/history`,
      { params, signal }
    );
    return response.data;
  },

  async getPortfolioStats(
    request: PortfolioStatsRequest,
    signal?: AbortSignal
  ): Promise<PortfolioStatsResponse> {
    const response = await apiClient.post<PortfolioStatsResponse>(
      '/api/market/analytics/portfolio-stats',
      request,
      { signal }
    );
    return response.data;
  },
};
