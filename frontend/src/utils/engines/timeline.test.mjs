import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { getBondFamily, isBondItem } from '../domain/assets.ts';
import { calculateBondCurrentPrice } from '../domain/bondPricing.ts';
import { generateUnifiedPortfolioTimeline } from './timeline/index.ts';
import { generateRealAnnualSummary } from './reports.ts';

const assertCloseTo = (actual, expected, tolerance = 1e-4, message) => {
  assert.ok(typeof actual === 'number' && !Number.isNaN(actual), `${message || 'Expected number'}: received ${actual}`);
  const diff = Math.abs(actual - expected);
  assert.ok(diff <= tolerance, `${message || 'Assertion failed'}: expected ${actual} to be close to ${expected} (diff: ${diff}, tolerance: ${tolerance})`);
};

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

    it('does not inflate non-compounding bond unit price with past paid coupons (CALC-08)', () => {
      const priceAt2Years = calculateBondCurrentPrice({
        purchaseDate: '2022-01-01',
        bondRate: 7.0,
        bondMargin: 1.5,
        cpi: 3.0,
        targetDate: '2024-01-01',
        seriesName: 'COI0126',
      });
      assertCloseTo(priceAt2Years, 100.0, 1e-2);

      const priceAt18Months = calculateBondCurrentPrice({
        purchaseDate: '2022-01-01',
        bondRate: 7.0,
        bondMargin: 1.5,
        cpi: 3.0,
        targetDate: '2023-07-02',
        seriesName: 'COI0126',
      });
      assertCloseTo(priceAt18Months, 102.24, 1e-2);

      const rorAt18Months = calculateBondCurrentPrice({
        purchaseDate: '2024-01-01',
        bondRate: 6.0,
        targetDate: '2025-07-02',
        seriesName: 'ROR0125',
      });
      assertCloseTo(rorAt18Months, 103.0, 1e-2);
    });

    it('does not treat equity or ETF in PLN with price 100 as a bond (CALC-06)', () => {
      assert.strictEqual(
        isBondItem({ currency: 'PLN', price: 100 }),
        false
      );
      assert.strictEqual(
        isBondItem({ currency: 'PLN', averageBuyPrice: 100 }),
        false
      );
      assert.strictEqual(
        isBondItem(
          { currency: 'PLN', price: 100 },
          { id: 'inst-pko', symbol: 'PKO.WA', currency: 'PLN' }
        ),
        false
      );
    });

    it('evaluates multi-lot bond holdings with per-lot interest from actual execution date (CALC-03)', () => {
      const t1 = '2024-01-01T00:00:00.000Z';
      const t2 = '2024-07-01T00:00:00.000Z';
      const transactions = [
        {
          id: 'tx-edo-1',
          instrumentId: 'inst-edo',
          seriesName: 'EDO0134',
          bondRate: 10.0,
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: t1,
          baseAmount: 1000,
        },
        {
          id: 'tx-edo-2',
          instrumentId: 'inst-edo',
          seriesName: 'EDO0134',
          bondRate: 10.0,
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: t2,
          baseAmount: 1000,
        },
      ];
      const positions = [
        {
          id: 'pos-edo',
          instrumentId: 'inst-edo',
          seriesName: 'EDO0134',
          quantity: 20,
          averageBuyPrice: 100,
          totalCostBase: 2000,
          currency: 'PLN',
          isBond: true,
          bondRate: 10.0,
          purchaseDate: t1,
        },
      ];
      const instruments = {
        'inst-edo': { id: 'inst-edo', symbol: 'EDO0134', type: 'BOND', currency: 'PLN' },
      };

      const points = generateUnifiedPortfolioTimeline({
        transactions,
        positions,
        instruments,
        totalPortfolioValue: 2000,
        totalPortfolioCost: 2000,
        taxMode: 'BRUTTO',
        timeframe: 'MAX',
        projectionHorizon: 'OFF',
        cpi: 3.6,
        rates: { PLN: 1 },
        baseCurrency: 'PLN',
        instrumentPrices: {},
      });

      const todayPoint = points.find((p) => !p.isFuture && p.timestamp >= Date.now() - 86400000);
      assert.ok(todayPoint, 'Timeline point for today must exist');
      const edo = todayPoint.contributors.find((c) => c.key === 'EDO');
      assert.ok(edo, 'EDO contributor must exist');
      assertCloseTo(edo.valuation, 2563, 10);
    });

    it('calculates real returns via Fisher equation instead of naive subtraction (CALC-05)', () => {
      const transactions = [
        {
          id: 'tx-1',
          instrumentId: 'inst-1',
          transactionType: 'BUY',
          quantity: 10,
          price: 100,
          currency: 'PLN',
          executedAt: '2025-01-01T10:00:00Z',
          baseAmount: 1000,
        },
      ];
      const instruments = {
        'inst-1': { id: 'inst-1', symbol: 'TEST', type: 'STOCK', currency: 'PLN' },
      };
      const quotes = {
        TEST: { symbol: 'TEST', price: 140, currency: 'PLN' },
      };

      const summary = generateRealAnnualSummary({
        transactions,
        instruments,
        quotes,
        rates: { PLN: 1 },
        cpi: 5.0,
        monthlyCpi: { 0: 5.0 },
        baseCurrency: 'PLN',
        isIkeAccount: true,
        year: 2025,
      });

      const hurdle = Math.pow(1.05, 30.9996 / 365);
      const expectedRealPct = (1.4 / hurdle - 1) * 100;
      assertCloseTo(summary.records[0].realProfitPct, expectedRealPct, 0.05);
      const recorded = summary.records.filter((r) => r.status === 'RECORDED');
      assertCloseTo(summary.ytdRealReturnPct, recorded[recorded.length - 1].realProfitPct, 0.01);
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

    it('strictly balances contributor amounts and percentages to total profit and 100% without rounding drift (CALC-15)', () => {
      const pastDate = new Date(Date.now() - 30 * 24 * 3600 * 1000).toISOString();
      const assets = ['EQ1', 'EQ2', 'EQ3'];
      const transactions = assets.map((id) => ({
        id: `tx-${id}`,
        instrumentId: `inst-${id}`,
        transactionType: 'BUY',
        quantity: 10,
        price: 100,
        currency: 'PLN',
        executedAt: pastDate,
        baseAmount: 1000,
      }));
      const positions = assets.map((id) => ({
        id: `pos-${id}`,
        instrumentId: `inst-${id}`,
        quantity: 10,
        averageBuyPrice: 100,
        totalCostBase: 1000,
        currency: 'PLN',
        isBond: false,
        purchaseDate: pastDate,
      }));
      const instruments = Object.fromEntries(
        assets.map((id) => [
          `inst-${id}`,
          { id: `inst-${id}`, symbol: id, type: 'STOCK', currency: 'PLN' },
        ])
      );
      const instrumentPrices = Object.fromEntries(
        assets.map((id) => [`inst-${id}`, 110])
      );

      const points = generateUnifiedPortfolioTimeline({
        transactions,
        positions,
        instruments,
        totalPortfolioValue: 3300,
        totalPortfolioCost: 3000,
        taxMode: 'BRUTTO',
        timeframe: 'MAX',
        projectionHorizon: 'OFF',
        cpi: 0,
        rates: { PLN: 1 },
        baseCurrency: 'PLN',
        instrumentPrices,
      });

      const latestPoint = points[points.length - 1];
      assert.ok(Array.isArray(latestPoint.contributors), 'Point contributors must be an array');
      assert.strictEqual(latestPoint.contributors.length, 3);
      assert.strictEqual(latestPoint.profit, 300);

      const sumProfit = latestPoint.contributors.reduce((acc, c) => acc + c.profit, 0);
      assert.strictEqual(sumProfit, latestPoint.profit, 'Sum of contributor profits must strictly equal total profit');

      const sumShare = Number(latestPoint.contributors.reduce((acc, c) => acc + c.sharePct, 0).toFixed(1));
      assert.strictEqual(sumShare, 100.0, 'Sum of contributor sharePct must strictly equal 100.0% without rounding drift');

      const sumReturn = Number(latestPoint.contributors.reduce((acc, c) => acc + c.returnContributionPct, 0).toFixed(2));
      assert.strictEqual(sumReturn, Number(latestPoint.returnPct.toFixed(2)), 'Sum of returnContributionPct must strictly equal returnPct');
    });
  });

