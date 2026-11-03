import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  getBondFamily,
  isBondItem,
  detectEtfProfile,
  CORRELATION_MATRIX,
} from './assets.ts';

describe('domain/assets', () => {
  it('classifies bond series correctly', () => {
    assert.strictEqual(getBondFamily('EDO1030'), 'EDO');
    assert.strictEqual(getBondFamily('TOS0527'), 'TOS');
    assert.strictEqual(getBondFamily('COI0828'), 'COI');
    assert.strictEqual(getBondFamily('ROR0125'), 'ROR');
    assert.strictEqual(getBondFamily('DOR0226'), 'DOR');
    assert.strictEqual(getBondFamily('OTS0324'), 'OTS');
  });

  it('classifies unnamed bond positions by termMonths and margin', () => {
    assert.strictEqual(getBondFamily(null, 36, null), 'TOS');
    assert.strictEqual(getBondFamily(null, 48, null), 'COI');
    assert.strictEqual(getBondFamily(null, 12, null), 'ROR');
    assert.strictEqual(getBondFamily(null, 24, null), 'DOR');
    assert.strictEqual(getBondFamily(null, 3, null), 'OTS');
    assert.strictEqual(getBondFamily(null, 120, null), 'EDO');
    assert.strictEqual(getBondFamily(null, null, 2.0), 'EDO');
    assert.strictEqual(getBondFamily(null, null, 0), 'TOS');
  });

  it('detects bond items vs equities', () => {
    assert.strictEqual(isBondItem({ bondRate: 5.5 }), true);
    assert.strictEqual(isBondItem({ seriesName: 'EDO' }), true);
    assert.strictEqual(isBondItem({}, { type: 'BOND', symbol: 'PL_BOND_1' }), true);
    assert.strictEqual(isBondItem({ bondRate: 5.5 }, { type: 'ETF', symbol: 'VWCE.DE' }), false);
    assert.strictEqual(isBondItem({}, { type: 'STOCK', symbol: 'AAPL' }), false);
  });

  it('detects ETF profile categories and parameters', () => {
    const sp = detectEtfProfile('SXR8.DE', 'iShares S&P 500');
    assert.strictEqual(sp.category, 'SP500');
    assert.strictEqual(sp.ter, 0.07);

    const world = detectEtfProfile('VWCE.DE', 'Vanguard FTSE All-World');
    assert.strictEqual(world.category, 'ALL_WORLD');

    const dev = detectEtfProfile('EUNL.DE', 'iShares Core MSCI World');
    assert.strictEqual(dev.category, 'DEVELOPED');

    const em = detectEtfProfile('AMEM.DE', 'Amundi MSCI Emerging Markets');
    assert.strictEqual(em.category, 'EMERGING');
  });

  it('contains valid correlation matrix', () => {
    assert.strictEqual(CORRELATION_MATRIX.SP500.SP500, 1.0);
    assert.strictEqual(CORRELATION_MATRIX.SP500.ALL_WORLD, 0.96);
  });
});
