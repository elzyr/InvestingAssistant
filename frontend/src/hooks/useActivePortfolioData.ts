import { useCallback, useEffect } from 'react';
import { useAppDispatch, useAppSelector } from '../store/store.ts';
import {
  fetchAnnualReport,
  fetchPortfolios,
  fetchTimeline,
  fetchTransactions,
  fetchValuation,
  reportKey,
  timelineKey,
  valuationKey,
} from '../store/thunks/portfolio.thunk.ts';
import type { Currency } from '../types/auth.ts';
import type { TaxMode, TimeframeOption } from '../types/dashboard.ts';
import type { TransactionResponse } from '../types/portfolio.ts';

export interface ActivePortfolioFetchParams {
  activePortfolioId: string | null;
  baseCurrency?: Currency;
  taxMode: TaxMode;
  timeframe: TimeframeOption;
  reportYear?: number;
  skipValuation?: boolean;
  skipTimeline?: boolean;
  skipTransactions?: boolean;
  force?: boolean;
}

export function dispatchPortfolioDataLoad(
  dispatch: (action: unknown) => unknown,
  params: ActivePortfolioFetchParams
): unknown {
  const {
    activePortfolioId,
    baseCurrency,
    taxMode,
    timeframe,
    reportYear,
    skipValuation,
    skipTimeline,
    skipTransactions,
    force = false,
  } = params;

  if (!activePortfolioId) {
    if (force) {
      return dispatch(fetchPortfolios(baseCurrency));
    }
    return undefined;
  }

  const forceOpt = force ? { force: true } : {};
  const promises: Array<unknown> = [];
  if (!skipTransactions) {
    promises.push(dispatch(fetchTransactions(force ? { portfolioId: activePortfolioId, force: true } : activePortfolioId)));
  }
  if (!skipValuation) {
    promises.push(dispatch(fetchValuation({ portfolioId: activePortfolioId, taxMode, ...forceOpt })));
  }
  if (!skipTimeline) {
    promises.push(dispatch(fetchTimeline({ portfolioId: activePortfolioId, timeframe, taxMode, ...forceOpt })));
  }
  if (reportYear != null) {
    promises.push(dispatch(fetchAnnualReport({ portfolioId: activePortfolioId, year: reportYear, taxMode, ...forceOpt })));
  }
  return Promise.all(promises);
}

const EMPTY_TRANSACTIONS: TransactionResponse[] = [];

export interface ActivePortfolioDataOptions {
  taxMode?: TaxMode;
  timeframe?: TimeframeOption;
  reportYear?: number;
  skipValuation?: boolean;
  skipTimeline?: boolean;
  skipTransactions?: boolean;
}

export function useActivePortfolioData(options?: ActivePortfolioDataOptions) {
  const dispatch = useAppDispatch();
  const taxMode: TaxMode = options?.taxMode ?? 'NETTO';
  const timeframe: TimeframeOption = options?.timeframe ?? 'YTD';
  const reportYear = options?.reportYear;

  const {
    portfolios,
    portfoliosStatus,
    activePortfolioId,
    valuations,
    timelines,
    latestTimelineByPortfolio,
    reports,
    transactionsByPortfolio,
    instruments,
    portfolioStats,
    portfolioStatsErrors,
    projection,
    loading,
    valuationPendingCount,
    timelinePendingCount,
    reportPendingCount,
    transactionsPendingCount,
    error,
    cacheRevision,
  } = useAppSelector((state) => state.portfolio);
  const isValuationLoading = valuationPendingCount > 0;
  const isTimelineLoading = timelinePendingCount > 0;
  const isReportLoading = reportPendingCount > 0;
  const isTransactionsLoading = transactionsPendingCount > 0;
  const { user } = useAppSelector((state) => state.auth);

  const activePortfolio =
    portfolios.find((p) => p.id === activePortfolioId) || portfolios[0] || null;
  const baseCurrency = activePortfolio?.baseCurrency || user?.baseCurrency || 'PLN';
  const transactions = activePortfolioId
    ? transactionsByPortfolio[activePortfolioId] ?? EMPTY_TRANSACTIONS
    : EMPTY_TRANSACTIONS;

  const vKey = activePortfolioId ? valuationKey(activePortfolioId, taxMode) : '';
  const tKey = activePortfolioId ? timelineKey(activePortfolioId, timeframe, taxMode) : '';
  const rKey =
    activePortfolioId && reportYear != null ? reportKey(activePortfolioId, reportYear, taxMode) : '';

  const valuation = vKey ? valuations[vKey] ?? null : null;
  const timeline =
    (tKey ? timelines[tKey] : undefined) ??
    (vKey ? latestTimelineByPortfolio[vKey] : undefined) ??
    null;
  const report = rKey ? reports[rKey] ?? null : null;

  useEffect(() => {
    if (portfoliosStatus === 'idle' && portfolios.length === 0) {
      dispatch(fetchPortfolios(user?.baseCurrency));
    }
  }, [dispatch, portfoliosStatus, portfolios.length, user?.baseCurrency]);

  useEffect(() => {
    dispatchPortfolioDataLoad(dispatch, {
      activePortfolioId,
      taxMode,
      timeframe,
      reportYear,
      skipValuation: options?.skipValuation,
      skipTimeline: options?.skipTimeline,
      skipTransactions: options?.skipTransactions,
      force: false,
    });
  }, [
    dispatch,
    activePortfolioId,
    taxMode,
    timeframe,
    reportYear,
    options?.skipValuation,
    options?.skipTimeline,
    options?.skipTransactions,
    cacheRevision,
  ]);

  const refreshData = useCallback(() => {
    dispatchPortfolioDataLoad(dispatch, {
      activePortfolioId,
      baseCurrency: user?.baseCurrency,
      taxMode,
      timeframe,
      reportYear,
      skipValuation: options?.skipValuation,
      skipTimeline: options?.skipTimeline,
      skipTransactions: options?.skipTransactions,
      force: true,
    });
  }, [
    dispatch,
    activePortfolioId,
    user?.baseCurrency,
    taxMode,
    timeframe,
    reportYear,
    options?.skipValuation,
    options?.skipTimeline,
    options?.skipTransactions,
  ]);

  return {
    dispatch,
    portfolios,
    activePortfolio,
    activePortfolioId,
    valuation,
    timeline,
    report,
    transactions,
    instruments,
    portfolioStats,
    portfolioStatsErrors,
    projection,
    baseCurrency,
    loading: loading || isValuationLoading || isTimelineLoading || isReportLoading,
    isValuationLoading,
    isTimelineLoading,
    isReportLoading,
    isTransactionsLoading,
    error,
    refreshData,
  };
}
