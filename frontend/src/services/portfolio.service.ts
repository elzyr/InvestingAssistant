import apiClient from '../api/client.ts';
import type {
  PortfolioResponse,
  CreatePortfolioRequest,
  TransactionResponse,
  CreateTransactionRequest,
} from '../types/portfolio.ts';
import type {
  AnnualReportResponse,
  PortfolioTimelineResponse,
  PortfolioValuationResponse,
  ProjectionRequestPayload,
  ProjectionResponse,
} from '../types/valuation.types.ts';
import type { TaxMode, TimeframeOption } from '../types/dashboard.ts';

export const portfolioService = {
  async getPortfolios(): Promise<PortfolioResponse[]> {
    const response = await apiClient.get<PortfolioResponse[]>('/api/portfolios');
    return response.data;
  },

  async createPortfolio(data: CreatePortfolioRequest): Promise<PortfolioResponse> {
    const response = await apiClient.post<PortfolioResponse>('/api/portfolios', data);
    return response.data;
  },

  async getTransactions(portfolioId: string): Promise<TransactionResponse[]> {
    const response = await apiClient.get<TransactionResponse[]>(
      `/api/portfolios/${portfolioId}/transactions`
    );
    return response.data;
  },

  async createTransaction(
    portfolioId: string,
    data: CreateTransactionRequest
  ): Promise<TransactionResponse> {
    const response = await apiClient.post<TransactionResponse>(
      `/api/portfolios/${portfolioId}/transactions`,
      data
    );
    return response.data;
  },

  async updateTransaction(
    portfolioId: string,
    transactionId: string,
    data: CreateTransactionRequest
  ): Promise<TransactionResponse> {
    const response = await apiClient.put<TransactionResponse>(
      `/api/portfolios/${portfolioId}/transactions/${transactionId}`,
      data
    );
    return response.data;
  },

  async deleteTransaction(portfolioId: string, transactionId: string): Promise<void> {
    await apiClient.delete(`/api/portfolios/${portfolioId}/transactions/${transactionId}`);
  },

  async getValuation(
    portfolioId: string,
    taxMode: TaxMode = 'NETTO',
    signal?: AbortSignal
  ): Promise<PortfolioValuationResponse> {
    const response = await apiClient.get<PortfolioValuationResponse>(
      `/api/portfolios/${portfolioId}/valuation`,
      { params: { taxMode }, signal }
    );
    return response.data;
  },

  async getTimeline(
    portfolioId: string,
    timeframe: TimeframeOption = 'YTD',
    taxMode: TaxMode = 'NETTO',
    signal?: AbortSignal
  ): Promise<PortfolioTimelineResponse> {
    const response = await apiClient.get<PortfolioTimelineResponse>(
      `/api/portfolios/${portfolioId}/timeline`,
      { params: { timeframe, taxMode }, signal }
    );
    return response.data;
  },

  async getAnnualReport(
    portfolioId: string,
    year: number,
    taxMode: TaxMode = 'NETTO',
    signal?: AbortSignal
  ): Promise<AnnualReportResponse> {
    const response = await apiClient.get<AnnualReportResponse>(
      `/api/portfolios/${portfolioId}/reports/${year}`,
      { params: { taxMode }, signal }
    );
    return response.data;
  },

  async getProjection(
    portfolioId: string,
    payload: ProjectionRequestPayload,
    signal?: AbortSignal
  ): Promise<ProjectionResponse> {
    const response = await apiClient.post<ProjectionResponse>(
      `/api/portfolios/${portfolioId}/projection`,
      payload,
      { signal }
    );
    return response.data;
  },
};
