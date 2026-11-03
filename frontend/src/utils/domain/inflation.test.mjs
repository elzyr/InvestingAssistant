import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { compoundHurdle } from './inflation.ts';

describe('domain/inflation', () => {
  it('returns sum of costs when CPI is 0', () => {
    const lots = [
      { cost: 1000, timeMs: 0 },
      { cost: 2000, timeMs: 1000 },
    ];
    assert.strictEqual(compoundHurdle(lots, 0, 1000000), 3000);
  });

  it('compounds correctly over 1 year at 5% CPI', () => {
    const oneYearMs = 365 * 86400000;
    const lots = [{ cost: 1000, timeMs: 0 }];
    const hurdle = compoundHurdle(lots, 5.0, oneYearMs);
    assert.ok(Math.abs(hurdle - 1050) < 0.001);
  });

  it('clamps future timeMs to deltaYears = 0 without negative exponent', () => {
    const lots = [{ cost: 1000, timeMs: 500000 }];
    const hurdle = compoundHurdle(lots, 5.0, 100000);
    assert.strictEqual(hurdle, 1000);
  });
});
