import React, { useState } from 'react';
import type { MonthlyCategoryBreakdown } from '../../types/reports.types.ts';
import { formatPLN, formatPercent } from '../../utils/reportsFormatters.ts';

interface MonthDonutChartProps {
  breakdown: MonthlyCategoryBreakdown[];
  totalValue: number;
}

export const MonthDonutChart: React.FC<MonthDonutChartProps> = ({
  breakdown,
  totalValue,
}) => {
  const [hoveredIdx, setHoveredIdx] = useState<number | null>(null);

  if (!breakdown || breakdown.length === 0 || totalValue <= 0) {
    return (
      <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-6 flex items-center justify-center text-zinc-500 text-sm">
        Brak danych o strukturze alokacji w tym miesiącu
      </div>
    );
  }

  const items = breakdown.map((item, idx) => {
    const pct = totalValue > 0 ? (item.currentValue / totalValue) * 100 : item.sharePct;
    return {
      ...item,
      pct,
      idx,
    };
  });

  const activeItem = hoveredIdx !== null ? items[hoveredIdx] : null;

  return (
    <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-5 flex flex-col justify-between">
      <div>
        <div className="flex items-center justify-between mb-4">
          <div>
            <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
              Struktura Portfela
            </span>
            <h3 className="text-sm font-medium text-zinc-200 mt-0.5">
              Alokacja kapitału według aktywów
            </h3>
          </div>
          <div className="text-right font-mono">
            <span className="text-[11px] text-zinc-500 block">Łącznie</span>
            <span className="text-sm font-bold text-white tabular-nums">
              {formatPLN(totalValue)}
            </span>
          </div>
        </div>

                <div className="space-y-1.5 my-3">
          <div className="h-4 w-full bg-zinc-950 rounded-md overflow-hidden flex p-0.5 gap-0.5 border border-zinc-800">
            {items.map((item) => {
              const isHovered = hoveredIdx === item.idx;
              return (
                <div
                  key={item.category}
                  style={{
                    width: `${Math.max(1, item.pct)}%`,
                    backgroundColor: item.color,
                  }}
                  onMouseEnter={() => setHoveredIdx(item.idx)}
                  onMouseLeave={() => setHoveredIdx(null)}
                  className={`h-full rounded-sm transition-all duration-200 cursor-pointer ${
                    isHovered ? 'brightness-125 scale-y-110 shadow-lg' : 'hover:brightness-110'
                  }`}
                  title={`${item.category}: ${formatPercent(item.pct)} (${formatPLN(item.currentValue)})`}
                />
              );
            })}
          </div>

          <div className="h-4 flex items-center justify-between text-[11px] font-mono">
            {activeItem ? (
              <>
                <span className="text-zinc-300 font-medium">{activeItem.category}</span>
                <span className="text-sky-400 font-bold">
                  {formatPercent(activeItem.pct)} &bull; {formatPLN(activeItem.currentValue)}
                </span>
              </>
            ) : (
              <>
                <span className="text-zinc-500">Najedź na segment, aby sprawdzić wagę</span>
                <span className="text-zinc-500">100% alokacji</span>
              </>
            )}
          </div>
        </div>
      </div>

            <div className="mt-3 pt-3 border-t border-zinc-800/80 space-y-2">
        {items.map((item) => {
          const isHovered = hoveredIdx === item.idx;
          return (
            <div
              key={item.category}
              onMouseEnter={() => setHoveredIdx(item.idx)}
              onMouseLeave={() => setHoveredIdx(null)}
              className={`p-2.5 rounded-lg border transition cursor-pointer ${
                isHovered
                  ? 'bg-zinc-800/70 border-zinc-700'
                  : 'bg-zinc-950/40 border-zinc-800/50 hover:bg-zinc-800/30'
              }`}
            >
              <div className="flex items-center justify-between text-xs mb-1.5">
                <div className="flex items-center gap-2 min-w-0">
                  <span
                    className="w-2.5 h-2.5 rounded-full shrink-0"
                    style={{ backgroundColor: item.color }}
                  />
                  <span className={`truncate font-medium ${isHovered ? 'text-white' : 'text-zinc-300'}`}>
                    {item.category}
                  </span>
                </div>
                <div className="flex items-center gap-2.5 font-mono tabular-nums shrink-0">
                  <span className="text-zinc-400">{formatPLN(item.currentValue)}</span>
                  <span className="text-zinc-100 font-bold w-12 text-right">
                    {formatPercent(item.pct)}
                  </span>
                </div>
              </div>

                            <div className="w-full h-1 bg-zinc-800/60 rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full transition-all duration-300"
                  style={{
                    width: `${Math.min(100, Math.max(2, item.pct))}%`,
                    backgroundColor: item.color,
                  }}
                />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
