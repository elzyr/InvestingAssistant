import type { ChartPoint } from '../../../types/dashboard.ts';
import { standardizeDate } from '../../domain/dates.ts';
import { getBondFamily, isBondItem } from '../../domain/assets.ts';
import { calculateBondCurrentPrice } from '../../domain/bondPricing.ts';
import { getHistoricalPrice } from '../../domain/prices.ts';
import { computeBelka } from '../../domain/tax.ts';
import type { UnifiedEngineParams, TimelineState, RawContributor } from './types.ts';

export function buildHistoryPoints(
  state: TimelineState,
  params: UnifiedEngineParams
): {
  points: ChartPoint[];
  rawStepContributors: Map<string, RawContributor>[];
  keyMetaMap: Map<string, { label: string; assetType: 'ETF' | 'BOND' | 'STOCK' | 'OTHER' }>;
} {
  const {
    now,
    startMs,
    stepMs,
    totalHistorySteps,
    preparedTxs,
    getBondValuation,
    posInstrumentCounts,
    currentBondCapital,
    currentEquityCapital,
    currentBondCost,
    currentEquityCost,
  } = state;
  const {
    positions,
    instruments = {},
    totalPortfolioValue,
    totalPortfolioCost,
    taxMode,
    cpi,
    rates,
    baseCurrency,
    instrumentPrices = {},
    isIkeAccount = false,
    historicalPrices = {},
    historicalSortedDates = {},
    locale = 'pl-PL',
  } = params;

  const points: ChartPoint[] = [];
  const rawStepContributors: Map<string, RawContributor>[] = [];
  const keyMetaMap = new Map<string, { label: string; assetType: 'ETF' | 'BOND' | 'STOCK' | 'OTHER' }>();

  for (let i = 0; i <= totalHistorySteps; i++) {
    const isToday = i === totalHistorySteps;
    const tMs = isToday ? now : Math.min(now - 1000, startMs + Math.round(i * stepMs));
    const dDay = new Date(tMs);
    const dayDateStr = standardizeDate(dDay);

    const holdings = new Map<string, {
      instrumentId: string;
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
        : `${tx.instrumentId}_${tx.currency}`;

      let h = holdings.get(key);
      if (!h) {
        h = {
          instrumentId: tx.instrumentId,
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

    const stepContribMap = new Map<string, {
      key: string;
      label: string;
      assetType: 'ETF' | 'BOND' | 'STOCK' | 'OTHER';
      valuation: number;
      cost: number;
      isBond: boolean;
      netProfit: number;
    }>();

    for (const h of holdings.values()) {
      if (h.currentQty <= 0) continue;
      if (h.isBond) {
        bondCostAtT += h.costBasis;
        let hVal = 0;
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
          const lotVal = lot.quantity * unitPrice * lot.fx;
          bondValAtT += lotVal;
          hVal += lotVal;
        }
        const bondKey = getBondFamily(h.lots[0]?.seriesName || h.symbol, h.lots[0]?.termMonths, h.lots[0]?.bondMargin);
        const bondAssetType: 'ETF' | 'BOND' | 'STOCK' | 'OTHER' = 'BOND';
        keyMetaMap.set(bondKey, { label: bondKey, assetType: bondAssetType });
        const existingBond = stepContribMap.get(bondKey);
        if (existingBond) {
          existingBond.valuation += hVal;
          existingBond.cost += h.costBasis;
        } else {
          stepContribMap.set(bondKey, {
            key: bondKey,
            label: bondKey,
            assetType: bondAssetType,
            valuation: hVal,
            cost: h.costBasis,
            isBond: true,
            netProfit: 0,
          });
        }
      } else {
        nonBondCostAtT += h.costBasis;
        const realClose = getHistoricalPrice(
          historicalPrices[h.symbol],
          historicalSortedDates[h.symbol],
          dayDateStr,
          h.fallbackPrice
        );
        const hVal = h.currentQty * realClose * h.fx;
        nonBondValAtT += hVal;
        const inst = instruments[h.instrumentId];
        const eqKey = inst?.symbol || h.symbol || h.instrumentId;
        const eqAssetType: 'ETF' | 'BOND' | 'STOCK' | 'OTHER' = inst?.type === 'STOCK' ? 'STOCK' : 'ETF';
        keyMetaMap.set(eqKey, { label: eqKey, assetType: eqAssetType });
        const existingEq = stepContribMap.get(eqKey);
        if (existingEq) {
          existingEq.valuation += hVal;
          existingEq.cost += h.costBasis;
        } else {
          stepContribMap.set(eqKey, {
            key: eqKey,
            label: eqKey,
            assetType: eqAssetType,
            valuation: hVal,
            cost: h.costBasis,
            isBond: false,
            netProfit: 0,
          });
        }
      }
    }

    let finalCost = bondCostAtT + nonBondCostAtT;
    let rawValuation = bondValAtT + nonBondValAtT;

    if (isToday) {
      if (positions.length > 0) {
        stepContribMap.clear();
        for (const pos of positions) {
          const inst = instruments[pos.instrumentId];
          const isBond = isBondItem(pos, inst);
          const fallbackFx =
            pos.totalCostBase != null && pos.quantity * pos.averageBuyPrice > 0
              ? pos.totalCostBase / (pos.quantity * pos.averageBuyPrice)
              : undefined;
          const fx = (pos.currency === baseCurrency ? 1 : (rates[pos.currency] || fallbackFx)) || 1;
          const canUseDirectPrice =
            instrumentPrices[pos.instrumentId] != null &&
            (!isBond || posInstrumentCounts.get(pos.instrumentId) === 1);
          const currentPrice = canUseDirectPrice
            ? instrumentPrices[pos.instrumentId]
            : isBond
            ? calculateBondCurrentPrice({
                purchaseDate: pos.purchaseDate,
                bondRate: pos.bondRate,
                bondMargin: pos.bondMargin,
                cpi,
                seriesName: pos.seriesName,
                termMonths: pos.termMonths,
              })
            : (instrumentPrices[pos.instrumentId] ?? pos.averageBuyPrice);
          const val = isBond && !canUseDirectPrice ? getBondValuation(pos, tMs, fx) : pos.quantity * currentPrice * fx;
          const cost = pos.totalCostBase ?? (pos.quantity * pos.averageBuyPrice * fx);
          const key = isBond
            ? getBondFamily(pos.seriesName || inst?.symbol, pos.termMonths, pos.bondMargin)
            : (inst?.symbol || pos.seriesName || pos.instrumentId);
          const assetType: 'ETF' | 'BOND' | 'STOCK' | 'OTHER' = isBond
            ? 'BOND'
            : (inst?.type === 'STOCK' ? 'STOCK' : 'ETF');
          keyMetaMap.set(key, { label: key, assetType });
          const existing = stepContribMap.get(key);
          if (existing) {
            existing.valuation += val;
            existing.cost += cost;
          } else {
            stepContribMap.set(key, {
              key,
              label: key,
              assetType,
              valuation: val,
              cost,
              isBond,
              netProfit: 0,
            });
          }
        }
      }
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

      let sumContribVal = 0;
      let sumContribCost = 0;
      for (const c of stepContribMap.values()) {
        sumContribVal += c.valuation;
        sumContribCost += c.cost;
      }
      if (sumContribVal > 0 && rawValuation > 0 && Math.abs(sumContribVal - rawValuation) > 1e-4) {
        const ratio = rawValuation / sumContribVal;
        for (const c of stepContribMap.values()) {
          c.valuation *= ratio;
        }
      }
      if (sumContribCost > 0 && finalCost > 0 && Math.abs(sumContribCost - finalCost) > 1e-4) {
        const ratio = finalCost / sumContribCost;
        for (const c of stepContribMap.values()) {
          c.cost *= ratio;
        }
      }
    }

    const bondProfit = Math.max(0, bondValAtT - bondCostAtT);
    const nonBondProfit = Math.max(0, nonBondValAtT - nonBondCostAtT);
    const totalProfitGross = rawValuation - finalCost;

    let profit = totalProfitGross;
    let valuation = rawValuation;

    if (taxMode === 'NETTO') {
      const bondBelka = computeBelka(bondProfit, isIkeAccount);
      const nonBondBelka = computeBelka(nonBondProfit, isIkeAccount);
      const totalBelka = bondBelka + nonBondBelka;

      profit = totalProfitGross - totalBelka;
      valuation = rawValuation - totalBelka;
    }

    const bondBelkaAmt = computeBelka(bondProfit, taxMode !== 'NETTO' || isIkeAccount);
    const nonBondBelkaAmt = computeBelka(nonBondProfit, taxMode !== 'NETTO' || isIkeAccount);

    let sumPosGrossBond = 0;
    let sumPosGrossNonBond = 0;
    for (const c of stepContribMap.values()) {
      const g = c.valuation - c.cost;
      if (g > 0) {
        if (c.isBond) sumPosGrossBond += g;
        else sumPosGrossNonBond += g;
      }
    }

    for (const c of stepContribMap.values()) {
      const g = c.valuation - c.cost;
      let belka = 0;
      if (taxMode === 'NETTO' && !isIkeAccount && g > 0) {
        if (c.isBond && sumPosGrossBond > 0) {
          belka = bondBelkaAmt * (g / sumPosGrossBond);
        } else if (!c.isBond && sumPosGrossNonBond > 0) {
          belka = nonBondBelkaAmt * (g / sumPosGrossNonBond);
        }
      }
      c.netProfit = g - belka;
    }

    rawStepContributors.push(stepContribMap);

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

  return { points, rawStepContributors, keyMetaMap };
}
