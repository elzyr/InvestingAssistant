import { standardizeDate } from '../../utils/domain/dates.ts';
import {
  BOND_SPEC_BY_FAMILY,
  BOND_SPEC_BY_TERM,
  findKnownBondFamily,
  isBondInstrument,
  parseBondSeriesMetadata,
} from '../../utils/domain/assets.ts';
import type { Currency } from '../../types/auth.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { TransactionResponse, TransactionType } from '../../types/portfolio.ts';

export function getTodayLocalDateStr(): string {
  return standardizeDate(new Date());
}

export interface TransactionFormState {
  selectedInstrument: InstrumentResponse | null;
  transactionType: TransactionType;
  quantity: string;
  price: string;
  commission: string;
  currency: Currency;
  transactionDate: string;
  seriesName: string;
  interestType: 'FIXED' | 'INFLATION';
  bondRate: string;
  bondMargin: string;
  termMonths: string;
  loadingPrice: boolean;
  submitting: boolean;
  deleting: boolean;
  error: string | null;
}

export type TransactionFormField =
  | 'transactionType'
  | 'quantity'
  | 'price'
  | 'commission'
  | 'currency'
  | 'transactionDate'
  | 'interestType'
  | 'bondRate'
  | 'bondMargin'
  | 'termMonths'
  | 'loadingPrice'
  | 'submitting'
  | 'deleting'
  | 'error';

export type TransactionFormAction =
  | { type: 'SELECT_INSTRUMENT'; instrument: InstrumentResponse }
  | { type: 'SET_SERIES_NAME'; value: string; preserveDate?: boolean }
  | { type: 'PRICE_LOADED'; price: number }
  | { type: 'PRICE_LOAD_FINISHED' }
  | { type: 'SET_FIELD'; field: TransactionFormField; value: TransactionFormState[TransactionFormField] };

export function createInitialTransactionFormState(params: {
  transaction?: TransactionResponse | null;
  instrument?: InstrumentResponse;
  cachedInstruments?: InstrumentResponse[];
  defaultCurrency: Currency;
  todayDate?: string;
}): TransactionFormState {
  const { transaction, instrument, cachedInstruments = [], defaultCurrency, todayDate } = params;
  if (transaction) {
    const resolved = cachedInstruments.find((i) => i.id === transaction.instrumentId) || null;
    return buildEditState(transaction, resolved, todayDate ?? getTodayLocalDateStr(), defaultCurrency);
  }
  return buildNewState(instrument, defaultCurrency, todayDate ?? getTodayLocalDateStr());
}

function buildNewState(
  instrument: InstrumentResponse | undefined,
  defaultCurrency: Currency,
  todayDate: string
): TransactionFormState {
  const isBond = isBondInstrument(instrument);
  return {
    selectedInstrument: instrument || null,
    transactionType: 'BUY',
    quantity: '10',
    price: '100.00',
    commission: '0',
    currency: isBond ? 'PLN' : ((instrument?.currency as Currency) || defaultCurrency),
    transactionDate: todayDate,
    seriesName: '',
    interestType: 'FIXED',
    bondRate: '',
    bondMargin: '',
    termMonths: isBond ? '36' : '',
    loadingPrice: false,
    submitting: false,
    deleting: false,
    error: null,
  };
}

function buildEditState(
  transaction: TransactionResponse,
  resolvedInstrument: InstrumentResponse | null,
  fallbackDate: string,
  defaultCurrency: Currency
): TransactionFormState {
  const parsedSeries = transaction.seriesName ? parseBondSeriesMetadata(transaction.seriesName) : null;
  const matchedFamily = findKnownBondFamily(transaction.seriesName || resolvedInstrument?.symbol);
  const familySpec = matchedFamily ? BOND_SPEC_BY_FAMILY[matchedFamily] : undefined;
  const termSpec =
    transaction.termMonths != null ? BOND_SPEC_BY_TERM[transaction.termMonths] : undefined;
  const isExplicitFixed =
    parsedSeries?.interestType === 'FIXED' ||
    familySpec?.interestType === 'FIXED' ||
    termSpec?.interestType === 'FIXED';
  const isInflationBond =
    parsedSeries?.interestType === 'INFLATION' ||
    familySpec?.interestType === 'INFLATION' ||
    termSpec?.interestType === 'INFLATION' ||
    (transaction.bondMargin != null && (transaction.bondMargin > 0 || !isExplicitFixed));
  const bond = isBondInstrument(resolvedInstrument) || transaction.bondRate != null;
  return {
    selectedInstrument: resolvedInstrument,
    transactionType: transaction.transactionType,
    quantity: transaction.quantity?.toString() || '1',
    price: transaction.price?.toString() || '100',
    commission: transaction.commission?.toString() || '0',
    currency: transaction.currency || defaultCurrency,
    transactionDate: transaction.executedAt ? standardizeDate(transaction.executedAt) : fallbackDate,
    seriesName: transaction.seriesName || '',
    interestType: isInflationBond ? 'INFLATION' : 'FIXED',
    bondRate:
      transaction.bondRate !== undefined && transaction.bondRate !== null
        ? transaction.bondRate.toString()
        : '',
    bondMargin:
      transaction.bondMargin !== undefined && transaction.bondMargin !== null
        ? transaction.bondMargin.toString()
        : '',
    termMonths: transaction.termMonths ? transaction.termMonths.toString() : bond ? '36' : '',
    loadingPrice: false,
    submitting: false,
    deleting: false,
    error: null,
  };
}

export function transactionFormReducer(
  state: TransactionFormState,
  action: TransactionFormAction
): TransactionFormState {
  switch (action.type) {
    case 'SELECT_INSTRUMENT': {
      const bond = isBondInstrument(action.instrument);
      return {
        ...state,
        selectedInstrument: action.instrument,
        transactionType:
          bond && state.transactionType === 'DIVIDEND' ? 'BUY' : state.transactionType,
        currency: bond ? 'PLN' : action.instrument.currency || 'USD',
        price: bond ? '100.00' : state.price,
        termMonths: bond ? state.termMonths || '36' : '',
        loadingPrice: !bond,
      };
    }
    case 'SET_SERIES_NAME': {
      const parsed = parseBondSeriesMetadata(action.value);
      const shouldAutoFillDate = state.transactionType === 'BUY' && !action.preserveDate;
      return {
        ...state,
        seriesName: action.value,
        interestType: parsed ? parsed.interestType : state.interestType,
        termMonths: parsed ? parsed.tenure.toString() : state.termMonths,
        transactionDate:
          parsed && shouldAutoFillDate ? parsed.issueDate : state.transactionDate,
      };
    }
    case 'PRICE_LOADED':
      return { ...state, price: action.price.toFixed(2) };
    case 'PRICE_LOAD_FINISHED':
      return { ...state, loadingPrice: false };
    case 'SET_FIELD':
      if (action.field === 'interestType' && action.value === 'FIXED') {
        return { ...state, interestType: 'FIXED', bondMargin: '' };
      }
      return { ...state, [action.field]: action.value };
  }
}
