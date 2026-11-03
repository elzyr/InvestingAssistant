export interface BondPriceParams {
  purchaseDate?: string | Date | number;
  bondRate?: number | null;
  bondMargin?: number | null;
  cpi?: number | null;
  targetDate?: string | Date | number;
  seriesName?: string | null;
  termMonths?: number | null;
}

export function calculateBondCurrentPrice({
  purchaseDate,
  bondRate,
  bondMargin,
  cpi = 3.6,
  targetDate,
  seriesName,
  termMonths,
}: BondPriceParams): number {
  if (!purchaseDate) return 100;
  const purchaseMs = new Date(purchaseDate).getTime();
  const currentMs = targetDate !== undefined ? new Date(targetDate).getTime() : Date.now();

  let effectiveMs = currentMs;
  if (termMonths != null && termMonths > 0) {
    const d = new Date(purchaseDate);
    const matDate = new Date(d);
    matDate.setMonth(matDate.getMonth() + termMonths);
    if (effectiveMs > matDate.getTime()) {
      effectiveMs = matDate.getTime();
    }
  }

  const days = Math.max(0, Math.floor((effectiveMs - purchaseMs) / (1000 * 60 * 60 * 24)));
  const prefix = seriesName ? seriesName.trim().toUpperCase() : '';
  const isNonCompounding =
    prefix.startsWith('COI') ||
    prefix.startsWith('ROR') ||
    prefix.startsWith('DOR') ||
    prefix.startsWith('OTS');

  const isInflation = bondMargin != null && bondMargin > 0;

  if (isInflation) {
    const r1 = (bondRate ?? 6.75) / 100;
    const m = (bondMargin ?? 2.0) / 100;
    const cpiRate = (cpi ?? 3.6) / 100;
    const laterRate = Math.max(0, cpiRate) + m;
    const k = Math.floor(days / 365);
    const curDays = days % 365;

    if (isNonCompounding) {
      const activeRate = k === 0 ? r1 : laterRate;
      return 100 * (1 + activeRate * (curDays / 365));
    }

    if (k === 0) {
      return 100 * (1 + r1 * (curDays / 365));
    }
    const fullYearsCompounded = 100 * (1 + r1) * Math.pow(1 + laterRate, k - 1);
    return fullYearsCompounded * (1 + laterRate * (curDays / 365));
  }

  const r = (bondRate ?? 6.5) / 100;
  if (isNonCompounding) {
    const curDays = days % 365;
    return 100 * (1 + r * (curDays / 365));
  }

  const k = Math.floor(days / 365);
  const curDays = days % 365;
  return 100 * Math.pow(1 + r, k) * (1 + r * (curDays / 365));
}
