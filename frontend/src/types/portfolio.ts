import type { Currency } from './auth.ts';

export type TransactionType = 'BUY' | 'SELL' | 'DIVIDEND';

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
  exchangeRateToBase?: number;
  instrumentToTxRate?: number;
  withholdingTaxPct?: number;
  fxFeePct?: number;
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
  exchangeRateToBase?: number;
  instrumentToTxRate?: number;
  withholdingTaxPct?: number;
  fxFeePct?: number;
  executedAt: string;
  seriesName?: string;
  bondRate?: number;
  bondMargin?: number;
  termMonths?: number;
  baseAmount?: number;
}
