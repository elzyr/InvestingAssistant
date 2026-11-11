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
import type { HoldingItem, PortfolioStatsResponse } from '../../types/analytics.types.ts';
import type { Currency } from '../../types/auth.ts';
import type { RootState, AppDispatch } from '../store.ts';

function getErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError<{ message?: string }>(error)) {
    return error.response?.data?.message || error.message || fallback;
  }
  return fallback;
}

let inFlightPortfolios = false;
let inFlightInstruments: Promise<InstrumentResponse[]> | null = null;
const inFlightQuotes = new Map<string, Promise<Record<string, PriceQuote>>>();
const inFlightFxRates = new Map<string, Promise<number | null>>();
const inFlightPositions = new Set<string>();
const inFlightTransactions = new Set<string>();
const inFlightHistoricalSymbols = new Set<string>();
let inFlightInflation = false;
const inFlightMonthlyInflation = new Set<number>();
const inFlightPortfolioStats = new Set<string>();

function loadInstrumentsShared(): Promise<InstrumentResponse[]> {
  if (!inFlightInstruments) {
    inFlightInstruments = instrumentService.getInstruments().finally(() => {
      inFlightInstruments = null;
    });
  }
  return inFlightInstruments;
}

async function loadQuotesShared(
  symbols: string[],
  existingQuotes: Record<string, PriceQuote>,
  force: boolean
): Promise<Record<string, PriceQuote>> {
  const result: Record<string, PriceQuote> = {};
  const targets = symbols.filter((sym) => force || !existingQuotes[sym]);
  if (targets.length === 0) return result;

  const pendingPromises = new Set<Promise<Record<string, PriceQuote>>>();
  const missingSymbols: string[] = [];

  for (const sym of targets) {
    const existingPromise = inFlightQuotes.get(sym);
    if (existingPromise) {
      pendingPromises.add(existingPromise);
    } else {
      missingSymbols.push(sym);
    }
  }

  if (missingSymbols.length > 0) {
    const batchPromise = marketService
      .getBatchPrices(missingSymbols)
      .catch((err) => {
        console.warn('Failed to fetch batch prices:', err);
        return {} as Record<string, PriceQuote>;
      })
      .finally(() => {
        for (const sym of missingSymbols) {
          inFlightQuotes.delete(sym);
        }
      });

    for (const sym of missingSymbols) {
      inFlightQuotes.set(sym, batchPromise);
    }
    pendingPromises.add(batchPromise);
  }

  const settled = await Promise.all(pendingPromises);
  for (const batch of settled) {
    Object.assign(result, batch);
  }
  return result;
}

async function loadFxRatesShared(
  currencies: string[],
  baseCurrency: string,
  existingRates: Record<string, number>,
  force: boolean
): Promise<Record<string, number>> {
  const result: Record<string, number> = {};
  const targets = currencies.filter(
    (curr) => curr && curr !== baseCurrency && (force || !existingRates[curr])
  );
  if (targets.length === 0) return result;

  await Promise.all(
    targets.map(async (curr) => {
      const key = `${curr}:${baseCurrency}`;
      let promise = inFlightFxRates.get(key);
      if (!promise) {
        promise = marketService
          .getExchangeRate(curr, baseCurrency)
          .then((fx) => (fx && fx.rate ? fx.rate : null))
          .catch((err) => {
            console.warn(`Failed to fetch exchange rate ${key}:`, err);
            return null;
          })
          .finally(() => {
            inFlightFxRates.delete(key);
          });
        inFlightFxRates.set(key, promise);
      }
      const rate = await promise;
      if (rate !== null) {
        result[curr] = rate;
      }
    })
  );

  return result;
}

interface HydrateMarketParams {
  instrumentIds: string[];
  currencies: string[];
  baseCurrency: string;
  existingInstruments: Record<string, InstrumentResponse>;
  existingQuotes: Record<string, PriceQuote>;
  existingRates: Record<string, number>;
  force: boolean;
  preloadedInstrumentsPromise?: Promise<InstrumentResponse[]> | null;
  markUnavailableQuotes?: boolean;
}

async function hydrateMarketContext({
  instrumentIds,
  currencies,
  baseCurrency,
  existingInstruments,
  existingQuotes,
  existingRates,
  force,
  preloadedInstrumentsPromise,
  markUnavailableQuotes = false,
}: HydrateMarketParams): Promise<{
  instruments: Record<string, InstrumentResponse>;
  quotes: Record<string, PriceQuote>;
  rates: Record<string, number>;
}> {
  const instrumentsMap: Record<string, InstrumentResponse> = { ...existingInstruments };
  const missingInstrument = instrumentIds.some((id) => !instrumentsMap[id]);

  if (preloadedInstrumentsPromise || Object.keys(instrumentsMap).length === 0 || missingInstrument) {
    try {
      const fetched = await (preloadedInstrumentsPromise ?? loadInstrumentsShared());
      for (const inst of fetched) {
        instrumentsMap[inst.id] = inst;
      }
    } catch (err) {
      console.warn('Failed to fetch instruments catalog:', err);
    }
  }

  const distinctSymbols = Array.from(
    new Set(
      instrumentIds
        .map((id) => instrumentsMap[id])
        .filter((inst): inst is InstrumentResponse =>
          Boolean(inst && inst.type !== 'BOND' && inst.symbol && !inst.symbol.startsWith('PL_BOND'))
        )
        .map((inst) => inst.symbol)
    )
  );

  const distinctCurrencies = Array.from(new Set(currencies)).filter(
    (c): c is Currency => Boolean(c && c !== baseCurrency)
  );

  const [fetchedQuotes, fetchedRates] = await Promise.all([
    loadQuotesShared(distinctSymbols, existingQuotes, force),
    loadFxRatesShared(distinctCurrencies, baseCurrency, existingRates, force),
  ]);

  const quotes: Record<string, PriceQuote> = { ...existingQuotes, ...fetchedQuotes };
  if (markUnavailableQuotes) {
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

  const rates: Record<string, number> = {
    ...existingRates,
    [baseCurrency]: 1,
    ...fetchedRates,
  };

  return { instruments: instrumentsMap, quotes, rates };
}

export const fetchPortfolios = createAsyncThunk<
  PortfolioResponse[],
  Currency | undefined,
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchPortfolios',
  async (defaultCurrency = 'PLN', { rejectWithValue }) => {
    inFlightPortfolios = true;
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
    } finally {
      inFlightPortfolios = false;
    }
  },
  {
    condition: (_, { getState }) => {
      if (inFlightPortfolios) return false;
      return getState().portfolio.portfolios.length === 0;
    },
  }
);

export const fetchPositions = createAsyncThunk<
  {
    positions: PositionResponse[];
    instruments: Record<string, InstrumentResponse>;
    quotes: Record<string, PriceQuote>;
    rates: Record<string, number>;
  },
  string | { portfolioId: string; force?: boolean },
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchPositions',
  async (arg, { getState, rejectWithValue }) => {
    const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
    const force = typeof arg === 'object' && Boolean(arg.force);
    inFlightPositions.add(portfolioId);
    try {
      const currentState = getState().portfolio;
      const preloadedInstrumentsPromise =
        Object.keys(currentState.instruments).length === 0 ? loadInstrumentsShared() : null;

      const positions = await portfolioService.getPositions(portfolioId);
      const activePortfolio = currentState.portfolios.find((p) => p.id === portfolioId);
      const baseCurrency = activePortfolio?.baseCurrency || 'PLN';

      const market = await hydrateMarketContext({
        instrumentIds: Array.from(new Set(positions.map((p) => p.instrumentId))),
        currencies: positions.map((p) => p.currency),
        baseCurrency,
        existingInstruments: currentState.instruments,
        existingQuotes: currentState.quotes,
        existingRates: currentState.rates,
        force,
        preloadedInstrumentsPromise,
        markUnavailableQuotes: true,
      });

      return {
        positions,
        instruments: market.instruments,
        quotes: market.quotes,
        rates: market.rates,
      };
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.fetchPositions')));
    } finally {
      inFlightPositions.delete(portfolioId);
    }
  },
  {
    condition: (arg, { getState }) => {
      const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
      const force = typeof arg === 'object' && Boolean(arg.force);
      if (!force && inFlightPositions.has(portfolioId)) {
        return false;
      }
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
    instruments: Record<string, InstrumentResponse>;
    quotes: Record<string, PriceQuote>;
    rates: Record<string, number>;
  },
  string | { portfolioId: string; force?: boolean },
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchTransactions',
  async (arg, { getState, rejectWithValue }) => {
    const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
    const force = typeof arg === 'object' && Boolean(arg.force);
    inFlightTransactions.add(portfolioId);
    try {
      const currentState = getState().portfolio;
      const preloadedInstrumentsPromise =
        Object.keys(currentState.instruments).length === 0 ? loadInstrumentsShared() : null;

      const txs = await portfolioService.getTransactions(portfolioId);
      const latestState = getState().portfolio;
      const activePortfolio = latestState.portfolios.find((p) => p.id === portfolioId);
      const baseCurrency = activePortfolio?.baseCurrency || 'PLN';

      const market = await hydrateMarketContext({
        instrumentIds: Array.from(new Set(txs.map((t) => t.instrumentId))),
        currencies: txs.map((t) => t.currency),
        baseCurrency,
        existingInstruments: latestState.instruments,
        existingQuotes: latestState.quotes,
        existingRates: latestState.rates,
        force: false,
        preloadedInstrumentsPromise,
        markUnavailableQuotes: false,
      });

      void force;
      return {
        transactions: txs,
        instruments: market.instruments,
        quotes: market.quotes,
        rates: market.rates,
      };
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.fetchTransactions')));
    } finally {
      inFlightTransactions.delete(portfolioId);
    }
  },
  {
    condition: (arg, { getState }) => {
      const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
      const force = typeof arg === 'object' && Boolean(arg.force);
      if (!force && inFlightTransactions.has(portfolioId)) {
        return false;
      }
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
  { force?: boolean } | void,
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchInflation',
  async (_, { rejectWithValue }) => {
    inFlightInflation = true;
    try {
      const data = await marketService.getInflation();
      return { cpi: data.currentCpi };
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.fetchInflation')));
    } finally {
      inFlightInflation = false;
    }
  },
  {
    condition: (arg, { getState }) => {
      const force = typeof arg === 'object' && Boolean(arg?.force);
      if (!force && inFlightInflation) return false;
      if (!force && getState().portfolio.cpi !== null) return false;
      return true;
    },
  }
);

export const fetchMonthlyInflation = createAsyncThunk<
  { year: number; data: Record<number, number> },
  number | { year: number; force?: boolean },
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchMonthlyInflation',
  async (arg, { rejectWithValue }) => {
    const year = typeof arg === 'number' ? arg : arg.year;
    inFlightMonthlyInflation.add(year);
    try {
      const points = await marketService.getMonthlyInflation(year);
      const map: Record<number, number> = {};
      if (Array.isArray(points)) {
        for (const p of points) {
          if (p.year === year) {
            map[p.month - 1] = p.rate;
          }
        }
      }
      return { year, data: map };
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.fetchInflation')));
    } finally {
      inFlightMonthlyInflation.delete(year);
    }
  },
  {
    condition: (arg, { getState }) => {
      const year = typeof arg === 'number' ? arg : arg.year;
      const force = typeof arg === 'object' && Boolean(arg.force);
      if (!force && inFlightMonthlyInflation.has(year)) return false;
      if (!force && getState().portfolio.monthlyCpi[year] !== undefined) return false;
      return true;
    },
  }
);

export const fetchPortfolioStats = createAsyncThunk<
  { key: string; data: PortfolioStatsResponse },
  { key: string; holdings: HoldingItem[]; force?: boolean },
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchPortfolioStats',
  async ({ key, holdings }, { rejectWithValue }) => {
    inFlightPortfolioStats.add(key);
    try {
      const data = await marketService.getPortfolioStats({ holdings });
      return { key, data };
    } catch (error: unknown) {
      return rejectWithValue(getErrorMessage(error, i18n.t('errors.generalError')));
    } finally {
      inFlightPortfolioStats.delete(key);
    }
  },
  {
    condition: ({ key, force }, { getState }) => {
      if (!force && inFlightPortfolioStats.has(key)) return false;
      if (!force && getState().portfolio.portfolioStats[key]) return false;
      return true;
    },
  }
);

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
    inFlightHistoricalSymbols.add(symbol);
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
    } finally {
      inFlightHistoricalSymbols.delete(symbol);
    }
  },
  {
    condition: ({ symbol, force }, { getState }) => {
      if (!force && inFlightHistoricalSymbols.has(symbol)) return false;
      if (force) return true;
      const existing = getState().portfolio.historicalPrices[symbol];
      return !existing || Object.keys(existing).length === 0;
    },
  }
);
