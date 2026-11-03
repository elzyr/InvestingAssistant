import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { BELKA_RATE, computeBelka } from './tax.ts';

describe('domain/tax', () => {
  it('computes 19% Belka tax for positive profit', () => {
    assert.strictEqual(BELKA_RATE, 0.19);
    assert.strictEqual(computeBelka(1000), 190);
    assert.strictEqual(computeBelka(100), 19);
  });

  it('returns 0 for zero or negative profit', () => {
    assert.strictEqual(computeBelka(0), 0);
    assert.strictEqual(computeBelka(-500), 0);
  });

  it('returns 0 when exempt (e.g. IKE or taxMode BRUTTO)', () => {
    assert.strictEqual(computeBelka(1000, true), 0);
    assert.strictEqual(computeBelka(-500, true), 0);
  });
});
