import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  resolveTxFx,
  resolveTxCost,
  getTransactionCashFlow,
  sumDividends,
  mapDividendsByInstrument,
} from './transactions.ts';

describe('domain/transactions', () => {
  const rates = { EUR: 4.5, USD: 4.0, PLN: 1.0 };

  it('resolves base currency fx as 1', () => {
    assert.strictEqual(resolveTxFx({ currency: 'PLN' }, 'PLN', rates), 1);
  });

  it('respects fxPriority differences', () => {
    const tx = { currency: 'EUR', exchangeRateToBase: 4.2 };
    assert.strictEqual(resolveTxFx(tx, 'PLN', rates, 'current'), 4.5);
    assert.strictEqual(resolveTxFx(tx, 'PLN', rates, 'transaction'), 4.2);
  });

  it('calculates BUY and SELL cost correctly', () => {
    const buyTx = { transactionType: 'BUY', quantity: 10, price: 100, commission: 5 };
    assert.strictEqual(resolveTxCost(buyTx, 1.0), 1005);

    const sellTx = { transactionType: 'SELL', quantity: 10, price: 100, commission: 5 };
    assert.strictEqual(resolveTxCost(sellTx, 1.0), 995);

    const overrideTx = { baseAmount: 1234, transactionType: 'BUY', quantity: 10, price: 100 };
    assert.strictEqual(resolveTxCost(overrideTx, 1.0), 1234);
  });

  it('computes transaction cash flows', () => {
    const buyFlow = getTransactionCashFlow(
      { transactionType: 'BUY', quantity: 2, price: 50, currency: 'PLN', executedAt: '2025-01-01' },
      'PLN',
      rates
    );
    assert.strictEqual(buyFlow.amount, 100);
    assert.strictEqual(buyFlow.isOutflow, true);

    const divFlow = getTransactionCashFlow(
      { transactionType: 'DIVIDEND', quantity: 10, price: 2.5, currency: 'PLN', executedAt: '2025-06-01' },
      'PLN',
      rates
    );
    assert.strictEqual(divFlow.amount, 25);
    assert.strictEqual(divFlow.isOutflow, false);
  });

  it('sums and maps dividends correctly', () => {
    const txs = [
      { id: '1', instrumentId: 'instA', transactionType: 'DIVIDEND', quantity: 10, price: 2, currency: 'PLN', executedAt: '2025-01-01' },
      { id: '2', instrumentId: 'instA', transactionType: 'DIVIDEND', quantity: 5, price: 2, currency: 'PLN', executedAt: '2025-02-01' },
      { id: '3', instrumentId: 'instB', transactionType: 'DIVIDEND', quantity: 1, price: 50, currency: 'PLN', executedAt: '2025-03-01' },
      { id: '4', instrumentId: 'instB', transactionType: 'BUY', quantity: 10, price: 100, currency: 'PLN', executedAt: '2025-01-01' },
    ];
    assert.strictEqual(sumDividends(txs, 'PLN', rates), 80);
    const map = mapDividendsByInstrument(txs, 'PLN', rates);
    assert.strictEqual(map.get('instA'), 30);
    assert.strictEqual(map.get('instB'), 50);
  });
});
