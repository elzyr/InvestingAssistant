import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  formatValuationSummary,
  formatValuationPositions,
  formatTimelineChartPoints,
  formatAnnualReportSummary,
} from './valuationFormatters.ts';

const t = (key, vars = {}) => {
  const entries = Object.entries(vars).map(([k, v]) => `${k}=${v}`).join(',');
  return entries ? `${key}(${entries})` : key;
};

describe('valuationFormatters', () => {
  it('formats fallback summary when response is null', () => {
    const summary = formatValuationSummary(null, t);
    assert.strictEqual(summary.portfolioValueFormatted, '0 PLN');
    assert.strictEqual(summary.totalProfitAmountFormatted, '0 PLN');
    assert.strictEqual(summary.positionCount, 0);
    assert.strictEqual(summary.taxMode, 'NETTO');
  });

  it('converts backend xirrPct percentages into decimal fractions and formats NETTO Belka note', () => {
    const resp = {
      portfolioId: 'p1',
      baseCurrency: 'PLN',
      taxMode: 'NETTO',
      summary: {
        totalValue: 10961,
        grossTotalValue: 11065,
        dailyChange: 45,
        dailyChangePct: 0.41,
        totalInvested: 10000,
        grossProfit: 1200,
        realizedProfit: 0,
        dividendsTotal: 0,
        netProfit: 1096,
        netProfitPct: 10.96,
        taxPaid: 104,
        xirrPct: 12.92,
        isXirrFallback: false,
        realProfitAmount: 750,
        realReturnPct: 7.5,
        realXirrPct: 9.1,
        targetRealReturnPct: null,
        currentCpiPct: 2.5,
        positionsCount: 2,
      },
      positions: [],
      projectionSeed: {
        startTotalValue: 10961,
        startBondValue: 0,
        startEquityValue: 10961,
        startInvestedCapital: 10000,
        weightedEquityYieldPct: 8.0,
        weightedBondYieldPct: 0,
        currentCpiPct: 2.5,
        belkaTaxRate: 0.19,
        equityWeights: [],
      },
    };

    const summary = formatValuationSummary(resp, t);
    assert.strictEqual(summary.portfolioValueFormatted, '10 961 PLN');
    assert.strictEqual(summary.totalProfitAmountFormatted, '+1 096 PLN');
    assert.strictEqual(summary.taxNoteFormatted, 'dashboard.belkaTaxNote(amount=104 PLN)');
    assert.ok(summary.totalProfitPercentFormatted.includes('12.9%'));
  });

  it('maps bond and equity positions including fractional xirr conversion', () => {
    const resp = {
      portfolioId: 'p1',
      baseCurrency: 'PLN',
      taxMode: 'BRUTTO',
      summary: {
        totalValue: 2000,
        grossTotalValue: 2000,
        dailyChange: 0,
        dailyChangePct: 0,
        totalInvested: 1800,
        grossProfit: 200,
        realizedProfit: 0,
        dividendsTotal: 0,
        netProfit: 200,
        netProfitPct: 11.11,
        taxPaid: 0,
        xirrPct: null,
        isXirrFallback: true,
        realProfitAmount: null,
        realReturnPct: null,
        realXirrPct: null,
        targetRealReturnPct: null,
        currentCpiPct: 2.5,
        positionsCount: 2,
      },
      positions: [
        {
          id: 'pos-bond',
          symbol: 'EDO',
          name: 'EDO',
          category: 'BOND',
          bondFamily: 'EDO',
          bondRateMin: 6.5,
          bondRateMax: 6.5,
          quantity: 10,
          allocationPct: 50,
          currentPriceNative: 105.5,
          quoteCurrency: 'PLN',
          investedBase: 1000,
          valueBase: 1055,
          profitBase: 55,
          profitPct: 5.5,
          xirrPct: 6.2,
          isXirrFallback: false,
          isPriceUnavailable: false,
          isCpiUnavailable: true,
          fxDecomposition: null,
          subPositions: [
            {
              id: 'sub-bond',
              label: '2025',
              date: '2025-01-15',
              quantity: 10,
              txPrice: 100,
              txCurrency: 'PLN',
              currentPriceNative: 105.5,
              sharePercent: 100,
              investedBase: 1000,
              valueBase: 1055,
              profitBase: 55,
              profitPct: 5.5,
              xirrPct: 6.2,
              isXirrFallback: false,
              transactionId: 'tx-1',
              fxDecomposition: null,
            },
          ],
        },
        {
          id: 'pos-eq',
          symbol: 'SXR8.DE',
          name: 'iShares S&P 500',
          category: 'ETF',
          bondFamily: null,
          bondRateMin: null,
          bondRateMax: null,
          quantity: 2,
          allocationPct: 50,
          currentPriceNative: 500,
          quoteCurrency: 'EUR',
          investedBase: 800,
          valueBase: 945,
          profitBase: 145,
          profitPct: 18.125,
          xirrPct: 14.5,
          isXirrFallback: false,
          isPriceUnavailable: false,
          isCpiUnavailable: false,
          fxDecomposition: { marketReturnPct: 15.0, fxReturnPct: 2.7 },
          subPositions: [],
        },
      ],
      projectionSeed: {
        startTotalValue: 2000,
        startBondValue: 1055,
        startEquityValue: 945,
        startInvestedCapital: 1800,
        weightedEquityYieldPct: 8.0,
        weightedBondYieldPct: 6.5,
        currentCpiPct: 2.5,
        belkaTaxRate: 0.19,
        equityWeights: [],
      },
    };

    const summary = formatValuationSummary(resp, t);
    assert.strictEqual(summary.taxNoteFormatted, undefined);

    const positions = formatValuationPositions(resp, t, 'en-US');
    assert.strictEqual(positions.length, 2);
    assert.strictEqual(positions[0].type, 'BOND');
    assert.strictEqual(positions[0].isCpiUnavailable, true);
    assert.strictEqual(positions[1].isCpiUnavailable, false);
    assert.strictEqual(positions[0].ticker, 'dashboard.tenYear');
    assert.ok(Math.abs(positions[0].xirr - 0.062) < 1e-9);
    assert.strictEqual(positions[1].ticker, 'SXR8.DE');
    assert.strictEqual(positions[1].valueBase, 945);
    assert.strictEqual(positions[1].profitPct, 18.125);
    assert.deepStrictEqual(positions[1].fxDecomposition, { marketReturnPct: 15.0, fxReturnPct: 2.7 });
  });

  it('formats timeline chart points and contributors', () => {
    const timeline = {
      portfolioId: 'p1',
      baseCurrency: 'PLN',
      taxMode: 'NETTO',
      timeframe: 'MAX',
      points: [
        {
          date: '2026-01-15',
          invested: 1000,
          value: 1120.4,
          netProfit: 120.4,
          returnPct: 12.04,
          contributors: [
            {
              id: 'SXR8.DE',
              symbol: 'SXR8.DE',
              category: 'ETF',
              valuationBase: 1120.4,
              costBase: 1000,
              gainBase: 120.4,
              returnContributionPct: 12.04,
              sharePct: 100,
              isOther: false,
            },
          ],
        },
      ],
    };

    const points = formatTimelineChartPoints(timeline, t, 'pl-PL');
    assert.strictEqual(points.length, 1);
    assert.strictEqual(points[0].valuation, 1120);
    assert.strictEqual(points[0].contributors?.[0].label, 'SXR8.DE');
  });

  it('maps annual report statuses and category codes', () => {
    const report = {
      portfolioId: 'p1',
      baseCurrency: 'PLN',
      taxMode: 'NETTO',
      year: 2026,
      ytdPortfolioValue: 12000,
      ytdInvested: 10000,
      ytdProfitGross: 2000,
      ytdReturnGrossPct: 20,
      ytdProfitNet: 1620,
      ytdReturnNetPct: 16.2,
      ytdRealReturnPct: 13.0,
      ytdTwrPct: 15.5,
      avgCpi: 3.2,
      months: [
        {
          monthIndex: 0,
          portfolioValue: 12000,
          investedTotal: 10000,
          profitGross: 2000,
          returnGrossPct: 20,
          profitNet: 1620,
          returnNetPct: 16.2,
          profitNetMom: 200,
          returnNetMomPct: 2.0,
          cpiInflation: 3.2,
          realProfitPct: 13.0,
          status: 'RECORDED',
          breakdown: [
            {
              categoryCode: 'SP500',
              invested: 10000,
              currentValue: 12000,
              profitGross: 2000,
              profitNet: 1620,
              returnGrossPct: 20,
              returnNetPct: 16.2,
              sharePct: 100,
            },
          ],
        },
        {
          monthIndex: 11,
          portfolioValue: 0,
          investedTotal: 0,
          profitGross: 0,
          returnGrossPct: 0,
          profitNet: 0,
          returnNetPct: 0,
          profitNetMom: 0,
          returnNetMomPct: 0,
          cpiInflation: null,
          realProfitPct: null,
          status: 'FUTURE',
          breakdown: [],
        },
      ],
    };

    const summary = formatAnnualReportSummary(report, t, 'en-US');
    assert.strictEqual(summary.records[0].status, 'RECORDED');
    assert.strictEqual(summary.records[0].breakdown[0].category, 'S&P 500');
    assert.strictEqual(summary.records[1].status, 'FUTURE');
    assert.strictEqual(summary.ytdTwr, 15.5);
  });

  it('avoids -0.00% / +-0.00%, tracks sub-position isProfit accurately, and labels ROR/DOR/OTS/ROS/ROD', () => {
    const resp = {
      portfolioId: 'p-1',
      baseCurrency: 'PLN',
      taxMode: 'NETTO',
      summary: {
        totalValue: 1000,
        totalInvested: 1000,
        netProfit: -0.2,
        netProfitPct: -0.02,
        dailyChange: 1,
        dailyChangePct: -0.001,
        taxPaid: 0,
        positionsCount: 2,
      },
      positions: [
        {
          id: 'ros',
          symbol: 'ROS',
          name: 'ROS',
          category: 'BOND',
          bondFamily: 'ROS',
          bondRateMin: 5.5,
          bondRateMax: 5.5,
          quantity: 10,
          allocationPct: 50,
          valueBase: 1000,
          profitBase: -0.3,
          profitPct: -0.03,
          currentPriceNative: 100,
          quoteCurrency: 'PLN',
          subPositions: [
            {
              id: 's1',
              label: 'ROS0132',
              quantity: 10,
              investedBase: 1000.3,
              currentPriceNative: 100,
              valueBase: 1000,
              profitBase: -0.3,
              profitPct: -0.03,
              sharePercent: 100,
            },
          ],
        },
        {
          id: 'ror',
          symbol: 'ROR',
          name: 'ROR',
          category: 'BOND',
          bondFamily: 'ROR',
          quantity: 1,
          allocationPct: 50,
          valueBase: 100,
          profitBase: 0,
          profitPct: 0,
          currentPriceNative: 100,
          quoteCurrency: 'PLN',
          subPositions: [],
        },
      ],
    };

    const sum = formatValuationSummary(resp, t);
    assert.ok(!sum.dailyChangePercentFormatted.includes('+-0'), sum.dailyChangePercentFormatted);
    assert.ok(!sum.dailyChangePercentFormatted.includes('-0'), sum.dailyChangePercentFormatted);

    const positions = formatValuationPositions(resp, t, 'pl-PL');
    assert.strictEqual(positions[0].ticker, 'dashboard.sixYear');
    assert.strictEqual(positions[0].name, 'dashboard.inflationIndexed');
    assert.strictEqual(positions[0].subPositions[0].isProfit, false);
    assert.strictEqual(positions[1].ticker, 'dashboard.oneYear');
    assert.strictEqual(positions[1].name, 'dashboard.variableRate');
  });
});
