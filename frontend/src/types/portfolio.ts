import type { Currency } from './auth.ts';

export type TransactionType = 'BUY' | 'SELL';

export interface CreatePortfolioRequest {
  name: string;
  description?: string;
  baseCurrency: Currency;
}

export interface PortfolioResponse {
  id: string;
  userId: string;
  name: string;
  description?: string;
  baseCurrency: Currency;
  createdAt: string;
}

export interface CreateTransactionRequest {
  instrumentId: string;
  transactionType: TransactionType;
  price: number;
  quantity: number;
  commission: number;
  currency: Currency;
  executedAt?: string;
  seriesName?: string;
  bondRate?: number;
  bondMargin?: number;
  termMonths?: number;
  baseAmount?: number;
}

export interface TransactionResponse {
  id: string;
  instrumentId: string;
  transactionType: TransactionType;
  quantity: number;
  price: number;
  commission: number;
  currency: Currency;
  exchangeRate: number;
  executedAt: string;
  seriesName?: string;
  bondRate?: number;
  bondMargin?: number;
  termMonths?: number;
  baseAmount?: number;
}

export interface PositionResponse {
  id: string;
  instrumentId: string;
  quantity: number;
  averageBuyPrice: number;
  currency: Currency;
  seriesName?: string;
  bondRate?: number;
  bondMargin?: number;
  termMonths?: number;
  purchaseDate?: string;
  totalCostBase?: number;
}
