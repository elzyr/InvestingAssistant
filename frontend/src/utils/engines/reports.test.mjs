import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { generateRealAnnualSummary } from './reports.ts';

describe('monthly reports cpi fallback for latest month', () => {
  it('uses last known CPI and maintains positive real profit in months without GUS table entries', () => {
    const transactions = [
      {
        id: 'tx-1',
        instrumentId: 'inst-1',
        transactionType: 'BUY',
        quantity: 100,
        price: 100,
        currency: 'PLN',
        executedAt: '2026-01-10T10:00:00Z',
        baseAmount: 10000,
      },
    ];

    const instruments = {
      'inst-1': { id: 'inst-1', symbol: 'TEST.ETF', type: 'ETF', currency: 'PLN' },
    };

    const historicalPrices = {
      'TEST.ETF': {
        '2026-01-31': 101,
        '2026-02-28': 102,
        '2026-03-31': 103,
        '2026-04-30': 104,
        '2026-05-31': 105,
        '2026-06-30': 106,
        '2026-07-31': 107,
        '2026-08-31': 108,
        '2026-09-30': 109,
        '2026-10-31': 110,
        '2026-11-03': 111,
      },
    };

    const quotes = {
      'TEST.ETF': { symbol: 'TEST.ETF', price: 111, currency: 'PLN' },
    };

    const summary = generateRealAnnualSummary({
      transactions,
      instruments,
      year: 2026,
      baseCurrency: 'PLN',
      rates: { PLN: 1 },
      cpi: 4.5,
      isIkeAccount: false,
      quotes,
      historicalPrices,
      nowTimestamp: new Date('2026-11-03T18:00:00Z').getTime(),
    });

    const nov = summary.records.find((r) => r.monthIndex === 10);
    assert.ok(nov, 'November report record should be generated');
    assert.ok(nov.cpiInflation > 0, `CPI inflation for latest month must be > 0, got ${nov.cpiInflation}`);
    assert.ok(nov.realProfitPct > 0, `Real profit pct must be positive, got ${nov.realProfitPct}`);
  });
});
