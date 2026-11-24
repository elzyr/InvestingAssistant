import React, { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Search, Pencil, Loader2, Repeat, AlertCircle, EyeOff } from 'lucide-react';
import type {
  ExpenseCategory,
  ExpenseTransactionResponse,
  FlowKind,
  UpdateExpenseTransactionRequest,
} from '../../types/expense.types.ts';
import {
  ALL_EXPENSE_CATEGORIES,
  filterExpenseTransactions,
  formatExpenseCurrency,
  getCategoryMeta,
  getSelectableCategoriesForAmount,
  isFlowCompatibleWithAmount,
  resolveCategoryFlowKind,
  shouldAutoRememberRule,
} from '../../utils/expenseFormatters.ts';
import { Modal } from '../common/Modal.tsx';

interface ExpenseTransactionsTableProps {
  transactions: ExpenseTransactionResponse[];
  selectedCategory: ExpenseCategory | '';
  onSelectCategory: (category: ExpenseCategory | '') => void;
  onUpdateTransaction: (
    txId: string,
    payload: UpdateExpenseTransactionRequest
  ) => Promise<void>;
}

const FLOW_OPTIONS: Array<FlowKind | 'ALL'> = [
  'ALL',
  'EXPENSE',
  'INCOME',
  'INVESTMENT_SAVING',
  'INTERNAL_TRANSFER',
  'REFUND',
];

export const ExpenseTransactionsTable: React.FC<ExpenseTransactionsTableProps> = ({
  transactions,
  selectedCategory,
  onSelectCategory,
  onUpdateTransaction,
}) => {
  const { t, i18n } = useTranslation();
  const locale = i18n.language.startsWith('pl') ? 'pl-PL' : 'en-US';

  const [search, setSearch] = useState('');
  const [flowFilter, setFlowFilter] = useState<FlowKind | 'ALL'>('ALL');
  const [needsReviewOnly, setNeedsReviewOnly] = useState(false);
  const [hideExcluded, setHideExcluded] = useState(false);
  const [visibleLimit, setVisibleLimit] = useState(100);

  const [editingTx, setEditingTx] = useState<ExpenseTransactionResponse | null>(null);
  const [editCategory, setEditCategory] = useState<ExpenseCategory>('OTHER_EXPENSE');
  const [editExcluded, setEditExcluded] = useState(false);
  const [editNote, setEditNote] = useState('');
  const [editRememberRule, setEditRememberRule] = useState(false);
  const [editApplyMatching, setEditApplyMatching] = useState(false);
  const [saving, setSaving] = useState(false);

  const filtered = useMemo(
    () =>
      filterExpenseTransactions(transactions, {
        search,
        category: selectedCategory || 'ALL',
        flowKind: flowFilter,
        needsReviewOnly,
        hideExcluded,
      }),
    [transactions, search, selectedCategory, flowFilter, needsReviewOnly, hideExcluded]
  );

  const visibleRows = filtered.slice(0, visibleLimit);
  const remainingCount = Math.max(0, filtered.length - visibleRows.length);

  const openEdit = (tx: ExpenseTransactionResponse) => {
    const selectable = getSelectableCategoriesForAmount(tx.amount);
    setEditingTx(tx);
    setEditCategory(selectable.includes(tx.category) ? tx.category : 'OTHER_EXPENSE');
    setEditExcluded(tx.excluded);
    setEditNote(tx.userNote ?? '');
    setEditRememberRule(false);
    setEditApplyMatching(false);
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingTx) return;
    setSaving(true);
    try {
      const nextFlowKind =
        editCategory === editingTx.category &&
        isFlowCompatibleWithAmount(editingTx.flowKind, editingTx.amount)
          ? editingTx.flowKind
          : resolveCategoryFlowKind(editCategory, editingTx.amount);
      await onUpdateTransaction(editingTx.id, {
        category: editCategory,
        flowKind: nextFlowKind,
        excluded: editExcluded,
        userNote: editNote.trim(),
        rememberRule: editRememberRule,
        applyToMatching: editApplyMatching,
      });
      setEditingTx(null);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-5 space-y-4">
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-zinc-100">{t('expenses.table.title')}</h3>
          <span className="text-xs font-mono text-zinc-500">
            ({filtered.length}/{transactions.length})
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <Search className="w-3.5 h-3.5 text-zinc-500 absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t('expenses.table.searchPlaceholder')}
              className="bg-zinc-950 border border-zinc-800 text-zinc-200 text-xs rounded-lg pl-8 pr-3 py-1.5 w-52 focus:outline-none focus:border-emerald-500"
            />
          </div>

          <select
            value={selectedCategory}
            onChange={(e) => onSelectCategory(e.target.value as ExpenseCategory | '')}
            className="bg-zinc-950 border border-zinc-800 text-zinc-200 text-xs rounded-lg px-2.5 py-1.5 focus:outline-none focus:border-emerald-500"
          >
            <option value="">{t('expenses.table.allCategories')}</option>
            {ALL_EXPENSE_CATEGORIES.map((cat) => (
              <option key={cat} value={cat}>
                {t(getCategoryMeta(cat).labelKey)}
              </option>
            ))}
          </select>

          <select
            value={flowFilter}
            onChange={(e) => setFlowFilter(e.target.value as FlowKind | 'ALL')}
            className="bg-zinc-950 border border-zinc-800 text-zinc-200 text-xs rounded-lg px-2.5 py-1.5 focus:outline-none focus:border-emerald-500"
          >
            {FLOW_OPTIONS.map((fk) => (
              <option key={fk} value={fk}>
                {t(`expenses.flows.${fk}`)}
              </option>
            ))}
          </select>

          <label className="inline-flex items-center gap-1.5 text-xs text-zinc-300 px-2.5 py-1.5 rounded-lg bg-zinc-950 border border-zinc-800 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={needsReviewOnly}
              onChange={(e) => setNeedsReviewOnly(e.target.checked)}
              className="accent-emerald-500"
            />
            <span>{t('expenses.table.needsReviewOnly')}</span>
          </label>

          <label className="inline-flex items-center gap-1.5 text-xs text-zinc-300 px-2.5 py-1.5 rounded-lg bg-zinc-950 border border-zinc-800 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={hideExcluded}
              onChange={(e) => setHideExcluded(e.target.checked)}
              className="accent-emerald-500"
            />
            <span>{t('expenses.table.hideExcluded')}</span>
          </label>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-left border-collapse text-xs">
          <thead>
            <tr className="border-b border-zinc-800 text-zinc-500 uppercase tracking-wider text-[10px]">
              <th className="py-2.5 pr-3">{t('expenses.table.colDate')}</th>
              <th className="py-2.5 pr-3">{t('expenses.table.colMerchant')}</th>
              <th className="py-2.5 pr-3">{t('expenses.table.colCategory')}</th>
              <th className="py-2.5 pr-3 text-right">{t('expenses.table.colAmount')}</th>
              <th className="py-2.5 text-right">{t('expenses.table.colActions')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-800/60">
            {visibleRows.length === 0 ? (
              <tr>
                <td colSpan={5} className="py-8 text-center text-zinc-500">
                  {t('expenses.table.empty')}
                </td>
              </tr>
            ) : (
              visibleRows.map((tx) => {
                const meta = getCategoryMeta(tx.category);
                const amountPositive = tx.baseAmount > 0;
                const multiCurrency = tx.currency !== tx.baseCurrency;

                return (
                  <tr
                    key={tx.id}
                    className={`hover:bg-zinc-800/30 transition ${
                      tx.excluded ? 'opacity-50' : ''
                    }`}
                  >
                    <td className="py-2.5 pr-3 font-mono text-zinc-400 whitespace-nowrap align-top">
                      {tx.txDate}
                    </td>
                    <td className="py-2.5 pr-3 align-top max-w-md">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="font-semibold text-zinc-200">{tx.merchantDisplay}</span>
                        {tx.isRecurring && (
                          <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-indigo-500/15 text-indigo-300 text-[10px]">
                            <Repeat className="w-2.5 h-2.5" />
                            {t('expenses.table.recurringBadge')}
                          </span>
                        )}
                        {tx.needsReview && !tx.excluded && (
                          <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-300 text-[10px]">
                            <AlertCircle className="w-2.5 h-2.5" />
                            {t('expenses.table.reviewBadge')}
                          </span>
                        )}
                        {tx.excluded && (
                          <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-400 text-[10px]">
                            <EyeOff className="w-2.5 h-2.5" />
                            {t('expenses.table.excludedBadge')}
                          </span>
                        )}
                        {tx.fxDegraded && (
                          <span className="px-1.5 py-0.5 rounded bg-rose-500/15 text-rose-300 text-[10px] font-mono">
                            {t('expenses.table.fxDegradedBadge')}
                          </span>
                        )}
                      </div>
                      <p className="text-[11px] text-zinc-500 truncate mt-0.5" title={tx.rawDescription}>
                        {tx.rawDescription}
                      </p>
                      {tx.userNote && (
                        <p className="text-[11px] text-emerald-400/90 mt-0.5">{tx.userNote}</p>
                      )}
                    </td>
                    <td className="py-2.5 pr-3 align-top whitespace-nowrap">
                      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-zinc-950 border border-zinc-800 text-zinc-200">
                        <span
                          className="w-2 h-2 rounded-full"
                          style={{ backgroundColor: meta.color }}
                        />
                        <span>{t(meta.labelKey)}</span>
                      </span>
                    </td>
                    <td className="py-2.5 pr-3 text-right font-mono tabular-nums align-top whitespace-nowrap">
                      <div
                        className={`font-semibold ${
                          amountPositive ? 'text-emerald-400' : 'text-zinc-100'
                        }`}
                      >
                        {formatExpenseCurrency(tx.baseAmount, true, tx.baseCurrency, locale)}
                      </div>
                      {multiCurrency && (
                        <div className="text-[10px] text-zinc-500">
                          {formatExpenseCurrency(tx.amount, true, tx.currency, locale)}
                        </div>
                      )}
                    </td>
                    <td className="py-2.5 text-right align-top">
                      <button
                        type="button"
                        onClick={() => openEdit(tx)}
                        className="p-1.5 rounded-lg text-zinc-400 hover:text-emerald-300 hover:bg-zinc-800 transition cursor-pointer"
                        title={t('expenses.table.editTitle')}
                      >
                        <Pencil className="w-3.5 h-3.5" />
                      </button>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {remainingCount > 0 && (
        <div className="flex justify-center pt-1">
          <button
            type="button"
            onClick={() => setVisibleLimit((n) => n + 100)}
            className="px-4 py-1.5 rounded-lg bg-zinc-800 hover:bg-zinc-700 border border-zinc-700/70 text-xs font-medium text-zinc-200 transition cursor-pointer"
          >
            {t('expenses.table.showMore', { remaining: remainingCount })}
          </button>
        </div>
      )}

      <Modal
        isOpen={Boolean(editingTx)}
        onClose={() => setEditingTx(null)}
        title={<span className="text-sm font-semibold text-zinc-100">{t('expenses.table.editTitle')}</span>}
        subtitle={editingTx ? `${editingTx.txDate} • ${editingTx.merchantDisplay}` : undefined}
      >
        {editingTx && (
          <form onSubmit={handleSave} className="p-5 space-y-4 text-xs">
            <div className="p-2.5 rounded-lg bg-zinc-950 border border-zinc-800 text-zinc-400 break-words">
              {editingTx.rawDescription}
            </div>

            <div className="space-y-1.5">
              <label className="block text-zinc-300 font-medium">
                {t('expenses.table.categoryLabel')}
              </label>
              <select
                value={editCategory}
                onChange={(e) => {
                  const nextCat = e.target.value as ExpenseCategory;
                  setEditCategory(nextCat);
                  if (editingTx) {
                    setEditRememberRule(
                      shouldAutoRememberRule(nextCat, editingTx.category, editingTx.amount)
                    );
                  }
                }}
                className="w-full bg-zinc-950 border border-zinc-800 text-zinc-100 rounded-lg px-3 py-2 focus:outline-none focus:border-emerald-500"
              >
                {getSelectableCategoriesForAmount(editingTx.amount).map((cat) => (
                  <option key={cat} value={cat}>
                    {t(getCategoryMeta(cat).labelKey)}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-1.5">
              <label className="block text-zinc-300 font-medium">
                {t('expenses.table.noteLabel')}
              </label>
              <input
                type="text"
                maxLength={255}
                value={editNote}
                onChange={(e) => setEditNote(e.target.value)}
                placeholder={t('expenses.table.notePlaceholder')}
                className="w-full bg-zinc-950 border border-zinc-800 text-zinc-100 rounded-lg px-3 py-2 focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div className="space-y-2 pt-1">
              <label className="flex items-center gap-2 text-zinc-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={editExcluded}
                  onChange={(e) => setEditExcluded(e.target.checked)}
                  className="accent-emerald-500"
                />
                <span>{t('expenses.table.excludedLabel')}</span>
              </label>
              <label className="flex items-center gap-2 text-zinc-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={editRememberRule}
                  onChange={(e) => setEditRememberRule(e.target.checked)}
                  className="accent-emerald-500"
                />
                <span>{t('expenses.table.rememberRuleLabel')}</span>
              </label>
              <label className="flex items-center gap-2 text-zinc-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={editApplyMatching}
                  onChange={(e) => setEditApplyMatching(e.target.checked)}
                  className="accent-emerald-500"
                />
                <span>{t('expenses.table.applyMatchingLabel')}</span>
              </label>
            </div>

            <div className="flex items-center justify-end gap-2 pt-3 border-t border-zinc-800">
              <button
                type="button"
                onClick={() => setEditingTx(null)}
                className="px-3.5 py-2 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-zinc-300 font-medium transition cursor-pointer"
              >
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                disabled={saving}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-semibold transition disabled:opacity-60 cursor-pointer"
              >
                {saving && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                <span>{t('expenses.table.saveChanges')}</span>
              </button>
            </div>
          </form>
        )}
      </Modal>
    </div>
  );
};
