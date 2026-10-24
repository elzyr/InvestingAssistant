import React, { useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { X, Search, Pencil, Trash2, ArrowDownRight, ArrowUpRight, Loader2 } from 'lucide-react';
import { useAppDispatch } from '../../store/hooks.ts';
import { deleteTransaction } from '../../store/thunks/portfolio.thunk.ts';
import type { TransactionResponse } from '../../types/portfolio.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';

export interface TransactionHistoryModalProps {
  isOpen: boolean;
  onClose: () => void;
  portfolioId: string;
  transactions: TransactionResponse[];
  instruments: Record<string, InstrumentResponse>;
  baseCurrency: string;
  onEditTransaction: (tx: TransactionResponse) => void;
}

export const TransactionHistoryModal: React.FC<TransactionHistoryModalProps> = ({
  isOpen,
  onClose,
  portfolioId,
  transactions,
  instruments,
  baseCurrency,
  onEditTransaction,
}) => {
  const { t } = useTranslation();
  const dispatch = useAppDispatch();
  const [searchQuery, setSearchQuery] = useState('');
  const [filterType, setFilterType] = useState<'ALL' | 'BUY' | 'SELL'>('ALL');
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const filteredTransactions = useMemo(() => {
    return [...transactions]
      .sort((a, b) => new Date(b.executedAt).getTime() - new Date(a.executedAt).getTime())
      .filter((tx) => {
        if (filterType !== 'ALL' && tx.transactionType !== filterType) {
          return false;
        }
        if (!searchQuery.trim()) {
          return true;
        }
        const query = searchQuery.toLowerCase().trim();
        const inst = instruments[tx.instrumentId];
        const symbol = inst?.symbol?.toLowerCase() || '';
        const name = inst?.name?.toLowerCase() || '';
        const series = tx.seriesName?.toLowerCase() || '';
        return symbol.includes(query) || name.includes(query) || series.includes(query);
      });
  }, [transactions, filterType, searchQuery, instruments]);

  if (!isOpen) return null;

  const handleDelete = async (txId: string) => {
    const confirmed = window.confirm(t('transactionHistory.confirmDelete'));
    if (!confirmed) return;

    setDeletingId(txId);
    setDeleteError(null);
    try {
      await dispatch(deleteTransaction({ portfolioId, transactionId: txId })).unwrap();
    } catch (err: unknown) {
      setDeleteError(typeof err === 'string' ? err : t('transactionHistory.deleteError'));
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-xs">
      <div className="relative w-full max-w-5xl max-h-[90vh] bg-[#10121a] border border-zinc-800 rounded-2xl shadow-2xl flex flex-col overflow-hidden">
        <div className="flex items-center justify-between px-6 py-4 border-b border-zinc-800/80 bg-[#141722]">
          <div>
            <h2 className="text-lg font-bold text-white tracking-tight">
              {t('transactionHistory.title')}
            </h2>
            <p className="text-xs text-zinc-400 mt-0.5">
              {t('transactionHistory.subtitle')} ({transactions.length})
            </p>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-zinc-400 hover:text-white hover:bg-zinc-800 transition cursor-pointer"
            title={t('transactionHistory.close')}
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {deleteError && (
          <div className="mx-6 mt-4 p-2.5 rounded bg-rose-950/40 border border-rose-800/60 text-xs text-rose-300">
            {deleteError}
          </div>
        )}

        <div className="p-4 border-b border-zinc-800/80 flex flex-wrap items-center justify-between gap-3 bg-[#0d0f17]">
          <div className="relative flex-1 min-w-[200px] max-w-md">
            <Search className="w-4 h-4 text-zinc-500 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('transactionHistory.searchPlaceholder')}
              className="w-full bg-zinc-900 border border-zinc-750 rounded-lg pl-9 pr-3 py-1.5 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-emerald-500 transition"
            />
          </div>

          <div className="flex items-center gap-1 bg-zinc-900 p-0.5 rounded-lg border border-zinc-800 text-xs">
            <button
              onClick={() => setFilterType('ALL')}
              className={`px-3 py-1 rounded-md transition ${
                filterType === 'ALL'
                  ? 'bg-zinc-800 text-white font-semibold'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {t('transactionHistory.filterAll')}
            </button>
            <button
              onClick={() => setFilterType('BUY')}
              className={`px-3 py-1 rounded-md transition ${
                filterType === 'BUY'
                  ? 'bg-emerald-500/20 text-emerald-300 font-semibold border border-emerald-500/30'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {t('transactionHistory.filterBuy')}
            </button>
            <button
              onClick={() => setFilterType('SELL')}
              className={`px-3 py-1 rounded-md transition ${
                filterType === 'SELL'
                  ? 'bg-rose-500/20 text-rose-300 font-semibold border border-rose-500/30'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {t('transactionHistory.filterSell')}
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4">
          {filteredTransactions.length === 0 ? (
            <div className="py-16 text-center text-zinc-500 text-xs font-medium">
              {t('transactionHistory.empty')}
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-xs text-left border-collapse">
                <thead>
                  <tr className="border-b border-zinc-800 text-[11px] font-bold text-zinc-400 uppercase tracking-wider bg-zinc-900/40">
                    <th className="py-2.5 px-3">{t('transactionHistory.colDate')}</th>
                    <th className="py-2.5 px-3">{t('transactionHistory.colAsset')}</th>
                    <th className="py-2.5 px-3">{t('transactionHistory.colType')}</th>
                    <th className="py-2.5 px-3 text-right">{t('transactionHistory.colQuantity')}</th>
                    <th className="py-2.5 px-3 text-right">{t('transactionHistory.colPrice')}</th>
                    <th className="py-2.5 px-3 text-right">{t('transactionHistory.colCommission')}</th>
                    <th className="py-2.5 px-3 text-right">{t('transactionHistory.colTotalBase')}</th>
                    <th className="py-2.5 px-3 text-right">{t('transactionHistory.colExchangeRate')}</th>
                    <th className="py-2.5 px-3 text-center">{t('transactionHistory.colActions')}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-zinc-800/60 font-mono">
                  {filteredTransactions.map((tx) => {
                    const inst = instruments[tx.instrumentId];
                    const isBuy = tx.transactionType === 'BUY';
                    const isDeleting = deletingId === tx.id;
                    const displaySymbol = tx.seriesName || inst?.symbol || 'UNKNOWN';
                    const fxVal = tx.exchangeRateToBase || (tx.baseAmount && tx.quantity * tx.price > 0 ? tx.baseAmount / (tx.quantity * tx.price) : undefined);

                    return (
                      <tr key={tx.id} className="hover:bg-zinc-850/40 transition">
                        <td className="py-2.5 px-3 whitespace-nowrap text-zinc-400">
                          {new Date(tx.executedAt).toLocaleDateString()}
                        </td>
                        <td className="py-2.5 px-3 font-sans">
                          <span className="font-bold text-white block">{displaySymbol}</span>
                          {inst?.name && (
                            <span className="text-[11px] text-zinc-400 truncate block max-w-[200px]">
                              {inst.name}
                            </span>
                          )}
                        </td>
                        <td className="py-2.5 px-3 whitespace-nowrap">
                          <span
                            className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-bold uppercase ${
                              isBuy
                                ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30'
                                : 'bg-rose-500/15 text-rose-400 border border-rose-500/30'
                            }`}
                          >
                            {isBuy ? (
                              <ArrowUpRight className="w-3 h-3" />
                            ) : (
                              <ArrowDownRight className="w-3 h-3" />
                            )}
                            {isBuy
                              ? t('transactionHistory.filterBuy')
                              : t('transactionHistory.filterSell')}
                          </span>
                        </td>
                        <td className="py-2.5 px-3 text-right text-zinc-200">
                          {tx.quantity}
                        </td>
                        <td className="py-2.5 px-3 text-right text-zinc-200">
                          {tx.price.toFixed(2)} {tx.currency}
                        </td>
                        <td className="py-2.5 px-3 text-right text-zinc-400">
                          {tx.commission ? `${tx.commission.toFixed(2)} ${tx.currency}` : '—'}
                        </td>
                        <td className="py-2.5 px-3 text-right font-bold text-white">
                          {tx.baseAmount != null
                            ? `${tx.baseAmount.toLocaleString('pl-PL', { maximumFractionDigits: 2 })} ${baseCurrency}`
                            : '—'}
                        </td>
                        <td className="py-2.5 px-3 text-right text-zinc-400">
                          {tx.currency !== baseCurrency && fxVal
                            ? fxVal.toFixed(4)
                            : '—'}
                        </td>
                        <td className="py-2.5 px-3 text-center whitespace-nowrap">
                          <div className="flex items-center justify-center gap-1.5">
                            <button
                              onClick={() => {
                                onEditTransaction(tx);
                                onClose();
                              }}
                              className="p-1 rounded text-zinc-400 hover:text-white hover:bg-zinc-800 transition"
                              title={t('transactionHistory.edit')}
                            >
                              <Pencil className="w-3.5 h-3.5" />
                            </button>
                            <button
                              disabled={isDeleting}
                              onClick={() => handleDelete(tx.id)}
                              className="p-1 rounded text-zinc-400 hover:text-rose-400 hover:bg-rose-500/10 transition disabled:opacity-50"
                              title={t('transactionHistory.delete')}
                            >
                              {isDeleting ? (
                                <Loader2 className="w-3.5 h-3.5 animate-spin" />
                              ) : (
                                <Trash2 className="w-3.5 h-3.5" />
                              )}
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
