import React from 'react';
import type { AnnualSummaryData } from '../../types/reports.types.ts';
import {
  formatPLN,
  formatPercent,
  getValueColorClass,
} from '../../utils/reportsFormatters.ts';
import { ArrowUpRight, ArrowDownRight, Clock } from 'lucide-react';

interface AnnualSummaryTableProps {
  summary: AnnualSummaryData;
  onSelectMonth?: (monthIndex: number) => void;
  selectedMonthIndex?: number;
}

export const AnnualSummaryTable: React.FC<AnnualSummaryTableProps> = ({
  summary,
  onSelectMonth,
  selectedMonthIndex,
}) => {
  const recordedRecords = summary.records.filter((r) => r.status === 'RECORDED');
  const latestRecordedMonth =
    recordedRecords.length > 0
      ? recordedRecords[recordedRecords.length - 1].monthName
      : '';
  const ytdLabel = latestRecordedMonth
    ? `Podsumowanie YTD (${latestRecordedMonth})`
    : 'Podsumowanie YTD';

  return (
    <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl overflow-hidden">
      <div className="p-4 sm:p-5 border-b border-zinc-800/80 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-zinc-100 tracking-tight">
            Podsumowanie Roczne {summary.year}
          </h3>
          <p className="text-xs text-zinc-400 mt-0.5">
            Zestawienie wyceny, rentowności brutto/netto oraz stóp realnych po uwzględnieniu CPI
          </p>
        </div>
        <div className="text-xs font-mono text-zinc-400 bg-zinc-800/40 px-3 py-1.5 rounded-lg border border-zinc-700/40 self-start sm:self-auto">
          Średnia inflacja CPI: <span className="text-zinc-200 font-semibold">{summary.avgCpi.toFixed(1)}%</span>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-xs text-left">
          <thead className="bg-[#0e0f15] text-zinc-400 font-semibold uppercase tracking-wider text-[11px] border-b border-zinc-800/80">
            <tr>
              <th className="py-2.5 px-3 whitespace-nowrap">Miesiąc</th>
              <th className="py-2.5 px-3 text-right whitespace-nowrap">Wartość portfela</th>
              <th className="py-2.5 px-3 text-right whitespace-nowrap">Zysk brutto</th>
              <th className="py-2.5 px-3 text-right whitespace-nowrap">Zwrot brutto %</th>
              <th className="py-2.5 px-3 text-right whitespace-nowrap">Zysk netto (Belka)</th>
              <th className="py-2.5 px-3 text-right whitespace-nowrap">Zwrot netto %</th>
              <th className="py-2.5 px-3 text-right whitespace-nowrap">Inflacja CPI</th>
              <th className="py-2.5 px-3 text-right whitespace-nowrap">Zysk realny</th>
              <th className="py-2.5 px-3 text-right whitespace-nowrap">Akcja</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-800/50 font-mono">
            {summary.records.map((r) => {
              const isUpcoming = r.status === 'UPCOMING';
              const isSelected = selectedMonthIndex === r.monthIndex;

              if (isUpcoming) {
                return (
                  <tr
                    key={r.monthIndex}
                    className="opacity-40 hover:opacity-60 transition text-zinc-500"
                  >
                    <td className="py-2.5 px-3 font-sans font-medium text-zinc-400 whitespace-nowrap">
                      {r.monthName}
                    </td>
                    <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                    <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                    <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                    <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                    <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                    <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">
                      {r.cpiInflation > 0 ? `${r.cpiInflation.toFixed(1)}%` : '—'}
                    </td>
                    <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                    <td className="py-2.5 px-3 text-right whitespace-nowrap">
                      <span className="inline-flex items-center gap-1 text-[10px] text-zinc-500 bg-zinc-800/30 px-2 py-0.5 rounded font-sans">
                        <Clock className="w-3 h-3" /> Nadchodzący
                      </span>
                    </td>
                  </tr>
                );
              }

              return (
                <tr
                  key={r.monthIndex}
                  onClick={() => onSelectMonth && onSelectMonth(r.monthIndex)}
                  className={`cursor-pointer transition-colors ${
                    isSelected
                      ? 'bg-zinc-800/60 hover:bg-zinc-800/80'
                      : 'hover:bg-zinc-800/30'
                  }`}
                >
                  <td className="py-2.5 px-3 font-sans font-medium text-zinc-200 whitespace-nowrap">
                    <div className="flex items-center gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 shrink-0" />
                      <span>{r.monthName}</span>
                    </div>
                  </td>
                  <td className="py-2.5 px-3 text-right font-medium text-zinc-100 tabular-nums whitespace-nowrap">
                    {formatPLN(r.portfolioValue)}
                  </td>
                  <td
                    className={`py-2.5 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                      r.profitGross
                    )}`}
                  >
                    {formatPLN(r.profitGross, true)}
                  </td>
                  <td
                    className={`py-2.5 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                      r.returnGrossPct
                    )}`}
                  >
                    {formatPercent(r.returnGrossPct, true)}
                  </td>
                  <td
                    className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${getValueColorClass(
                      r.profitNet
                    )}`}
                  >
                    {formatPLN(r.profitNet, true)}
                  </td>
                  <td
                    className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${getValueColorClass(
                      r.returnNetPct
                    )}`}
                  >
                    {formatPercent(r.returnNetPct, true)}
                  </td>
                  <td className="py-2.5 px-3 text-right text-zinc-400 tabular-nums whitespace-nowrap">
                    {r.cpiInflation > 0 ? `${r.cpiInflation.toFixed(1)}%` : '—'}
                  </td>
                  <td
                    className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${
                      r.cpiInflation > 0 ? getValueColorClass(r.realProfitPct) : 'text-zinc-500'
                    }`}
                  >
                    {r.cpiInflation > 0 ? formatPercent(r.realProfitPct, true) : '—'}
                  </td>
                  <td className="py-2.5 px-3 text-right font-sans whitespace-nowrap">
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        onSelectMonth?.(r.monthIndex);
                      }}
                      className="text-[11px] text-zinc-400 hover:text-emerald-400 hover:bg-zinc-800 px-2.5 py-1 rounded transition inline-flex items-center gap-1"
                    >
                      Szczegóły →
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>

          {/* Summary footer row (YTD / Year End) */}
          <tfoot className="bg-[#0b0c10] border-t-2 border-zinc-700/80 font-mono text-xs">
            <tr className="font-semibold text-zinc-200">
              <td className="py-3 px-3 font-sans text-xs sm:text-sm text-zinc-100 whitespace-nowrap">
                {ytdLabel}
              </td>
              <td className="py-3 px-3 text-right text-zinc-100 text-xs sm:text-sm tabular-nums whitespace-nowrap">
                {formatPLN(summary.ytdPortfolioValue)}
              </td>
              <td
                className={`py-3 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                  summary.ytdProfitGross
                )}`}
              >
                {formatPLN(summary.ytdProfitGross, true)}
              </td>
              <td
                className={`py-3 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                  summary.ytdReturnGrossPct
                )}`}
              >
                {formatPercent(summary.ytdReturnGrossPct, true)}
              </td>
              <td
                className={`py-3 px-3 text-right text-xs sm:text-sm tabular-nums whitespace-nowrap ${getValueColorClass(
                  summary.ytdProfitNet
                )}`}
              >
                {formatPLN(summary.ytdProfitNet, true)}
              </td>
              <td
                className={`py-3 px-3 text-right text-xs sm:text-sm tabular-nums whitespace-nowrap ${getValueColorClass(
                  summary.ytdReturnNetPct
                )}`}
              >
                {formatPercent(summary.ytdReturnNetPct, true)}
              </td>
              <td className="py-3 px-3 text-right text-zinc-400 tabular-nums whitespace-nowrap">
                {summary.avgCpi > 0 ? `${summary.avgCpi.toFixed(1)}%` : '—'}
              </td>
              <td
                className={`py-3 px-3 text-right text-xs sm:text-sm tabular-nums whitespace-nowrap ${
                  summary.avgCpi > 0 ? getValueColorClass(summary.ytdRealReturnPct) : 'text-zinc-500'
                }`}
              >
                {summary.avgCpi > 0 ? formatPercent(summary.ytdRealReturnPct, true) : '—'}
              </td>
              <td className="py-3 px-3 text-right font-sans text-emerald-400 text-[11px] whitespace-nowrap">
                {summary.ytdProfitNet >= 0 ? (
                  <span className="inline-flex items-center gap-0.5 justify-end">
                    <ArrowUpRight className="w-3.5 h-3.5" /> Na plusie
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-0.5 justify-end text-rose-400">
                    <ArrowDownRight className="w-3.5 h-3.5" /> Strata
                  </span>
                )}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  );
};
