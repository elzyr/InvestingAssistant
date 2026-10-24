import { useMemo, useState } from 'react';
import { useActivePortfolioData } from '../hooks/useActivePortfolioData.ts';
import { calculateBondCurrentPrice, isBondItem } from '../utils/portfolioUnifiedEngine.ts';
import { StressTestingView } from '../components/analytics/StressTestingView.tsx';

export const MonteCarloPage = () => {
  const {
    activePortfolio,
    positions,
    transactions,
    instruments,
    quotes,
    rates,
    baseCurrency,
    cpi,
  } = useActivePortfolioData();

  const [nowTimestamp] = useState(() => Date.now());

  const isIkeAccount = useMemo(() => {
    return activePortfolio?.name?.toLowerCase().includes('ike') ?? false;
  }, [activePortfolio]);

  const enrichedHoldings = useMemo(() => {
    return positions.map((pos) => {
      const inst = instruments[pos.instrumentId];
      const isBond = isBondItem(pos, inst);
      const symbol = pos.seriesName || inst?.symbol || 'UNKNOWN';
      const isForeign = pos.currency !== baseCurrency;
      const fallbackFx =
        pos.totalCostBase != null && pos.quantity * pos.averageBuyPrice > 0
          ? pos.totalCostBase / (pos.quantity * pos.averageBuyPrice)
          : undefined;
      const fxRate = (isForeign ? rates[pos.currency] : 1) || fallbackFx || 1;

      const currentPrice = isBond
        ? calculateBondCurrentPrice({
            purchaseDate: pos.purchaseDate,
            bondRate: pos.bondRate,
            bondMargin: pos.bondMargin,
            cpi,
            seriesName: pos.seriesName,
            termMonths: pos.termMonths,
          })
        : (quotes[inst?.symbol || symbol]?.price ?? pos.averageBuyPrice);

      const totalValueNative = pos.quantity * currentPrice;
      const totalCost = pos.totalCostBase != null ? pos.totalCostBase : pos.quantity * pos.averageBuyPrice * fxRate;
      return {
        symbol,
        isBond,
        bondRate: pos.bondRate,
        bondMargin: pos.bondMargin,
        totalValue: totalValueNative * fxRate,
        totalCost,
      };
    });
  }, [positions, instruments, quotes, rates, baseCurrency, cpi]);

  const currentBondVal = useMemo(
    () => enrichedHoldings.filter((h) => h.isBond).reduce((s, h) => s + h.totalValue, 0),
    [enrichedHoldings]
  );

  const currentEquityVal = useMemo(
    () => enrichedHoldings.filter((h) => !h.isBond).reduce((s, h) => s + h.totalValue, 0),
    [enrichedHoldings]
  );

  const currentCostVal = useMemo(
    () => enrichedHoldings.reduce((s, h) => s + h.totalCost, 0),
    [enrichedHoldings]
  );

  const weightedBondRate = useMemo(() => {
    if (currentBondVal <= 0) return 5.43;
    const sumRateVal = enrichedHoldings
      .filter((h) => h.isBond)
      .reduce((sum, b) => {
        let r = b.bondRate ?? 5.43;
        if (b.bondMargin && b.bondMargin > 0) {
          r = Math.max(0, cpi) + b.bondMargin;
        }
        return sum + r * b.totalValue;
      }, 0);
    return sumRateVal / currentBondVal;
  }, [enrichedHoldings, currentBondVal, cpi]);

  const calculatedDca = useMemo(() => {
    if (transactions.length === 0) return { tos: 0, edo: 0, etf: 0, total: 0 };

    const dates = transactions
      .map((t) => new Date(t.executedAt).getTime())
      .filter((t) => !isNaN(t));
    if (dates.length === 0) return { tos: 0, edo: 0, etf: 0, total: 0 };

    const minDate = Math.min(...dates);
    const monthsDiff = Math.max(1, Math.round((nowTimestamp - minDate) / (30.44 * 24 * 3600 * 1000)));

    let tosTotal = 0;
    let edoTotal = 0;
    let etfTotal = 0;

    for (const tx of transactions) {
      if (tx.transactionType !== 'BUY') continue;
      const inst = instruments[tx.instrumentId];
      const sName = (tx.seriesName || inst?.symbol || '').toUpperCase();
      const fx = (tx.currency === baseCurrency ? 1 : rates[tx.currency]) || 1;
      const cost = tx.baseAmount ?? tx.quantity * tx.price * fx;

      if (sName.startsWith('TOS')) {
        tosTotal += cost;
      } else if (sName.startsWith('EDO') || sName.startsWith('COI')) {
        edoTotal += cost;
      } else {
        etfTotal += cost;
      }
    }

    const tos = Math.round(tosTotal / monthsDiff);
    const edo = Math.round(edoTotal / monthsDiff);
    const etf = Math.round(etfTotal / monthsDiff);

    return {
      tos,
      edo,
      etf,
      total: tos + edo + etf,
    };
  }, [transactions, instruments, rates, baseCurrency, nowTimestamp]);

  return (
    <div className="w-full max-w-[1920px] mx-auto px-6 lg:px-8 py-6">
      <StressTestingView
        currentBondVal={currentBondVal}
        currentEquityVal={currentEquityVal}
        currentCostVal={currentCostVal}
        baseCurrency={baseCurrency}
        isIkeAccount={isIkeAccount}
        weightedBondRate={weightedBondRate}
        defaultTosDca={calculatedDca.tos}
        defaultEdoDca={calculatedDca.edo}
        defaultEtfDca={calculatedDca.etf}
      />
    </div>
  );
};
