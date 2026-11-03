import { calculateBondCurrentPrice } from './bondPricing.ts';

export interface BondLot {
  quantity: number;
  executedAt: string | Date | number;
  bondRate?: number | null;
  bondMargin?: number | null;
  seriesName?: string | null;
  termMonths?: number | null;
  fx: number;
}

export function reduceBondLotsFifo(lots: BondLot[], quantity: number): void {
  let toReduce = quantity;
  while (toReduce > 0 && lots.length > 0) {
    if (lots[0].quantity <= toReduce) {
      toReduce -= lots[0].quantity;
      lots.shift();
    } else {
      lots[0].quantity -= toReduce;
      toReduce = 0;
    }
  }
}

export function valueBondLots(
  lots: BondLot[],
  params: {
    cpi: number;
    targetDate: number | string | Date;
    fallbackFx?: number;
    fallback?: {
      bondRate?: number | null;
      bondMargin?: number | null;
      seriesName?: string | null;
      termMonths?: number | null;
    };
  }
): number {
  let sum = 0;
  for (const lot of lots) {
    const p = calculateBondCurrentPrice({
      purchaseDate: lot.executedAt,
      bondRate: lot.bondRate ?? params.fallback?.bondRate,
      bondMargin: lot.bondMargin ?? params.fallback?.bondMargin,
      cpi: params.cpi,
      targetDate: params.targetDate,
      seriesName: lot.seriesName ?? params.fallback?.seriesName,
      termMonths: lot.termMonths ?? params.fallback?.termMonths,
    });
    sum += lot.quantity * p * (lot.fx || params.fallbackFx || 1);
  }
  return sum;
}
