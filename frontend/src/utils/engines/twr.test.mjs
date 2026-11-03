import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { calculateTwr } from './twr.ts';
import { standardizeDate } from '../domain/dates.ts';

describe('performanceEngine and date standardization (CALC-14, CALC-16)', () => {
  it('standardizes dates from local and UTC ISO strings to YYYY-MM-DD without day drift (CALC-14)', async () => {
    const mod = await import('../domain/dates.ts');
    const normalizeDate = mod.standardizeDate || mod.normalizeDateToYMD || mod.parseDateToYMD || mod.formatDateToYMD;
    assert.ok(typeof normalizeDate === 'function', 'Date standardization function must be exported from dates.ts');

    assert.strictEqual(normalizeDate('2024-03-15T12:00:00'), '2024-03-15');
    assert.strictEqual(normalizeDate('2024-03-15T00:00:00Z'), '2024-03-15');
    assert.strictEqual(normalizeDate('2024-03-15'), '2024-03-15');
  });

  it('calculates TWR when initial cash flow or initial portfolio value is 0 without NaN or Infinity (CALC-16)', async () => {
    const { calculateTwr } = await import('./twr.ts').catch(() => ({ calculateTwr: undefined }));
    assert.ok(typeof calculateTwr === 'function', 'calculateTwr must be exported from twr.ts');

    const twrZeroInitial = calculateTwr([
      { startValue: 0, endValue: 0, netCashFlow: 0 },
    ]);
    assert.ok(typeof twrZeroInitial === 'number' && Number.isFinite(twrZeroInitial), 'TWR must be finite number');
    assert.strictEqual(twrZeroInitial, 0.0);

    const twrDepositOnly = calculateTwr([
      { startValue: 0, endValue: 1000, netCashFlow: 1000 },
    ]);
    assert.ok(typeof twrDepositOnly === 'number' && Number.isFinite(twrDepositOnly), 'Deposit only must not produce NaN or Infinity');
    assert.strictEqual(twrDepositOnly, 0.0);
  });
});
