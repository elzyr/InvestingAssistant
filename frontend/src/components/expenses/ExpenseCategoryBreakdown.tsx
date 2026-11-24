import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  CategoryBreakdownItem,
  ExpenseCategory,
  MerchantSummaryItem,
} from '../../types/expense.types.ts';
import { formatExpenseCurrency, getCategoryMeta } from '../../utils/expenseFormatters.ts';
import { formatPercent } from '../../utils/reportsFormatters.ts';

interface ExpenseCategoryBreakdownProps {
  breakdown: CategoryBreakdownItem[];
  topMerchants: MerchantSummaryItem[];
  totalExpenses: number;
  currency: string;
  selectedCategory: ExpenseCategory | '';
  onSelectCategory: (category: ExpenseCategory | '') => void;
}

export const ExpenseCategoryBreakdown: React.FC<ExpenseCategoryBreakdownProps> = ({
  breakdown,
  topMerchants,
  totalExpenses,
  currency,
  selectedCategory,
  onSelectCategory,
}) => {
  const { t, i18n } = useTranslation();
  const locale = i18n.language.startsWith('pl') ? 'pl-PL' : 'en-US';
  const [hoveredCategory, setHoveredCategory] = useState<ExpenseCategory | null>(null);

  const activeItem = hoveredCategory
    ? breakdown.find((b) => b.category === hoveredCategory) ?? null
    : null;

  return (
    <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-5 flex flex-col justify-between gap-5">
      <div>
        <div className="flex items-center justify-between gap-2 mb-4">
          <div>
            <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
              {t('expenses.breakdown.title')}
            </span>
            <p className="text-xs text-zinc-500 mt-0.5">{t('expenses.breakdown.subtitle')}</p>
          </div>
          {selectedCategory && (
            <button
              type="button"
              onClick={() => onSelectCategory('')}
              className="text-xs text-emerald-400 hover:text-emerald-300 bg-emerald-500/10 border border-emerald-500/30 px-2.5 py-1 rounded-lg transition cursor-pointer"
            >
              {t('expenses.breakdown.clearFilter')}
            </button>
          )}
        </div>

        {breakdown.length === 0 || totalExpenses <= 0 ? (
          <div className="py-8 text-center text-sm text-zinc-500">
            {t('expenses.breakdown.noData')}
          </div>
        ) : (
          <>
            <div className="space-y-1.5 mb-4">
              <div className="h-4 w-full bg-zinc-950 rounded-md overflow-hidden flex p-0.5 gap-0.5 border border-zinc-800">
                {breakdown.map((item) => {
                  const meta = getCategoryMeta(item.category);
                  const isHovered = hoveredCategory === item.category;
                  const isSelected = selectedCategory === item.category;
                  return (
                    <button
                      type="button"
                      key={item.category}
                      style={{
                        width: `${Math.max(1.5, item.sharePct)}%`,
                        backgroundColor: meta.color,
                      }}
                      onMouseEnter={() => setHoveredCategory(item.category)}
                      onMouseLeave={() => setHoveredCategory(null)}
                      onClick={() =>
                        onSelectCategory(isSelected ? '' : item.category)
                      }
                      className={`h-full rounded-sm transition-all duration-200 cursor-pointer ${
                        isHovered || isSelected ? 'brightness-125 scale-y-110' : 'hover:brightness-110'
                      }`}
                      title={`${t(meta.labelKey)}: ${formatPercent(item.sharePct, false, 1, locale)} (${formatExpenseCurrency(item.amount, false, currency, locale)})`}
                    />
                  );
                })}
              </div>

              <div className="h-4 flex items-center justify-between text-[11px] font-mono">
                {activeItem ? (
                  <>
                    <span className="text-zinc-200 font-medium">
                      {t(getCategoryMeta(activeItem.category).labelKey)}
                    </span>
                    <span className="text-emerald-400 font-semibold">
                      {formatPercent(activeItem.sharePct, false, 1, locale)} &bull;{' '}
                      {formatExpenseCurrency(activeItem.amount, false, currency, locale)}
                    </span>
                  </>
                ) : (
                  <>
                    <span className="text-zinc-500">{t('reports.hoverSegment')}</span>
                    <span className="text-zinc-400">
                      {formatExpenseCurrency(totalExpenses, false, currency, locale)}
                    </span>
                  </>
                )}
              </div>
            </div>

            <div className="space-y-2 max-h-72 overflow-y-auto pr-1">
              {breakdown.map((item) => {
                const meta = getCategoryMeta(item.category);
                const isSelected = selectedCategory === item.category;
                const isHovered = hoveredCategory === item.category;
                return (
                  <button
                    type="button"
                    key={item.category}
                    onMouseEnter={() => setHoveredCategory(item.category)}
                    onMouseLeave={() => setHoveredCategory(null)}
                    onClick={() => onSelectCategory(isSelected ? '' : item.category)}
                    className={`w-full text-left p-2.5 rounded-lg border transition cursor-pointer ${
                      isSelected
                        ? 'bg-emerald-500/10 border-emerald-500/40'
                        : isHovered
                        ? 'bg-zinc-800/70 border-zinc-700'
                        : 'bg-zinc-950/40 border-zinc-800/60 hover:bg-zinc-800/30'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2 text-xs mb-1.5">
                      <div className="flex items-center gap-2 min-w-0">
                        <span
                          className="w-2.5 h-2.5 rounded-full shrink-0"
                          style={{ backgroundColor: meta.color }}
                        />
                        <span className="truncate font-medium text-zinc-200">
                          {t(meta.labelKey)}
                        </span>
                        <span className="text-[10px] text-zinc-500 font-mono">
                          ({item.transactionCount})
                        </span>
                      </div>
                      <div className="flex items-center gap-2 font-mono tabular-nums shrink-0">
                        {item.momChangePct !== null && Math.abs(item.momChangePct) >= 5 && (
                          <span
                            className={`text-[10px] px-1.5 py-0.5 rounded ${
                              item.momChangePct > 0
                                ? 'bg-rose-500/15 text-rose-300'
                                : 'bg-emerald-500/15 text-emerald-300'
                            }`}
                          >
                            {formatPercent(item.momChangePct, true, 0, locale)}
                          </span>
                        )}
                        <span className="text-zinc-300">
                          {formatExpenseCurrency(item.amount, false, currency, locale)}
                        </span>
                        <span className="text-zinc-100 font-semibold w-12 text-right">
                          {formatPercent(item.sharePct, false, 1, locale)}
                        </span>
                      </div>
                    </div>
                    <div className="w-full h-1 bg-zinc-800/70 rounded-full overflow-hidden">
                      <div
                        className="h-full rounded-full transition-all duration-300"
                        style={{
                          width: `${Math.min(100, Math.max(2, item.sharePct))}%`,
                          backgroundColor: meta.color,
                        }}
                      />
                    </div>
                  </button>
                );
              })}
            </div>
          </>
        )}
      </div>

      {topMerchants.length > 0 && (
        <div className="pt-4 border-t border-zinc-800/80">
          <h4 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider mb-2.5">
            {t('expenses.breakdown.topMerchantsTitle')}
          </h4>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {topMerchants.slice(0, 6).map((merchant) => {
              const meta = getCategoryMeta(merchant.category);
              return (
                <div
                  key={merchant.merchantKey}
                  className="flex items-center justify-between gap-2 px-3 py-2 rounded-lg bg-zinc-950/50 border border-zinc-800/60 text-xs"
                >
                  <div className="min-w-0">
                    <div className="flex items-center gap-1.5">
                      <span
                        className="w-2 h-2 rounded-full shrink-0"
                        style={{ backgroundColor: meta.color }}
                      />
                      <span className="font-medium text-zinc-200 truncate">
                        {merchant.merchantDisplay}
                      </span>
                    </div>
                    <p className="text-[10px] text-zinc-500 font-mono mt-0.5">
                      {t('expenses.breakdown.transactionsCount', {
                        count: merchant.transactionCount,
                      })}{' '}
                      &bull;{' '}
                      {t('expenses.breakdown.avgPerTx', {
                        amount: formatExpenseCurrency(merchant.averageAmount, false, currency, locale),
                      })}
                    </p>
                  </div>
                  <span className="font-mono font-semibold text-zinc-100 tabular-nums shrink-0">
                    {formatExpenseCurrency(merchant.totalAmount, false, currency, locale)}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};
