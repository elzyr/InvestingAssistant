export const BELKA_RATE = 0.19;

export function computeBelka(profit: number, exempt = false): number {
  return exempt || profit <= 0 ? 0 : profit * BELKA_RATE;
}
