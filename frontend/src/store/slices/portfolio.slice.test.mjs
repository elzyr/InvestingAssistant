import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { configureStore } from '@reduxjs/toolkit';
import portfolioReducer, { resetPortfolio } from './portfolio.slice.ts';
import authReducer from './auth.slice.ts';
import {
  createTransaction,
  deleteTransaction,
  fetchAnnualReport,
  fetchPortfolioStats,
  fetchPortfolios,
  fetchTimeline,
  fetchTransactions,
  fetchValuation,
  updateTransaction,
} from '../thunks/portfolio.thunk.ts';
import { logoutThunk } from '../thunks/auth.thunk.ts';
import { portfolioService } from '../../services/portfolio.service.ts';

describe('portfolioSlice & authSlice', () => {
  it('tracks portfoliosStatus across pending, fulfilled, and rejected', () => {
    const init = portfolioReducer(undefined, { type: '@@INIT' });
    assert.strictEqual(init.portfoliosStatus, 'idle');

    const loading = portfolioReducer(init, { type: fetchPortfolios.pending.type });
    assert.strictEqual(loading.portfoliosStatus, 'loading');

    const failed = portfolioReducer(loading, {
      type: fetchPortfolios.rejected.type,
      payload: 'network error',
    });
    assert.strictEqual(failed.portfoliosStatus, 'failed');
    assert.strictEqual(failed.error, 'network error');

    const loaded = portfolioReducer(failed, {
      type: fetchPortfolios.fulfilled.type,
      payload: [{ id: 'p1', name: 'Main', baseCurrency: 'PLN', userId: 'u1', createdAt: '2026-01-01' }],
    });
    assert.strictEqual(loaded.portfoliosStatus, 'succeeded');
    assert.strictEqual(loaded.activePortfolioId, 'p1');
  });

  it('isolates transactions by portfolioId and updates caches on create/update/delete', () => {
    let state = portfolioReducer(undefined, { type: '@@INIT' });
    state = portfolioReducer(state, {
      type: fetchTransactions.fulfilled.type,
      meta: { arg: 'p1' },
      payload: {
        transactions: [{ id: 'tx-1', instrumentId: 'i1', transactionType: 'BUY', quantity: 1, price: 100 }],
        instruments: {},
      },
    });
    state = portfolioReducer(state, {
      type: fetchTransactions.fulfilled.type,
      meta: { arg: 'p2' },
      payload: {
        transactions: [{ id: 'tx-2', instrumentId: 'i2', transactionType: 'BUY', quantity: 2, price: 200 }],
        instruments: {},
      },
    });

    assert.strictEqual(state.transactionsByPortfolio.p1.length, 1);
    assert.strictEqual(state.transactionsByPortfolio.p2[0].id, 'tx-2');

    state = portfolioReducer(state, {
      type: createTransaction.fulfilled.type,
      meta: { arg: { portfolioId: 'p1', request: {} } },
      payload: { id: 'tx-3', instrumentId: 'i1', transactionType: 'BUY', quantity: 5, price: 110 },
    });
    assert.strictEqual(state.transactionsByPortfolio.p1.length, 2);
    assert.strictEqual(state.cacheRevision, 1);

    state = portfolioReducer(state, {
      type: updateTransaction.fulfilled.type,
      meta: { arg: { portfolioId: 'p1', transactionId: 'tx-3', request: {} } },
      payload: { id: 'tx-3', instrumentId: 'i1', transactionType: 'BUY', quantity: 6, price: 115 },
    });
    assert.strictEqual(state.transactionsByPortfolio.p1[1].quantity, 6);
    assert.strictEqual(state.cacheRevision, 2);

    state = portfolioReducer(state, {
      type: deleteTransaction.fulfilled.type,
      meta: { arg: { portfolioId: 'p1', transactionId: 'tx-1' } },
    });
    assert.deepStrictEqual(
      state.transactionsByPortfolio.p1.map((t) => t.id),
      ['tx-3']
    );
    assert.strictEqual(state.cacheRevision, 3);
  });

  it('clears auth and portfolio state on logoutThunk.rejected as well as fulfilled', () => {
    const dirtyPortfolio = portfolioReducer(undefined, {
      type: fetchPortfolios.fulfilled.type,
      payload: [{ id: 'p1', name: 'Main', baseCurrency: 'PLN', userId: 'u1', createdAt: '2026-01-01' }],
    });
    const resetPortfolioState = portfolioReducer(dirtyPortfolio, { type: logoutThunk.rejected.type });
    assert.strictEqual(resetPortfolioState.portfolios.length, 0);
    assert.strictEqual(resetPortfolioState.portfoliosStatus, 'idle');

    const loggedInAuth = {
      user: { id: 'u1', email: 'a@b.pl', firstName: 'A', lastName: 'B', role: 'USER', baseCurrency: 'PLN', createdAt: '' },
      isAuthenticated: true,
      isInitialized: true,
      loading: false,
      error: 'err',
    };
    const resetAuthState = authReducer(loggedInAuth, { type: logoutThunk.rejected.type });
    assert.strictEqual(resetAuthState.user, null);
    assert.strictEqual(resetAuthState.isAuthenticated, false);
    assert.strictEqual(resetAuthState.error, null);
  });

  it('tracks concurrent pending counters and clears error on pending refetches', () => {
    let state = portfolioReducer(undefined, {
      type: fetchTimeline.rejected.type,
      payload: 'timeline boom',
    });
    assert.strictEqual(state.error, 'timeline boom');

    state = portfolioReducer(state, { type: fetchTimeline.pending.type });
    assert.strictEqual(state.error, null);
    assert.strictEqual(state.timelinePendingCount, 1);

    state = portfolioReducer(state, { type: fetchValuation.pending.type });
    state = portfolioReducer(state, { type: fetchValuation.pending.type });
    assert.strictEqual(state.valuationPendingCount, 2);

    state = portfolioReducer(state, {
      type: fetchValuation.fulfilled.type,
      payload: { key: 'p1:NETTO', data: { summary: {} }, requestRevision: 0 },
    });
    assert.strictEqual(state.valuationPendingCount, 1);

    state = portfolioReducer(state, {
      type: fetchAnnualReport.rejected.type,
      payload: 'report boom',
    });
    state = portfolioReducer(state, { type: fetchAnnualReport.pending.type });
    assert.strictEqual(state.error, null);

    state = portfolioReducer(state, {
      type: fetchTransactions.rejected.type,
      payload: 'tx boom',
    });
    state = portfolioReducer(state, { type: fetchTransactions.pending.type });
    assert.strictEqual(state.error, null);
  });

  it('ignores stale fulfilled responses from earlier cacheRevision generations', () => {
    let state = portfolioReducer(undefined, { type: fetchValuation.pending.type });
    state = portfolioReducer(state, {
      type: createTransaction.fulfilled.type,
      payload: { id: 'tx-1' },
      meta: { arg: { portfolioId: 'p1', request: {} } },
    });
    assert.strictEqual(state.cacheRevision, 1);

    state = portfolioReducer(state, {
      type: fetchValuation.fulfilled.type,
      payload: { key: 'p1:NETTO', data: { stale: true }, requestRevision: 0 },
    });
    assert.strictEqual(state.valuations['p1:NETTO'], undefined);

    state = portfolioReducer(state, {
      type: fetchTimeline.fulfilled.type,
      payload: { key: 'p1:YTD:NETTO', data: { stale: true }, requestRevision: 0 },
    });
    assert.strictEqual(state.timelines['p1:YTD:NETTO'], undefined);

    state = portfolioReducer(state, {
      type: fetchAnnualReport.fulfilled.type,
      payload: { key: 'p1:2026:NETTO', data: { stale: true }, requestRevision: 0 },
    });
    assert.strictEqual(state.reports['p1:2026:NETTO'], undefined);

    state = portfolioReducer(state, {
      type: fetchPortfolioStats.fulfilled.type,
      payload: { key: 'p1|stats', data: { stale: true }, requestRevision: 0 },
    });
    assert.strictEqual(state.portfolioStats['p1|stats'], undefined);

    state = portfolioReducer(state, {
      type: fetchTransactions.fulfilled.type,
      payload: { transactions: [{ id: 'stale' }], instruments: {}, requestRevision: 0 },
      meta: { arg: 'p1' },
    });
    assert.strictEqual(state.transactionsByPortfolio.p1, undefined);
  });

  it('allows post-mutation fetch while pre-mutation fetch is in flight and drops stale result', async () => {
    const origGetValuation = portfolioService.getValuation;
    const origGetPortfolios = portfolioService.getPortfolios;
    let resolveRev0;
    let resolveRev1;
    let calls = 0;

    portfolioService.getValuation = () => {
      calls += 1;
      if (calls === 1) {
        return new Promise((resolve) => {
          resolveRev0 = resolve;
        });
      }
      return new Promise((resolve) => {
        resolveRev1 = resolve;
      });
    };
    portfolioService.getPortfolios = async () => [
      { id: 'p-retry', name: 'Retry', baseCurrency: 'PLN', userId: 'u1', createdAt: '2026-01-01' },
    ];

    try {
      const store = configureStore({
        reducer: { portfolio: portfolioReducer, auth: authReducer },
      });

      const pRev0 = store.dispatch(fetchValuation({ portfolioId: 'p1', taxMode: 'NETTO' }));
      await store.dispatch(fetchValuation({ portfolioId: 'p1', taxMode: 'NETTO' }));
      assert.strictEqual(calls, 1);

      store.dispatch({
        type: createTransaction.fulfilled.type,
        payload: { id: 'tx-new' },
        meta: { arg: { portfolioId: 'p1', request: {} } },
      });
      assert.strictEqual(store.getState().portfolio.cacheRevision, 1);

      const pRev1 = store.dispatch(fetchValuation({ portfolioId: 'p1', taxMode: 'NETTO' }));
      assert.strictEqual(calls, 2);

      resolveRev0({ version: 'stale' });
      await pRev0;
      assert.strictEqual(store.getState().portfolio.valuations['p1:NETTO'], undefined);
      assert.strictEqual(store.getState().portfolio.valuationPendingCount, 1);

      resolveRev1({ version: 'fresh' });
      await pRev1;
      assert.deepStrictEqual(store.getState().portfolio.valuations['p1:NETTO'], { version: 'fresh' });
      assert.strictEqual(store.getState().portfolio.valuationPendingCount, 0);

      store.dispatch({ type: fetchPortfolios.rejected.type, payload: 'down' });
      assert.strictEqual(store.getState().portfolio.portfoliosStatus, 'failed');
      await store.dispatch(fetchPortfolios('PLN'));
      assert.strictEqual(store.getState().portfolio.portfoliosStatus, 'succeeded');
      assert.strictEqual(store.getState().portfolio.activePortfolioId, 'p-retry');
    } finally {
      portfolioService.getValuation = origGetValuation;
      portfolioService.getPortfolios = origGetPortfolios;
    }
  });

  it('increments cacheRevision on reset/logout, preserves transactionsLastFetchedAt on mutation, and tracks portfolioStatsErrors', () => {
    let state = portfolioReducer(undefined, { type: '@@INIT' });
    state = portfolioReducer(state, {
      type: fetchTransactions.fulfilled.type,
      payload: { transactions: [{ id: 't1' }], instruments: {}, requestRevision: 0 },
      meta: { arg: 'p1' },
    });
    const fetchedAt = state.transactionsLastFetchedAt.p1;
    assert.ok(typeof fetchedAt === 'number' && fetchedAt > 0);

    state = portfolioReducer(state, {
      type: createTransaction.fulfilled.type,
      payload: { id: 't2' },
      meta: { arg: { portfolioId: 'p1', request: {} } },
    });
    assert.strictEqual(state.cacheRevision, 1);
    assert.strictEqual(state.transactionsLastFetchedAt.p1, fetchedAt);

    state = portfolioReducer(state, {
      type: fetchPortfolioStats.rejected.type,
      payload: 'stats error',
      meta: { arg: { key: 'p1|stats' } },
    });
    assert.strictEqual(state.portfolioStatsErrors['p1|stats'], 'stats error');

    state = portfolioReducer(state, {
      type: fetchPortfolioStats.pending.type,
      meta: { arg: { key: 'p1|stats' } },
    });
    assert.strictEqual(state.portfolioStatsErrors['p1|stats'], undefined);

    state = portfolioReducer(state, resetPortfolio());
    assert.strictEqual(state.cacheRevision, 2);

    state = portfolioReducer(state, { type: logoutThunk.fulfilled.type });
    assert.strictEqual(state.cacheRevision, 3);
    assert.deepStrictEqual(state.latestTimelineByPortfolio, {});
  });

  it('retains latestTimelineByPortfolio across timeframe switches and mutations while isolating portfolios', () => {
    let state = portfolioReducer(undefined, { type: '@@INIT' });
    const ytdTimeline = { portfolioId: 'p1', timeframe: 'YTD', points: [{ date: '2026-01-02' }] };
    const oneMonthTimeline = { portfolioId: 'p1', timeframe: '1M', points: [{ date: '2026-10-15' }] };

    state = portfolioReducer(state, {
      type: fetchTimeline.fulfilled.type,
      payload: { key: 'p1:YTD:NETTO', data: ytdTimeline, requestRevision: 0 },
      meta: { arg: { portfolioId: 'p1', timeframe: 'YTD', taxMode: 'NETTO' } },
    });
    assert.strictEqual(state.latestTimelineByPortfolio['p1:NETTO'], ytdTimeline);
    assert.strictEqual(state.latestTimelineByPortfolio['p2:NETTO'], undefined);

    state = portfolioReducer(state, {
      type: createTransaction.fulfilled.type,
      payload: { id: 'tx-mut' },
      meta: { arg: { portfolioId: 'p1', request: {} } },
    });
    assert.deepStrictEqual(state.timelines, {});
    assert.strictEqual(state.latestTimelineByPortfolio['p1:NETTO'], ytdTimeline);

    state = portfolioReducer(state, {
      type: fetchTimeline.fulfilled.type,
      payload: { key: 'p1:1M:NETTO', data: oneMonthTimeline, requestRevision: 1 },
      meta: { arg: { portfolioId: 'p1', timeframe: '1M', taxMode: 'NETTO' } },
    });
    assert.strictEqual(state.latestTimelineByPortfolio['p1:NETTO'], oneMonthTimeline);
  });
});
