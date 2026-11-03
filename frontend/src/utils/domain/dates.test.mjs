import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { parseDateMs, standardizeDate } from './dates.ts';

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
});
