import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { computeAverageMonthlyDca, computeElapsedMonths } from './portfolioSimulation.ts';

describe('portfolioSimulation', () => {
  it('computes elapsed months with minimum 1-month floor and ignores invalid dates', () => {
    const now = Date.UTC(2026, 5, 1);
    assert.strictEqual(computeElapsedMonths([], now), 0);

    const sameDayTx = [
      { id: '1', executedAt: '2026-06-01T10:00:00', transactionType: 'BUY', quantity: 1, price: 100, commission: 0, currency: 'PLN', instrumentId: 'a', exchangeRate: 1 },
    ];
    assert.strictEqual(computeElapsedMonths(sameDayTx, now), 1);

    const txs = [
      { id: '1', executedAt: '2026-01-01T12:00:00', transactionType: 'BUY', quantity: 10, price: 100, commission: 0, currency: 'PLN', instrumentId: 'a', exchangeRate: 1 },
      { id: '2', executedAt: 'invalid-date', transactionType: 'BUY', quantity: 1, price: 100, commission: 0, currency: 'PLN', instrumentId: 'a', exchangeRate: 1 },
    ];
    const elapsed = computeElapsedMonths(txs, now);
    assert.ok(elapsed > 4.9 && elapsed < 5.05);
  });

  it('computes rounded monthly DCA and handles empty/zero inputs', () => {
    const now = Date.UTC(2026, 5, 1);
    const txs = [
      { id: '1', executedAt: '2026-01-01T12:00:00', transactionType: 'BUY', quantity: 10, price: 100, commission: 0, currency: 'PLN', instrumentId: 'a', exchangeRate: 1 },
      { id: '2', executedAt: 'invalid-date', transactionType: 'BUY', quantity: 1, price: 100, commission: 0, currency: 'PLN', instrumentId: 'a', exchangeRate: 1 },
    ];
    assert.strictEqual(computeAverageMonthlyDca(txs, 5000, now), 1000);
    assert.strictEqual(computeAverageMonthlyDca([], 5000, now), 0);
    assert.strictEqual(computeAverageMonthlyDca(txs, 0, now), 0);
  });
});
