import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useActivePortfolioData } from '../hooks/useActivePortfolioData.ts';
import { clearPortfolioError, setActivePortfolioId } from '../store/slices/portfolio.slice.ts';
import { fetchPortfolioStats } from '../store/thunks/portfolio.thunk.ts';
import { ErrorBanner } from '../components/common/ErrorBanner.tsx';
import { TransactionModal } from '../components/portfolio/TransactionModal.tsx';
import { CreatePortfolioModal } from '../components/portfolio/CreatePortfolioModal.tsx';
import { TransactionHistoryModal } from '../components/portfolio/TransactionHistoryModal.tsx';
import { DashboardHeader } from '../components/dashboard/DashboardHeader.tsx';
import { KpiGrid } from '../components/dashboard/KpiGrid.tsx';
import { PortfolioChart } from '../components/dashboard/PortfolioChart.tsx';
import { FilterBar } from '../components/dashboard/FilterBar.tsx';
import { PositionsTable } from '../components/dashboard/PositionsTable.tsx';
import { generateUnifiedPortfolioTimeline } from '../utils/engines/timeline/index.ts';
import { enrichPortfolioHoldings } from '../utils/domain/holdings.ts';
import { isIkePortfolio } from '../utils/domain/tax.ts';
import {
  buildDashboardPositions,
  buildDashboardSummary,
} from '../utils/engines/dashboard.ts';
import type {
  TaxMode,
  TimeframeOption,
  ChartMetricMode,
  ProjectionHorizonOption,
  InflationAdjustMode,
  AssetFilterType,
  SortOption,
} from '../types/dashboard.ts';
import type { TransactionResponse } from '../types/portfolio.ts';

export const DashboardPage = () => {
  const { t, i18n } = useTranslation();

  const {
    dispatch,
    activePortfolioId,
    portfolios,
    activePortfolio,
    positions,
    transactions,
    instruments,
    quotes,
    rates,
    historicalPrices,
    historicalSortedDates,
    cpi,
    portfolioStats,
    baseCurrency,
    loading,
    error,
    refreshData,
  } = useActivePortfolioData();

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isCreatePortfolioOpen, setIsCreatePortfolioOpen] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [editingTx, setEditingTx] = useState<TransactionResponse | null>(null);
  const [taxMode, setTaxMode] = useState<TaxMode>('NETTO');
  const [timeframe, setTimeframe] = useState<TimeframeOption>('YTD');
  const [metricMode, setMetricMode] = useState<ChartMetricMode>('PLN');
  const [projectionHorizon, setProjectionHorizon] = useState<ProjectionHorizonOption>('OFF');
  const [userMonthlyDca, setUserMonthlyDca] = useState<number | null>(null);
  const [dcaBondShare, setDcaBondShare] = useState<number>(0.5);
  const [userEtfReturnOverride, setUserEtfReturnOverride] = useState<{
    portfolioId: string | null;
    value: number;
  } | null>(null);
  const [bondRollover, setBondRollover] = useState<boolean>(true);
  const [inflationAdjustMode, setInflationAdjustMode] = useState<InflationAdjustMode>('NOMINAL');
  const [targetGoal, setTargetGoal] = useState<number | null>(null);
  const [nowTimestamp] = useState(() => Date.now());

  const [searchQuery, setSearchQuery] = useState('');
  const [activeFilter, setActiveFilter] = useState<AssetFilterType>('ALL');
  const [sortBy, setSortBy] = useState<SortOption>('value');

  const handleRefresh = () => {
    refreshData();
  };

  const enrichedHoldings = useMemo(
    () =>
      enrichPortfolioHoldings({
        positions,
        transactions,
        instruments,
        quotes,
        rates,
        baseCurrency,
        cpi,
        nowTimestamp,
        historicalPrices,
        historicalSortedDates,
      }),
    [positions, transactions, instruments, quotes, rates, baseCurrency, cpi, nowTimestamp, historicalPrices, historicalSortedDates]
  );

  const { empiricalRequestKey, equityStatsPayload } = useMemo(() => {
    const equities = enrichedHoldings.filter((h) => !h.isBond);
    if (!activePortfolioId || equities.length === 0) {
      return { empiricalRequestKey: null, equityStatsPayload: [] };
    }
    const totalEquityVal = equities.reduce((sum, h) => sum + Math.max(0, h.totalValue), 0);
    const payload = equities.map((h) => ({ symbol: h.ticker, weight: 0, value: h.totalValue }));
    const weightsKey = equities
      .map((h) => {
        const weightPerMille = totalEquityVal > 0 ? Math.round((h.totalValue / totalEquityVal) * 1000) : 0;
        return `${h.ticker}:${weightPerMille}`;
      })
      .sort()
      .join('|');
    return {
      empiricalRequestKey: `${activePortfolioId}|${weightsKey}`,
      equityStatsPayload: payload,
    };
  }, [activePortfolioId, enrichedHoldings]);

  const empiricalStats = empiricalRequestKey ? (portfolioStats[empiricalRequestKey] ?? null) : null;

  useEffect(() => {
    if (!empiricalRequestKey || equityStatsPayload.length === 0 || empiricalStats) {
      return;
    }
    dispatch(fetchPortfolioStats({ key: empiricalRequestKey, holdings: equityStatsPayload }));
  }, [dispatch, empiricalRequestKey, equityStatsPayload, empiricalStats]);

  const defaultExpectedEtfReturn =
    empiricalStats?.weightedReturn != null
      ? Math.round(empiricalStats.weightedReturn * 1000) / 10
      : 8.5;

  const expectedEtfReturn =
    userEtfReturnOverride && userEtfReturnOverride.portfolioId === activePortfolioId
      ? userEtfReturnOverride.value
      : defaultExpectedEtfReturn;

  const totalPortfolioValue = useMemo(() => {
    return enrichedHoldings.reduce((sum, h) => sum + h.totalValue, 0);
  }, [enrichedHoldings]);

  const totalPortfolioCost = useMemo(() => {
    return enrichedHoldings.reduce((sum, h) => sum + h.totalCost, 0);
  }, [enrichedHoldings]);

  const defaultDca = useMemo(() => {
    if (transactions.length > 0 && totalPortfolioCost > 0) {
      const timestamps = transactions.map((t) => new Date(t.executedAt).getTime());
      const earliest = Math.min(...timestamps);
      const monthsElapsed = Math.max(1, (nowTimestamp - earliest) / (1000 * 60 * 60 * 24 * 30.4375));
      const avgMonthly = totalPortfolioCost / monthsElapsed;
      return Math.max(100, Math.round(avgMonthly / 100) * 100);
    }
    return 0;
  }, [transactions, totalPortfolioCost, nowTimestamp]);

  const monthlyDca = userMonthlyDca ?? defaultDca;

  const isIkeAccount = isIkePortfolio(activePortfolio);
  const shouldApplyTax = taxMode === 'NETTO' && !isIkeAccount;

  const dashboardPositions = useMemo(
    () =>
      buildDashboardPositions({
        enrichedHoldings,
        transactions,
        totalPortfolioValue,
        baseCurrency,
        rates,
        shouldApplyTax,
        nowTimestamp,
        t,
        locale: i18n.language,
      }),
    [enrichedHoldings, transactions, totalPortfolioValue, baseCurrency, rates, shouldApplyTax, nowTimestamp, t, i18n.language]
  );

  const dashboardSummary = useMemo(
    () =>
      buildDashboardSummary({
        totalPortfolioValue,
        totalPortfolioCost,
        enrichedHoldings,
        positions,
        transactions,
        baseCurrency,
        rates,
        cpi,
        taxMode,
        shouldApplyTax,
        isIkeAccount,
        nowTimestamp,
        t: (k: string, d?: string) => (d !== undefined ? t(k, d) : t(k)),
      }),
    [
      totalPortfolioValue,
      totalPortfolioCost,
      enrichedHoldings,
      positions,
      transactions,
      baseCurrency,
      rates,
      cpi,
      taxMode,
      shouldApplyTax,
      isIkeAccount,
      nowTimestamp,
      t,
    ]
  );

  const chartData = useMemo(() => {
    const instrumentPrices: Record<string, number> = {};
    enrichedHoldings.forEach((h) => {
      instrumentPrices[h.instrumentId] = h.currentPrice;
    });

    return generateUnifiedPortfolioTimeline({
      transactions,
      positions,
      instruments,
      totalPortfolioValue,
      totalPortfolioCost,
      taxMode,
      timeframe,
      projectionHorizon,
      cpi,
      rates,
      baseCurrency,
      instrumentPrices,
      monthlyDca,
      dcaBondShare,
      expectedEtfReturn,
      etfVolatility: empiricalStats?.weightedVolatility != null ? Math.round(empiricalStats.weightedVolatility * 1000) / 10 : undefined,
      empiricalTer: empiricalStats?.weightedTer != null ? Math.round(empiricalStats.weightedTer * 10000) / 100 : undefined,
      correlationMatrix: empiricalStats?.correlationMatrix,
      bondRollover,
      isIkeAccount,
      historicalPrices,
      historicalSortedDates,
      inflationAdjustMode,
      targetGoal,
      locale: i18n.language,
    });
  }, [
    transactions,
    positions,
    instruments,
    totalPortfolioValue,
    totalPortfolioCost,
    taxMode,
    timeframe,
    projectionHorizon,
    cpi,
    rates,
    baseCurrency,
    enrichedHoldings,
    monthlyDca,
    dcaBondShare,
    expectedEtfReturn,
    bondRollover,
    isIkeAccount,
    historicalPrices,
    historicalSortedDates,
    inflationAdjustMode,
    targetGoal,
    empiricalStats,
    i18n.language,
  ]);

  const filteredPositions = useMemo(() => {
    return dashboardPositions
      .filter((pos) => {
        const matchesQuery =
          pos.ticker.toLowerCase().includes(searchQuery.toLowerCase()) ||
          pos.name.toLowerCase().includes(searchQuery.toLowerCase());

        const matchesType =
          activeFilter === 'ALL' || pos.type === activeFilter;

        return matchesQuery && matchesType;
      })
      .sort((a, b) => {
        if (sortBy === 'share' || sortBy === 'value') return b.sharePercent - a.sharePercent;
        if (sortBy === 'profit') {
          const profitA = parseFloat(a.profitPercentFormatted.replace(/[^0-9.-]/g, '')) || 0;
          const profitB = parseFloat(b.profitPercentFormatted.replace(/[^0-9.-]/g, '')) || 0;
          return profitB - profitA;
        }
        if (sortBy === 'name') return a.ticker.localeCompare(b.ticker);
        if (sortBy === 'xirr') {
          const hasA = a.xirr != null && !a.isXirrFallback;
          const hasB = b.xirr != null && !b.isXirrFallback;
          if (!hasA && !hasB) return b.sharePercent - a.sharePercent;
          if (!hasA) return 1;
          if (!hasB) return -1;
          return b.xirr! - a.xirr!;
        }
        return b.sharePercent - a.sharePercent;
      });
  }, [dashboardPositions, searchQuery, activeFilter, sortBy]);

  return (
    <div className="w-full max-w-[1920px] mx-auto px-6 lg:px-8 py-6 space-y-4">
      <DashboardHeader
        positionCount={dashboardSummary.positionCount}
        portfolios={portfolios}
        activePortfolioId={activePortfolioId}
        loading={loading}
        taxMode={taxMode}
        onTaxModeChange={setTaxMode}
        onSelectPortfolio={(id) => dispatch(setActivePortfolioId(id))}
        onCreatePortfolio={() => setIsCreatePortfolioOpen(true)}
        onRefresh={handleRefresh}
        onAddTransaction={() => setIsModalOpen(true)}
        onOpenTransactionHistory={() => setIsHistoryOpen(true)}
      />

      <ErrorBanner error={error} onDismiss={() => dispatch(clearPortfolioError())} />

      <KpiGrid summary={dashboardSummary} />

      <PortfolioChart
        data={chartData}
        baseCurrency={baseCurrency}
        taxMode={taxMode}
        timeframe={timeframe}
        onTimeframeChange={setTimeframe}
        metricMode={metricMode}
        onMetricModeChange={setMetricMode}
        projectionHorizon={projectionHorizon}
        onProjectionHorizonChange={setProjectionHorizon}
        monthlyDca={monthlyDca}
        onMonthlyDcaChange={setUserMonthlyDca}
        dcaBondShare={dcaBondShare}
        onDcaBondShareChange={setDcaBondShare}
        expectedEtfReturn={expectedEtfReturn}
        onExpectedEtfReturnChange={(val) =>
          setUserEtfReturnOverride({ portfolioId: activePortfolioId, value: val })
        }
        bondRollover={bondRollover}
        onBondRolloverChange={setBondRollover}
        inflationAdjustMode={inflationAdjustMode}
        onInflationAdjustModeChange={setInflationAdjustMode}
        targetGoal={targetGoal}
        onTargetGoalChange={setTargetGoal}
        empiricalStats={empiricalStats}
      />

      <FilterBar
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        activeFilter={activeFilter}
        onFilterChange={setActiveFilter}
        sortBy={sortBy}
        onSortChange={setSortBy}
      />

      <PositionsTable
        positions={filteredPositions}
        taxMode={taxMode}
        onAddTransaction={() => setIsModalOpen(true)}
        onEditTransaction={(txId) => {
          const tx = transactions.find((t) => t.id === txId);
          if (tx) {
            setEditingTx(tx);
          }
        }}
      />

      <TransactionModal
        isOpen={isModalOpen || Boolean(editingTx)}
        onClose={() => {
          setIsModalOpen(false);
          setEditingTx(null);
        }}
        portfolioId={activePortfolioId || ''}
        defaultCurrency={baseCurrency}
        transaction={editingTx}
        instrument={editingTx ? instruments[editingTx.instrumentId] : undefined}
      />

      <TransactionHistoryModal
        isOpen={isHistoryOpen}
        onClose={() => setIsHistoryOpen(false)}
        portfolioId={activePortfolioId || ''}
        transactions={transactions}
        instruments={instruments}
        baseCurrency={baseCurrency}
        onEditTransaction={(tx) => setEditingTx(tx)}
      />

      <CreatePortfolioModal
        isOpen={isCreatePortfolioOpen}
        onClose={() => setIsCreatePortfolioOpen(false)}
      />
    </div>
  );
};
