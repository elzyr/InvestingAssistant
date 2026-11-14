import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { configureStore } from '@reduxjs/toolkit';
import portfolioReducer from '../store/slices/portfolio.slice.ts';
import authReducer from '../store/slices/auth.slice.ts';
import { portfolioService } from '../services/portfolio.service.ts';
import { dispatchPortfolioDataLoad } from './useActivePortfolioData.ts';

describe('useActivePortfolioData refresh helper', () => {
  it('dispatches fetchPortfolios when refreshData runs without activePortfolioId', async () => {
    const origGetPortfolios = portfolioService.getPortfolios;
    const origCreatePortfolio = portfolioService.createPortfolio;
    const origGetValuation = portfolioService.getValuation;
    const calls = { portfolios: 0, createdCurrencies: [], valuations: 0 };

    portfolioService.getPortfolios = async () => {
      calls.portfolios += 1;
      return [];
    };
    portfolioService.createPortfolio = async (req) => {
      calls.createdCurrencies.push(req.baseCurrency);
      return {
        id: 'p-1',
        name: req.name,
        baseCurrency: req.baseCurrency,
        userId: 'u-1',
        createdAt: '2026-01-01T00:00:00Z',
      };
    };
    portfolioService.getValuation = async () => {
      calls.valuations += 1;
      throw new Error('should not fetch valuation without activePortfolioId');
    };

    try {
      const store = configureStore({
        reducer: { portfolio: portfolioReducer, auth: authReducer },
      });

      const idleResult = dispatchPortfolioDataLoad(store.dispatch, {
        activePortfolioId: null,
        baseCurrency: 'EUR',
        taxMode: 'NETTO',
        timeframe: 'YTD',
        force: false,
      });
      assert.strictEqual(idleResult, undefined);
      assert.strictEqual(calls.portfolios, 0);

      await dispatchPortfolioDataLoad(store.dispatch, {
        activePortfolioId: null,
        baseCurrency: 'EUR',
        taxMode: 'NETTO',
        timeframe: 'YTD',
        force: true,
      });
      assert.strictEqual(calls.portfolios, 1);
      assert.deepStrictEqual(calls.createdCurrencies, ['EUR']);
      assert.strictEqual(calls.valuations, 0);
      assert.strictEqual(store.getState().portfolio.activePortfolioId, 'p-1');
    } finally {
      portfolioService.getPortfolios = origGetPortfolios;
      portfolioService.createPortfolio = origCreatePortfolio;
      portfolioService.getValuation = origGetValuation;
    }
  });

  it('dispatches forced fetches when activePortfolioId exists and respects skip flags', () => {
    const dispatched = [];
    const result = dispatchPortfolioDataLoad((action) => {
      dispatched.push(action);
      return action;
    }, {
      activePortfolioId: 'p-42',
      baseCurrency: 'PLN',
      taxMode: 'BRUTTO',
      timeframe: '1Y',
      reportYear: 2026,
      skipTimeline: true,
      force: true,
    });

    assert.ok(result instanceof Promise);
    assert.strictEqual(dispatched.length, 3);
    assert.ok(dispatched.every((action) => typeof action === 'function'));
  });
});
