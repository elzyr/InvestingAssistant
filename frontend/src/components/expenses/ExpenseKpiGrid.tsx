import React from 'react';
import { useTranslation } from 'react-i18next';
import {
  TrendingUp,
  TrendingDown,
  Wallet,
  PiggyBank,
  Layers,
  AlertTriangle,
} from 'lucide-react';
import type { ExpenseKpis } from '../../types/expense.types.ts';
import { formatExpenseCurrency } from '../../utils/expenseFormatters.ts';
import { formatPercent } from '../../utils/reportsFormatters.ts';

interface ExpenseKpiGridProps {
  kpis: ExpenseKpis;
  currency: string;
}

export const ExpenseKpiGrid: React.FC<ExpenseKpiGridProps> = ({ kpis, currency }) => {
  const { t, i18n } = useTranslation();
  const locale = i18n.language.startsWith('pl') ? 'pl-PL' : 'en-US';
  const netPositive = kpis.netCashflow >= 0;

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3">
        <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-4">
          <div className="flex items-center justify-between text-xs text-zinc-400 mb-2">
            <span>{t('expenses.kpis.income')}</span>
            <TrendingUp className="w-4 h-4 text-emerald-400" />
          </div>
          <div className="text-lg font-bold font-mono tabular-nums text-emerald-400">
            {formatExpenseCurrency(kpis.totalIncome, false, currency, locale)}
          </div>
          {kpis.totalRefundsNetted > 0 && (
            <p className="text-[11px] text-zinc-400 mt-1.5 font-mono">
              {t('expenses.kpis.refundsNetted')}{' '}
              <span className="text-teal-400">
                {formatExpenseCurrency(kpis.totalRefundsNetted, false, currency, locale)}
              </span>
            </p>
          )}
        </div>

        <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-4">
          <div className="flex items-center justify-between text-xs text-zinc-400 mb-2">
            <span>{t('expenses.kpis.consumption')}</span>
            <TrendingDown className="w-4 h-4 text-rose-400" />
          </div>
          <div className="text-lg font-bold font-mono tabular-nums text-zinc-100">
            {formatExpenseCurrency(kpis.totalExpenses, false, currency, locale)}
          </div>
          <div className="text-[11px] text-zinc-400 mt-1.5 flex flex-wrap gap-x-2 gap-y-0.5 font-mono">
            <span>
              {t('expenses.kpis.baseLiving')}{' '}
              <strong className="text-zinc-200">
                {formatExpenseCurrency(kpis.baseLivingExpenses, false, currency, locale)}
              </strong>
            </span>
            {kpis.oneOffOutliersTotal > 0 && (
              <span className="text-amber-400">
                {t('expenses.kpis.outliersNote')}{' '}
                {formatExpenseCurrency(kpis.oneOffOutliersTotal, false, currency, locale)}
              </span>
            )}
          </div>
        </div>

        <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-4">
          <div className="flex items-center justify-between text-xs text-zinc-400 mb-2">
            <span>{t('expenses.kpis.netCashflow')}</span>
            <Wallet className={`w-4 h-4 ${netPositive ? 'text-emerald-400' : 'text-rose-400'}`} />
          </div>
          <div
            className={`text-lg font-bold font-mono tabular-nums ${
              netPositive ? 'text-emerald-400' : 'text-rose-400'
            }`}
          >
            {formatExpenseCurrency(kpis.netCashflow, true, currency, locale)}
          </div>
          <p className="text-[11px] text-zinc-400 mt-1.5 font-mono">
            {t('expenses.kpis.savingsRate')}{' '}
            <span
              className={
                kpis.savingsRatePct === null
                  ? 'text-zinc-500'
                  : kpis.savingsRatePct >= 20
                  ? 'text-emerald-400 font-semibold'
                  : kpis.savingsRatePct >= 0
                  ? 'text-amber-300 font-semibold'
                  : 'text-rose-400 font-semibold'
              }
            >
              {kpis.savingsRatePct !== null
                ? formatPercent(kpis.savingsRatePct, false, 1, locale)
                : '—'}
            </span>
          </p>
        </div>

        <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-4">
          <div className="flex items-center justify-between text-xs text-zinc-400 mb-2">
            <span>{t('expenses.kpis.investments')}</span>
            <PiggyBank className="w-4 h-4 text-sky-400" />
          </div>
          <div className="text-lg font-bold font-mono tabular-nums text-sky-400">
            {formatExpenseCurrency(kpis.totalInvestmentsSavings, false, currency, locale)}
          </div>
          <p className="text-[11px] text-zinc-500 mt-1.5">
            {t('expenses.kpis.excludedFromConsumption')}
          </p>
        </div>

        <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-4">
          <div className="flex items-center justify-between text-xs text-zinc-400 mb-2">
            <span>{t('expenses.kpis.fixedVsVariable')}</span>
            <Layers className="w-4 h-4 text-indigo-400" />
          </div>
          <div className="flex items-baseline gap-2">
            <span className="text-lg font-bold font-mono tabular-nums text-zinc-100">
              {formatPercent(kpis.fixedSharePct, false, 0, locale)}
            </span>
            <span className="text-xs text-zinc-400">{t('expenses.kpis.fixed')}</span>
          </div>
          <div className="w-full h-1.5 bg-zinc-800 rounded-full overflow-hidden mt-2">
            <div
              className="h-full bg-indigo-500 rounded-full transition-all duration-300"
              style={{ width: `${Math.min(100, Math.max(0, kpis.fixedSharePct))}%` }}
            />
          </div>
          <div className="flex items-center justify-between text-[10px] text-zinc-500 font-mono mt-1">
            <span>{formatExpenseCurrency(kpis.fixedCostsTotal, false, currency, locale)}</span>
            <span>{formatExpenseCurrency(kpis.variableCostsTotal, false, currency, locale)}</span>
          </div>
        </div>
      </div>

      {(kpis.totalInternalTransfers > 0 || kpis.needsReviewCount > 0 || kpis.fxDegradedCount > 0) && (
        <div className="flex flex-wrap items-center gap-2 text-xs font-mono">
          {kpis.totalInternalTransfers > 0 && (
            <span className="px-2.5 py-1 rounded-lg bg-zinc-900 border border-zinc-800 text-zinc-400">
              {t('expenses.kpis.internalTransfers')}{' '}
              <strong className="text-zinc-200">
                {formatExpenseCurrency(kpis.totalInternalTransfers, false, currency, locale)}
              </strong>
            </span>
          )}
          {kpis.needsReviewCount > 0 && (
            <span className="px-2.5 py-1 rounded-lg bg-amber-950/40 border border-amber-800/50 text-amber-300 inline-flex items-center gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5" />
              {t('expenses.kpis.needsReview')} <strong>{kpis.needsReviewCount}</strong>
            </span>
          )}
          {kpis.fxDegradedCount > 0 && (
            <span className="px-2.5 py-1 rounded-lg bg-rose-950/40 border border-rose-800/50 text-rose-300 inline-flex items-center gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5" />
              {t('expenses.kpis.fxDegraded')} <strong>{kpis.fxDegradedCount}</strong>
            </span>
          )}
        </div>
      )}
    </div>
  );
};
