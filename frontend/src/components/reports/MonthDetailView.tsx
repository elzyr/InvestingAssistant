import React from 'react';
import { useTranslation } from 'react-i18next';
import type { MonthlyReportRecord } from '../../types/reports.types.ts';
import {
  formatCurrency,
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

interface MonthSummaryCardProps {
  label: string;
  icon: React.ReactNode;
  value: string;
  valueClassName?: string;
  subLabel: string;
  subValue: string;
  subValueClassName?: string;
}

const MonthSummaryCard: React.FC<MonthSummaryCardProps> = ({
  label,
  icon,
  value,
  valueClassName = 'text-zinc-100',
  subLabel,
  subValue,
  subValueClassName = 'text-zinc-300',
}) => (
  <div className="bg-zinc-900/60 border border-zinc-800/80 rounded-xl p-4 flex flex-col justify-between">
    <div className="flex items-center justify-between text-zinc-400">
      <span className="text-xs font-medium">{label}</span>
      {icon}
    </div>
    <div className="mt-2">
      <div className={`text-xl font-bold font-mono tabular-nums ${valueClassName}`}>
        {value}
      </div>
      <div className="text-[11px] text-zinc-400 mt-1 flex items-center gap-1 font-mono">
        <span>{subLabel}</span>
        <span className={`font-medium ${subValueClassName}`}>{subValue}</span>
      </div>
    </div>
  </div>
);

export const MonthDetailView: React.FC<MonthDetailViewProps> = ({
  record,
}) => {
  const { t } = useTranslation();
  if (record.status === 'FUTURE') {
    return (
      <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-12 text-center">
        <Calendar className="w-12 h-12 text-zinc-600 mx-auto mb-3" />
        <h3 className="text-base font-medium text-zinc-300">
          {t('reports.monthWaiting', { month: record.monthName, year: record.year })}
        </h3>
        <p className="text-xs text-zinc-500 mt-1 max-w-md mx-auto">
          {t('reports.monthWaitingDesc')}
        </p>
      </div>
    );
  }

  const currency = record.baseCurrency || 'PLN';

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <MonthSummaryCard
          label={t('reports.valuation')}
          icon={<Wallet className="w-4 h-4 text-emerald-400" />}
          value={formatCurrency(record.portfolioValue, false, currency)}
          subLabel={t('reports.invested')}
          subValue={formatCurrency(record.investedTotal, false, currency)}
        />
        <MonthSummaryCard
          label={t('reports.netProfitBelka')}
          icon={<ShieldCheck className="w-4 h-4 text-emerald-400" />}
          value={formatCurrency(record.profitNet, true, currency)}
          valueClassName={getValueColorClass(record.profitNet)}
          subLabel={t('reports.gross')}
          subValue={formatCurrency(record.profitGross, true, currency)}
        />
        <MonthSummaryCard
          label={t('reports.netReturnRate')}
          icon={<Percent className="w-4 h-4 text-cyan-400" />}
          value={formatPercent(record.returnNetPct, true)}
          valueClassName={getValueColorClass(record.returnNetPct)}
          subLabel={t('reports.gross')}
          subValue={formatPercent(record.returnGrossPct, true)}
        />
        <MonthSummaryCard
          label={t('reports.momProfit')}
          icon={<TrendingUp className="w-4 h-4 text-amber-400" />}
          value={formatCurrency(record.profitNetMom, true, currency)}
          valueClassName={getValueColorClass(record.profitNetMom)}
          subLabel={t('reports.momChange')}
          subValue={formatPercent(record.returnNetMomPct, true)}
          subValueClassName={getValueColorClass(record.returnNetMomPct)}
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        <div className="lg:col-span-8 bg-zinc-900/50 border border-zinc-800/80 rounded-xl overflow-hidden">
          <div className="p-4 sm:p-5 border-b border-zinc-800/80 flex items-center justify-between">
            <div>
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
                {t('reports.breakdownTitle')}
              </span>
              <h3 className="text-sm font-medium text-zinc-200 mt-0.5">
                {t('reports.breakdownSubtitle', { month: record.monthName, year: record.year })}
              </h3>
            </div>
            <span className="text-xs font-mono text-zinc-400 bg-zinc-800/40 px-2.5 py-1 rounded border border-zinc-700/40">
              {t('reports.positionsCount', { count: record.breakdown.length })}
            </span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full table-auto text-xs text-left">
              <thead className="bg-[#0e0f15] text-zinc-400 font-semibold uppercase tracking-wider text-[11px] border-b border-zinc-800/80">
                <tr>
                  <th className="py-2.5 px-3 text-left whitespace-nowrap">{t('reports.category')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.invested')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.valuation')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.grossProfit')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.netProfit')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.grossReturn')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.netReturn')}</th>
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
                      {formatCurrency(item.invested, false, currency)}
                    </td>
                    <td className="py-2.5 px-3 text-right font-medium text-zinc-100 tabular-nums whitespace-nowrap">
                      {formatCurrency(item.currentValue, false, currency)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                        item.profitGross
                      )}`}
                    >
                      {formatCurrency(item.profitGross, true, currency)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${getValueColorClass(
                        item.profitNet
                      )}`}
                    >
                      {formatCurrency(item.profitNet, true, currency)}
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

              <tfoot className="bg-[#0b0c10] border-t-2 border-zinc-700/80 font-mono text-xs">
                <tr className="font-semibold text-zinc-200">
                  <td className="py-3 px-3 font-sans text-xs sm:text-sm text-zinc-100 whitespace-nowrap">
                    {t('reports.total')}
                  </td>
                  <td className="py-3 px-3 text-right text-zinc-300 tabular-nums whitespace-nowrap">
                    {formatCurrency(record.investedTotal, false, currency)}
                  </td>
                  <td className="py-3 px-3 text-right text-zinc-100 text-xs sm:text-sm tabular-nums whitespace-nowrap">
                    {formatCurrency(record.portfolioValue, false, currency)}
                  </td>
                  <td
                    className={`py-3 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                      record.profitGross
                    )}`}
                  >
                    {formatCurrency(record.profitGross, true, currency)}
                  </td>
                  <td
                    className={`py-3 px-3 text-right text-xs sm:text-sm tabular-nums whitespace-nowrap ${getValueColorClass(
                      record.profitNet
                    )}`}
                  >
                    {formatCurrency(record.profitNet, true, currency)}
                  </td>
                  <td
                    className={`py-3 px-3 text-right tabular-nums whitespace-nowrap ${getValueColorClass(
                      record.returnGrossPct
                    )}`}
                  >
                    {formatPercent(record.returnGrossPct, true)}
                  </td>
                  <td
                    className={`py-3 px-3 text-right text-xs sm:text-sm tabular-nums whitespace-nowrap ${getValueColorClass(
                      record.returnNetPct
                    )}`}
                  >
                    {formatPercent(record.returnNetPct, true)}
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>
        </div>

        <div className="lg:col-span-4">
          <MonthDonutChart
            breakdown={record.breakdown}
            totalValue={record.portfolioValue}
            currency={currency}
          />
        </div>
      </div>

      {record.positions.length > 0 && (
        <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl overflow-hidden">
          <div className="p-4 sm:p-5 border-b border-zinc-800/80 flex items-center justify-between">
            <div>
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
                {t('reports.positionsBreakdownTitle')}
              </span>
              <h3 className="text-sm font-medium text-zinc-200 mt-0.5">
                {t('reports.positionsBreakdownSubtitle', { month: record.monthName, year: record.year })}
              </h3>
            </div>
            <span className="text-xs font-mono text-zinc-400 bg-zinc-800/40 px-2.5 py-1 rounded border border-zinc-700/40">
              {t('reports.positionsCount', { count: record.positions.length })}
            </span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full table-auto text-xs text-left">
              <thead className="bg-[#0e0f15] text-zinc-400 font-semibold uppercase tracking-wider text-[11px] border-b border-zinc-800/80">
                <tr>
                  <th className="py-2.5 px-3 text-left whitespace-nowrap">{t('reports.position')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.sharePct')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.invested')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.valuation')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.momGain')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.netProfit')}</th>
                  <th className="py-2.5 px-3 text-right whitespace-nowrap">{t('reports.netReturn')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-800/50 font-mono text-[11px] sm:text-xs">
                {record.positions.map((pos) => (
                  <tr key={pos.key} className="hover:bg-zinc-800/30 transition-colors">
                    <td className="py-2.5 px-3 font-sans whitespace-nowrap">
                      <div className="flex items-center gap-2">
                        <span
                          className="w-2.5 h-2.5 rounded-full shrink-0"
                          style={{ backgroundColor: pos.color }}
                        />
                        <div className="min-w-0">
                          <div className="font-semibold text-zinc-100 truncate">{pos.symbol}</div>
                          {pos.name && pos.name !== pos.symbol && (
                            <div className="text-[11px] text-zinc-400 truncate max-w-[220px]">{pos.name}</div>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="py-2.5 px-3 text-right text-zinc-400 tabular-nums whitespace-nowrap">
                      {formatPercent(pos.sharePct, false)}
                    </td>
                    <td className="py-2.5 px-3 text-right text-zinc-400 tabular-nums whitespace-nowrap">
                      {formatCurrency(pos.invested, false, currency)}
                    </td>
                    <td className="py-2.5 px-3 text-right font-medium text-zinc-100 tabular-nums whitespace-nowrap">
                      {formatCurrency(pos.currentValue, false, currency)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${getValueColorClass(
                        pos.profitNetMom
                      )}`}
                    >
                      {formatCurrency(pos.profitNetMom, true, currency)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${getValueColorClass(
                        pos.profitNet
                      )}`}
                    >
                      {formatCurrency(pos.profitNet, true, currency)}
                    </td>
                    <td
                      className={`py-2.5 px-3 text-right font-semibold tabular-nums whitespace-nowrap ${getValueColorClass(
                        pos.returnNetPct
                      )}`}
                    >
                      {formatPercent(pos.returnNetPct, true)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
};
