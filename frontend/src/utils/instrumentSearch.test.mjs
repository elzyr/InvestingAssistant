import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { partitionInstruments } from './instrumentSearch.ts';

describe('partitionInstruments', () => {
  const inst1 = { id: '1', symbol: 'VWCE', name: 'Vanguard FTSE All-World', type: 'ETF', currency: 'EUR' };
  const inst2 = { id: '2', symbol: 'VUAA', name: 'Vanguard S&P 500', type: 'ETF', currency: 'USD' };
  const inst3 = { id: '3', symbol: 'EDO0134', name: 'Obligacje EDO 10-letnie', type: 'BOND', currency: 'PLN' };
  const inst4 = { id: '4', symbol: 'AAPL', name: 'Apple Inc.', type: 'STOCK', currency: 'USD' };

  it('partitions into yourInstruments and allInstruments when query is empty', () => {
    const catalogInstruments = [inst1, inst2, inst3, inst4];
    const ownedInstrumentIds = new Set(['1', '3']);
    const ownedSymbols = new Set(['VWCE', 'EDO0134']);

    const result = partitionInstruments({
      catalogInstruments,
      ownedInstrumentIds,
      ownedSymbols,
      query: '',
    });

    assert.deepEqual(
      result.yourInstruments.map((i) => i.symbol),
      ['EDO0134', 'VWCE']
    );
    assert.deepEqual(
      result.allInstruments.map((i) => i.symbol),
      ['AAPL', 'VUAA']
    );
  });

  it('filters instruments case-insensitively by symbol', () => {
    const catalogInstruments = [inst1, inst2, inst3, inst4];
    const ownedInstrumentIds = new Set(['1']);
    const ownedSymbols = new Set(['VWCE']);

    const result = partitionInstruments({
      catalogInstruments,
      ownedInstrumentIds,
      ownedSymbols,
      query: 'vuaa',
    });

    assert.strictEqual(result.yourInstruments.length, 0);
    assert.deepEqual(
      result.allInstruments.map((i) => i.symbol),
      ['VUAA']
    );
  });

  it('filters instruments case-insensitively by name', () => {
    const catalogInstruments = [inst1, inst2, inst3, inst4];
    const ownedInstrumentIds = new Set(['1']);
    const ownedSymbols = new Set(['VWCE']);

    const result = partitionInstruments({
      catalogInstruments,
      ownedInstrumentIds,
      ownedSymbols,
      query: 'vanguard',
    });

    assert.deepEqual(
      result.yourInstruments.map((i) => i.symbol),
      ['VWCE']
    );
    assert.deepEqual(
      result.allInstruments.map((i) => i.symbol),
      ['VUAA']
    );
  });

  it('filters instruments case-insensitively by type', () => {
    const catalogInstruments = [inst1, inst2, inst3, inst4];
    const ownedInstrumentIds = new Set(['1']);
    const ownedSymbols = new Set(['VWCE']);

    const result = partitionInstruments({
      catalogInstruments,
      ownedInstrumentIds,
      ownedSymbols,
      query: 'bond',
    });

    assert.strictEqual(result.yourInstruments.length, 0);
    assert.deepEqual(
      result.allInstruments.map((i) => i.symbol),
      ['EDO0134']
    );
  });

  it('deduplicates instruments between catalog and owned lists without repetition', () => {
    const catalogInstruments = [inst1, inst2];
    const ownedInstruments = [inst1, inst3];
    const ownedInstrumentIds = new Set(['1', '3']);
    const ownedSymbols = new Set(['VWCE', 'EDO0134']);

    const result = partitionInstruments({
      catalogInstruments,
      ownedInstruments,
      ownedInstrumentIds,
      ownedSymbols,
      query: '',
    });

    assert.deepEqual(
      result.yourInstruments.map((i) => i.symbol),
      ['EDO0134', 'VWCE']
    );
    assert.deepEqual(
      result.allInstruments.map((i) => i.symbol),
      ['VUAA']
    );
  });

  it('matches owned instruments by symbol even if id is different or absent', () => {
    const altInst1 = { id: 'alt-1', symbol: 'VWCE', name: 'Vanguard FTSE', type: 'ETF', currency: 'EUR' };
    const catalogInstruments = [altInst1, inst2];
    const ownedInstrumentIds = new Set();
    const ownedSymbols = new Set(['VWCE']);

    const result = partitionInstruments({
      catalogInstruments,
      ownedInstrumentIds,
      ownedSymbols,
      query: '',
    });

    assert.deepEqual(
      result.yourInstruments.map((i) => i.symbol),
      ['VWCE']
    );
    assert.deepEqual(
      result.allInstruments.map((i) => i.symbol),
      ['VUAA']
    );
  });

  it('returns empty lists when no instruments match the search query', () => {
    const catalogInstruments = [inst1, inst2, inst3, inst4];
    const ownedInstrumentIds = new Set(['1']);
    const ownedSymbols = new Set(['VWCE']);

    const result = partitionInstruments({
      catalogInstruments,
      ownedInstrumentIds,
      ownedSymbols,
      query: 'nonexistent-ticker-xyz',
    });

    assert.strictEqual(result.yourInstruments.length, 0);
    assert.strictEqual(result.allInstruments.length, 0);
  });

  it('handles empty inputs and missing optional sets gracefully', () => {
    const result = partitionInstruments({
      catalogInstruments: [],
      query: 'test',
    });

    assert.deepEqual(result.yourInstruments, []);
    assert.deepEqual(result.allInstruments, []);
  });
});
