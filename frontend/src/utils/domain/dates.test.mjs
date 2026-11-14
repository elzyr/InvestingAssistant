import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  formatUtcDate,
  normalizeChartDateStr,
  parseDateMs,
  standardizeDate,
} from './dates.ts';

describe('domain/dates', () => {
  it('parses timestamps, Date objects, and ISO strings', () => {
    assert.strictEqual(parseDateMs(1700000000000), 1700000000000);
    assert.strictEqual(parseDateMs(new Date(1700000000000)), 1700000000000);
    assert.ok(Number.isFinite(parseDateMs('2025-01-01')));
    assert.ok(Number.isNaN(parseDateMs(null)));
  });

  it('standardizes dates into YYYY-MM-DD', () => {
    assert.strictEqual(standardizeDate('2025-06-15T12:00:00Z'), '2025-06-15');
    assert.strictEqual(standardizeDate('2025-06-15'), '2025-06-15');
  });

  it('preserves exact day in normalizeChartDateStr and formatUtcDate', () => {
    assert.strictEqual(normalizeChartDateStr({ year: 2026, month: 4, day: 15 }), '2026-04-15');
    assert.strictEqual(normalizeChartDateStr(Date.UTC(2026, 3, 15) / 1000), '2026-04-15');
    assert.strictEqual(
      formatUtcDate('2026-01-01T00:00:00Z', 'en-US', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
      }),
      '01/01/2026'
    );
  });
});
