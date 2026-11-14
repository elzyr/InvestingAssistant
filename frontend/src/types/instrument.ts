import type { Currency } from './auth.ts';

export type InstrumentType = 'STOCK' | 'ETF' | 'BOND';

export interface InstrumentResponse {
  id: string;
  symbol: string;
  name: string;
  type: InstrumentType;
  currency: Currency;
  assetCategory?: string;
}
