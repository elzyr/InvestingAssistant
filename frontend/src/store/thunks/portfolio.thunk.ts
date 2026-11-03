import i18n from '../../i18n/config.ts';
import { createAsyncThunk } from '@reduxjs/toolkit';
import { isAxiosError } from 'axios';
import { portfolioService } from '../../services/portfolio.service.ts';
import { instrumentService } from '../../services/instrument.service.ts';
import { marketService, type PriceQuote } from '../../services/market.service.ts';
import type {
  PortfolioResponse,
  CreatePortfolioRequest,
  PositionResponse,
  TransactionResponse,
  CreateTransactionRequest,
} from '../../types/portfolio.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { Currency } from '../../types/auth.ts';
import type { RootState, AppDispatch } from '../store.ts';

function getErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError<{ message?: string }>(error)) {
    return error.response?.data?.message || error.message || fallback;
  }
  return fallback;
}

export const fetchPortfolios = createAsyncThunk<
  PortfolioResponse[],
  Currency | undefined,
  { rejectValue: string }
>('portfolio/fetchPortfolios', async (defaultCurrency = 'PLN', { rejectWithValue }) => {
  try {
    const portfolios = await portfolioService.getPortfolios();
    if (portfolios.length === 0) {
      const defaultPortfolio = await portfolioService.createPortfolio({
        name: i18n.t('common.defaultPortfolioName'),
        baseCurrency: defaultCurrency,
      });
      return [defaultPortfolio];
    }
    return portfolios;
  } catch (error: unknown) {
    return rejectWithValue(getErrorMessage(error, i18n.t('errors.fetchPortfolios')));
  }
});

export const fetchPositions = createAsyncThunk<
  {
    positions: PositionResponse[];
    instruments: Record<string, InstrumentResponse>;
    quotes: Record<string, PriceQuote>;
    rates: Record<string, number>;
    historicalPrices: Record<string, Record<string, number>>;
    historicalSortedDates: Record<string, string[]>;
  },
  string | { portfolioId: string; force?: boolean },
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchPositions',
  async (arg, { getState, rejectWithValue }) => {
    const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
    try {
      const currentState = getState().portfolio;
      const positions = await portfolioService.getPositions(portfolioId);
      const missingInstrument = positions.some((p) => !currentState.instruments[p.instrumentId]);
      const fetchedInstruments =
        Object.keys(currentState.instruments).length > 0 && !missingInstrument
          ? []
          : await instrumentService.getInstruments();

      const instrumentsMap: Record<string, InstrumentResponse> = {
        ...currentState.instruments,
      };
      fetchedInstruments.forEach((inst) => {
        instrumentsMap[inst.id] = inst;
      });

      const activePortfolio = currentState.portfolios.find((p) => p.id === portfolioId);
      const baseCurrency = activePortfolio?.baseCurrency || 'PLN';

      const quotes: Record<string, PriceQuote> = { ...currentState.quotes };
      const historicalPrices: Record<string, Record<string, number>> = {
        ...currentState.historicalPrices,
      };
      const historicalSortedDates: Record<string, string[]> = {
        ...currentState.historicalSortedDates,
      };

      const uniqueInstrumentIds = Array.from(new Set(positions.map((p) => p.instrumentId)));
      const distinctSymbols = Array.from(
        new Set(
          uniqueInstrumentIds
            .map((instId) => instrumentsMap[instId]?.symbol)
            .filter((sym): sym is string => Boolean(sym))
        )
      );

      if (distinctSymbols.length > 0) {
        try {
          const batchQuotes = await marketService.getBatchPrices(distinctSymbols);
          Object.assign(quotes, batchQuotes);
        } catch {
          void 0;
        }
        for (const sym of distinctSymbols) {
          if (!quotes[sym]) {
            const inst = Object.values(instrumentsMap).find((i) => i.symbol === sym);
            quotes[sym] = {
              symbol: sym,
              price: null,
              currency: inst?.currency || 'USD',
              source: 'unavailable',
              isUnavailable: true,
            };
          }
        }
      }
      const rates: Record<string, number> = { [baseCurrency]: 1 };
      const distinctCurrencies = Array.from(new Set(positions.map((p) => p.currency))).filter(
        (curr): curr is Currency => !!curr && curr !== baseCurrency
      );

      await Promise.all(
        distinctCurrencies.map(async (curr) => {
          try {
            const fx = await marketService.getExchangeRate(curr, baseCurrency);
            if (fx && fx.rate) {
              rates[curr] = fx.rate;
            }
          } catch {
            void 0;
          }
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
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.fetchPositions')));
    }
  },
  {
    condition: (arg, { getState }) => {
      const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
      const force = typeof arg === 'object' && Boolean(arg.force);
      const last = getState().portfolio.positionsLastFetchedAt?.[portfolioId];
      if (!force && last && Date.now() - last < 60000) {
        return false;
      }
      return true;
    },
  }
);

export const fetchTransactions = createAsyncThunk<
  {
    transactions: TransactionResponse[];
    instruments?: Record<string, InstrumentResponse>;
    quotes?: Record<string, PriceQuote>;
    rates?: Record<string, number>;
  },
  string | { portfolioId: string; force?: boolean },
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchTransactions',
  async (arg, { getState, rejectWithValue }) => {
    const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
    try {
      const txs = await portfolioService.getTransactions(portfolioId);
      const state = getState().portfolio;
      let instrumentsMap: Record<string, InstrumentResponse> | undefined;
      let ratesMap: Record<string, number> | undefined;

      if (
        Object.keys(state.instruments).length === 0 ||
        txs.some((t) => !state.instruments[t.instrumentId])
      ) {
        try {
          const fetchedInstruments = await instrumentService.getInstruments();
          instrumentsMap = { ...state.instruments };
          for (const inst of fetchedInstruments) {
            instrumentsMap[inst.id] = inst;
          }
        } catch {
          void 0;
        }
      }

      const effectiveInstruments = instrumentsMap || state.instruments;
      const distinctTxSymbols = Array.from(
        new Set(
          txs
            .map((t) => effectiveInstruments[t.instrumentId]?.symbol)
            .filter((sym): sym is string => Boolean(sym && !sym.startsWith('PL_BOND') && !state.quotes[sym]))
        )
      );
      let quotesMap: Record<string, PriceQuote> | undefined;
      if (distinctTxSymbols.length > 0) {
        try {
          quotesMap = await marketService.getBatchPrices(distinctTxSymbols);
        } catch {
          void 0;
        }
      }

      const activePortfolio = state.portfolios.find((p) => p.id === portfolioId);
      const baseCurrency = activePortfolio?.baseCurrency || 'PLN';
      const foreignCurrencies = Array.from(new Set(txs.map((t) => t.currency))).filter(
        (c): c is Currency => Boolean(c && c !== baseCurrency && !state.rates[c])
      );
      if (foreignCurrencies.length > 0) {
        ratesMap = { ...state.rates };
        await Promise.all(
          foreignCurrencies.map(async (curr) => {
            try {
              const fx = await marketService.getExchangeRate(curr, baseCurrency);
              if (fx && fx.rate) {
                ratesMap![curr] = fx.rate;
              }
            } catch {
              void 0;
            }
          })
        );
      }

      return { transactions: txs, instruments: instrumentsMap, quotes: quotesMap, rates: ratesMap };
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.fetchTransactions')));
    }
  },
  {
    condition: (arg, { getState }) => {
      const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
      const force = typeof arg === 'object' && Boolean(arg.force);
      const last = getState().portfolio.transactionsLastFetchedAt?.[portfolioId];
      if (!force && last && Date.now() - last < 60000) {
        return false;
      }
      return true;
    },
  }
);

export const fetchInflation = createAsyncThunk<
  { cpi: number | null },
  void,
  { rejectValue: string }
>('portfolio/fetchInflation', async (_, { rejectWithValue }) => {
  try {
    const data = await marketService.getInflation();
    return { cpi: data.currentCpi };
  } catch (error: unknown) {
    return rejectWithValue(getErrorMessage(error, i18n.t('errors.fetchInflation')));
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
      dispatch(fetchPositions({ portfolioId, force: true }));
      dispatch(fetchTransactions({ portfolioId, force: true }));
      return response;
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.saveTransaction')));
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
      dispatch(fetchPositions({ portfolioId, force: true }));
      dispatch(fetchTransactions({ portfolioId, force: true }));
      return response;
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.updateTransaction')));
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
      dispatch(fetchPositions({ portfolioId, force: true }));
      dispatch(fetchTransactions({ portfolioId, force: true }));
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.deleteTransaction')));
    }
  }
);

export const createPortfolio = createAsyncThunk<
  PortfolioResponse,
  CreatePortfolioRequest,

  { rejectValue: string }
>(
  'portfolio/createPortfolio',
  async (request, { rejectWithValue }) => {
    try {
      return await portfolioService.createPortfolio(request);
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.createPortfolio')));
    }
  }
);

export const fetchHistoricalPrices = createAsyncThunk<
  {
    symbol: string;
    prices: Record<string, number>;
    sortedDates: string[];
  },
  { symbol: string; period?: string; interval?: string; currency?: string; force?: boolean },
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchHistoricalPrices',
  async ({ symbol, period = '5y', interval = '1d', currency }, { rejectWithValue }) => {
    try {
      const hist = await marketService.getHistoricalPrices(symbol, period, interval, currency);
      const dateMap: Record<string, number> = {};
      const dates: string[] = [];
      if (hist && Array.isArray(hist.history)) {
        for (const pt of hist.history) {
          dateMap[pt.date] = pt.close;
          dates.push(pt.date);
        }
        dates.sort();
      }
      return { symbol, prices: dateMap, sortedDates: dates };
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.fetchHistory', { symbol })));
    }
  },
  {
    condition: ({ symbol, force }, { getState }) => {
      if (force) return true;
      const existing = getState().portfolio.historicalPrices[symbol];
      return !existing || Object.keys(existing).length === 0;
    },
  }
);
