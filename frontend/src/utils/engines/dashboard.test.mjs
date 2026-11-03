import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { getBondFamily, isBondItem } from '../domain/assets.ts';
import { calculateBondCurrentPrice } from '../domain/bondPricing.ts';
import { buildDashboardPositions, buildDashboardSummary, getTransactionCashFlow } from './dashboard.ts';

const assertCloseTo = (actual, expected, tolerance = 1e-4, message) => {
  assert.ok(typeof actual === 'number' && !Number.isNaN(actual), `${message || 'Expected number'}: received ${actual}`);
  const diff = Math.abs(actual - expected);
  assert.ok(diff <= tolerance, `${message || 'Assertion failed'}: expected ${actual} to be close to ${expected} (diff: ${diff}, tolerance: ${tolerance})`);
};

  describe('buildDashboardPositions cohort and lot deductions', () => {
    it('applies FIFO lot reduction to equity positions after partial sell', () => {
      const enrichedHoldings = [
        {
          id: 'pos-eq',
          isBond: false,
          ticker: 'VWCE',
          name: 'Vanguard FTSE All-World',
          instrumentId: 'inst-etf',
          quantity: 5,
          totalValue: 1000,
          totalCost: 500,
          currentPrice: 200,
          averagePrice: 100,
          currency: 'PLN',
          assetType: 'ETF',
          purchaseDate: '2024-01-10T10:00:00Z',
        },
      ];

      const transactions = [
        {
          id: 'tx-buy-1',
          instrumentId: 'inst-etf',
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: '2024-01-10T10:00:00Z',
          baseAmount: 1000,
        },
        {
          id: 'tx-sell-1',
          instrumentId: 'inst-etf',
          transactionType: 'SELL',
          quantity: 5,
          price: 200,
          currency: 'PLN',
          executedAt: '2024-06-10T10:00:00Z',
          baseAmount: 1000,
        },
      ];

      const positions = buildDashboardPositions({
        enrichedHoldings,
        transactions,
        baseCurrency: 'PLN',
        rates: { PLN: 1 },
        totalPortfolioValue: 1000,
        shouldApplyTax: false,
        nowTimestamp: new Date('2025-01-10T10:00:00Z').getTime(),
      });

      assert.strictEqual(positions.length, 1);
      const sub = positions[0].subPositions;
      assert.strictEqual(sub.length, 1);
      assert.strictEqual(sub[0].id, 'tx-buy-1');
      assert.ok(sub[0].subName.includes('5 szt.'));
      assert.strictEqual(sub[0].priceFormatted, '500 PLN');
      assert.strictEqual(sub[0].valueFormatted, '1000 PLN');
    });

    it('includes SELL transactions for matching bond series in cohort grouping', () => {
      const enrichedHoldings = [
        {
          isBond: true,
          ticker: 'EDO0134',
          instrumentId: 'inst-bond',
          quantity: 5,
          totalValue: 525,
          totalCost: 500,
          currentPrice: 105,
          currency: 'PLN',
          purchaseDate: '2024-01-10T10:00:00Z',
          bondRate: 6.5,
        },
      ];

      const transactions = [
        {
          id: 'tx-1',
          instrumentId: 'inst-bond',
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: '2024-01-10T10:00:00Z',
          seriesName: 'EDO0134',
          baseAmount: 1000,
        },
        {
          id: 'tx-2',
          instrumentId: 'inst-bond',
          transactionType: 'SELL',
          quantity: 5,
          price: 102,
          currency: 'PLN',
          executedAt: '2024-06-10T10:00:00Z',
          seriesName: 'EDO0134',
          baseAmount: 510,
        },
      ];

      const res = buildDashboardPositions({
        enrichedHoldings,
        transactions,
        baseCurrency: 'PLN',
        rates: { PLN: 1 },
        totalPortfolioValue: 525,
        shouldApplyTax: false,
        nowTimestamp: new Date('2025-01-10T10:00:00Z').getTime(),
      });

      assert.strictEqual(res.length, 1);
      const edo = res[0];
      assert.strictEqual(edo.subPositions.length, 1);
      const sub = edo.subPositions[0];
      assert.strictEqual(sub.id, 'EDO-2024');
      assert.strictEqual(typeof sub.xirr, 'number');
      assert.strictEqual(sub.isXirrFallback, false);
    });

    it('uses actual purchase exchange rate instead of 1.0 buy FX for foreign currency positions', () => {
      const enrichedHoldings = [
        {
          id: 'pos-vuaa',
          isBond: false,
          ticker: 'VUAA',
          name: 'Vanguard S&P 500',
          instrumentId: 'inst-vuaa',
          quantity: 10,
          totalValue: 4405,
          totalCost: 4300,
          currentPrice: 100,
          averagePrice: 100,
          currency: 'EUR',
          assetType: 'ETF',
          purchaseDate: '2024-01-10T10:00:00Z',
          profit: 105,
        },
      ];

      const transactions = [
        {
          id: 'tx-vuaa-1',
          instrumentId: 'inst-vuaa',
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'EUR',
          exchangeRate: 1.0,
          exchangeRateToBase: 4.30,
          baseAmount: 4300,
          executedAt: '2024-01-10T10:00:00Z',
        },
      ];

      const positions = buildDashboardPositions({
        enrichedHoldings,
        transactions,
        baseCurrency: 'PLN',
        rates: { EUR: 4.405, PLN: 1 },
        totalPortfolioValue: 4405,
        shouldApplyTax: false,
        nowTimestamp: new Date('2025-01-10T10:00:00Z').getTime(),
      });

      assert.strictEqual(positions.length, 1);
      const pos = positions[0];
      assert.ok(pos.fxDecomposition != null);
      assertCloseTo(pos.fxDecomposition.marketReturnPct, 0.0, 1e-4);
      assertCloseTo(pos.fxDecomposition.fxReturnPct, 2.44186, 1e-2);

      assert.strictEqual(pos.subPositions.length, 1);
      const sub = pos.subPositions[0];
      assert.ok(sub.fxDecomposition != null);
      assertCloseTo(sub.fxDecomposition.marketReturnPct, 0.0, 1e-4);
      assertCloseTo(sub.fxDecomposition.fxReturnPct, 2.44186, 1e-2);
      assert.notStrictEqual(Math.round(sub.fxDecomposition.fxReturnPct), 341);
    });

    it('prioritizes historical exchangeRateToBase over current spot rates in getTransactionCashFlow (CALC-07)', () => {
      const tx = {
        id: 'tx-usd-1',
        instrumentId: 'inst-usd',
        transactionType: 'BUY',
        quantity: 10,
        price: 100,
        currency: 'USD',
        exchangeRateToBase: 3.80,
        executedAt: '2023-01-01T10:00:00Z',
      };

      const flow = getTransactionCashFlow(tx, 'PLN', { USD: 4.50 });
      assert.strictEqual(flow.isOutflow, true);
      assertCloseTo(flow.amount, 3800, 1e-2);
    });

    it('prioritizes historical exchangeRateToBase over current spot rates for SELL proceeds (CALC-07)', () => {
      const sellTx = {
        id: 'tx-usd-sell',
        instrumentId: 'inst-usd',
        transactionType: 'SELL',
        quantity: 10,
        price: 150,
        currency: 'USD',
        exchangeRateToBase: 3.90,
        executedAt: '2023-06-01T10:00:00Z',
      };

      const flow = getTransactionCashFlow(sellTx, 'PLN', { USD: 4.50 });
      assert.strictEqual(flow.isOutflow, false);
      assertCloseTo(flow.amount, 5850, 1e-2);
    });

    it('handles DIVIDEND as a positive cash flow cash inflow (CALC-09)', () => {
      const divTx = {
        id: 'tx-div-1',
        instrumentId: 'inst-div-1',
        type: 'DIVIDEND',
        amount: 250,
        currency: 'PLN',
        executedAt: '2024-04-15T10:00:00Z',
      };

      const flow = getTransactionCashFlow(divTx, 'PLN', {});
      assert.strictEqual(flow.isOutflow, false);
      assertCloseTo(flow.amount, 250, 1e-2);

      const divTxForeign = {
        id: 'tx-div-2',
        instrumentId: 'inst-div-2',
        transactionType: 'DIVIDEND',
        quantity: 10,
        price: 5,
        currency: 'USD',
        exchangeRateToBase: 4.0,
        executedAt: '2024-04-15T10:00:00Z',
      };

      const flowForeign = getTransactionCashFlow(divTxForeign, 'PLN', { USD: 4.5 });
      assert.strictEqual(flowForeign.isOutflow, false);
      assertCloseTo(flowForeign.amount, 200, 1e-2);
    });
  });


  describe('cohort vintage XIRR isolation across bond series', () => {
    it('isolates cash flows of different bond series sharing the same instrumentId', () => {
      const nowTs = new Date('2026-11-01T12:00:00Z').getTime();
      const transactions = [
        {
          id: 'tx-edo-1',
          instrumentId: 'PL_BOND',
          seriesName: 'EDO0436',
          transactionType: 'BUY',
          quantity: 4,
          price: 100,
          currency: 'PLN',
          executedAt: '2026-04-10T10:00:00Z',
          baseAmount: 400,
        },
        {
          id: 'tx-edo-2',
          instrumentId: 'PL_BOND',
          seriesName: 'EDO0636',
          transactionType: 'BUY',
          quantity: 4,
          price: 100,
          currency: 'PLN',
          executedAt: '2026-06-12T10:00:00Z',
          baseAmount: 400,
        },
        {
          id: 'tx-tos-1',
          instrumentId: 'PL_BOND',
          seriesName: 'TOS0129',
          transactionType: 'BUY',
          quantity: 120,
          price: 100,
          currency: 'PLN',
          executedAt: '2026-01-15T10:00:00Z',
          baseAmount: 12000,
        },
        {
          id: 'tx-tos-2',
          instrumentId: 'PL_BOND',
          seriesName: 'TOS0529',
          transactionType: 'BUY',
          quantity: 120,
          price: 100,
          currency: 'PLN',
          executedAt: '2026-05-20T10:00:00Z',
          baseAmount: 12000,
        },
      ];

      const enrichedHoldings = [
        {
          id: 'pos-edo-1',
          instrumentId: 'PL_BOND',
          ticker: 'EDO0436',
          name: 'EDO0436',
          assetType: 'BOND',
          isBond: true,
          quantity: 4,
          averagePrice: 100,
          currentPrice: 102.5,
          previousPrice: 102.4,
          currency: 'PLN',
          totalValue: 410,
          totalCost: 400,
          profit: 10,
          profitPct: 2.5,
          dailyChange: 0.4,
          seriesName: 'EDO0436',
          purchaseDate: '2026-04-10T10:00:00Z',
        },
        {
          id: 'pos-edo-2',
          instrumentId: 'PL_BOND',
          ticker: 'EDO0636',
          name: 'EDO0636',
          assetType: 'BOND',
          isBond: true,
          quantity: 4,
          averagePrice: 100,
          currentPrice: 101.5,
          previousPrice: 101.4,
          currency: 'PLN',
          totalValue: 406,
          totalCost: 400,
          profit: 6,
          profitPct: 1.5,
          dailyChange: 0.4,
          seriesName: 'EDO0636',
          purchaseDate: '2026-06-12T10:00:00Z',
        },
        {
          id: 'pos-tos',
          instrumentId: 'PL_BOND',
          ticker: 'TOS0129',
          name: 'TOS0129',
          assetType: 'BOND',
          isBond: true,
          quantity: 240,
          averagePrice: 100,
          currentPrice: 103.0,
          previousPrice: 102.9,
          currency: 'PLN',
          totalValue: 24720,
          totalCost: 24000,
          profit: 720,
          profitPct: 3.0,
          dailyChange: 24,
          seriesName: 'TOS0129',
          purchaseDate: '2026-01-15T10:00:00Z',
        },
      ];

      const positions = buildDashboardPositions({
        enrichedHoldings,
        transactions,
        totalPortfolioValue: 25536,
        baseCurrency: 'PLN',
        rates: { PLN: 1 },
        shouldApplyTax: true,
        nowTimestamp: nowTs,
      });

      const edoPos = positions.find((p) => p.ticker.includes('EDO'));
      assert.ok(edoPos, 'EDO position should be present');
      assert.strictEqual(edoPos.subPositionsType, 'bonds_yearly');
      assert.ok(edoPos.subPositions && edoPos.subPositions.length > 0);

      const vintage2026 = edoPos.subPositions.find((sub) => sub.name.includes('2026'));
      assert.ok(vintage2026, 'Rocznik 2026 subposition should exist for EDO');
      assert.ok(vintage2026.xirr != null, 'XIRR should be calculated for vintage 2026');
      assert.ok(vintage2026.xirr > 0.02 && vintage2026.xirr < 0.10, `EDO vintage XIRR must be positive annual return, got: ${vintage2026.xirr}`);
    });
  });


  describe('dashboard summary Netto vs Brutto portfolio value card', () => {
    const holdings = [
      {
        id: 'pos-1',
        instrumentId: 'inst-1',
        ticker: 'ETF1',
        name: 'ETF 1',
        assetType: 'ETF',
        isBond: false,
        quantity: 100,
        averagePrice: 100,
        currentPrice: 120,
        previousPrice: 119,
        currency: 'PLN',
        totalValue: 12000,
        totalCost: 10000,
        profit: 2000,
        profitPct: 20,
        dailyChange: 100,
        purchaseDate: '2026-01-01T00:00:00Z',
      },
    ];

    const txs = [
      {
        id: 'tx-1',
        instrumentId: 'inst-1',
        transactionType: 'BUY',
        quantity: 100,
        price: 100,
        currency: 'PLN',
        executedAt: '2026-01-01T00:00:00Z',
        baseAmount: 10000,
      },
    ];

    const positionsRaw = [
      {
        id: 'pos-1',
        instrumentId: 'inst-1',
        quantity: 100,
        averageBuyPrice: 100,
        totalCostBase: 10000,
        currency: 'PLN',
        purchaseDate: '2026-01-01T00:00:00Z',
      },
    ];

    it('formats portfolio value as net of tax when shouldApplyTax is true', () => {
      const summaryNet = buildDashboardSummary({
        totalPortfolioValue: 12000,
        totalPortfolioCost: 10000,
        enrichedHoldings: holdings,
        positions: positionsRaw,
        transactions: txs,
        baseCurrency: 'PLN',
        rates: { PLN: 1 },
        cpi: 3.0,
        taxMode: 'NETTO',
        shouldApplyTax: true,
        isIkeAccount: false,
        nowTimestamp: new Date('2026-06-01T00:00:00Z').getTime(),
        t: (k, d) => (d !== undefined ? d : k),
      });

      assert.strictEqual(summaryNet.portfolioValueFormatted, '11 620 PLN');
      assert.strictEqual(summaryNet.totalProfitAmountFormatted, '+1 620 PLN');
      assert.strictEqual(summaryNet.taxNoteFormatted, 'Belka: -380 PLN');
      assert.strictEqual(summaryNet.portfolioValueLabel, 'Wartość portfela (netto)');
    });

    it('formats portfolio value as gross when shouldApplyTax is false', () => {
      const summaryGross = buildDashboardSummary({
        totalPortfolioValue: 12000,
        totalPortfolioCost: 10000,
        enrichedHoldings: holdings,
        positions: positionsRaw,
        transactions: txs,
        baseCurrency: 'PLN',
        rates: { PLN: 1 },
        cpi: 3.0,
        taxMode: 'BRUTTO',
        shouldApplyTax: false,
        isIkeAccount: false,
        nowTimestamp: new Date('2026-06-01T00:00:00Z').getTime(),
        t: (k, d) => (d !== undefined ? d : k),
      });

      assert.strictEqual(summaryGross.portfolioValueFormatted, '12 000 PLN');
      assert.strictEqual(summaryGross.totalProfitAmountFormatted, '+2 000 PLN');
      assert.strictEqual(summaryGross.portfolioValueLabel, 'Wartość portfela (brutto)');
    });
  });

