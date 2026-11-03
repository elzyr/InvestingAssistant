import { useEffect, useCallback } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks.ts';
import {
  fetchPortfolios,
  fetchPositions,
  fetchTransactions,
  fetchHistoricalPrices,
  fetchInflation,
} from '../store/thunks/portfolio.thunk.ts';
import { setYearMonthlyCpi } from '../store/slices/portfolio.slice.ts';
import { marketService } from '../services/market.service.ts';
import type { Currency } from '../types/auth.ts';

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
    for (const sym of equitySymbols) {
      if (!historicalPrices[sym]) {
        dispatch(fetchHistoricalPrices({ symbol: sym }));
      }
    }
  }, [dispatch, positions, transactions, instruments, historicalPrices, options?.skipHistory]);

  useEffect(() => {
    if (cpi === null) {
      dispatch(fetchInflation());
    }
  }, [dispatch, cpi]);

  useEffect(() => {
    if (selectedYear && !monthlyCpiByYear[selectedYear]) {
      marketService
        .getMonthlyInflation(selectedYear)
        .then((points) => {
          if (Array.isArray(points)) {
            const map: Record<number, number> = {};
            for (const p of points) {
              if (p.year === selectedYear) {
                map[p.month - 1] = p.rate;
              }
            }
            dispatch(setYearMonthlyCpi({ year: selectedYear, data: map }));
          }
        })
        .catch(() => {});
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
      for (const sym of equitySymbols) {
        dispatch(fetchHistoricalPrices({ symbol: sym, force: true }));
      }
    }
    dispatch(fetchInflation());
    if (selectedYear) {
      marketService
        .getMonthlyInflation(selectedYear)
        .then((points) => {
          if (Array.isArray(points)) {
            const map: Record<number, number> = {};
            for (const p of points) {
              if (p.year === selectedYear) {
                map[p.month - 1] = p.rate;
              }
            }
            dispatch(setYearMonthlyCpi({ year: selectedYear, data: map }));
          }
        })
        .catch(() => {});
    }
  }, [dispatch, activePortfolioId, selectedYear, options?.skipPositions, options?.skipHistory, positions, instruments]);



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
    rawCpi: cpi,
    monthlyCpi,
    monthlyCpiByYear,
    baseCurrency: activePortfolio?.baseCurrency || baseCurrency,
    loading,
    isInitialLoading,
    isPositionsLoading,
    isTransactionsLoading,
    error,
    refreshData,
  };
}
