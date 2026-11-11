import i18n from '../../i18n/config.ts';
import { createSlice, type PayloadAction } from '@reduxjs/toolkit';
import type {
  PortfolioResponse,
  PositionResponse,
  TransactionResponse,
} from '../../types/portfolio.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { PriceQuote } from '../../services/market.service.ts';
import type { PortfolioStatsResponse } from '../../types/analytics.types.ts';
import {
  fetchPortfolios,
  fetchPositions,
  fetchTransactions,
  fetchHistoricalPrices,
  fetchInflation,
  fetchMonthlyInflation,
  fetchPortfolioStats,
  createTransaction,
  updateTransaction,
  deleteTransaction,
  createPortfolio,
} from '../thunks/portfolio.thunk.ts';
import { logoutThunk, checkAuthThunk } from '../thunks/auth.thunk.ts';

export interface PortfolioState {
  portfolios: PortfolioResponse[];
  activePortfolioId: string | null;
  positions: PositionResponse[];
  transactions: TransactionResponse[];
  instruments: Record<string, InstrumentResponse>;
  quotes: Record<string, PriceQuote>;
  rates: Record<string, number>;
  historicalPrices: Record<string, Record<string, number>>;
  historicalSortedDates: Record<string, string[]>;
  cpi: number | null;
  monthlyCpi: Record<number, Record<number, number>>;
  portfolioStats: Record<string, PortfolioStatsResponse>;
  loading: boolean;
  isInitialLoading: boolean;
  isPositionsLoading: boolean;
  isTransactionsLoading: boolean;
  isInflationLoading: boolean;
  error: string | null;
  positionsLastFetchedAt: Record<string, number>;
  transactionsLastFetchedAt: Record<string, number>;
}

const initialState: PortfolioState = {
  portfolios: [],
  activePortfolioId: null,
  positions: [],
  transactions: [],
  instruments: {},
  quotes: {},
  rates: { PLN: 1 },
  historicalPrices: {},
  historicalSortedDates: {},
  cpi: null,
  monthlyCpi: {},
  portfolioStats: {},
  loading: false,
  isInitialLoading: false,
  isPositionsLoading: false,
  isTransactionsLoading: false,
  isInflationLoading: false,
  error: null,
  positionsLastFetchedAt: {},
  transactionsLastFetchedAt: {},
};

export const portfolioSlice = createSlice({
  name: 'portfolio',
  initialState,
  reducers: {
    setActivePortfolioId: (state, action: PayloadAction<string>) => {
      state.activePortfolioId = action.payload;
    },
    clearPortfolioError: (state) => {
      state.error = null;
    },
    resetPortfolio: () => initialState,
  },
  extraReducers: (builder) => {
    builder
      .addCase(logoutThunk.fulfilled, () => initialState)
      .addCase(checkAuthThunk.rejected, () => initialState)
      .addCase(fetchPortfolios.pending, (state) => {
        state.loading = true;
        state.isInitialLoading = true;
        state.error = null;
      })
      .addCase(fetchPortfolios.fulfilled, (state, action) => {
        state.loading = false;
        state.isInitialLoading = false;
        state.portfolios = action.payload;
        if (!state.activePortfolioId && action.payload.length > 0) {
          state.activePortfolioId = action.payload[0].id;
        }
      })
      .addCase(fetchPortfolios.rejected, (state, action) => {
        state.loading = false;
        state.isInitialLoading = false;
        state.error = action.payload || i18n.t('errors.fetchPortfolios');
      })
      .addCase(fetchPositions.pending, (state) => {
        state.loading = state.positions.length === 0;
        state.isPositionsLoading = true;
        state.error = null;
      })
      .addCase(fetchPositions.fulfilled, (state, action) => {
        state.loading = false;
        state.isPositionsLoading = false;
        state.positions = action.payload.positions;
        state.instruments = { ...state.instruments, ...action.payload.instruments };
        state.quotes = { ...state.quotes, ...action.payload.quotes };
        state.rates = { ...state.rates, ...action.payload.rates };
        const pId = typeof action.meta.arg === 'string' ? action.meta.arg : action.meta.arg.portfolioId;
        state.positionsLastFetchedAt[pId] = Date.now();
      })
      .addCase(fetchPositions.rejected, (state, action) => {
        state.loading = false;
        state.isPositionsLoading = false;
        state.error = action.payload || i18n.t('errors.fetchPositions');
      })
      .addCase(fetchTransactions.pending, (state) => {
        state.isTransactionsLoading = true;
      })
      .addCase(fetchTransactions.fulfilled, (state, action) => {
        state.isTransactionsLoading = false;
        state.transactions = action.payload.transactions;
        state.instruments = { ...state.instruments, ...action.payload.instruments };
        state.quotes = { ...state.quotes, ...action.payload.quotes };
        state.rates = { ...state.rates, ...action.payload.rates };
        const tId = typeof action.meta.arg === 'string' ? action.meta.arg : action.meta.arg.portfolioId;
        state.transactionsLastFetchedAt[tId] = Date.now();
      })
      .addCase(fetchTransactions.rejected, (state, action) => {
        state.isTransactionsLoading = false;
        state.error = action.payload || i18n.t('errors.fetchTransactions');
      })
      .addCase(fetchHistoricalPrices.fulfilled, (state, action) => {
        state.historicalPrices[action.payload.symbol] = action.payload.prices;
        state.historicalSortedDates[action.payload.symbol] = action.payload.sortedDates;
      })
      .addCase(fetchHistoricalPrices.rejected, (state, action) => {
        if (action.payload) {
          state.error = action.payload;
        }
      })
      .addCase(fetchInflation.pending, (state) => {
        state.isInflationLoading = true;
      })
      .addCase(fetchInflation.fulfilled, (state, action) => {
        state.isInflationLoading = false;
        state.cpi = action.payload.cpi;
      })
      .addCase(fetchInflation.rejected, (state, action) => {
        state.isInflationLoading = false;
        if (action.payload) {
          state.error = action.payload;
        }
      })
      .addCase(fetchMonthlyInflation.fulfilled, (state, action) => {
        state.monthlyCpi[action.payload.year] = action.payload.data;
      })
      .addCase(fetchMonthlyInflation.rejected, (state, action) => {
        if (action.payload) {
          state.error = action.payload;
        }
      })
      .addCase(fetchPortfolioStats.fulfilled, (state, action) => {
        state.portfolioStats[action.payload.key] = action.payload.data;
      })
      .addCase(createTransaction.pending, (state) => {
        state.error = null;
      })
      .addCase(createTransaction.fulfilled, (state) => {
        state.positionsLastFetchedAt = {};
        state.transactionsLastFetchedAt = {};
        state.portfolioStats = {};
      })
      .addCase(createTransaction.rejected, (state, action) => {
        state.error = action.payload || i18n.t('errors.saveTransaction');
      })
      .addCase(updateTransaction.pending, (state) => {
        state.error = null;
      })
      .addCase(updateTransaction.fulfilled, (state) => {
        state.positionsLastFetchedAt = {};
        state.transactionsLastFetchedAt = {};
        state.portfolioStats = {};
      })
      .addCase(updateTransaction.rejected, (state, action) => {
        state.error = action.payload || i18n.t('errors.updateTransaction');
      })
      .addCase(deleteTransaction.pending, (state) => {
        state.error = null;
      })
      .addCase(deleteTransaction.fulfilled, (state) => {
        state.positionsLastFetchedAt = {};
        state.transactionsLastFetchedAt = {};
        state.portfolioStats = {};
      })
      .addCase(deleteTransaction.rejected, (state, action) => {
        state.error = action.payload || i18n.t('errors.deleteTransaction');
      })
      .addCase(createPortfolio.fulfilled, (state, action) => {
        state.portfolios.push(action.payload);
        state.activePortfolioId = action.payload.id;
      });
  },
});

export const {
  setActivePortfolioId,
  clearPortfolioError,
  resetPortfolio,
} = portfolioSlice.actions;

export default portfolioSlice.reducer;
