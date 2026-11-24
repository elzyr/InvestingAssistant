import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { MonthlyTrendPoint } from '../../types/expense.types.ts';
import { formatExpenseCurrency } from '../../utils/expenseFormatters.ts';
import { formatPercent, getValueColorClass } from '../../utils/reportsFormatters.ts';

export interface TrendChartBarItem {
  key: string;
  label: string;
  hoverLabel: string;
  hasData: boolean;
  point: MonthlyTrendPoint | null;
  monthIndex?: number;
}

interface ExpenseMonthlyTrendChartProps {
  points?: MonthlyTrendPoint[];
  bars?: TrendChartBarItem[];
  title?: string;
  subtitle?: string;
  currency: string;
  selectedMonthIndex?: number | null;
  onSelectMonth?: (monthIndex: number) => void;
}

export const ExpenseMonthlyTrendChart: React.FC<ExpenseMonthlyTrendChartProps> = ({
  points = [],
  bars,
  title,
  subtitle,
  currency,
  selectedMonthIndex = null,
  onSelectMonth,
}) => {
  const { t, i18n } = useTranslation();
  const locale = i18n.language.startsWith('pl') ? 'pl-PL' : 'en-US';
  const [hoveredKey, setHoveredKey] = useState<string | null>(null);

  const items: TrendChartBarItem[] =
    bars ??
    points.slice(-12).map((p) => ({
      key: p.month,
      label: p.month.slice(2),
      hoverLabel: p.month,
      hasData: true,
      point: p,
    }));

  const maxValue = items.reduce((max, item) => {
    if (!item.point) return max;
    return Math.max(
      max,
      item.point.income,
      item.point.expenses,
      item.point.investmentsSavings
    );
  }, 0);

  const activeItem = hoveredKey
    ? items.find((item) => item.key === hoveredKey && item.hasData && item.point) ?? null
    : null;

  return (
    <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-4 sm:p-5 flex flex-col justify-between">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-3">
        <div>
          <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
            {title ?? t('expenses.trend.title')}
          </span>
          <p className="text-xs text-zinc-500 mt-0.5">
            {subtitle ?? t('expenses.trend.subtitle')}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3 text-[11px] font-mono text-zinc-400">
          <span className="inline-flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm bg-emerald-500" />
            {t('expenses.trend.income')}
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm bg-rose-500" />
            {t('expenses.trend.expenses')}
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm bg-sky-500" />
            {t('expenses.trend.investments')}
          </span>
        </div>
      </div>

      {items.length === 0 || (maxValue <= 0 && !bars) ? (
        <div className="py-10 text-center text-sm text-zinc-500">
          {t('expenses.trend.noData')}
        </div>
      ) : (
        <div className="space-y-3">
          <div className="min-h-[28px] flex items-center justify-between text-xs font-mono px-2.5 py-1 rounded bg-zinc-950/60 border border-zinc-800/60">
            {activeItem?.point ? (
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 w-full">
                <span className="font-sans font-semibold text-zinc-200">
                  {activeItem.hoverLabel}
                </span>
                <span className="text-emerald-400">
                  +{formatExpenseCurrency(activeItem.point.income, false, currency, locale)}
                </span>
                <span className="text-rose-400">
                  -{formatExpenseCurrency(activeItem.point.expenses, false, currency, locale)}
                </span>
                <span className={getValueColorClass(activeItem.point.netCashflow)}>
                  {t('expenses.kpis.netCashflow')}:{' '}
                  {formatExpenseCurrency(activeItem.point.netCashflow, true, currency, locale)}
                </span>
                {activeItem.point.savingsRatePct !== null && (
                  <span className="text-zinc-400">
                    ({formatPercent(activeItem.point.savingsRatePct, false, 1, locale)})
                  </span>
                )}
              </div>
            ) : (
              <span className="text-zinc-500 text-[11px]">{t('reports.hoverSegment')}</span>
            )}
          </div>

          <div className="overflow-x-auto">
            <div className="h-44 flex items-end gap-1.5 sm:gap-2 min-w-[540px] pt-2">
              {items.map((item) => {
                const pt = item.point;
                const incH = pt && maxValue > 0 ? Math.max(3, (pt.income / maxValue) * 100) : 0;
                const expH = pt && maxValue > 0 ? Math.max(3, (pt.expenses / maxValue) * 100) : 0;
                const invH =
                  pt && maxValue > 0
                    ? Math.max(3, (pt.investmentsSavings / maxValue) * 100)
                    : 0;
                const isSelected =
                  item.monthIndex !== undefined && selectedMonthIndex === item.monthIndex;
                const isInteractive = item.monthIndex !== undefined && Boolean(onSelectMonth);

                const barContent = (
                  <>
                    <div className="w-full flex-1 flex items-end justify-center gap-1">
                      <div
                        className="w-2 sm:w-2.5 rounded-t bg-emerald-500/85 transition-all duration-300"
                        style={{
                          height: pt && pt.income > 0 ? `${incH}%` : '2px',
                          opacity: pt && pt.income > 0 ? 1 : 0.2,
                        }}
                      />
                      <div
                        className="w-2 sm:w-2.5 rounded-t bg-rose-500/85 transition-all duration-300"
                        style={{
                          height: pt && pt.expenses > 0 ? `${expH}%` : '2px',
                          opacity: pt && pt.expenses > 0 ? 1 : 0.2,
                        }}
                      />
                      <div
                        className="w-2 sm:w-2.5 rounded-t bg-sky-500/85 transition-all duration-300"
                        style={{
                          height: pt && pt.investmentsSavings > 0 ? `${invH}%` : '2px',
                          opacity: pt && pt.investmentsSavings > 0 ? 1 : 0.2,
                        }}
                      />
                    </div>
                    <span
                      className={`text-[11px] font-sans font-semibold ${
                        isSelected ? 'text-emerald-300' : 'text-zinc-400'
                      }`}
                    >
                      {item.label}
                    </span>
                  </>
                );

                if (isInteractive) {
                  return (
                    <button
                      type="button"
                      key={item.key}
                      onMouseEnter={() => setHoveredKey(item.key)}
                      onMouseLeave={() => setHoveredKey(null)}
                      onClick={() => onSelectMonth?.(item.monthIndex!)}
                      className={`flex-1 h-full flex flex-col items-center justify-end gap-1.5 px-1 py-1 rounded-lg transition cursor-pointer ${
                        isSelected
                          ? 'bg-emerald-500/10 ring-1 ring-emerald-500/40'
                          : item.hasData
                          ? 'hover:bg-zinc-800/50'
                          : 'opacity-45 hover:opacity-75'
                      }`}
                    >
                      {barContent}
                    </button>
                  );
                }

                return (
                  <div
                    key={item.key}
                    onMouseEnter={() => setHoveredKey(item.key)}
                    onMouseLeave={() => setHoveredKey(null)}
                    className={`flex-1 h-full flex flex-col items-center justify-end gap-1.5 px-1 py-1 rounded-lg transition ${
                      hoveredKey === item.key ? 'bg-zinc-800/40' : ''
                    }`}
                  >
                    {barContent}
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
