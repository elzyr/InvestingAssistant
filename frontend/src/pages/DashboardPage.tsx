import { useState, useEffect, useMemo, useRef } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks.ts';
import {
  fetchPortfolios,
  fetchPositions,
  fetchTransactions,
} from '../store/thunks/portfolio.thunk.ts';
import { setActivePortfolioId } from '../store/slices/portfolio.slice.ts';
import { TransactionModal } from '../components/portfolio/TransactionModal.tsx';
import { CreatePortfolioModal } from '../components/portfolio/CreatePortfolioModal.tsx';
import { DashboardHeader } from '../components/dashboard/DashboardHeader.tsx';
import { KpiGrid } from '../components/dashboard/KpiGrid.tsx';
import { PortfolioChart } from '../components/dashboard/PortfolioChart.tsx';
import { FilterBar } from '../components/dashboard/FilterBar.tsx';
import { PositionsTable } from '../components/dashboard/PositionsTable.tsx';
import { marketService } from '../services/market.service.ts';
import {
  generateUnifiedPortfolioTimeline,
  calculateBondCurrentPrice,
} from '../utils/portfolioUnifiedEngine.ts';
import type {
  PortfolioSummary,
  Position,
  SubPosition,
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

function formatMoney(amount: number, currency: string, showSign = false): string {
  const rounded = Math.round(amount);
  const sign = showSign && rounded > 0 ? '+' : rounded < 0 ? '-' : '';
  const absFormatted = Math.abs(rounded).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return `${sign}${absFormatted} ${currency}`;
}

export const DashboardPage = () => {
  const dispatch = useAppDispatch();
  const { user } = useAppSelector((state) => state.auth);

  const {
    activePortfolioId,
    portfolios,
    positions,
    transactions,
    instruments,
    quotes,
    rates,
    historicalPrices,
    historicalSortedDates,
    loading,
  } = useAppSelector((state) => state.portfolio);

  const activePortfolio = useMemo(
    () => portfolios.find((p) => p.id === activePortfolioId),
    [portfolios, activePortfolioId]
  );
  const baseCurrency = activePortfolio?.baseCurrency || user?.baseCurrency || 'PLN';

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isCreatePortfolioOpen, setIsCreatePortfolioOpen] = useState(false);
  const [editingTx, setEditingTx] = useState<TransactionResponse | null>(null);
  const [cpi, setCpi] = useState<number>(3.6);
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

  useEffect(() => {
    dispatch(fetchPortfolios(baseCurrency));
  }, [dispatch, baseCurrency]);

  useEffect(() => {
    marketService
      .getInflation()
      .then((inf) => {
        if (inf && inf.currentCpi != null) {
          setCpi(inf.currentCpi);
        }
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (activePortfolioId) {
      dispatch(fetchPositions(activePortfolioId));
      dispatch(fetchTransactions(activePortfolioId));
    }
  }, [dispatch, activePortfolioId]);

  const handleRefresh = () => {
    if (activePortfolioId) {
      dispatch(fetchPositions(activePortfolioId));
      dispatch(fetchTransactions(activePortfolioId));
    }
    marketService
      .getInflation()
      .then((inf) => {
        if (inf && inf.currentCpi != null) {
          setCpi(inf.currentCpi);
        }
      })
      .catch(() => {});
  };

  const enrichedHoldings = useMemo(() => {
    return positions.map((pos) => {
      const inst = instruments[pos.instrumentId];
      const isBond = inst?.type === 'BOND' || pos.bondRate != null;
      const symbol = pos.seriesName || inst?.symbol || 'UNKNOWN';
      const fxRate = (pos.currency === baseCurrency ? 1 : rates[pos.currency]) || 1;

      const currentPrice = isBond
        ? calculateBondCurrentPrice({
            purchaseDate: pos.purchaseDate,
            bondRate: pos.bondRate,
            bondMargin: pos.bondMargin,
            cpi,
            targetDate: nowTimestamp,
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
        });
      } else if (quotes[symbol]?.previousClose && quotes[symbol].previousClose! > 0) {
        previousPrice = quotes[symbol].previousClose!;
      } else if (historicalPrices && historicalSortedDates && inst?.symbol && historicalPrices[inst.symbol]) {
        const dates = historicalSortedDates[inst.symbol];
        const dateMap = historicalPrices[inst.symbol];
        if (dates && dates.length > 0) {
          const todayDateStr = new Date(nowTimestamp).toISOString().slice(0, 10);
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
      .catch((err) => {
        console.error('Failed to fetch empirical stats:', err);
      });

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

  const dashboardPositions = useMemo((): Position[] => {
    const list: Position[] = [];

    const nonBonds = enrichedHoldings.filter((h) => !h.isBond);
    for (const h of nonBonds) {
      const holdingTransactions = transactions
        .filter((t) => t.instrumentId === h.instrumentId && t.transactionType === 'BUY')
        .sort((a, b) => new Date(a.executedAt).getTime() - new Date(b.executedAt).getTime());

      const subPositions: SubPosition[] = holdingTransactions.map((tx) => {
        const fx = (tx.currency === baseCurrency ? 1 : rates[tx.currency]) || 1;
        const txCost = tx.baseAmount ?? (tx.quantity * tx.price + (tx.commission || 0)) * fx;
        const txVal = tx.quantity * h.currentPrice * fx;
        const txProfitGross = txVal - txCost;
        const txTax = shouldApplyTax && txProfitGross > 0 ? txProfitGross * 0.19 : 0;
        const txProfit = txProfitGross - txTax;
        const txProfitPct = txCost > 0 ? (txProfit / txCost) * 100 : 0;

        return {
          id: tx.id,
          name: new Date(tx.executedAt).toLocaleDateString('pl-PL'),
          subName: `${tx.quantity} szt. po ${tx.price.toFixed(2)} ${tx.currency}`,
          sharePercent: 0,
          priceFormatted: `${txCost.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
          valueFormatted: `${txVal.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
          profitFormatted: `${txProfit >= 0 ? '+' : ''}${txProfit.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
          profitPercentFormatted: `${txProfitPct >= 0 ? '+' : ''}${txProfitPct.toFixed(1)}%`,
          transactionId: tx.id,
        };
      });

      const posProfitGross = h.profit;
      const posTax = shouldApplyTax && posProfitGross > 0 ? posProfitGross * 0.19 : 0;
      const posProfit = posProfitGross - posTax;
      const posProfitPct = h.totalCost > 0 ? (posProfit / h.totalCost) * 100 : 0;

      list.push({
        id: h.id,
        ticker: h.ticker,
        name: h.name,
        type: h.assetType,
        quantityFormatted: `${h.quantity.toLocaleString('pl-PL')} szt.`,
        sharePercent: totalPortfolioValue > 0 ? (h.totalValue / totalPortfolioValue) * 100 : 0,
        priceFormatted: `${h.currentPrice.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${h.currency}`,
        valueFormatted: `${h.totalValue.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
        profitFormatted: `${posProfit >= 0 ? '+' : ''}${posProfit.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
        profitPercentFormatted: `${posProfitPct >= 0 ? '+' : ''}${posProfitPct.toFixed(1)}%`,
        isProfit: posProfit >= 0,
        subPositionsType: 'purchases',
        subPositions,
      });
    }

    const bondHoldings = enrichedHoldings.filter((h) => h.isBond);
    const bondFamilies: Record<string, typeof bondHoldings> = {};

    for (const bh of bondHoldings) {
      let family = 'EDO';
      if (bh.ticker.startsWith('TOS')) family = 'TOS';
      else if (bh.ticker.startsWith('COI')) family = 'COI';
      else if (bh.ticker.startsWith('ROR')) family = 'ROR';
      else if (bh.ticker.startsWith('DOR')) family = 'DOR';

      if (!bondFamilies[family]) {
        bondFamilies[family] = [];
      }
      bondFamilies[family].push(bh);
    }

    Object.entries(bondFamilies).forEach(([family, items]) => {
      const sumQty = items.reduce((s, b) => s + b.quantity, 0);
      const sumVal = items.reduce((s, b) => s + b.totalValue, 0);
      const sumCost = items.reduce((s, b) => s + b.totalCost, 0);
      const sumProfitGross = sumVal - sumCost;
      const bondTax = shouldApplyTax && sumProfitGross > 0 ? sumProfitGross * 0.19 : 0;
      const sumProfit = sumProfitGross - bondTax;
      const sumProfitPct = sumCost > 0 ? (sumProfit / sumCost) * 100 : 0;
      const avgPrice = sumQty > 0 ? sumVal / sumQty : 100;

      const familyName =
        family === 'EDO'
          ? 'EDO (10-letnie)'
          : family === 'TOS'
          ? 'TOS (3-letnie)'
          : family === 'COI'
          ? 'COI (4-letnie)'
          : `${family} Obligacje`;

      const familyRates = items
        .map((b) => b.bondRate)
        .filter((r): r is number => r != null);
      const minRate = familyRates.length ? Math.min(...familyRates) : null;
      const maxRate = familyRates.length ? Math.max(...familyRates) : null;
      const fixedRateLabel =
        minRate == null
          ? 'Stałe oprocentowanie'
          : minRate === maxRate
          ? `Stałe ${minRate.toFixed(2)}%`
          : `Stałe ${minRate.toFixed(2)}–${maxRate!.toFixed(2)}%`;

      const typeDesc =
        family === 'EDO'
          ? 'Antyinflacyjne'
          : family === 'TOS'
          ? fixedRateLabel
          : 'Indeksowane';

      const yearlyGroups: Record<number, typeof items> = {};
      items.forEach((item) => {
        const year = item.purchaseDate
          ? new Date(item.purchaseDate).getFullYear()
          : new Date().getFullYear();
        if (!yearlyGroups[year]) {
          yearlyGroups[year] = [];
        }
        yearlyGroups[year].push(item);
      });

      const subPositions: SubPosition[] = Object.entries(yearlyGroups)
        .sort(([yA], [yB]) => Number(yB) - Number(yA))
        .map(([year, yearItems]) => {
          const yQty = yearItems.reduce((s, i) => s + i.quantity, 0);
          const yVal = yearItems.reduce((s, i) => s + i.totalValue, 0);
          const yCost = yearItems.reduce((s, i) => s + i.totalCost, 0);
          const yProfitGross = yVal - yCost;
          const yTax = shouldApplyTax && yProfitGross > 0 ? yProfitGross * 0.19 : 0;
          const yProfit = yProfitGross - yTax;
          const yProfitPct = yCost > 0 ? (yProfit / yCost) * 100 : 0;
          const yPrice = yQty > 0 ? yVal / yQty : 100;
          const yShare = totalPortfolioValue > 0 ? (yVal / totalPortfolioValue) * 100 : 0;
          const purchaseYear = Number(year);
          const yearInstrumentIds = [...new Set(yearItems.map((i) => i.instrumentId))];
          const yearTransactions = transactions.filter(
            (t) =>
              t.transactionType === 'BUY' &&
              yearInstrumentIds.includes(t.instrumentId) &&
              t.executedAt &&
              new Date(t.executedAt).getFullYear() === purchaseYear
          );
          const yearTx =
            yearInstrumentIds.length === 1 && yearTransactions.length === 1
              ? yearTransactions[0]
              : undefined;

          return {
            id: `${family}-${year}`,
            name: `Rocznik ${year}`,
            subName: `${yQty.toLocaleString('pl-PL')} szt. (Wkład ${yCost.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency})`,
            sharePercent: yShare,
            shareNote: 'portfela',
            priceFormatted: `${yPrice.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${baseCurrency}`,
            valueFormatted: formatMoney(yVal, baseCurrency),
            profitFormatted: formatMoney(yProfit, baseCurrency, true),
            profitPercentFormatted: `+${yProfitPct.toFixed(1)}%`,
            transactionId: yearTx?.id,
          };
        });

      list.push({
        id: `bond-${family}`,
        ticker: familyName,
        name: typeDesc,
        type: 'BOND',
        quantityFormatted: `${sumQty.toLocaleString('pl-PL')} szt.`,
        sharePercent: totalPortfolioValue > 0 ? (sumVal / totalPortfolioValue) * 100 : 0,
        priceFormatted: `${avgPrice.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${baseCurrency}`,
        valueFormatted: formatMoney(sumVal, baseCurrency),
        profitFormatted: formatMoney(sumProfit, baseCurrency, true),
        profitPercentFormatted: `+${sumProfitPct.toFixed(1)}% (Odsetki)`,
        isProfit: true,
        subPositionsType: 'bonds_yearly',
        subPositions,
      });
    });

    return list;
  }, [enrichedHoldings, transactions, totalPortfolioValue, baseCurrency, rates, shouldApplyTax]);

  const dashboardSummary = useMemo((): PortfolioSummary => {
    const grossProfit = totalPortfolioValue - totalPortfolioCost;

    let totalBelkaTax = 0;
    if (shouldApplyTax) {
      enrichedHoldings.forEach((h) => {
        if (h.profit > 0) {
          totalBelkaTax += h.profit * 0.19;
        }
      });
    }

    const profit = grossProfit - totalBelkaTax;
    const profitPct = totalPortfolioCost > 0 ? (profit / totalPortfolioCost) * 100 : 0;

    const earliestDate = enrichedHoldings.reduce((min, h) => {
      if (!h.purchaseDate) return min;
      const t = new Date(h.purchaseDate).getTime();
      return t < min ? t : min;
    }, nowTimestamp);

    const yearsElapsed = Math.max(0.08, (nowTimestamp - earliestDate) / (1000 * 60 * 60 * 24 * 365));
    const inflationMultiplier = Math.pow(1 + (cpi ?? 3.6) / 100, yearsElapsed);
    const inflationThreshold = totalPortfolioCost * inflationMultiplier;
    const inflationImpact = inflationThreshold - totalPortfolioCost;
    const realProfit = profit - inflationImpact;
    const realProfitPct = profitPct - (cpi ?? 3.6) * yearsElapsed;

    const taxNoteFormatted = shouldApplyTax
      ? `Belka: -${formatMoney(totalBelkaTax, baseCurrency)}`
      : isIkeAccount
      ? 'IKE (zwolnione z Belki)'
      : undefined;

    const totalDailyChange = enrichedHoldings.reduce((sum, h) => sum + (h.dailyChange || 0), 0);
    const prevPortfolioVal = totalPortfolioValue - totalDailyChange;
    const dailyChangePct = prevPortfolioVal > 0 ? (totalDailyChange / prevPortfolioVal) * 100 : 0;

    return {
      portfolioValueFormatted: formatMoney(totalPortfolioValue, baseCurrency),
      dailyChangeAmountFormatted: formatMoney(totalDailyChange, baseCurrency, true),
      dailyChangePercentFormatted: `${totalDailyChange >= 0 ? '+' : ''}${dailyChangePct.toFixed(2)}% dzisiaj`,
      totalCostFormatted: formatMoney(totalPortfolioCost, baseCurrency),
      totalProfitAmountFormatted: formatMoney(profit, baseCurrency, true),
      totalProfitPercentFormatted: `${profitPct >= 0 ? '+' : ''}${profitPct.toFixed(1)}% ${shouldApplyTax ? 'po podatku' : 'zwrotu'}`,
      realProfitAmountFormatted: formatMoney(realProfit, baseCurrency, true),
      realProfitPercentFormatted: `${realProfitPct >= 0 ? '+' : ''}${realProfitPct.toFixed(1)}% ponad inflację`,
      positionCount: positions.length,
      taxMode,
      profitLabel: shouldApplyTax ? 'Zysk netto' : 'Zysk brutto',
      taxNoteFormatted,
    };
  }, [totalPortfolioValue, totalPortfolioCost, enrichedHoldings, baseCurrency, cpi, positions.length, taxMode, shouldApplyTax, isIkeAccount, nowTimestamp]);

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
