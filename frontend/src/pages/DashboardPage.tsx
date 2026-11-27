import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useActivePortfolioData } from '../hooks/useActivePortfolioData.ts';
import { clearPortfolioError, clearProjection, setActivePortfolioId } from '../store/slices/portfolio.slice.ts';
import { fetchPortfolioStats, fetchProjection } from '../store/thunks/portfolio.thunk.ts';
import { ErrorBanner } from '../components/common/ErrorBanner.tsx';
import { TransactionModal } from '../components/portfolio/TransactionModal.tsx';
import { CreatePortfolioModal } from '../components/portfolio/CreatePortfolioModal.tsx';
import { TransactionHistoryModal } from '../components/portfolio/TransactionHistoryModal.tsx';
import { DashboardHeader } from '../components/dashboard/DashboardHeader.tsx';
import { KpiGrid } from '../components/dashboard/KpiGrid.tsx';
import { PortfolioChart } from '../components/dashboard/PortfolioChart.tsx';
import { FilterBar } from '../components/dashboard/FilterBar.tsx';
import { PositionsTable } from '../components/dashboard/PositionsTable.tsx';
import {
  formatTimelineChartPoints,
  formatValuationPositions,
  formatValuationSummary,
} from '../utils/valuationFormatters.ts';
import {
  horizonToYears,
  mapProjectionResponseToChartPoints,
} from '../utils/projectionSimulator.ts';
import { roundOneDecimal } from '../utils/reportsFormatters.ts';
import { computeAverageMonthlyDca } from '../utils/portfolioSimulation.ts';
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

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isCreatePortfolioOpen, setIsCreatePortfolioOpen] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [editingTx, setEditingTx] = useState<TransactionResponse | null>(null);
  const [taxMode, setTaxMode] = useState<TaxMode>('NETTO');
  const [timeframe, setTimeframe] = useState<TimeframeOption>('YTD');
  const [metricMode, setMetricMode] = useState<ChartMetricMode>('PLN');
  const [projectionHorizon, setProjectionHorizon] = useState<ProjectionHorizonOption>('OFF');
  const [userMonthlyDcaOverride, setUserMonthlyDcaOverride] = useState<{
    portfolioId: string | null;
    value: number;
  } | null>(null);
  const [userDcaBondShareOverride, setUserDcaBondShareOverride] = useState<{
    portfolioId: string | null;
    value: number;
  } | null>(null);
  const [userEtfReturnOverride, setUserEtfReturnOverride] = useState<{
    portfolioId: string | null;
    value: number | null;
  } | null>(null);
  const [bondRollover, setBondRollover] = useState<boolean>(true);
  const [inflationAdjustMode, setInflationAdjustMode] = useState<InflationAdjustMode>('NOMINAL');
  const [targetGoalOverride, setTargetGoalOverride] = useState<{
    portfolioId: string | null;
    value: number | null;
  } | null>(null);
  const [nowTimestamp] = useState(() => Date.now());

  const {
    dispatch,
    activePortfolioId,
    portfolios,
    valuation,
    timeline,
    transactions,
    instruments,
    portfolioStats,
    portfolioStatsErrors,
    projection: projectionData,
    baseCurrency,
    loading,
    cacheRevision,
    error,
    refreshData,
  } = useActivePortfolioData({ taxMode, timeframe });

  const [searchQuery, setSearchQuery] = useState('');
  const [activeFilter, setActiveFilter] = useState<AssetFilterType>('ALL');
  const [sortBy, setSortBy] = useState<SortOption>('value');

  const { empiricalRequestKey, equityStatsPayload } = useMemo(() => {
    const equities = valuation?.projectionSeed?.equityWeights || [];
    if (!activePortfolioId || equities.length === 0) {
      return { empiricalRequestKey: null, equityStatsPayload: [] };
    }
    const payload = equities.map((ew) => ({
      symbol: ew.symbol,
      weight: 0,
      value: ew.valueBase,
    }));
    const weightsKey = equities
      .map((ew) => `${ew.symbol}:${Math.round(ew.weightPct * 10)}`)
      .sort()
      .join('|');
    return {
      empiricalRequestKey: `${activePortfolioId}|${weightsKey}`,
      equityStatsPayload: payload,
    };
  }, [activePortfolioId, valuation]);

  const empiricalStats = empiricalRequestKey ? (portfolioStats[empiricalRequestKey] ?? null) : null;
  const empiricalStatsError = empiricalRequestKey
    ? (portfolioStatsErrors[empiricalRequestKey] ?? null)
    : null;

  useEffect(() => {
    if (
      !empiricalRequestKey ||
      equityStatsPayload.length === 0 ||
      empiricalStats ||
      empiricalStatsError
    ) {
      return;
    }
    dispatch(fetchPortfolioStats({ key: empiricalRequestKey, holdings: equityStatsPayload }));
  }, [dispatch, empiricalRequestKey, equityStatsPayload, empiricalStats, empiricalStatsError]);

  const defaultExpectedEtfReturn =
    empiricalStats?.weightedReturn != null
      ? roundOneDecimal(empiricalStats.weightedReturn * 100)
      : null;

  const expectedEtfReturn =
    userEtfReturnOverride && userEtfReturnOverride.portfolioId === activePortfolioId
      ? userEtfReturnOverride.value
      : defaultExpectedEtfReturn;

  const totalPortfolioCost = valuation?.summary?.totalInvested ?? 0;

  const defaultDca = useMemo(
    () => computeAverageMonthlyDca(transactions, totalPortfolioCost, nowTimestamp),
    [transactions, totalPortfolioCost, nowTimestamp]
  );

  const monthlyDca =
    userMonthlyDcaOverride && userMonthlyDcaOverride.portfolioId === activePortfolioId
      ? userMonthlyDcaOverride.value
      : defaultDca;

  const defaultDcaBondShare = useMemo(() => {
    const seed = valuation?.projectionSeed;
    if (!seed || seed.startTotalValue <= 0) return 0.5;
    const share = seed.startBondValue / seed.startTotalValue;
    return Math.min(1, Math.max(0, Number(share.toFixed(2))));
  }, [valuation]);

  const dcaBondShare =
    userDcaBondShareOverride && userDcaBondShareOverride.portfolioId === activePortfolioId
      ? userDcaBondShareOverride.value
      : defaultDcaBondShare;

  const targetGoal =
    targetGoalOverride && targetGoalOverride.portfolioId === activePortfolioId
      ? targetGoalOverride.value
      : null;

  const userEtfReturnVal =
    userEtfReturnOverride && userEtfReturnOverride.portfolioId === activePortfolioId
      ? userEtfReturnOverride.value
      : null;

  useEffect(() => {
    const horizonYears = horizonToYears(projectionHorizon);
    if (!activePortfolioId || horizonYears <= 0) {
      dispatch(clearProjection());
      return;
    }
    let activePromise: { abort: () => void } | null = null;
    const timer = setTimeout(() => {
      activePromise = dispatch(
        fetchProjection({
          portfolioId: activePortfolioId,
          request: {
            horizonYears,
            monthlyDca,
            dcaEquityShare: Number((1 - dcaBondShare).toFixed(4)),
            taxMode,
            realMode: inflationAdjustMode === 'REAL',
            bondRollover,
            targetGoal,
            etfReturnOverridePct: userEtfReturnVal,
          },
        })
      );
    }, 180);

    return () => {
      clearTimeout(timer);
      activePromise?.abort();
    };
  }, [
    dispatch,
    activePortfolioId,
    projectionHorizon,
    monthlyDca,
    dcaBondShare,
    taxMode,
    inflationAdjustMode,
    bondRollover,
    targetGoal,
    userEtfReturnVal,
    cacheRevision,
  ]);

  const dashboardPositions = useMemo(
    () => formatValuationPositions(valuation, t, i18n.language),
    [valuation, t, i18n.language]
  );

  const dashboardSummary = useMemo(
    () => formatValuationSummary(valuation, t),
    [valuation, t]
  );

  const historicalPoints = useMemo(
    () => formatTimelineChartPoints(timeline, t, i18n.language),
    [timeline, t, i18n.language]
  );

  const activeProjection = projectionHorizon === 'OFF' ? null : projectionData;

  const chartData = useMemo(() => {
    if (projectionHorizon === 'OFF' || historicalPoints.length === 0 || !activeProjection) {
      return historicalPoints;
    }
    const future = mapProjectionResponseToChartPoints(activeProjection, {
      locale: i18n.language,
      weightedTer:
        empiricalStats?.weightedTer != null
          ? Math.round(empiricalStats.weightedTer * 10000) / 100
          : undefined,
      diversifiedVolatility:
        empiricalStats?.weightedVolatility != null
          ? roundOneDecimal(empiricalStats.weightedVolatility * 100)
          : undefined,
    });
    return [...historicalPoints, ...future];
  }, [
    historicalPoints,
    projectionHorizon,
    activeProjection,
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
        if (sortBy === 'value') return b.valueBase - a.valueBase;
        if (sortBy === 'share') return b.sharePercent - a.sharePercent;
        if (sortBy === 'profit') return b.profitPct - a.profitPct;
        if (sortBy === 'name') return a.ticker.localeCompare(b.ticker);
        if (sortBy === 'xirr') {
          const hasA = a.xirr != null;
          const hasB = b.xirr != null;
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
        onRefresh={refreshData}
        onAddTransaction={() => {
          if (activePortfolioId) setIsModalOpen(true);
        }}
        onOpenTransactionHistory={() => {
          if (activePortfolioId) setIsHistoryOpen(true);
        }}
      />

      <ErrorBanner error={error} onDismiss={() => dispatch(clearPortfolioError())} />

      {valuation?.positions?.some((p) => p.isPriceUnavailable || p.isCpiUnavailable) && (
        <div
          role="status"
          className="mb-4 rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-2.5 text-sm text-amber-200"
        >
          {t('dashboard.marketDataDegraded')}
        </div>
      )}

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
        onMonthlyDcaChange={(val) =>
          setUserMonthlyDcaOverride({ portfolioId: activePortfolioId, value: val })
        }
        dcaBondShare={dcaBondShare}
        onDcaBondShareChange={(val) =>
          setUserDcaBondShareOverride({ portfolioId: activePortfolioId, value: val })
        }
        expectedEtfReturn={expectedEtfReturn}
        onExpectedEtfReturnChange={(val) =>
          setUserEtfReturnOverride({ portfolioId: activePortfolioId, value: val })
        }
        bondRollover={bondRollover}
        onBondRolloverChange={setBondRollover}
        inflationAdjustMode={inflationAdjustMode}
        onInflationAdjustModeChange={setInflationAdjustMode}
        targetGoal={targetGoal}
        onTargetGoalChange={(val) =>
          setTargetGoalOverride({ portfolioId: activePortfolioId, value: val })
        }
        empiricalStats={empiricalStats}
        proxyWarnings={activeProjection?.proxyWarnings}
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
        onAddTransaction={activePortfolioId ? () => setIsModalOpen(true) : undefined}
        onEditTransaction={(txId) => {
          const tx = transactions.find((t) => t.id === txId);
          if (tx) {
            setEditingTx(tx);
          }
        }}
      />

      {activePortfolioId && (
        <>
          <TransactionModal
            isOpen={isModalOpen || Boolean(editingTx)}
            onClose={() => {
              setIsModalOpen(false);
              setEditingTx(null);
            }}
            portfolioId={activePortfolioId}
            defaultCurrency={baseCurrency}
            transaction={editingTx}
            instrument={editingTx ? instruments[editingTx.instrumentId] : undefined}
          />

          <TransactionHistoryModal
            isOpen={isHistoryOpen}
            onClose={() => setIsHistoryOpen(false)}
            portfolioId={activePortfolioId}
            transactions={transactions}
            instruments={instruments}
            baseCurrency={baseCurrency}
            onEditTransaction={(tx) => setEditingTx(tx)}
          />
        </>
      )}

      <CreatePortfolioModal
        isOpen={isCreatePortfolioOpen}
        onClose={() => setIsCreatePortfolioOpen(false)}
        defaultCurrency={baseCurrency}
      />
    </div>
  );
};
