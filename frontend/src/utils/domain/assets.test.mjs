import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  BOND_FAMILY_SPECS,
  isBondItem,
  isInflationBondFamily,
  parseBondSeriesMetadata,
} from './assets.ts';

describe('domain/assets', () => {
  it('detects bond items vs equities', () => {
    assert.strictEqual(isBondItem({ bondRate: 5.5 }), true);
    assert.strictEqual(isBondItem({ seriesName: 'EDO' }), true);
    assert.strictEqual(isBondItem({}, { type: 'BOND', symbol: 'PL_BOND_1' }), true);
    assert.strictEqual(isBondItem({ bondRate: 5.5 }, { type: 'ETF', symbol: 'VWCE.DE' }), false);
    assert.strictEqual(isBondItem({}, { type: 'STOCK', symbol: 'AAPL' }), false);
  });

  it('parses bond series code metadata for fixed and inflation bonds', () => {
    assert.deepStrictEqual(parseBondSeriesMetadata('edo0534'), {
      tenure: 120,
      interestType: 'INFLATION',
      issueDate: '2024-05-01',
    });
    assert.deepStrictEqual(parseBondSeriesMetadata('TOS0127'), {
      tenure: 36,
      interestType: 'FIXED',
      issueDate: '2024-01-01',
    });
    assert.deepStrictEqual(parseBondSeriesMetadata('ROS0632'), {
      tenure: 72,
      interestType: 'INFLATION',
      issueDate: '2026-06-01',
    });
    assert.deepStrictEqual(parseBondSeriesMetadata('ROD0638'), {
      tenure: 144,
      interestType: 'INFLATION',
      issueDate: '2026-06-01',
    });
    assert.deepStrictEqual(parseBondSeriesMetadata('OTS0325'), {
      tenure: 3,
      interestType: 'FIXED',
      issueDate: '2024-12-01',
    });
    assert.deepStrictEqual(parseBondSeriesMetadata('OTS0625'), {
      tenure: 3,
      interestType: 'FIXED',
      issueDate: '2025-03-01',
    });
    assert.strictEqual(parseBondSeriesMetadata('INVALID'), null);
    assert.strictEqual(parseBondSeriesMetadata('XYZ0128'), null);
    assert.strictEqual(BOND_FAMILY_SPECS.length, 8);
    assert.strictEqual(isInflationBondFamily('edo'), true);
    assert.strictEqual(isInflationBondFamily('TOS'), false);
    assert.strictEqual(isInflationBondFamily(null), false);
    assert.strictEqual(
      isBondItem({ seriesName: 'ROS0132' }, { id: '1', symbol: 'CDR.WA', name: 'CD Projekt', type: 'STOCK', currency: 'PLN' }),
      false
    );
    assert.strictEqual(
      isBondItem({}, { id: '2', symbol: 'PL_ROS', name: 'ROS', type: 'BOND', currency: 'PLN' }),
      true
    );
  });
});
