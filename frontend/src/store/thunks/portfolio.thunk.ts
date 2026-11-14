import { createAsyncThunk } from '@reduxjs/toolkit';
import { extractApiErrorMessage } from '../../api/client.ts';
import { portfolioService } from '../../services/portfolio.service.ts';
import { instrumentService } from '../../services/instrument.service.ts';
import { marketService } from '../../services/market.service.ts';
import type {
  PortfolioResponse,
  CreatePortfolioRequest,
  TransactionResponse,
  CreateTransactionRequest,
} from '../../types/portfolio.ts';
import type {
  AnnualReportResponse,
  PortfolioTimelineResponse,
  PortfolioValuationResponse,
  ProjectionRequestPayload,
  ProjectionResponse,
} from '../../types/valuation.types.ts';
import type { TaxMode, TimeframeOption } from '../../types/dashboard.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { HoldingItem, PortfolioStatsResponse } from '../../types/analytics.types.ts';
import type { Currency } from '../../types/auth.ts';
import type { RootState } from '../store.ts';

const CACHE_TTL_MS = 60000;

export function valuationKey(portfolioId: string, taxMode: TaxMode): string {
  return `${portfolioId}:${taxMode}`;
}

export function timelineKey(
  portfolioId: string,
  timeframe: TimeframeOption,
  taxMode: TaxMode
): string {
  return `${portfolioId}:${timeframe}:${taxMode}`;
}

export function reportKey(portfolioId: string, year: number, taxMode: TaxMode): string {
  return `${portfolioId}:${year}:${taxMode}`;
}

let inFlightInstruments: Promise<InstrumentResponse[]> | null = null;
const inFlightTransactions = new Map<string, number>();

function revisionFlightKey(key: string, revision: number): string {
  return `${key}@${revision}`;
}

function loadInstrumentsShared(): Promise<InstrumentResponse[]> {
  if (!inFlightInstruments) {
    inFlightInstruments = instrumentService.getInstruments().finally(() => {
      inFlightInstruments = null;
    });
  }
  return inFlightInstruments;
}

function shouldFetchKeyed(
  inFlight: Map<string, number>,
  flightKey: string,
  cacheKey: string,
  lastFetchedMap: Record<string, number> | undefined,
  dataMap: Record<string, unknown>,
  force?: boolean
): boolean {
  if (!force && (inFlight.get(flightKey) ?? 0) > 0) return false;
  if (dataMap[cacheKey] === undefined) return true;
  if (!lastFetchedMap) return Boolean(force);
  const last = lastFetchedMap[cacheKey];
  if (!force && last && Date.now() - last < CACHE_TTL_MS) {
    return false;
  }
  return true;
}

async function withInFlightKey<T>(
  inFlight: Map<string, number>,
  flightKey: string,
  task: () => Promise<T>
): Promise<T> {
  inFlight.set(flightKey, (inFlight.get(flightKey) ?? 0) + 1);
  try {
    return await task();
  } finally {
    const remaining = (inFlight.get(flightKey) ?? 1) - 1;
    if (remaining <= 0) {
      inFlight.delete(flightKey);
    } else {
      inFlight.set(flightKey, remaining);
    }
  }
}

function createKeyedCachedThunk<TArg extends { force?: boolean }, TResult>(config: {
  typePrefix: string;
  getKey: (arg: TArg) => string;
  fetch: (arg: TArg) => Promise<TResult>;
  getDataMap: (state: RootState['portfolio']) => Record<string, unknown>;
  getLastFetchedMap?: (state: RootState['portfolio']) => Record<string, number>;
  errorMessageKey: string;
}) {
  const inFlight = new Map<string, number>();
  return createAsyncThunk<
    { key: string; data: TResult; requestRevision: number },
    TArg,
    { state: RootState; rejectValue: string }
  >(
    config.typePrefix,
    async (arg, { getState, rejectWithValue }) => {
      const requestRevision = getState().portfolio.cacheRevision;
      const key = config.getKey(arg);
      const flightKey = revisionFlightKey(key, requestRevision);
      try {
        const data = await withInFlightKey(inFlight, flightKey, () => config.fetch(arg));
        return { key, data, requestRevision };
      } catch (error: unknown) {
        return rejectWithValue(extractApiErrorMessage(error, config.errorMessageKey, true));
      }
    },
    {
      condition: (arg, { getState }) => {
        const portfolioState = getState().portfolio;
        const key = config.getKey(arg);
        return shouldFetchKeyed(
          inFlight,
          revisionFlightKey(key, portfolioState.cacheRevision),
          key,
          config.getLastFetchedMap?.(portfolioState),
          config.getDataMap(portfolioState),
          arg.force
        );
      },
    }
  );
}

export const fetchPortfolios = createAsyncThunk<
  PortfolioResponse[],
  Currency | { defaultCurrency?: Currency; defaultName?: string } | undefined,
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchPortfolios',
  async (arg, { rejectWithValue }) => {
    const defaultCurrency = typeof arg === 'string' ? arg : arg?.defaultCurrency ?? 'PLN';
    const defaultName = (typeof arg === 'object' ? arg?.defaultName : undefined) || 'Main';
    try {
      const portfolios = await portfolioService.getPortfolios();
      if (portfolios.length === 0) {
        const defaultPortfolio = await portfolioService.createPortfolio({
          name: defaultName,
          baseCurrency: defaultCurrency,
        });
        return [defaultPortfolio];
      }
      return portfolios;
    } catch (error: unknown) {
      return rejectWithValue(extractApiErrorMessage(error, 'errors.fetchPortfolios', true));
    }
  },
  {
    condition: (_, { getState }) => {
      const { portfoliosStatus, portfolios } = getState().portfolio;
      return portfoliosStatus !== 'loading' && portfolios.length === 0;
    },
  }
);

export const fetchValuation = createKeyedCachedThunk<
  { portfolioId: string; taxMode?: TaxMode; force?: boolean },
  PortfolioValuationResponse
>({
  typePrefix: 'portfolio/fetchValuation',
  getKey: ({ portfolioId, taxMode = 'NETTO' }) => valuationKey(portfolioId, taxMode),
  fetch: ({ portfolioId, taxMode = 'NETTO' }) => portfolioService.getValuation(portfolioId, taxMode),
  getDataMap: (state) => state.valuations,
  getLastFetchedMap: (state) => state.valuationsLastFetchedAt,
  errorMessageKey: 'errors.fetchPositions',
});

export const fetchTimeline = createKeyedCachedThunk<
  { portfolioId: string; timeframe?: TimeframeOption; taxMode?: TaxMode; force?: boolean },
  PortfolioTimelineResponse
>({
  typePrefix: 'portfolio/fetchTimeline',
  getKey: ({ portfolioId, timeframe = 'YTD', taxMode = 'NETTO' }) =>
    timelineKey(portfolioId, timeframe, taxMode),
  fetch: ({ portfolioId, timeframe = 'YTD', taxMode = 'NETTO' }) =>
    portfolioService.getTimeline(portfolioId, timeframe, taxMode),
  getDataMap: (state) => state.timelines,
  getLastFetchedMap: (state) => state.timelinesLastFetchedAt,
  errorMessageKey: 'errors.fetchPositions',
});

export const fetchAnnualReport = createKeyedCachedThunk<
  { portfolioId: string; year: number; taxMode?: TaxMode; force?: boolean },
  AnnualReportResponse
>({
  typePrefix: 'portfolio/fetchAnnualReport',
  getKey: ({ portfolioId, year, taxMode = 'NETTO' }) => reportKey(portfolioId, year, taxMode),
  fetch: ({ portfolioId, year, taxMode = 'NETTO' }) =>
    portfolioService.getAnnualReport(portfolioId, year, taxMode),
  getDataMap: (state) => state.reports,
  getLastFetchedMap: (state) => state.reportsLastFetchedAt,
  errorMessageKey: 'errors.generalError',
});

export const fetchTransactions = createAsyncThunk<
  {
    transactions: TransactionResponse[];
    instruments: Record<string, InstrumentResponse>;
    requestRevision: number;
  },
  string | { portfolioId: string; force?: boolean },
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchTransactions',
  async (arg, { getState, rejectWithValue }) => {
    const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
    const requestRevision = getState().portfolio.cacheRevision;
    const flightKey = revisionFlightKey(portfolioId, requestRevision);
    try {
      return await withInFlightKey(inFlightTransactions, flightKey, async () => {
        const existingInstruments = { ...getState().portfolio.instruments };
        const [transactions, catalog] = await Promise.all([
          portfolioService.getTransactions(portfolioId),
          Object.keys(existingInstruments).length === 0
            ? loadInstrumentsShared().catch(() => [])
            : Promise.resolve<InstrumentResponse[]>([]),
        ]);

        for (const inst of catalog) {
          existingInstruments[inst.id] = inst;
        }
        if (transactions.some((tx) => !existingInstruments[tx.instrumentId])) {
          const refreshed = await loadInstrumentsShared().catch(() => []);
          for (const inst of refreshed) {
            existingInstruments[inst.id] = inst;
          }
        }

        return { transactions, instruments: existingInstruments, requestRevision };
      });
    } catch (error: unknown) {
      return rejectWithValue(extractApiErrorMessage(error, 'errors.fetchTransactions', true));
    }
  },
  {
    condition: (arg, { getState }) => {
      const portfolioState = getState().portfolio;
      const portfolioId = typeof arg === 'string' ? arg : arg.portfolioId;
      const force = typeof arg === 'object' && Boolean(arg.force);
      return shouldFetchKeyed(
        inFlightTransactions,
        revisionFlightKey(portfolioId, portfolioState.cacheRevision),
        portfolioId,
        portfolioState.transactionsLastFetchedAt,
        portfolioState.transactionsByPortfolio,
        force
      );
    },
  }
);

export const fetchPortfolioStats = createKeyedCachedThunk<
  { key: string; holdings: HoldingItem[]; force?: boolean },
  PortfolioStatsResponse
>({
  typePrefix: 'portfolio/fetchPortfolioStats',
  getKey: ({ key }) => key,
  fetch: ({ holdings }) => marketService.getPortfolioStats({ holdings }),
  getDataMap: (state) => state.portfolioStats,
  errorMessageKey: 'errors.generalError',
});

function createMutationThunk<TArg, TResult = TransactionResponse>(
  typePrefix: string,
  task: (arg: TArg) => Promise<TResult>,
  errorMessageKey: string
) {
  return createAsyncThunk<TResult, TArg, { rejectValue: string }>(
    typePrefix,
    async (arg, { rejectWithValue }) => {
      try {
        return await task(arg);
      } catch (error: unknown) {
        return rejectWithValue(extractApiErrorMessage(error, errorMessageKey, true));
      }
    }
  );
}

export const createTransaction = createMutationThunk<{
  portfolioId: string;
  request: CreateTransactionRequest;
}>(
  'portfolio/createTransaction',
  ({ portfolioId, request }) => portfolioService.createTransaction(portfolioId, request),
  'errors.saveTransaction'
);

export const updateTransaction = createMutationThunk<{
  portfolioId: string;
  transactionId: string;
  request: CreateTransactionRequest;
}>(
  'portfolio/updateTransaction',
  ({ portfolioId, transactionId, request }) =>
    portfolioService.updateTransaction(portfolioId, transactionId, request),
  'errors.updateTransaction'
);

export const deleteTransaction = createMutationThunk<
  { portfolioId: string; transactionId: string },
  void
>(
  'portfolio/deleteTransaction',
  ({ portfolioId, transactionId }) =>
    portfolioService.deleteTransaction(portfolioId, transactionId),
  'errors.deleteTransaction'
);

export const createPortfolio = createMutationThunk<CreatePortfolioRequest, PortfolioResponse>(
  'portfolio/createPortfolio',
  (request) => portfolioService.createPortfolio(request),
  'errors.createPortfolio'
);

export const fetchProjection = createAsyncThunk<
  { portfolioId: string; data: ProjectionResponse; requestRevision: number },
  { portfolioId: string; request: ProjectionRequestPayload },
  { state: RootState; rejectValue: string }
>(
  'portfolio/fetchProjection',
  async ({ portfolioId, request }, { getState, signal, rejectWithValue }) => {
    const requestRevision = getState().portfolio.cacheRevision;
    try {
      const data = await portfolioService.getProjection(portfolioId, request, signal);
      return { portfolioId, data, requestRevision };
    } catch (error: unknown) {
      if (signal.aborted) {
        throw error;
      }
      return rejectWithValue(extractApiErrorMessage(error, 'errors.generalError', true));
    }
  }
);
