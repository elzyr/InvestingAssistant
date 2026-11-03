import type { ChartPoint } from '../../../types/dashboard.ts';
import { CORRELATION_MATRIX, type EtfProfile } from '../../domain/assets.ts';
import { computeBelka } from '../../domain/tax.ts';
import type { UnifiedEngineParams, TimelineState } from './types.ts';

function normalCdf(x: number): number {
  const t = 1 / (1 + 0.2316419 * Math.abs(x));
  const d = 0.3989422804014337 * Math.exp((-x * x) / 2);
  const prob = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
  return x > 0 ? 1 - prob : prob;
}

export function appendProjection(
  points: ChartPoint[],
  state: TimelineState,
  params: UnifiedEngineParams
): void {
  const {
    now,
    stepDays,
    isProjectionActive,
    horizonYears,
    currentBondCapital,
    currentEquityCapital,
    currentBondCost,
    currentEquityCost,
    equityHoldings,
    userBondValSum,
    userBondWeightedRateSum,
  } = state;

  const {
    totalPortfolioCost,
    taxMode,
    monthlyDca = 2000,
    dcaBondShare = 0.5,
    expectedEtfReturn = 8.5,
    etfVolatility,
    empiricalTer,
    bondRollover = true,
    isIkeAccount = false,
    whtDrag = 0.25,
    inflationAdjustMode = 'NOMINAL',
    cpi,
    targetGoal = null,
    locale = 'pl-PL',
  } = params;

  if (!isProjectionActive) {
    return;
  }

  const targetFuturePoints = Math.max(1, Math.round((horizonYears * 365.25) / stepDays));
  const dtYears = horizonYears / targetFuturePoints;

  const weightedBondRateAnnual = userBondValSum > 0 ? userBondWeightedRateSum / userBondValSum : 0;

  let weightedTer = empiricalTer !== undefined ? empiricalTer : undefined;
  let weightedWhtDrag = whtDrag !== undefined ? whtDrag : undefined;
  let portfolioEquityVol = etfVolatility !== undefined ? etfVolatility / 100 : undefined;

  if (equityHoldings.length > 0 && currentEquityCapital > 0) {
    let sumTer = 0;
    let sumWht = 0;
    const weights = equityHoldings.map((h: { profile: EtfProfile; value: number }) => ({
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
            params.correlationMatrix?.[weights[i].profile.symbol]?.[weights[j].profile.symbol] ??
            CORRELATION_MATRIX[weights[i].profile.category]?.[weights[j].profile.category] ??
            (i === j ? 1.0 : 0.85);
          portVariance += wI * wJ * sigI * sigJ * rho;
        }
      }
      portfolioEquityVol = Math.sqrt(Math.max(0.0001, portVariance));
    }
  }

  const resolvedTer = weightedTer ?? 0;
  const resolvedWhtDrag = weightedWhtDrag ?? 0;
  const totalCostDrag = resolvedTer + resolvedWhtDrag;
  const effectiveEtfCagr = Math.max(0, expectedEtfReturn - totalCostDrag) / 100;
  const sigmaAnnual = portfolioEquityVol ?? 0;

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
    const bondBelka = computeBelka(bondProfitGross, taxMode !== 'NETTO' || isIkeAccount);
    const futureBondNet = futureBondVal - bondBelka;

    const stockMedianProfit = Math.max(0, futureStockValMedian - futureEquityCost);
    const stockP90Profit = Math.max(0, stockP90 - futureEquityCost);
    const stockP10Profit = Math.max(0, stockP10 - futureEquityCost);

    const belkaMedian = computeBelka(stockMedianProfit, taxMode !== 'NETTO' || isIkeAccount);
    const belkaP90 = computeBelka(stockP90Profit, taxMode !== 'NETTO' || isIkeAccount);
    const belkaP10 = computeBelka(stockP10Profit, taxMode !== 'NETTO' || isIkeAccount);

    const netMedian = futureBondNet + (futureStockValMedian - belkaMedian);
    const netP90 = futureBondNet + (stockP90 - belkaP90);
    const netP10 = futureBondNet + (stockP10 - belkaP10);
    const floorNet = Math.max(currentBondCapital, futureBondNet);

    const infDeflator = cpi != null ? Math.pow(1 + cpi / 100, tYears) : 1;
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
      weightedTer: weightedTer != null ? Number(weightedTer.toFixed(2)) : undefined,
      weightedWhtDrag: weightedWhtDrag != null ? Number(weightedWhtDrag.toFixed(2)) : undefined,
      diversifiedVolatility: portfolioEquityVol != null ? Number((portfolioEquityVol * 100).toFixed(1)) : undefined,
    });
  }
}
