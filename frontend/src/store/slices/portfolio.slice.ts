import { createSlice, type AsyncThunk, type PayloadAction } from '@reduxjs/toolkit';
import type { PortfolioResponse, TransactionResponse } from '../../types/portfolio.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { PortfolioStatsResponse } from '../../types/analytics.types.ts';
import type {
  AnnualReportResponse,
  PortfolioTimelineResponse,
  PortfolioValuationResponse,
  ProjectionResponse,
} from '../../types/valuation.types.ts';
import {
  fetchPortfolios,
  fetchValuation,
  fetchTimeline,
  fetchAnnualReport,
  fetchTransactions,
  fetchPortfolioStats,
  fetchProjection,
  createTransaction,
  updateTransaction,
  deleteTransaction,
  createPortfolio,
  valuationKey,
} from '../thunks/portfolio.thunk.ts';
import { logoutThunk, checkAuthThunk, loginThunk, registerThunk } from '../thunks/auth.thunk.ts';

export type PortfoliosLoadStatus = 'idle' | 'loading' | 'succeeded' | 'failed';

export interface PortfolioState {
  portfolios: PortfolioResponse[];
  portfoliosStatus: PortfoliosLoadStatus;
  activePortfolioId: string | null;
  valuations: Record<string, PortfolioValuationResponse>;
  timelines: Record<string, PortfolioTimelineResponse>;
  latestTimelineByPortfolio: Record<string, PortfolioTimelineResponse>;
  reports: Record<string, AnnualReportResponse>;
  transactionsByPortfolio: Record<string, TransactionResponse[]>;
  instruments: Record<string, InstrumentResponse>;
  portfolioStats: Record<string, PortfolioStatsResponse>;
  portfolioStatsErrors: Record<string, string>;
  projection: ProjectionResponse | null;
  loading: boolean;
  valuationPendingCount: number;
  timelinePendingCount: number;
  reportPendingCount: number;
  transactionsPendingCount: number;
  error: string | null;
  cacheRevision: number;
  valuationsLastFetchedAt: Record<string, number>;
  timelinesLastFetchedAt: Record<string, number>;
  reportsLastFetchedAt: Record<string, number>;
  transactionsLastFetchedAt: Record<string, number>;
  authSessionEstablished?: boolean;
}

const initialState: PortfolioState = {
  portfolios: [],
  portfoliosStatus: 'idle',
  activePortfolioId: null,
  valuations: {},
  timelines: {},
  latestTimelineByPortfolio: {},
  reports: {},
  transactionsByPortfolio: {},
  instruments: {},
  portfolioStats: {},
  portfolioStatsErrors: {},
  projection: null,
  loading: false,
  valuationPendingCount: 0,
  timelinePendingCount: 0,
  reportPendingCount: 0,
  transactionsPendingCount: 0,
  error: null,
  cacheRevision: 0,
  valuationsLastFetchedAt: {},
  timelinesLastFetchedAt: {},
  reportsLastFetchedAt: {},
  transactionsLastFetchedAt: {},
};

type TrackedCounterKey =
  | 'valuationPendingCount'
  | 'timelinePendingCount'
  | 'reportPendingCount'
  | 'transactionsPendingCount';

function trackPending(
  state: PortfolioState,
  countKey: TrackedCounterKey,
  delta: 1 | -1
): boolean {
  state[countKey] = Math.max(0, state[countKey] + delta);
  return state[countKey] === 0;
}

function syncValuationLoading(state: PortfolioState): void {
  state.loading =
    state.portfoliosStatus === 'loading' ||
    (state.valuationPendingCount > 0 && Object.keys(state.valuations).length === 0);
}

function isStaleRevision(
  payload: { requestRevision?: number },
  cacheRevision: number
): boolean {
  return payload.requestRevision !== undefined && payload.requestRevision !== cacheRevision;
}

function storeKeyedSeries<Data>(
  target: Record<string, Data>,
  timestamps: Record<string, number>,
  payload: { key: string; data: Data }
): void {
  target[payload.key] = payload.data;
  timestamps[payload.key] = Date.now();
}

function resetStatePreservingRevision(state: PortfolioState): PortfolioState {
  return {
    ...initialState,
    cacheRevision: state.cacheRevision + 1,
  };
}

function clearDerivedCaches(state: PortfolioState): void {
  state.valuations = {};
  state.timelines = {};
  state.reports = {};
  state.valuationsLastFetchedAt = {};
  state.timelinesLastFetchedAt = {};
  state.reportsLastFetchedAt = {};
  state.portfolioStats = {};
  state.portfolioStatsErrors = {};
  state.projection = null;
  state.cacheRevision += 1;
}

export const portfolioSlice = createSlice({
  name: 'portfolio',
  initialState,
  reducers: {
    setActivePortfolioId: (state, action: PayloadAction<string>) => {
      state.activePortfolioId = action.payload;
      state.projection = null;
    },
    clearProjection: (state) => {
      state.projection = null;
    },
    clearPortfolioError: (state) => {
      state.error = null;
    },
    resetPortfolio: (state) => resetStatePreservingRevision(state),
  },
  extraReducers: (builder) => {
    builder
      .addCase(loginThunk.fulfilled, (state) => {
        state.authSessionEstablished = true;
      })
      .addCase(registerThunk.fulfilled, (state) => {
        state.authSessionEstablished = true;
      })
      .addCase(logoutThunk.fulfilled, (state) => resetStatePreservingRevision(state))
      .addCase(logoutThunk.rejected, (state) => resetStatePreservingRevision(state))
      .addCase(checkAuthThunk.rejected, (state) =>
        state.authSessionEstablished ? state : resetStatePreservingRevision(state)
      )
      .addCase(fetchPortfolios.pending, (state) => {
        state.loading = true;
        state.portfoliosStatus = 'loading';
        state.error = null;
      })
      .addCase(fetchPortfolios.fulfilled, (state, action) => {
        state.loading = false;
        state.portfoliosStatus = 'succeeded';
        state.portfolios = action.payload;
        if (!state.activePortfolioId && action.payload.length > 0) {
          state.activePortfolioId = action.payload[0].id;
        }
      })
      .addCase(fetchPortfolios.rejected, (state, action) => {
        state.loading = false;
        state.portfoliosStatus = 'failed';
        state.error = action.payload || 'errors.fetchPortfolios';
      })
      .addCase(fetchValuation.pending, (state) => {
        trackPending(state, 'valuationPendingCount', 1);
        syncValuationLoading(state);
        state.error = null;
      })
      .addCase(fetchValuation.fulfilled, (state, action) => {
        trackPending(state, 'valuationPendingCount', -1);
        syncValuationLoading(state);
        if (isStaleRevision(action.payload, state.cacheRevision)) {
          return;
        }
        storeKeyedSeries(state.valuations, state.valuationsLastFetchedAt, action.payload);
      })
      .addCase(fetchValuation.rejected, (state, action) => {
        const settled = trackPending(state, 'valuationPendingCount', -1);
        syncValuationLoading(state);
        if (settled) {
          state.error = action.payload || 'errors.fetchPositions';
        }
      });

    const addSeriesCases = <TData, TArg>(
      thunk: AsyncThunk<
        { key: string; data: TData; requestRevision: number },
        TArg,
        { rejectValue: string }
      >,
      countKey: 'timelinePendingCount' | 'reportPendingCount',
      getDataMap: (s: PortfolioState) => Record<string, TData>,
      getTsMap: (s: PortfolioState) => Record<string, number>,
      onFulfilled?: (s: PortfolioState, arg: TArg | undefined, data: TData) => void
    ) => {
      builder
        .addCase(thunk.pending, (state) => {
          trackPending(state, countKey, 1);
          state.error = null;
        })
        .addCase(thunk.fulfilled, (state, action) => {
          trackPending(state, countKey, -1);
          if (!isStaleRevision(action.payload, state.cacheRevision)) {
            storeKeyedSeries(getDataMap(state), getTsMap(state), action.payload);
            onFulfilled?.(state, action.meta?.arg, action.payload.data);
          }
        })
        .addCase(thunk.rejected, (state, action) => {
          if (trackPending(state, countKey, -1) && action.payload) {
            state.error = action.payload;
          }
        });
    };
    addSeriesCases(
      fetchTimeline,
      'timelinePendingCount',
      (s) => s.timelines,
      (s) => s.timelinesLastFetchedAt,
      (s, arg, data) => {
        if (arg?.portfolioId) {
          s.latestTimelineByPortfolio[valuationKey(arg.portfolioId, arg.taxMode ?? 'NETTO')] = data;
        }
      }
    );
    addSeriesCases(fetchAnnualReport, 'reportPendingCount', (s) => s.reports, (s) => s.reportsLastFetchedAt);

    builder
      .addCase(fetchTransactions.pending, (state) => {
        trackPending(state, 'transactionsPendingCount', 1);
        state.error = null;
      })
      .addCase(fetchTransactions.fulfilled, (state, action) => {
        trackPending(state, 'transactionsPendingCount', -1);
        if (isStaleRevision(action.payload, state.cacheRevision)) {
          return;
        }
        const tId =
          typeof action.meta.arg === 'string' ? action.meta.arg : action.meta.arg.portfolioId;
        state.transactionsByPortfolio[tId] = action.payload.transactions;
        state.instruments = { ...state.instruments, ...action.payload.instruments };
        state.transactionsLastFetchedAt[tId] = Date.now();
      })
      .addCase(fetchTransactions.rejected, (state, action) => {
        if (trackPending(state, 'transactionsPendingCount', -1)) {
          state.error = action.payload || 'errors.fetchTransactions';
        }
      })
      .addCase(fetchPortfolioStats.pending, (state, action) => {
        delete state.portfolioStatsErrors[action.meta.arg.key];
      })
      .addCase(fetchPortfolioStats.fulfilled, (state, action) => {
        if (isStaleRevision(action.payload, state.cacheRevision)) {
          return;
        }
        delete state.portfolioStatsErrors[action.payload.key];
        state.portfolioStats[action.payload.key] = action.payload.data;
      })
      .addCase(fetchPortfolioStats.rejected, (state, action) => {
        state.portfolioStatsErrors[action.meta.arg.key] =
          action.payload || 'errors.generalError';
      })
      .addCase(fetchProjection.fulfilled, (state, action) => {
        if (
          !isStaleRevision(action.payload, state.cacheRevision) &&
          action.payload.portfolioId === state.activePortfolioId
        ) {
          state.projection = action.payload.data;
        }
      })
      .addCase(fetchProjection.rejected, (state, action) => {
        if (!action.meta.aborted) {
          state.projection = null;
          state.error = action.payload || 'errors.generalError';
        }
      })
      .addCase(createPortfolio.fulfilled, (state, action) => {
        state.portfolios.push(action.payload);
        state.activePortfolioId = action.payload.id;
      });

    for (const mut of [
      {
        thunk: createTransaction,
        errorKey: 'errors.saveTransaction',
        apply: (existing: TransactionResponse[], action: { payload: TransactionResponse }) => [
          ...existing,
          action.payload,
        ],
      },
      {
        thunk: updateTransaction,
        errorKey: 'errors.updateTransaction',
        apply: (
          existing: TransactionResponse[],
          action: { meta: { arg: { transactionId: string } }; payload: TransactionResponse }
        ) => existing.map((tx) => (tx.id === action.meta.arg.transactionId ? action.payload : tx)),
      },
      {
        thunk: deleteTransaction,
        errorKey: 'errors.deleteTransaction',
        apply: (
          existing: TransactionResponse[],
          action: { meta: { arg: { transactionId: string } } }
        ) => existing.filter((tx) => tx.id !== action.meta.arg.transactionId),
      },
    ] as const) {
      builder
        .addCase(mut.thunk.pending, (state) => {
          state.error = null;
        })
        .addCase(mut.thunk.fulfilled, (state, action) => {
          const pId = action.meta.arg.portfolioId;
          const existing = state.transactionsByPortfolio[pId];
          if (existing) {
            state.transactionsByPortfolio[pId] = mut.apply(existing, action as never);
          }
          clearDerivedCaches(state);
        })
        .addCase(mut.thunk.rejected, (state, action) => {
          state.error = action.payload || mut.errorKey;
        });
    }
  },
});

export const {
  setActivePortfolioId,
  clearProjection,
  clearPortfolioError,
  resetPortfolio,
} = portfolioSlice.actions;

export default portfolioSlice.reducer;
