import type { AccountType } from '../../types/portfolio.ts';

export const BELKA_RATE = 0.19;

export function computeBelka(profit: number, exempt = false): number {
  return exempt || profit <= 0 ? 0 : profit * BELKA_RATE;
}

export function isIkePortfolio(portfolio?: { name?: string; accountType?: AccountType } | null): boolean {
  if (!portfolio) return false;
  if (portfolio.accountType === 'IKE' || portfolio.accountType === 'IKZE') {
    return true;
  }
  if (portfolio.accountType === 'STANDARD') {
    return false;
  }
  return Boolean(portfolio.name?.toLowerCase().includes('ike'));
}
