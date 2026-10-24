import { useState, useEffect, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useActivePortfolioData } from '../hooks/useActivePortfolioData.ts';
import { marketService } from '../services/market.service.ts';
import { setActivePortfolioId } from '../store/slices/portfolio.slice.ts';
import { TransactionModal } from '../components/portfolio/TransactionModal.tsx';
import { CreatePortfolioModal } from '../components/portfolio/CreatePortfolioModal.tsx';
import { DashboardHeader } from '../components/dashboard/DashboardHeader.tsx';
import { KpiGrid } from '../components/dashboard/KpiGrid.tsx';
import { PortfolioChart } from '../components/dashboard/PortfolioChart.tsx';
import { FilterBar } from '../components/dashboard/FilterBar.tsx';
import { PositionsTable } from '../components/dashboard/PositionsTable.tsx';
import {
  generateUnifiedPortfolioTimeline,
  calculateBondCurrentPrice,
  isBondItem,
} from '../utils/portfolioUnifiedEngine.ts';

import {
  buildDashboardPositions,
  buildDashboardSummary,

} from '../utils/dashboardCalculations.ts';
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
import type { PortfolioStatsResponse } from '../types/analytics.types.ts';

export const DashboardPage = () => {
  const { t } = useTranslation();

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
    baseCurrency,
    loading,
    refreshData,
  } = useActivePortfolioData();

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isCreatePortfolioOpen, setIsCreatePortfolioOpen] = useState(false);
  const [editingTx, setEditingTx] = useState<TransactionResponse | null>(null);
  const [taxMode, setTaxMode] = useState<TaxMode>('NETTO');
  const [timeframe, setTimeframe] = useState<TimeframeOption>('YTD');
  const [metricMode, setMetricMode] = useState<ChartMetricMode>('PLN');
  const [projectionHorizon, setProjectionHorizon] = useState<ProjectionHorizonOption>('OFF');
  const [userMonthlyDca, setUserMonthlyDca] = useState<number | null>(null);
  const [dcaBondShare, setDcaBondShare] = useState<number>(0.5);
  const [expectedEtfReturn, setExpectedEtfReturn] = useState<number>(8.5);
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

  const enrichedHoldings = useMemo(() => {
    return positions.map((pos) => {
      const inst = instruments[pos.instrumentId];
      const isBond = isBondItem(pos, inst);
      const symbol = pos.seriesName || inst?.symbol || 'UNKNOWN';
      const fxRate = (pos.currency === baseCurrency ? 1 : rates[pos.currency]) || 1;

      const currentPrice = isBond
        ? calculateBondCurrentPrice({
            purchaseDate: pos.purchaseDate,
            bondRate: pos.bondRate,
            bondMargin: pos.bondMargin,
            cpi,
            targetDate: nowTimestamp,
            seriesName: pos.seriesName,
            termMonths: pos.termMonths,
          })
        : (quotes[symbol]?.price && quotes[symbol].price > 0 ? quotes[symbol].price : pos.averageBuyPrice);

      let previousPrice = currentPrice;
      if (isBond) {
        previousPrice = calculateBondCurrentPrice({
          purchaseDate: pos.purchaseDate,
          bondRate: pos.bondRate,
          bondMargin: pos.bondMargin,
          cpi,
          targetDate: nowTimestamp - 86400000,
          seriesName: pos.seriesName,
          termMonths: pos.termMonths,
        });
      } else if (quotes[symbol]?.previousClose && quotes[symbol].previousClose! > 0) {
        previousPrice = quotes[symbol].previousClose!;
      } else if (historicalPrices && historicalSortedDates && inst?.symbol && historicalPrices[inst.symbol]) {
        const dates = historicalSortedDates[inst.symbol];
        const dateMap = historicalPrices[inst.symbol];
        if (dates && dates.length > 0) {
          const dNow = new Date(nowTimestamp);
          const todayDateStr = `${dNow.getFullYear()}-${String(dNow.getMonth() + 1).padStart(2, '0')}-${String(dNow.getDate()).padStart(2, '0')}`;
          const lastDate = dates[dates.length - 1];
          if (lastDate >= todayDateStr && dates.length >= 2) {
            previousPrice = dateMap[dates[dates.length - 2]] ?? currentPrice;
          } else {
            previousPrice = dateMap[lastDate] ?? currentPrice;
          }
        }
      }

      const totalVal = pos.quantity * currentPrice * fxRate;
      const totalCostVal = pos.totalCostBase !== undefined ? pos.totalCostBase : pos.quantity * pos.averageBuyPrice * fxRate;
      const profit = totalVal - totalCostVal;
      const profitPct = totalCostVal > 0 ? (profit / totalCostVal) * 100 : 0;
      const dailyChange = pos.quantity * (currentPrice - previousPrice) * fxRate;

      let assetType: 'ETF' | 'BOND' | 'STOCK' = 'ETF';
      if (isBond) {
        assetType = 'BOND';
      } else if (inst?.type === 'STOCK') {
        assetType = 'STOCK';
      }

      return {
        id: pos.id,
        instrumentId: pos.instrumentId,
        ticker: symbol,
        name: inst?.name || symbol,
        assetType,
        isBond,
        quantity: pos.quantity,
        averagePrice: pos.averageBuyPrice,
        currentPrice,
        previousPrice,
        currency: pos.currency,
        totalValue: totalVal,
        totalCost: totalCostVal,
        profit,
        profitPct,
        dailyChange,
        purchaseDate: pos.purchaseDate,
        bondRate: pos.bondRate,
        bondMargin: pos.bondMargin,
      };
    });
  }, [positions, instruments, quotes, rates, baseCurrency, cpi, nowTimestamp, historicalPrices, historicalSortedDates]);

  const equityHoldings = useMemo(
    () =>
      enrichedHoldings
        .filter((h) => !h.isBond)
        .map((h) => ({ symbol: h.ticker, weight: 0, value: h.totalValue })),
    [enrichedHoldings]
  );

  const empiricalRequestKey = useMemo(() => {
    if (!activePortfolioId || equityHoldings.length === 0) {
      return null;
    }
    const holdingsKey = [...equityHoldings]
      .map((h) => `${h.symbol}:${h.value.toFixed(2)}`)
      .sort()
      .join('|');
    return `${activePortfolioId}|${holdingsKey}`;
  }, [activePortfolioId, equityHoldings]);
  const [rawEmpiricalStats, setRawEmpiricalStats] = useState<{
    requestKey: string;
    data: PortfolioStatsResponse;
  } | null>(null);
  const empiricalStats =
    empiricalRequestKey && rawEmpiricalStats?.requestKey === empiricalRequestKey
      ? rawEmpiricalStats.data
      : null;
  const populatedPortfolioRef = useRef<string | null>(null);
  const latestEmpiricalRequestKeyRef = useRef<string | null>(null);

  useEffect(() => {
    if (!empiricalRequestKey) {
      latestEmpiricalRequestKeyRef.current = null;
      return;
    }

    let isCancelled = false;
    latestEmpiricalRequestKeyRef.current = empiricalRequestKey;

    marketService
      .getPortfolioStats({ holdings: equityHoldings })
      .then((stats) => {
        if (!isCancelled && stats && latestEmpiricalRequestKeyRef.current === empiricalRequestKey) {
          setRawEmpiricalStats({ requestKey: empiricalRequestKey, data: stats });
          if (activePortfolioId && populatedPortfolioRef.current !== activePortfolioId) {
            populatedPortfolioRef.current = activePortfolioId;
            setExpectedEtfReturn(Math.round(stats.weightedReturn * 1000) / 10);
          }
        }
      })
      .catch(() => {});

    return () => {
      isCancelled = true;
    };
  }, [equityHoldings, activePortfolioId, empiricalRequestKey]);

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
    return 2000;
  }, [transactions, totalPortfolioCost, nowTimestamp]);

  const monthlyDca = userMonthlyDca ?? defaultDca;

  const isIkeAccount = activePortfolio?.name.toLowerCase().includes('ike') || false;
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
      }),
    [enrichedHoldings, transactions, totalPortfolioValue, baseCurrency, rates, shouldApplyTax, nowTimestamp]
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
      etfVolatility: empiricalStats ? Math.round(empiricalStats.weightedVolatility * 1000) / 10 : 15.0,
      empiricalTer: empiricalStats ? Math.round(empiricalStats.weightedTer * 10000) / 100 : undefined,
      bondRollover,
      isIkeAccount,
      historicalPrices,
      historicalSortedDates,
      inflationAdjustMode,
      targetGoal,
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
      />

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
        onExpectedEtfReturnChange={setExpectedEtfReturn}
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

      <CreatePortfolioModal
        isOpen={isCreatePortfolioOpen}
        onClose={() => setIsCreatePortfolioOpen(false)}
      />
    </div>
  );
};
