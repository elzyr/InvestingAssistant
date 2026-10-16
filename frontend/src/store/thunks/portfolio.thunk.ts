import { createAsyncThunk } from '@reduxjs/toolkit';
import { portfolioService } from '../../services/portfolio.service.ts';
import { instrumentService } from '../../services/instrument.service.ts';
import { marketService, type PriceQuote } from '../../services/market.service.ts';
import type {
  PortfolioResponse,
  PositionResponse,
  TransactionResponse,
  CreateTransactionRequest,
} from '../../types/portfolio.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { Currency } from '../../types/auth.ts';

import { isAxiosError } from 'axios';

export const fetchPortfolios = createAsyncThunk<
  PortfolioResponse[],
  Currency | undefined,
  { rejectValue: string }
>('portfolio/fetchPortfolios', async (defaultCurrency = 'PLN', { rejectWithValue }) => {
  try {
    const portfolios = await portfolioService.getPortfolios();
    if (portfolios.length === 0) {
      const defaultPortfolio = await portfolioService.createPortfolio({
        name: 'Główny',
        baseCurrency: defaultCurrency,
      });
      return [defaultPortfolio];
    }
    return portfolios;
  } catch (error: unknown) {
    if (isAxiosError<{ message?: string }>(error)) {
      return rejectWithValue(
        error.response?.data?.message || error.message || 'Nie udało się pobrać portfeli'
      );
    }
    return rejectWithValue('Nie udało się pobrać portfeli');
  }
});

import type { RootState, AppDispatch } from '../store.ts';

export const fetchPositions = createAsyncThunk<
  {
    positions: PositionResponse[];
    instruments: Record<string, InstrumentResponse>;
    quotes: Record<string, PriceQuote>;
    rates: Record<string, number>;
    historicalPrices: Record<string, Record<string, number>>;
    historicalSortedDates: Record<string, string[]>;
  },
  string,
  { state: RootState; rejectValue: string }
>('portfolio/fetchPositions', async (portfolioId, { getState, rejectWithValue }) => {
  try {
    const currentState = getState().portfolio;
    const hasInstrumentsCached = Object.keys(currentState.instruments).length > 0;

    const [positions, fetchedInstruments] = await Promise.all([
      portfolioService.getPositions(portfolioId),
      hasInstrumentsCached ? Promise.resolve([]) : instrumentService.getInstruments(),
    ]);

    const instrumentsMap: Record<string, InstrumentResponse> = {
      ...currentState.instruments,
    };
    fetchedInstruments.forEach((inst) => {
      instrumentsMap[inst.id] = inst;
    });

    const quotes: Record<string, PriceQuote> = { ...currentState.quotes };
    const historicalPrices: Record<string, Record<string, number>> = {};
    const historicalSortedDates: Record<string, string[]> = {};

    const uniqueInstrumentIds = Array.from(new Set(positions.map((p) => p.instrumentId)));

    await Promise.all(
      uniqueInstrumentIds.map(async (instId) => {
        const inst = instrumentsMap[instId];
        if (!inst) return;

        try {
          quotes[inst.symbol] = await marketService.getPrice(inst.symbol);
        } catch {
          // Quote fallback
        }

        const isBond = inst.type === 'BOND';
        const alreadyHasHistory = !!currentState.historicalPrices[inst.symbol];

        if (!isBond && !inst.symbol.startsWith('PL_BOND') && !alreadyHasHistory) {
          try {
            const hist = await marketService.getHistoricalPrices(inst.symbol, '5y', '1d');
            if (hist && Array.isArray(hist.history) && hist.history.length > 0) {
              const dateMap: Record<string, number> = {};
              const dates: string[] = [];
              for (const pt of hist.history) {
                dateMap[pt.date] = pt.close;
                dates.push(pt.date);
              }
              dates.sort();
              historicalPrices[inst.symbol] = dateMap;
              historicalSortedDates[inst.symbol] = dates;
            }
          } catch {
            // History fallback
          }
        }
      })
    );

    const rates: Record<string, number> = { PLN: 1, ...currentState.rates };
    const distinctCurrencies = Array.from(new Set(positions.map((p) => p.currency))).filter(
      (curr): curr is Currency => !!curr && curr !== 'PLN'
    );

    await Promise.all(
      distinctCurrencies.map(async (curr) => {
        try {
          const fx = await marketService.getExchangeRate(curr, 'PLN');
          if (fx && fx.rate) {
            rates[curr] = fx.rate;
          }
        } catch {}
      })
    );

    return {
      positions,
      instruments: instrumentsMap,
      quotes,
      rates,
      historicalPrices,
      historicalSortedDates,
    };
  } catch (error: unknown) {
    if (isAxiosError<{ message?: string }>(error)) {
      return rejectWithValue(
        error.response?.data?.message || error.message || 'Nie udało się pobrać pozycji'
      );
    }
    return rejectWithValue('Nie udało się pobrać pozycji');
  }
});

export const fetchTransactions = createAsyncThunk<
  TransactionResponse[],
  string,
  { rejectValue: string }
>('portfolio/fetchTransactions', async (portfolioId, { rejectWithValue }) => {
  try {
    return await portfolioService.getTransactions(portfolioId);
  } catch (error: unknown) {
    if (isAxiosError<{ message?: string }>(error)) {
      return rejectWithValue(
        error.response?.data?.message || error.message || 'Nie udało się pobrać transakcji'
      );
    }
    return rejectWithValue('Nie udało się pobrać transakcji');
  }
});

export const createTransaction = createAsyncThunk<
  TransactionResponse,
  { portfolioId: string; request: CreateTransactionRequest },
  { dispatch: AppDispatch; rejectValue: string }
>(
  'portfolio/createTransaction',
  async ({ portfolioId, request }, { dispatch, rejectWithValue }) => {
    try {
      const response = await portfolioService.createTransaction(portfolioId, request);
      dispatch(fetchPositions(portfolioId));
      dispatch(fetchTransactions(portfolioId));
      return response;
    } catch (error: unknown) {
      if (isAxiosError<{ message?: string }>(error)) {
        return rejectWithValue(
          error.response?.data?.message || error.message || 'Nie udało się zapisać transakcji'
        );
      }
      return rejectWithValue('Nie udało się zapisać transakcji');
    }
  }
);

export const updateTransaction = createAsyncThunk<
  TransactionResponse,
  { portfolioId: string; transactionId: string; request: CreateTransactionRequest },
  { dispatch: AppDispatch; rejectValue: string }
>(
  'portfolio/updateTransaction',
  async ({ portfolioId, transactionId, request }, { dispatch, rejectWithValue }) => {
    try {
      const response = await portfolioService.updateTransaction(portfolioId, transactionId, request);
      dispatch(fetchPositions(portfolioId));
      dispatch(fetchTransactions(portfolioId));
      return response;
    } catch (error: unknown) {
      if (isAxiosError<{ message?: string }>(error)) {
        return rejectWithValue(
          error.response?.data?.message || error.message || 'Nie udało się zaktualizować transakcji'
        );
      }
      return rejectWithValue('Nie udało się zaktualizować transakcji');
    }
  }
);

export const deleteTransaction = createAsyncThunk<
  void,
  { portfolioId: string; transactionId: string },
  { dispatch: AppDispatch; rejectValue: string }
>(
  'portfolio/deleteTransaction',
  async ({ portfolioId, transactionId }, { dispatch, rejectWithValue }) => {
    try {
      await portfolioService.deleteTransaction(portfolioId, transactionId);
      dispatch(fetchPositions(portfolioId));
      dispatch(fetchTransactions(portfolioId));
    } catch (error: unknown) {
      if (isAxiosError<{ message?: string }>(error)) {
        return rejectWithValue(
          error.response?.data?.message || error.message || 'Nie udało się usunąć transakcji'
        );
      }
      return rejectWithValue('Nie udało się usunąć transakcji');
    }
  }
);

export const createPortfolio = createAsyncThunk<
  PortfolioResponse,
  { name: string; description?: string; baseCurrency: Currency },
  { rejectValue: string }
>('portfolio/createPortfolio', async (request, { rejectWithValue }) => {
  try {
    const response = await portfolioService.createPortfolio(request);
    return response;
  } catch (error: unknown) {
    if (isAxiosError<{ message?: string }>(error)) {
      return rejectWithValue(
        error.response?.data?.message || error.message || 'Nie udało się utworzyć portfela'
      );
    }
    return rejectWithValue('Nie udało się utworzyć portfela');
  }
});

