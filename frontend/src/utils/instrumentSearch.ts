import type { InstrumentResponse } from '../types/instrument.ts';

export interface PartitionInstrumentsParams {
  catalogInstruments?: InstrumentResponse[];
  ownedInstruments?: InstrumentResponse[];
  ownedInstrumentIds?: Set<string>;
  ownedSymbols?: Set<string>;
  query?: string;
}

export interface PartitionedInstruments {
  yourInstruments: InstrumentResponse[];
  allInstruments: InstrumentResponse[];
}

export function partitionInstruments({
  catalogInstruments = [],
  ownedInstruments = [],
  ownedInstrumentIds = new Set(),
  ownedSymbols = new Set(),
  query = '',
}: PartitionInstrumentsParams): PartitionedInstruments {
  const cleanQuery = query.trim().toLowerCase();
  const seenIds = new Set<string>();
  const seenSymbols = new Set<string>();

  const yourInstruments: InstrumentResponse[] = [];
  const allInstruments: InstrumentResponse[] = [];

  const combined = [...(ownedInstruments || []), ...(catalogInstruments || [])];

  for (const inst of combined) {
    if (!inst) continue;
    const hasId = Boolean(inst.id && seenIds.has(inst.id));
    const hasSymbol = Boolean(inst.symbol && seenSymbols.has(inst.symbol));
    if (hasId || hasSymbol) continue;

    if (inst.id) seenIds.add(inst.id);
    if (inst.symbol) seenSymbols.add(inst.symbol);

    if (cleanQuery) {
      const match =
        (inst.symbol || '').toLowerCase().includes(cleanQuery) ||
        (inst.name || '').toLowerCase().includes(cleanQuery) ||
        (inst.type || '').toLowerCase().includes(cleanQuery);
      if (!match) continue;
    }

    const isOwned =
      Boolean(inst.id && ownedInstrumentIds.has(inst.id)) ||
      Boolean(inst.symbol && ownedSymbols.has(inst.symbol));

    if (isOwned) {
      yourInstruments.push(inst);
    } else {
      allInstruments.push(inst);
    }
  }

  yourInstruments.sort((a, b) => (a.symbol || '').localeCompare(b.symbol || ''));
  allInstruments.sort((a, b) => (a.symbol || '').localeCompare(b.symbol || ''));

  return { yourInstruments, allInstruments };
}
