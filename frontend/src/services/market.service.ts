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

export const marketService = {
  async getPrice(symbol: string, currency?: string, signal?: AbortSignal): Promise<PriceQuote> {
    const params: Record<string, string> = {};
    if (currency) params.currency = currency;
    const response = await apiClient.get<PriceQuote>(`/api/prices/${symbol}`, {
      params,
      signal,
    });
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
