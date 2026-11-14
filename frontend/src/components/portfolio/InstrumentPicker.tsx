import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { isCancel } from 'axios';
import { extractApiErrorMessage } from '../../api/client.ts';
import { Check, ChevronDown, Loader2, Search, X } from 'lucide-react';
import { instrumentService } from '../../services/instrument.service.ts';
import { partitionInstruments } from '../../utils/instrumentSearch.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';

interface InstrumentPickerProps {
  isOpen: boolean;
  effectiveInstrumentId: string;
  selectedInstrument?: InstrumentResponse | null;
  cachedInstruments: InstrumentResponse[];
  ownedInstrumentIds: Set<string>;
  ownedSymbols: Set<string>;
  onSelectInstrument: (inst: InstrumentResponse) => void;
  onError: (message: string) => void;
}

export const InstrumentPicker: React.FC<InstrumentPickerProps> = ({
  isOpen,
  effectiveInstrumentId,
  selectedInstrument,
  cachedInstruments,
  ownedInstrumentIds,
  ownedSymbols,
  onSelectInstrument,
  onError,
}) => {
  const { t } = useTranslation();
  const [searchQuery, setSearchQuery] = useState('');
  const [isPickerOpen, setIsPickerOpen] = useState(false);
  const [highlightedIndex, setHighlightedIndex] = useState(0);
  const [catalogInstruments, setCatalogInstruments] = useState<InstrumentResponse[]>([]);
  const [loadingCatalog, setLoadingCatalog] = useState(false);
  const pickerRef = useRef<HTMLDivElement>(null);

  const onErrorRef = useRef(onError);

  useEffect(() => {
    onErrorRef.current = onError;
  });

  const { yourInstruments, allInstruments } = useMemo(
    () =>
      partitionInstruments({
        catalogInstruments,
        ownedInstruments: cachedInstruments,
        ownedInstrumentIds,
        ownedSymbols,
        query: searchQuery,
      }),
    [catalogInstruments, cachedInstruments, ownedInstrumentIds, ownedSymbols, searchQuery]
  );

  const combinedList = useMemo(
    () => [...yourInstruments, ...allInstruments],
    [yourInstruments, allInstruments]
  );

  useEffect(() => {
    if (!isPickerOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (pickerRef.current && !pickerRef.current.contains(e.target as Node)) {
        setIsPickerOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isPickerOpen]);

  useEffect(() => {
    const trimmed = searchQuery.trim();
    if (!isOpen || !trimmed) return;

    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const data = trimmed.length >= 2
          ? await instrumentService.discoverInstruments(trimmed, undefined, controller.signal)
          : await instrumentService.getInstruments(trimmed, undefined, controller.signal);
        if (controller.signal.aborted) return;
        const filtered = data.filter((i) => i.type === 'ETF' || i.type === 'BOND' || i.type === 'STOCK');
        setCatalogInstruments(filtered);
      } catch (err: unknown) {
        if (controller.signal.aborted || isCancel(err)) return;
        onErrorRef.current(
          extractApiErrorMessage(err, t('transactionModal.errorLoadingInstruments'), true)
        );
      } finally {
        if (!controller.signal.aborted) setLoadingCatalog(false);
      }
    }, 250);

    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [isOpen, searchQuery, t]);

  const handleSearchQueryChange = (val: string) => {
    setSearchQuery(val);
    setHighlightedIndex(0);
    if (!val.trim()) {
      setCatalogInstruments([]);
      setLoadingCatalog(false);
    } else if (isOpen) {
      setLoadingCatalog(true);
    }
  };

  const handlePickerKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      setIsPickerOpen(false);
      return;
    }
    if (combinedList.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlightedIndex((prev) => (prev + 1) % combinedList.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlightedIndex((prev) => (prev - 1 + combinedList.length) % combinedList.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const targetInst = combinedList[highlightedIndex];
      if (targetInst) {
        onSelectInstrument(targetInst);
        setIsPickerOpen(false);
      }
    }
  };

  const renderGroup = (title: string, items: InstrumentResponse[], offset: number) => {
    if (items.length === 0) return null;
    return (
      <div>
        <div className="px-3 py-1.5 text-[10px] uppercase font-semibold text-zinc-400 bg-zinc-800/40 tracking-wider">
          {title}
        </div>
        {items.map((inst, idx) => {
          const itemIndex = offset + idx;
          const isSelected = inst.id === effectiveInstrumentId;
          const isHighlighted = highlightedIndex === itemIndex;
          return (
            <button
              key={inst.id}
              type="button"
              onClick={() => {
                onSelectInstrument(inst);
                setIsPickerOpen(false);
              }}
              onMouseEnter={() => setHighlightedIndex(itemIndex)}
              className={`w-full text-left px-3 py-2 text-xs flex items-center justify-between transition ${
                isSelected ? 'text-emerald-400 font-medium' : 'text-zinc-200'
              } ${isHighlighted ? 'bg-zinc-800' : 'hover:bg-zinc-800/60'}`}
            >
              <div className="truncate mr-2">
                <span className="font-mono font-semibold">{inst.symbol}</span>
                <span className="text-zinc-400 ml-2">{inst.name}</span>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <span className="text-[10px] text-zinc-500 font-mono uppercase">
                  {inst.type} · {inst.currency}
                </span>
                {isSelected && <Check className="w-3.5 h-3.5 text-emerald-400" />}
              </div>
            </button>
          );
        })}
      </div>
    );
  };

  return (
    <div className="relative" ref={pickerRef}>
      <label className="block text-xs text-zinc-400 mb-1.5">
        {t('transactionModal.instrument')}
      </label>
      <button
        type="button"
        onClick={() => {
          setIsPickerOpen((prev) => {
            if (!prev) setHighlightedIndex(0);
            return !prev;
          });
        }}
        className="w-full flex items-center justify-between bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-sans focus:outline-none focus:border-zinc-600 transition"
      >
        <span className="truncate">
          {selectedInstrument
            ? `${selectedInstrument.symbol} — ${selectedInstrument.name} (${selectedInstrument.currency})`
            : t('transactionModal.selectInstrument')}
        </span>
        <ChevronDown
          className={`w-3.5 h-3.5 text-zinc-400 transition-transform shrink-0 ml-2 ${isPickerOpen ? 'rotate-180' : ''}`}
        />
      </button>

      {isPickerOpen && (
        <div className="absolute left-0 right-0 top-full mt-1 z-30 bg-[#16181f] border border-zinc-700/80 rounded-lg shadow-2xl overflow-hidden flex flex-col max-h-60">
          <div className="p-2 border-b border-zinc-800 flex items-center gap-2 bg-[#121318] shrink-0">
            {loadingCatalog ? (
              <Loader2 className="w-3.5 h-3.5 text-zinc-400 animate-spin shrink-0" />
            ) : (
              <Search className="w-3.5 h-3.5 text-zinc-400 shrink-0" />
            )}
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => handleSearchQueryChange(e.target.value)}
              onKeyDown={handlePickerKeyDown}
              placeholder={t('transactionModal.searchInstrumentPlaceholder')}
              className="w-full bg-transparent text-xs text-zinc-100 placeholder-zinc-500 focus:outline-none font-sans"
              autoFocus
            />
            {searchQuery && (
              <button
                type="button"
                onClick={() => handleSearchQueryChange('')}
                className="text-zinc-500 hover:text-zinc-300"
              >
                <X className="w-3 h-3" />
              </button>
            )}
          </div>

          <div className="overflow-y-auto flex-1 divide-y divide-zinc-800/40">
            {combinedList.length === 0 ? (
              <div className="p-4 text-center text-xs text-zinc-500">
                {loadingCatalog
                  ? t('transactionModal.loadingCatalog')
                  : t('transactionModal.noInstrumentsFound')}
              </div>
            ) : (
              <>
                {renderGroup(t('transactionModal.yourInstruments'), yourInstruments, 0)}
                {renderGroup(t('transactionModal.allInstruments'), allInstruments, yourInstruments.length)}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
