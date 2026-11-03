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
  cpi,
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
    if (bondRate == null) {
      return 100;
    }
    const r1 = bondRate / 100;
    const m = (bondMargin || 0) / 100;
    const k = Math.floor(days / 365);
    const curDays = days % 365;

    if (k === 0) {
      return 100 * (1 + r1 * (curDays / 365));
    }

    if (cpi == null) {
      return 100 * (1 + r1);
    }
    const cpiRate = cpi / 100;
    const laterRate = Math.max(0, cpiRate) + m;

    if (isNonCompounding) {
      return 100 * (1 + laterRate * (curDays / 365));
    }

    const fullYearsCompounded = 100 * (1 + r1) * Math.pow(1 + laterRate, k - 1);
    return fullYearsCompounded * (1 + laterRate * (curDays / 365));
  }

  if (bondRate == null) {
    return 100;
  }
  const r = bondRate / 100;
  if (isNonCompounding) {
    const curDays = days % 365;
    return 100 * (1 + r * (curDays / 365));
  }

  const k = Math.floor(days / 365);
  const curDays = days % 365;
  return 100 * Math.pow(1 + r, k) * (1 + r * (curDays / 365));
}
