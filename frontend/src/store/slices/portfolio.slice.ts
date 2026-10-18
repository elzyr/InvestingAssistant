import { createSlice, type PayloadAction } from '@reduxjs/toolkit';
import type {
  PortfolioResponse,
  PositionResponse,
  TransactionResponse,
} from '../../types/portfolio.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { PriceQuote } from '../../services/market.service.ts';
import {
  fetchPortfolios,
  fetchPositions,
  fetchTransactions,
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
  loading: boolean;
  transactionSubmitting: boolean;
  error: string | null;
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
  loading: false,
  transactionSubmitting: false,
  error: null,
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
        state.error = null;
      })
      .addCase(fetchPortfolios.fulfilled, (state, action) => {
        state.loading = false;
        state.portfolios = action.payload;
        if (!state.activePortfolioId && action.payload.length > 0) {
          state.activePortfolioId = action.payload[0].id;
        }
      })
      .addCase(fetchPortfolios.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Błąd pobierania portfeli';
      })
      .addCase(fetchPositions.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchPositions.fulfilled, (state, action) => {
        state.loading = false;
        state.positions = action.payload.positions;
        state.instruments = { ...state.instruments, ...action.payload.instruments };
        state.quotes = { ...state.quotes, ...action.payload.quotes };
        state.rates = { ...state.rates, ...action.payload.rates };
        state.historicalPrices = { ...state.historicalPrices, ...action.payload.historicalPrices };
        state.historicalSortedDates = { ...state.historicalSortedDates, ...action.payload.historicalSortedDates };
      })
      .addCase(fetchPositions.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Błąd pobierania pozycji';
      })
      .addCase(fetchTransactions.fulfilled, (state, action) => {
        state.transactions = action.payload;
      })
      .addCase(createTransaction.pending, (state) => {
        state.transactionSubmitting = true;
        state.error = null;
      })
      .addCase(createTransaction.fulfilled, (state) => {
        state.transactionSubmitting = false;
      })
      .addCase(createTransaction.rejected, (state, action) => {
        state.transactionSubmitting = false;
        state.error = action.payload || 'Błąd dodawania transakcji';
      })
      .addCase(updateTransaction.pending, (state) => {
        state.transactionSubmitting = true;
        state.error = null;
      })
      .addCase(updateTransaction.fulfilled, (state) => {
        state.transactionSubmitting = false;
      })
      .addCase(updateTransaction.rejected, (state, action) => {
        state.transactionSubmitting = false;
        state.error = action.payload || 'Błąd aktualizacji transakcji';
      })
      .addCase(deleteTransaction.pending, (state) => {
        state.transactionSubmitting = true;
        state.error = null;
      })
      .addCase(deleteTransaction.fulfilled, (state) => {
        state.transactionSubmitting = false;
      })
      .addCase(deleteTransaction.rejected, (state, action) => {
        state.transactionSubmitting = false;
        state.error = action.payload || 'Błąd usuwania transakcji';
      })
      .addCase(createPortfolio.fulfilled, (state, action) => {
        state.portfolios.push(action.payload);
        state.activePortfolioId = action.payload.id;
      });
  },
});

export const { setActivePortfolioId, clearPortfolioError, resetPortfolio } = portfolioSlice.actions;
export default portfolioSlice.reducer;
