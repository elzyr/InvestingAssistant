import { useEffect, useCallback } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks.ts';
import {
  fetchPortfolios,
  fetchPositions,
  fetchTransactions,
  fetchHistoricalPrices,
  fetchInflation,
  fetchMonthlyInflation,
} from '../store/thunks/portfolio.thunk.ts';
import type { Currency } from '../types/auth.ts';
import type { PositionResponse, TransactionResponse } from '../types/portfolio.ts';
import type { InstrumentResponse } from '../types/instrument.ts';

function collectEquitySymbols(
  positions: PositionResponse[],
  transactions: TransactionResponse[],
  instruments: Record<string, InstrumentResponse>
): string[] {
  const equitySymbols = new Set<string>();
  for (const pos of positions) {
    const inst = instruments[pos.instrumentId];
    if (inst && inst.type !== 'BOND' && !inst.symbol.startsWith('PL_BOND') && !pos.seriesName) {
      equitySymbols.add(inst.symbol);
    }
  }
  for (const tx of transactions) {
    const inst = instruments[tx.instrumentId];
    if (inst && inst.type !== 'BOND' && !inst.symbol.startsWith('PL_BOND') && !tx.seriesName) {
      equitySymbols.add(inst.symbol);
    }
  }
  return Array.from(equitySymbols);
}

export function useActivePortfolioData(
  selectedYear?: number,
  options?: { skipPositions?: boolean; skipHistory?: boolean }
) {
  const dispatch = useAppDispatch();
  const user = useAppSelector((state) => state.auth.user);
  const baseCurrency: Currency = user?.baseCurrency || 'PLN';

  const {
    portfolios,
    activePortfolioId,
    positions,
    transactions,
    instruments,
    quotes,
    rates,
    historicalPrices,
    historicalSortedDates,
    cpi,
    monthlyCpi: monthlyCpiByYear,
    portfolioStats,
    loading,
    isInitialLoading,
    isPositionsLoading,
    isTransactionsLoading,
    error,
  } = useAppSelector((state) => state.portfolio);

  const monthlyCpi = selectedYear ? (monthlyCpiByYear[selectedYear] || {}) : {};
  const activePortfolio = portfolios.find((p) => p.id === activePortfolioId);

  useEffect(() => {
    if (portfolios.length === 0) {
      dispatch(fetchPortfolios(baseCurrency));
    }
  }, [dispatch, portfolios.length, baseCurrency]);

  useEffect(() => {
    if (activePortfolioId) {
      if (!options?.skipPositions) {
        dispatch(fetchPositions(activePortfolioId));
      }
      dispatch(fetchTransactions(activePortfolioId));
    }
  }, [dispatch, activePortfolioId, options?.skipPositions]);

  useEffect(() => {
    if (options?.skipHistory) return;
    const equitySymbols = collectEquitySymbols(positions, transactions, instruments);
    for (const sym of equitySymbols) {
      dispatch(fetchHistoricalPrices({ symbol: sym }));
    }
  }, [dispatch, positions, transactions, instruments, options?.skipHistory]);

  useEffect(() => {
    if (cpi === null) {
      dispatch(fetchInflation());
    }
  }, [dispatch, cpi]);

  useEffect(() => {
    if (selectedYear && !monthlyCpiByYear[selectedYear]) {
      dispatch(fetchMonthlyInflation(selectedYear));
    }
  }, [dispatch, selectedYear, monthlyCpiByYear]);

  const refreshData = useCallback(() => {
    if (activePortfolioId) {
      if (!options?.skipPositions) {
        dispatch(fetchPositions({ portfolioId: activePortfolioId, force: true }));
      }
      dispatch(fetchTransactions({ portfolioId: activePortfolioId, force: true }));
    }
    if (!options?.skipHistory) {
      const equitySymbols = collectEquitySymbols(positions, transactions, instruments);
      for (const sym of equitySymbols) {
        dispatch(fetchHistoricalPrices({ symbol: sym, force: true }));
      }
    }
    dispatch(fetchInflation({ force: true }));
    if (selectedYear) {
      dispatch(fetchMonthlyInflation({ year: selectedYear, force: true }));
    }
  }, [dispatch, activePortfolioId, selectedYear, options?.skipPositions, options?.skipHistory, positions, transactions, instruments]);

  return {
    dispatch,
    portfolios,
    activePortfolio,
    activePortfolioId,
    positions,
    transactions,
    instruments,
    quotes,
    rates,
    historicalPrices,
    historicalSortedDates,
    cpi,
    monthlyCpi,
    portfolioStats,
    baseCurrency: activePortfolio?.baseCurrency || baseCurrency,
    loading,
    isInitialLoading,
    isPositionsLoading,
    isTransactionsLoading,
    error,
    refreshData,
  };
}
