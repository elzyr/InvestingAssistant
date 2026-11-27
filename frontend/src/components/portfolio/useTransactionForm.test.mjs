import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { Provider } from 'react-redux';
import { store } from '../../store/store.ts';
import { portfolioService } from '../../services/portfolio.service.ts';
import {
  createInitialTransactionFormState,
  transactionFormReducer,
} from './transactionFormReducer.ts';
import { useTransactionForm } from './useTransactionForm.ts';

const EQUITY_INST = {
  id: 'inst-etf',
  symbol: 'SXR8.DE',
  name: 'iShares Core S&P 500',
  type: 'ETF',
  currency: 'EUR',
};

const BOND_INST = {
  id: 'inst-bond',
  symbol: 'EDO',
  name: 'Obligacje EDO',
  type: 'BOND',
  currency: 'PLN',
};

describe('transactionFormReducer', () => {
  it('leaves selection empty on new transaction when no instrument prop is passed', () => {
    const state = createInitialTransactionFormState({
      transaction: null,
      instrument: undefined,
      cachedInstruments: [EQUITY_INST, BOND_INST],
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });

    assert.strictEqual(state.selectedInstrument, null);
    assert.strictEqual(state.currency, 'PLN');
    assert.strictEqual(state.transactionDate, '2026-04-15');
  });

  it('initializes with explicit instrument prop and updates currency cleanly on select', () => {
    const initial = createInitialTransactionFormState({
      instrument: EQUITY_INST,
      cachedInstruments: [BOND_INST, EQUITY_INST],
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });
    assert.strictEqual(initial.selectedInstrument?.id, EQUITY_INST.id);
    assert.strictEqual(initial.currency, 'EUR');

    const selectedBond = transactionFormReducer(initial, {
      type: 'SELECT_INSTRUMENT',
      instrument: BOND_INST,
    });
    assert.strictEqual(selectedBond.selectedInstrument?.id, BOND_INST.id);
    assert.strictEqual(selectedBond.currency, 'PLN');
    assert.strictEqual(selectedBond.price, '100.00');
    assert.strictEqual(selectedBond.loadingPrice, false);

    const selectedEquity = transactionFormReducer(selectedBond, {
      type: 'SELECT_INSTRUMENT',
      instrument: EQUITY_INST,
    });
    assert.strictEqual(selectedEquity.currency, 'EUR');
    assert.strictEqual(selectedEquity.loadingPrice, true);

    const priceLoaded = transactionFormReducer(selectedEquity, {
      type: 'PRICE_LOADED',
      price: 512.345,
    });
    assert.strictEqual(priceLoaded.price, '512.35');

    const priceDone = transactionFormReducer(priceLoaded, { type: 'PRICE_LOAD_FINISHED' });
    assert.strictEqual(priceDone.loadingPrice, false);
  });

  it('hydrates edit mode transaction and parses bond series metadata', () => {
    const base = createInitialTransactionFormState({
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });

    const edited = createInitialTransactionFormState({
      transaction: {
        id: 'tx-1',
        portfolioId: 'p-1',
        instrumentId: BOND_INST.id,
        transactionType: 'BUY',
        quantity: 25,
        price: 100,
        commission: 0,
        currency: 'PLN',
        executedAt: '2024-05-01T12:00:00',
        seriesName: 'EDO0534',
        bondRate: 6.8,
        bondMargin: 2.0,
        termMonths: 120,
      },
      cachedInstruments: [BOND_INST],
      defaultCurrency: 'PLN',
    });

    assert.strictEqual(edited.selectedInstrument?.id, BOND_INST.id);
    assert.strictEqual(edited.quantity, '25');
    assert.strictEqual(edited.interestType, 'INFLATION');
    assert.strictEqual(edited.bondRate, '6.8');
    assert.strictEqual(edited.bondMargin, '2');
    assert.strictEqual(edited.termMonths, '120');
    assert.strictEqual(edited.transactionDate, '2024-05-01');

    const updatedSeries = transactionFormReducer(base, {
      type: 'SET_SERIES_NAME',
      value: 'TOS0127',
    });
    assert.strictEqual(updatedSeries.interestType, 'FIXED');
    assert.strictEqual(updatedSeries.termMonths, '36');
    assert.strictEqual(updatedSeries.transactionDate, '2024-01-01');

    const preservedEditSeries = transactionFormReducer(edited, {
      type: 'SET_SERIES_NAME',
      value: 'TOS0127',
      preserveDate: true,
    });
    assert.strictEqual(preservedEditSeries.transactionDate, '2024-05-01');

    const clearedMargin = transactionFormReducer(edited, {
      type: 'SET_FIELD',
      field: 'interestType',
      value: 'FIXED',
    });
    assert.strictEqual(clearedMargin.bondMargin, '');
  });

  it('hydrates zero bondMargin as FIXED rather than INFLATION', () => {
    const edited = createInitialTransactionFormState({
      transaction: {
        id: 'tx-2',
        portfolioId: 'p-1',
        instrumentId: BOND_INST.id,
        transactionType: 'BUY',
        quantity: 10,
        price: 100,
        commission: 0,
        currency: 'PLN',
        executedAt: '2025-02-15T12:00:00',
        seriesName: 'TOS0228',
        bondRate: 5.95,
        bondMargin: 0,
        termMonths: 36,
      },
      cachedInstruments: [BOND_INST],
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });
    assert.strictEqual(edited.interestType, 'FIXED');
  });

  it('defaults termMonths to 36 for bonds, resets DIVIDEND to BUY, and handles GBP/CHF currencies', () => {
    const initialBond = createInitialTransactionFormState({
      instrument: BOND_INST,
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });
    assert.strictEqual(initialBond.termMonths, '36');

    let state = createInitialTransactionFormState({
      instrument: EQUITY_INST,
      defaultCurrency: 'GBP',
      todayDate: '2026-04-15',
    });
    state = transactionFormReducer(state, {
      type: 'SELECT_INSTRUMENT',
      instrument: { id: 'inst-uk', symbol: 'VWRL.L', name: 'Vanguard FTSE', type: 'ETF', currency: 'GBP' },
    });
    assert.strictEqual(state.currency, 'GBP');

    state = transactionFormReducer(state, {
      type: 'SET_FIELD',
      field: 'transactionType',
      value: 'DIVIDEND',
    });
    state = transactionFormReducer(state, {
      type: 'SELECT_INSTRUMENT',
      instrument: BOND_INST,
    });
    assert.strictEqual(state.transactionType, 'BUY');
    assert.strictEqual(state.termMonths, '36');
    assert.strictEqual(state.currency, 'PLN');

    const coiZeroMargin = createInitialTransactionFormState({
      transaction: {
        id: 'tx-coi',
        portfolioId: 'p-1',
        instrumentId: BOND_INST.id,
        transactionType: 'BUY',
        quantity: 5,
        price: 100,
        commission: 0,
        executedAt: '2026-01-05T12:00:00',
        seriesName: 'COI0130',
        bondRate: 6.25,
        bondMargin: 0,
        termMonths: 48,
      },
      cachedInstruments: [BOND_INST],
      defaultCurrency: 'CHF',
    });
    assert.strictEqual(coiZeroMargin.interestType, 'INFLATION');
    assert.strictEqual(coiZeroMargin.currency, 'CHF');

    const unknownZeroMargin = createInitialTransactionFormState({
      transaction: {
        id: 'tx-custom',
        portfolioId: 'p-1',
        instrumentId: BOND_INST.id,
        transactionType: 'BUY',
        quantity: 5,
        price: 100,
        commission: 0,
        executedAt: '2026-01-05T12:00:00',
        seriesName: 'CUSTOM-IDX',
        bondRate: 5.5,
        bondMargin: 0,
        termMonths: 60,
      },
      cachedInstruments: [BOND_INST],
      defaultCurrency: 'PLN',
    });
    assert.strictEqual(unknownZeroMargin.interestType, 'INFLATION');
  });

  it('preserves SELL transactionDate on SET_SERIES_NAME while filling issueDate on BUY', () => {
    const baseState = createInitialTransactionFormState({
      instrument: BOND_INST,
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });

    const sellState = transactionFormReducer(baseState, {
      type: 'SET_FIELD',
      field: 'transactionType',
      value: 'SELL',
    });
    const sellWithSeries = transactionFormReducer(sellState, {
      type: 'SET_SERIES_NAME',
      value: 'EDO0534',
    });
    assert.strictEqual(sellWithSeries.transactionDate, '2026-04-15');
    assert.strictEqual(sellWithSeries.termMonths, '120');

    const buyWithSeries = transactionFormReducer(baseState, {
      type: 'SET_SERIES_NAME',
      value: 'EDO0534',
    });
    assert.strictEqual(buyWithSeries.transactionDate, '2024-05-01');
  });

  it('defaults fxFeePct to 0.5 only on cross-currency non-bond transactions and preserves edit fee', () => {
    const usdEtf = {
      id: 'inst-usd',
      symbol: 'AAPL',
      name: 'Apple',
      type: 'STOCK',
      currency: 'USD',
    };
    const plnEtf = {
      id: 'inst-pln',
      symbol: 'ETFSP500.WA',
      name: 'Beta S&P 500 PLN',
      type: 'ETF',
      currency: 'PLN',
    };

    const crossState = createInitialTransactionFormState({
      transaction: null,
      instrument: usdEtf,
      cachedInstruments: [usdEtf, plnEtf, BOND_INST],
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });
    assert.strictEqual(crossState.fxFeePct, '0.5');

    const editedFee = transactionFormReducer(crossState, {
      type: 'SET_FIELD',
      field: 'fxFeePct',
      value: '0',
    });
    assert.strictEqual(editedFee.fxFeePct, '0');

    const switchedToPln = transactionFormReducer(crossState, {
      type: 'SELECT_INSTRUMENT',
      instrument: plnEtf,
    });
    assert.strictEqual(switchedToPln.fxFeePct, '0');

    const switchedToBond = transactionFormReducer(crossState, {
      type: 'SELECT_INSTRUMENT',
      instrument: BOND_INST,
    });
    assert.strictEqual(switchedToBond.fxFeePct, '0');

    const switchedBackToUsd = transactionFormReducer(switchedToBond, {
      type: 'SELECT_INSTRUMENT',
      instrument: usdEtf,
    });
    assert.strictEqual(switchedBackToUsd.fxFeePct, '0.5');

    const editHydrated = createInitialTransactionFormState({
      transaction: {
        id: 'tx-fx-1',
        instrumentId: 'inst-usd',
        transactionType: 'BUY',
        quantity: 5,
        price: 100,
        commission: 0,
        currency: 'USD',
        exchangeRate: 1,
        executedAt: '2026-04-10T12:00:00',
        fxFeePct: 0.25,
      },
      instrument: usdEtf,
      cachedInstruments: [usdEtf],
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });
    assert.strictEqual(editHydrated.fxFeePct, '0.25');

    const editMissingFee = createInitialTransactionFormState({
      transaction: {
        id: 'tx-fx-2',
        instrumentId: 'inst-usd',
        transactionType: 'BUY',
        quantity: 5,
        price: 100,
        commission: 0,
        currency: 'USD',
        exchangeRate: 1,
        executedAt: '2026-04-10T12:00:00',
      },
      instrument: usdEtf,
      cachedInstruments: [usdEtf],
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });
    assert.strictEqual(editMissingFee.fxFeePct, '0');
  });

  it('updates fxFeePct on SET_FIELD currency toggle while preserving custom non-zero fee', () => {
    const basePln = createInitialTransactionFormState({
      transaction: null,
      instrument: {
        id: 'inst-pln',
        symbol: 'PKO.WA',
        name: 'PKO BP',
        type: 'STOCK',
        currency: 'PLN',
      },
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });
    assert.strictEqual(basePln.fxFeePct, '0');

    const switchedToUsd = transactionFormReducer(basePln, {
      type: 'SET_FIELD',
      field: 'currency',
      value: 'USD',
    });
    assert.strictEqual(switchedToUsd.currency, 'USD');
    assert.strictEqual(switchedToUsd.fxFeePct, '0.5');

    const withCustomFee = transactionFormReducer(switchedToUsd, {
      type: 'SET_FIELD',
      field: 'fxFeePct',
      value: '0.75',
    });
    const switchedToEur = transactionFormReducer(withCustomFee, {
      type: 'SET_FIELD',
      field: 'currency',
      value: 'EUR',
    });
    assert.strictEqual(switchedToEur.currency, 'EUR');
    assert.strictEqual(switchedToEur.fxFeePct, '0.75');

    const switchedBackToDefault = transactionFormReducer(switchedToEur, {
      type: 'SET_FIELD',
      field: 'currency',
      value: 'PLN',
    });
    assert.strictEqual(switchedBackToDefault.fxFeePct, '0');

    const bondState = createInitialTransactionFormState({
      transaction: null,
      instrument: BOND_INST,
      defaultCurrency: 'PLN',
      todayDate: '2026-04-15',
    });
    const bondCurrencyChanged = transactionFormReducer(bondState, {
      type: 'SET_FIELD',
      field: 'currency',
      value: 'USD',
    });
    assert.strictEqual(bondCurrencyChanged.fxFeePct, '0');
  });
});

describe('useTransactionForm executedAt formatting', () => {
  function mountHook(params) {
    let captured;
    function Harness() {
      captured = useTransactionForm(params);
      return null;
    }
    renderToStaticMarkup(
      React.createElement(
        Provider,
        { store },
        React.createElement(Harness)
      )
    );
    return captured;
  }

  it('formats executedAt with local HH:mm:ss for today, noon for past dates, and preserves edit timestamp', async () => {
    const origCreate = portfolioService.createTransaction;
    const origUpdate = portfolioService.updateTransaction;
    const RealDate = globalThis.Date;
    const capturedRequests = [];

    class FixedTodayDate extends RealDate {
      constructor(...args) {
        if (args.length === 0) {
          super(2026, 3, 15, 8, 5, 9, 0);
        } else {
          super(...args);
        }
      }
      static now() {
        return new RealDate(2026, 3, 15, 8, 5, 9, 0).getTime();
      }
    }

    portfolioService.createTransaction = async (_portfolioId, req) => {
      capturedRequests.push(req);
      return { id: 'tx-created', exchangeRate: 1, ...req };
    };
    portfolioService.updateTransaction = async (_portfolioId, transactionId, req) => {
      capturedRequests.push({ transactionId, ...req });
      return { id: transactionId, exchangeRate: 1, ...req };
    };

    try {
      globalThis.Date = FixedTodayDate;

      const todayHook = mountHook({
        onClose: () => {},
        portfolioId: 'p-1',
        defaultCurrency: 'PLN',
        instrument: EQUITY_INST,
      });
      await todayHook.handleSubmit({ preventDefault() {} });
      assert.strictEqual(capturedRequests[0].executedAt, '2026-04-15T08:05:09');

      const editPreservedHook = mountHook({
        onClose: () => {},
        portfolioId: 'p-1',
        defaultCurrency: 'PLN',
        instrument: EQUITY_INST,
        transaction: {
          id: 'tx-edit-1',
          instrumentId: EQUITY_INST.id,
          transactionType: 'BUY',
          quantity: 2,
          price: 100,
          commission: 0,
          currency: 'EUR',
          exchangeRate: 1,
          executedAt: '2026-04-10T09:15:30',
        },
      });
      await editPreservedHook.handleSubmit({ preventDefault() {} });
      assert.strictEqual(capturedRequests[1].executedAt, '2026-04-10T09:15:30');

      const pastNoonHook = mountHook({
        onClose: () => {},
        portfolioId: 'p-1',
        defaultCurrency: 'PLN',
        instrument: EQUITY_INST,
        transaction: {
          id: 'tx-edit-2',
          instrumentId: EQUITY_INST.id,
          transactionType: 'BUY',
          quantity: 2,
          price: 100,
          commission: 0,
          currency: 'EUR',
          exchangeRate: 1,
          executedAt: '2026-04-10',
        },
      });
      await pastNoonHook.handleSubmit({ preventDefault() {} });
      assert.strictEqual(capturedRequests[2].executedAt, '2026-04-10T12:00:00');
    } finally {
      globalThis.Date = RealDate;
      portfolioService.createTransaction = origCreate;
      portfolioService.updateTransaction = origUpdate;
    }
  });
});
