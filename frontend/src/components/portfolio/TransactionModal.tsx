import React from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowDownRight, ArrowUpRight, Loader2, ShieldCheck, Sparkles, Trash2 } from 'lucide-react';
import { Modal } from '../common/Modal.tsx';
import { ErrorBanner } from '../common/ErrorBanner.tsx';
import { InstrumentPicker } from './InstrumentPicker.tsx';
import { BondAmountsFields, BondMetadataFields } from './BondTransactionFields.tsx';
import { EquityTransactionFields } from './EquityTransactionFields.tsx';
import { getTodayLocalDateStr } from './transactionFormReducer.ts';
import { useTransactionForm } from './useTransactionForm.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { Currency } from '../../types/auth.ts';
import type { TransactionResponse } from '../../types/portfolio.ts';

export interface TransactionModalProps {
  isOpen: boolean;
  onClose: () => void;
  portfolioId: string;
  defaultCurrency?: Currency;
  transaction?: TransactionResponse | null;
  instrument?: InstrumentResponse;
}

export const TransactionModal: React.FC<TransactionModalProps> = ({
  isOpen,
  onClose,
  portfolioId,
  defaultCurrency = 'PLN',
  transaction,
  instrument,
}) => {
  if (!isOpen) return null;

  return (
    <TransactionModalContent
      key={`${transaction?.id ?? 'new'}-${instrument?.id ?? ''}`}
      onClose={onClose}
      portfolioId={portfolioId}
      defaultCurrency={defaultCurrency}
      transaction={transaction}
      instrument={instrument}
    />
  );
};

const TransactionModalContent: React.FC<Omit<TransactionModalProps, 'isOpen'>> = ({
  onClose,
  portfolioId,
  defaultCurrency = 'PLN',
  transaction,
  instrument,
}) => {
  const { t } = useTranslation();
  const {
    state,
    setField,
    isEdit,
    isBond,
    effectiveInstrumentId,
    selectedInstrument,
    cachedInstruments,
    ownedInstrumentIds,
    ownedSymbols,
    handleInstrumentSelect,
    handleSeriesNameChange,
    handleSubmit,
    handleDelete,
  } = useTransactionForm({
    onClose,
    portfolioId,
    defaultCurrency,
    transaction,
    instrument,
  });

  return (
    <Modal
      isOpen={true}
      onClose={onClose}
      closeTitle={t('common.cancel')}
      title={
        <h2 className="text-sm font-semibold uppercase tracking-wider text-white flex items-center gap-2">
          {isBond && <ShieldCheck className={`w-4 h-4 ${isEdit ? 'text-amber-400' : 'text-emerald-400'}`} />}
          {isEdit
            ? `${t('transactionModal.editTitle')}: ${state.seriesName || selectedInstrument?.symbol || 'Instrument'}`
            : isBond
            ? t('transactionModal.buyBondTitle')
            : t('transactionModal.addTitle')}
        </h2>
      }
    >
      <form onSubmit={handleSubmit} className="p-5 space-y-4 overflow-y-auto">
          <ErrorBanner error={state.error} />

          {!isEdit && (
            <InstrumentPicker
              isOpen={true}
              effectiveInstrumentId={effectiveInstrumentId}
              selectedInstrument={selectedInstrument}
              cachedInstruments={cachedInstruments}
              ownedInstrumentIds={ownedInstrumentIds}
              ownedSymbols={ownedSymbols}
              onSelectInstrument={handleInstrumentSelect}
              onError={(msg) => setField('error', msg)}
            />
          )}

          {isBond && (
            <BondMetadataFields
              isEdit={isEdit}
              transactionType={state.transactionType}
              seriesName={state.seriesName}
              onSeriesNameChange={handleSeriesNameChange}
              interestType={state.interestType}
              onInterestTypeChange={(val) => setField('interestType', val)}
            />
          )}

          <div>
            <label className="block text-xs text-zinc-400 mb-1.5 flex justify-between">
              <span>{t('transactionModal.date')}</span>
              {!isEdit && <span className="text-[10px] text-zinc-500 font-mono">{t('transactionModal.defaultToday')}</span>}
            </label>
            <input
              type="date"
              max={getTodayLocalDateStr()}
              value={state.transactionDate}
              onChange={(e) => setField('transactionDate', e.target.value)}
              className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600 cursor-pointer"
            />
          </div>

          <div>
            <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.typeLabel')}</label>
            <div className={`grid ${isBond ? 'grid-cols-2' : 'grid-cols-3'} gap-2`}>
              <button
                type="button"
                onClick={() => setField('transactionType', 'BUY')}
                className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded text-xs font-semibold border transition ${
                  state.transactionType === 'BUY'
                    ? 'bg-emerald-950/60 border-emerald-600 text-emerald-300'
                    : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700'
                }`}
              >
                <ArrowDownRight className="w-3.5 h-3.5" />
                {t('transactionModal.typeBuy')}
              </button>
              <button
                type="button"
                onClick={() => setField('transactionType', 'SELL')}
                className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded text-xs font-semibold border transition ${
                  state.transactionType === 'SELL'
                    ? 'bg-rose-950/60 border-rose-600 text-rose-300'
                    : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700'
                }`}
              >
                <ArrowUpRight className="w-3.5 h-3.5" />
                {t('transactionModal.typeSell')}
              </button>
              {!isBond && (
                <button
                  type="button"
                  onClick={() => setField('transactionType', 'DIVIDEND')}
                  className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded text-xs font-semibold border transition ${
                    state.transactionType === 'DIVIDEND'
                      ? 'bg-blue-950/60 border-blue-600 text-blue-300'
                      : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700'
                  }`}
                >
                  <Sparkles className="w-3.5 h-3.5" />
                  {t('transactionModal.typeDividend')}
                </button>
              )}
            </div>
          </div>

          {isBond ? (
            <BondAmountsFields
              isEdit={isEdit}
              transactionType={state.transactionType}
              quantity={state.quantity}
              onQuantityChange={(v) => setField('quantity', v)}
              price={state.price}
              onPriceChange={(v) => setField('price', v)}
              commission={state.commission}
              onCommissionChange={(v) => setField('commission', v)}
              currency={state.currency}
              interestType={state.interestType}
              bondRate={state.bondRate}
              onBondRateChange={(v) => setField('bondRate', v)}
              bondMargin={state.bondMargin}
              onBondMarginChange={(v) => setField('bondMargin', v)}
              termMonths={state.termMonths}
              onTermMonthsChange={(v) => setField('termMonths', v)}
            />
          ) : (
            <EquityTransactionFields
              quantity={state.quantity}
              onQuantityChange={(v) => setField('quantity', v)}
              price={state.price}
              onPriceChange={(v) => setField('price', v)}
              commission={state.commission}
              onCommissionChange={(v) => setField('commission', v)}
              currency={state.currency}
              onCurrencyChange={(v) => setField('currency', v)}
              loadingPrice={state.loadingPrice}
            />
          )}

          <div className="pt-3 border-t border-zinc-800 flex justify-between items-center gap-2 shrink-0">
            {isEdit ? (
              <button
                type="button"
                onClick={handleDelete}
                disabled={state.deleting || state.submitting}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded bg-rose-950/40 hover:bg-rose-900/60 border border-rose-800/60 text-xs font-medium text-rose-300 transition disabled:opacity-50"
              >
                <Trash2 className="w-3.5 h-3.5" />
                <span>{t('transactionHistory.delete')}</span>
              </button>
            ) : (
              <div />
            )}

            <div className="flex gap-2">
              <button
                type="button"
                onClick={onClose}
                className="px-3.5 py-1.5 rounded bg-zinc-900 hover:bg-zinc-800 border border-zinc-800 text-xs font-medium text-zinc-300 transition"
              >
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                disabled={state.submitting || state.deleting || !effectiveInstrumentId}
                className="flex items-center gap-1.5 px-4 py-1.5 rounded bg-white hover:bg-zinc-200 text-zinc-950 text-xs font-semibold transition disabled:opacity-50"
              >
                {state.submitting && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                {isEdit ? t('common.save') : isBond ? t('transactionModal.addBond') : t('transactionModal.save')}
              </button>
            </div>
          </div>
        </form>
    </Modal>
  );
};
