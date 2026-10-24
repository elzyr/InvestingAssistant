import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import {
  calculateXirr,
  calculateHoldingXirr,
  calculatePortfolioRealMetrics,
  calculateFxDecomposition,
} from './xirrEngine.ts';

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
      // (1 + r)^(366/365) = 1.1 => r = 1.1^(365/366) - 1 ≈ 0.099709
      assertCloseTo(rate, 0.099709, 1e-4, 'Expected Act/365 leap year return');
    });

    it('matches Microsoft Excel official XIRR benchmark test vector', () => {
      // Official MS Excel XIRR documentation example:
      // 2008-01-01: -10,000
      // 2008-03-01: 2,750
      // 2008-10-30: 4,250
      // 2009-02-15: 3,250
      // 2009-04-01: 2,750
      // Documented Excel result: 0.373363 (37.34%)
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
      // Monthly DCA: 1000 on Jan 1, Feb 1, Mar 1; valuation 3300 on Jun 1
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
      // 2023-01-01: BUY -1000
      // 2023-07-02: SELL +360 (182 days)
      // 2023-10-01: BUY -550 (273 days)
      // 2024-01-01: Valuation +1560 (365 days)
      // Known independent benchmark rate: 0.386886 (38.69%)
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
      const terminalDate = '2024-01-15'; // 14 days elapsed

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

      // Day 29: guardrail active
      const day29 = calculateHoldingXirr({
        cashFlows: baseFlows,
        currentValue: 1020,
        terminalDate: '2024-01-30', // 29 days
      });
      assert.strictEqual(day29.xirr, null);
      assert.strictEqual(day29.isSimpleFallback, true);
      assert.strictEqual(day29.daysElapsed, 29);

      // Day 30: guardrail lifted
      const day30 = calculateHoldingXirr({
        cashFlows: baseFlows,
        currentValue: 1020,
        terminalDate: '2024-01-31', // 30 days
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
      const terminalDate = '2024-01-01'; // 365 days

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
      // Position with an intermediate partial sale:
      // 2023-01-01: BUY -1000
      // 2023-07-02: SELL +360
      // 2024-01-01: Terminal value 1000
      // Known independent benchmark rate: 0.430834 (43.08%)
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
      // Transaction 1: 10,000 PLN executed exactly 1 year ago (365 days)
      // Transaction 2: 5,000 PLN executed 182 days ago
      // Current valuation: 18,000 PLN
      // CPI: 5% (0.05)
      //
      // Hurdle 1 = 10000 * (1 + 0.05)^1 = 10500.00 PLN
      // Hurdle 2 = 5000 * (1 + 0.05)^(182/365) = 5123.13 PLN
      // Total hurdle = 15623.13 PLN
      // Real profit amount = 18000 - 15623.13 = 2376.87 PLN
      // Real profit percent = 2376.87 / 15623.13 * 100 = 15.21%
      const transactions = [
        { cost: 10000, executedAt: '2023-01-01' },
        { cost: 5000, executedAt: '2023-07-03' }, // 182 days to 2024-01-01
      ];

      const metrics = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 18000,
        cpi: 0.05,
        nominalXirr: 0.20,
        currentDate: '2024-01-01',
      });

      assertCloseTo(metrics.inflationHurdle, 15623.13, 1e-1, 'Total inflation hurdle');
      assertCloseTo(metrics.realProfitAmount, 2376.87, 1e-1, 'Real profit amount');
      assertCloseTo(metrics.realProfitPercent, 15.21, 1e-2, 'Real profit percentage');
    });

    it('calculates real XIRR via the Fisher equation r_real = (1 + r_nominal)/(1 + CPI) - 1', () => {
      // Nominal XIRR: 20% (0.20), CPI: 5% (0.05)
      // r_real = 1.20 / 1.05 - 1 = 0.142857 (14.29%)
      const transactions = [{ cost: 10000, executedAt: '2023-01-01' }];

      const metrics = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 12000,
        cpi: 0.05,
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

    it('handles CPI passed as percentage (e.g. 5.0) or ratio (e.g. 0.05) consistently', () => {
      const transactions = [{ cost: 10000, executedAt: '2023-01-01' }];

      const metricsWithPercent = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 12000,
        cpi: 5.0, // 5.0%
        nominalXirr: 0.20,
        currentDate: '2024-01-01',
      });

      const metricsWithRatio = calculatePortfolioRealMetrics({
        transactions,
        portfolioValue: 12000,
        cpi: 0.05, // 0.05
        nominalXirr: 0.20,
        currentDate: '2024-01-01',
      });

      assertCloseTo(metricsWithPercent.inflationHurdle, metricsWithRatio.inflationHurdle, 1e-4);
      assertCloseTo(metricsWithPercent.realProfitAmount, metricsWithRatio.realProfitAmount, 1e-4);
      assertCloseTo(metricsWithPercent.realXirr, metricsWithRatio.realXirr, 1e-4);
    });

    it('handles low inflation rates (e.g. CPI = 0.8% and 0.5%) without inflating to 80% or 50%', () => {
      // Deposit 10,000 PLN 1 year ago (365 days)
      // CPI = 0.8 (meaning 0.8% inflation, rate = 0.008)
      // Expected hurdle = 10000 * (1 + 0.008)^1 = 10080 PLN (NOT 18000 PLN from 80%)
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
      // Fisher equation: 1.10 / 1.008 - 1 ≈ 0.09127 (9.13%)
      assertCloseTo(metrics08.realXirr, 0.09127, 1e-4, 'Real XIRR must reflect 0.8% inflation via Fisher equation');

      // CPI = 0.5 (meaning 0.5% inflation, rate = 0.005)
      // Expected hurdle = 10000 * (1 + 0.005)^1 = 10050 PLN (NOT 15000 PLN from 50%)
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
      // Fisher equation: 1.10 / 1.005 - 1 ≈ 0.094527
      assertCloseTo(metrics05.realXirr, 0.094527, 1e-4, 'Real XIRR must reflect 0.5% inflation via Fisher equation');
    });

    it('handles deflation (e.g. CPI = -0.5%) correctly reducing inflation hurdle', () => {
      // Deposit 10,000 PLN 1 year ago (365 days)
      // CPI = -0.5 (meaning -0.5% deflation, rate = -0.005)
      // Expected hurdle = 10000 * (1 - 0.005)^1 = 9950 PLN
      // With portfolio value = 10,500 PLN:
      // Real profit amount = 10500 - 9950 = 550 PLN
      // Real profit percent = (550 / 9950) * 100 ≈ 5.5276%
      // Fisher equation: (1 + 0.05) / (1 - 0.005) - 1 = 1.05 / 0.995 - 1 ≈ 0.055276 (5.53%)
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
      // Buy price: 100 EUR, Current price: 110 EUR (+10.0% asset return)
      // Buy FX: 4.00 PLN/EUR, Current FX: 4.20 PLN/EUR (+5.0% currency appreciation)
      // Total cost PLN: 400 PLN, Total current PLN: 462 PLN (+15.5% total return)
      // (1 + 0.10) * (1 + 0.05) = 1.155 => +15.5%
      const decomp = calculateFxDecomposition({
        buyPrice: 100,
        currentPrice: 110,
        buyExchangeRate: 4.00,
        currentExchangeRate: 4.20,
      });

      assert.ok(decomp != null, 'Must return decomposition object');
      assertCloseTo(decomp.marketReturnPct, 10.0, 1e-4, 'Market return pct');
      assertCloseTo(decomp.fxReturnPct, 5.0, 1e-4, 'FX return pct');

      // Verify exact multiplicative equivalence
      const rAsset = decomp.marketReturnPct / 100;
      const rFx = decomp.fxReturnPct / 100;
      const compoundTotal = (1 + rAsset) * (1 + rFx) - 1;
      assertCloseTo(compoundTotal, 0.155, 1e-4, 'Exact multiplicative decomposition product');
    });

    it('decomposes asset gain with currency drag (depreciation)', () => {
      // Buy price: 100 EUR, Current price: 120 EUR (+20.0% asset return)
      // Buy FX: 4.50 PLN/EUR, Current FX: 4.05 PLN/EUR (-10.0% currency drag)
      // Total compound return = 1.20 * 0.90 - 1 = +8.0%
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
      // Buy price: 100 EUR, Current price: 90 EUR (-10.0% asset return)
      // Buy FX: 4.00 PLN/EUR, Current FX: 4.40 PLN/EUR (+10.0% currency buffer)
      // Total compound return = 0.90 * 1.10 - 1 = -1.0%
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
});
