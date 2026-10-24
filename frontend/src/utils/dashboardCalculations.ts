import type { TransactionResponse, PositionResponse } from '../types/portfolio.ts';
import type { Position, SubPosition, PortfolioSummary, TaxMode } from '../types/dashboard.ts';
import {
  calculateHoldingXirr,
  calculatePortfolioRealMetrics,
  calculateFxDecomposition,
  type CashFlow,
} from './xirrEngine.ts';
import { formatMoney } from './reportsFormatters.ts';

export interface EnrichedHolding {
  id: string;
  instrumentId: string;
  ticker: string;
  name: string;
  assetType: 'ETF' | 'BOND' | 'STOCK';
  isBond: boolean;
  quantity: number;
  averagePrice: number;
  currentPrice: number;
  previousPrice: number;
  currency: string;
  totalValue: number;
  totalCost: number;
  profit: number;
  profitPct: number;
  dailyChange: number;
  purchaseDate?: string | Date | number;
  bondRate?: number | null;
  bondMargin?: number | null;
}

export function getTransactionCashFlow(
  tx: TransactionResponse,
  baseCurrency: string,
  rates: Record<string, number>
): { amount: number; isOutflow: boolean } {
  const isForeign = tx.currency !== baseCurrency;
  const txFxFallback =
    tx.exchangeRateToBase ||
    (tx.baseAmount && tx.quantity * tx.price > 0
      ? tx.baseAmount / (tx.quantity * tx.price)
      : 1);
  const fx = isForeign ? (rates[tx.currency] || txFxFallback) : 1;
  if (tx.transactionType === 'BUY') {
    const cost = tx.baseAmount ?? (tx.quantity * tx.price + (tx.commission || 0)) * fx;
    return { amount: cost, isOutflow: true };
  } else {
    const proceeds = tx.baseAmount ?? (tx.quantity * tx.price - (tx.commission || 0)) * fx;
    return { amount: proceeds, isOutflow: false };
  }
}

export interface BuildDashboardPositionsParams {
  enrichedHoldings: EnrichedHolding[];
  transactions: TransactionResponse[];
  totalPortfolioValue: number;
  baseCurrency: string;
  rates: Record<string, number>;
  shouldApplyTax: boolean;
  nowTimestamp: number;
  t?: (key: string, options?: any) => string;
  locale?: string;
}

export function buildDashboardPositions({
  enrichedHoldings,
  transactions,
  totalPortfolioValue,
  baseCurrency,
  rates,
  shouldApplyTax,
  nowTimestamp,
  t,
  locale = 'pl-PL',
}: BuildDashboardPositionsParams): Position[] {
  const translate = t || ((k: string, opt?: any) => (typeof opt === 'string' ? opt : opt?.defaultValue || k));
  const units = translate('common.units', 'szt.');
  const atPrice = translate('common.atPrice', 'po');
  const list: Position[] = [];

  const nonBonds = enrichedHoldings.filter((h) => !h.isBond);
  for (const h of nonBonds) {
    const allHoldingTransactions = transactions
      .filter((t) => t.instrumentId === h.instrumentId)
      .sort((a, b) => new Date(a.executedAt).getTime() - new Date(b.executedAt).getTime());

    const buyTransactions = allHoldingTransactions.filter((t) => t.transactionType === 'BUY');
    const sellTransactions = allHoldingTransactions.filter((t) => t.transactionType === 'SELL');

    let totalSoldQty = sellTransactions.reduce((sum, t) => sum + t.quantity, 0);

    const activeLots: { tx: TransactionResponse; remainingQty: number; effectiveCost: number }[] = [];
    for (const buyTx of buyTransactions) {
      if (totalSoldQty >= buyTx.quantity) {
        totalSoldQty -= buyTx.quantity;
        continue;
      }
      const remainingQty = buyTx.quantity - totalSoldQty;
      totalSoldQty = 0;
      const { amount: originalCost } = getTransactionCashFlow(buyTx, baseCurrency, rates);
      const effectiveCost = originalCost * (remainingQty / buyTx.quantity);
      activeLots.push({ tx: buyTx, remainingQty, effectiveCost });
    }

    const isForeign = h.currency !== baseCurrency;
    const foreignTotalCost = h.quantity * h.averagePrice;
    const avgHoldingBuyFx =
      foreignTotalCost > 0 && h.totalCost > 0
        ? h.totalCost / foreignTotalCost
        : undefined;

    const hasCurrentFx = isForeign ? Boolean(rates[h.currency]) : true;
    const currentFx = isForeign
      ? (rates[h.currency] || avgHoldingBuyFx || 1)
      : 1;

    const subPositions: SubPosition[] = activeLots.map(({ tx, remainingQty, effectiveCost: txCost }) => {
      const txBuyFx =
        tx.exchangeRateToBase ||
        (tx.baseAmount && tx.quantity * tx.price > 0
          ? tx.baseAmount / (tx.quantity * tx.price)
          : avgHoldingBuyFx || rates[tx.currency] || 1);
      const isTxForeign = tx.currency !== baseCurrency;
      const hasLotCurrentFx = isTxForeign ? Boolean(rates[tx.currency]) : true;
      const fx = isTxForeign ? (rates[tx.currency] || txBuyFx || 1) : 1;
      const txVal = remainingQty * h.currentPrice * fx;
      const txProfitGross = txVal - txCost;
      const txTax = shouldApplyTax && txProfitGross > 0 ? txProfitGross * 0.19 : 0;
      const txProfit = txProfitGross - txTax;
      const txProfitPct = txCost > 0 ? (txProfit / txCost) * 100 : 0;
      const txNetVal = txVal - txTax;

      const subXirrResult = calculateHoldingXirr(
        [{ amount: -txCost, date: tx.executedAt }],
        txNetVal,
        nowTimestamp
      );

      let subDecomp: { marketReturnPct: number; fxReturnPct: number } | null = null;
      if (isForeign && hasCurrentFx && hasLotCurrentFx) {
        const decomp = calculateFxDecomposition({
          buyPrice: tx.price,
          currentPrice: h.currentPrice,
          buyExchangeRate: txBuyFx,
          currentExchangeRate: currentFx,
        });
        if (decomp) {
          subDecomp = {
            marketReturnPct: decomp.marketReturnPct,
            fxReturnPct: decomp.fxReturnPct,
          };
        }
      }

      return {
        id: tx.id,
        name: new Date(tx.executedAt).toLocaleDateString(locale),
        subName: `${remainingQty} ${units} ${atPrice} ${tx.price.toFixed(2)} ${tx.currency}`,
        sharePercent: 0,
        priceFormatted: `${txCost.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
        valueFormatted: `${txVal.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
        profitFormatted: `${txProfit >= 0 ? '+' : ''}${txProfit.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
        profitPercentFormatted: `${txProfitPct >= 0 ? '+' : ''}${txProfitPct.toFixed(1)}%`,
        transactionId: tx.id,
        xirr: subXirrResult.xirr,
        isXirrFallback: subXirrResult.isSimpleFallback,
        fxDecomposition: subDecomp,
      };
    });

    const posProfitGross = h.profit;
    const posTax = shouldApplyTax && posProfitGross > 0 ? posProfitGross * 0.19 : 0;
    const posProfit = posProfitGross - posTax;
    const posProfitPct = h.totalCost > 0 ? (posProfit / h.totalCost) * 100 : 0;
    const posNetVal = h.totalValue - posTax;

    const parentCashFlows: CashFlow[] = allHoldingTransactions.map((tx) => {
      const { amount, isOutflow } = getTransactionCashFlow(tx, baseCurrency, rates);
      return { amount: isOutflow ? -amount : amount, date: tx.executedAt };
    });
    if (parentCashFlows.length === 0 && h.totalCost > 0) {
      parentCashFlows.push({ amount: -h.totalCost, date: h.purchaseDate || nowTimestamp });
    }

    const posHoldingResult = calculateHoldingXirr(parentCashFlows, posNetVal, nowTimestamp);

    let parentDecomp: { marketReturnPct: number; fxReturnPct: number } | null = null;
    if (isForeign && hasCurrentFx) {
      const avgBuyFx =
        avgHoldingBuyFx ||
        (rates[h.currency] || 1);
      const decomp = calculateFxDecomposition({
        buyPrice: h.averagePrice,
        currentPrice: h.currentPrice,
        buyExchangeRate: avgBuyFx,
        currentExchangeRate: currentFx,
      });
      if (decomp) {
        parentDecomp = {
          marketReturnPct: decomp.marketReturnPct,
          fxReturnPct: decomp.fxReturnPct,
        };
      }
    }

    list.push({
      id: h.id,
      ticker: h.ticker,
      name: h.name,
      type: h.assetType,
      quantityFormatted: `${h.quantity.toLocaleString(locale)} ${units}`,
      sharePercent: totalPortfolioValue > 0 ? (h.totalValue / totalPortfolioValue) * 100 : 0,
      priceFormatted: `${h.currentPrice.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${h.currency}`,
      valueFormatted: `${h.totalValue.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
      profitFormatted: `${posProfit >= 0 ? '+' : ''}${posProfit.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} ${baseCurrency}`,
      profitPercentFormatted: `${posProfitPct >= 0 ? '+' : ''}${posProfitPct.toFixed(1)}%`,
      isProfit: posProfit >= 0,
      subPositionsType: 'purchases',
      subPositions,
      xirr: posHoldingResult.xirr,
      isXirrFallback: posHoldingResult.isSimpleFallback,
      fxDecomposition: parentDecomp,
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
        ? translate('dashboard.tenYear', 'EDO (10-letnie)')
        : family === 'TOS'
        ? translate('dashboard.threeYear', 'TOS (3-letnie)')
        : family === 'COI'
        ? translate('dashboard.fourYear', 'COI (4-letnie)')
        : translate('dashboard.bondsFamily', { family, defaultValue: `${family} Obligacje` });

    const familyRates = items
      .map((b) => b.bondRate)
      .filter((r): r is number => r != null);
    const minRate = familyRates.length ? Math.min(...familyRates) : null;
    const maxRate = familyRates.length ? Math.max(...familyRates) : null;
    const fixedRateLabel =
      minRate == null
        ? translate('dashboard.fixedRate', 'Stałe oprocentowanie')
        : minRate === maxRate
        ? translate('dashboard.fixedRateLabel', { rate: minRate.toFixed(2), defaultValue: `Stałe ${minRate.toFixed(2)}%` })
        : translate('dashboard.fixedRateRange', { min: minRate.toFixed(2), max: maxRate!.toFixed(2), defaultValue: `Stałe ${minRate.toFixed(2)}–${maxRate!.toFixed(2)}%` });

    const typeDesc =
      family === 'EDO'
        ? translate('dashboard.antiInflation', 'Antyinflacyjne')
        : family === 'TOS'
        ? fixedRateLabel
        : translate('dashboard.inflationIndexed', 'Indeksowane');

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

    const yearlyCashFlows: CashFlow[] = [];
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
        const yNetVal = yVal - yTax;
        const yPrice = yQty > 0 ? yVal / yQty : 100;
        const yShare = totalPortfolioValue > 0 ? (yVal / totalPortfolioValue) * 100 : 0;
        const purchaseYear = Number(year);
        const yearInstrumentIds = [...new Set(yearItems.map((i) => i.instrumentId))];
        const yearSeriesNames = new Set(yearItems.map((i) => i.ticker).filter(Boolean));

        const cohortTxs = transactions
          .filter((t) => {
            if (!yearInstrumentIds.includes(t.instrumentId) || !t.executedAt) return false;
            const tYear = new Date(t.executedAt).getFullYear();
            if (t.transactionType === 'BUY') {
              return tYear === purchaseYear;
            }
            if (t.transactionType === 'SELL') {
              if (t.seriesName) {
                return yearSeriesNames.has(t.seriesName);
              }
              return tYear >= purchaseYear;
            }
            return false;
          })
          .sort((a, b) => new Date(a.executedAt).getTime() - new Date(b.executedAt).getTime());

        const buyTxs = cohortTxs.filter((t) => t.transactionType === 'BUY');
        const sellTxs = cohortTxs.filter((t) => t.transactionType === 'SELL');

        let totalSoldQty = sellTxs.reduce((sum, t) => sum + t.quantity, 0);
        const activeLots: { tx: TransactionResponse; remainingQty: number; effectiveCost: number }[] = [];
        for (const buyTx of buyTxs) {
          if (totalSoldQty >= buyTx.quantity) {
            totalSoldQty -= buyTx.quantity;
            continue;
          }
          const remainingQty = buyTx.quantity - totalSoldQty;
          totalSoldQty = 0;
          const { amount: originalCost } = getTransactionCashFlow(buyTx, baseCurrency, rates);
          const effectiveCost = originalCost * (remainingQty / buyTx.quantity);
          activeLots.push({ tx: buyTx, remainingQty, effectiveCost });
        }

        const yearTx =
          yearInstrumentIds.length === 1 && buyTxs.length === 1
            ? buyTxs[0]
            : undefined;

        const yearFlows: CashFlow[] = [];
        if (cohortTxs.length > 0) {
          cohortTxs.forEach((tx) => {
            const { amount, isOutflow } = getTransactionCashFlow(tx, baseCurrency, rates);
            yearFlows.push({
              amount: isOutflow ? -amount : amount,
              date: tx.executedAt,
            });
          });
        } else {
          yearItems.forEach((item) => {
            yearFlows.push({
              amount: -item.totalCost,
              date: item.purchaseDate || `${year}-01-01`,
            });
          });
        }

        yearlyCashFlows.push(...yearFlows);

        const yHoldingResult = calculateHoldingXirr(yearFlows, yNetVal, nowTimestamp);

        return {
          id: `${family}-${year}`,
          name: `${translate('dashboard.vintage', 'Rocznik')} ${year}`,
          subName: `${yQty.toLocaleString(locale)} ${units} (${translate('common.costBasis', 'Wkład')} ${yCost.toLocaleString(locale, { maximumFractionDigits: 0 })} ${baseCurrency})`,
          sharePercent: yShare,
          shareNote: translate('dashboard.ofPortfolio', 'portfela'),
          priceFormatted: `${yPrice.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${baseCurrency}`,
          valueFormatted: formatMoney(yVal, baseCurrency),
          profitFormatted: formatMoney(yProfit, baseCurrency, true),
          profitPercentFormatted: `+${yProfitPct.toFixed(1)}%`,
          transactionId: yearTx?.id,
          xirr: yHoldingResult.xirr,
          isXirrFallback: yHoldingResult.isSimpleFallback,
          fxDecomposition: null,
        };
      });

    const familyHoldingResult = calculateHoldingXirr(
      yearlyCashFlows,
      sumVal - bondTax,
      nowTimestamp
    );

    list.push({
      id: `bond-${family}`,
      ticker: familyName,
      name: typeDesc,
      type: 'BOND',
      quantityFormatted: `${sumQty.toLocaleString(locale)} ${units}`,
      sharePercent: totalPortfolioValue > 0 ? (sumVal / totalPortfolioValue) * 100 : 0,
      priceFormatted: `${avgPrice.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${baseCurrency}`,
      valueFormatted: formatMoney(sumVal, baseCurrency),
      profitFormatted: formatMoney(sumProfit, baseCurrency, true),
      profitPercentFormatted: `+${sumProfitPct.toFixed(1)}% (Odsetki)`,
      isProfit: true,
      subPositionsType: 'bonds_yearly',
      subPositions,
      xirr: familyHoldingResult.xirr,
      isXirrFallback: familyHoldingResult.isSimpleFallback,
      fxDecomposition: null,
    });
  });

  return list;
}

export interface BuildDashboardSummaryParams {
  totalPortfolioValue: number;
  totalPortfolioCost: number;
  enrichedHoldings: EnrichedHolding[];
  positions: PositionResponse[];
  transactions: TransactionResponse[];
  baseCurrency: string;
  rates: Record<string, number>;
  cpi?: number | null;
  taxMode: TaxMode;
  shouldApplyTax: boolean;
  isIkeAccount: boolean;
  nowTimestamp: number;
  t: (key: string, defaultValue?: string) => string;
  locale?: string;
}

export function buildDashboardSummary({
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
}: BuildDashboardSummaryParams): PortfolioSummary {
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
  const netPortfolioVal = totalPortfolioValue - totalBelkaTax;

  const portfolioFlows: CashFlow[] = [];
  const hurdleTransactions: { cost: number; executedAt: string | Date | number }[] = [];

  const txInstrumentIds = new Set(transactions.map((t) => t.instrumentId));

  transactions.forEach((tx) => {
    const { amount, isOutflow } = getTransactionCashFlow(tx, baseCurrency, rates);
    portfolioFlows.push({ amount: isOutflow ? -amount : amount, date: tx.executedAt });
  });

  enrichedHoldings.forEach((h) => {
    if (!txInstrumentIds.has(h.instrumentId) && h.totalCost > 0) {
      const execDate = h.purchaseDate || nowTimestamp;
      portfolioFlows.push({ amount: -h.totalCost, date: execDate });
    }
  });

  const activeHoldings = enrichedHoldings.filter((h) => h.quantity > 0 && h.totalCost > 0);
  for (const h of activeHoldings) {
    const holdingBuyTxs = transactions.filter(
      (t) => t.instrumentId === h.instrumentId && t.transactionType === 'BUY'
    );
    if (holdingBuyTxs.length > 0) {
      const totalBuyCost = holdingBuyTxs.reduce((sum, tx) => {
        const { amount } = getTransactionCashFlow(tx, baseCurrency, rates);
        return sum + amount;
      }, 0);
      const scale = totalBuyCost > 0 ? Math.min(1, h.totalCost / totalBuyCost) : 1;
      holdingBuyTxs.forEach((tx) => {
        const { amount } = getTransactionCashFlow(tx, baseCurrency, rates);
        hurdleTransactions.push({ cost: amount * scale, executedAt: tx.executedAt });
      });
    } else {
      hurdleTransactions.push({
        cost: h.totalCost,
        executedAt: h.purchaseDate || nowTimestamp,
      });
    }
  }

  const portfolioHoldingRes = calculateHoldingXirr(
    portfolioFlows,
    netPortfolioVal,
    nowTimestamp
  );
  const nominalXirr = portfolioHoldingRes.xirr;

  const realMetrics = calculatePortfolioRealMetrics({
    transactions: hurdleTransactions,
    portfolioValue: netPortfolioVal,
    cpi: cpi ?? 3.6,
    nominalXirr,
    currentDate: nowTimestamp,
  });

  const taxNoteFormatted = shouldApplyTax
    ? `Belka: -${formatMoney(totalBelkaTax, baseCurrency)}`
    : isIkeAccount
    ? (t ? t('dashboard.ikeTaxFree', 'IKE (zwolnione z Belki)') : 'IKE (zwolnione z Belki)')
    : undefined;

  const totalDailyChange = enrichedHoldings.reduce((sum, h) => sum + (h.dailyChange || 0), 0);
  const prevPortfolioVal = totalPortfolioValue - totalDailyChange;
  const dailyChangePct = prevPortfolioVal > 0 ? (totalDailyChange / prevPortfolioVal) * 100 : 0;

  const annualizedUnit = t('dashboard.annualizedReturn', 'r/r');
  const aboveCpiUnit = t('dashboard.aboveCpi', 'ponad CPI');
  const taxNoteSuffix = shouldApplyTax ? t('dashboard.afterTax', 'po podatku') : t('dashboard.return', 'zwrotu');

  let totalProfitPercentFormatted: string;
  if (nominalXirr != null && !portfolioHoldingRes.isSimpleFallback) {
    const xirrPct = (nominalXirr * 100).toFixed(1);
    totalProfitPercentFormatted = `${profitPct >= 0 ? '+' : ''}${profitPct.toFixed(1)}% · ${xirrPct}% ${annualizedUnit}`;
  } else {
    totalProfitPercentFormatted = `${profitPct >= 0 ? '+' : ''}${profitPct.toFixed(1)}% ${taxNoteSuffix}`;
  }

  let realProfitPercentFormatted: string;
  if (realMetrics.realXirr != null && !portfolioHoldingRes.isSimpleFallback) {
    const realXirrPct = (realMetrics.realXirr * 100).toFixed(1);
    realProfitPercentFormatted = `${realMetrics.realProfitPercent >= 0 ? '+' : ''}${realMetrics.realProfitPercent.toFixed(1)}% ${aboveCpiUnit} · ${realXirrPct}% ${annualizedUnit}`;
  } else {
    realProfitPercentFormatted = `${realMetrics.realProfitPercent >= 0 ? '+' : ''}${realMetrics.realProfitPercent.toFixed(1)}% ${aboveCpiUnit}`;
  }

  return {
    portfolioValueFormatted: formatMoney(totalPortfolioValue, baseCurrency),
    dailyChangeAmountFormatted: formatMoney(totalDailyChange, baseCurrency, true),
    dailyChangePercentFormatted: `${totalDailyChange >= 0 ? '+' : ''}${dailyChangePct.toFixed(2)}% ${t ? t('dashboard.today', 'dzisiaj') : 'dzisiaj'}`,
    totalCostFormatted: formatMoney(totalPortfolioCost, baseCurrency),
    totalProfitAmountFormatted: formatMoney(profit, baseCurrency, true),
    totalProfitPercentFormatted,
    realProfitAmountFormatted: formatMoney(realMetrics.realProfitAmount, baseCurrency, true),
    realProfitPercentFormatted,
    positionCount: positions.length,
    taxMode,
    profitLabel: shouldApplyTax ? (t ? t('dashboard.netProfit', 'Zysk netto') : 'Zysk netto') : (t ? t('dashboard.grossProfit', 'Zysk brutto') : 'Zysk brutto'),
    taxNoteFormatted,
    nominalXirr,
    realXirr: realMetrics.realXirr,
  };
}
