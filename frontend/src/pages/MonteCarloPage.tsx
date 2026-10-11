import { useEffect, useMemo, useState } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks.ts';
import { fetchPortfolios, fetchPositions, fetchTransactions } from '../store/thunks/portfolio.thunk.ts';
import { marketService } from '../services/market.service.ts';
import { calculateBondCurrentPrice } from '../utils/portfolioUnifiedEngine.ts';
import { StressTestingView } from '../components/analytics/StressTestingView.tsx';

export const MonteCarloPage = () => {
  const dispatch = useAppDispatch();
  const { user } = useAppSelector((state) => state.auth);
  const baseCurrency = user?.baseCurrency || 'PLN';

  const { activePortfolioId, portfolios, positions, transactions, instruments, quotes, rates } =
    useAppSelector((state) => state.portfolio);

  const [cpi, setCpi] = useState<number>(3.4);
  const [nowTimestamp] = useState(() => Date.now());

  useEffect(() => {
    if (!activePortfolioId) {
      dispatch(fetchPortfolios(baseCurrency));
    }
  }, [dispatch, activePortfolioId, baseCurrency]);

  useEffect(() => {
    if (activePortfolioId) {
      dispatch(fetchPositions(activePortfolioId));
      dispatch(fetchTransactions(activePortfolioId));
    }
  }, [dispatch, activePortfolioId]);

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

  const activePortfolio = useMemo(
    () => portfolios.find((p) => p.id === activePortfolioId),
    [portfolios, activePortfolioId]
  );

  const isIkeAccount = useMemo(() => {
    return activePortfolio?.name?.toLowerCase().includes('ike') ?? false;
  }, [activePortfolio]);

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
          })
        : (quotes[inst?.symbol || symbol]?.price ?? pos.averageBuyPrice);

      const totalValueNative = pos.quantity * currentPrice;
      const totalCostNative = pos.quantity * pos.averageBuyPrice;
      return {
        symbol,
        isBond,
        bondRate: pos.bondRate,
        bondMargin: pos.bondMargin,
        totalValue: totalValueNative * fxRate,
        totalCost: totalCostNative * fxRate,
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
    if (currentBondVal <= 0) return 0.0543;
    const sumRateVal = enrichedHoldings
      .filter((h) => h.isBond)
      .reduce((sum, b) => {
        let r = b.bondRate ?? 5.43;
        if (b.bondMargin && b.bondMargin > 0) {
          r = Math.max(0, cpi) + b.bondMargin;
        }
        return sum + (r / 100) * b.totalValue;
      }, 0);
    return sumRateVal / currentBondVal;
  }, [enrichedHoldings, currentBondVal, cpi]);

  // Compute real historical DCA averages from actual user transactions
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
      const cost = (tx.baseAmount ?? tx.quantity * tx.price) * fx;

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
