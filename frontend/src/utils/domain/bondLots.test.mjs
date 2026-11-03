import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { reduceBondLotsFifo, valueBondLots } from './bondLots.ts';

describe('domain/bondLots', () => {
  it('reduces lots in FIFO order', () => {
    const lots = [
      { quantity: 10, executedAt: '2025-01-01', fx: 1 },
      { quantity: 20, executedAt: '2025-02-01', fx: 1 },
    ];
    reduceBondLotsFifo(lots, 5);
    assert.strictEqual(lots.length, 2);
    assert.strictEqual(lots[0].quantity, 5);

    reduceBondLotsFifo(lots, 10);
    assert.strictEqual(lots.length, 1);
    assert.strictEqual(lots[0].quantity, 15);

    reduceBondLotsFifo(lots, 20);
    assert.strictEqual(lots.length, 0);
  });

  it('values bond lots correctly', () => {
    const lots = [
      { quantity: 10, executedAt: '2025-01-01', bondRate: 5.0, fx: 1, seriesName: 'TOS' },
    ];
    const val = valueBondLots(lots, {
      cpi: 3.6,
      targetDate: '2026-01-01',
    });
    assert.ok(Math.abs(val - 1050) < 1.0);
  });
});
