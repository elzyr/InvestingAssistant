import React from 'react';
import { useTranslation } from 'react-i18next';
import {
  AlertTriangle,
  CheckCircle2,
  Info,
  Repeat,
  Flame,
} from 'lucide-react';
import type {
  ExpenseInsightDto,
  OutlierExpenseItem,
  RecurringChargeItem,
} from '../../types/expense.types.ts';
import {
  formatExpenseCurrency,
  formatInsightMessage,
  getCategoryMeta,
} from '../../utils/expenseFormatters.ts';

interface ExpenseSmartInsightsProps {
  insights: ExpenseInsightDto[];
  recurringCharges: RecurringChargeItem[];
  outliers: OutlierExpenseItem[];
  currency: string;
}

export const ExpenseSmartInsights: React.FC<ExpenseSmartInsightsProps> = ({
  insights,
  recurringCharges,
  outliers,
  currency,
}) => {
  const { t, i18n } = useTranslation();
  const locale = i18n.language.startsWith('pl') ? 'pl-PL' : 'en-US';

  const translateInsight = (insight: ExpenseInsightDto) => {
    const params: Record<string, unknown> = { ...(insight.params ?? {}) };
    if (typeof params.category === 'string') {
      params.category = t(getCategoryMeta(params.category).labelKey);
    }
    if (insight.code === 'CONVENIENCE_DRAIN' && typeof params.merchant === 'string') {
      params.merchant = params.merchant ? ` (${params.merchant})` : '';
    }
    return formatInsightMessage({ ...insight, params }, t);
  };

  return (
    <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-5 space-y-5">
      <div>
        <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
          {t('expenses.insightsPanel.title')}
        </span>
        <p className="text-xs text-zinc-500 mt-0.5">{t('expenses.insightsPanel.subtitle')}</p>
      </div>

      {insights.length === 0 ? (
        <p className="text-xs text-zinc-500">{t('expenses.insightsPanel.noInsights')}</p>
      ) : (
        <div className="space-y-2">
          {insights.map((insight, idx) => {
            const isWarn = insight.severity === 'WARN';
            const isSuccess = insight.severity === 'SUCCESS';
            const Icon = isWarn ? AlertTriangle : isSuccess ? CheckCircle2 : Info;
            const boxClass = isWarn
              ? 'bg-amber-950/30 border-amber-800/50 text-amber-200'
              : isSuccess
              ? 'bg-emerald-950/30 border-emerald-800/50 text-emerald-200'
              : 'bg-sky-950/30 border-sky-800/50 text-sky-200';
            const iconClass = isWarn
              ? 'text-amber-400'
              : isSuccess
              ? 'text-emerald-400'
              : 'text-sky-400';

            return (
              <div
                key={`${insight.code}-${idx}`}
                className={`flex items-start gap-2.5 p-3 rounded-lg border text-xs ${boxClass}`}
              >
                <Icon className={`w-4 h-4 shrink-0 mt-0.5 ${iconClass}`} />
                <span className="leading-relaxed">{translateInsight(insight)}</span>
              </div>
            );
          })}
        </div>
      )}

      {recurringCharges.length > 0 && (
        <div className="pt-4 border-t border-zinc-800/80 space-y-2">
          <div className="flex items-center gap-1.5 text-xs font-semibold text-zinc-300">
            <Repeat className="w-3.5 h-3.5 text-indigo-400" />
            <span>{t('expenses.insightsPanel.recurringTitle')}</span>
          </div>
          <div className="space-y-1.5">
            {recurringCharges.map((item) => (
              <div
                key={item.merchantKey}
                className="flex items-center justify-between gap-2 px-3 py-2 rounded-lg bg-zinc-950/50 border border-zinc-800/60 text-xs"
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-zinc-200 truncate">
                      {item.merchantDisplay}
                    </span>
                    {item.priceIncreasePct !== null && item.priceIncreasePct >= 5 && (
                      <span className="px-1.5 py-0.5 rounded bg-rose-500/15 border border-rose-500/30 text-[10px] font-mono text-rose-300">
                        {t('expenses.insightsPanel.priceHike', { pct: item.priceIncreasePct })}
                      </span>
                    )}
                  </div>
                  <p className="text-[10px] text-zinc-500 font-mono mt-0.5">
                    {t(getCategoryMeta(item.category).labelKey)} &bull; {item.lastChargeDate}
                  </p>
                </div>
                <div className="text-right font-mono tabular-nums shrink-0">
                  <div className="text-zinc-100 font-semibold">
                    {formatExpenseCurrency(item.latestAmount, false, currency, locale)}
                  </div>
                  <div className="text-[10px] text-zinc-500">
                    {t('expenses.insightsPanel.annualized', {
                      amount: formatExpenseCurrency(item.annualizedCost, false, currency, locale),
                    })}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {outliers.length > 0 && (
        <div className="pt-4 border-t border-zinc-800/80 space-y-2">
          <div className="flex items-center gap-1.5 text-xs font-semibold text-zinc-300">
            <Flame className="w-3.5 h-3.5 text-amber-400" />
            <span>{t('expenses.insightsPanel.outliersTitle')}</span>
          </div>
          <div className="space-y-1.5">
            {outliers.slice(0, 5).map((item) => (
              <div
                key={item.transactionId}
                className="flex items-center justify-between gap-2 px-3 py-2 rounded-lg bg-zinc-950/50 border border-zinc-800/60 text-xs"
              >
                <div className="min-w-0">
                  <span className="font-medium text-zinc-200 truncate block">
                    {item.merchantDisplay}
                  </span>
                  <span className="text-[10px] text-zinc-500 font-mono">
                    {item.txDate} &bull; {t(getCategoryMeta(item.category).labelKey)}
                  </span>
                </div>
                <span className="font-mono font-semibold text-amber-300 tabular-nums shrink-0">
                  {formatExpenseCurrency(item.amount, false, currency, locale)}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
