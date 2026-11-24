import React from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowDownRight, ArrowUpRight } from 'lucide-react';
import type { ExpenseYearGrid } from '../../utils/expenseFormatters.ts';
import { formatExpenseCurrency, getCategoryMeta } from '../../utils/expenseFormatters.ts';
import { formatPercent, getValueColorClass } from '../../utils/reportsFormatters.ts';
import { ExpenseMonthlyTrendChart } from './ExpenseMonthlyTrendChart.tsx';

interface ExpenseAnnualSummaryProps {
  grid: ExpenseYearGrid;
  currency: string;
  selectedMonthIndex: number | null;
  onSelectMonth: (monthIndex: number) => void;
}

export const ExpenseAnnualSummary: React.FC<ExpenseAnnualSummaryProps> = ({
  grid,
  currency,
  selectedMonthIndex,
  onSelectMonth,
}) => {
  const { t, i18n } = useTranslation();
  const locale = i18n.language.startsWith('pl') ? 'pl-PL' : 'en-US';

  const chartBars = grid.months.map((cell) => ({
    key: cell.monthKey,
    label: cell.shortName,
    hoverLabel: `${cell.monthName} ${grid.year}`,
    hasData: cell.hasData,
    point: cell.point,
    monthIndex: cell.monthIndex,
  }));

  return (
    <div className="space-y-6">
      <ExpenseMonthlyTrendChart
        bars={chartBars}
        title={t('expenses.annual.chartTitle', { year: grid.year })}
        subtitle={t('expenses.annual.chartSubtitle')}
        currency={currency}
        selectedMonthIndex={selectedMonthIndex}
        onSelectMonth={onSelectMonth}
      />

      <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl overflow-hidden">
        <div className="p-4 sm:p-5 border-b border-zinc-800/80 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
          <div>
            <h3 className="text-sm font-semibold text-zinc-100 tracking-tight">
              {t('expenses.annual.tableTitle', { year: grid.year })}
            </h3>
            <p className="text-xs text-zinc-400 mt-0.5">{t('expenses.annual.tableSubtitle')}</p>
          </div>
          <div className="flex flex-wrap items-center gap-2 self-start sm:self-auto">
            <div className="text-xs font-mono text-zinc-400 bg-zinc-800/40 px-3 py-1.5 rounded-lg border border-zinc-700/40">
              {t('expenses.kpis.netCashflow')}:{' '}
              <span className={`font-semibold ${getValueColorClass(grid.totals.netCashflow)}`}>
                {formatExpenseCurrency(grid.totals.netCashflow, true, currency, locale)}
              </span>
            </div>
            <div className="text-xs font-mono text-zinc-400 bg-zinc-800/40 px-3 py-1.5 rounded-lg border border-zinc-700/40">
              {t('expenses.kpis.savingsRate')}{' '}
              <span className="text-zinc-200 font-semibold">
                {grid.totals.savingsRatePct !== null
                  ? formatPercent(grid.totals.savingsRatePct, false, 1, locale)
                  : '—'}
              </span>
            </div>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-xs text-left">
            <thead className="bg-[#0e0f15] text-zinc-400 font-semibold uppercase tracking-wider text-[11px] border-b border-zinc-800/80">
              <tr>
                <th className="py-2.5 px-3 whitespace-nowrap">{t('expenses.annual.colMonth')}</th>
                <th className="py-2.5 px-3 whitespace-nowrap">{t('expenses.annual.colStatus')}</th>
                <th className="py-2.5 px-3 text-right whitespace-nowrap">
                  {t('expenses.annual.colIncome')}
                </th>
                <th className="py-2.5 px-3 text-right whitespace-nowrap">
                  {t('expenses.annual.colExpenses')}
                </th>
                <th className="py-2.5 px-3 text-right whitespace-nowrap">
                  {t('expenses.annual.colInvestments')}
                </th>
                <th className="py-2.5 px-3 text-right whitespace-nowrap">
                  {t('expenses.annual.colNetCashflow')}
                </th>
                <th className="py-2.5 px-3 text-right whitespace-nowrap">
                  {t('expenses.annual.colSavingsRate')}
                </th>
                <th className="py-2.5 px-3 whitespace-nowrap">
                  {t('expenses.annual.colTopCategory')}
                </th>
                <th className="py-2.5 px-3 text-right whitespace-nowrap">
                  {t('expenses.annual.colMomChange')}
                </th>
                <th className="py-2.5 px-3 text-right whitespace-nowrap">
                  {t('expenses.annual.colAction')}
                </th>
              </tr>
            </thead>

            <tbody className="divide-y divide-zinc-800/50 font-mono">
              {grid.months.map((cell) => {
                const pt = cell.point;
                const isSelected = selectedMonthIndex === cell.monthIndex;

                if (!cell.hasData || !pt) {
                  return (
                    <tr
                      key={cell.monthKey}
                      onClick={() => onSelectMonth(cell.monthIndex)}
                      className="opacity-45 hover:opacity-70 transition text-zinc-500 cursor-pointer"
                    >
                      <td className="py-2.5 px-3 font-sans font-medium text-zinc-400 whitespace-nowrap">
                        {cell.monthName}
                      </td>
                      <td className="py-2.5 px-3 font-sans whitespace-nowrap">
                        <span className="inline-flex items-center text-[10px] text-zinc-500 bg-zinc-800/30 px-2 py-0.5 rounded">
                          {t('expenses.annual.noDataBadge')}
                        </span>
                      </td>
                      <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                      <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                      <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                      <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                      <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                      <td className="py-2.5 px-3 whitespace-nowrap">—</td>
                      <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">—</td>
                      <td className="py-2.5 px-3 text-right font-sans whitespace-nowrap">
                        <span className="text-[11px] text-zinc-600">—</span>
                      </td>
                    </tr>
                  );
                }

                const topCatMeta = pt.topCategory ? getCategoryMeta(pt.topCategory) : null;

                return (
                  <tr
                    key={cell.monthKey}
                    onClick={() => onSelectMonth(cell.monthIndex)}
                    className={`cursor-pointer transition-colors ${
                      isSelected
                        ? 'bg-zinc-800/60 hover:bg-zinc-800/80'
                        : 'hover:bg-zinc-800/30'
                    }`}
                  >
                    <td className="py-2.5 px-3 font-sans font-medium text-zinc-200 whitespace-nowrap">
                      <div className="flex items-center gap-2">
                        <span
                          className={`w-1.5 h-1.5 rounded-full shrink-0 ${
                            pt.netCashflow >= 0 ? 'bg-emerald-400' : 'bg-rose-400'
                          }`}
                        />
                        <span>{cell.monthName}</span>
                      </div>
                    </td>
                    <td className="py-2.5 px-3 font-sans whitespace-nowrap">
                      <span className="inline-flex items-center text-[10px] font-mono text-emerald-300 bg-emerald-500/10 border border-emerald-500/20 px-2 py-0.5 rounded">
                        {t('expenses.annual.txCountBadge', { count: pt.transactionCount })}
                      </span>
                    </td>
                    <td className="py-2.5 px-3 text-right text-emerald-400 tabular-nums whitespace-nowrap">
                      {formatExpenseCurrency(pt.income, false, currency, locale)}
                    </td>
                    <td className="py-2.5 px-3 text-right text-zinc-100 font-medium tabular-nums whitespace-nowrap">
                      {formatExpenseCurrency(pt.expenses, false, currency, locale)}
                    </td>
                    <td className="py-2.5 px-3 text-right text-sky-400 tabular-nums whitespace-nowrap">
                      {formatExpenseCurrency(pt.investmentsSavings, false, currency, locale)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${getValueColorClass(
                        pt.netCashflow
                      )}`}
                    >
                      {formatExpenseCurrency(pt.netCashflow, true, currency, locale)}
                    </td>
                    <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">
                      {pt.savingsRatePct !== null ? (
                        <span
                          className={
                            pt.savingsRatePct >= 20
                              ? 'text-emerald-400 font-semibold'
                              : pt.savingsRatePct >= 0
                              ? 'text-amber-300'
                              : 'text-rose-400 font-semibold'
                          }
                        >
                          {formatPercent(pt.savingsRatePct, false, 1, locale)}
                        </span>
                      ) : (
                        <span className="text-zinc-500">—</span>
                      )}
                    </td>
                    <td className="py-2.5 px-3 font-sans whitespace-nowrap">
                      {topCatMeta ? (
                        <div className="inline-flex items-center gap-1.5">
                          <span
                            className="w-2 h-2 rounded-full shrink-0"
                            style={{ backgroundColor: topCatMeta.color }}
                          />
                          <span className="text-zinc-200">{t(topCatMeta.labelKey)}</span>
                          <span className="text-zinc-400 font-mono text-[11px]">
                            ({formatExpenseCurrency(pt.topCategoryAmount, false, currency, locale)})
                          </span>
                        </div>
                      ) : (
                        <span className="text-zinc-500">—</span>
                      )}
                    </td>
                    <td className="py-2.5 px-3 text-right tabular-nums whitespace-nowrap">
                      {pt.momExpensesChangePct !== null ? (
                        <span
                          className={
                            pt.momExpensesChangePct > 0
                              ? 'text-rose-400'
                              : pt.momExpensesChangePct < 0
                              ? 'text-emerald-400'
                              : 'text-zinc-400'
                          }
                        >
                          {formatPercent(pt.momExpensesChangePct, true, 1, locale)}
                        </span>
                      ) : (
                        <span className="text-zinc-500">—</span>
                      )}
                    </td>
                    <td className="py-2.5 px-3 text-right font-sans whitespace-nowrap">
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelectMonth(cell.monthIndex);
                        }}
                        className="text-[11px] text-zinc-300 hover:text-emerald-400 hover:bg-zinc-800 px-2.5 py-1 rounded transition cursor-pointer"
                      >
                        {t('expenses.annual.openMonth')}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>

            <tfoot className="bg-[#0b0c10] border-t-2 border-zinc-700/80 font-mono text-xs">
              <tr className="font-semibold text-zinc-200">
                <td className="py-3 px-3 font-sans text-xs sm:text-sm text-zinc-100 whitespace-nowrap">
                  {t('expenses.annual.ytdTotal', { year: grid.year })}
                </td>
                <td className="py-3 px-3 font-sans text-[11px] text-zinc-400 whitespace-nowrap">
                  {t('expenses.annual.txCountBadge', { count: grid.totals.transactionCount })}
                </td>
                <td className="py-3 px-3 text-right text-emerald-400 tabular-nums whitespace-nowrap">
                  {formatExpenseCurrency(grid.totals.income, false, currency, locale)}
                </td>
                <td className="py-3 px-3 text-right text-zinc-100 tabular-nums whitespace-nowrap">
                  {formatExpenseCurrency(grid.totals.expenses, false, currency, locale)}
                </td>
                <td className="py-3 px-3 text-right text-sky-400 tabular-nums whitespace-nowrap">
                  {formatExpenseCurrency(grid.totals.investmentsSavings, false, currency, locale)}
                </td>
                <td
                  className={`py-3 px-3 text-right text-xs sm:text-sm tabular-nums whitespace-nowrap ${getValueColorClass(
                    grid.totals.netCashflow
                  )}`}
                >
                  {formatExpenseCurrency(grid.totals.netCashflow, true, currency, locale)}
                </td>
                <td className="py-3 px-3 text-right tabular-nums whitespace-nowrap">
                  {grid.totals.savingsRatePct !== null
                    ? formatPercent(grid.totals.savingsRatePct, false, 1, locale)
                    : '—'}
                </td>
                <td className="py-3 px-3 whitespace-nowrap" />
                <td className="py-3 px-3 whitespace-nowrap" />
                <td className="py-3 px-3 text-right font-sans text-[11px] whitespace-nowrap">
                  {grid.totals.netCashflow >= 0 ? (
                    <span className="inline-flex items-center gap-0.5 text-emerald-400">
                      <ArrowUpRight className="w-3.5 h-3.5" />
                      {t('reports.inProfit')}
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-0.5 text-rose-400">
                      <ArrowDownRight className="w-3.5 h-3.5" />
                      {t('reports.inLoss')}
                    </span>
                  )}
                </td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>
    </div>
  );
};
