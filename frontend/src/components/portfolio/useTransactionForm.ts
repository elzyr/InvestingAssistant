import { useCallback, useEffect, useMemo, useReducer, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { isCancel } from 'axios';
import { marketService } from '../../services/market.service.ts';
import { standardizeDate } from '../../utils/domain/dates.ts';
import { isBondInstrument, isBondItem } from '../../utils/domain/assets.ts';
import { useAppDispatch, useAppSelector } from '../../store/store.ts';
import {
  createTransaction,
  updateTransaction,
  deleteTransaction,
} from '../../store/thunks/portfolio.thunk.ts';
import type { Currency } from '../../types/auth.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { TransactionResponse } from '../../types/portfolio.ts';
import {
  createInitialTransactionFormState,
  transactionFormReducer,
  type TransactionFormField,
  type TransactionFormState,
} from './transactionFormReducer.ts';

const EMPTY_TRANSACTIONS: TransactionResponse[] = [];

interface UseTransactionFormParams {
  onClose: () => void;
  portfolioId: string;
  defaultCurrency: Currency;
  transaction?: TransactionResponse | null;
  instrument?: InstrumentResponse;
}

export function useTransactionForm({
  onClose,
  portfolioId,
  defaultCurrency,
  transaction,
  instrument,
}: UseTransactionFormParams) {
  const { t } = useTranslation();
  const appDispatch = useAppDispatch();
  const reduxInstruments = useAppSelector((s) => s.portfolio.instruments);
  const reduxTransactions = useAppSelector(
    (s) => s.portfolio.transactionsByPortfolio[portfolioId] ?? EMPTY_TRANSACTIONS
  );

  const { ownedInstrumentIds, ownedSymbols } = useMemo(() => {
    const ids = new Set<string>();
    const symbols = new Set<string>();
    for (const tx of reduxTransactions) {
      if (tx.instrumentId) ids.add(tx.instrumentId);
      if (tx.seriesName) symbols.add(tx.seriesName);
    }
    return { ownedInstrumentIds: ids, ownedSymbols: symbols };
  }, [reduxTransactions]);

  const cachedInstruments = useMemo(
    () =>
      Object.values(reduxInstruments).filter(
        (i) => i.type === 'ETF' || i.type === 'BOND' || i.type === 'STOCK'
      ),
    [reduxInstruments]
  );

  const [state, dispatch] = useReducer(transactionFormReducer, undefined, () =>
    createInitialTransactionFormState({
      transaction,
      instrument,
      cachedInstruments,
      defaultCurrency,
    })
  );

  const setField = useCallback(
    <K extends TransactionFormField>(field: K, value: TransactionFormState[K]) => {
      dispatch({ type: 'SET_FIELD', field, value });
    },
    []
  );

  const priceAbortRef = useRef<AbortController | null>(null);

  const loadLivePrice = useCallback((symbol: string) => {
    priceAbortRef.current?.abort();
    const controller = new AbortController();
    priceAbortRef.current = controller;
    marketService
      .getPrice(symbol, undefined, controller.signal)
      .then((quote) => {
        if (!controller.signal.aborted && quote?.price) {
          dispatch({ type: 'PRICE_LOADED', price: quote.price });
        }
      })
      .catch((err: unknown) => {
        if (!isCancel(err)) {
          console.warn(`Failed to fetch price for ${symbol}:`, err);
        }
      })
      .finally(() => {
        if (priceAbortRef.current === controller) {
          priceAbortRef.current = null;
          dispatch({ type: 'PRICE_LOAD_FINISHED' });
        }
      });
  }, []);

  useEffect(() => {
    if (!transaction && instrument && !isBondInstrument(instrument)) {
      loadLivePrice(instrument.symbol);
    }
    return () => {
      priceAbortRef.current?.abort();
    };
  }, [transaction, instrument, loadLivePrice]);

  const handleInstrumentSelect = useCallback(
    (inst: InstrumentResponse) => {
      priceAbortRef.current?.abort();
      priceAbortRef.current = null;
      dispatch({ type: 'SELECT_INSTRUMENT', instrument: inst });
      if (!isBondInstrument(inst)) {
        loadLivePrice(inst.symbol);
      }
    },
    [loadLivePrice]
  );

  const isEdit = Boolean(transaction);
  const handleSeriesNameChange = useCallback(
    (val: string) => {
      dispatch({ type: 'SET_SERIES_NAME', value: val, preserveDate: isEdit });
    },
    [isEdit]
  );

  const effectiveInstrumentId =
    state.selectedInstrument?.id || transaction?.instrumentId || instrument?.id || '';
  const selectedInstrument =
    state.selectedInstrument ||
    cachedInstruments.find((i) => i.id === effectiveInstrumentId) ||
    instrument;
  const isBond = isBondItem(transaction ?? {}, selectedInstrument);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!effectiveInstrumentId) {
      setField('error', t('transactionModal.errorSelectInstrument'));
      return;
    }
    const q = parseFloat(state.quantity);
    const p = isBond && state.transactionType === 'BUY' ? 100.0 : parseFloat(state.price);
    const c = parseFloat(state.commission) || 0;

    if (isNaN(q) || q <= 0) {
      setField('error', t('transactionModal.errorQuantityPositive'));
      return;
    }
    if (isNaN(p) || p <= 0) {
      setField('error', t('transactionModal.errorPricePositive'));
      return;
    }
    if (isBond && state.transactionType === 'BUY') {
      const parsedRate = parseFloat(state.bondRate);
      if (isNaN(parsedRate) || parsedRate < 0) {
        setField('error', t('transactionModal.errorBondRatePositive'));
        return;
      }
      if (state.interestType === 'INFLATION') {
        const parsedMargin = parseFloat(state.bondMargin);
        if (isNaN(parsedMargin) || parsedMargin < 0) {
          setField('error', t('transactionModal.errorBondMarginPositive'));
          return;
        }
      }
    }

    setField('submitting', true);
    setField('error', null);

    const executedAt = state.transactionDate
      ? `${standardizeDate(state.transactionDate)}T12:00:00`
      : undefined;
    const isBondBuy = isBond && state.transactionType === 'BUY';
    const requestPayload = {
      instrumentId: effectiveInstrumentId,
      transactionType: state.transactionType,
      quantity: q,
      price: p,
      commission: c,
      currency: isBond ? 'PLN' : state.currency,
      executedAt,
      seriesName: isBond && state.seriesName.trim() ? state.seriesName.trim() : undefined,
      bondRate: isBondBuy && state.bondRate ? parseFloat(state.bondRate) : undefined,
      bondMargin:
        isBondBuy && state.interestType === 'INFLATION' && state.bondMargin
          ? parseFloat(state.bondMargin)
          : undefined,
      termMonths: isBondBuy ? parseInt(state.termMonths || '36', 10) : undefined,
    };

    try {
      if (isEdit && transaction) {
        await appDispatch(
          updateTransaction({
            portfolioId,
            transactionId: transaction.id,
            request: requestPayload,
          })
        ).unwrap();
      } else {
        await appDispatch(
          createTransaction({
            portfolioId,
            request: requestPayload,
          })
        ).unwrap();
      }
      onClose();
    } catch (err: unknown) {
      setField(
        'error',
        typeof err === 'string'
          ? err
          : isEdit
          ? t('transactionModal.errorUpdateFailed')
          : t('transactionModal.errorCreateFailed')
      );
    } finally {
      setField('submitting', false);
    }
  };

  const handleDelete = async () => {
    if (!transaction || !window.confirm(t('transactionModal.confirmDelete'))) {
      return;
    }
    setField('deleting', true);
    setField('error', null);
    try {
      await appDispatch(
        deleteTransaction({
          portfolioId,
          transactionId: transaction.id,
        })
      ).unwrap();
      onClose();
    } catch (err: unknown) {
      setField('error', typeof err === 'string' ? err : t('transactionModal.errorDeleteFailed'));
    } finally {
      setField('deleting', false);
    }
  };

  return {
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
  };
}
