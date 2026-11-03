export interface TwrSubPeriod {
  startValue: number;
  endValue: number;
  netCashFlow?: number;
}

export function calculateTwr(subPeriods: TwrSubPeriod[]): number {
  if (!subPeriods || subPeriods.length === 0) {
    return 0.0;
  }

  let compoundProduct = 1.0;
  let hasValidPeriod = false;

  for (const period of subPeriods) {
    const start = period.startValue;
    const end = period.endValue;
    const flow = period.netCashFlow ?? 0;

    const base = start > 0 ? start : (start + flow);
    if (base <= 0) {
      continue;
    }

    const subReturn = (end - (start > 0 ? flow : 0)) / base;
    if (Number.isFinite(subReturn) && subReturn >= 0) {
      compoundProduct *= subReturn;
      hasValidPeriod = true;
    }
  }

  if (!hasValidPeriod) {
    return 0.0;
  }

  const result = compoundProduct - 1.0;
  return Math.abs(result) < 1e-12 ? 0.0 : result;
}
