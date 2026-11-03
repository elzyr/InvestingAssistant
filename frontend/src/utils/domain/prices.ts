export function getHistoricalPrice(
  symbolPrices: Record<string, number> | undefined,
  sortedDates: string[] | undefined,
  targetDateStr: string,
  fallbackPrice: number
): number {
  if (!symbolPrices || !sortedDates || sortedDates.length === 0) return fallbackPrice;
  if (symbolPrices[targetDateStr] != null) return symbolPrices[targetDateStr];
  let low = 0;
  let high = sortedDates.length - 1;
  let bestDate: string | null = null;
  while (low <= high) {
    const mid = Math.floor((low + high) / 2);
    if (sortedDates[mid] <= targetDateStr) {
      bestDate = sortedDates[mid];
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  return bestDate && symbolPrices[bestDate] != null ? symbolPrices[bestDate] : fallbackPrice;
}
