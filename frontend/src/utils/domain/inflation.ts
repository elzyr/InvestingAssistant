import { MS_PER_DAY } from './dates.ts';

export interface InflationCostLot {
  cost: number;
  timeMs: number;
}

export function compoundHurdle(
  lots: InflationCostLot[],
  cpiPercent: number,
  atMs: number
): number {
  const normalizedCpi = cpiPercent / 100;
  let totalHurdle = 0;
  for (const lot of lots) {
    if (!Number.isFinite(lot.cost) || Number.isNaN(lot.timeMs)) continue;
    const deltaYears = Math.max(0, (atMs - lot.timeMs) / (365 * MS_PER_DAY));
    totalHurdle += lot.cost * Math.pow(1 + normalizedCpi, deltaYears);
  }
  return totalHurdle;
}
