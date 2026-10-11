import apiClient from '../api/client.ts';
import type {
  PortfolioResponse,
  CreatePortfolioRequest,
  PositionResponse,
  TransactionResponse,
  CreateTransactionRequest,
} from '../types/portfolio.ts';

export const portfolioService = {
  async getPortfolios(): Promise<PortfolioResponse[]> {
    const response = await apiClient.get<PortfolioResponse[]>('/api/portfolios');
    return response.data;
  },

  async createPortfolio(data: CreatePortfolioRequest): Promise<PortfolioResponse> {
    const response = await apiClient.post<PortfolioResponse>('/api/portfolios', data);
    return response.data;
  },

  async getPositions(portfolioId: string): Promise<PositionResponse[]> {
    const response = await apiClient.get<PositionResponse[]>(
      `/api/portfolios/${portfolioId}/positions`
    );
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
};
