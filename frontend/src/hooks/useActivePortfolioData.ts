import { useEffect, useCallback } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks.ts';
import {
  fetchPortfolios,
  fetchPositions,
  fetchTransactions,
  fetchInflation,
} from '../store/thunks/portfolio.thunk.ts';
import { setMonthlyCpi } from '../store/slices/portfolio.slice.ts';
import { marketService } from '../services/market.service.ts';
import type { Currency } from '../types/auth.ts';

export function useActivePortfolioData(selectedYear?: number) {
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
    monthlyCpi,
    loading,
    error,
  } = useAppSelector((state) => state.portfolio);

  const activePortfolio = portfolios.find((p) => p.id === activePortfolioId);

  useEffect(() => {
    if (portfolios.length === 0) {
      dispatch(fetchPortfolios(baseCurrency));
    }
  }, [dispatch, portfolios.length, baseCurrency]);

  useEffect(() => {
    if (activePortfolioId) {
      dispatch(fetchPositions(activePortfolioId));
      dispatch(fetchTransactions(activePortfolioId));
    }
  }, [dispatch, activePortfolioId]);

  useEffect(() => {
    if (cpi === null) {
      dispatch(fetchInflation());
    }
  }, [dispatch, cpi]);

  useEffect(() => {
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
            dispatch(setMonthlyCpi(map));
          }
        })
        .catch(() => {});
    }
  }, [dispatch, selectedYear]);

  const refreshData = useCallback(() => {
    if (activePortfolioId) {
      dispatch(fetchPositions(activePortfolioId));
      dispatch(fetchTransactions(activePortfolioId));
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
            dispatch(setMonthlyCpi(map));
          }
        })
        .catch(() => {});
    }
  }, [dispatch, activePortfolioId, selectedYear]);

  const resolvedCpi = cpi ?? 3.6;

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
    cpi: resolvedCpi,
    rawCpi: cpi,
    monthlyCpi,
    baseCurrency: activePortfolio?.baseCurrency || baseCurrency,
    loading,
    error,
    refreshData,
  };
}
