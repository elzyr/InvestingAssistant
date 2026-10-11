import React from 'react';
import type { MonthlyReportRecord } from '../../types/reports.types.ts';
import {
  formatPLN,
  formatPercent,
  getValueColorClass,
} from '../../utils/reportsFormatters.ts';
import { MonthDonutChart } from './MonthDonutChart.tsx';
import {
  Wallet,
  TrendingUp,
  Percent,
  ShieldCheck,
  Calendar,
} from 'lucide-react';

interface MonthDetailViewProps {
  record: MonthlyReportRecord;
}

export const MonthDetailView: React.FC<MonthDetailViewProps> = ({
  record,
}) => {
  if (record.status === 'UPCOMING') {
    return (
      <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-12 text-center">
        <Calendar className="w-12 h-12 text-zinc-600 mx-auto mb-3" />
        <h3 className="text-base font-medium text-zinc-300">
          Miesiąc {record.monthName} {record.year} oczekuje na zamknięcie
        </h3>
        <p className="text-xs text-zinc-500 mt-1 max-w-md mx-auto">
          Dane dotyczące wyceny, zysków i alokacji będą dostępne po zakończeniu bieżącego okresu rozliczeniowego.
        </p>
      </div>
    );
  }

  const totalInvested = record.breakdown.reduce((sum, item) => sum + item.invested, 0);
  const totalValue = record.breakdown.reduce((sum, item) => sum + item.currentValue, 0);
  const totalProfitGross = record.breakdown.reduce((sum, item) => sum + item.profitGross, 0);
  const totalProfitNet = record.breakdown.reduce((sum, item) => sum + item.profitNet, 0);
  const totalReturnGross = totalInvested > 0 ? (totalProfitGross / totalInvested) * 100 : 0;
  const totalReturnNet = totalInvested > 0 ? (totalProfitNet / totalInvested) * 100 : 0;

  return (
    <div className="space-y-6">
      {/* Month Header Banner & Metric Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Total Valuation Card */}
        <div className="bg-zinc-900/60 border border-zinc-800/80 rounded-xl p-4 flex flex-col justify-between">
          <div className="flex items-center justify-between text-zinc-400">
            <span className="text-xs font-medium">Wycena portfela</span>
            <Wallet className="w-4 h-4 text-emerald-400" />
          </div>
          <div className="mt-2">
            <div className="text-xl font-bold font-mono text-zinc-100 tabular-nums">
              {formatPLN(record.portfolioValue)}
            </div>
            <div className="text-[11px] text-zinc-400 mt-1 flex items-center gap-1 font-mono">
              <span>Wpłacone:</span>
              <span className="text-zinc-300 font-medium">{formatPLN(record.investedTotal)}</span>
            </div>
          </div>
        </div>

        {/* Net Profit Card */}
        <div className="bg-zinc-900/60 border border-zinc-800/80 rounded-xl p-4 flex flex-col justify-between">
          <div className="flex items-center justify-between text-zinc-400">
            <span className="text-xs font-medium">Zysk netto (po Belce)</span>
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
          </div>
          <div className="mt-2">
            <div
              className={`text-xl font-bold font-mono tabular-nums ${getValueColorClass(
                record.profitNet
              )}`}
            >
              {formatPLN(record.profitNet, true)}
            </div>
            <div className="text-[11px] text-zinc-400 mt-1 flex items-center gap-1 font-mono">
              <span>Brutto:</span>
              <span className="text-zinc-300 font-medium">
                {formatPLN(record.profitGross, true)}
              </span>
            </div>
          </div>
        </div>

        {/* Net Return Rate Card */}
        <div className="bg-zinc-900/60 border border-zinc-800/80 rounded-xl p-4 flex flex-col justify-between">
          <div className="flex items-center justify-between text-zinc-400">
            <span className="text-xs font-medium">Stopa zwrotu netto</span>
            <Percent className="w-4 h-4 text-cyan-400" />
          </div>
          <div className="mt-2">
            <div
              className={`text-xl font-bold font-mono tabular-nums ${getValueColorClass(
                record.returnNetPct
              )}`}
            >
              {formatPercent(record.returnNetPct, true)}
            </div>
            <div className="text-[11px] text-zinc-400 mt-1 flex items-center gap-1 font-mono">
              <span>Brutto:</span>
              <span className="text-zinc-300 font-medium">
                {formatPercent(record.returnGrossPct, true)}
              </span>
            </div>
          </div>
        </div>

        {/* MoM Performance Card */}
        <div className="bg-zinc-900/60 border border-zinc-800/80 rounded-xl p-4 flex flex-col justify-between">
          <div className="flex items-center justify-between text-zinc-400">
            <span className="text-xs font-medium">Zysk miesiąc do miesiąca</span>
            <TrendingUp className="w-4 h-4 text-amber-400" />
          </div>
          <div className="mt-2">
            <div
              className={`text-xl font-bold font-mono tabular-nums ${getValueColorClass(
                record.profitNetMom
              )}`}
            >
              {formatPLN(record.profitNetMom, true)}
            </div>
            <div className="text-[11px] text-zinc-400 mt-1 flex items-center gap-1 font-mono">
              <span>Zmiana m/m:</span>
              <span className={`font-medium ${getValueColorClass(record.returnNetMomPct)}`}>
                {formatPercent(record.returnNetMomPct, true)}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Two-Column Desktop Layout: Table (Left 65%) + Donut (Right 35%) */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Left Column: Asset Breakdown Table (8 cols out of 12 ~ 66.7%) */}
        <div className="lg:col-span-8 bg-zinc-900/50 border border-zinc-800/80 rounded-xl overflow-hidden">
          <div className="p-4 sm:p-5 border-b border-zinc-800/80 flex items-center justify-between">
            <div>
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
                Rozbicie Kategorii Aktywów
              </span>
              <h3 className="text-sm font-medium text-zinc-200 mt-0.5">
                Szczegółowy wynik składników majątku w {record.monthName} {record.year}
              </h3>
            </div>
            <span className="text-xs font-mono text-zinc-400 bg-zinc-800/40 px-2.5 py-1 rounded border border-zinc-700/40">
              {record.breakdown.length} pozycji
            </span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full table-auto text-xs text-left">
              <thead className="bg-[#0e0f15] text-zinc-400 font-semibold uppercase tracking-wider text-[11px] border-b border-zinc-800/80">
                <tr>
                  <th className="py-2.5 px-3 text-left whitespace-nowrap">Kategoria</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">Wpłacone</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">Wycena</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">Zysk brutto</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">Zysk netto</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">Zwrot brutto %</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">Zwrot netto %</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-800/50 font-mono text-[11px] sm:text-xs">
                {record.breakdown.map((item) => (
                  <tr key={item.category} className="hover:bg-zinc-800/30 transition-colors">
                    <td className="py-2.5 px-3 font-sans font-medium text-zinc-200 whitespace-nowrap">
                      <div className="flex items-center gap-2">
                        <span
                          className="w-2.5 h-2.5 rounded-full shrink-0"
                          style={{ backgroundColor: item.color }}
                        />
                        <span className="truncate">{item.category}</span>
                      </div>
                    </td>
                    <td className="py-2.5 px-3 text-right text-zinc-400 tabular-nums whitespace-nowrap">
                      {formatPLN(item.invested)}
                    </td>
                    <td className="py-2.5 px-3 text-right font-medium text-zinc-100 tabular-nums whitespace-nowrap">
                      {formatPLN(item.currentValue)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                        item.profitGross
                      )}`}
                    >
                      {formatPLN(item.profitGross, true)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${getValueColorClass(
                        item.profitNet
                      )}`}
                    >
                      {formatPLN(item.profitNet, true)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                        item.returnGrossPct
                      )}`}
                    >
                      {formatPercent(item.returnGrossPct, true)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${getValueColorClass(
                        item.returnNetPct
                      )}`}
                    >
                      {formatPercent(item.returnNetPct, true)}
                    </td>
                  </tr>
                ))}
              </tbody>

              {/* Total Footer */}
              <tfoot className="bg-[#0b0c10] border-t-2 border-zinc-700/80 font-mono text-xs">
                <tr className="font-semibold text-zinc-200">
                  <td className="py-3 px-3 font-sans text-xs sm:text-sm text-zinc-100 whitespace-nowrap">
                    Razem
                  </td>
                  <td className="py-3 px-3 text-right text-zinc-300 tabular-nums whitespace-nowrap">
                    {formatPLN(totalInvested)}
                  </td>
                  <td className="py-3 px-3 text-right text-zinc-100 text-xs sm:text-sm tabular-nums whitespace-nowrap">
                    {formatPLN(totalValue)}
                  </td>
                  <td
                    className={`py-3 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                      totalProfitGross
                    )}`}
                  >
                    {formatPLN(totalProfitGross, true)}
                  </td>
                  <td
                    className={`py-3 px-3 text-right text-xs sm:text-sm tabular-nums whitespace-nowrap ${getValueColorClass(
                      totalProfitNet
                    )}`}
                  >
                    {formatPLN(totalProfitNet, true)}
                  </td>
                  <td
                    className={`py-3 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                      totalReturnGross
                    )}`}
                  >
                    {formatPercent(totalReturnGross, true)}
                  </td>
                  <td
                    className={`py-3 px-3 text-right text-xs sm:text-sm tabular-nums whitespace-nowrap ${getValueColorClass(
                      totalReturnNet
                    )}`}
                  >
                    {formatPercent(totalReturnNet, true)}
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>
        </div>

        {/* Right Column: Donut Chart Allocation (4 cols out of 12 ~ 33.3%) */}
        <div className="lg:col-span-4">
          <MonthDonutChart
            breakdown={record.breakdown}
            totalValue={record.portfolioValue}
          />
        </div>
      </div>
    </div>
  );
};
