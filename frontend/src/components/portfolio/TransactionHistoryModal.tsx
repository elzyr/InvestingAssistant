import React, { useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Search,
  Pencil,
  Trash2,
  ArrowDownRight,
  ArrowUpRight,
  Sparkles,
  Loader2,
} from 'lucide-react';
import { Modal } from '../common/Modal.tsx';
import { ErrorBanner } from '../common/ErrorBanner.tsx';
import { useAppDispatch } from '../../store/store.ts';
import { deleteTransaction } from '../../store/thunks/portfolio.thunk.ts';
import { formatUtcDate } from '../../utils/domain/dates.ts';
import type { TransactionResponse, TransactionType } from '../../types/portfolio.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';

type HistoryFilterType = 'ALL' | TransactionType;

const TX_BADGE_CONFIG: Record<
  TransactionType,
  { badgeClass: string; activeBtnClass: string; Icon: typeof ArrowUpRight; labelKey: string }
> = {
  BUY: {
    badgeClass: 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30',
    activeBtnClass: 'bg-emerald-500/20 text-emerald-300 font-semibold border border-emerald-500/30',
    Icon: ArrowUpRight,
    labelKey: 'transactionHistory.filterBuy',
  },
  SELL: {
    badgeClass: 'bg-rose-500/15 text-rose-400 border border-rose-500/30',
    activeBtnClass: 'bg-rose-500/20 text-rose-300 font-semibold border border-rose-500/30',
    Icon: ArrowDownRight,
    labelKey: 'transactionHistory.filterSell',
  },
  DIVIDEND: {
    badgeClass: 'bg-blue-500/15 text-blue-400 border border-blue-500/30',
    activeBtnClass: 'bg-blue-500/20 text-blue-300 font-semibold border border-blue-500/30',
    Icon: Sparkles,
    labelKey: 'transactionHistory.filterDividend',
  },
};

export interface TransactionHistoryModalProps {
  isOpen: boolean;
  onClose: () => void;
  portfolioId: string;
  transactions: TransactionResponse[];
  instruments: Record<string, InstrumentResponse>;
  baseCurrency: string;
  onEditTransaction: (tx: TransactionResponse) => void;
}

const TransactionHistoryContent: React.FC<Omit<TransactionHistoryModalProps, 'isOpen'>> = ({
  onClose,
  portfolioId,
  transactions,
  instruments,
  baseCurrency,
  onEditTransaction,
}) => {
  const { t, i18n } = useTranslation();
  const dispatch = useAppDispatch();
  const [searchQuery, setSearchQuery] = useState('');
  const [filterType, setFilterType] = useState<HistoryFilterType>('ALL');
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
    <>
      <ErrorBanner error={deleteError} className="mx-6 mt-4 p-2.5 rounded bg-rose-950/40 border border-rose-800/60 text-xs text-rose-300" />

      <div className="p-4 border-b border-zinc-800/80 flex flex-wrap items-center justify-between gap-3 bg-[#0d0f17]">
        <div className="relative flex-1 min-w-[200px] max-w-md">
          <Search className="w-4 h-4 text-zinc-500 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={t('transactionHistory.searchPlaceholder')}
            className="w-full bg-zinc-900 border border-zinc-700/80 rounded-lg pl-9 pr-3 py-1.5 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-emerald-500 transition"
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
          {(['BUY', 'SELL', 'DIVIDEND'] as TransactionType[]).map((type) => {
            const cfg = TX_BADGE_CONFIG[type];
            return (
              <button
                key={type}
                onClick={() => setFilterType(type)}
                className={`px-3 py-1 rounded-md transition ${
                  filterType === type ? cfg.activeBtnClass : 'text-zinc-400 hover:text-zinc-200'
                }`}
              >
                {t(cfg.labelKey)}
              </button>
            );
          })}
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
                  const badge = TX_BADGE_CONFIG[tx.transactionType] || TX_BADGE_CONFIG.BUY;
                  const BadgeIcon = badge.Icon;
                  const isDeleting = deletingId === tx.id;
                  const displaySymbol = tx.seriesName || inst?.symbol || 'UNKNOWN';
                  const fxVal =
                    tx.exchangeRateToBase ||
                    (tx.baseAmount && tx.quantity * tx.price > 0
                      ? tx.baseAmount / (tx.quantity * tx.price)
                      : undefined);

                  return (
                    <tr key={tx.id} className="hover:bg-zinc-800/40 transition">
                      <td className="py-2.5 px-3 whitespace-nowrap text-zinc-400">
                        {formatUtcDate(tx.executedAt, i18n.language)}
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
                          className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-bold uppercase ${badge.badgeClass}`}
                        >
                          <BadgeIcon className="w-3 h-3" />
                          {t(badge.labelKey)}
                        </span>
                      </td>
                      <td className="py-2.5 px-3 text-right text-zinc-200">{tx.quantity}</td>
                      <td className="py-2.5 px-3 text-right text-zinc-200">
                        {tx.price.toFixed(2)} {tx.currency}
                      </td>
                      <td className="py-2.5 px-3 text-right text-zinc-400">
                        {tx.commission ? `${tx.commission.toFixed(2)} ${tx.currency}` : '—'}
                      </td>
                      <td className="py-2.5 px-3 text-right font-bold text-white">
                        {tx.baseAmount != null
                          ? `${tx.baseAmount.toLocaleString(i18n.language, { maximumFractionDigits: 2 })} ${baseCurrency}`
                          : '—'}
                      </td>
                      <td className="py-2.5 px-3 text-right text-zinc-400">
                        {tx.currency !== baseCurrency && fxVal ? fxVal.toFixed(4) : '—'}
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
    </>
  );
};

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

  if (!isOpen) return null;

  return (
    <Modal
      isOpen={true}
      onClose={onClose}
      maxWidthClass="max-w-5xl"
      panelClassName="relative max-h-[90vh] bg-[#10121a] border border-zinc-800 rounded-2xl shadow-2xl flex flex-col overflow-hidden"
      headerClassName="flex items-center justify-between px-6 py-4 border-b border-zinc-800/80 bg-[#141722] shrink-0"
      title={
        <h2 className="text-lg font-bold text-white tracking-tight">
          {t('transactionHistory.title')}
        </h2>
      }
      subtitle={`${t('transactionHistory.subtitle')} (${transactions.length})`}
      closeTitle={t('transactionHistory.close')}
    >
      <TransactionHistoryContent
        onClose={onClose}
        portfolioId={portfolioId}
        transactions={transactions}
        instruments={instruments}
        baseCurrency={baseCurrency}
        onEditTransaction={onEditTransaction}
      />
    </Modal>
  );
};
