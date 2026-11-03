import i18n from '../../i18n/config.ts';
import type { TransactionResponse, PositionResponse } from '../../types/portfolio.ts';
import type { Position, SubPosition, PortfolioSummary, TaxMode } from '../../types/dashboard.ts';
import {
  calculateHoldingXirr,
  calculatePortfolioRealMetrics,
  calculateFxDecomposition,
  type CashFlow,
} from './xirr.ts';
import { formatMoney } from '../reportsFormatters.ts';
import { computeBelka } from '../domain/tax.ts';
import { getBondFamily } from '../domain/assets.ts';
import { standardizeDate } from '../domain/dates.ts';
import {
  getTransactionCashFlow,
  sumDividends,
  mapDividendsByInstrument,
  matchTransactionLotsFifo,
  filterHoldingTransactions,
} from '../domain/transactions.ts';
export { getTransactionCashFlow };
export { standardizeDate };

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
  seriesName?: string | null;
  bondRate?: number | null;
  bondMargin?: number | null;
  termMonths?: number | null;
  isPriceUnavailable?: boolean;
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
  const translate = t || i18n.t.bind(i18n);
  const units = translate('common.units');
  const atPrice = translate('common.atPrice');
  const list: Position[] = [];

  const nonBonds = enrichedHoldings.filter((h) => !h.isBond);
  for (const h of nonBonds) {
    const allHoldingTransactions = filterHoldingTransactions(transactions, h);
    const activeLots = matchTransactionLotsFifo(allHoldingTransactions, baseCurrency, rates);

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
      const txTax = computeBelka(txProfitGross, !shouldApplyTax);
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
        priceFormatted: `${txCost.toLocaleString(locale, { maximumFractionDigits: 0 })} ${baseCurrency}`,
        valueFormatted: `${txVal.toLocaleString(locale, { maximumFractionDigits: 0 })} ${baseCurrency}`,
        profitFormatted: `${txProfit >= 0 ? '+' : ''}${txProfit.toLocaleString(locale, { maximumFractionDigits: 0 })} ${baseCurrency}`,
        profitPercentFormatted: `${txProfitPct >= 0 ? '+' : ''}${txProfitPct.toFixed(1)}%`,
        transactionId: tx.id,
        xirr: subXirrResult.xirr,
        isXirrFallback: subXirrResult.isSimpleFallback,
        fxDecomposition: subDecomp,
      };
    });

    const totalDividends = sumDividends(allHoldingTransactions, baseCurrency, rates);

    const posProfitGross = h.profit + totalDividends;
    const posTax = computeBelka(posProfitGross, !shouldApplyTax);
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
      priceFormatted: `${h.currentPrice.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${h.currency}`,
      valueFormatted: `${h.totalValue.toLocaleString(locale, { maximumFractionDigits: 0 })} ${baseCurrency}`,
      profitFormatted: `${posProfit >= 0 ? '+' : ''}${posProfit.toLocaleString(locale, { maximumFractionDigits: 0 })} ${baseCurrency}`,
      profitPercentFormatted: `${posProfitPct >= 0 ? '+' : ''}${posProfitPct.toFixed(1)}%`,
      isProfit: posProfit >= 0,
      subPositionsType: 'purchases',
      subPositions,
      xirr: posHoldingResult.xirr,
      isXirrFallback: posHoldingResult.isSimpleFallback,
      fxDecomposition: parentDecomp,
      isPriceUnavailable: h.isPriceUnavailable,
    });
  }

  const bondHoldings = enrichedHoldings.filter((h) => h.isBond);
  const bondFamilies: Record<string, typeof bondHoldings> = {};

  for (const bh of bondHoldings) {
    const family = getBondFamily(bh.ticker, bh.termMonths, bh.bondMargin);

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
    const bondTax = computeBelka(sumProfitGross, !shouldApplyTax);
    const sumProfit = sumProfitGross - bondTax;
    const sumProfitPct = sumCost > 0 ? (sumProfit / sumCost) * 100 : 0;
    const avgPrice = sumQty > 0 ? sumVal / sumQty : 100;

    const familyName =
      family === 'EDO'
        ? translate('dashboard.tenYear')
        : family === 'TOS'
        ? translate('dashboard.threeYear')
        : family === 'COI'
        ? translate('dashboard.fourYear')
        : translate('dashboard.bondsFamily', { family });

    const familyRates = items
      .map((b) => b.bondRate)
      .filter((r): r is number => r != null);
    const minRate = familyRates.length ? Math.min(...familyRates) : null;
    const maxRate = familyRates.length ? Math.max(...familyRates) : null;
    const fixedRateLabel =
      minRate == null
        ? translate('dashboard.fixedRate')
        : minRate === maxRate
        ? translate('dashboard.fixedRateLabel', { rate: minRate.toFixed(2) })
        : translate('dashboard.fixedRateRange', { min: minRate.toFixed(2), max: maxRate!.toFixed(2) });

    const typeDesc =
      family === 'EDO'
        ? translate('dashboard.antiInflation')
        : family === 'TOS'
        ? fixedRateLabel
        : translate('dashboard.inflationIndexed');

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
        const yTax = computeBelka(yProfitGross, !shouldApplyTax);
        const yProfit = yProfitGross - yTax;
        const yProfitPct = yCost > 0 ? (yProfit / yCost) * 100 : 0;
        const yNetVal = yVal - yTax;
        const yPrice = yQty > 0 ? yVal / yQty : 100;
        const yShare = totalPortfolioValue > 0 ? (yVal / totalPortfolioValue) * 100 : 0;
        const purchaseYear = Number(year);
        const yearInstrumentIds = [...new Set(yearItems.map((i) => i.instrumentId))];
        const yearSeriesKeys = new Set(
          yearItems.map((i) => i.seriesName ?? (i.ticker.startsWith('PL_BOND') ? '' : i.ticker))
        );

        const cohortTxs = transactions
          .filter((t) => {
            if (!yearInstrumentIds.includes(t.instrumentId) || !t.executedAt) return false;
            const txSeriesKey = t.seriesName ?? '';
            if (!yearSeriesKeys.has(txSeriesKey)) return false;
            const tYear = new Date(t.executedAt).getFullYear();
            if (t.transactionType === 'BUY') {
              return tYear === purchaseYear;
            }
            if (t.transactionType === 'SELL') {
              return tYear >= purchaseYear;
            }
            return false;
          })
          .sort((a, b) => new Date(a.executedAt).getTime() - new Date(b.executedAt).getTime());

        const buyTxs = cohortTxs.filter((t) => t.transactionType === 'BUY');

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
              date: item.purchaseDate ? standardizeDate(item.purchaseDate) : `${year}-01-01`,
            });
          });
        }

        yearlyCashFlows.push(...yearFlows);

        const yHoldingResult = calculateHoldingXirr(yearFlows, yNetVal, nowTimestamp);

        return {
          id: `${family}-${year}`,
          name: `${translate('dashboard.vintage')} ${year}`,
          subName: `${yQty.toLocaleString(locale)} ${units} (${translate('common.costBasis')} ${yCost.toLocaleString(locale, { maximumFractionDigits: 0 })} ${baseCurrency})`,
          sharePercent: yShare,
          shareNote: translate('dashboard.ofPortfolio'),
          priceFormatted: `${yPrice.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${baseCurrency}`,
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
      priceFormatted: `${avgPrice.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${baseCurrency}`,
      valueFormatted: formatMoney(sumVal, baseCurrency),
      profitFormatted: formatMoney(sumProfit, baseCurrency, true),
      profitPercentFormatted: `+${sumProfitPct.toFixed(1)}% (${translate('dashboard.interestAccrued')})`,
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
  const totalDividends = sumDividends(transactions, baseCurrency, rates);

  const grossProfit = totalPortfolioValue - totalPortfolioCost + totalDividends;

  let totalBelkaTax = 0;
  if (shouldApplyTax) {
    const divsByInst = mapDividendsByInstrument(transactions, baseCurrency, rates);
    enrichedHoldings.forEach((h) => {
      const hDivs = divsByInst.get(h.instrumentId) || 0;
      const hProfit = h.profit + hDivs;
      if (hProfit > 0) {
        totalBelkaTax += computeBelka(hProfit);
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
    const allHoldingTransactions = filterHoldingTransactions(transactions, h);
    const activeLots = matchTransactionLotsFifo(allHoldingTransactions, baseCurrency, rates);
    if (activeLots.length > 0) {
      for (const lot of activeLots) {
        hurdleTransactions.push({ cost: lot.effectiveCost, executedAt: lot.tx.executedAt });
      }
    } else if (h.totalCost > 0) {
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

  const realMetrics: {
    realProfitAmount?: number | null;
    realProfitPercent?: number | null;
    realXirr?: number | null;
  } =
    cpi != null
      ? calculatePortfolioRealMetrics({
          transactions: hurdleTransactions,
          portfolioValue: netPortfolioVal,
          cpi,
          nominalXirr,
          currentDate: nowTimestamp,
        })
      : { realProfitAmount: null, realProfitPercent: null, realXirr: null };

  const translate = t || i18n.t.bind(i18n);

  const taxNoteFormatted = shouldApplyTax
    ? `Belka: -${formatMoney(totalBelkaTax, baseCurrency)}`
    : isIkeAccount
    ? translate('dashboard.ikeTaxFree')
    : undefined;

  const totalDailyChange = enrichedHoldings.reduce((sum, h) => sum + (h.dailyChange || 0), 0);
  const displayPortfolioValue = shouldApplyTax ? netPortfolioVal : totalPortfolioValue;
  const prevPortfolioVal = displayPortfolioValue - totalDailyChange;
  const dailyChangePct = prevPortfolioVal > 0 ? (totalDailyChange / prevPortfolioVal) * 100 : 0;

  const annualizedUnit = translate('dashboard.annualizedReturn');
  const aboveCpiUnit = translate('dashboard.aboveCpi');
  const taxNoteSuffix = shouldApplyTax ? translate('dashboard.afterTax') : translate('dashboard.return');

  let totalProfitPercentFormatted: string;
  if (nominalXirr != null && !portfolioHoldingRes.isSimpleFallback) {
    const xirrPct = (nominalXirr * 100).toFixed(1);
    totalProfitPercentFormatted = `${profitPct >= 0 ? '+' : ''}${profitPct.toFixed(1)}% · ${xirrPct}% ${annualizedUnit}`;
  } else {
    totalProfitPercentFormatted = `${profitPct >= 0 ? '+' : ''}${profitPct.toFixed(1)}% ${taxNoteSuffix}`;
  }

  let realProfitPercentFormatted: string;
  let realProfitAmountFormatted: string;
  if (realMetrics.realProfitPercent == null || realMetrics.realProfitAmount == null) {
    realProfitPercentFormatted = '—';
    realProfitAmountFormatted = '—';
  } else if (realMetrics.realXirr != null && !portfolioHoldingRes.isSimpleFallback) {
    const realXirrPct = (realMetrics.realXirr * 100).toFixed(1);
    realProfitPercentFormatted = `${realMetrics.realProfitPercent >= 0 ? '+' : ''}${realMetrics.realProfitPercent.toFixed(1)}% ${aboveCpiUnit} · ${realXirrPct}% ${annualizedUnit}`;
    realProfitAmountFormatted = formatMoney(realMetrics.realProfitAmount, baseCurrency, true);
  } else {
    realProfitPercentFormatted = `${realMetrics.realProfitPercent >= 0 ? '+' : ''}${realMetrics.realProfitPercent.toFixed(1)}% ${aboveCpiUnit}`;
    realProfitAmountFormatted = formatMoney(realMetrics.realProfitAmount, baseCurrency, true);
  }

  return {
    portfolioValueFormatted: formatMoney(displayPortfolioValue, baseCurrency),
    dailyChangeAmountFormatted: formatMoney(totalDailyChange, baseCurrency, true),
    dailyChangePercentFormatted: `${totalDailyChange >= 0 ? '+' : ''}${dailyChangePct.toFixed(2)}% ${translate('dashboard.today')}`,
    totalCostFormatted: formatMoney(totalPortfolioCost, baseCurrency),
    totalProfitAmountFormatted: formatMoney(profit, baseCurrency, true),
    totalProfitPercentFormatted,
    realProfitAmountFormatted,
    realProfitPercentFormatted,
    positionCount: positions.length,
    taxMode,
    profitLabel: shouldApplyTax ? translate('dashboard.netProfit') : translate('dashboard.grossProfit'),
    portfolioValueLabel: shouldApplyTax
      ? translate('dashboard.netPortfolioValue')
      : translate('dashboard.grossPortfolioValue'),
    taxNoteFormatted,
    nominalXirr,
    realXirr: realMetrics.realXirr,
  };
}
