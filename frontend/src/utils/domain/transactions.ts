import type { TransactionResponse } from '../../types/portfolio.ts';

export function resolveTxFx(
  tx: { currency?: string; exchangeRateToBase?: number; baseAmount?: number; quantity?: number; price?: number },
  baseCurrency: string,
  rates: Record<string, number>,
  fxPriority: 'transaction' | 'current' = 'current'
): number {
  if (!tx.currency || tx.currency === baseCurrency) {
    return 1;
  }
  const rawProduct = (tx.quantity != null && tx.price != null) ? tx.quantity * tx.price : ((tx as any).amount ?? 0);
  const txFxFallback =
    tx.exchangeRateToBase ||
    (tx.baseAmount && rawProduct > 0 ? tx.baseAmount / rawProduct : undefined);

  if (fxPriority === 'transaction') {
    return txFxFallback || rates[tx.currency] || 1;
  }
  return (rates[tx.currency] || txFxFallback) || 1;
}

export function resolveTxCost(
  tx: { baseAmount?: number; transactionType?: string; quantity: number; price: number; commission?: number },
  fx: number
): number {
  if (tx.baseAmount != null) {
    return tx.baseAmount;
  }
  const commission = tx.commission || 0;
  const isSell = (tx.transactionType || (tx as any).type) === 'SELL';
  if (isSell) {
    return (tx.quantity * tx.price - commission) * fx;
  }
  return (tx.quantity * tx.price + commission) * fx;
}

export function getTransactionCashFlow(
  tx: TransactionResponse,
  baseCurrency: string,
  rates: Record<string, number>
): { amount: number; isOutflow: boolean } {
  const fx = resolveTxFx(tx, baseCurrency, rates, 'transaction');
  const txType = (tx.transactionType || (tx as any).type);
  if (txType === 'BUY') {
    const cost = resolveTxCost(tx, fx);
    return { amount: cost, isOutflow: true };
  } else if (txType === 'DIVIDEND') {
    const rawDiv = (tx as any).amount ?? ((tx.price ?? 0) * (tx.quantity || 1));
    const dividendAmount = tx.baseAmount ?? (rawDiv - (tx.commission || 0)) * fx;
    return { amount: dividendAmount, isOutflow: false };
  } else {
    const proceeds = resolveTxCost(tx, fx);
    return { amount: proceeds, isOutflow: false };
  }
}

export function mapDividendsByInstrument(
  transactions: TransactionResponse[],
  baseCurrency: string,
  rates: Record<string, number>
): Map<string, number> {
  const map = new Map<string, number>();
  for (const t of transactions) {
    if ((t.transactionType || (t as any).type) === 'DIVIDEND') {
      const flow = getTransactionCashFlow(t, baseCurrency, rates);
      map.set(t.instrumentId, (map.get(t.instrumentId) || 0) + flow.amount);
    }
  }
  return map;
}

export function sumDividends(
  transactions: TransactionResponse[],
  baseCurrency: string,
  rates: Record<string, number>
): number {
  let sum = 0;
  for (const t of transactions) {
    if ((t.transactionType || (t as any).type) === 'DIVIDEND') {
      sum += getTransactionCashFlow(t, baseCurrency, rates).amount;
    }
  }
  return sum;
}
