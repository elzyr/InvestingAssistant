import { MS_PER_DAY, parseDateMs } from './domain/dates.ts';
import type { TransactionResponse } from '../types/portfolio.ts';

const MS_PER_AVG_MONTH = MS_PER_DAY * 30.4375;

export function computeElapsedMonths(
  transactions: TransactionResponse[],
  nowMs: number
): number {
  const timestamps = transactions
    .map((tx) => parseDateMs(tx.executedAt))
    .filter((ms) => Number.isFinite(ms));
  if (timestamps.length === 0) return 0;

  const earliest = Math.min(...timestamps);
  const rawMonths = (nowMs - earliest) / MS_PER_AVG_MONTH;
  return Math.max(1, rawMonths);
}

export function computeAverageMonthlyDca(
  transactions: TransactionResponse[],
  totalInvested: number,
  nowMs: number
): number {
  if (transactions.length === 0 || totalInvested <= 0) return 0;
  const monthsElapsed = computeElapsedMonths(transactions, nowMs);
  if (monthsElapsed <= 0) return 0;
  const avgMonthly = totalInvested / monthsElapsed;
  return Math.max(0, Math.round(avgMonthly / 100) * 100);
}
