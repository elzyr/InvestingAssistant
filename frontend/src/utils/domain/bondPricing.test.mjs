import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { calculateBondCurrentPrice } from './bondPricing.ts';

describe('domain/bondPricing', () => {
  it('returns 100 when purchaseDate is missing', () => {
    assert.strictEqual(calculateBondCurrentPrice({}), 100);
  });

  it('calculates fixed rate bond price after 1 year', () => {
    const p = calculateBondCurrentPrice({
      purchaseDate: '2025-01-01',
      targetDate: '2026-01-01',
      bondRate: 5.0,
      seriesName: 'TOS',
    });
    assert.ok(Math.abs(p - 105.0) < 0.1);
  });

  it('stops accumulating past termMonths maturity', () => {
    const atMaturity = calculateBondCurrentPrice({
      purchaseDate: '2020-01-01',
      targetDate: '2023-01-01',
      bondRate: 4.0,
      termMonths: 36,
      seriesName: 'TOS',
    });
    const pastMaturity = calculateBondCurrentPrice({
      purchaseDate: '2020-01-01',
      targetDate: '2025-01-01',
      bondRate: 4.0,
      termMonths: 36,
      seriesName: 'TOS',
    });
    assert.strictEqual(atMaturity, pastMaturity);
  });
});
