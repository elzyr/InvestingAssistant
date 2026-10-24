import type { TransactionResponse, PositionResponse } from '../types/portfolio.ts';
import type { InstrumentResponse } from '../types/instrument.ts';
import type { 
  ChartPoint, 
  TaxMode, 
  TimeframeOption, 
  ProjectionHorizonOption,
  InflationAdjustMode 
} from '../types/dashboard.ts';

export type EtfCategory = 'SP500' | 'ALL_WORLD' | 'DEVELOPED' | 'EMERGING' | 'OTHER';

export interface EtfProfile {
  symbol: string;
  name: string;
  ter: number;
  whtDrag: number;
  volatility: number;
  category: EtfCategory;
}

export function isBondItem(
  item: {
    bondRate?: number | null;
    seriesName?: string | null;
    currency?: string;
    price?: number;
    averageBuyPrice?: number;
  },
  inst?: InstrumentResponse
): boolean {
  return Boolean(
    item.bondRate != null ||
      (item.seriesName && item.seriesName.length >= 3) ||
      (inst &&
        (inst.type === 'BOND' ||
          inst.symbol.startsWith('PL_BOND') ||
          inst.symbol.includes('EDO') ||
          inst.symbol.includes('TOS') ||
          inst.symbol.includes('COI'))) ||
      (item.currency === 'PLN' && (item.price === 100 || item.averageBuyPrice === 100))
  );
}

export function detectEtfProfile(symbol: string, name: string): EtfProfile {
  const sym = (symbol || '').toUpperCase();
  const nm = (name || '').toUpperCase();

  if (
    sym.includes('SXR8') ||
    sym.includes('CSSPX') ||
    sym.includes('VUAA') ||
    sym.includes('VUSA') ||
    sym.includes('SPY') ||
    sym.includes('VOO') ||
    nm.includes('S&P 500') ||
    nm.includes('SP 500')
  ) {
    return { symbol, name, ter: 0.07, whtDrag: 0.23, volatility: 15.5, category: 'SP500' };
  }

  if (
    sym.includes('VWCE') ||
    sym.includes('VWRA') ||
    sym.includes('ISAC') ||
    sym.includes('SSAC') ||
    nm.includes('ALL-WORLD') ||
    nm.includes('ALL WORLD') ||
    nm.includes('ACWI')
  ) {
    return { symbol, name, ter: 0.22, whtDrag: 0.20, volatility: 14.8, category: 'ALL_WORLD' };
  }

  if (
    sym.includes('EUNL') ||
    sym.includes('IWDA') ||
    sym.includes('SWDA') ||
    sym.includes('XDWD') ||
    nm.includes('MSCI WORLD') ||
    nm.includes('DEVELOPED')
  ) {
    return { symbol, name, ter: 0.20, whtDrag: 0.22, volatility: 14.6, category: 'DEVELOPED' };
  }

  if (
    sym.includes('AMEM') ||
    sym.includes('EIMI') ||
    sym.includes('EMIM') ||
    sym.includes('IS3N') ||
    sym.includes('VFEM') ||
    nm.includes('EMERGING')
  ) {
    return { symbol, name, ter: 0.18, whtDrag: 0.25, volatility: 18.5, category: 'EMERGING' };
  }

  return { symbol, name, ter: 0.20, whtDrag: 0.25, volatility: 15.0, category: 'OTHER' };
}

export const CORRELATION_MATRIX: Record<EtfCategory, Record<EtfCategory, number>> = {
  SP500: { SP500: 1.00, ALL_WORLD: 0.96, DEVELOPED: 0.97, EMERGING: 0.72, OTHER: 0.85 },
  ALL_WORLD: { SP500: 0.96, ALL_WORLD: 1.00, DEVELOPED: 0.98, EMERGING: 0.82, OTHER: 0.88 },
  DEVELOPED: { SP500: 0.97, ALL_WORLD: 0.98, DEVELOPED: 1.00, EMERGING: 0.74, OTHER: 0.86 },
  EMERGING: { SP500: 0.72, ALL_WORLD: 0.82, DEVELOPED: 0.74, EMERGING: 1.00, OTHER: 0.75 },
  OTHER: { SP500: 0.85, ALL_WORLD: 0.88, DEVELOPED: 0.86, EMERGING: 0.75, OTHER: 1.00 },
};

export interface BondPriceParams {
  purchaseDate?: string | Date | number;
  bondRate?: number | null;
  bondMargin?: number | null;
  cpi?: number | null;
  targetDate?: string | Date | number;
  seriesName?: string | null;
  termMonths?: number | null;
}

export function calculateBondCurrentPrice({
  purchaseDate,
  bondRate,
  bondMargin,
  cpi = 3.6,
  targetDate,
  seriesName,
  termMonths,
}: BondPriceParams): number {
  if (!purchaseDate) return 100;
  const purchaseMs = new Date(purchaseDate).getTime();
  const currentMs = targetDate !== undefined ? new Date(targetDate).getTime() : Date.now();

  let effectiveMs = currentMs;
  if (termMonths != null && termMonths > 0) {
    const d = new Date(purchaseDate);
    const matDate = new Date(d);
    matDate.setMonth(matDate.getMonth() + termMonths);
    if (effectiveMs > matDate.getTime()) {
      effectiveMs = matDate.getTime();
    }
  }

  const days = Math.max(0, Math.floor((effectiveMs - purchaseMs) / (1000 * 60 * 60 * 24)));
  const prefix = seriesName ? seriesName.trim().toUpperCase() : '';
  const isNonCompounding =
    prefix.startsWith('COI') ||
    prefix.startsWith('ROR') ||
    prefix.startsWith('DOR') ||
    prefix.startsWith('OTS');

  const isInflation = bondMargin != null && bondMargin > 0;

  if (isInflation) {
    const r1 = (bondRate ?? 6.75) / 100;
    const m = (bondMargin ?? 2.0) / 100;
    const cpiRate = (cpi ?? 3.6) / 100;
    const laterRate = Math.max(0, cpiRate) + m;
    const k = Math.floor(days / 365);
    const curDays = days % 365;

    if (isNonCompounding) {
      if (k === 0) {
        return 100 * (1 + r1 * (curDays / 365));
      }
      const totalCoupons = 100 * r1 + 100 * (k - 1) * laterRate + 100 * laterRate * (curDays / 365);
      return 100 + totalCoupons;
    }

    if (k === 0) {
      return 100 * (1 + r1 * (curDays / 365));
    }
    const fullYearsCompounded = 100 * (1 + r1) * Math.pow(1 + laterRate, k - 1);
    return fullYearsCompounded * (1 + laterRate * (curDays / 365));
  }

  const r = (bondRate ?? 6.5) / 100;
  if (isNonCompounding) {
    return 100 * (1 + r * (days / 365));
  }

  const k = Math.floor(days / 365);
  const curDays = days % 365;
  return 100 * Math.pow(1 + r, k) * (1 + r * (curDays / 365));
}

export interface UnifiedEngineParams {
  transactions: TransactionResponse[];
  positions: PositionResponse[];
  instruments?: Record<string, InstrumentResponse>;
  totalPortfolioValue: number;
  totalPortfolioCost: number;
  taxMode: TaxMode;
  timeframe: TimeframeOption;
  projectionHorizon: ProjectionHorizonOption;
  cpi: number;
  rates: Record<string, number>;
  baseCurrency: string;
  instrumentPrices?: Record<string, number>;
  monthlyDca?: number;
  dcaBondShare?: number;
  expectedEtfReturn?: number;
  etfVolatility?: number;
  empiricalTer?: number;
  bondRollover?: boolean;
  isIkeAccount?: boolean;
  whtDrag?: number;
  historicalPrices?: Record<string, Record<string, number>>;
  historicalSortedDates?: Record<string, string[]>;
  inflationAdjustMode?: InflationAdjustMode;
  targetGoal?: number | null;
  locale?: string;
}

export function getHistoricalPrice(
  symbolPrices: Record<string, number> | undefined,
  sortedDates: string[] | undefined,
  targetDateStr: string,
  fallbackPrice: number
): number {
  if (!symbolPrices || !sortedDates || sortedDates.length === 0) return fallbackPrice;
  if (symbolPrices[targetDateStr] != null) return symbolPrices[targetDateStr];
  let low = 0;
  let high = sortedDates.length - 1;
  let bestDate: string | null = null;
  while (low <= high) {
    const mid = Math.floor((low + high) / 2);
    if (sortedDates[mid] <= targetDateStr) {
      bestDate = sortedDates[mid];
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  return bestDate && symbolPrices[bestDate] != null ? symbolPrices[bestDate] : fallbackPrice;
}

function normalCdf(x: number): number {
  const t = 1 / (1 + 0.2316419 * Math.abs(x));
  const d = 0.3989422804014337 * Math.exp((-x * x) / 2);
  const prob = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
  return x > 0 ? 1 - prob : prob;
}

export function generateUnifiedPortfolioTimeline({
  transactions,
  positions,
  instruments = {},
  totalPortfolioValue,
  totalPortfolioCost,
  taxMode,
  timeframe,
  projectionHorizon,
  cpi,
  rates,
  baseCurrency,
  instrumentPrices = {},
  monthlyDca = 2000,
  dcaBondShare = 0.5,
  expectedEtfReturn = 8.5,
  etfVolatility,
  empiricalTer,
  bondRollover = true,
  isIkeAccount = false,
  whtDrag = 0.25,
  historicalPrices = {},
  historicalSortedDates = {},
  inflationAdjustMode = 'NOMINAL',
  targetGoal = null,
  locale = 'pl-PL',
}: UnifiedEngineParams): ChartPoint[] {
  const now = Date.now();
  const preparedTxs = transactions
    .map((tx) => {
      const timeMs = new Date(tx.executedAt).getTime();
      const fx = (tx.currency === baseCurrency ? 1 : rates[tx.currency]) || 1;
      const commission = tx.commission || 0;
      const txCost = tx.baseAmount ?? (
        tx.transactionType === 'SELL'
          ? (tx.quantity * tx.price - commission) * fx
          : (tx.quantity * tx.price + commission) * fx
      );
      const inst = instruments[tx.instrumentId];
      const isBond = isBondItem(tx, inst);
      const symbol = inst?.symbol || tx.seriesName || '';
      const fallbackPrice = instrumentPrices[tx.instrumentId] ?? tx.price;
      return {
        timeMs,
        executedAt: tx.executedAt,
        transactionType: tx.transactionType || 'BUY',
        quantity: tx.quantity,
        fx,
        txCost,
        isBond,
        bondRate: tx.bondRate,
        bondMargin: tx.bondMargin,
        seriesName: tx.seriesName,
        termMonths: tx.termMonths,
        symbol,
        fallbackPrice,
        instrumentId: tx.instrumentId,
      };
    })
    .sort((a, b) => a.timeMs - b.timeMs);

  const earliestTx = preparedTxs.length > 0 ? preparedTxs[0].timeMs : now - 365 * 24 * 3600 * 1000;

  let currentBondCapital = 0;
  let currentEquityCapital = 0;
  let currentBondCost = 0;
  let currentEquityCost = 0;
  const equityHoldings: { profile: EtfProfile; value: number }[] = [];
  let userBondValSum = 0;
  let userBondWeightedRateSum = 0;

  for (const pos of positions) {
    const inst = instruments[pos.instrumentId];
    const isBond = isBondItem(pos, inst);
    const fx = (pos.currency === baseCurrency ? 1 : rates[pos.currency]) || 1;
    const currentPrice = isBond
      ? calculateBondCurrentPrice({
          purchaseDate: pos.purchaseDate,
          bondRate: pos.bondRate,
          bondMargin: pos.bondMargin,
          cpi,
          seriesName: pos.seriesName,
          termMonths: pos.termMonths,
        })
      : (instrumentPrices[pos.instrumentId] ?? pos.averageBuyPrice);
    const val = pos.quantity * currentPrice * fx;
    const cost = pos.totalCostBase ?? (pos.quantity * pos.averageBuyPrice * fx);

    if (isBond) {
      currentBondCapital += val;
      currentBondCost += cost;
      let r = pos.bondRate ?? 5.43;
      if (pos.bondMargin && pos.bondMargin > 0) {
        r = Math.max(0, cpi) + pos.bondMargin;
      }
      userBondValSum += val;
      userBondWeightedRateSum += val * r;
    } else {
      currentEquityCapital += val;
      currentEquityCost += cost;
      const profile = detectEtfProfile(inst?.symbol || pos.seriesName || '', inst?.name || '');
      equityHoldings.push({ profile, value: val });
    }
  }

  if (currentBondCapital + currentEquityCapital === 0) {
    currentEquityCapital = totalPortfolioValue;
    currentEquityCost = totalPortfolioCost;
  }

  let startMs: number;
  switch (timeframe) {
    case '1M':
      startMs = now - 30 * 24 * 3600 * 1000;
      break;
    case '3M':
      startMs = now - 90 * 24 * 3600 * 1000;
      break;
    case '6M':
      startMs = now - 180 * 24 * 3600 * 1000;
      break;
    case 'YTD': {
      const yearStart = new Date(new Date().getFullYear(), 0, 1).getTime();
      startMs = Math.min(yearStart, now - 30 * 24 * 3600 * 1000);
      break;
    }
    case '1R':
      startMs = now - 365 * 24 * 3600 * 1000;
      break;
    case 'MAX':
    default:
      startMs = Math.min(earliestTx, now - 90 * 24 * 3600 * 1000);
      break;
  }

  if (startMs >= now) {
    startMs = now - 30 * 24 * 3600 * 1000;
  }

  const horizonYearsMap: Record<string, number> = {
    '3Y': 3,
    '5Y': 5,
    '10Y': 10,
    '20Y': 20,
  };

  const horizonYears = horizonYearsMap[projectionHorizon] || 0;
  const isProjectionActive = horizonYears > 0;

  let stepDays = 1;
  if (isProjectionActive) {
    if (horizonYears <= 3) {
      stepDays = 14;
    } else if (horizonYears <= 5) {
      stepDays = 21;
    } else {
      stepDays = 30.4375;
    }
  }

  const oneDayMs = 24 * 3600 * 1000;
  const stepMs = stepDays * oneDayMs;
  const totalHistorySteps = isProjectionActive
    ? Math.max(1, Math.round((now - startMs) / stepMs))
    : Math.max(1, Math.round((now - startMs) / oneDayMs));

  const points: ChartPoint[] = [];

  for (let i = 0; i <= totalHistorySteps; i++) {
    const isToday = i === totalHistorySteps;
    const tMs = isToday ? now : Math.min(now - 1000, startMs + Math.round(i * stepMs));
    const dDay = new Date(tMs);
    const dayDateStr = `${dDay.getFullYear()}-${String(dDay.getMonth() + 1).padStart(2, '0')}-${String(dDay.getDate()).padStart(2, '0')}`;

    const holdings = new Map<string, {
      currentQty: number;
      costBasis: number;
      isBond: boolean;
      symbol: string;
      fallbackPrice: number;
      fx: number;
      lots: {
        quantity: number;
        executedAt: string | Date | number;
        bondRate?: number | null;
        bondMargin?: number | null;
        seriesName?: string | null;
        termMonths?: number | null;
        fx: number;
      }[];
    }>();

    for (let k = 0; k < preparedTxs.length; k++) {
      const tx = preparedTxs[k];
      if (tx.timeMs > tMs) break;

      const key = tx.isBond
        ? `${tx.instrumentId}_${tx.seriesName || ''}`
        : tx.instrumentId;

      let h = holdings.get(key);
      if (!h) {
        h = {
          currentQty: 0,
          costBasis: 0,
          isBond: tx.isBond,
          symbol: tx.symbol,
          fallbackPrice: tx.fallbackPrice,
          fx: tx.fx,
          lots: [],
        };
        holdings.set(key, h);
      }

      if (tx.transactionType === 'BUY') {
        h.currentQty += tx.quantity;
        h.costBasis += tx.txCost;
        if (tx.isBond) {
          h.lots.push({
            quantity: tx.quantity,
            executedAt: tx.executedAt,
            bondRate: tx.bondRate,
            bondMargin: tx.bondMargin,
            seriesName: tx.seriesName,
            termMonths: tx.termMonths,
            fx: tx.fx,
          });
        }
      } else if (tx.transactionType === 'SELL') {
        if (h.currentQty > 0) {
          const sellQty = Math.min(tx.quantity, h.currentQty);
          h.costBasis -= h.costBasis * (sellQty / h.currentQty);
          h.currentQty -= sellQty;
          if (h.currentQty <= 0) {
            h.costBasis = 0;
            h.currentQty = 0;
            h.lots = [];
          } else if (tx.isBond) {
            let toReduce = sellQty;
            while (toReduce > 0 && h.lots.length > 0) {
              if (h.lots[0].quantity <= toReduce) {
                toReduce -= h.lots[0].quantity;
                h.lots.shift();
              } else {
                h.lots[0].quantity -= toReduce;
                toReduce = 0;
              }
            }
          }
        }
      }
    }

    let bondCostAtT = 0;
    let bondValAtT = 0;
    let nonBondCostAtT = 0;
    let nonBondValAtT = 0;

    for (const h of holdings.values()) {
      if (h.currentQty <= 0) continue;
      if (h.isBond) {
        bondCostAtT += h.costBasis;
        for (const lot of h.lots) {
          const unitPrice = calculateBondCurrentPrice({
            purchaseDate: lot.executedAt,
            bondRate: lot.bondRate,
            bondMargin: lot.bondMargin,
            cpi,
            targetDate: tMs,
            seriesName: lot.seriesName,
            termMonths: lot.termMonths,
          });
          bondValAtT += lot.quantity * unitPrice * lot.fx;
        }
      } else {
        nonBondCostAtT += h.costBasis;
        const realClose = getHistoricalPrice(
          historicalPrices[h.symbol],
          historicalSortedDates[h.symbol],
          dayDateStr,
          h.fallbackPrice
        );
        nonBondValAtT += h.currentQty * realClose * h.fx;
      }
    }

    let finalCost = bondCostAtT + nonBondCostAtT;
    let rawValuation = bondValAtT + nonBondValAtT;

    if (isToday) {
      finalCost = totalPortfolioCost > 0 ? totalPortfolioCost : finalCost;
      if (totalPortfolioValue > 0 && (totalPortfolioValue !== totalPortfolioCost || rawValuation === 0)) {
        rawValuation = totalPortfolioValue;
      }
      if (currentBondCapital > 0) {
        bondValAtT = currentBondCapital;
      }
      if (currentEquityCapital > 0) {
        nonBondValAtT = currentEquityCapital;
      }
      if (currentBondCost > 0) {
        bondCostAtT = currentBondCost;
      }
      if (currentEquityCost > 0) {
        nonBondCostAtT = currentEquityCost;
      }
    }

    const bondProfit = Math.max(0, bondValAtT - bondCostAtT);
    const nonBondProfit = Math.max(0, nonBondValAtT - nonBondCostAtT);
    const totalProfitGross = rawValuation - finalCost;

    let profit = totalProfitGross;
    let valuation = rawValuation;

    if (taxMode === 'NETTO') {
      const bondBelka = isIkeAccount ? 0 : bondProfit * 0.19;
      const nonBondBelka = isIkeAccount ? 0 : nonBondProfit * 0.19;
      const totalBelka = bondBelka + nonBondBelka;

      profit = totalProfitGross - totalBelka;
      valuation = rawValuation - totalBelka;
    }

    const returnPct = finalCost > 0 ? (profit / finalCost) * 100 : 0;
    const dateObj = new Date(tMs);
    const dateLabel = dateObj.toLocaleDateString(locale, {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    });

    points.push({
      timestamp: tMs,
      dateLabel,
      valuation: Math.round(valuation),
      cost: Math.round(finalCost),
      profit: Math.round(profit),
      returnPct: Number(returnPct.toFixed(2)),
      isFuture: false,
      netMedian: Math.round(valuation),
      netP90: Math.round(valuation),
      netP10: Math.round(valuation),
    });
  }

  if (!isProjectionActive) {
    return points;
  }

  const targetFuturePoints = Math.max(1, Math.round((horizonYears * 365.25) / stepDays));
  const dtYears = horizonYears / targetFuturePoints;

  const defaultBondRate = 5.43;
  const weightedBondRateAnnual = userBondValSum > 0 ? userBondWeightedRateSum / userBondValSum : defaultBondRate;

  let weightedTer = empiricalTer !== undefined ? empiricalTer : 0.20;
  let weightedWhtDrag = whtDrag ?? 0.25;
  let portfolioEquityVol = (etfVolatility ?? 15.0) / 100;

  if (equityHoldings.length > 0 && currentEquityCapital > 0) {
    let sumTer = 0;
    let sumWht = 0;
    const weights = equityHoldings.map((h) => ({
      profile: h.profile,
      w: h.value / currentEquityCapital,
    }));

    for (const item of weights) {
      sumTer += item.w * item.profile.ter;
      sumWht += item.w * item.profile.whtDrag;
    }

    if (empiricalTer !== undefined) {
      weightedTer = empiricalTer;
    } else {
      weightedTer = sumTer;
    }
    weightedWhtDrag = whtDrag !== undefined ? whtDrag : sumWht;

    if (etfVolatility !== undefined) {
      portfolioEquityVol = etfVolatility / 100;
    } else {
      let portVariance = 0;
      for (let i = 0; i < weights.length; i++) {
        for (let j = 0; j < weights.length; j++) {
          const wI = weights[i].w;
          const wJ = weights[j].w;
          const sigI = weights[i].profile.volatility / 100;
          const sigJ = weights[j].profile.volatility / 100;
          const rho =
            CORRELATION_MATRIX[weights[i].profile.category]?.[weights[j].profile.category] ?? 0.85;
          portVariance += wI * wJ * sigI * sigJ * rho;
        }
      }
      portfolioEquityVol = Math.sqrt(Math.max(0.0001, portVariance));
    }
  }

  const totalCostDrag = weightedTer + weightedWhtDrag;
  const effectiveEtfCagr = Math.max(0, expectedEtfReturn - totalCostDrag) / 100;
  const sigmaAnnual = portfolioEquityVol;

  const bondGrowthPerStep = Math.pow(1 + weightedBondRateAnnual / 100, dtYears);
  const stockGrowthPerStep = Math.pow(1 + effectiveEtfCagr, dtYears);

  const stepDca = (monthlyDca * 12) * dtYears;
  const stepBondDca = stepDca * dcaBondShare;
  const stepStockDca = stepDca * (1 - dcaBondShare);

  let futureBondVal = currentBondCapital;
  let futureStockValMedian = currentEquityCapital;
  let futureCost = totalPortfolioCost;
  let futureBondCost = currentBondCost;
  let futureEquityCost = currentEquityCost;

  for (let s = 1; s <= targetFuturePoints; s++) {
    futureBondVal = bondRollover
      ? (futureBondVal + stepBondDca) * bondGrowthPerStep
      : (futureBondVal + stepBondDca);
    futureStockValMedian = (futureStockValMedian + stepStockDca) * stockGrowthPerStep;
    futureCost += stepDca;
    futureBondCost += stepBondDca;
    futureEquityCost += stepStockDca;

    const tYears = s * dtYears;

    const initialEquityGrown = currentEquityCapital * Math.pow(stockGrowthPerStep, s);
    const dcaEquityGrown = Math.max(0, futureStockValMedian - initialEquityGrown);

    const z90 = 1.28155;
    const volFactorInit90 = Math.exp(z90 * sigmaAnnual * Math.sqrt(tYears));
    const volFactorDca90 = Math.exp(z90 * sigmaAnnual * Math.sqrt(tYears / 3));
    const stockP90 = initialEquityGrown * volFactorInit90 + dcaEquityGrown * volFactorDca90;

    const z10 = -1.28155;
    const volFactorInit10 = Math.exp(z10 * sigmaAnnual * Math.sqrt(tYears));
    const volFactorDca10 = Math.exp(z10 * sigmaAnnual * Math.sqrt(tYears / 3));
    const stockP10 = initialEquityGrown * volFactorInit10 + dcaEquityGrown * volFactorDca10;

    const bondProfitGross = Math.max(0, futureBondVal - futureBondCost);
    const bondBelka = taxMode === 'NETTO' && !isIkeAccount ? bondProfitGross * 0.19 : 0;
    const futureBondNet = futureBondVal - bondBelka;

    const stockMedianProfit = Math.max(0, futureStockValMedian - futureEquityCost);
    const stockP90Profit = Math.max(0, stockP90 - futureEquityCost);
    const stockP10Profit = Math.max(0, stockP10 - futureEquityCost);

    const belkaMedian = taxMode === 'NETTO' && !isIkeAccount ? stockMedianProfit * 0.19 : 0;
    const belkaP90 = taxMode === 'NETTO' && !isIkeAccount ? stockP90Profit * 0.19 : 0;
    const belkaP10 = taxMode === 'NETTO' && !isIkeAccount ? stockP10Profit * 0.19 : 0;

    const netMedian = futureBondNet + (futureStockValMedian - belkaMedian);
    const netP90 = futureBondNet + (stockP90 - belkaP90);
    const netP10 = futureBondNet + (stockP10 - belkaP10);
    const floorNet = Math.max(currentBondCapital, futureBondNet);

    const infDeflator = Math.pow(1 + (cpi || 3.6) / 100, tYears);
    const isReal = inflationAdjustMode === 'REAL';
    const deflator = isReal ? infDeflator : 1;

    const adjNetMedian = netMedian / deflator;
    const adjNetP90 = netP90 / deflator;
    const adjNetP10 = netP10 / deflator;
    const adjFloorNet = floorNet / deflator;
    const adjFutureCost = futureCost / deflator;

    let goalProbability: number | undefined = undefined;
    if (targetGoal && targetGoal > 0) {
      if (adjFloorNet >= targetGoal) {
        goalProbability = 100;
      } else {
        const targetNominal = targetGoal * deflator;
        const neededStockNet = targetNominal - futureBondNet;
        const neededStockGross = taxMode === 'NETTO' && !isIkeAccount
          ? futureEquityCost + Math.max(0, neededStockNet - futureEquityCost) / 0.81
          : neededStockNet;

        const effSigma = sigmaAnnual * Math.sqrt(Math.max(0.01, tYears * 0.7));
        if (futureStockValMedian > 0 && neededStockGross > 0 && effSigma > 0) {
          const logMean = Math.log(futureStockValMedian);
          const logTarget = Math.log(neededStockGross);
          const z = (logTarget - logMean) / effSigma;
          const p = (1 - normalCdf(z)) * 100;
          goalProbability = Math.min(99.9, Math.max(0.1, Number(p.toFixed(1))));
        } else {
          goalProbability = futureStockValMedian >= neededStockGross ? 99 : 1;
        }
      }
    }

    const futureTimestamp = now + Math.round(s * stepDays * 24 * 3600 * 1000);
    const futureDate = new Date(futureTimestamp);
    const dayNum = futureDate.getDate();
    const monthName = futureDate.toLocaleDateString(locale, { month: 'short' });
    const yearNum = futureDate.getFullYear();
    const yr = Math.floor(tYears);
    const remM = Math.floor((tYears - yr) * 12);
    const offsetStr = yr > 0 && remM === 0 
      ? `+${yr}L` 
      : yr > 0 
      ? `+${yr}L ${remM}m` 
      : `+${remM}m`;
    const dateLabel = `${dayNum} ${monthName} ${yearNum} (${offsetStr})`;

    const profit = adjNetMedian - adjFutureCost;
    const futureReturnPct = adjFutureCost > 0 ? (profit / adjFutureCost) * 100 : 0;
    const returnP90 = adjFutureCost > 0 ? ((adjNetP90 - adjFutureCost) / adjFutureCost) * 100 : 0;
    const returnP10 = adjFutureCost > 0 ? ((adjNetP10 - adjFutureCost) / adjFutureCost) * 100 : 0;

    points.push({
      timestamp: futureTimestamp,
      dateLabel,
      valuation: Math.round(adjNetMedian),
      cost: Math.round(adjFutureCost),
      profit: Math.round(profit),
      returnPct: Number(futureReturnPct.toFixed(2)),
      returnP90: Number(returnP90.toFixed(2)),
      returnP10: Number(returnP10.toFixed(2)),
      isFuture: true,
      netMedian: Math.round(adjNetMedian),
      netP90: Math.round(adjNetP90),
      netP10: Math.round(adjNetP10),
      goalProbability,
      weightedTer: Number(weightedTer.toFixed(2)),
      weightedWhtDrag: Number(weightedWhtDrag.toFixed(2)),
      diversifiedVolatility: Number((portfolioEquityVol * 100).toFixed(1)),
    });
  }

  return points;
}
