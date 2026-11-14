import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  formatNumberWithSpaces,
  formatCurrency,
  formatPercent,
  formatSignedFixedPercent,
  formatSignedNumberWithSpaces,
  formatSignedPercentPoints,
  getValueColorClass,
} from './reportsFormatters.ts';

describe('reportsFormatters', () => {
  it('never formats near-zero negative numbers with -0 or +-0', () => {
    assert.strictEqual(formatNumberWithSpaces(-0.4), '0');
    assert.strictEqual(formatSignedNumberWithSpaces(-0.4), '0');
    assert.strictEqual(formatSignedNumberWithSpaces(0), '0');
    assert.strictEqual(formatSignedFixedPercent(-0.001, 2), '+0.00%');
    assert.strictEqual(formatSignedFixedPercent(-0.04, 1, false), '0.0%');

    const pln = formatCurrency(-0.001, true, 'PLN');
    assert.ok(!pln.includes('-0'), `Unexpected negative zero in ${pln}`);

    const pct2 = formatPercent(-0.001, true);
    assert.ok(!pct2.includes('-0'), `Unexpected negative zero in ${pct2}`);

    const pct1 = formatPercent(-0.04, true, 1);
    assert.ok(!pct1.includes('-0'), `Unexpected negative zero in ${pct1}`);
  });

  it('formats signed percentage points and suppresses negative color on near-zero values', () => {
    assert.strictEqual(formatSignedPercentPoints(1.234, 2), '+1.23 pp');
    assert.strictEqual(formatSignedPercentPoints(-0.001, 2), '+0.00 pp');
    assert.strictEqual(getValueColorClass(-0.001), 'text-zinc-400');
    assert.strictEqual(getValueColorClass(-0.5), 'text-rose-400');
    assert.strictEqual(getValueColorClass(0.5), 'text-emerald-400');
  });
});
