import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { isAxiosError } from 'axios';
import { X, ArrowDownRight, ArrowUpRight, Loader2, ShieldCheck, Sparkles, Trash2 } from 'lucide-react';
import { instrumentService } from '../../services/instrument.service.ts';
import { marketService } from '../../services/market.service.ts';
import { useAppDispatch, useAppSelector } from '../../store/hooks.ts';
import {
  createTransaction,
  updateTransaction,
  deleteTransaction,
} from '../../store/thunks/portfolio.thunk.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { Currency } from '../../types/auth.ts';
import type { TransactionResponse, TransactionType } from '../../types/portfolio.ts';

export interface TransactionModalProps {
  isOpen: boolean;
  onClose: () => void;
  portfolioId: string;
  defaultCurrency?: Currency;
  transaction?: TransactionResponse | null;
  instrument?: InstrumentResponse;
}

const getTodayLocalDateStr = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

export const TransactionModal: React.FC<TransactionModalProps> = ({
  isOpen,
  onClose,
  portfolioId,
  defaultCurrency = 'PLN',
  transaction,
  instrument,
}) => {
  const { t } = useTranslation();
  const dispatch = useAppDispatch();
  const reduxInstruments = useAppSelector((state) => state.portfolio.instruments);
  const reduxQuotes = useAppSelector((state) => state.portfolio.quotes);
  const cachedInstruments = useMemo(() => {
    return Object.values(reduxInstruments).filter((i) => i.type === 'ETF' || i.type === 'BOND' || i.type === 'STOCK');
  }, [reduxInstruments]);
  const initialInstrument = instrument || cachedInstruments[0];
  const initialInstrumentIsBond = Boolean(
    initialInstrument &&
      (initialInstrument.type === 'BOND' || initialInstrument.symbol.startsWith('PL_BOND'))
  );
  const initialQuote = initialInstrument ? reduxQuotes[initialInstrument.symbol] : undefined;
  const [fetchedInstruments, setFetchedInstruments] = useState<InstrumentResponse[]>([]);
  const instruments = cachedInstruments.length > 0 ? cachedInstruments : fetchedInstruments;
  const isEdit = Boolean(transaction);
  const [loadingInstruments, setLoadingInstruments] = useState(false);
  const [selectedInstrumentId, setSelectedInstrumentId] = useState(initialInstrument?.id || '');
  const [transactionType, setTransactionType] = useState<TransactionType>('BUY');
  const [quantity, setQuantity] = useState<string>('10');
  const [price, setPrice] = useState<string>(() => {
    if (initialInstrumentIsBond) return '100.00';
    if (initialQuote?.price) return initialQuote.price.toFixed(2);
    return '100.00';
  });
  const [commission, setCommission] = useState<string>('0');
  const [currency, setCurrency] = useState<Currency>(() => {
    if (initialInstrumentIsBond) return 'PLN';
    return (initialInstrument?.currency as Currency) || defaultCurrency;
  });
  const [transactionDate, setTransactionDate] = useState<string>(
    () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; }
  );

  const [seriesName, setSeriesName] = useState<string>('');
  const [interestType, setInterestType] = useState<'FIXED' | 'INFLATION'>('FIXED');
  const [bondRate, setBondRate] = useState<string>('6.50');
  const [bondMargin, setBondMargin] = useState<string>('2.00');
  const [termMonths, setTermMonths] = useState<string>('36');

  const [loadingPrice, setLoadingPrice] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [prevTxId, setPrevTxId] = useState<string | null>(null);

  if (transaction && transaction.id !== prevTxId) {
    setPrevTxId(transaction.id);
    setSelectedInstrumentId(transaction.instrumentId);
    setTransactionType(transaction.transactionType);
    setQuantity(transaction.quantity?.toString() || '1');
    setPrice(transaction.price?.toString() || '100');
    setCommission(transaction.commission?.toString() || '0');
    setCurrency(transaction.currency || 'PLN');
    if (transaction.executedAt) {
      setTransactionDate(transaction.executedAt.split('T')[0]);
    }
    setSeriesName(transaction.seriesName || '');
    setInterestType(transaction.bondMargin ? 'INFLATION' : 'FIXED');
    if (transaction.bondRate !== undefined && transaction.bondRate !== null) {
      setBondRate(transaction.bondRate.toString());
    }
    if (transaction.bondMargin !== undefined && transaction.bondMargin !== null) {
      setBondMargin(transaction.bondMargin.toString());
    }
    if (transaction.termMonths) {
      setTermMonths(transaction.termMonths.toString());
    }
  } else if (!transaction && prevTxId !== null) {
    setPrevTxId(null);
    setSelectedInstrumentId(initialInstrument?.id || '');
    setTransactionType('BUY');
    setQuantity('10');
    setPrice(
      initialInstrumentIsBond
        ? '100.00'
        : initialQuote?.price
        ? initialQuote.price.toFixed(2)
        : '100.00'
    );
    setCommission('0');
    setCurrency(
      initialInstrumentIsBond
        ? 'PLN'
        : ((initialInstrument?.currency as Currency) || defaultCurrency)
    );
    setTransactionDate(getTodayLocalDateStr());
    setSeriesName('');
    setInterestType('FIXED');
    setBondRate('6.50');
    setBondMargin('2.00');
    setTermMonths('36');
    setError(null);
  }

  const effectiveInstrumentId = selectedInstrumentId || instruments[0]?.id || '';
  const selectedInstrument = instruments.find((i) => i.id === effectiveInstrumentId) || instrument;
  const isBond =
    selectedInstrument?.type === 'BOND' ||
    selectedInstrument?.symbol?.startsWith('PL_BOND') ||
    transaction?.bondRate !== undefined ||
    Boolean(transaction?.seriesName);

  const handleInstrumentSelect = useCallback(
    (inst: InstrumentResponse) => {
      setSelectedInstrumentId(inst.id);
      const instrumentIsBond =
        inst.type === 'BOND' || inst.symbol.startsWith('PL_BOND');

      if (instrumentIsBond) {
        setCurrency('PLN');
        setPrice('100.00');
      } else {
        setCurrency(inst.currency || 'USD');
        const cached = reduxQuotes[inst.symbol];
        if (cached?.price) {
          setPrice(cached.price.toFixed(2));
        } else {
          setLoadingPrice(true);
          marketService
            .getPrice(inst.symbol)
            .then((quote) => {
              if (quote?.price) {
                setPrice(quote.price.toFixed(2));
              }
            })
            .catch(() => {})
            .finally(() => {
              setLoadingPrice(false);
            });
        }
      }
    },
    [reduxQuotes]
  );

  const handleSeriesNameChange = (val: string) => {
    setSeriesName(val);
    const raw = val.toUpperCase().trim();
    const match = raw.match(/^([A-Z]{3})(\d{2})(\d{2})$/);
    if (match) {
      const prefix = match[1];
      const month = parseInt(match[2], 10);
      const year = 2000 + parseInt(match[3], 10);

      let tenure = 36;
      let detectedType: 'FIXED' | 'INFLATION' = 'FIXED';
      let defaultRate = '6.50';
      let defaultMargin = '2.00';

      if (prefix === 'TOS') {
        tenure = 36;
        detectedType = 'FIXED';
        defaultRate = '6.50';
      } else if (prefix === 'EDO') {
        tenure = 120;
        detectedType = 'INFLATION';
        defaultRate = '6.75';
        defaultMargin = '2.00';
      } else if (prefix === 'COI') {
        tenure = 48;
        detectedType = 'INFLATION';
        defaultRate = '6.50';
        defaultMargin = '1.50';
      } else if (prefix === 'DOR') {
        tenure = 24;
        detectedType = 'FIXED';
        defaultRate = '6.30';
      } else if (prefix === 'ROR') {
        tenure = 12;
        detectedType = 'FIXED';
        defaultRate = '6.00';
      } else if (prefix === 'OTS') {
        tenure = 3;
        detectedType = 'FIXED';
        defaultRate = '3.00';
      }

      setInterestType(detectedType);
      setTermMonths(tenure.toString());
      setBondRate(defaultRate);
      if (detectedType === 'INFLATION') {
        setBondMargin(defaultMargin);
      }

      const issueYear = year - Math.round(tenure / 12);
      const safeMonth = Math.min(12, Math.max(1, month));
      const mm = String(safeMonth).padStart(2, '0');
      setTransactionDate(`${issueYear}-${mm}-01`);
    }
  };

  useEffect(() => {
    if (!isOpen || transaction || cachedInstruments.length > 0) return;

    let mounted = true;
    const loadData = async () => {
      setLoadingInstruments(true);
      setError(null);
      try {
        const data = await instrumentService.getInstruments();
        if (!mounted) return;
        const filtered = data.filter((i) => i.type === 'ETF' || i.type === 'BOND' || i.type === 'STOCK');
        setFetchedInstruments(filtered);
        if (!transaction && !selectedInstrumentId && filtered.length > 0) {
          handleInstrumentSelect(filtered[0]);
        }
      } catch (err: unknown) {
        if (!mounted) return;
        if (isAxiosError<{ message?: string }>(err)) {
          setError(err.response?.data?.message || t('transactionModal.errorLoadingInstruments'));
        } else {
          setError(t('transactionModal.errorLoadingInstruments'));
        }
      } finally {
        if (mounted) setLoadingInstruments(false);
      }
    };

    void loadData();
    return () => {
      mounted = false;
    };
  }, [isOpen, transaction, cachedInstruments.length, selectedInstrumentId, handleInstrumentSelect]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!effectiveInstrumentId) {
      setError(t('transactionModal.errorSelectInstrument'));
      return;
    }
    const q = parseFloat(quantity);
    const p = isBond && transactionType === 'BUY' ? 100.0 : parseFloat(price);
    const c = parseFloat(commission) || 0;

    if (isNaN(q) || q <= 0) {
      setError(t('transactionModal.errorQuantityPositive'));
      return;
    }
    if (isNaN(p) || p <= 0) {
      setError(t('transactionModal.errorPricePositive'));
      return;
    }

    if (isBond && transactionType === 'BUY') {
      const parsedRate = parseFloat(bondRate);
      if (isNaN(parsedRate) || parsedRate < 0) {
        setError(t('transactionModal.errorBondRatePositive'));
        return;
      }
      if (interestType === 'INFLATION') {
        const parsedMargin = parseFloat(bondMargin);
        if (isNaN(parsedMargin) || parsedMargin < 0) {
          setError(t('transactionModal.errorBondMarginPositive'));
          return;
        }
      }
    }

    setSubmitting(true);
    setError(null);

    const executedAt = transactionDate ? `${transactionDate}T12:00:00` : undefined;
    const requestPayload = {
      instrumentId: effectiveInstrumentId,
      transactionType,
      quantity: q,
      price: p,
      commission: c,
      currency: isBond ? 'PLN' : currency,
      executedAt,
      seriesName: isBond && seriesName.trim() ? seriesName.trim() : undefined,
      bondRate: isBond && bondRate ? parseFloat(bondRate) : undefined,
      bondMargin: isBond && interestType === 'INFLATION' && bondMargin ? parseFloat(bondMargin) : undefined,
      termMonths: isBond && termMonths ? parseInt(termMonths, 10) : undefined,
    };

    try {
      if (isEdit && transaction) {
        await dispatch(
          updateTransaction({
            portfolioId,
            transactionId: transaction.id,
            request: requestPayload,
          })
        ).unwrap();
      } else {
        await dispatch(
          createTransaction({
            portfolioId,
            request: requestPayload,
          })
        ).unwrap();
      }
      onClose();
    } catch (err: unknown) {
      if (typeof err === 'string') {
        setError(err);
      } else {
        setError(isEdit ? t('transactionModal.errorUpdateFailed') : t('transactionModal.errorCreateFailed'));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async () => {
    if (!transaction || !window.confirm(t('transactionModal.confirmDelete', 'Czy na pewno chcesz usunąć tę transakcję?'))) {
      return;
    }
    setDeleting(true);
    setError(null);
    try {
      await dispatch(
        deleteTransaction({
          portfolioId,
          transactionId: transaction.id,
        })
      ).unwrap();
      onClose();
    } catch (err: unknown) {
      setError(typeof err === 'string' ? err : t('transactionModal.errorDeleteFailed'));
    } finally {
      setDeleting(false);
    }
  };

  const parsedQty = parseFloat(quantity) || 0;
  const bondTotalNominal = parsedQty * 100;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-xs">
      <div className="bg-[#101115] border border-zinc-800 rounded-lg max-w-md w-full shadow-2xl overflow-hidden max-h-[92vh] flex flex-col">
        <div className="px-5 py-4 border-b border-zinc-800 flex items-center justify-between shrink-0">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-white flex items-center gap-2">
            {isBond && <ShieldCheck className={`w-4 h-4 ${isEdit ? 'text-amber-400' : 'text-emerald-400'}`} />}
            {isEdit
              ? `${t('transactionModal.editTitle', 'Edycja transakcji')}: ${seriesName || selectedInstrument?.symbol || 'Instrument'}`
              : isBond
              ? t('transactionModal.buyBondTitle')
              : t('transactionModal.addTitle')}
          </h2>
          <button onClick={onClose} className="text-zinc-400 hover:text-zinc-200 transition">
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-5 space-y-4 overflow-y-auto">
          {error && (
            <div className="p-2.5 rounded bg-rose-950/40 border border-rose-800/60 text-xs text-rose-300">
              {error}
            </div>
          )}

          {!isEdit && (
            <div>
              <label className="block text-xs text-zinc-400 mb-1.5">
                {t('transactionModal.instrument')}
              </label>
              {loadingInstruments ? (
                <div className="text-xs text-zinc-500 py-2">{t('transactionModal.loadingCatalog')}</div>
              ) : (
                <select
                  value={effectiveInstrumentId}
                  onChange={(e) => {
                    const inst = instruments.find((i) => i.id === e.target.value);
                    if (inst) handleInstrumentSelect(inst);
                  }}
                  className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-sans focus:outline-none focus:border-zinc-600"
                >
                  {instruments.map((inst) => (
                    <option key={inst.id} value={inst.id}>
                      {inst.symbol} — {inst.name} ({inst.currency})
                    </option>
                  ))}
                </select>
              )}
            </div>
          )}

          {isBond && (
            <div>
              <div className="flex justify-between items-center mb-1.5">
                <label className="text-xs text-zinc-400">{t('transactionModal.seriesName')} {!isEdit && `(${t('transactionModal.optional')})`}</label>
                <span className="text-[10px] text-amber-400/90 font-mono flex items-center gap-1">
                  <Sparkles className="w-3 h-3" /> {t('transactionModal.autoFill')}
                </span>
              </div>
              <input
                type="text"
                value={seriesName}
                onChange={(e) => handleSeriesNameChange(e.target.value)}
                className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono uppercase focus:outline-none focus:border-zinc-600"
                placeholder={t('transactionModal.seriesPlaceholder')}
              />
              {!isEdit && (
                <span className="text-[10px] text-zinc-500 block mt-1">
                  {t('transactionModal.seriesHint')}
                </span>
              )}
            </div>
          )}

          {isBond && (
            <div>
              <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.interestType')}</label>
              <div className="grid grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={() => setInterestType('FIXED')}
                  className={`py-2 px-3 rounded text-xs font-semibold border transition ${
                    interestType === 'FIXED'
                      ? 'bg-amber-950/60 border-amber-600 text-amber-300'
                      : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700'
                  }`}
                >
                  {t('transactionModal.fixed')}
                </button>
                <button
                  type="button"
                  onClick={() => setInterestType('INFLATION')}
                  className={`py-2 px-3 rounded text-xs font-semibold border transition ${
                    interestType === 'INFLATION'
                      ? 'bg-amber-950/60 border-amber-600 text-amber-300'
                      : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700'
                  }`}
                >
                  {t('transactionModal.inflation')}
                </button>
              </div>
            </div>
          )}

          <div>
            <label className="block text-xs text-zinc-400 mb-1.5 flex justify-between">
              <span>{t('transactionModal.date')}</span>
              {!isEdit && <span className="text-[10px] text-zinc-500 font-mono">{t('transactionModal.defaultToday')}</span>}
            </label>
            <input
              type="date"
              max={getTodayLocalDateStr()}
              value={transactionDate}
              onChange={(e) => setTransactionDate(e.target.value)}
              className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600 cursor-pointer"
            />
          </div>

          <div>
            <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.typeLabel')}</label>
            <div className="grid grid-cols-2 gap-2">
              <button
                type="button"
                onClick={() => setTransactionType('BUY')}
                className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded text-xs font-semibold border transition ${
                  transactionType === 'BUY'
                    ? 'bg-emerald-950/60 border-emerald-600 text-emerald-300'
                    : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700'
                }`}
              >
                <ArrowDownRight className="w-3.5 h-3.5" />
                {t('transactionModal.typeBuy')}
              </button>
              <button
                type="button"
                onClick={() => setTransactionType('SELL')}
                className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded text-xs font-semibold border transition ${
                  transactionType === 'SELL'
                    ? 'bg-rose-950/60 border-rose-600 text-rose-300'
                    : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700'
                }`}
              >
                <ArrowUpRight className="w-3.5 h-3.5" />
                {t('transactionModal.typeSell')}
              </button>
            </div>
          </div>

          {isBond ? (
            transactionType === 'SELL' ? (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.sellQuantity')}</label>
                    <input
                      type="number"
                      step="1"
                      min="1"
                      required
                      value={quantity}
                      onChange={(e) => setQuantity(e.target.value)}
                      className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                      placeholder="10"
                    />
                  </div>
                  <div>
                    <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.sellPrice')}</label>
                    <input
                      type="number"
                      step="0.01"
                      min="0"
                      required
                      value={price}
                      onChange={(e) => setPrice(e.target.value)}
                      className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                      placeholder="100.00"
                    />
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.totalPayout')}</label>
                    <div className="w-full bg-zinc-900/60 border border-zinc-800 rounded px-3 py-2 text-xs text-emerald-400 font-mono flex items-center h-[34px]">
                      {((parseFloat(quantity) || 0) * (parseFloat(price) || 0)).toLocaleString('pl-PL', { minimumFractionDigits: 2 })} PLN
                    </div>
                  </div>
                  <div>
                    <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.commissionFee')}</label>
                    <input
                      type="number"
                      step="0.01"
                      min="0"
                      value={commission}
                      onChange={(e) => setCommission(e.target.value)}
                      className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                      placeholder="0.00"
                    />
                  </div>
                </div>
              </>
            ) : (
              <>
                <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.nominalUnits')}</label>
                  <input
                    type="number"
                    step="1"
                    min="1"
                    required
                    value={quantity}
                    onChange={(e) => setQuantity(e.target.value)}
                    className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                    placeholder="10"
                  />
                  {!isEdit && (
                    <span className="text-[10px] text-zinc-500 font-mono block mt-1">
                      {t('transactionModal.nominalPriceNote')}
                    </span>
                  )}
                </div>
                <div>
                  <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.nominalValue')}</label>
                  <div className="w-full bg-zinc-900/60 border border-zinc-800 rounded px-3 py-2 text-xs text-emerald-400 font-mono flex items-center h-[34px]">
                    {bondTotalNominal.toLocaleString('pl-PL', { minimumFractionDigits: 2 })} PLN
                  </div>
                  {!isEdit && (
                    <span className="text-[10px] text-zinc-500 font-mono block mt-1">
                      {t('transactionModal.amountToPay')}
                    </span>
                  )}
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-zinc-400 mb-1.5">
                    {interestType === 'INFLATION' ? t('transactionModal.bondRateFirstYear') : t('transactionModal.bondRateAnnual')}
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    min="0"
                    required
                    value={bondRate}
                    onChange={(e) => setBondRate(e.target.value)}
                    className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                    placeholder="6.50"
                  />
                </div>
                {interestType === 'INFLATION' ? (
                  <div>
                    <label className="block text-xs text-zinc-400 mb-1.5">
                      {t('transactionModal.bondMargin')}
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      min="0"
                      required
                      value={bondMargin}
                      onChange={(e) => setBondMargin(e.target.value)}
                      className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                      placeholder="1.25"
                    />
                  </div>
                ) : (
                  <div>
                    <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.couponType')}</label>
                    <div className="w-full bg-zinc-900/60 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-400 font-sans flex items-center h-[34px]">
                      {t('transactionModal.fixedRate')}
                    </div>
                  </div>
                )}
              </div>

              <div>
                <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.termMonths')}</label>
                <select
                  value={termMonths}
                  onChange={(e) => setTermMonths(e.target.value)}
                  className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-sans focus:outline-none focus:border-zinc-600"
                >
                  <option value="3">{t('transactionModal.termMonthsOptionOTS')}</option>
                  <option value="12">{t('transactionModal.termMonthsOptionROR')}</option>
                  <option value="24">{t('transactionModal.termMonthsOptionDOR')}</option>
                  <option value="36">{t('transactionModal.termMonthsOptionTOS')}</option>
                  <option value="48">{t('transactionModal.termMonthsOptionCOI')}</option>
                  <option value="120">{t('transactionModal.termMonthsOptionEDO')}</option>
                </select>
              </div>
            </>
            )
          ) : (
            <>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.quantity')}</label>
                  <input
                    type="number"
                    step="any"
                    min="0.0001"
                    required
                    value={quantity}
                    onChange={(e) => setQuantity(e.target.value)}
                    className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                    placeholder="10"
                  />
                </div>
                <div>
                  <label className="block text-xs text-zinc-400 mb-1.5 flex justify-between">
                    <span>{t('transactionModal.price')} ({currency})</span>
                    {loadingPrice && (
                      <Loader2 className="w-3 h-3 text-zinc-500 animate-spin" />
                    )}
                  </label>
                  <input
                    type="number"
                    step="any"
                    min="0.0001"
                    required
                    value={price}
                    onChange={(e) => setPrice(e.target.value)}
                    className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                    placeholder="120.50"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.commission')} ({currency})</label>
                  <input
                    type="number"
                    step="any"
                    min="0"
                    value={commission}
                    onChange={(e) => setCommission(e.target.value)}
                    className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                    placeholder="0"
                  />
                </div>
                <div>
                  <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.currency')}</label>
                  <select
                    value={currency}
                    onChange={(e) => setCurrency(e.target.value as Currency)}
                    className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
                  >
                    <option value="PLN">PLN</option>
                    <option value="USD">USD</option>
                    <option value="EUR">EUR</option>
                  </select>
                </div>
              </div>
            </>
          )}

          <div className="pt-3 border-t border-zinc-800 flex justify-between items-center gap-2 shrink-0">
            {isEdit ? (
              <button
                type="button"
                onClick={handleDelete}
                disabled={deleting || submitting}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded bg-rose-950/40 hover:bg-rose-900/60 border border-rose-800/60 text-xs font-medium text-rose-300 transition disabled:opacity-50"
              >
                <Trash2 className="w-3.5 h-3.5" />
                <span>{t('transactionHistory.delete', 'Usuń')}</span>
              </button>
            ) : <div />}

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
                disabled={submitting || deleting || !effectiveInstrumentId}
                className="flex items-center gap-1.5 px-4 py-1.5 rounded bg-white hover:bg-zinc-200 text-zinc-950 text-xs font-semibold transition disabled:opacity-50"
              >
                {submitting && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                {isEdit ? t('common.save') : isBond ? t('transactionModal.addBond') : t('transactionModal.save')}
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
};
