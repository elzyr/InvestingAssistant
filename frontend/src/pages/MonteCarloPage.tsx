import { useMemo, useState } from 'react';
import { useActivePortfolioData } from '../hooks/useActivePortfolioData.ts';
import { calculateBondCurrentPrice } from '../utils/domain/bondPricing.ts';
import { getBondFamily, isBondItem } from '../utils/domain/assets.ts';
import { isIkePortfolio } from '../utils/domain/tax.ts';
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
  } = useActivePortfolioData(undefined, { skipHistory: true });

  const [nowTimestamp] = useState(() => Date.now());

  const isIkeAccount = useMemo(() => isIkePortfolio(activePortfolio), [activePortfolio]);

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

  const { currentBondVal, currentEquityVal, currentCostVal, weightedBondRate } = useMemo(() => {
    let bondVal = 0;
    let equityVal = 0;
    let costVal = 0;
    let sumRateVal = 0;
    let ratedBondVal = 0;

    for (const h of enrichedHoldings) {
      costVal += h.totalCost;
      if (h.isBond) {
        bondVal += h.totalValue;
        let r: number | null = h.bondRate ?? null;
        if (h.bondMargin != null && h.bondMargin > 0 && cpi != null) {
          r = cpi + h.bondMargin;
        }
        if (r != null) {
          sumRateVal += r * h.totalValue;
          ratedBondVal += h.totalValue;
        }
      } else {
        equityVal += h.totalValue;
      }
    }

    return {
      currentBondVal: bondVal,
      currentEquityVal: equityVal,
      currentCostVal: costVal,
      weightedBondRate: ratedBondVal > 0 ? sumRateVal / ratedBondVal : 0,
    };
  }, [enrichedHoldings, cpi]);

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
      const isBond = isBondItem(tx, inst);
      const fx = (tx.currency === baseCurrency ? 1 : rates[tx.currency]) || 1;
      const cost = tx.baseAmount ?? tx.quantity * tx.price * fx;

      if (isBond) {
        const fam = getBondFamily(tx.seriesName || inst?.symbol, tx.termMonths, tx.bondMargin);
        if (fam === 'TOS' || fam === 'ROR' || fam === 'DOR' || fam === 'OTS') {
          tosTotal += cost;
        } else {
          edoTotal += cost;
        }
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
