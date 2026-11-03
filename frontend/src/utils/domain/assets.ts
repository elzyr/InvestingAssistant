import type { InstrumentResponse } from '../../types/instrument.ts';

export function getBondFamily(
  seriesOrSymbol?: string | null,
  termMonths?: number | null,
  bondMargin?: number | null
): string {
  if (seriesOrSymbol) {
    const s = seriesOrSymbol.toUpperCase();
    for (const prefix of ['TOS', 'COI', 'ROR', 'DOR', 'OTS', 'EDO']) {
      if (s.includes(prefix)) {
        return prefix;
      }
    }
  }
  if (termMonths === 36) return 'TOS';
  if (termMonths === 48) return 'COI';
  if (termMonths === 12) return 'ROR';
  if (termMonths === 24) return 'DOR';
  if (termMonths === 3) return 'OTS';
  if (termMonths === 120) return 'EDO';
  if (bondMargin != null && bondMargin > 0) return 'EDO';
  if (bondMargin == null || bondMargin === 0) return 'TOS';
  if (seriesOrSymbol && seriesOrSymbol.toUpperCase().startsWith('PL_BOND')) return 'TOS';
  return 'EDO';
}

export type EtfCategory = 'SP500' | 'ALL_WORLD' | 'DEVELOPED' | 'EMERGING' | 'OTHER';

export interface EtfProfile {
  symbol: string;
  name: string;
  ter: number;
  whtDrag: number;
  volatility: number;
  category: EtfCategory;
}

export function isBondInstrument(inst?: { type?: string; symbol?: string } | null): boolean {
  if (!inst) return false;
  return inst.type === 'BOND' || Boolean(inst.symbol?.startsWith('PL_BOND'));
}

export function isBondItem(
  item: {
    bondRate?: number | null;
    seriesName?: string | null;
    currency?: string;
    price?: number;
    averageBuyPrice?: number;
  },
  inst?: InstrumentResponse
): boolean {
  if (inst?.type === 'ETF' || inst?.type === 'STOCK') {
    return false;
  }
  return Boolean(
    item.bondRate != null ||
      (item.seriesName && item.seriesName.length >= 3) ||
      (inst &&
        (inst.type === 'BOND' ||
          inst.symbol.startsWith('PL_BOND') ||
          inst.symbol.includes('EDO') ||
          inst.symbol.includes('TOS') ||
          inst.symbol.includes('COI') ||
          inst.symbol.includes('ROR') ||
          inst.symbol.includes('DOR') ||
          inst.symbol.includes('OTS')))
  );
}

export function detectEtfProfile(symbol: string, name: string): EtfProfile {
  const sym = (symbol || '').toUpperCase();
  const nm = (name || '').toUpperCase();

  if (
    sym.includes('SXR8') ||
    sym.includes('CSSPX') ||
    sym.includes('VUAA') ||
    sym.includes('VUSA') ||
    sym.includes('SPY') ||
    sym.includes('VOO') ||
    nm.includes('S&P 500') ||
    nm.includes('SP 500')
  ) {
    return { symbol, name, ter: 0.07, whtDrag: 0.23, volatility: 15.5, category: 'SP500' };
  }

  if (
    sym.includes('VWCE') ||
    sym.includes('VWRA') ||
    sym.includes('ISAC') ||
    sym.includes('SSAC') ||
    nm.includes('ALL-WORLD') ||
    nm.includes('ALL WORLD') ||
    nm.includes('ACWI')
  ) {
    return { symbol, name, ter: 0.22, whtDrag: 0.20, volatility: 14.8, category: 'ALL_WORLD' };
  }

  if (
    sym.includes('EUNL') ||
    sym.includes('IWDA') ||
    sym.includes('SWDA') ||
    sym.includes('XDWD') ||
    nm.includes('MSCI WORLD') ||
    nm.includes('DEVELOPED')
  ) {
    return { symbol, name, ter: 0.20, whtDrag: 0.22, volatility: 14.6, category: 'DEVELOPED' };
  }

  if (
    sym.includes('AMEM') ||
    sym.includes('EIMI') ||
    sym.includes('EMIM') ||
    sym.includes('IS3N') ||
    sym.includes('VFEM') ||
    nm.includes('EMERGING')
  ) {
    return { symbol, name, ter: 0.18, whtDrag: 0.25, volatility: 18.5, category: 'EMERGING' };
  }

  return { symbol, name, ter: 0.20, whtDrag: 0.25, volatility: 15.0, category: 'OTHER' };
}

export const CORRELATION_MATRIX: Record<EtfCategory, Record<EtfCategory, number>> = {
  SP500: { SP500: 1.00, ALL_WORLD: 0.96, DEVELOPED: 0.97, EMERGING: 0.72, OTHER: 0.85 },
  ALL_WORLD: { SP500: 0.96, ALL_WORLD: 1.00, DEVELOPED: 0.98, EMERGING: 0.82, OTHER: 0.88 },
  DEVELOPED: { SP500: 0.97, ALL_WORLD: 0.98, DEVELOPED: 1.00, EMERGING: 0.74, OTHER: 0.86 },
  EMERGING: { SP500: 0.72, ALL_WORLD: 0.82, DEVELOPED: 0.74, EMERGING: 1.00, OTHER: 0.75 },
  OTHER: { SP500: 0.85, ALL_WORLD: 0.88, DEVELOPED: 0.86, EMERGING: 0.75, OTHER: 1.00 },
};
