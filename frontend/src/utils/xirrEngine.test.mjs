import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import {
  calculateXirr,
  calculateHoldingXirr,
  calculatePortfolioRealMetrics,
  calculateFxDecomposition,
} from './xirrEngine.ts';
import {
  calculateBondCurrentPrice,
  generateUnifiedPortfolioTimeline,
  getBondFamily,
} from './portfolioUnifiedEngine.ts';
import { buildDashboardPositions } from './dashboardCalculations.ts';

const assertCloseTo = (actual, expected, tolerance = 1e-4, message) => {
  assert.ok(
    typeof actual === 'number' && !Number.isNaN(actual),
    `${message || 'Expected number'}: received ${actual}`
  );
  const diff = Math.abs(actual - expected);
  assert.ok(
    diff <= tolerance,
    `${message || 'Assertion failed'}: expected ${actual} to be close to ${expected} (diff: ${diff}, tolerance: ${tolerance})`
  );
};

describe('xirrEngine', () => {
  describe('calculateXirr', () => {
    it('calculates exact 10% annual return for a single 365-day cash flow', () => {
      const cashFlows = [
        { amount: -1000, date: '2023-01-01' },
        { amount: 1100, date: '2024-01-01' },
      ];

      const rate = calculateXirr(cashFlows);
      assertCloseTo(rate, 0.10, 1e-4, 'Expected exact 10% annual return for 365-day period');
    });

    it('accounts for leap year day count (366 days) using Act/365 convention', () => {
      const cashFlows = [
        { amount: -1000, date: '2024-01-01' },
        { amount: 1100, date: '2025-01-01' },
      ];

      const rate = calculateXirr(cashFlows);
      assertCloseTo(rate, 0.099709, 1e-4, 'Expected Act/365 leap year return');
    });

    it('matches Microsoft Excel official XIRR benchmark test vector', () => {
      const excelBenchmarkFlows = [
        { amount: -10000, date: '2008-01-01' },
        { amount: 2750, date: '2008-03-01' },
        { amount: 4250, date: '2008-10-30' },
        { amount: 3250, date: '2009-02-15' },
        { amount: 2750, date: '2009-04-01' },
      ];

      const rate = calculateXirr(excelBenchmarkFlows);
      assertCloseTo(rate, 0.373363, 1e-4, 'Must match Excel official XIRR benchmark 37.34%');
    });

    it('calculates accurate XIRR for multi-transaction DCA streams with terminal valuation', () => {
      const dcaFlows = [
        { amount: -1000, date: '2024-01-01' },
        { amount: -1000, date: '2024-02-01' },
        { amount: -1000, date: '2024-03-01' },
        { amount: 3300, date: '2024-06-01' },
      ];

      const rate = calculateXirr(dcaFlows);
      assertCloseTo(rate, 0.330267, 1e-4, 'Matches multi-DCA benchmark return (33.03%)');
    });

    it('calculates accurate XIRR with SELL inflows interspersed between BUY outflows and terminal valuation', () => {
      const flowsWithSell = [
        { amount: -1000, date: '2023-01-01' },
        { amount: 360, date: '2023-07-02' },
        { amount: -550, date: '2023-10-01' },
        { amount: 1560, date: '2024-01-01' },
      ];

      const rate = calculateXirr(flowsWithSell);
      assertCloseTo(rate, 0.386886, 1e-4, 'Matches XIRR with interspersed SELL inflows');
    });

    it('returns 0.0 for zero profit cash flows', () => {
      const flatFlows = [
        { amount: -1000, date: '2023-01-01' },
        { amount: 1000, date: '2024-01-01' },
      ];

      const rate = calculateXirr(flatFlows);
      assertCloseTo(rate, 0.0, 1e-5, 'Zero profit should have 0% XIRR');
    });

    it('calculates negative return correctly for capital loss', () => {
      const lossFlows = [
        { amount: -1000, date: '2023-01-01' },
        { amount: 800, date: '2024-01-01' },
      ];

      const rate = calculateXirr(lossFlows);
      assertCloseTo(rate, -0.20, 1e-4, 'Expected -20% annualized return');
    });

    it('calculates high return correctly without divergence', () => {
      const highReturnFlows = [
        { amount: -1000, date: '2023-01-01' },
        { amount: 2500, date: '2024-01-01' },
      ];

      const rate = calculateXirr(highReturnFlows);
      assertCloseTo(rate, 1.50, 1e-4, 'Expected +150% return');
    });

    it('supports Date objects and timestamp numbers in cash flow dates', () => {
      const dateObjectFlows = [
        { amount: -1000, date: new Date('2023-01-01T00:00:00Z') },
        { amount: 1100, date: new Date('2024-01-01T00:00:00Z') },
      ];

      const rate = calculateXirr(dateObjectFlows);
      assertCloseTo(rate, 0.10, 1e-4, 'Supports Date object instances');
    });

    it('returns null safely for invalid or degenerate cash flows', () => {
      assert.strictEqual(calculateXirr([]), null, 'Empty array should return null');
      assert.strictEqual(
        calculateXirr([{ amount: -1000, date: '2023-01-01' }]),
        null,
        'Single cash flow should return null'
      );
      assert.strictEqual(
        calculateXirr([
          { amount: 1000, date: '2023-01-01' },
          { amount: 1200, date: '2024-01-01' },
        ]),
        null,
        'All positive cash flows (no outflows) should return null'
      );
      assert.strictEqual(
        calculateXirr([
          { amount: -1000, date: '2023-01-01' },
          { amount: -1200, date: '2024-01-01' },
        ]),
        null,
        'All negative cash flows (no inflows) should return null'
      );
      assert.strictEqual(
        calculateXirr([
          { amount: -1000, date: '2023-01-01' },
          { amount: 1100, date: '2023-01-01' },
        ]),
        null,
        'Identical transaction dates should return null'
      );
    });
  });

  describe('calculateHoldingXirr', () => {
    it('enforces < 30 days guardrail returning xirr: null and isSimpleFallback: true', () => {
      const recentHoldingFlows = [
        { amount: -1000, date: '2024-01-01' },
      ];
      const terminalValue = 1050;
      const terminalDate = '2024-01-15';

      const result = calculateHoldingXirr({
        cashFlows: recentHoldingFlows,
        currentValue: terminalValue,
        terminalDate,
      });

      assert.strictEqual(result.xirr, null, 'Younger than 30 days must return xirr null');
      assert.strictEqual(result.isSimpleFallback, true, 'isSimpleFallback must be true for < 30 days');
      assert.strictEqual(result.daysElapsed, 14, 'Must report accurate daysElapsed');
    });

    it('enforces exact 29 vs 30 day boundary condition', () => {
      const baseFlows = [{ amount: -1000, date: '2024-01-01' }];

      const day29 = calculateHoldingXirr({
        cashFlows: baseFlows,
        currentValue: 1020,
        terminalDate: '2024-01-30',
      });
      assert.strictEqual(day29.xirr, null);
      assert.strictEqual(day29.isSimpleFallback, true);
      assert.strictEqual(day29.daysElapsed, 29);

      const day30 = calculateHoldingXirr({
        cashFlows: baseFlows,
        currentValue: 1020,
        terminalDate: '2024-01-31',
      });
      assert.strictEqual(day30.isSimpleFallback, false);
      assert.strictEqual(day30.daysElapsed, 30);
      assert.ok(typeof day30.xirr === 'number' && !Number.isNaN(day30.xirr));
    });

    it('calculates holding XIRR for mature positions (>= 30 days)', () => {
      const holdingFlows = [
        { amount: -1000, date: '2023-01-01' },
      ];
      const terminalValue = 1100;
      const terminalDate = '2024-01-01';

      const result = calculateHoldingXirr({
        cashFlows: holdingFlows,
        currentValue: terminalValue,
        terminalDate,
      });

      assert.strictEqual(result.isSimpleFallback, false);
      assert.strictEqual(result.daysElapsed, 365);
      assertCloseTo(result.xirr, 0.10, 1e-4, 'Annual holding return must be 10%');
    });

    it('supports positional argument invocation signature calculateHoldingXirr(cashFlows, currentValue, terminalDate)', () => {
      const holdingFlows = [
        { amount: -1000, date: '2023-01-01' },
      ];
      const result = calculateHoldingXirr(holdingFlows, 1100, '2024-01-01');

      assert.strictEqual(result.isSimpleFallback, false);
      assertCloseTo(result.xirr, 0.10, 1e-4);
    });

    it('calculates holding XIRR when intermediate cash flows include SELL proceeds', () => {
      const holdingFlowsWithSell = [
        { amount: -1000, date: '2023-01-01' },
        { amount: 360, date: '2023-07-02' },
      ];

      const result = calculateHoldingXirr({
        cashFlows: holdingFlowsWithSell,
        currentValue: 1000,
        terminalDate: '2024-01-01',
      });

      assert.strictEqual(result.isSimpleFallback, false);
      assert.strictEqual(result.daysElapsed, 365);
      assertCloseTo(result.xirr, 0.430834, 1e-4, 'Annual holding return with intermediate SELL proceeds');
    });
  });

  describe('calculatePortfolioRealMetrics', () => {
    it('calculates fair compounded per-transaction inflation hurdle and real profit', () => {
      const transactions = [
        { cost: 10000, executedAt: '2023-01-01' },
        { cost: 5000, executedAt: '2023-07-03' },
      ];

      const metrics = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 18000,
        cpi: 5.0,
        nominalXirr: 0.20,
        currentDate: '2024-01-01',
      });

      assertCloseTo(metrics.inflationHurdle, 15623.13, 1e-1, 'Total inflation hurdle');
      assertCloseTo(metrics.realProfitAmount, 2376.87, 1e-1, 'Real profit amount');
      assertCloseTo(metrics.realProfitPercent, 15.21, 1e-2, 'Real profit percentage');
    });

    it('calculates real XIRR via the Fisher equation r_real = (1 + r_nominal)/(1 + CPI) - 1', () => {
      const transactions = [{ cost: 10000, executedAt: '2023-01-01' }];

      const metrics = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 12000,
        cpi: 5.0,
        nominalXirr: 0.20,
        currentDate: '2024-01-01',
      });

      assertCloseTo(metrics.realXirr, 0.142857, 1e-4, 'Fisher equation real XIRR');
    });

    it('returns realXirr: null when nominal XIRR is null', () => {
      const transactions = [{ cost: 10000, executedAt: '2024-01-01' }];

      const metrics = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 10200,
        cpi: 3.6,
        nominalXirr: null,
        currentDate: '2024-01-10',
      });

      assert.strictEqual(metrics.realXirr, null, 'Null nominal XIRR must yield null real XIRR');
    });

    it('handles uniform percentage CPI consistently without 100x spike on low inflation', () => {
      const transactions = [{ cost: 10000, executedAt: '2023-01-01' }];

      const metrics50 = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 12000,
        cpi: 5.0,
        nominalXirr: 0.20,
        currentDate: '2024-01-01',
      });

      assertCloseTo(metrics50.inflationHurdle, 10500, 1e-2);
      assertCloseTo(metrics50.realProfitAmount, 1500, 1e-2);
      assertCloseTo(metrics50.realXirr, 0.142857, 1e-4);

      const metrics015 = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 10500,
        cpi: 0.15,
        nominalXirr: 0.05,
        currentDate: '2024-01-01',
      });

      assertCloseTo(metrics015.inflationHurdle, 10015, 1e-2);
      assertCloseTo(metrics015.realProfitAmount, 485, 1e-2);
      assertCloseTo(metrics015.realXirr, 0.048427, 1e-4);
    });

    it('handles low inflation rates (e.g. CPI = 0.8% and 0.5%) without inflating to 80% or 50%', () => {
      const transactions = [{ cost: 10000, executedAt: '2023-01-01' }];

      const metrics08 = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 11000,
        cpi: 0.8,
        nominalXirr: 0.10,
        currentDate: '2024-01-01',
      });

      assertCloseTo(metrics08.inflationHurdle, 10080, 1e-2, 'Hurdle must reflect 0.8% inflation, not 80%');
      assertCloseTo(metrics08.realProfitAmount, 920, 1e-2, 'Real profit must be 11000 - 10080 = 920 PLN');
      assertCloseTo(metrics08.realProfitPercent, 9.127, 1e-2, 'Real profit percent must be (920 / 10080) * 100');
      assertCloseTo(metrics08.realXirr, 0.09127, 1e-4, 'Real XIRR must reflect 0.8% inflation via Fisher equation');

      const metrics05 = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 11000,
        cpi: 0.5,
        nominalXirr: 0.10,
        currentDate: '2024-01-01',
      });

      assertCloseTo(metrics05.inflationHurdle, 10050, 1e-2, 'Hurdle must reflect 0.5% inflation, not 50%');
      assertCloseTo(metrics05.realProfitAmount, 950, 1e-2, 'Real profit must be 11000 - 10050 = 950 PLN');
      assertCloseTo(metrics05.realProfitPercent, 9.453, 1e-2, 'Real profit percent must be (950 / 10050) * 100');
      assertCloseTo(metrics05.realXirr, 0.094527, 1e-4, 'Real XIRR must reflect 0.5% inflation via Fisher equation');
    });

    it('handles deflation (e.g. CPI = -0.5%) correctly reducing inflation hurdle', () => {
      const transactions = [{ cost: 10000, executedAt: '2023-01-01' }];

      const metricsDeflation = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 10500,
        cpi: -0.5,
        nominalXirr: 0.05,
        currentDate: '2024-01-01',
      });

      assertCloseTo(metricsDeflation.inflationHurdle, 9950, 1e-2, 'Deflation reduces inflation hurdle below nominal cost');
      assertCloseTo(metricsDeflation.realProfitAmount, 550, 1e-2, 'Real profit amount in deflation');
      assertCloseTo(metricsDeflation.realProfitPercent, 5.5276, 1e-2, 'Real profit percentage in deflation');
      assertCloseTo(metricsDeflation.realXirr, 0.055276, 1e-4, 'Real XIRR in deflation via Fisher equation');
    });

    it('handles empty transaction list without throwing or division by zero', () => {
      const metrics = calculatePortfolioRealMetrics({
        transactions: [],
        portfolioValue: 0,
        cpi: 3.6,
        nominalXirr: null,
      });

      assert.strictEqual(metrics.inflationHurdle, 0);
      assert.strictEqual(metrics.realProfitAmount, 0);
      assert.strictEqual(metrics.realProfitPercent, 0);
      assert.strictEqual(metrics.realXirr, null);
    });
  });

  describe('calculateFxDecomposition', () => {
    it('decomposes foreign asset gains and currency appreciation matching (1+R_total) = (1+R_asset)(1+R_fx)', () => {
      const decomp = calculateFxDecomposition({
        buyPrice: 100,
        currentPrice: 110,
        buyExchangeRate: 4.00,
        currentExchangeRate: 4.20,
      });

      assert.ok(decomp != null, 'Must return decomposition object');
      assertCloseTo(decomp.marketReturnPct, 10.0, 1e-4, 'Market return pct');
      assertCloseTo(decomp.fxReturnPct, 5.0, 1e-4, 'FX return pct');

      const rAsset = decomp.marketReturnPct / 100;
      const rFx = decomp.fxReturnPct / 100;
      const compoundTotal = (1 + rAsset) * (1 + rFx) - 1;
      assertCloseTo(compoundTotal, 0.155, 1e-4, 'Exact multiplicative decomposition product');
    });

    it('decomposes asset gain with currency drag (depreciation)', () => {
      const decomp = calculateFxDecomposition({
        buyPrice: 100,
        currentPrice: 120,
        buyExchangeRate: 4.50,
        currentExchangeRate: 4.05,
      });

      assert.ok(decomp != null);
      assertCloseTo(decomp.marketReturnPct, 20.0, 1e-4, 'Market return pct');
      assertCloseTo(decomp.fxReturnPct, -10.0, 1e-4, 'FX return pct');
    });

    it('decomposes asset decline with currency buffer (appreciation)', () => {
      const decomp = calculateFxDecomposition({
        buyPrice: 100,
        currentPrice: 90,
        buyExchangeRate: 4.00,
        currentExchangeRate: 4.40,
      });

      assert.ok(decomp != null);
      assertCloseTo(decomp.marketReturnPct, -10.0, 1e-4, 'Market return pct');
      assertCloseTo(decomp.fxReturnPct, 10.0, 1e-4, 'FX return pct');
    });

    it('supports positional argument invocation calculateFxDecomposition(buyPrice, currentPrice, buyFx, currentFx)', () => {
      const decomp = calculateFxDecomposition(100, 110, 4.00, 4.20);

      assert.ok(decomp != null);
      assertCloseTo(decomp.marketReturnPct, 10.0, 1e-4);
      assertCloseTo(decomp.fxReturnPct, 5.0, 1e-4);
    });

    it('returns null or zero fx for invalid or non-positive price/rate inputs', () => {
      assert.strictEqual(calculateFxDecomposition({ buyPrice: 0, currentPrice: 100, buyExchangeRate: 4, currentExchangeRate: 4 }), null);
      assert.strictEqual(calculateFxDecomposition({ buyPrice: 100, currentPrice: 100, buyExchangeRate: 0, currentExchangeRate: 4 }), null);
    });
  });

  describe('calculateBondCurrentPrice and portfolioUnifiedEngine', () => {
    it('applies termMonths maturity cap so bond stops accumulating past maturity', () => {
      const priceAtMaturity = calculateBondCurrentPrice({
        purchaseDate: '2020-01-01',
        bondRate: 6.0,
        targetDate: '2023-01-01',
        termMonths: 36,
        seriesName: 'TOS0123',
      });
      const pricePastMaturity = calculateBondCurrentPrice({
        purchaseDate: '2020-01-01',
        bondRate: 6.0,
        targetDate: '2025-01-01',
        termMonths: 36,
        seriesName: 'TOS0123',
      });
      assertCloseTo(pricePastMaturity, priceAtMaturity, 1e-2);
    });

    it('calculates simple non-compounded interest for COI bond', () => {
      const price = calculateBondCurrentPrice({
        purchaseDate: '2022-01-01',
        bondRate: 7.0,
        bondMargin: 1.5,
        cpi: 3.0,
        targetDate: '2024-01-01',
        seriesName: 'COI0126',
      });
      assertCloseTo(price, 111.5, 1e-2);
    });

    it('reduces weighted-average cost basis proportionally on SELL rather than subtracting proceeds', () => {
      const pts = generateUnifiedPortfolioTimeline({
        transactions: [
          {
            id: '1',
            instrumentId: 'inst-etf',
            transactionType: 'BUY',
            quantity: 10,
            price: 150,
            currency: 'PLN',
            executedAt: '2024-01-01T00:00:00.000Z',
            baseAmount: 1500,
          },
          {
            id: '2',
            instrumentId: 'inst-etf',
            transactionType: 'SELL',
            quantity: 5,
            price: 300,
            currency: 'PLN',
            executedAt: '2024-06-01T00:00:00.000Z',
            baseAmount: 1500,
          },
        ],
        positions: [],
        instruments: {
          'inst-etf': { id: 'inst-etf', symbol: 'VWCE', type: 'ETF', currency: 'PLN' },
        },
        totalPortfolioValue: 0,
        totalPortfolioCost: 0,
        taxMode: 'STANDARD',
        timeframe: 'ALL',
        projectionHorizon: 'OFF',
        cpi: 3.6,
        rates: { PLN: 1 },
        baseCurrency: 'PLN',
      });
      const sellTs = new Date('2024-06-01T00:00:00.000Z').getTime();
      const ptAfterSell = pts.find((p) => p.timestamp >= sellTs);
      assert.ok(ptAfterSell, 'Timeline point after sell must exist');
      assertCloseTo(ptAfterSell.cost, 750, 1e-2);
    });
  });

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
  });

  describe('profit attribution breakdown and getBondFamily', () => {
    it('extracts bond family code from series names and symbols', () => {
      assert.strictEqual(typeof getBondFamily, 'function', 'getBondFamily must be exported as a function');
      assert.strictEqual(getBondFamily('EDO0134'), 'EDO');
      assert.strictEqual(getBondFamily('TOS0126'), 'TOS');
      assert.strictEqual(getBondFamily('COI1225'), 'COI');
      assert.strictEqual(getBondFamily('ROR0325'), 'ROR');
      assert.strictEqual(getBondFamily('DOR0525'), 'DOR');
      assert.strictEqual(getBondFamily('OTS0425'), 'OTS');
      assert.strictEqual(getBondFamily('PL_BOND_EDO_2024'), 'EDO');
    });

    it('populates contributors on historical points with matching sums in BRUTTO and NETTO tax modes', () => {
      const now = Date.now();
      const pastDate = new Date(now - 90 * 24 * 3600 * 1000).toISOString();
      const transactions = [
        {
          id: 'tx-vwce',
          instrumentId: 'inst-vwce',
          transactionType: 'BUY',
          quantity: 10,
          price: 150,
          currency: 'PLN',
          executedAt: pastDate,
          baseAmount: 1500,
        },
        {
          id: 'tx-edo',
          instrumentId: 'inst-edo',
          seriesName: 'EDO0134',
          bondRate: 10.0,
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: pastDate,
          baseAmount: 1000,
        },
      ];
      const positions = [
        {
          id: 'pos-vwce',
          instrumentId: 'inst-vwce',
          quantity: 10,
          averageBuyPrice: 150,
          totalCostBase: 1500,
          currency: 'PLN',
          isBond: false,
          purchaseDate: pastDate,
        },
        {
          id: 'pos-edo',
          instrumentId: 'inst-edo',
          seriesName: 'EDO0134',
          quantity: 10,
          averageBuyPrice: 100,
          totalCostBase: 1000,
          currency: 'PLN',
          isBond: true,
          bondRate: 10.0,
          purchaseDate: pastDate,
        },
      ];
      const instruments = {
        'inst-vwce': { id: 'inst-vwce', symbol: 'VWCE', type: 'ETF', currency: 'PLN' },
        'inst-edo': { id: 'inst-edo', symbol: 'EDO0134', type: 'BOND', currency: 'PLN' },
      };
      const instrumentPrices = {
        'inst-vwce': 180,
      };

      for (const taxMode of ['BRUTTO', 'NETTO']) {
        const points = generateUnifiedPortfolioTimeline({
          transactions,
          positions,
          instruments,
          totalPortfolioValue: 0,
          totalPortfolioCost: 0,
          taxMode,
          timeframe: 'MAX',
          projectionHorizon: 'OFF',
          cpi: 3.6,
          rates: { PLN: 1 },
          baseCurrency: 'PLN',
          instrumentPrices,
          isIkeAccount: false,
        });

        assert.ok(points.length > 0);
        const historicalPoints = points.filter((p) => !p.isFuture);
        assert.ok(historicalPoints.length > 0);

        for (const pt of historicalPoints) {
          assert.ok(Array.isArray(pt.contributors), 'Point contributors must be an array');
          assert.ok(pt.contributors.length > 0, 'Point contributors must not be empty');

          for (const c of pt.contributors) {
            assert.strictEqual(typeof c.key, 'string');
            assert.strictEqual(typeof c.label, 'string');
            assert.ok(['ETF', 'BOND', 'STOCK', 'OTHER'].includes(c.assetType));
            assert.strictEqual(typeof c.profit, 'number');
            assert.strictEqual(typeof c.returnContributionPct, 'number');
            assert.strictEqual(typeof c.sharePct, 'number');
          }

          const sumProfit = pt.contributors.reduce((acc, c) => acc + c.profit, 0);
          assert.strictEqual(sumProfit, pt.profit, 'Sum of contributor profits must equal point.profit');

          const sumReturn = pt.contributors.reduce((acc, c) => acc + c.returnContributionPct, 0);
          assertCloseTo(sumReturn, pt.returnPct, 0.05, 'Sum of contributor returnContributionPct must equal point.returnPct');

          if (pt.profit > 0) {
            const sumShare = pt.contributors.reduce((acc, c) => acc + c.sharePct, 0);
            assertCloseTo(sumShare, 100.0, 0.2, 'Sum of positive shares must equal 100%');
          }
        }
      }
    });

    it('aggregates multiple bond positions of the same family into a single contributor key', () => {
      const now = Date.now();
      const pastDate = new Date(now - 90 * 24 * 3600 * 1000).toISOString();
      const transactions = [
        {
          id: 'tx-edo-1',
          instrumentId: 'inst-edo-1',
          seriesName: 'EDO0134',
          bondRate: 7.0,
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: pastDate,
          baseAmount: 1000,
        },
        {
          id: 'tx-edo-2',
          instrumentId: 'inst-edo-2',
          seriesName: 'EDO0234',
          bondRate: 6.8,
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: pastDate,
          baseAmount: 1000,
        },
        {
          id: 'tx-vwce',
          instrumentId: 'inst-vwce',
          transactionType: 'BUY',
          quantity: 10,
          price: 150,
          currency: 'PLN',
          executedAt: pastDate,
          baseAmount: 1500,
        },
      ];
      const positions = [
        {
          id: 'pos-edo-1',
          instrumentId: 'inst-edo-1',
          seriesName: 'EDO0134',
          quantity: 10,
          averageBuyPrice: 100,
          totalCostBase: 1000,
          currency: 'PLN',
          isBond: true,
          bondRate: 7.0,
          purchaseDate: pastDate,
        },
        {
          id: 'pos-edo-2',
          instrumentId: 'inst-edo-2',
          seriesName: 'EDO0234',
          quantity: 10,
          averageBuyPrice: 100,
          totalCostBase: 1000,
          currency: 'PLN',
          isBond: true,
          bondRate: 6.8,
          purchaseDate: pastDate,
        },
        {
          id: 'pos-vwce',
          instrumentId: 'inst-vwce',
          quantity: 10,
          averageBuyPrice: 150,
          totalCostBase: 1500,
          currency: 'PLN',
          isBond: false,
          purchaseDate: pastDate,
        },
      ];
      const instruments = {
        'inst-edo-1': { id: 'inst-edo-1', symbol: 'EDO0134', type: 'BOND', currency: 'PLN' },
        'inst-edo-2': { id: 'inst-edo-2', symbol: 'EDO0234', type: 'BOND', currency: 'PLN' },
        'inst-vwce': { id: 'inst-vwce', symbol: 'VWCE', type: 'ETF', currency: 'PLN' },
      };

      const points = generateUnifiedPortfolioTimeline({
        transactions,
        positions,
        instruments,
        totalPortfolioValue: 0,
        totalPortfolioCost: 0,
        taxMode: 'BRUTTO',
        timeframe: 'MAX',
        projectionHorizon: 'OFF',
        cpi: 3.6,
        rates: { PLN: 1 },
        baseCurrency: 'PLN',
      });

      const latestPoint = points[points.length - 1];
      assert.ok(Array.isArray(latestPoint.contributors), 'Point contributors must be an array');

      const edoContributor = latestPoint.contributors.find((c) => c.key === 'EDO');
      assert.ok(edoContributor, 'Bond family EDO contributor must exist');
      assert.strictEqual(edoContributor.assetType, 'BOND');
      assert.strictEqual(edoContributor.label, 'EDO');

      assert.strictEqual(
        latestPoint.contributors.some((c) => c.key === 'EDO0134'),
        false,
        'EDO0134 must not appear as an individual contributor'
      );
      assert.strictEqual(
        latestPoint.contributors.some((c) => c.key === 'EDO0234'),
        false,
        'EDO0234 must not appear as an individual contributor'
      );

      const vwceContributor = latestPoint.contributors.find((c) => c.key === 'VWCE');
      assert.ok(vwceContributor, 'VWCE contributor must exist');
      assert.strictEqual(vwceContributor.assetType, 'ETF');
    });

    it('handles mixed gains and losses with signed return contributions and absolute profit normalization', () => {
      const now = Date.now();
      const pastDate = new Date(now - 90 * 24 * 3600 * 1000).toISOString();
      const transactions = [
        {
          id: 'tx-win',
          instrumentId: 'inst-win',
          transactionType: 'BUY',
          quantity: 5,
          price: 100,
          currency: 'PLN',
          executedAt: pastDate,
          baseAmount: 500,
        },
        {
          id: 'tx-loss',
          instrumentId: 'inst-loss',
          transactionType: 'BUY',
          quantity: 5,
          price: 100,
          currency: 'PLN',
          executedAt: pastDate,
          baseAmount: 500,
        },
      ];
      const positions = [
        {
          id: 'pos-win',
          instrumentId: 'inst-win',
          quantity: 5,
          averageBuyPrice: 100,
          totalCostBase: 500,
          currency: 'PLN',
          isBond: false,
          purchaseDate: pastDate,
        },
        {
          id: 'pos-loss',
          instrumentId: 'inst-loss',
          quantity: 5,
          averageBuyPrice: 100,
          totalCostBase: 500,
          currency: 'PLN',
          isBond: false,
          purchaseDate: pastDate,
        },
      ];
      const instruments = {
        'inst-win': { id: 'inst-win', symbol: 'VWCE', type: 'ETF', currency: 'PLN' },
        'inst-loss': { id: 'inst-loss', symbol: 'SXR8', type: 'ETF', currency: 'PLN' },
      };
      const instrumentPrices = {
        'inst-win': 130,
        'inst-loss': 90,
      };

      const points = generateUnifiedPortfolioTimeline({
        transactions,
        positions,
        instruments,
        totalPortfolioValue: 1100,
        totalPortfolioCost: 1000,
        taxMode: 'BRUTTO',
        timeframe: 'MAX',
        projectionHorizon: 'OFF',
        cpi: 3.6,
        rates: { PLN: 1 },
        baseCurrency: 'PLN',
        instrumentPrices,
      });

      const latestPoint = points[points.length - 1];
      assert.ok(Array.isArray(latestPoint.contributors), 'Point contributors must be an array');
      assert.strictEqual(latestPoint.cost, 1000);
      assert.strictEqual(latestPoint.profit, 100);
      assertCloseTo(latestPoint.returnPct, 10.0, 0.01);

      const winContributor = latestPoint.contributors.find((c) => c.key === 'VWCE');
      const lossContributor = latestPoint.contributors.find((c) => c.key === 'SXR8');

      assert.ok(winContributor, 'Winning contributor VWCE must exist');
      assert.ok(lossContributor, 'Losing contributor SXR8 must exist');

      assert.strictEqual(winContributor.profit, 150);
      assert.strictEqual(lossContributor.profit, -50);

      assertCloseTo(winContributor.returnContributionPct, 15.0, 0.01);
      assertCloseTo(lossContributor.returnContributionPct, -5.0, 0.01);
      assertCloseTo(
        winContributor.returnContributionPct + lossContributor.returnContributionPct,
        latestPoint.returnPct,
        0.01
      );

      assertCloseTo(winContributor.sharePct, 75.0, 0.1);
      assertCloseTo(lossContributor.sharePct, -25.0, 0.1);
    });

    it('caps distinct contributors at top 5 by absolute profit and buckets the remainder into OTHER', () => {
      const now = Date.now();
      const pastDate = new Date(now - 90 * 24 * 3600 * 1000).toISOString();
      const holdingsConfig = [
        { id: 'inst-1', symbol: 'VWCE', type: 'ETF', isBond: false, cost: 1000, val: 1600 },
        { id: 'inst-2', symbol: 'SXR8', type: 'ETF', isBond: false, cost: 1000, val: 1500 },
        { id: 'inst-3', symbol: 'EDO0134', type: 'BOND', isBond: true, seriesName: 'EDO0134', cost: 1000, val: 1400 },
        { id: 'inst-4', symbol: 'TOS0126', type: 'BOND', isBond: true, seriesName: 'TOS0126', cost: 1000, val: 1300 },
        { id: 'inst-5', symbol: 'COI1225', type: 'BOND', isBond: true, seriesName: 'COI1225', cost: 1000, val: 1200 },
        { id: 'inst-6', symbol: 'IWDA', type: 'ETF', isBond: false, cost: 1000, val: 1100 },
        { id: 'inst-7', symbol: 'EMIM', type: 'ETF', isBond: false, cost: 1000, val: 1050 },
      ];

      const transactions = holdingsConfig.map((h) => ({
        id: `tx-${h.id}`,
        instrumentId: h.id,
        seriesName: h.seriesName,
        bondRate: h.isBond ? 10.0 : undefined,
        transactionType: 'BUY',
        quantity: 10,
        price: 100,
        currency: 'PLN',
        executedAt: pastDate,
        baseAmount: h.cost,
      }));

      const positions = holdingsConfig.map((h) => ({
        id: `pos-${h.id}`,
        instrumentId: h.id,
        seriesName: h.seriesName,
        quantity: 10,
        averageBuyPrice: 100,
        totalCostBase: h.cost,
        currency: 'PLN',
        isBond: h.isBond,
        bondRate: h.isBond ? 10.0 : undefined,
        purchaseDate: pastDate,
      }));

      const instruments = Object.fromEntries(
        holdingsConfig.map((h) => [
          h.id,
          { id: h.id, symbol: h.symbol, type: h.type, currency: 'PLN' },
        ])
      );

      const instrumentPrices = Object.fromEntries(
        holdingsConfig.map((h) => [h.id, h.val / 10])
      );

      const totalPortfolioCost = holdingsConfig.reduce((acc, h) => acc + h.cost, 0);
      const totalPortfolioValue = holdingsConfig.reduce((acc, h) => acc + h.val, 0);

      const points = generateUnifiedPortfolioTimeline({
        transactions,
        positions,
        instruments,
        totalPortfolioValue,
        totalPortfolioCost,
        taxMode: 'BRUTTO',
        timeframe: 'MAX',
        projectionHorizon: 'OFF',
        cpi: 3.6,
        rates: { PLN: 1 },
        baseCurrency: 'PLN',
        instrumentPrices,
      });

      const latestPoint = points[points.length - 1];
      assert.ok(Array.isArray(latestPoint.contributors), 'Point contributors must be an array');
      assert.strictEqual(latestPoint.contributors.length, 6, 'Must have 5 top contributors plus 1 OTHER bucket');

      const keys = latestPoint.contributors.map((c) => c.key);
      assert.deepStrictEqual(keys.slice(0, 5), ['VWCE', 'SXR8', 'EDO', 'TOS', 'COI']);
      assert.strictEqual(keys[5], 'OTHER');

      const otherContributor = latestPoint.contributors.find((c) => c.key === 'OTHER');
      assert.ok(otherContributor, 'OTHER contributor must exist');
      assert.strictEqual(otherContributor.assetType, 'OTHER');
      assert.strictEqual(otherContributor.profit, 150);

      assert.strictEqual(keys.includes('IWDA'), false);
      assert.strictEqual(keys.includes('EMIM'), false);

      const sumProfit = latestPoint.contributors.reduce((acc, c) => acc + c.profit, 0);
      assert.strictEqual(sumProfit, latestPoint.profit);
    });

    it('populates contributors on historical points while preserving future projection points', () => {
      const now = Date.now();
      const pastDate = new Date(now - 90 * 24 * 3600 * 1000).toISOString();
      const transactions = [
        {
          id: 'tx-vwce',
          instrumentId: 'inst-vwce',
          transactionType: 'BUY',
          quantity: 10,
          price: 150,
          currency: 'PLN',
          executedAt: pastDate,
          baseAmount: 1500,
        },
      ];
      const positions = [
        {
          id: 'pos-vwce',
          instrumentId: 'inst-vwce',
          quantity: 10,
          averageBuyPrice: 150,
          totalCostBase: 1500,
          currency: 'PLN',
          isBond: false,
          purchaseDate: pastDate,
        },
      ];
      const instruments = {
        'inst-vwce': { id: 'inst-vwce', symbol: 'VWCE', type: 'ETF', currency: 'PLN' },
      };

      const points = generateUnifiedPortfolioTimeline({
        transactions,
        positions,
        instruments,
        totalPortfolioValue: 1800,
        totalPortfolioCost: 1500,
        taxMode: 'BRUTTO',
        timeframe: 'MAX',
        projectionHorizon: '3Y',
        cpi: 3.6,
        rates: { PLN: 1 },
        baseCurrency: 'PLN',
        instrumentPrices: { 'inst-vwce': 180 },
      });

      const historicalPoints = points.filter((p) => !p.isFuture);
      const futurePoints = points.filter((p) => p.isFuture === true);

      assert.ok(historicalPoints.length > 0, 'Must have historical points');
      assert.ok(futurePoints.length > 0, 'Must have future projection points');

      for (const pt of historicalPoints) {
        assert.ok(Array.isArray(pt.contributors), 'Historical points must have contributors');
        assert.ok(pt.contributors.length > 0, 'Historical contributors must not be empty');
      }

      const terminalPoint = futurePoints[futurePoints.length - 1];
      const latestHistorical = historicalPoints[historicalPoints.length - 1];
      assert.ok(terminalPoint.timestamp > latestHistorical.timestamp, 'Future projection must extend past today');
      assert.strictEqual(futurePoints[0].isFuture, true);
    });

    it('prevents cross-contamination between multi-lot bond positions sharing instrumentId and equity contributors', () => {
      const now = Date.now();
      const d1 = new Date(now - 730 * 24 * 3600 * 1000).toISOString();
      const d2 = new Date(now - 365 * 24 * 3600 * 1000).toISOString();
      const dVwce = new Date(now - 120 * 24 * 3600 * 1000).toISOString();

      const transactions = [
        {
          id: 'tx-edo-1',
          instrumentId: 'inst-edo',
          seriesName: 'EDO0122',
          bondRate: 10.0,
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: d1,
          baseAmount: 1000,
        },
        {
          id: 'tx-edo-2',
          instrumentId: 'inst-edo',
          seriesName: 'EDO0125',
          bondRate: 8.0,
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: d2,
          baseAmount: 1000,
        },
        {
          id: 'tx-vwce',
          instrumentId: 'inst-vwce',
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: dVwce,
          baseAmount: 1000,
        },
      ];

      const positions = [
        {
          id: 'pos-edo-1',
          instrumentId: 'inst-edo',
          seriesName: 'EDO0122',
          quantity: 10,
          averageBuyPrice: 100,
          totalCostBase: 1000,
          currency: 'PLN',
          isBond: true,
          bondRate: 10.0,
          purchaseDate: d1,
        },
        {
          id: 'pos-edo-2',
          instrumentId: 'inst-edo',
          seriesName: 'EDO0125',
          quantity: 10,
          averageBuyPrice: 100,
          totalCostBase: 1000,
          currency: 'PLN',
          isBond: true,
          bondRate: 8.0,
          purchaseDate: d2,
        },
        {
          id: 'pos-vwce',
          instrumentId: 'inst-vwce',
          quantity: 10,
          averageBuyPrice: 100,
          totalCostBase: 1000,
          currency: 'PLN',
          isBond: false,
          purchaseDate: dVwce,
        },
      ];

      const instruments = {
        'inst-edo': { id: 'inst-edo', symbol: 'EDO', type: 'BOND', currency: 'PLN' },
        'inst-vwce': { id: 'inst-vwce', symbol: 'VWCE', type: 'ETF', currency: 'PLN' },
      };

      const instrumentPrices = {
        'inst-edo': 108,
        'inst-vwce': 150,
      };

      const totalPortfolioCost = 3000;
      const totalPortfolioValue = 3790;

      const points = generateUnifiedPortfolioTimeline({
        transactions,
        positions,
        instruments,
        totalPortfolioValue,
        totalPortfolioCost,
        taxMode: 'BRUTTO',
        timeframe: 'MAX',
        projectionHorizon: 'OFF',
        cpi: 3.6,
        rates: { PLN: 1 },
        baseCurrency: 'PLN',
        instrumentPrices,
      });

      const latestPoint = points[points.length - 1];
      assert.ok(Array.isArray(latestPoint.contributors), 'Point contributors must be an array');
      assert.strictEqual(latestPoint.profit, 790);

      const vwce = latestPoint.contributors.find((c) => c.key === 'VWCE');
      const edo = latestPoint.contributors.find((c) => c.key === 'EDO');

      assert.ok(vwce, 'VWCE contributor must exist');
      assert.ok(edo, 'EDO contributor must exist');

      assert.strictEqual(vwce.profit, 500, 'VWCE profit must match true 1500 - 1000 without cross-contamination');
      assert.strictEqual(edo.profit, 290, 'EDO profit must match true per-lot sum (210 + 80) without cross-contamination');
      assert.strictEqual(vwce.valuation, 1500);
      assert.strictEqual(edo.valuation, 2290);
    });
  });
});
