import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, Pencil } from 'lucide-react';
import type { Position, TaxMode } from '../../types/dashboard.ts';

interface PositionRowProps {
  position: Position;
  isExpanded: boolean;
  onToggle: () => void;
  onEditTransaction?: (transactionId: string) => void;
}

const formatReturnWithXirr = (
  profitPercentFormatted: string,
  xirr?: number | null,
  isXirrFallback?: boolean,
  unit = 'r/r'
): string => {
  if (xirr != null && !isXirrFallback) {
    const xirrVal = (xirr * 100).toFixed(1);
    const match = profitPercentFormatted.match(/^([+-]?\d+(?:\.\d+)?%)/);
    const base = match ? match[1] : profitPercentFormatted;
    return `${base} (${xirrVal}% ${unit})`;
  }
  return profitPercentFormatted;
};

const PositionRow = ({
  position,
  isExpanded,
  onToggle,
  onEditTransaction,
}: PositionRowProps) => {
  const { t } = useTranslation();
  const annualizedUnit = t('dashboard.annualizedReturn', 'r/r');
  const isExpandable = Boolean(
    position.subPositions &&
    (position.subPositionsType === 'bonds_yearly'
      ? position.subPositions.length > 0
      : position.subPositions.length > 1)
  );

  return (
    <div className="group">
      <div
        onClick={isExpandable ? onToggle : undefined}
        className={`grid grid-cols-[minmax(140px,1.8fr)_80px_100px_110px_140px_32px] gap-x-4 px-4 py-3 items-center transition-colors select-none ${
          isExpandable ? 'cursor-pointer hover:bg-[#1a1e2a]' : ''
        }`}
      >
        <div>
          <span className="font-bold text-white text-xs block tracking-tight">
            {position.ticker}
          </span>
          <span className="text-xs text-zinc-400">
            {position.quantityFormatted} • {position.name}
          </span>
          {position.fxDecomposition && (
            <div className="mt-1 flex items-center gap-1 font-mono text-[10px] text-zinc-400">
              <span className="bg-zinc-800/80 px-1.5 py-0.5 rounded border border-zinc-700/50">
                {t('dashboard.market', 'Rynek')}: {position.fxDecomposition.marketReturnPct >= 0 ? '+' : ''}
                {position.fxDecomposition.marketReturnPct.toFixed(1)}% · {t('dashboard.fx', 'FX')}:{' '}
                {position.fxDecomposition.fxReturnPct >= 0 ? '+' : ''}
                {position.fxDecomposition.fxReturnPct.toFixed(1)}%
              </span>
            </div>
          )}
        </div>

        <div className="text-right font-mono font-medium text-xs text-zinc-300">
          {position.sharePercent.toFixed(1)}%
        </div>

        <div className="text-right font-mono text-xs text-zinc-300">
          {position.priceFormatted}
        </div>

        <div className="text-right font-mono font-bold text-xs text-white">
          {position.valueFormatted}
        </div>

        <div className="text-right">
          <span className={`font-mono font-bold text-xs block ${position.isProfit ? 'text-emerald-400' : 'text-rose-400'}`}>
            {position.profitFormatted}
          </span>
          <span className={`font-mono text-[11px] font-medium ${position.isProfit ? 'text-emerald-400/90' : 'text-rose-400/90'}`}>
            {formatReturnWithXirr(position.profitPercentFormatted, position.xirr, position.isXirrFallback, annualizedUnit)}
          </span>
        </div>

        <div className="w-8 flex items-center justify-center">
          {!isExpandable && position.subPositions?.length === 1 && position.subPositions[0].transactionId && onEditTransaction ? (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onEditTransaction(position.subPositions![0].transactionId!);
              }}
              className="p-1 rounded hover:bg-zinc-800 text-zinc-400 hover:text-white transition"
              title="Edytuj transakcję"
            >
              <Pencil className="w-3.5 h-3.5" />
            </button>
          ) : isExpandable ? (
            <ChevronDown
              className={`w-4 h-4 text-zinc-400 group-hover:text-emerald-400 transition-transform duration-200 ${
                isExpanded ? 'rotate-180 text-emerald-400' : ''
              }`}
            />
          ) : null}
        </div>
      </div>

      {isExpandable && isExpanded && (
        <div className="bg-[#0a0c10] border-t border-b border-zinc-800/80 border-l-2 border-l-emerald-500/50 py-2.5 px-4 space-y-1.5">
          <div className="text-xs font-normal text-zinc-400 mb-2 flex items-center gap-1.5 font-sans">
            <span>
              {position.subPositionsType === 'bonds_yearly'
                ? 'Podział na roczniki emisji:'
                : 'Transakcje zakupu w ramach pozycji:'}
            </span>
          </div>

          {position.subPositions!.map((sub) => {
            const isSubProfit = !sub.profitFormatted.startsWith('-');
            return (
              <div
                key={sub.id}
                className="grid grid-cols-[minmax(140px,1.8fr)_80px_100px_110px_140px_32px] gap-x-4 py-1.5 items-center text-xs font-mono"
              >
                <div>
                  <span className="text-zinc-100 font-semibold block">{sub.name}</span>
                  {sub.subName && (
                    <span className="text-zinc-400 text-[11px] block">{sub.subName}</span>
                  )}
                  {sub.fxDecomposition && (
                    <div className="mt-0.5 flex items-center gap-1 font-mono text-[10px] text-zinc-400">
                      <span className="bg-zinc-800/60 px-1.5 py-0.5 rounded border border-zinc-700/40">
                        {t('dashboard.market', 'Rynek')}: {sub.fxDecomposition.marketReturnPct >= 0 ? '+' : ''}
                        {sub.fxDecomposition.marketReturnPct.toFixed(1)}% · {t('dashboard.fx', 'FX')}:{' '}
                        {sub.fxDecomposition.fxReturnPct >= 0 ? '+' : ''}
                        {sub.fxDecomposition.fxReturnPct.toFixed(1)}%
                      </span>
                    </div>
                  )}
                </div>

                <div className="text-right font-mono">
                  {sub.sharePercent > 0 ? (
                    <>
                      <span className="text-xs text-zinc-300 font-medium block">
                        {sub.sharePercent.toFixed(1)}%
                      </span>
                      {sub.shareNote && (
                        <span className="text-[10px] text-zinc-400 block">{sub.shareNote}</span>
                      )}
                    </>
                  ) : (
                    <span className="text-zinc-500">—</span>
                  )}
                </div>

                <div className="text-right font-mono text-zinc-300 text-xs">
                  {sub.priceFormatted}
                </div>

                <div className="text-right font-mono font-bold text-white text-xs">
                  {sub.valueFormatted}
                </div>

                <div className="text-right font-mono">
                  <span className={`font-bold block ${isSubProfit ? 'text-emerald-400' : 'text-rose-400'}`}>
                    {sub.profitFormatted}
                  </span>
                  <span className={`text-[11px] font-medium block ${isSubProfit ? 'text-emerald-400/90' : 'text-rose-400/90'}`}>
                    {formatReturnWithXirr(sub.profitPercentFormatted, sub.xirr, sub.isXirrFallback, annualizedUnit)}
                  </span>
                </div>

                <div className="w-8 flex items-center justify-center">
                  {sub.transactionId && onEditTransaction && (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onEditTransaction(sub.transactionId!);
                      }}
                      className="p-1 rounded hover:bg-zinc-800 text-zinc-400 hover:text-white transition"
                      title="Edytuj transakcję"
                    >
                      <Pencil className="w-3.5 h-3.5" />
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

interface PositionsTableProps {
  positions: Position[];
  taxMode?: TaxMode;
  onAddTransaction?: () => void;
  onEditTransaction?: (transactionId: string) => void;
}

export const PositionsTable = ({
  positions,
  taxMode = 'NETTO',
  onAddTransaction,
  onEditTransaction,
}: PositionsTableProps) => {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set(['bond-EDO']));

  const handleToggle = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  return (
    <div className="bg-[#141720] border border-zinc-800 rounded-xl overflow-hidden shadow-sm">
      <div className="grid grid-cols-[minmax(140px,1.8fr)_80px_100px_110px_140px_32px] gap-x-4 px-4 py-2.5 bg-[#10121a] border-b border-zinc-800 text-[11px] font-bold text-zinc-400 uppercase tracking-wider">
        <div>Aktywo / Typ</div>
        <div className="text-right">Udział %</div>
        <div className="text-right">Kurs / Cena</div>
        <div className="text-right">Wartość</div>
        <div className="text-right">
          Zysk / Zwrot <span className="text-[10px] text-zinc-500 font-mono font-normal">({taxMode.toLowerCase()})</span>
        </div>
        <div className="w-8"></div>
      </div>

      {positions.length === 0 ? (
        <div className="py-12 px-4 text-center">
          <p className="text-sm font-medium text-zinc-300">Brak pozycji spełniających kryteria</p>
          <p className="text-xs text-zinc-500 max-w-sm mx-auto mt-1 mb-4">
            Zmień filtr wyszukiwania lub dodaj nową transakcję do portfela.
          </p>
          {onAddTransaction && (
            <button
              onClick={onAddTransaction}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-400 hover:bg-emerald-300 text-zinc-950 text-xs font-semibold transition"
            >
              + Dodaj transakcję
            </button>
          )}
        </div>
      ) : (
        <div className="divide-y divide-zinc-800/80">
          {positions.map((pos) => (
            <PositionRow
              key={pos.id}
              position={pos}
              isExpanded={expandedIds.has(pos.id)}
              onToggle={() => handleToggle(pos.id)}
              onEditTransaction={onEditTransaction}
            />
          ))}
        </div>
      )}
    </div>
  );
};
